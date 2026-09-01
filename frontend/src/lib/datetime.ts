/**
 * Conversions between ISO-8601 instants (what the API speaks) and the local
 * `YYYY-MM-DDTHH:mm` strings that `<input type="datetime-local">` speaks.
 * Both directions go through the browser's local timezone deliberately: an
 * organiser schedules an event in the venue's wall-clock time, not in UTC.
 */

const MINUTE_MS = 60_000

function pad(value: number) {
  return String(value).padStart(2, '0')
}

/** ISO instant → `datetime-local` value. Returns '' for missing/invalid input. */
export function isoToLocalInput(iso?: string | null): string {
  if (!iso) return ''
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return ''
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}`
  )
}

/** `datetime-local` value → ISO instant. Returns null for empty/invalid input. */
export function localInputToIso(value: string): string | null {
  if (!value) return null
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return null
  return date.toISOString()
}

/** A `datetime-local` value `days` from now, snapped to `hour:00` local time. */
export function localInputInDays(days: number, hour = 20): string {
  const date = new Date(Date.now() + days * 24 * 60 * MINUTE_MS)
  date.setHours(hour, 0, 0, 0)
  return isoToLocalInput(date.toISOString())
}

/** Adds whole hours to a `datetime-local` value; '' when the input is empty. */
export function addHoursToLocalInput(value: string, hours: number): string {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  return isoToLocalInput(new Date(date.getTime() + hours * 60 * MINUTE_MS).toISOString())
}

/** Coarse "3 天后 / 2 小时前" label used on cards and tables. */
export function relativeTime(iso?: string | null, now = Date.now()): string {
  if (!iso) return ''
  const target = new Date(iso).getTime()
  if (Number.isNaN(target)) return ''
  const diffMinutes = Math.round((target - now) / MINUTE_MS)
  const future = diffMinutes >= 0
  const magnitude = Math.abs(diffMinutes)
  const [value, unit] =
    magnitude < 60
      ? [magnitude, '分钟']
      : magnitude < 60 * 24
        ? [Math.round(magnitude / 60), '小时']
        : [Math.round(magnitude / (60 * 24)), '天']
  if (value === 0) return '刚刚'
  return future ? `${value} ${unit}后` : `${value} ${unit}前`
}

/** Short calendar label, e.g. `9月10日 20:00`. */
export function formatDayTime(iso?: string | null): string {
  if (!iso) return ''
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return ''
  return date.toLocaleString('zh-CN', { month: 'long', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}
