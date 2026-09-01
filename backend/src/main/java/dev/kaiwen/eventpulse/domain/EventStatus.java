package dev.kaiwen.eventpulse.domain;

import java.util.Set;

public final class EventStatus {

    public static final String DRAFT = "DRAFT";
    public static final String PUBLISHED = "PUBLISHED";
    public static final String ONGOING = "ONGOING";
    public static final String FINISHED = "FINISHED";
    public static final String CANCELLED = "CANCELLED";
    public static final String ARCHIVED = "ARCHIVED";

    public static final Set<String> PUBLIC_LIST = Set.of(PUBLISHED, ONGOING, FINISHED);
    public static final Set<String> PUBLIC_LINK = Set.of(PUBLISHED, ONGOING, FINISHED, CANCELLED);

    private EventStatus() {
    }

    public static boolean canTransition(String from, String to) {
        if (DRAFT.equals(from)) {
            return PUBLISHED.equals(to);
        }
        if (PUBLISHED.equals(from)) {
            return CANCELLED.equals(to) || ONGOING.equals(to);
        }
        if (ONGOING.equals(from)) {
            return FINISHED.equals(to);
        }
        if (FINISHED.equals(from) || CANCELLED.equals(from)) {
            return ARCHIVED.equals(to);
        }
        return false;
    }
}
