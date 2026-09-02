package dev.kaiwen.eventpulse.controller;

import java.time.Instant;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.kaiwen.eventpulse.common.PageResult;
import dev.kaiwen.eventpulse.common.Result;
import dev.kaiwen.eventpulse.dto.EventDtos.ArchiveEventRequest;
import dev.kaiwen.eventpulse.dto.EventDtos.CancelEventRequest;
import dev.kaiwen.eventpulse.dto.EventDtos.EventVo;
import dev.kaiwen.eventpulse.dto.EventDtos.OrganiserEventRequest;
import dev.kaiwen.eventpulse.service.OrganiserEventService;

import jakarta.validation.Valid;

@RestController
@Profile("api")
@RequestMapping("/api/organiser/events")
public class OrganiserEventController {

    private final OrganiserEventService organiserEvents;

    public OrganiserEventController(OrganiserEventService organiserEvents) {
        this.organiserEvents = organiserEvents;
    }

    @GetMapping
    public Result<PageResult<EventVo>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String sort) {
        return Result.success(organiserEvents.list(q, status, category, from, to, sort));
    }

    @PostMapping
    public Result<EventVo> create(@Valid @RequestBody OrganiserEventRequest request) {
        return Result.success(organiserEvents.create(request));
    }

    @GetMapping("/{id}")
    public Result<EventVo> get(@PathVariable Long id) {
        return Result.success(organiserEvents.get(id));
    }

    @PutMapping("/{id}")
    public Result<EventVo> update(@PathVariable Long id, @Valid @RequestBody OrganiserEventRequest request) {
        return Result.success(organiserEvents.update(id, request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        organiserEvents.delete(id);
        return Result.success();
    }

    @PostMapping("/{id}/publish")
    public Result<EventVo> publish(@PathVariable Long id) {
        return Result.success(organiserEvents.publish(id));
    }

    @PostMapping("/{id}/cancel")
    public Result<EventVo> cancel(@PathVariable Long id, @Valid @RequestBody CancelEventRequest request) {
        return Result.success(organiserEvents.cancel(id, request));
    }

    @PostMapping("/{id}/archive")
    public Result<EventVo> archive(@PathVariable Long id, @RequestBody(required = false) ArchiveEventRequest request) {
        return Result.success(organiserEvents.archive(id, request == null ? new ArchiveEventRequest(null) : request));
    }

    @PostMapping("/{id}/duplicate")
    public Result<EventVo> duplicate(@PathVariable Long id) {
        return Result.success(organiserEvents.duplicate(id));
    }
}
