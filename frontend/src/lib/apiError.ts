import i18n from '../i18n'
import { ApiError } from '../api'

/**
 * 后端错误消息 → 本地化文案的映射层。
 *
 * 后端 `BusinessException` 目前只带一个英文裸串（见 `exception/BusinessException.java`），
 * 没有稳定错误码，所以这里只能按消息文本匹配。未命中的一律透传原始 `msg` ——
 * 保证行为不会比「直接显示后端消息」更差。
 *
 * TODO: 后端给 `BusinessException` 补 `code` 字段、`Result` envelope 下发 `errorCode`
 * 之后，把 EXACT / PATTERNS 两张表换成按码索引，本文件的字符串匹配即可整体删除。
 *
 * 覆盖范围：观众侧流程（钱包 / 购物车 / 结算 / 订单 / 登录 / 消息）全量映射。
 * 主办方后台专有的错误（活动生命周期、图片上传、核销）保持透传 —— 那些是运营界面，
 * 英文原文可读，且逐条翻译会把这张表撑到无法维护。
 */

/** 已解析的错误：文案已本地化，动作可直接渲染成链接。 */
export interface ResolvedError {
  message: string
  action?: { label: string; to: string }
}

interface ErrorSpec {
  /** i18n key，位于 `errors.*` 命名空间下。 */
  key: string
  /** 可选跳转动作，用于把「报错」变成「下一步做什么」。 */
  action?: { labelKey: string; to: string }
}

const TOP_UP_ACTION = { labelKey: 'errors.action.topUp', to: '/profile' } as const
const SIGN_IN_ACTION = { labelKey: 'errors.action.signIn', to: '/login' } as const
const CART_ACTION = { labelKey: 'errors.action.openCart', to: '/cart' } as const

/** 后端消息全等匹配。key 必须与 Java 侧字面量逐字一致。 */
const EXACT: Readonly<Record<string, ErrorSpec>> = {
  // ---- 钱包 ----
  'Insufficient wallet balance': { key: 'errors.insufficientBalance', action: TOP_UP_ACTION },
  'Wallet balance exceeds the limit': { key: 'errors.walletLimit' },
  'Amount must be positive': { key: 'errors.amountPositive' },
  'Idempotency key is too long': { key: 'errors.idempotencyTooLong' },
  'Idempotency key was already used with a different amount': { key: 'errors.idempotencyAmount' },
  'Idempotency key was already used with a different request': { key: 'errors.idempotencyRequest' },

  // ---- 账号 ----
  'Please sign in': { key: 'errors.signInRequired', action: SIGN_IN_ACTION },
  'Invalid email or password': { key: 'errors.badCredentials' },
  'Email is already registered': { key: 'errors.emailTaken' },
  'User not found': { key: 'errors.userNotFound' },

  // ---- 购物车 / 结算 ----
  'Cart item not found': { key: 'errors.cartItemMissing' },
  'Cart was changed on another device, please refresh': { key: 'errors.cartStale' },
  'Cart item quantity was changed on another device, please refresh': { key: 'errors.cartItemStale' },
  'Checkout is being processed, retry shortly': { key: 'errors.checkoutInFlight' },
  'Checkout not found': { key: 'errors.checkoutMissing' },
  'Price has changed, please review and confirm the new price': { key: 'errors.priceChanged', action: CART_ACTION },

  // ---- 订单 / 电子票 ----
  'Booking not found': { key: 'errors.bookingMissing' },
  'Booking already cancelled': { key: 'errors.bookingAlreadyCancelled' },
  'A ticket has already been checked in, refund is not allowed': { key: 'errors.refundBlockedCheckedIn' },
  'You can only view your own bookings': { key: 'errors.notYourBooking' },
  'You can only subscribe to your own bookings': { key: 'errors.notYourBooking' },
  'Ticket not found': { key: 'errors.ticketMissing' },

  // ---- 活动 ----
  'Event not found': { key: 'errors.eventMissing' },
  'Event has started or cannot be cancelled in its current state': { key: 'errors.eventNotCancellable' },
  'Event was modified by someone else, refresh and try again': { key: 'errors.eventStale' },

  // ---- 消息 ----
  'Notification not found': { key: 'errors.notificationMissing' },
  'You can only read your own notifications': { key: 'errors.notYourNotification' },

  // ---- 限流 ----
  'Too many live connections for this user': { key: 'errors.tooManyConnections' },
  'Too many live connections for this booking': { key: 'errors.tooManyConnections' },
  'Too many AI requests, please try again in a minute': { key: 'errors.aiRateLimited' },
}

/**
 * 带动态数值的消息。后端用字符串拼接（`"Maximum " + maxQty + " tickets…"`），
 * 所以用捕获组把数字取出来喂给 i18n 插值。
 */
const PATTERNS: ReadonlyArray<{ test: RegExp; spec: ErrorSpec; params: readonly string[] }> = [
  { test: /^Maximum (\d+) tickets per booking/, spec: { key: 'errors.maxPerBooking' }, params: ['max'] },
  { test: /^Only (\d+) tickets left/, spec: { key: 'errors.ticketsLeft' }, params: ['count'] },
  { test: /^Quantity must be between 1 and (\d+)$/, spec: { key: 'errors.quantityRange' }, params: ['max'] },
  { test: /^Cart is full: at most (\d+) events per cart$/, spec: { key: 'errors.cartFull' }, params: ['max'] },
  { test: /^Wallet entry already recorded: /, spec: { key: 'errors.walletDuplicate' }, params: [] },
]

function lookup(message: string): { spec: ErrorSpec; values: Record<string, string> } | null {
  const exact = EXACT[message]
  if (exact) return { spec: exact, values: {} }

  for (const { test, spec, params } of PATTERNS) {
    const match = message.match(test)
    if (!match) continue
    const values: Record<string, string> = {}
    params.forEach((name, index) => {
      values[name] = match[index + 1] ?? ''
    })
    return { spec, values }
  }
  return null
}

/**
 * 把任意 catch 到的值转成可直接渲染的提示。
 *
 * @param error       catch 到的值（可能是 ApiError、Error，或任何东西）
 * @param fallbackKey 非 ApiError 时使用的 i18n key，例如 `'bookings.cancelFailed'`
 */
export function resolveApiError(error: unknown, fallbackKey: string): ResolvedError {
  if (!(error instanceof ApiError)) {
    return { message: i18n.t(fallbackKey) }
  }

  const hit = lookup(error.message)
  if (!hit) {
    // 未知消息：透传后端原文，绝不吞掉。
    return { message: error.message || i18n.t(fallbackKey) }
  }

  const { spec, values } = hit
  return {
    message: i18n.t(spec.key, values),
    action: spec.action ? { label: i18n.t(spec.action.labelKey), to: spec.action.to } : undefined,
  }
}
