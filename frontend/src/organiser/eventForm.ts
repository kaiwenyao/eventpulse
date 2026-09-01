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

import { EventVo } from '../types'
import { addHoursToLocalInput, isoToLocalInput, localInputInDays, localInputToIso } from '../lib/datetime'

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
  priceYuan: string
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
  category: 50,
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
    title: '新活动',
    summary: '',
    description: '主办方创建的活动',
    category: 'music',
    city: '上海',
    venueName: '',
    address: '',
    startsAt,
    endsAt: addHoursToLocalInput(startsAt, DEFAULT_DURATION_HOURS),
    salesStartAt: '',
    salesEndAt: '',
    priceYuan: '99',
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
    category: event.category ?? '',
    city: event.city ?? '',
    venueName: event.venueName ?? '',
    address: event.address ?? '',
    startsAt: isoToLocalInput(event.startsAt),
    endsAt: isoToLocalInput(event.endsAt),
    salesStartAt: isoToLocalInput(event.salesStartAt),
    salesEndAt: isoToLocalInput(event.salesEndAt),
    priceYuan: centsToYuanInput(event.priceCents),
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

/** Cents → a yuan string without trailing `.00` noise (`18000` → `180`). */
export function centsToYuanInput(cents?: number): string {
  if (cents == null || Number.isNaN(cents)) return ''
  const yuan = cents / 100
  return Number.isInteger(yuan) ? String(yuan) : yuan.toFixed(2)
}

/** Yuan string → integer cents. `null` when the text is not a number. */
export function yuanToCents(value: string): number | null {
  const trimmed = value.trim()
  if (trimmed === '') return null
  const yuan = Number(trimmed)
  if (!Number.isFinite(yuan)) return null
  return Math.round(yuan * 100)
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

  if (!form.title.trim()) errors.title = '请填写活动标题'
  else if (tooLong(form.title, FIELD_LIMITS.title)) errors.title = `标题不能超过 ${FIELD_LIMITS.title} 个字`

  if (tooLong(form.summary, FIELD_LIMITS.summary)) errors.summary = `摘要不能超过 ${FIELD_LIMITS.summary} 个字`

  if (!form.category.trim()) errors.category = '请选择或填写分类'
  else if (tooLong(form.category, FIELD_LIMITS.category)) errors.category = `分类不能超过 ${FIELD_LIMITS.category} 个字`

  if (!form.city.trim()) errors.city = '请填写城市'
  else if (tooLong(form.city, FIELD_LIMITS.city)) errors.city = `城市不能超过 ${FIELD_LIMITS.city} 个字`

  if (tooLong(form.venueName, FIELD_LIMITS.venueName)) errors.venueName = `场地名称不能超过 ${FIELD_LIMITS.venueName} 个字`
  if (tooLong(form.address, FIELD_LIMITS.address)) errors.address = `详细地址不能超过 ${FIELD_LIMITS.address} 个字`
  if (tooLong(form.contactInfo, FIELD_LIMITS.contactInfo))
    errors.contactInfo = `联系方式不能超过 ${FIELD_LIMITS.contactInfo} 个字`

  const startsAt = localInputToIso(form.startsAt)
  if (!startsAt) errors.startsAt = '请选择活动开始时间'

  const endsAt = localInputToIso(form.endsAt)
  if (form.endsAt && !endsAt) errors.endsAt = '结束时间格式不正确'
  else if (startsAt && endsAt && endsAt <= startsAt) errors.endsAt = '结束时间必须晚于开始时间'

  const salesStartAt = localInputToIso(form.salesStartAt)
  const salesEndAt = localInputToIso(form.salesEndAt)
  if (salesStartAt && salesEndAt && salesEndAt <= salesStartAt) errors.salesEndAt = '停售时间必须晚于开售时间'
  else if (salesEndAt && startsAt && salesEndAt > startsAt) errors.salesEndAt = '停售时间不能晚于活动开始时间'

  const cents = yuanToCents(form.priceYuan)
  if (cents === null) errors.priceYuan = '请填写票价，免费活动填 0'
  else if (cents < 0) errors.priceYuan = '票价不能为负数'

  const capacity = parseInteger(form.capacity)
  if (capacity === null) errors.capacity = '请填写库存容量'
  else if (capacity < 1) errors.capacity = '容量必须大于零'

  if (form.maxQuantityPerBooking.trim()) {
    const limit = parseInteger(form.maxQuantityPerBooking)
    if (limit === null || limit < 1) errors.maxQuantityPerBooking = '单笔限购至少为 1 张'
    else if (capacity !== null && limit > capacity) errors.maxQuantityPerBooking = '单笔限购不能超过总容量'
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
    priceCents: yuanToCents(form.priceYuan) ?? 0,
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
  if (!form.coverUrl.trim()) warnings.push('还没有上传封面图，活动卡片会显示占位背景')
  if (!form.summary.trim()) warnings.push('补一句摘要，列表页会更吸引人')
  if (form.description.trim().length < 20) warnings.push('活动介绍偏短，建议补充亮点与流程')
  if (!form.venueName.trim()) warnings.push('填写场地名称，观众更容易找到现场')
  return warnings
}
