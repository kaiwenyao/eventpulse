package dev.kaiwen.eventpulse.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import dev.kaiwen.eventpulse.common.PageResult;
import dev.kaiwen.eventpulse.common.Result;
import dev.kaiwen.eventpulse.dto.EventDtos.EventVo;
import dev.kaiwen.eventpulse.service.PlatformService;

@RestController
public class PlatformController {

    private final PlatformService platform;

    public PlatformController(PlatformService platform) {
        this.platform = platform;
    }

    @PostMapping("/api/events/{id}/favourite")
    public Result<Void> favourite(@PathVariable Long id) {
        platform.favourite(id);
        return Result.success();
    }

    @DeleteMapping("/api/events/{id}/favourite")
    public Result<Void> unfavourite(@PathVariable Long id) {
        platform.unfavourite(id);
        return Result.success();
    }

    @GetMapping("/api/favourites")
    public Result<PageResult<EventVo>> favourites() {
        return Result.success(platform.myFavourites());
    }

    @PostMapping("/api/interactions")
    public Result<Void> interact(@RequestBody Map<String, Object> body) {
        platform.interact(((Number) body.get("eventId")).longValue(), String.valueOf(body.get("type")));
        return Result.success();
    }

    @GetMapping("/api/events/nearby")
    public Result<List<EventVo>> nearby(
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng,
            @RequestParam(required = false) Double radiusKm) {
        return Result.success(platform.nearby(lat, lng, radiusKm));
    }

    @GetMapping("/api/recommendations")
    public Result<List<EventVo>> recommend() {
        return Result.success(platform.recommend());
    }

    @GetMapping("/api/organiser/dashboard")
    public Result<Map<String, Object>> dashboard() {
        return Result.success(platform.dashboard());
    }

    @GetMapping("/api/organiser/analytics")
    public Result<Map<String, Object>> analytics(
            @RequestParam(required = false) Long eventId,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        return Result.success(platform.analytics(eventId, from, to));
    }

    @GetMapping("/api/organiser/events/{id}/analytics")
    public Result<Map<String, Object>> eventAnalytics(
            @PathVariable Long id,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        return Result.success(platform.analytics(id, from, to));
    }

    @GetMapping(path = "/api/bookings/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable Long id) {
        return platform.subscribe(id);
    }

    @PostMapping("/api/preferences")
    public Result<Void> preferences(@RequestBody Map<String, Object> body) {
        platform.savePreference(
                body.get("categories") == null ? null : String.valueOf(body.get("categories")),
                body.get("cities") == null ? null : String.valueOf(body.get("cities")),
                body.get("latitude") == null ? null : ((Number) body.get("latitude")).doubleValue(),
                body.get("longitude") == null ? null : ((Number) body.get("longitude")).doubleValue(),
                body.get("radiusKm") == null ? null : ((Number) body.get("radiusKm")).doubleValue());
        return Result.success();
    }
}
