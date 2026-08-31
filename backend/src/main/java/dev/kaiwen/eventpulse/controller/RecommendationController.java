package dev.kaiwen.eventpulse.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.kaiwen.eventpulse.security.AuthUser;
import dev.kaiwen.eventpulse.service.RecommendationService;
import dev.kaiwen.eventpulse.service.RecommendationService.InteractionBatch;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "recommendations")
public class RecommendationController {

    private final RecommendationService recommendations;

    public RecommendationController(RecommendationService recommendations) {
        this.recommendations = recommendations;
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
        int accepted = recommendations.recordInteractions(user == null ? null : user.id(),
                batch == null ? null : batch.sessionId(), batch);
        return Map.of("accepted", accepted);
    }
}