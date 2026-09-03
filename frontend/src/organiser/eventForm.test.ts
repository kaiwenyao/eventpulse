import { describe, expect, it } from 'vitest'
import { isoToLocalInput, localInputToIso } from '../lib/datetime'
import { EventVo } from '../types'
import {
  centsToEuroInput,
  createInitialForm,
  EventFormState,
  formFromEvent,
  publishWarnings,
  toOrganiserRequest,
  validateEventForm,
  euroToCents,
} from './eventForm'

function formWith(overrides: Partial<EventFormState> = {}): EventFormState {
  return { ...createInitialForm(), ...overrides }
}

describe('createInitialForm', () => {
  it('produces a draft that already passes validation', () => {
    expect(validateEventForm(createInitialForm())).toEqual({})
  })

  it('schedules the end time after the start time', () => {
    const form = createInitialForm()
    expect(localInputToIso(form.endsAt)! > localInputToIso(form.startsAt)!).toBe(true)
  })
})

describe('euroToCents / centsToEuroInput', () => {
  it('converts euro text to integer cents', () => {
    expect(euroToCents('180')).toBe(18000)
    expect(euroToCents('9.99')).toBe(999)
    expect(euroToCents(' 0 ')).toBe(0)
  })

  it('returns null for empty or non-numeric text', () => {
    expect(euroToCents('')).toBeNull()
    expect(euroToCents('free')).toBeNull()
  })

  it('renders cents without trailing .00 noise', () => {
    expect(centsToEuroInput(18000)).toBe('180')
    expect(centsToEuroInput(999)).toBe('9.99')
    expect(centsToEuroInput(undefined)).toBe('')
  })
})

describe('validateEventForm', () => {
  it('requires the fields the server marks @NotBlank/@NotNull', () => {
    const errors = validateEventForm(formWith({ title: '  ', category: '', city: '', startsAt: '' }))
    expect(errors.title).toBe('请填写活动标题')
    expect(errors.category).toBe('请选择或填写分类')
    expect(errors.city).toBe('请填写城市')
    expect(errors.startsAt).toBe('请选择活动开始时间')
  })

  it('enforces the server length limits', () => {
    const errors = validateEventForm(formWith({ title: 'a'.repeat(201), summary: 'b'.repeat(301) }))
    expect(errors.title).toContain('200')
    expect(errors.summary).toContain('300')
  })

  it('rejects an end time at or before the start time', () => {
    expect(validateEventForm(formWith({ startsAt: '2027-03-01T20:00', endsAt: '2027-03-01T19:00' })).endsAt).toBe(
      '结束时间必须晚于开始时间',
    )
    expect(validateEventForm(formWith({ startsAt: '2027-03-01T20:00', endsAt: '2027-03-01T20:00' })).endsAt).toBe(
      '结束时间必须晚于开始时间',
    )
  })

  it('rejects a malformed end time', () => {
    expect(validateEventForm(formWith({ endsAt: 'nope' })).endsAt).toBe('结束时间格式不正确')
  })

  it('keeps the sales window inside the event window', () => {
    expect(
      validateEventForm(formWith({ salesStartAt: '2027-03-01T10:00', salesEndAt: '2027-03-01T09:00' })).salesEndAt,
    ).toBe('停售时间必须晚于开售时间')

    expect(
      validateEventForm(formWith({ startsAt: '2027-03-01T20:00', endsAt: '', salesEndAt: '2027-03-02T10:00' }))
        .salesEndAt,
    ).toBe('停售时间不能晚于活动开始时间')
  })

  it('requires a numeric price and a capacity of at least one', () => {
    expect(validateEventForm(formWith({ priceEuro: '' })).priceEuro).toBe('请填写票价，免费活动填 0')
    expect(validateEventForm(formWith({ priceEuro: '-1' })).priceEuro).toBe('票价不能为负数')
    expect(validateEventForm(formWith({ capacity: '' })).capacity).toBe('请填写库存容量')
    expect(validateEventForm(formWith({ capacity: '0' })).capacity).toBe('容量必须大于零')
    expect(validateEventForm(formWith({ capacity: '1.5' })).capacity).toBe('请填写库存容量')
  })

  it('keeps the per-booking limit between 1 and the capacity', () => {
    expect(validateEventForm(formWith({ maxQuantityPerBooking: '0' })).maxQuantityPerBooking).toBe('单笔限购至少为 1 张')
    expect(validateEventForm(formWith({ capacity: '10', maxQuantityPerBooking: '11' })).maxQuantityPerBooking).toBe(
      '单笔限购不能超过总容量',
    )
    expect(validateEventForm(formWith({ capacity: '10', maxQuantityPerBooking: '10' })).maxQuantityPerBooking).toBeUndefined()
  })

  it('flags over-long optional text fields', () => {
    const errors = validateEventForm(
      formWith({ venueName: 'v'.repeat(201), address: 'a'.repeat(401), contactInfo: 'c'.repeat(301) }),
    )
    expect(errors.venueName).toContain('200')
    expect(errors.address).toContain('400')
    expect(errors.contactInfo).toContain('300')
  })
})

