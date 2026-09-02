package dev.kaiwen.eventpulse.seed;

import java.util.List;

/**
 * 演示数据目录：只有常量和纯函数，没有持久化逻辑。
 *
 * 想改 demo 的账号、活动或订单，只改这个文件；写库的顺序与外键关系由
 * {@link DemoDataSeeder} 和 {@link DemoEngagementSeeder} 负责。
 *
 * 所有时间都写成「相对现在的小时数」，负数表示过去，这样无论什么时候
 * 启动 demo，活动的生命周期（未开始 / 进行中 / 已结束）都保持一致。
 */
final class DemoCatalog {

    /** 活动场地。多个活动共用同一个场地，坐标用于「附近的活动」。 */
    record Venue(String city, String name, String address, double latitude, double longitude) {
    }

    /** 演示账号。walletCents 是初始余额，实际入库时会先扣掉已确认订单的金额。 */
    record UserSpec(String email, String rawPassword, String name, String role, long walletCents) {
    }

    /**
     * 演示活动。startOffsetHours 为负表示已经开始，status 直接写死，
     * 与 startOffsetHours / durationHours 保持自洽（PlatformService 的生命周期
     * 定时任务不会把它们改成别的状态）。
     */
    record EventSpec(
            String title,
            String summary,
            String description,
            String category,
            Venue venue,
            int startOffsetHours,
            int durationHours,
            int priceCents,
            int capacity,
            int baseSold,
            String status,
            int organiserIndex,
            String cancellationReason) {
    }

    /** 演示订单。eventIndex 指向 {@link #EVENTS} 的下标，checkedIn 是已核销的票数。 */
    record BookingSpec(
            String userEmail,
            int eventIndex,
            int quantity,
            String status,
            int createdOffsetHours,
            int checkedIn) {
    }

    /** 收藏关系：谁收藏了哪个活动。 */
    record FavouriteSpec(String userEmail, int eventIndex) {
    }

    /** 用户偏好，决定「为你推荐」的排序权重。 */
    record PreferenceSpec(
            String userEmail, String categories, String cities,
            Double latitude, Double longitude, Double radiusKm) {
    }

    static final Venue LIVEHOUSE = new Venue("上海", "声空间 LiveHouse", "上海市黄浦区建国中路 8 号 3 号楼", 31.2020, 121.4670);
    static final Venue THINK_TANK = new Venue("上海", "西岸智库", "上海市徐汇区龙腾大道 2555 号", 31.1770, 121.4560);
    static final Venue RIVERSIDE = new Venue("上海", "徐汇滨江跑道", "上海市徐汇区龙腾大道滨江绿地", 31.1830, 121.4610);
    static final Venue SUZHOU_CREEK = new Venue("上海", "苏州河滨水步道", "上海市普陀区光复西路 1155 号", 31.2400, 121.4230);
    static final Venue CRAFT_LOFT = new Venue("上海", "愚园路手作工坊", "上海市静安区愚园路 749 号", 31.2240, 121.4400);
    static final Venue OLD_TOWN = new Venue("上海", "豫园老城厢", "上海市黄浦区安仁街 132 号", 31.2270, 121.4920);
    static final Venue GALLERY_798 = new Venue("北京", "798 星丛美术馆", "北京市朝阳区酒仙桥路 4 号", 39.9840, 116.4950);
    static final Venue WEST_LAKE = new Venue("杭州", "西湖畔草坪剧场", "浙江省杭州市西湖区北山街 78 号", 30.2560, 120.1450);
    static final Venue MAKER_PLAZA = new Venue("深圳", "南山科创广场", "广东省深圳市南山区科苑南路 2888 号", 22.5310, 113.9440);
    static final Venue FILM_ALLEY = new Venue("成都", "玉林路旧巷", "四川省成都市武侯区玉林西路 55 号", 30.6350, 104.0620);
    static final Venue WAREHOUSE = new Venue("广州", "珠江仓库", "广东省广州市海珠区阅江西路 222 号", 23.1010, 113.3240);

    /** 索引 0 是 README 里给出的普通用户，1 是主办方；后面是让列表更真实的陪衬账号。 */
    static final List<UserSpec> USERS = List.of(
            new UserSpec("user@eventpulse.dev", "User123456", "演示用户", "USER", 88800),
            new UserSpec("organiser@eventpulse.dev", "Organiser123456", "演示主办方", "ORGANISER", 88800),
            new UserSpec("studio@eventpulse.dev", "Organiser123456", "声浪现场", "ORGANISER", 50000),
            new UserSpec("guild@eventpulse.dev", "Organiser123456", "城市漫游者", "ORGANISER", 50000),
            new UserSpec("lin@eventpulse.dev", "User123456", "林可可", "USER", 120000),
            new UserSpec("zhao@eventpulse.dev", "User123456", "赵一鸣", "USER", 96000),
            new UserSpec("chen@eventpulse.dev", "User123456", "陈思远", "USER", 150000),
            new UserSpec("wang@eventpulse.dev", "User123456", "王雨桐", "USER", 60000));

