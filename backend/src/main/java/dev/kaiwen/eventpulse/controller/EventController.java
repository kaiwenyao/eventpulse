package dev.kaiwen.eventpulse.controller;

import java.time.Instant;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.kaiwen.eventpulse.common.Result;
import dev.kaiwen.eventpulse.dto.EventDtos.EventRequest;
import dev.kaiwen.eventpulse.dto.EventDtos.EventVo;
import dev.kaiwen.eventpulse.service.EventService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @GetMapping
    public Result<List<EventVo>> list(
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Instant dateFrom,
            @RequestParam(required = false) Instant dateTo,
            @RequestParam(required = false) Integer minPrice,
            @RequestParam(required = false) Integer maxPrice,
            @RequestParam(required = false) Boolean hasRemaining,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) Boolean desc,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return Result.success(eventService.search(
                city, category, q, dateFrom, dateTo, minPrice, maxPrice, hasRemaining,
                sort == null ? "startsAt" : sort, Boolean.TRUE.equals(desc), page, size).getRecords());
    }

    @GetMapping("/{id}")
    public Result<EventVo> get(@PathVariable Long id) {
        return Result.success(eventService.get(id));
    }

    @GetMapping("/mine")
    public Result<List<EventVo>> mine() {
        return Result.success(eventService.mine());
    }

    @PostMapping
    public Result<EventVo> create(@Valid @RequestBody EventRequest request) {
        return Result.success(eventService.create(request));
    }

    @PutMapping("/{id}")
    public Result<EventVo> update(@PathVariable Long id, @Valid @RequestBody EventRequest request) {
        return Result.success(eventService.update(id, request));
    }

}