describe('formFromEvent', () => {
  const event: EventVo = {
    id: 8,
    title: 'Indie Rock Night',
    summary: 'A live show',
    description: 'd',
    category: 'music',
    city: 'Berlin',
    venueName: 'Sound Space',
    address: '一号路',
    startsAt: new Date(2027, 2, 1, 19, 30).toISOString(),
    endsAt: new Date(2027, 2, 1, 22, 0).toISOString(),
    priceCents: 18000,
    capacity: 100,
    sold: 10,
    remaining: 90,
    status: 'PUBLISHED',
    maxQuantityPerBooking: 4,
    version: 7,
  }

  it('hydrates every field the editor exposes', () => {
    const form = formFromEvent(event)
    expect(form.title).toBe('Indie Rock Night')
    expect(form.priceEuro).toBe('180')
    expect(form.capacity).toBe('100')
    expect(form.maxQuantityPerBooking).toBe('4')
    expect(form.startsAt).toBe(isoToLocalInput(event.startsAt))
    expect(form.endsAt).toBe(isoToLocalInput(event.endsAt))
  })

  it('normalises absent optional values to empty strings', () => {
    const form = formFromEvent({ ...event, summary: undefined, venueName: undefined, maxQuantityPerBooking: 0 })
    expect(form.summary).toBe('')
    expect(form.venueName).toBe('')
    expect(form.maxQuantityPerBooking).toBe('')
  })
})

describe('toOrganiserRequest', () => {
  it('maps the form onto the API contract, trimming and nulling blanks', () => {
    const body = toOrganiserRequest(
      formWith({
        title: '  Indie Rock Night  ',
        summary: '   ',
        city: 'Berlin',
        priceEuro: '9.99',
        capacity: '80',
        maxQuantityPerBooking: '',
        startsAt: '2027-03-01T19:30',
        endsAt: '',
      }),
      { publish: true, version: 7 },
    )

    expect(body.title).toBe('Indie Rock Night')
    expect(body.summary).toBeNull()
    expect(body.priceCents).toBe(999)
    expect(body.capacity).toBe(80)
    expect(body.maxQuantityPerBooking).toBeNull()
    expect(body.startsAt).toBe(new Date('2027-03-01T19:30').toISOString())
    expect(body.endsAt).toBeNull()
    expect(body.version).toBe(7)
    expect(body.publish).toBe(true)
  })

  it('sends a null version when creating', () => {
    expect(toOrganiserRequest(formWith(), { publish: false }).version).toBeNull()
  })
})

describe('publishWarnings', () => {
  it('lists the soft gaps that weaken a listing', () => {
    const warnings = publishWarnings(formWith({ coverUrl: '', summary: '', description: '短', venueName: '' }))
    expect(warnings).toHaveLength(4)
  })

  it('is empty once the listing is complete', () => {
    const warnings = publishWarnings(
      formWith({
        coverUrl: '/api/media/images/1',
        summary: 'A live show',
        description: 'A long enough description of the lineup, flow, and what to expect.',
        venueName: 'Sound Space',
      }),
    )
    expect(warnings).toEqual([])
  })
})
