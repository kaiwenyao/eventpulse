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

export interface BookingVo {
  id: number
  eventId: number
  eventTitle: string
  quantity: number
  status: string
  createdAt: string
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
  { key: 'music', label: '音乐' },
  { key: 'tech', label: '科技' },
  { key: 'sports', label: '运动' },
  { key: 'art', label: '艺术' },
] as const

export const CATEGORY_LABELS: Record<string, string> = Object.fromEntries(
  CATEGORIES.map((c) => [c.key, c.label]),
)

/** Event lifecycle: DRAFT → PUBLISHED → ONGOING → FINISHED → ARCHIVED (CANCELLED is terminal). */
export const EVENT_STATUSES = [
  { key: 'DRAFT', label: '草稿' },
  { key: 'PUBLISHED', label: '已发布' },
  { key: 'ONGOING', label: '进行中' },
  { key: 'FINISHED', label: '已结束' },
  { key: 'CANCELLED', label: '已取消' },
  { key: 'ARCHIVED', label: '已归档' },
] as const

export const EVENT_STATUS_LABELS: Record<string, string> = Object.fromEntries(
  EVENT_STATUSES.map((s) => [s.key, s.label]),
)

export const BOOKING_STATUS_LABELS: Record<string, string> = {
  PENDING: '待确认',
  CONFIRMED: '已确认',
  CANCELLED: '已取消',
  REFUNDED: '已退款',
  FAILED: '失败',
}

export const TICKET_STATUS_LABELS: Record<string, string> = {
  VALID: '未使用',
  CHECKED_IN: '已核销',
  CANCELLED: '已作废',
  EXPIRED: '已过期',
}