    /** 主办方账号在 {@link #USERS} 里的下标，供 {@link EventSpec#organiserIndex} 使用。 */
    static final List<Integer> ORGANISER_INDEXES = List.of(1, 2, 3);

    /** 每个主办方的联系方式，跟着 {@link #ORGANISER_INDEXES} 的顺序。 */
    static final List<String> ORGANISER_CONTACTS = List.of(
            "演示主办方 · demo@eventpulse.dev · 021-0000 0000",
            "声浪现场 · studio@eventpulse.dev · 020-0000 0000",
            "城市漫游者 · guild@eventpulse.dev · 028-0000 0000");

    private static final int DAY = 24;

    static final List<EventSpec> EVENTS = List.of(
            new EventSpec("城市脉搏 · 独立摇滚之夜",
                    "六组本地乐队接力开唱，从后朋到数学摇滚。",
                    "六组上海本地独立乐队轮番登场，横跨后朋克、数学摇滚与迷幻民谣。现场配备全套 Meyer 音响，"
                            + "开演前一小时开放黑胶市集与乐队周边摊位。",
                    "music", LIVEHOUSE, 14 * DAY, 3, 18000, 300, 168, "PUBLISHED", 0, null),
            new EventSpec("AI 与城市生活 · 技术沙龙",
                    "四位一线工程师聊模型落地的真实成本。",
                    "四场 20 分钟闪电演讲 + 一场圆桌：推荐系统的冷启动、端侧推理的功耗账、"
                            + "以及把大模型接进老系统时踩过的坑。结束后有自由交流环节。",
                    "tech", THINK_TANK, 9 * DAY, 3, 4900, 120, 74, "PUBLISHED", 0, null),
            new EventSpec("滨江晨跑 5K",
                    "沿江 5 公里慢跑，配速分组，跑完有早餐。",
                    "按 5'30\"、6'30\"、7'30\" 三档配速分组出发，全程有领跑员和收尾员。终点提供咖啡和三明治，"
                            + "寄存点在起跑拱门旁边。",
                    "sports", RIVERSIDE, 5 * DAY, 2, 0, 200, 96, "PUBLISHED", 0, null),
            new EventSpec("城市光影 · 数字艺术展",
                    "十二组沉浸式装置，夜场延长至 22:00。",
                    "十二组来自国内外的数字艺术装置，围绕「城市与记忆」展开。含三件需要预约体验的交互作品，"
                            + "现场每晚 19:30 有策展人导览。",
                    "art", GALLERY_798, 21 * DAY, 8, 8800, 500, 212, "PUBLISHED", 0, null),
            new EventSpec("深夜爵士 · 三重奏",
                    "钢琴、贝斯与鼓的即兴之夜，仅 60 座。",
                    "标准爵士三重奏编制，上下半场各 45 分钟，中场休息 15 分钟。座位为自由入座，"
                            + "建议提前 30 分钟入场。",
                    "music", LIVEHOUSE, 3 * DAY, 3, 12800, 60, 56, "PUBLISHED", 0, null),
            new EventSpec("云原生可观测性工作坊",
                    "自带电脑，动手搭一套完整的 tracing 链路。",
                    "从 OpenTelemetry SDK 接入到采样策略调优，全程动手。请自带能跑 Docker 的笔记本，"
                            + "课前会发一份环境准备清单。",
                    "tech", THINK_TANK, 12 * DAY, 6, 29900, 60, 31, "PUBLISHED", 0, null),
            new EventSpec("城市骑行 · 苏州河夜骑",
                    "20 公里夜骑，沿苏州河穿过八座桥。",
                    "20 公里休闲配速，沿苏州河一路向西再折返，途经八座风格各异的桥。需自备车辆与头盔，"
                            + "队伍前后各有一名领骑。",
                    "sports", SUZHOU_CREEK, 7 * DAY, 3, 3900, 150, 88, "PUBLISHED", 0, null),
            new EventSpec("陶艺工坊 · 手作一日",
                    "从拉坯到上釉，带走两件自己的作品。",
                    "上午拉坯、下午修坯上釉，作品烧制后邮寄到家。材料、围裙和工具全部提供，零基础可参加，"
                            + "每桌配一名助教。",
                    "art", CRAFT_LOFT, 10 * DAY, 5, 26800, 24, 19, "PUBLISHED", 0, null),
            new EventSpec("民谣露天场 · 西湖边",
                    "湖畔草坪，自带野餐垫，日落时开唱。",
                    "四组民谣音乐人的露天演出，草坪自由入座，欢迎自带野餐垫和折叠椅。雨天顺延一周，"
                            + "顺延通知会通过站内消息发送。",
                    "music", WEST_LAKE, 18 * DAY, 3, 15800, 400, 156, "PUBLISHED", 1, null),
            new EventSpec("开源硬件市集",
                    "四十个摊位，免费入场，现场可焊可玩。",
                    "四十个开源硬件团队摆摊，从键盘、示波器到农业传感器。设有焊接体验区和二手器材交换角，"
                            + "免费入场但需要预约名额。",
                    "tech", MAKER_PLAZA, 16 * DAY, 8, 0, 800, 421, "PUBLISHED", 1, null),
            new EventSpec("城市定向赛 · 老城厢",
                    "三人一队，在老城厢里解谜打卡。",
                    "三人一队，四小时内完成十二个打卡点的解谜任务。路线全程步行可达，终点在豫园九曲桥，"
                            + "前三名有城市手绘地图作为奖品。",
                    "sports", OLD_TOWN, 25 * DAY, 4, 5900, 300, 64, "PUBLISHED", 2, null),
            new EventSpec("胶片摄影漫游",
                    "带上胶片机，跟摄影师扫街四小时。",
                    "由本地摄影师带队，沿玉林片区扫街四小时，讲解构图与曝光。可租借胶片机，"
                            + "活动结束后统一冲扫并线上分享。",
                    "art", FILM_ALLEY, 11 * DAY, 4, 12800, 40, 27, "PUBLISHED", 2, null),
            new EventSpec("电子音乐现场 · 仓库场",
                    "六小时不间断，三组现场电子演出。",
                    "老仓库改造的现场空间，三组以硬件设备演出的电子音乐人接力六小时。现场有耳塞发放，"
                            + "22:00 后禁止再入场。",
                    "music", WAREHOUSE, -2, 6, 19800, 350, 318, "ONGOING", 1, null),
            new EventSpec("产品设计闭门会",
                    "40 人闭门讨论，不录音不直播。",
                    "围绕「AI 功能如何不破坏既有工作流」的闭门讨论，遵循 Chatham House 规则，"
                            + "不录音、不直播、不留存发言人姓名。",
                    "tech", THINK_TANK, -3, 5, 19900, 40, 38, "ONGOING", 0, null),
            new EventSpec("春季马拉松 · 城市半程",
                    "21.0975 公里，穿过外滩与滨江。",
                    "半程马拉松，关门时间 3 小时。赛道沿黄浦江布置六个补给站，完赛者可领取奖牌与完赛毛巾。",
                    "sports", RIVERSIDE, -30 * DAY, 5, 12000, 1000, 964, "FINISHED", 0, null),
            new EventSpec("当代雕塑巡展 · 首场",
                    "三十件当代雕塑，巡展的第一站。",
                    "巡展首站展出三十件当代雕塑作品，覆盖金属、陶土与混合材料。每周六下午有艺术家现场答问。",
                    "art", GALLERY_798, -60 * DAY, 8, 6800, 300, 287, "FINISHED", 0, null),
            new EventSpec("秋日音乐节 · 江畔场",
                    "两天三舞台的户外音乐节。",
                    "原计划两天三舞台、二十四组演出的江畔音乐节。因场地施工许可未获批，全场取消并全额退款。",
                    "music", WAREHOUSE, 30 * DAY, 10, 38000, 1200, 246, "CANCELLED", 1,
                    "场地施工许可未获批，全场取消并全额退款"),
            new EventSpec("城市漫谈 · 建筑与记忆",
                    "还在筹备中的城市建筑漫谈。",
                    "计划邀请三位建筑师聊上海里弄的更新与保留，场地与时间仍在确认，尚未对外发布。",
                    "art", CRAFT_LOFT, 45 * DAY, 3, 9800, 60, 0, "DRAFT", 0, null),
            new EventSpec("老唱片交换会",
                    "带一张来，换一张走。",
                    "自带黑胶或磁带来交换，现场有唱机可以试听。往期活动，已归档留档。",
                    "music", FILM_ALLEY, -90 * DAY, 4, 3000, 80, 72, "ARCHIVED", 0, null));

