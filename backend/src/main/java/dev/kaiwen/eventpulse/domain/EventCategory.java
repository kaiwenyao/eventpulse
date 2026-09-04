package dev.kaiwen.eventpulse.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import dev.kaiwen.eventpulse.exception.BusinessException;

/**
 * 活动分类白名单。
 *
 * <p>分类曾经是自由文本，主办方可以随手填 {@code 工作坊}、{@code 音乐}、{@code Music}，
 * 结果是同一个意思散成好几个孤岛：详情页显示正常，但发现页的筛选用的是精确匹配
 * （见 {@code EventService#search}），这些活动永远搜不出来。所以分类改成固定集合，
 * 在 DTO 校验、写入归一化、数据库 CHECK 约束三层同时封死。
 *
 * <p>写成常量类而不是 Java enum，是为了和同目录的 {@link EventStatus} 保持一致，
 * 也免掉一个只为把大写枚举名映射回小写 slug 而存在的 JPA converter。
 *
 * <p>前端 {@code frontend/src/types.ts} 的 {@code CATEGORIES} 镜像这份清单，
 * 迁移 {@code V4__event_category_whitelist.sql} 的 CHECK 约束同理；三处要一起改。
 */
public final class EventCategory {

    public static final String MUSIC = "music";
    public static final String TECH = "tech";
    public static final String SPORTS = "sports";
    public static final String ART = "art";
    public static final String FOOD = "food";
    public static final String BUSINESS = "business";
    public static final String COMMUNITY = "community";
    /** 兜底分类：迁移时收纳无法归类的存量数据，也是主办方合法的「以上都不是」选项。 */
    public static final String OTHER = "other";

    /** 用 List 而不是 Set，保证前后端下拉框的顺序一致。 */
    public static final List<String> ALL =
            List.of(MUSIC, TECH, SPORTS, ART, FOOD, BUSINESS, COMMUNITY, OTHER);

    private EventCategory() {
    }

    /** 大小写与首尾空格不敏感的白名单判断。{@code null} 与空串一律不合法。 */
    public static boolean isValid(String raw) {
        return raw != null && ALL.contains(slug(raw));
    }

    /**
     * 归一化成入库形态：{@code " Music "} → {@code "music"}。
     *
     * @throws BusinessException 值不在白名单内。DTO 上的 {@code @ValidEventCategory}
     *         已经挡过一道，这里是给绕过校验的内部调用兜底。
     */
    public static String normalise(String raw) {
        String slug = raw == null ? "" : slug(raw);
        if (!ALL.contains(slug)) {
            throw new BusinessException("Unknown category: " + raw + ". Allowed: " + String.join(", ", ALL));
        }
        return slug;
    }

    /**
     * 把逗号分隔的偏好分类串过滤成只剩合法值，顺序与去重按输入先后保留。
     * 用户偏好是软推荐信号，没必要因为混进一个脏值就整串拒绝。
     */
    public static String filterCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return csv;
        }
        List<String> kept = Arrays.stream(csv.split(","))
                .map(EventCategory::slug)
                .filter(ALL::contains)
                .distinct()
                .toList();
        return kept.isEmpty() ? null : String.join(",", kept);
    }

    private static String slug(String raw) {
        return raw.trim().toLowerCase(Locale.ROOT);
    }
}
