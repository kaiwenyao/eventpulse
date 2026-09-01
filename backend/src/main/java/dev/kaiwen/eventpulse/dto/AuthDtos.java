package dev.kaiwen.eventpulse.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @Email @NotBlank String email,
            @NotBlank @Size(min = 6, max = 64) String password,
            @NotBlank @Size(max = 50) String name) {
    }

    public record LoginRequest(
            @Email @NotBlank String email,
            @NotBlank String password) {
    }

    public record UserVo(Long id, String email, String name, String role) {
    }

    /** 个人中心：余额 + 账户内各维度统计（预订 / 票据 / 收藏 / 消息）。 */
    public record ProfileVo(
            Long id,
            String email,
            String name,
            String role,
            long walletCents,
            long totalSpentCents,
            long bookingCount,
            long ticketCount,
            long favouriteCount,
            long notificationCount) {
    }

    /** 演示充值：金额以分为单位，1–500,000 分（¥1–¥5,000）。 */
    public record WalletRechargeRequest(
            @Min(100) @Max(500000) int amountCents) {
    }

    public record LoginVo(String token, UserVo user) {
    }
}
