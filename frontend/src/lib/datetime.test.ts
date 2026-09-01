import { describe, expect, it } from 'vitest'
import {
  addHoursToLocalInput,
  formatDayTime,
  isoToLocalInput,
  localInputInDays,
  localInputToIso,
  relativeTime,
} from './datetime'

describe('isoToLocalInput', () => {
  it('renders an instant as a zero-padded datetime-local value', () => {
    // Arrange: build the instant from a local wall-clock time so the
    // assertion holds in any timezone the suite runs in.
    const iso = new Date(2026, 8, 5, 9, 7).toISOString()

    // Act
    const value = isoToLocalInput(iso)

    // Assert
    expect(value).toBe('2026-09-05T09:07')
  })

  it('returns an empty string for missing or unparsable input', () => {
    expect(isoToLocalInput(undefined)).toBe('')
    expect(isoToLocalInput(null)).toBe('')
    expect(isoToLocalInput('not-a-date')).toBe('')
  })
})

describe('localInputToIso', () => {
  it('round-trips a datetime-local value back to the same instant', () => {
    const iso = new Date(2027, 0, 15, 20, 30).toISOString()
    expect(localInputToIso('2027-01-15T20:30')).toBe(iso)
  })

  it('returns null for empty or invalid values', () => {
    expect(localInputToIso('')).toBeNull()
    expect(localInputToIso('nope')).toBeNull()
  })
})

describe('localInputInDays', () => {
  it('snaps to the requested hour on the requested day', () => {
    const value = localInputInDays(7, 20)
    const expected = new Date(Date.now() + 7 * 86_400_000)
    expected.setHours(20, 0, 0, 0)
    expect(value).toBe(isoToLocalInput(expected.toISOString()))
  })
})

describe('addHoursToLocalInput', () => {
  it('adds whole hours', () => {
    expect(addHoursToLocalInput('2026-09-05T21:00', 3)).toBe('2026-09-06T00:00')
  })

  it('passes through empty and invalid values', () => {
    expect(addHoursToLocalInput('', 3)).toBe('')
    expect(addHoursToLocalInput('nope', 3)).toBe('')
  })
})

describe('relativeTime', () => {
  const now = new Date(2026, 8, 1, 12, 0).getTime()

  it('labels future distances in the largest sensible unit', () => {
    expect(relativeTime(new Date(now + 30 * 60_000).toISOString(), now)).toBe('30 分钟后')
    expect(relativeTime(new Date(now + 5 * 3_600_000).toISOString(), now)).toBe('5 小时后')
    expect(relativeTime(new Date(now + 3 * 86_400_000).toISOString(), now)).toBe('3 天后')
  })

  it('labels past distances', () => {
    expect(relativeTime(new Date(now - 2 * 3_600_000).toISOString(), now)).toBe('2 小时前')
  })

  it('collapses sub-minute distances to 刚刚', () => {
    expect(relativeTime(new Date(now + 10_000).toISOString(), now)).toBe('刚刚')
  })

  it('returns an empty string for missing or invalid input', () => {
    expect(relativeTime(undefined, now)).toBe('')
    expect(relativeTime('nope', now)).toBe('')
  })
})

describe('formatDayTime', () => {
  it('formats a short calendar label', () => {
    expect(formatDayTime(new Date(2026, 8, 10, 20, 0).toISOString())).toContain('9')
  })

  it('returns an empty string for missing or invalid input', () => {
    expect(formatDayTime(undefined)).toBe('')
    expect(formatDayTime('nope')).toBe('')
  })
})
