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

