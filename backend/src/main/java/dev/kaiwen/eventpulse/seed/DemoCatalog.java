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

    static final Venue LIVEHOUSE = new Venue("Shanghai", "Sound Space Livehouse", "Building 3, No. 8 Jianguo Middle Road, Huangpu, Shanghai", 31.2020, 121.4670);
    static final Venue THINK_TANK = new Venue("Shanghai", "West Bund Think Tank", "No. 2555 Longteng Avenue, Xuhui, Shanghai", 31.1770, 121.4560);
    static final Venue RIVERSIDE = new Venue("Shanghai", "Xuhui Riverside Track", "Longteng Avenue waterfront green, Xuhui, Shanghai", 31.1830, 121.4610);
    static final Venue SUZHOU_CREEK = new Venue("Shanghai", "Suzhou Creek Promenade", "No. 1155 Guangfu West Road, Putuo, Shanghai", 31.2400, 121.4230);
    static final Venue CRAFT_LOFT = new Venue("Shanghai", "Yuyuan Road Craft Studio", "No. 749 Yuyuan Road, Jing'an, Shanghai", 31.2240, 121.4400);
    static final Venue OLD_TOWN = new Venue("Shanghai", "Yuyuan Old Town", "No. 132 Anren Street, Huangpu, Shanghai", 31.2270, 121.4920);
    static final Venue GALLERY_798 = new Venue("Beijing", "798 Star Cluster Gallery", "No. 4 Jiuxianqiao Road, Chaoyang, Beijing", 39.9840, 116.4950);
    static final Venue WEST_LAKE = new Venue("Hangzhou", "West Lake Lawn Theatre", "No. 78 Beishan Street, Xihu, Hangzhou", 30.2560, 120.1450);
    static final Venue MAKER_PLAZA = new Venue("Shenzhen", "Nanshan Maker Plaza", "No. 2888 Keyuan South Road, Nanshan, Shenzhen", 22.5310, 113.9440);
    static final Venue FILM_ALLEY = new Venue("Chengdu", "Yulin Road Lane", "No. 55 Yulin West Road, Wuhou, Chengdu", 30.6350, 104.0620);
    static final Venue WAREHOUSE = new Venue("Guangzhou", "Pearl River Warehouse", "No. 222 Yuejiang West Road, Haizhu, Guangzhou", 23.1010, 113.3240);

    /** 索引 0 是 README 里给出的普通用户，1 是主办方；后面是让列表更真实的陪衬账号。 */
    static final List<UserSpec> USERS = List.of(
            new UserSpec("user@eventpulse.dev", "User123456", "Demo User", "USER", 88800),
            new UserSpec("organiser@eventpulse.dev", "Organiser123456", "Demo Organiser", "ORGANISER", 88800),
            new UserSpec("studio@eventpulse.dev", "Organiser123456", "Soundwave Live", "ORGANISER", 50000),
            new UserSpec("guild@eventpulse.dev", "Organiser123456", "City Wanderers", "ORGANISER", 50000),
            new UserSpec("lin@eventpulse.dev", "User123456", "Coco Lin", "USER", 120000),
            new UserSpec("zhao@eventpulse.dev", "User123456", "Yiming Zhao", "USER", 96000),
            new UserSpec("chen@eventpulse.dev", "User123456", "Siyuan Chen", "USER", 150000),
            new UserSpec("wang@eventpulse.dev", "User123456", "Yutong Wang", "USER", 60000));

    /** 主办方账号在 {@link #USERS} 里的下标，供 {@link EventSpec#organiserIndex} 使用。 */
    static final List<Integer> ORGANISER_INDEXES = List.of(1, 2, 3);

    /** 每个主办方的联系方式，跟着 {@link #ORGANISER_INDEXES} 的顺序。 */
    static final List<String> ORGANISER_CONTACTS = List.of(
            "Demo Organiser · demo@eventpulse.dev · 021-0000 0000",
            "Soundwave Live · studio@eventpulse.dev · 020-0000 0000",
            "City Wanderers · guild@eventpulse.dev · 028-0000 0000");

    private static final int DAY = 24;

    static final List<EventSpec> EVENTS = List.of(
            new EventSpec("City Pulse · Indie Rock Night",
                    "Six local bands, from post-punk to math rock.",
                    "Six Shanghai indie bands take the stage, spanning post-punk, math rock, and psychedelic folk. Full Meyer PA, "
                            + "plus a vinyl market and merch stalls opening an hour before doors.",
                    "music", LIVEHOUSE, 14 * DAY, 3, 18000, 300, 168, "PUBLISHED", 0, null),
            new EventSpec("AI and City Life · Tech Salon",
                    "Four working engineers on the real cost of shipping models.",
                    "Four 20-minute lightning talks plus a panel: cold-start in recommenders, on-device inference power budgets, "
                            + "and the pitfalls of wiring LLMs into legacy systems. Open networking afterwards.",
                    "tech", THINK_TANK, 9 * DAY, 3, 4900, 120, 74, "PUBLISHED", 0, null),
            new EventSpec("Riverside Morning 5K",
                    "A 5 km jog along the river, grouped by pace, breakfast at the finish.",
                    "Three pace groups at 5'30\", 6'30\", and 7'30\", with pacers and a sweep. Coffee and sandwiches at the finish; "
                            + "bag drop next to the start arch.",
                    "sports", RIVERSIDE, 5 * DAY, 2, 0, 200, 96, "PUBLISHED", 0, null),
            new EventSpec("City Light · Digital Art Show",
                    "Twelve immersive installations, night hours until 22:00.",
                    "Twelve digital installations from China and abroad around \"city and memory\". Three interactive works need a timed slot; "
                            + "curator tours every evening at 19:30.",
                    "art", GALLERY_798, 21 * DAY, 8, 8800, 500, 212, "PUBLISHED", 0, null),
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
            new EventSpec("City Ride · Suzhou Creek Night Ride",
                    "20 km night ride along Suzhou Creek across eight bridges.",
                    "An easy 20 km out-and-back west along Suzhou Creek, crossing eight bridges. Bring your own bike and helmet; "
                            + "a lead and a sweep ride with the group.",
                    "sports", SUZHOU_CREEK, 7 * DAY, 3, 3900, 150, 88, "PUBLISHED", 0, null),
            new EventSpec("Pottery Studio · One-Day Making",
                    "From throwing to glazing; take two pieces home.",
                    "Throwing in the morning, trimming and glazing in the afternoon; fired pieces ship to you. Clay, aprons, and tools provided. Beginners welcome, "
                            + "one assistant per table.",
                    "art", CRAFT_LOFT, 10 * DAY, 5, 26800, 24, 19, "PUBLISHED", 0, null),
            new EventSpec("Folk on the Lawn · West Lake",
                    "Lakeside lawn, bring a picnic blanket, music at sunset.",
                    "Four folk acts outdoors. Free seating on the lawn; picnic blankets and folding chairs welcome. Rain date is one week later, "
                            + "announced in-app.",
                    "music", WEST_LAKE, 18 * DAY, 3, 15800, 400, 156, "PUBLISHED", 1, null),
            new EventSpec("Open Hardware Fair",
                    "Forty stalls, free entry, soldering and tinkering on site.",
                    "Forty open-hardware teams with keyboards, scopes, and farm sensors. A soldering booth and a used-gear swap corner. "
                            + "Free, but a reservation is required.",
                    "tech", MAKER_PLAZA, 16 * DAY, 8, 0, 800, 421, "PUBLISHED", 1, null),
            new EventSpec("Urban Orienteering · Old Town",
                    "Teams of three solving clues around the old town.",
                    "Teams of three complete twelve checkpoints in four hours, all on foot. Finish at Yuyuan's zigzag bridge; "
                            + "top three teams get a hand-drawn city map.",
                    "sports", OLD_TOWN, 25 * DAY, 4, 5900, 300, 64, "PUBLISHED", 2, null),
            new EventSpec("Film Photography Walk",
                    "Bring a film camera and shoot the streets for four hours.",
                    "A local photographer leads a four-hour walk through Yulin, covering composition and exposure. Film cameras available to rent; "
                            + "film is developed together and shared online afterwards.",
                    "art", FILM_ALLEY, 11 * DAY, 4, 12800, 40, 27, "PUBLISHED", 2, null),
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
                    "21.0975 km through the Bund and the riverfront.",
                    "Half marathon, 3-hour cutoff. Six aid stations along the Huangpu. Finishers get a medal and a towel.",
                    "sports", RIVERSIDE, -30 * DAY, 5, 12000, 1000, 964, "FINISHED", 0, null),
            new EventSpec("Contemporary Sculpture Tour · First Stop",
                    "Thirty sculptures, first venue of the tour.",
                    "Thirty contemporary sculptures in metal, clay, and mixed media. Artist Q&A every Saturday afternoon.",
                    "art", GALLERY_798, -60 * DAY, 8, 6800, 300, 287, "FINISHED", 0, null),
            new EventSpec("Autumn Music Festival · Riverside",
                    "Two days, three stages, outdoor festival.",
                    "A two-day, three-stage riverside festival with twenty-four acts. Cancelled with a full refund after the venue permit was denied.",
                    "music", WAREHOUSE, 30 * DAY, 10, 38000, 1200, 246, "CANCELLED", 1,
                    "Venue construction permit denied; the festival is cancelled with a full refund"),
            new EventSpec("City Talks · Architecture and Memory",
                    "A city architecture conversation still being planned.",
                    "Three architects on the renewal and preservation of Shanghai lilong. Venue and time still to be confirmed; not yet public.",
                    "art", CRAFT_LOFT, 45 * DAY, 3, 9800, 60, 0, "DRAFT", 0, null),
            new EventSpec("Vinyl Swap Meet",
                    "Bring one record, leave with another.",
                    "Bring vinyl or tapes to swap. Turntables on site for listening. A past event, archived.",
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
            new PreferenceSpec("user@eventpulse.dev", "music,art", "Shanghai,Beijing", 31.2020, 121.4670, 25.0),
            new PreferenceSpec("lin@eventpulse.dev", "music", "Shanghai,Hangzhou", 31.2240, 121.4400, 15.0),
            new PreferenceSpec("zhao@eventpulse.dev", "tech,sports", "Shanghai", 31.1770, 121.4560, 30.0),
            new PreferenceSpec("chen@eventpulse.dev", "art,tech", "Beijing,Shenzhen", 39.9840, 116.4950, 20.0));

    /** 分类对应的入场提示，避免每个活动都手写一遍相同的说明。 */
    static String attendanceNotes(String category) {
        return switch (category) {
            case "music" -> "Scan the e-ticket QR code. One ticket per person. Children under 1.2 m are not admitted.";
            case "tech" -> "Bring a laptop with internet access. Power and Wi-Fi are provided.";
            case "sports" -> "Wear running shoes and check in 30 minutes early. Goes ahead in rain.";
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
