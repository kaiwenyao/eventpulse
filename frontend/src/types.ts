/**
 * Shared view models mirroring the backend DTOs in
 * `dev.kaiwen.eventpulse.dto.EventDtos` / `BookingDtos`. Kept in one place so
 * pages and the organiser console cannot drift apart on field names.
 */

export interface EventVo {
  id: number
  title: string
  summary?: string
  description: string
  category: string
  city: string
  venueName?: string
  address?: string
  latitude?: number
  longitude?: number
  startsAt: string
  endsAt?: string
  coverUrl?: string
  salesStartAt?: string
  salesEndAt?: string
  priceCents: number
  capacity: number
  sold: number
  remaining: number
  status: string
  maxQuantityPerBooking?: number
  favourite?: boolean
  bookable?: boolean
  unbookableReason?: string
  version?: number
  contactInfo?: string
  attendanceNotes?: string
  cancellationReason?: string
  updatedAt?: string
  createdAt?: string
}

export interface RelatedBookingVo {
  id: number
  eventId: number
  eventTitle?: string
  quantity: number
  paidCents: number
  status?: string
}

/**
 * 订单视图：金额一律来自订单快照（paidCents / unitPriceCents），历史订单不会
 * 按当前活动价重算。eventStatus / 票据计数是独立维度，不会写成新的支付状态。
 */
export interface BookingVo {
  id: number
  eventId: number
  eventTitle: string
  eventStatus?: string
  eventStartsAt?: string
  quantity: number
  unitPriceCents?: number
  paidCents: number
  status: string
  createdAt: string
  cancelledAt?: string
  organiserNote?: string
  checkedInCount?: number
  validCount?: number
  /** 已退款金额（取消订单 = paidCents）；refundLedgerId 指向关联退款流水。 */
  refundCents?: number
  refundLedgerId?: number
  /** 同一次购物车结算的关联标识。 */
  checkoutId?: number
  cancellable?: boolean
  /** 不可取消原因（机器键，i18n 渲染）。 */
  cancelBlockReason?: string
  relatedBookings?: RelatedBookingVo[] | null
}

export interface LedgerVo {
  id: number
  bizType: string
  /** 带正负号的变动金额（分）。 */
  amountCents: number
  balanceBeforeCents: number
  balanceAfterCents: number
  bookingId?: number
  checkoutId?: number
  description?: string
  seqNo: number
  createdAt: string
}

/** 单个购物车项的失效原因（机器键，i18n 渲染）。 */
export type CartIssueKey =
  | 'EVENT_NOT_FOUND'
  | 'EVENT_CANCELLED'
  | 'EVENT_NOT_OPEN'
  | 'SALES_NOT_STARTED'
  | 'SALES_ENDED'
  | 'EVENT_STARTED'
  | 'SOLD_OUT'
  | 'PRICE_CHANGED'
  | 'OVER_LIMIT'
  | 'LOW_STOCK'

export interface CartItemVo {
  id: number
  eventId: number
  eventTitle?: string
  eventStatus?: string
  startsAt?: string
  quantity: number
  unitPriceCents: number
  currentUnitPriceCents: number
  lineTotalCents: number
  selected: boolean
  maxQuantityPerBooking: number
  remaining: number
  issues: CartIssueKey[]
}

export interface CartVo {
  items: CartItemVo[]
  selectedTotalCents: number
  hasIssues: boolean
}

export interface CheckoutVo {
  checkoutId?: number
  reused?: boolean
  bookings: BookingVo[]
  totalPaidCents: number
}

export interface UserProfile {
  id: number
  email: string
  name: string
  role: string
  walletCents: number
  totalSpentCents: number
  bookingCount: number
  ticketCount: number
  favouriteCount: number
  notificationCount: number
}

export interface NotificationVo {
  id: number
  bookingId?: number
  type?: string
  title?: string
  message: string
  createdAt: string
}

export interface TicketVo {
  id: number
  code?: string
  status: string
}

export interface AttendeeRow {
  bookingId: number
  ticketId: number
  name: string
  email: string
  status: string
  checkedInAt?: string
}

export interface OrganiserDashboardVo {
  eventCount?: number
  publishedCount?: number
  sold?: number
  capacity?: number
  sellThrough?: number
  lowStock?: string[]
  outboxPending?: number
}

export interface PageVo<T> {
  records?: T[]
  total?: number
}

export const CATEGORIES = [
  { key: 'music' },
  { key: 'tech' },
  { key: 'sports' },
  { key: 'art' },
] as const

/** Event lifecycle: DRAFT → PUBLISHED → ONGOING → FINISHED → ARCHIVED (CANCELLED is terminal). */
export const EVENT_STATUSES = [
  { key: 'DRAFT' },
  { key: 'PUBLISHED' },
  { key: 'ONGOING' },
  { key: 'FINISHED' },
  { key: 'CANCELLED' },
  { key: 'ARCHIVED' },
] as const

