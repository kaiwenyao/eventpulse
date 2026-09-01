package dev.kaiwen.eventpulse.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import dev.kaiwen.eventpulse.common.Result;
import dev.kaiwen.eventpulse.dto.BookingDtos.NotificationVo;
import dev.kaiwen.eventpulse.service.PlatformService;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final PlatformService platform;

    public NotificationController(PlatformService platform) {
        this.platform = platform;
    }

    @GetMapping
    public Result<List<NotificationVo>> list() {
        return Result.success(platform.myNotifications());
    }

    @PostMapping("/{id}/read")
    public Result<Void> read(@PathVariable Long id) {
        platform.markRead(id);
        return Result.success();
    }
}
