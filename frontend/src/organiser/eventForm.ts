/**
 * Form model for creating and editing an event.
 *
 * The whole model is strings — that is what DOM inputs hold, and it lets a
 * numeric field be genuinely empty instead of silently collapsing to 0.
 * Parsing, validation and the API mapping live here (pure functions) so the
 * page component only wires state to markup.
 *
 * Validation mirrors the server contract in `EventDtos.OrganiserEventRequest`
 * so an organiser sees the problem inline instead of a 400 after submitting.
 */

import { EventVo, isKnownCategory } from '../types'
import { addHoursToLocalInput, isoToLocalInput, localInputInDays, localInputToIso } from '../lib/datetime'
import i18n from '../i18n'

export interface EventFormState {
  title: string
  summary: string
  description: string
  category: string
  city: string
  venueName: string
  address: string
  startsAt: string
  endsAt: string
  salesStartAt: string
  salesEndAt: string
  priceEuro: string
  capacity: string
  maxQuantityPerBooking: string
  coverUrl: string
  contactInfo: string
  attendanceNotes: string
}

export type EventFormErrors = Partial<Record<keyof EventFormState, string>>

export const FIELD_LIMITS = {
  title: 200,
  summary: 300,
  city: 50,
  venueName: 200,
  address: 400,
  contactInfo: 300,
} as const

const DEFAULT_LEAD_DAYS = 7
const DEFAULT_DURATION_HOURS = 3

/** A new-event draft pre-filled with sane defaults an organiser can publish as-is. */
export function createInitialForm(): EventFormState {
  const startsAt = localInputInDays(DEFAULT_LEAD_DAYS)
  return {
    title: i18n.t('organiser.form.defaultTitle'),
    summary: '',
    description: i18n.t('organiser.form.defaultDescription'),
    category: 'music',
    city: 'Berlin',
    venueName: '',
    address: '',
    startsAt,
    endsAt: addHoursToLocalInput(startsAt, DEFAULT_DURATION_HOURS),
    salesStartAt: '',
    salesEndAt: '',
    priceEuro: '99',
    capacity: '50',
    maxQuantityPerBooking: '',
    coverUrl: '',
    contactInfo: '',
    attendanceNotes: '',
  }
}

/** Hydrates the form from an existing event so edits start from real values. */
export function formFromEvent(event: EventVo): EventFormState {
  return {
    title: event.title ?? '',
    summary: event.summary ?? '',
    description: event.description ?? '',
    // 存量活动可能带着白名单外的旧分类（分类固定之前是自由文本）。回落到
    // 'other' 而不是空串，否则下拉框会是空白，主办方一保存就把它写死成非法值。
    category: isKnownCategory(event.category) ? event.category : 'other',
    city: event.city ?? '',
    venueName: event.venueName ?? '',
    address: event.address ?? '',
    startsAt: isoToLocalInput(event.startsAt),
    endsAt: isoToLocalInput(event.endsAt),
    salesStartAt: isoToLocalInput(event.salesStartAt),
    salesEndAt: isoToLocalInput(event.salesEndAt),
    priceEuro: centsToEuroInput(event.priceCents),
    capacity: event.capacity == null ? '' : String(event.capacity),
    maxQuantityPerBooking:
      event.maxQuantityPerBooking == null || event.maxQuantityPerBooking <= 0
        ? ''
        : String(event.maxQuantityPerBooking),
    coverUrl: event.coverUrl ?? '',
    contactInfo: event.contactInfo ?? '',
    attendanceNotes: event.attendanceNotes ?? '',
  }
}

/** Cents → a euro string without trailing `.00` noise (`18000` → `180`). */
export function centsToEuroInput(cents?: number): string {
  if (cents == null || Number.isNaN(cents)) return ''
  const euro = cents / 100
  return Number.isInteger(euro) ? String(euro) : euro.toFixed(2)
}

/** Euro string → integer cents. `null` when the text is not a number. */
export function euroToCents(value: string): number | null {
  const trimmed = value.trim()
  if (trimmed === '') return null
  const euro = Number(trimmed)
  if (!Number.isFinite(euro)) return null
  return Math.round(euro * 100)
}

function parseInteger(value: string): number | null {
  const trimmed = value.trim()
  if (trimmed === '') return null
  const parsed = Number(trimmed)
  if (!Number.isFinite(parsed) || !Number.isInteger(parsed)) return null
  return parsed
}

function tooLong(value: string, limit: number) {
  return value.trim().length > limit
}

