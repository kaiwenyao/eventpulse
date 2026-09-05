package dev.kaiwen.eventpulse.controller;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.kaiwen.eventpulse.common.BaseContext;
import dev.kaiwen.eventpulse.common.Result;
import dev.kaiwen.eventpulse.domain.EventStatus;
import dev.kaiwen.eventpulse.dto.AiDtos.ToolEventVo;
import dev.kaiwen.eventpulse.dto.AiDtos.ToolNearbyRequest;
import dev.kaiwen.eventpulse.dto.AiDtos.ToolPreferenceVo;
import dev.kaiwen.eventpulse.dto.AiDtos.ToolSearchRequest;
import dev.kaiwen.eventpulse.entity.Event;
import dev.kaiwen.eventpulse.exception.BusinessException;
import dev.kaiwen.eventpulse.repository.EventRepository;
import dev.kaiwen.eventpulse.repository.InteractionRepository;
import dev.kaiwen.eventpulse.repository.UserPreferenceRepository;
import dev.kaiwen.eventpulse.service.EventService;
import dev.kaiwen.eventpulse.service.PlatformService;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Python AI 服务专用只读工具接口（/internal/ai-tools/**，见
 * InternalServiceInterceptor 的服务认证）。
 *
 * 数据访问仍然走 EventService / PlatformService 的同一套规则：只返回公开
 * 活动并重新核对状态与关键字段。没有任意 SQL、没有写工具，工具能查到的
 * 最多 20 条；userId 只来自签名的用户上下文，不来自请求参数。
 */
@RestController
@Profile("api")
@RequestMapping("/internal/ai-tools")
public class InternalAiToolsController {

    static final int MAX_TOOL_RESULTS = 20;
    static final double MAX_NEARBY_RADIUS_KM = 100;

    private final EventService eventService;
    private final PlatformService platformService;
    private final EventRepository events;
    private final UserPreferenceRepository preferences;
    private final InteractionRepository interactions;
    private final MeterRegistry meterRegistry;

    public InternalAiToolsController(EventService eventService, PlatformService platformService,
            EventRepository events, UserPreferenceRepository preferences, InteractionRepository interactions,
            MeterRegistry meterRegistry) {
        this.eventService = eventService;
        this.platformService = platformService;
        this.events = events;
        this.preferences = preferences;
        this.interactions = interactions;
        this.meterRegistry = meterRegistry;
    }

    @PostMapping("/events/search")
    public Result<List<ToolEventVo>> search(@RequestBody ToolSearchRequest request) {
        count("search_published_events");
        int limit = cap(request.limit());
        // hasRemaining=true 常见于“还能买票”的提问；null 时不过滤。
        List<ToolEventVo> found = eventService.search(
                request.city(), request.category(), request.q(),
                request.dateFrom(), request.dateTo(),
                request.minPriceCents(), request.maxPriceCents(),
                request.hasRemaining(), "startsAt", false, 0, limit)
                .getRecords().stream()
                .map(this::toToolVo)
                .toList();
        return Result.success(found);
    }

    @GetMapping("/events/{id}")
    public Result<ToolEventVo> details(@PathVariable Long id) {
        count("get_event_details");
        Event event = events.findById(id).orElseThrow(() -> BusinessException.notFound("Event not found"));
        if (!EventStatus.PUBLIC_LIST.contains(event.getStatus())) {
            // 草稿 / 已取消 / 已归档对 AI 用户不存在。
            throw BusinessException.notFound("Event not found");
        }
        return Result.success(toToolVo(eventService.toVo(event)));
    }

    @PostMapping("/events/nearby")
    public Result<List<ToolEventVo>> nearby(@RequestBody ToolNearbyRequest request) {
        count("find_nearby_events");
        if (request.lat() == null || request.lng() == null) {
            throw new BusinessException("lat and lng are required");
        }
        double radius = Math.min(request.radiusKm() == null ? 20 : request.radiusKm(), MAX_NEARBY_RADIUS_KM);
        int limit = cap(request.limit());
        return Result.success(platformService.nearby(request.lat(), request.lng(), radius).stream()
                .limit(limit)
                .map(this::toToolVo)
                .toList());
    }

    @GetMapping("/events/popular")
    public Result<List<ToolEventVo>> popular(@RequestParam(required = false) Integer limit) {
        count("get_popular_events");
        // cap(Integer) 的三元参数会经历拆箱再装箱（BX_UNBOXING_IMMEDIATELY_REBOXED）；
        // 先判空再取 cap(int) 即可全程 int，语义不变（null -> 8 条默认值）。
        int capped = limit == null ? 8 : cap(limit);
        return Result.success(platformService.popular().stream()
                .limit(capped)
                .map(this::toToolVo)
                .toList());
    }

    @GetMapping("/users/me/preferences")
    public Result<ToolPreferenceVo> myPreferences() {
        count("get_my_preferences");
        Long userId = requireContextUser();
        return preferences.findById(userId)
                .map(pref -> Result.success(new ToolPreferenceVo(
                        pref.getCategories(), pref.getCities(),
                        pref.getLatitude(), pref.getLongitude(), pref.getRadiusKm())))
                .orElseGet(() -> Result.success(new ToolPreferenceVo(null, null, null, null, null)));
    }

    @GetMapping("/users/me/recent-categories")
    public Result<List<dev.kaiwen.eventpulse.dto.AiDtos.ToolCategoryCount>> myRecentCategories() {
        count("get_my_recent_categories");
        Long userId = requireContextUser();
        // 只汇总类别，不返回完整浏览历史；数量上限防止刷历史放大上下文。
        List<Long> eventIds = interactions.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(i -> i.getEventId())
                .distinct()
                .limit(100)
                .toList();
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Event event : events.findAllById(eventIds)) {
            counts.merge(event.getCategory(), 1L, Long::sum);
        }
        List<dev.kaiwen.eventpulse.dto.AiDtos.ToolCategoryCount> top = counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .limit(10)
                .map(e -> new dev.kaiwen.eventpulse.dto.AiDtos.ToolCategoryCount(e.getKey(), e.getValue()))
                .toList();
        return Result.success(top);
    }

    private static Long requireContextUser() {
        Long userId = BaseContext.getUserId();
        if (userId == null) {
            throw BusinessException.forbidden("This tool requires a signed-in user context");
        }
        return userId;
    }

    private static int cap(Integer limit) {
        if (limit == null || limit <= 0) {
            return 10;
        }
        return Math.min(limit, MAX_TOOL_RESULTS);
    }

    /** 活动可见性以 EventService 的公开列表规则为准；工具视图重新核对关键字段。 */
    private ToolEventVo toToolVo(dev.kaiwen.eventpulse.dto.EventDtos.EventVo vo) {
        return new ToolEventVo(
                vo.id(),
                vo.title(),
                vo.summary(),
                vo.description() == null ? null : vo.description(),
                vo.category(),
                vo.city(),
                vo.venueName(),
                vo.address(),
                vo.latitude(),
                vo.longitude(),
                vo.startsAt(),
                vo.endsAt(),
                vo.priceCents(),
                vo.remaining(),
                vo.status());
    }

    private void count(String tool) {
        meterRegistry.counter("ai.tool.calls", "tool", tool).increment();
    }
}