    /**
     * 演示订单。已确认的订单会占用库存并从钱包扣款；已取消的订单只留记录，
     * 不占库存也不扣款（真实流程里下单扣款、取消退款，净额为零）。
     */
    static final List<BookingSpec> BOOKINGS = List.of(
            new BookingSpec("user@eventpulse.dev", 0, 2, "CONFIRMED", -5 * DAY, 0),
            new BookingSpec("user@eventpulse.dev", 1, 1, "CONFIRMED", -3 * DAY, 0),
            new BookingSpec("user@eventpulse.dev", 2, 1, "CONFIRMED", -2 * DAY, 0),
            new BookingSpec("user@eventpulse.dev", 14, 1, "CONFIRMED", -40 * DAY, 1),
            new BookingSpec("user@eventpulse.dev", 3, 2, "CANCELLED", -6 * DAY, 0),
            new BookingSpec("lin@eventpulse.dev", 0, 1, "CONFIRMED", -4 * DAY, 0),
            new BookingSpec("lin@eventpulse.dev", 8, 2, "CONFIRMED", -8 * DAY, 0),
            new BookingSpec("lin@eventpulse.dev", 15, 1, "CONFIRMED", -70 * DAY, 1),
            new BookingSpec("zhao@eventpulse.dev", 5, 1, "CONFIRMED", -9 * DAY, 0),
            new BookingSpec("zhao@eventpulse.dev", 6, 2, "CONFIRMED", -1 * DAY, 0),
            new BookingSpec("zhao@eventpulse.dev", 13, 1, "CONFIRMED", -11 * DAY, 1),
            new BookingSpec("chen@eventpulse.dev", 3, 4, "CONFIRMED", -7 * DAY, 0),
            new BookingSpec("chen@eventpulse.dev", 9, 2, "CONFIRMED", -12 * DAY, 0),
            new BookingSpec("chen@eventpulse.dev", 11, 1, "CONFIRMED", -2 * DAY, 0),
            new BookingSpec("chen@eventpulse.dev", 15, 2, "CONFIRMED", -75 * DAY, 2),
            new BookingSpec("wang@eventpulse.dev", 1, 2, "CONFIRMED", -3 * DAY, 0),
            new BookingSpec("wang@eventpulse.dev", 7, 1, "CONFIRMED", -6 * DAY, 0),
            new BookingSpec("wang@eventpulse.dev", 10, 1, "CANCELLED", -5 * DAY, 0));

