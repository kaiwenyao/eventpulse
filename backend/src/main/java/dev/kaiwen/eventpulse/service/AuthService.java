package dev.kaiwen.eventpulse.service;

import java.util.List;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.kaiwen.eventpulse.dto.AuthDtos.LoginRequest;
import dev.kaiwen.eventpulse.dto.AuthDtos.LoginVo;
import dev.kaiwen.eventpulse.dto.AuthDtos.ProfileVo;
import dev.kaiwen.eventpulse.dto.AuthDtos.RegisterRequest;
import dev.kaiwen.eventpulse.dto.AuthDtos.UserVo;
import dev.kaiwen.eventpulse.dto.AuthDtos.WalletRechargeRequest;
import dev.kaiwen.eventpulse.entity.Booking;
import dev.kaiwen.eventpulse.entity.User;
import dev.kaiwen.eventpulse.entity.WalletLedger;
import dev.kaiwen.eventpulse.exception.BusinessException;
import dev.kaiwen.eventpulse.repository.BookingRepository;
import dev.kaiwen.eventpulse.repository.EventFavouriteRepository;
import dev.kaiwen.eventpulse.repository.NotificationRepository;
import dev.kaiwen.eventpulse.repository.TicketRepository;
import dev.kaiwen.eventpulse.repository.UserRepository;
import dev.kaiwen.eventpulse.service.WalletService;

import jakarta.persistence.EntityManager;

@Service
public class AuthService {

    /** 幂等键长度上限：external_biz_id 列宽 120，需容纳 "RECHARGE:{userId}:" 前缀。 */
    private static final int MAX_RECHARGE_KEY_LENGTH = 90;

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final BookingRepository bookings;
    private final TicketRepository tickets;
    private final EventFavouriteRepository favourites;
    private final NotificationRepository notifications;
    private final WalletService wallets;
    private final EntityManager entityManager;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService,
            BookingRepository bookings, TicketRepository tickets,
            EventFavouriteRepository favourites, NotificationRepository notifications,
            WalletService wallets, EntityManager entityManager) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.bookings = bookings;
        this.tickets = tickets;
        this.favourites = favourites;
        this.notifications = notifications;
        this.wallets = wallets;
        this.entityManager = entityManager;
    }

    @Transactional
    public LoginVo register(RegisterRequest request) {
        if (users.existsByEmail(request.email())) {
            throw new BusinessException("Email is already registered");
        }
        User user = new User();
        user.setEmail(request.email());
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setName(request.name());
        user.setRole("USER");
        user.setWalletCents(0);
        users.save(user);
        return toLoginVo(user);
    }

    public LoginVo login(LoginRequest request) {
        User user = users.findByEmail(request.email())
                .orElseThrow(() -> new BusinessException("Invalid email or password"));
        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new BusinessException("Invalid email or password");
        }
        return toLoginVo(user);
    }

    public UserVo me(Long userId) {
        return toUserVo(requireUser(userId));
    }

    /** 个人中心汇总：余额、累计消费与各维度的账户统计。 */
    @Transactional(readOnly = true)
    public ProfileVo profile(Long userId) {
        User user = requireUser(userId);
        List<Booking> mine = bookings.findByUserIdOrderByCreatedAtDesc(userId);
        List<Long> bookingIds = mine.stream().map(Booking::getId).toList();
        long spent = mine.stream()
                .filter(b -> "CONFIRMED".equals(b.getStatus()))
                .mapToLong(Booking::getPaidCents)
                .sum();
        long ticketCount = bookingIds.isEmpty() ? 0 : tickets.countByBookingIdIn(bookingIds);
        return new ProfileVo(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getRole(),
                user.getWalletCents(),
                spent,
                mine.size(),
                ticketCount,
                favourites.countByUserId(userId),
                notifications.countByUserId(userId));
    }

    @Transactional
    public ProfileVo recharge(Long userId, WalletRechargeRequest request, String idempotencyKey) {
        User user = requireUser(userId);
        // 演示充值幂等：键按用户隔离（external_biz_id 全局唯一，不带用户前缀会让
        // 不同用户的相同键互相吞掉充值）；同键同金额重试不重复入账，同键不同金额拒绝；
        // 不带键的两笔充值各自成功。余额、流水与 wallet-events Outbox 在同一事务提交。
        String bizId;
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            bizId = "RECHARGE:" + java.util.UUID.randomUUID();
        }
        else {
            String key = idempotencyKey.trim();
            if (key.length() > MAX_RECHARGE_KEY_LENGTH) {
                throw new BusinessException("Idempotency key is too long");
            }
            bizId = "RECHARGE:" + userId + ":" + key;
        }
        wallets.creditOnce(userId, request.amountCents(), WalletLedger.TYPE_RECHARGE, bizId,
                "Demo wallet recharge");
        // 余额由 WalletService 的 JdbcTemplate 原子更新，绕过了持久化上下文；
        // 把刚加载的 User 从一级缓存脱离（旧实现 rechargeWalletWithinLimit 的
        // clearAutomatically 在这里承担同样职责），profile 才能读到新余额而不是旧快照。
        entityManager.detach(user);
        return profile(userId);
    }

    private User requireUser(Long userId) {
        if (userId == null) {
            throw new BusinessException("Please sign in");
        }
        return users.findById(userId).orElseThrow(() -> BusinessException.notFound("User not found"));
    }

    private LoginVo toLoginVo(User user) {
        return new LoginVo(jwtService.createToken(user.getId(), user.getRole()), toUserVo(user));
    }

    private static UserVo toUserVo(User user) {
        return new UserVo(user.getId(), user.getEmail(), user.getName(), user.getRole());
    }
}
