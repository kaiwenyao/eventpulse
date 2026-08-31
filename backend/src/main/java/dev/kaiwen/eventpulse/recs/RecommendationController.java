package dev.kaiwen.eventpulse.recs;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.kaiwen.eventpulse.auth.AuthUser;
import dev.kaiwen.eventpulse.common.error.ApiException;
import dev.kaiwen.eventpulse.common.error.ErrorCode;
import dev.kaiwen.eventpulse.outbox.OutboxWriter;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "recommendations")
public class RecommendationController {

    public record InteractionInput(UUID eventId, String type, Integer position, Instant occurredAt) {
    }

    public record InteractionBatch(String requestId, String sessionId, List<InteractionInput> events) {
    }

    private final RecommendationService recommendations;
    private final JdbcTemplate jdbc;
    private final OutboxWriter outbox;

    public RecommendationController(RecommendationService recommendations, JdbcTemplate jdbc,
            OutboxWriter outbox) {
        this.recommendations = recommendations;
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    @Operation(summary = "Recommendations with requestId, frozen candidate cursor and reason codes")
    @GetMapping("/recommendations")
    public RecommendationService.RecommendationPage get(@AuthenticationPrincipal AuthUser user,
            @RequestParam(defaultValue = "for-you") String section,
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) String cursor) {
        return recommendations.recommend(user == null ? null : user.id(), section, limit, cursor);
    }

    @Operation(summary = "Batch interactions; server receive time is the fact, batch capped and deduped")
    @PostMapping("/interactions")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> interactions(@AuthenticationPrincipal AuthUser user,
            @RequestBody InteractionBatch batch) {
        if (batch.requestId() == null || batch.requestId().isBlank() || batch.requestId().length() > 80) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "requestId required",
                    Map.of("requestId", "required, max 80 chars"));
        }
        if (batch.events() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "events required");
        }
        if (batch.events().size() > 50) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "batch too large",
                    Map.of("events", "max 50 per batch"));
        }
        int accepted = 0;
        UUID aggregateId = user != null ? user.id()
                : UUID.nameUUIDFromBytes((batch.sessionId() == null ? "anon" : batch.sessionId()).getBytes());
        for (InteractionInput input : batch.events()) {
            if (input.eventId() == null || input.type() == null
                    || !List.of("VIEW", "IMPRESSION", "SAVE", "UNSAVE", "SHARE", "BOOK_ATTEMPT")
                            .contains(input.type())) {
                continue;
            }
            int inserted = jdbc.update("""
                    INSERT INTO interactions (request_id, user_id, session_id, event_id, type, position,
                                              occurred_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT DO NOTHING
                    """, batch.requestId(), user == null ? null : user.id(), batch.sessionId(),
                    input.eventId(), input.type(), input.position(),
                    input.occurredAt() == null ? null : java.sql.Timestamp.from(input.occurredAt()));
            if (inserted == 1) {
                accepted++;
                outbox.append(user == null ? "session" : "user", aggregateId, OutboxWriter.TOPIC_INTERACTION,
                        "interaction.recorded", Map.of("eventId", input.eventId().toString(), "type",
                                input.type(), "requestId", batch.requestId()));
            }
        }
        return Map.of("accepted", accepted);
    }
}
