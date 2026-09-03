package dev.kaiwen.eventpulse.controller;

import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.kaiwen.eventpulse.common.BaseContext;
import dev.kaiwen.eventpulse.common.Result;
import dev.kaiwen.eventpulse.dto.BookingDtos.BookingVo;
import dev.kaiwen.eventpulse.dto.CartDtos.AddCartItemRequest;
import dev.kaiwen.eventpulse.dto.CartDtos.CartVo;
import dev.kaiwen.eventpulse.dto.CartDtos.CheckoutRequest;
import dev.kaiwen.eventpulse.dto.CartDtos.CheckoutVo;
import dev.kaiwen.eventpulse.dto.CartDtos.UpdateCartItemRequest;
import dev.kaiwen.eventpulse.service.BookingService;
import dev.kaiwen.eventpulse.service.CartService;
import dev.kaiwen.eventpulse.service.CheckoutService;

import jakarta.validation.Valid;

/**
 * 购物车：身份一律取自服务端登录上下文，请求里没有用户 ID。
 * 金额只是展示；结算时后端重新校验活动、库存、数量与价格。
 */
@RestController
@Profile("api")
@RequestMapping("/api/cart")
public class CartController {

    private final CartService cartService;
    private final CheckoutService checkoutService;
    private final BookingService bookingService;

    public CartController(CartService cartService, CheckoutService checkoutService, BookingService bookingService) {
        this.cartService = cartService;
        this.checkoutService = checkoutService;
        this.bookingService = bookingService;
    }

    @GetMapping
    public Result<CartVo> view() {
        return Result.success(cartService.view());
    }

    @PostMapping("/items")
    public Result<CartVo> add(@Valid @RequestBody AddCartItemRequest request) {
        return Result.success(cartService.add(request.eventId(), request.quantity()));
    }

    @PatchMapping("/items/{id}")
    public Result<CartVo> update(@PathVariable Long id, @Valid @RequestBody UpdateCartItemRequest request) {
        return Result.success(cartService.update(id, request.quantity(), request.selected()));
    }

    @DeleteMapping("/items/{id}")
    public Result<CartVo> remove(@PathVariable Long id) {
        return Result.success(cartService.remove(id));
    }

    @DeleteMapping
    public Result<CartVo> clear() {
        return Result.success(cartService.clear());
    }

    /** 价格变化后的用户确认：把购物车价格快照刷新为当前价。 */
    @PostMapping("/refresh-prices")
    public Result<CartVo> refreshPrices() {
        return Result.success(cartService.refreshPrices());
    }

    /**
     * 批量结算勾选项。必须携带 Idempotency-Key 请求头（客户端生成、重试复用）：
     * 相同键与相同参数返回同一结算结果（购物车已清空也一样），
     * 同键不同参数拒绝；多活动整次成功或整次回滚。
     */
    @PostMapping("/checkout")
    public Result<CheckoutVo> checkout(@Valid @RequestBody CheckoutRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        Long userId = BaseContext.getUserId();
        CheckoutService.Settlement settlement = checkoutService.settleCart(userId, idempotencyKey, request.items());
        List<BookingVo> bookingVos = bookingService.toVoList(settlement.bookings());
        return Result.success(new CheckoutVo(
                settlement.checkoutId(), settlement.reused(), bookingVos, settlement.totalPaidCents()));
    }
}
