package dev.kaiwen.eventpulse.controller;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.kaiwen.eventpulse.common.BaseContext;
import dev.kaiwen.eventpulse.common.PageResult;
import dev.kaiwen.eventpulse.common.Result;
import dev.kaiwen.eventpulse.dto.WalletDtos.LedgerVo;
import dev.kaiwen.eventpulse.service.WalletService;

/**
 * 余额流水（个人中心「余额明细」）：只读，数据以 wallet_ledger 为准。
 * 支持收支类型与时间范围筛选、服务端分页；点击关联订单进入订单详情。
 */
@RestController
@Profile("api")
@RequestMapping("/api/wallet")
public class WalletController {

    private final WalletService wallets;

    public WalletController(WalletService wallets) {
        this.wallets = wallets;
    }

    @GetMapping("/ledger")
    public Result<PageResult<LedgerVo>> ledger(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return Result.success(wallets.ledger(
                BaseContext.getUserId(),
                type,
                parseInstant(from),
                parseInstant(to),
                page == null ? 0 : page,
                size == null ? 10 : size));
    }

    private static java.time.Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return java.time.Instant.parse(value);
        }
        catch (java.time.format.DateTimeParseException e) {
            throw new dev.kaiwen.eventpulse.exception.BusinessException("time must be an ISO-8601 instant");
        }
    }
}
