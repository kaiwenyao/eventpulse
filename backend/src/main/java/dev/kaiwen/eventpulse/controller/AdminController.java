package dev.kaiwen.eventpulse.controller;

import java.util.Map;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.kaiwen.eventpulse.security.AuthUser;
import dev.kaiwen.eventpulse.service.AdminService;
import dev.kaiwen.eventpulse.service.AdminService.AbandonRefundRequest;
import dev.kaiwen.eventpulse.service.AdminService.ReauthRequest;
import dev.kaiwen.eventpulse.service.AdminService.ResolveGapRequest;
import dev.kaiwen.eventpulse.service.AdminService.RetryCommandRequest;
import dev.kaiwen.eventpulse.service.AdminService.ReplayRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Admin exception surface. Every high-risk action requires ADMIN plus a fresh
 * re-authentication (default 10 minutes, configurable); a confirm dialog is
 * never accepted as re-auth. All business logic lives in AdminService.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "admin")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @Operation(summary = "Fresh re-authentication for admin actions (MFA freshness window)")
    @PostMapping("/reauth")
    public Map<String, Object> reauth(@AuthenticationPrincipal AuthUser user,
            @RequestBody ReauthRequest request) {
        return adminService.reauth(user, request);
    }

    @Operation(summary = "Exception overview: manual review, UNKNOWN, refund failures, gaps, DLT")
    @GetMapping("/exceptions")
    public Map<String, Object> exceptions(@AuthenticationPrincipal AuthUser user,
            @RequestHeader(value = "X-Reauth-Token", required = false) String reauthToken) {
        return adminService.exceptions(user, reauthToken);
    }

    @Operation(summary = "Retry a manual-review command with its original providerKey")
    @PostMapping("/commands/{id}/retry")
    public Map<String, Object> retryCommand(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id,
            @RequestHeader(value = "X-Reauth-Token", required = false) String reauthToken,
            @RequestBody RetryCommandRequest request) {
        return adminService.retryCommand(user, id, reauthToken, request);
    }

    @Operation(summary = "Resolve a consumer gap: REPLAY, REBUILD_CURSOR or audited SKIP; dry-run supported")
    @PostMapping("/consumer-gaps/{id}/resolve")
    public Map<String, Object> resolveGap(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id,
            @RequestHeader(value = "X-Reauth-Token", required = false) String reauthToken,
            @RequestBody ResolveGapRequest request) {
        return adminService.resolveGap(user, id, reauthToken, request);
    }

    @Operation(summary = "Re-deliver published outbox events (idempotent consumers dedupe)")
    @PostMapping("/outbox/replay")
    public Map<String, Object> replayOutbox(@AuthenticationPrincipal AuthUser user,
            @RequestHeader(value = "X-Reauth-Token", required = false) String reauthToken,
            @RequestBody ReplayRequest request) {
        return adminService.replayOutbox(user, reauthToken, request);
    }

    @Operation(summary = "Human waiver: release a refund reservation after explicit approval")
    @PostMapping("/refunds/{id}/abandon")
    public Map<String, Object> abandonRefund(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id,
            @RequestHeader(value = "X-Reauth-Token", required = false) String reauthToken,
            @RequestBody AbandonRefundRequest request) {
        return adminService.abandonRefund(user, id, reauthToken, request);
    }
}