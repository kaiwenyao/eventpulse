package dev.kaiwen.eventpulse.seed;

import java.util.List;

import dev.kaiwen.eventpulse.domain.EventCategory;

/**
 * 演示数据目录：只有常量和纯函数，没有持久化逻辑。
 *
 * 想改 demo 的账号、活动或订单，只改这个文件；写库的顺序与外键关系由
 * {@link DemoDataSeeder} 和 {@link DemoEngagementSeeder} 负责。
 *
 * 所有时间都写成「相对现在的小时数」，负数表示过去，这样无论什么时候
 * 启动 demo，活动的生命周期（未开始 / 进行中 / 已结束）都保持一致。
 *
 * 场地刻意分布在五个大洲的六座城市：柏林是主场（多个场地聚在一起，
 * 「附近的活动」才有结果），其余城市各一到两个场地，用来演示城市筛选。
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

    /**
     * 演示活动封面。下标与 {@link #EVENTS} 一一对应：第 N 个活动用第 N 张图，
     * 图片按 EVENTS 顺序生成后预传到对象存储，key 固定为 seed/demo-covers/NN.jpeg
     * （NN 为活动的 1-based 序号，补零两位），内容类型统一 image/jpeg。
     * sizeBytes 是上传时的真实字节数，media_assets.size_bytes 非空需要它；
     * S3 上对象缺失时封面会 404，重新上传同名 key 即可修复。
     */
    record CoverSpec(String storageKey, long sizeBytes) {
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

    // 主场：柏林。六个场地集中在市区，半径 25km 的「附近」查询能一次覆盖。
    static final Venue LIVEHOUSE = new Venue("Berlin", "Kreuzberg Sound Space", "Skalitzer Strasse 134, Kreuzberg, 10999 Berlin", 52.4995, 13.4300);
    static final Venue THINK_TANK = new Venue("Berlin", "Spreeufer Think Tank", "Spreeufer 5, Mitte, 10178 Berlin", 52.5185, 13.4020);
    static final Venue RIVERSIDE = new Venue("Berlin", "Treptower Riverside Track", "Puschkinallee 76, Treptow, 12435 Berlin", 52.4930, 13.4690);
    static final Venue CANAL_WALK = new Venue("Berlin", "Landwehr Canal Promenade", "Paul-Lincke-Ufer 21, Kreuzberg, 10999 Berlin", 52.4945, 13.4270);
    static final Venue CRAFT_LOFT = new Venue("Berlin", "Prenzlauer Berg Craft Studio", "Kastanienallee 49, Prenzlauer Berg, 10119 Berlin", 52.5390, 13.4100);
    static final Venue OLD_TOWN = new Venue("Berlin", "Nikolaiviertel Old Town", "Nikolaikirchplatz 1, Mitte, 10178 Berlin", 52.5160, 13.4070);

    // 其余城市：每座一到两个场地，用来演示城市筛选与跨时区的活动列表。
    static final Venue NAVY_YARD = new Venue("New York", "Brooklyn Navy Yard Gallery", "63 Flushing Avenue, Brooklyn, NY 11205", 40.7010, -73.9720);
    static final Venue PARK_LAWN = new Venue("London", "Victoria Park Lawn Stage", "Grove Road, Victoria Park, London E3 5TB", 51.5360, -0.0400);
    static final Venue MAKER_PLAZA = new Venue("Tokyo", "Ariake Maker Plaza", "3-11-1 Ariake, Koto City, Tokyo 135-0063", 35.6300, 139.7950);
    static final Venue FILM_LANE = new Venue("Melbourne", "Fitzroy Film Lane", "112 Gertrude Street, Fitzroy VIC 3065", -37.7990, 144.9780);
    static final Venue WAREHOUSE = new Venue("Sao Paulo", "Vila Leopoldina Warehouse", "Rua Carlos Weber 800, Vila Leopoldina, Sao Paulo 05303-000", -23.5290, -46.7350);

    /** 索引 0 是 README 里给出的普通用户，1 是主办方；后面是让列表更真实的陪衬账号。 */
    static final List<UserSpec> USERS = List.of(
            new UserSpec("user@eventpulse.dev", "User123456", "Demo User", "USER", 88800),
            new UserSpec("organiser@eventpulse.dev", "Organiser123456", "Demo Organiser", "ORGANISER", 88800),
            new UserSpec("studio@eventpulse.dev", "Organiser123456", "Soundwave Live", "ORGANISER", 50000),
            new UserSpec("guild@eventpulse.dev", "Organiser123456", "City Wanderers", "ORGANISER", 50000),
            new UserSpec("priya@eventpulse.dev", "User123456", "Priya Sharma", "USER", 120000),
            new UserSpec("diego@eventpulse.dev", "User123456", "Diego Ramirez", "USER", 96000),
            new UserSpec("amara@eventpulse.dev", "User123456", "Amara Okafor", "USER", 150000),
            new UserSpec("yuki@eventpulse.dev", "User123456", "Yuki Tanaka", "USER", 60000));

    /** 主办方账号在 {@link #USERS} 里的下标，供 {@link EventSpec#organiserIndex} 使用。 */
    static final List<Integer> ORGANISER_INDEXES = List.of(1, 2, 3);

    /**
     * 每个主办方的联系方式，跟着 {@link #ORGANISER_INDEXES} 的顺序。
     * 电话号码都取自各国官方保留给影视 / 文档使用的号段，不会打到真人。
     */
    static final List<String> ORGANISER_CONTACTS = List.of(
            "Demo Organiser · demo@eventpulse.dev · +49 30 5550 0100",
            "Soundwave Live · studio@eventpulse.dev · +44 20 7946 0100",
            "City Wanderers · guild@eventpulse.dev · +1 212 555 0142");

    private static final int DAY = 24;

    static final List<EventSpec> EVENTS = List.of(
            new EventSpec("City Pulse · Indie Rock Night",
                    "Six local bands, from post-punk to math rock.",
                    "Six Berlin indie bands take the stage, spanning post-punk, math rock, and psychedelic folk. Full Meyer PA, "
                            + "plus a vinyl market and merch stalls opening an hour before doors.",
                    "music", LIVEHOUSE, 14 * DAY, 3, 18000, 300, 168, "PUBLISHED", 0, null),
            new EventSpec("AI and City Life · Tech Salon",
                    "Four working engineers on the real cost of shipping models.",
                    "Four 20-minute lightning talks plus a panel: cold-start in recommenders, on-device inference power budgets, "
                            + "and the pitfalls of wiring LLMs into legacy systems. Open networking afterwards.",
                    "tech", THINK_TANK, 9 * DAY, 3, 4900, 120, 74, "PUBLISHED", 0, null),
            new EventSpec("Riverside Morning 5K",
                    "A 5 km jog along the river, grouped by pace, breakfast at the finish.",
                    "Three pace groups at 5'30\", 6'30\", and 7'30\" per kilometre, with pacers and a sweep. Coffee and sandwiches at the finish; "
                            + "bag drop next to the start arch.",
                    "sports", RIVERSIDE, 5 * DAY, 2, 0, 200, 96, "PUBLISHED", 0, null),
            new EventSpec("City Light · Digital Art Show",
                    "Twelve immersive installations, night hours until 22:00.",
                    "Twelve digital installations by artists from four continents, all built around \"city and memory\". Three interactive works need a timed slot; "
                            + "curator tours every evening at 19:30.",
                    "art", NAVY_YARD, 21 * DAY, 8, 8800, 500, 212, "PUBLISHED", 0, null),
            new EventSpec("Late-Night Jazz Trio",
                    "Piano, bass, and drums improvising. 60 seats only.",
                    "A standard jazz trio: two 45-minute sets with a 15-minute interval. Free seating; "
                            + "arrive 30 minutes early.",
                    "music", LIVEHOUSE, 3 * DAY, 3, 12800, 60, 56, "PUBLISHED", 0, null),
            new EventSpec("Cloud-Native Observability Workshop",
                    "Bring a laptop and build a full tracing pipeline.",
                    "Hands-on from OpenTelemetry SDK instrumentation through sampling strategy. Bring a laptop that can run Docker; "
                            + "a setup checklist is sent before class.",
                    "tech", THINK_TANK, 12 * DAY, 6, 29900, 60, 31, "PUBLISHED", 0, null),
            new EventSpec("City Ride · Canal Night Ride",
                    "20 km night ride along the canal across eight bridges.",
                    "An easy 20 km out-and-back along the Landwehr Canal and the Spree, crossing eight bridges. Bring your own bike, lights, and helmet; "
                            + "a lead and a sweep ride with the group.",
                    "sports", CANAL_WALK, 7 * DAY, 3, 3900, 150, 88, "PUBLISHED", 0, null),
            new EventSpec("Pottery Studio · One-Day Making",
                    "From throwing to glazing; take two pieces home.",
                    "Throwing in the morning, trimming and glazing in the afternoon; fired pieces ship to you. Clay, aprons, and tools provided. Beginners welcome, "
                            + "one assistant per table.",
                    "art", CRAFT_LOFT, 10 * DAY, 5, 26800, 24, 19, "PUBLISHED", 0, null),
            new EventSpec("Folk on the Lawn · Victoria Park",
                    "Open lawn, bring a picnic blanket, music at sunset.",
                    "Four folk acts outdoors. Free seating on the lawn; picnic blankets and folding chairs welcome. Rain date is one week later, "
                            + "announced in-app.",
                    "music", PARK_LAWN, 18 * DAY, 3, 15800, 400, 156, "PUBLISHED", 1, null),
            new EventSpec("Open Hardware Fair",
                    "Forty stalls, free entry, soldering and tinkering on site.",
                    "Forty open-hardware teams with keyboards, scopes, and farm sensors. A soldering booth and a used-gear swap corner. "
                            + "Free, but a reservation is required.",
                    "tech", MAKER_PLAZA, 16 * DAY, 8, 0, 800, 421, "PUBLISHED", 1, null),
            new EventSpec("Urban Orienteering · Old Town",
                    "Teams of three solving clues around the old town.",
                    "Teams of three complete twelve checkpoints in four hours, all on foot. Finish on the riverside at Nikolaiviertel; "
                            + "top three teams get a hand-drawn city map.",
                    "sports", OLD_TOWN, 25 * DAY, 4, 5900, 300, 64, "PUBLISHED", 2, null),
            new EventSpec("Film Photography Walk",
                    "Bring a film camera and shoot the streets for four hours.",
                    "A local photographer leads a four-hour walk through the Fitzroy laneways, covering composition and exposure. Film cameras available to rent; "
                            + "film is developed together and shared online afterwards.",
                    "art", FILM_LANE, 11 * DAY, 4, 12800, 40, 27, "PUBLISHED", 2, null),
            new EventSpec("Electronic Live · Warehouse",
                    "Six hours straight, three live electronic sets.",
                    "A converted warehouse. Three hardware-live electronic acts over six hours. Earplugs at the door; "
                            + "no re-entry after 22:00.",
                    "music", WAREHOUSE, -2, 6, 19800, 350, 318, "ONGOING", 1, null),
            new EventSpec("Product Design Closed-Door",
                    "40 people, no recording, no livestream.",
                    "A Chatham House discussion on shipping AI features without breaking existing workflows: "
                            + "no recording, no livestream, no attributed quotes.",
                    "tech", THINK_TANK, -3, 5, 19900, 40, 38, "ONGOING", 0, null),
            new EventSpec("Spring Marathon · City Half",
                    "21.0975 km through the old centre and the riverfront.",
                    "Half marathon, 3-hour cutoff. Six aid stations along the Spree. Finishers get a medal and a towel.",
                    "sports", RIVERSIDE, -30 * DAY, 5, 12000, 1000, 964, "FINISHED", 0, null),
            new EventSpec("Contemporary Sculpture Tour · First Stop",
                    "Thirty sculptures, first venue of the tour.",
                    "Thirty contemporary sculptures in metal, clay, and mixed media. Artist Q&A every Saturday afternoon.",
                    "art", NAVY_YARD, -60 * DAY, 8, 6800, 300, 287, "FINISHED", 0, null),
            new EventSpec("City Sound Festival · Open Air",
                    "Two days, three stages, outdoor festival.",
                    "A two-day, three-stage open-air festival with twenty-four acts. Cancelled with a full refund after the venue permit was denied.",
                    "music", WAREHOUSE, 30 * DAY, 10, 38000, 1200, 246, "CANCELLED", 1,
                    "Venue construction permit denied; the festival is cancelled with a full refund"),
            new EventSpec("City Talks · Architecture and Memory",
                    "A city architecture conversation still being planned.",
                    "Three architects on the renewal and preservation of Berlin's courtyard tenements. Venue and time still to be confirmed; not yet public.",
                    "art", CRAFT_LOFT, 45 * DAY, 3, 9800, 60, 0, "DRAFT", 0, null),
            new EventSpec("Vinyl Swap Meet",
                    "Bring one record, leave with another.",
                    "Bring vinyl or tapes to swap. Turntables on site for listening. A past event, archived.",
                    "music", FILM_LANE, -90 * DAY, 4, 3000, 80, 72, "ARCHIVED", 0, null));

    /**
     * 演示封面，与 {@link #EVENTS} 等长且下标对齐（活动 1 ↔ seed/demo-covers/01.jpeg）。
     * 对象已在部署 S3（bucket eventpulse，path-style）就位；换 bucket 时只需重传
     * 同名 key，本表不用改。
     */
    static final List<CoverSpec> COVERS = List.of(
            new CoverSpec("seed/demo-covers/01.jpeg", 2948169),
            new CoverSpec("seed/demo-covers/02.jpeg", 790775),
            new CoverSpec("seed/demo-covers/03.jpeg", 3062872),
            new CoverSpec("seed/demo-covers/04.jpeg", 3508898),
            new CoverSpec("seed/demo-covers/05.jpeg", 2773825),
            new CoverSpec("seed/demo-covers/06.jpeg", 2796136),
            new CoverSpec("seed/demo-covers/07.jpeg", 3789018),
            new CoverSpec("seed/demo-covers/08.jpeg", 2902242),
            new CoverSpec("seed/demo-covers/09.jpeg", 4038727),
            new CoverSpec("seed/demo-covers/10.jpeg", 3604954),
            new CoverSpec("seed/demo-covers/11.jpeg", 4127479),
            new CoverSpec("seed/demo-covers/12.jpeg", 3666928),
            new CoverSpec("seed/demo-covers/13.jpeg", 3251015),
            new CoverSpec("seed/demo-covers/14.jpeg", 2971757),
            new CoverSpec("seed/demo-covers/15.jpeg", 3352567),
            new CoverSpec("seed/demo-covers/16.jpeg", 3351778),
            new CoverSpec("seed/demo-covers/17.jpeg", 3248658),
            new CoverSpec("seed/demo-covers/18.jpeg", 3598240),
            new CoverSpec("seed/demo-covers/19.jpeg", 3759650));

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
            new BookingSpec("priya@eventpulse.dev", 0, 1, "CONFIRMED", -4 * DAY, 0),
            new BookingSpec("priya@eventpulse.dev", 8, 2, "CONFIRMED", -8 * DAY, 0),
            new BookingSpec("priya@eventpulse.dev", 15, 1, "CONFIRMED", -70 * DAY, 1),
            new BookingSpec("diego@eventpulse.dev", 5, 1, "CONFIRMED", -9 * DAY, 0),
            new BookingSpec("diego@eventpulse.dev", 6, 2, "CONFIRMED", -1 * DAY, 0),
            new BookingSpec("diego@eventpulse.dev", 13, 1, "CONFIRMED", -11 * DAY, 1),
            new BookingSpec("amara@eventpulse.dev", 3, 4, "CONFIRMED", -7 * DAY, 0),
            new BookingSpec("amara@eventpulse.dev", 9, 2, "CONFIRMED", -12 * DAY, 0),
            new BookingSpec("amara@eventpulse.dev", 11, 1, "CONFIRMED", -2 * DAY, 0),
            new BookingSpec("amara@eventpulse.dev", 15, 2, "CONFIRMED", -75 * DAY, 2),
            new BookingSpec("yuki@eventpulse.dev", 1, 2, "CONFIRMED", -3 * DAY, 0),
            new BookingSpec("yuki@eventpulse.dev", 7, 1, "CONFIRMED", -6 * DAY, 0),
            new BookingSpec("yuki@eventpulse.dev", 10, 1, "CANCELLED", -5 * DAY, 0));

    static final List<FavouriteSpec> FAVOURITES = List.of(
            new FavouriteSpec("user@eventpulse.dev", 0),
            new FavouriteSpec("user@eventpulse.dev", 3),
            new FavouriteSpec("user@eventpulse.dev", 5),
            new FavouriteSpec("user@eventpulse.dev", 8),
            new FavouriteSpec("priya@eventpulse.dev", 0),
            new FavouriteSpec("priya@eventpulse.dev", 12),
            new FavouriteSpec("diego@eventpulse.dev", 1),
            new FavouriteSpec("diego@eventpulse.dev", 5),
            new FavouriteSpec("amara@eventpulse.dev", 3),
            new FavouriteSpec("amara@eventpulse.dev", 15),
            new FavouriteSpec("yuki@eventpulse.dev", 7),
            new FavouriteSpec("yuki@eventpulse.dev", 11));

    static final List<PreferenceSpec> PREFERENCES = List.of(
            new PreferenceSpec("user@eventpulse.dev", "music,art", "Berlin,New York", 52.4995, 13.4300, 25.0),
            new PreferenceSpec("priya@eventpulse.dev", "music", "Berlin,London", 52.5390, 13.4100, 15.0),
            new PreferenceSpec("diego@eventpulse.dev", "tech,sports", "Berlin", 52.5185, 13.4020, 30.0),
            new PreferenceSpec("amara@eventpulse.dev", "art,tech", "New York,Tokyo", 40.7010, -73.9720, 20.0));

    /** 分类对应的入场提示，避免每个活动都手写一遍相同的说明。 */
    static String attendanceNotes(String category) {
        return switch (category) {
            case EventCategory.MUSIC -> "Scan the e-ticket QR code at the door. One ticket per person. Under-16s must be accompanied by an adult.";
            case EventCategory.TECH -> "Bring a laptop with internet access. Power and Wi-Fi are provided.";
            case EventCategory.SPORTS -> "Wear running shoes and check in 30 minutes early. Goes ahead in rain.";
            default -> "E-ticket required. Photos are allowed; no flash or tripods.";
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
