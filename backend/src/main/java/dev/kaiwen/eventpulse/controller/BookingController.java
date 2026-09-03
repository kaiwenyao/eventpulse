package dev.kaiwen.eventpulse.controller;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.kaiwen.eventpulse.common.PageResult;
import dev.kaiwen.eventpulse.common.Result;
import dev.kaiwen.eventpulse.dto.BookingDtos.BookingVo;
import dev.kaiwen.eventpulse.dto.BookingDtos.CreateBookingRequest;
import dev.kaiwen.eventpulse.service.BookingService;

import jakarta.validation.Valid;
import java.util.List;

@RestController
@Profile("api")
@RequestMapping("/api/bookings")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    /**
     * 直接下单。可选的 Idempotency-Key 请求头提供服务端持久化幂等：
     * 网络重试 / 重复点击不会产生重复订单或重复扣款，已成功的重试返回原订单。
     */
    @PostMapping
    public Result<BookingVo> create(@Valid @RequestBody CreateBookingRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return Result.success(bookingService.create(request, idempotencyKey));
    }

    /**
     * 历史订单（服务端分页）。兼容策略：本接口从「全量数组」改为 PageResult 分页
     * 结构，前端与测试同步更新（同仓库同版本部署，无滚动兼容窗口）。
     * status: CONFIRMED / CANCELLED（缺省 = 全部）；from/to: ISO-8601 时间范围；
     * q: 订单号或活动名称搜索。
     */
    @GetMapping
    public Result<PageResult<BookingVo>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return Result.success(bookingService.listMine(status, from, to, q, page, size));
    }

    @GetMapping("/{id}")
    public Result<BookingVo> get(@PathVariable Long id) {
        return Result.success(bookingService.get(id));
    }

    @PostMapping("/{id}/cancel")
    public Result<BookingVo> cancel(@PathVariable Long id) {
        return Result.success(bookingService.cancel(id));
    }

    @GetMapping("/{id}/tickets")
    public Result<List<dev.kaiwen.eventpulse.service.TicketService.TicketView>> tickets(@PathVariable Long id) {
        return Result.success(bookingService.tickets(id));
    }
}
