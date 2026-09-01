package dev.kaiwen.eventpulse.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.kaiwen.eventpulse.common.BaseContext;
import dev.kaiwen.eventpulse.common.Result;
import dev.kaiwen.eventpulse.dto.AuthDtos.LoginRequest;
import dev.kaiwen.eventpulse.dto.AuthDtos.LoginVo;
import dev.kaiwen.eventpulse.dto.AuthDtos.ProfileVo;
import dev.kaiwen.eventpulse.dto.AuthDtos.RegisterRequest;
import dev.kaiwen.eventpulse.dto.AuthDtos.UserVo;
import dev.kaiwen.eventpulse.dto.AuthDtos.WalletRechargeRequest;
import dev.kaiwen.eventpulse.service.AuthService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public Result<LoginVo> register(@Valid @RequestBody RegisterRequest request) {
        return Result.success(authService.register(request));
    }

    @PostMapping("/login")
    public Result<LoginVo> login(@Valid @RequestBody LoginRequest request) {
        return Result.success(authService.login(request));
    }

    @GetMapping("/me")
    public Result<UserVo> me() {
        return Result.success(authService.me(BaseContext.getUserId()));
    }

    /** 个人中心：余额、累计消费与账户统计。 */
    @GetMapping("/profile")
    public Result<ProfileVo> profile() {
        return Result.success(authService.profile(BaseContext.getUserId()));
    }

    /** 演示充值：直接给演示钱包加钱，不做真实支付。 */
    @PostMapping("/wallet/recharge")
    public Result<ProfileVo> recharge(@Valid @RequestBody WalletRechargeRequest request) {
        return Result.success(authService.recharge(BaseContext.getUserId(), request));
    }
}