    static final List<FavouriteSpec> FAVOURITES = List.of(
            new FavouriteSpec("user@eventpulse.dev", 0),
            new FavouriteSpec("user@eventpulse.dev", 3),
            new FavouriteSpec("user@eventpulse.dev", 5),
            new FavouriteSpec("user@eventpulse.dev", 8),
            new FavouriteSpec("lin@eventpulse.dev", 0),
            new FavouriteSpec("lin@eventpulse.dev", 12),
            new FavouriteSpec("zhao@eventpulse.dev", 1),
            new FavouriteSpec("zhao@eventpulse.dev", 5),
            new FavouriteSpec("chen@eventpulse.dev", 3),
            new FavouriteSpec("chen@eventpulse.dev", 15),
            new FavouriteSpec("wang@eventpulse.dev", 7),
            new FavouriteSpec("wang@eventpulse.dev", 11));

    static final List<PreferenceSpec> PREFERENCES = List.of(
            new PreferenceSpec("user@eventpulse.dev", "music,art", "上海,北京", 31.2020, 121.4670, 25.0),
            new PreferenceSpec("lin@eventpulse.dev", "music", "上海,杭州", 31.2240, 121.4400, 15.0),
            new PreferenceSpec("zhao@eventpulse.dev", "tech,sports", "上海", 31.1770, 121.4560, 30.0),
            new PreferenceSpec("chen@eventpulse.dev", "art,tech", "北京,深圳", 39.9840, 116.4950, 20.0));

    /** 分类对应的入场提示，避免每个活动都手写一遍相同的说明。 */
    static String attendanceNotes(String category) {
        return switch (category) {
            case "music" -> "凭电子票二维码入场，一人一票，谢绝 1.2 米以下儿童入场。";
            case "tech" -> "请携带可上网的笔记本电脑，现场提供电源和 Wi-Fi。";
            case "sports" -> "请穿着运动鞋并提前 30 分钟到场检录，雨天照常进行。";
            default -> "凭电子票入场，展区内可拍照但请勿使用闪光灯与三脚架。";
        };
    }

    /** 某个账号在演示订单里实际花掉的钱（只算已确认的订单），用于计算初始余额。 */
    static long spentCents(String email) {
        return BOOKINGS.stream()
                .filter(booking -> booking.userEmail().equals(email))
                .filter(booking -> "CONFIRMED".equals(booking.status()))
                .mapToLong(booking -> (long) EVENTS.get(booking.eventIndex()).priceCents() * booking.quantity())
                .sum();
    }

    /** 活动最终售出票数：底数（非演示账号的销量）加上演示订单里已确认的票。 */
    static int soldFor(int eventIndex) {
        int booked = BOOKINGS.stream()
                .filter(booking -> booking.eventIndex() == eventIndex)
                .filter(booking -> "CONFIRMED".equals(booking.status()))
                .mapToInt(BookingSpec::quantity)
                .sum();
        return Math.min(EVENTS.get(eventIndex).capacity(), EVENTS.get(eventIndex).baseSold() + booked);
    }

    private DemoCatalog() {
    }
}
