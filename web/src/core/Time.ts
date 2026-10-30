// Kotlin's Time.kt and java.time collapse into this. An instant is epoch seconds (Swift `Date.timeIntervalSince1970`),
// a duration is seconds, both plain numbers; never a `Date` (milliseconds would cut the fixture's precision). The local
// calendar is the device's time zone, read through `Date`'s local fields.

export type Instant = number

/** `Instant.now()`. */
export function now(): Instant {
  return Date.now() / 1000
}

/**
 * Local midnight of the calendar day (y, m0 + 1, d): `LocalDate.atStartOfDay(zone)`. m0 is 0-based and the fields may
 * overflow (day 32 is the 1st of the next month), as `new Date` allows. Where a DST change skips midnight (Cairo, the last
 * Friday of April) the day starts at the first time that exists, 01:00, as in Java and Swift; older V8 builds put
 * `new Date(y, m, d)` in such a gap at 23:00 the day before, so step forward until the date is right.
 */
function midnight(y: number, m0: number, d: number): Instant {
  const noon = new Date(y, m0, d, 12)   // normalizes overflow; no zone has a gap at noon
  const day = noon.getDate()
  let t = new Date(noon.getFullYear(), noon.getMonth(), day)
  for (let i = 0; i < 96 && t.getDate() !== day; i++) t = new Date(t.getTime() + 15 * 60 * 1000)
  return t.getTime() / 1000
}

/** `t.atZone(zone).toLocalDate().atStartOfDay(zone)`: the start of t's local day. */
export function startOfDay(t: Instant): Instant {
  const d = new Date(t * 1000)
  return midnight(d.getFullYear(), d.getMonth(), d.getDate())
}

/** `localDate.plusDays(n).atStartOfDay(zone)`: local midnight n calendar days after `dayStart`'s day (a DST day is 23 or 25 hours, so never `+ n * 86400`). */
export function addDays(dayStart: Instant, n: number): Instant {
  const d = new Date(dayStart * 1000)
  return midnight(d.getFullYear(), d.getMonth(), d.getDate() + n)
}

/** `ZonedDateTime.year / monthValue / dayOfMonth`: t's local date, m 1…12. */
export function localYMD(t: Instant): { y: number; m: number; d: number } {
  const d = new Date(t * 1000)
  return { y: d.getFullYear(), m: d.getMonth() + 1, d: d.getDate() }
}

/** `t.atZone(zone).year`. */
export function localYear(t: Instant): number {
  return new Date(t * 1000).getFullYear()
}

/** `t.atZone(zone).hour`: 0…23. */
export function localHour(t: Instant): number {
  return new Date(t * 1000).getHours()
}