/** Field-level validation. An empty object means the draft is submittable. */
export function validateEventForm(form: EventFormState): EventFormErrors {
  const errors: EventFormErrors = {}

  if (!form.title.trim()) errors.title = i18n.t('organiser.form.needTitle')
  else if (tooLong(form.title, FIELD_LIMITS.title)) errors.title = i18n.t('organiser.form.titleTooLong', { max: FIELD_LIMITS.title })

  if (tooLong(form.summary, FIELD_LIMITS.summary)) errors.summary = i18n.t('organiser.form.summaryTooLong', { max: FIELD_LIMITS.summary })

  // 分类是固定白名单，不再是自由文本：只判断成员资格，长度校验没有意义了。
  if (!isKnownCategory(form.category)) errors.category = i18n.t('organiser.form.needCategory')

  if (!form.city.trim()) errors.city = i18n.t('organiser.form.needCity')
  else if (tooLong(form.city, FIELD_LIMITS.city)) errors.city = i18n.t('organiser.form.cityTooLong', { max: FIELD_LIMITS.city })

  if (tooLong(form.venueName, FIELD_LIMITS.venueName)) errors.venueName = i18n.t('organiser.form.venueTooLong', { max: FIELD_LIMITS.venueName })
  if (tooLong(form.address, FIELD_LIMITS.address)) errors.address = i18n.t('organiser.form.addressTooLong', { max: FIELD_LIMITS.address })
  if (tooLong(form.contactInfo, FIELD_LIMITS.contactInfo))
    errors.contactInfo = i18n.t('organiser.form.contactTooLong', { max: FIELD_LIMITS.contactInfo })

  const startsAt = localInputToIso(form.startsAt)
  if (!startsAt) errors.startsAt = i18n.t('organiser.form.needStart')

  const endsAt = localInputToIso(form.endsAt)
  if (form.endsAt && !endsAt) errors.endsAt = i18n.t('organiser.form.endInvalid')
  else if (startsAt && endsAt && endsAt <= startsAt) errors.endsAt = i18n.t('organiser.form.endAfterStart')

  const salesStartAt = localInputToIso(form.salesStartAt)
  const salesEndAt = localInputToIso(form.salesEndAt)
  if (salesStartAt && salesEndAt && salesEndAt <= salesStartAt) errors.salesEndAt = i18n.t('organiser.form.salesOrder')
  else if (salesEndAt && startsAt && salesEndAt > startsAt) errors.salesEndAt = i18n.t('organiser.form.salesBeforeStart')

  const cents = euroToCents(form.priceEuro)
  if (cents === null) errors.priceEuro = i18n.t('organiser.form.needPrice')
  else if (cents < 0) errors.priceEuro = i18n.t('organiser.form.priceNegative')

  const capacity = parseInteger(form.capacity)
  if (capacity === null) errors.capacity = i18n.t('organiser.form.needCapacity')
  else if (capacity < 1) errors.capacity = i18n.t('organiser.form.capacityMin')

  if (form.maxQuantityPerBooking.trim()) {
    const limit = parseInteger(form.maxQuantityPerBooking)
    if (limit === null || limit < 1) errors.maxQuantityPerBooking = i18n.t('organiser.form.limitMin')
    else if (capacity !== null && limit > capacity) errors.maxQuantityPerBooking = i18n.t('organiser.form.limitOver')
  }

  return errors
}

export interface OrganiserEventRequestBody {
  title: string
  summary: string | null
  description: string
  category: string
  city: string
  venueName: string | null
  address: string | null
  coverUrl: string | null
  startsAt: string | null
  endsAt: string | null
  salesStartAt: string | null
  salesEndAt: string | null
  priceCents: number
  capacity: number
  maxQuantityPerBooking: number | null
  contactInfo: string | null
  attendanceNotes: string | null
  version: number | null
  publish: boolean
}

function orNull(value: string): string | null {
  const trimmed = value.trim()
  return trimmed === '' ? null : trimmed
}

/**
 * Maps validated form state onto the create/update request body. `version` is
 * echoed back on edits so the server can reject a concurrent overwrite instead
 * of silently clobbering another organiser's change.
 */
export function toOrganiserRequest(
  form: EventFormState,
  { publish, version }: { publish: boolean; version?: number | null },
): OrganiserEventRequestBody {
  return {
    title: form.title.trim(),
    summary: orNull(form.summary),
    description: form.description.trim(),
    category: form.category.trim(),
    city: form.city.trim(),
    venueName: orNull(form.venueName),
    address: orNull(form.address),
    coverUrl: orNull(form.coverUrl),
    startsAt: localInputToIso(form.startsAt),
    endsAt: localInputToIso(form.endsAt),
    salesStartAt: localInputToIso(form.salesStartAt),
    salesEndAt: localInputToIso(form.salesEndAt),
    priceCents: euroToCents(form.priceEuro) ?? 0,
    capacity: Number(form.capacity.trim()),
    maxQuantityPerBooking: form.maxQuantityPerBooking.trim() ? Number(form.maxQuantityPerBooking.trim()) : null,
    contactInfo: orNull(form.contactInfo),
    attendanceNotes: orNull(form.attendanceNotes),
    version: version ?? null,
    publish,
  }
}

/** Publish readiness beyond raw validity — surfaced as soft warnings, never blocking. */
export function publishWarnings(form: EventFormState): string[] {
  const warnings: string[] = []
  if (!form.coverUrl.trim()) warnings.push(i18n.t('organiser.form.warnCover'))
  if (!form.summary.trim()) warnings.push(i18n.t('organiser.form.warnSummary'))
  if (form.description.trim().length < 20) warnings.push(i18n.t('organiser.form.warnDesc'))
  if (!form.venueName.trim()) warnings.push(i18n.t('organiser.form.warnVenue'))
  return warnings
}
