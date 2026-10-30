// Web-only stand-ins for what Kotlin's stdlib and org.json give the Kotlin port for free. Chromium 68 (webOS 5) is the
// floor: no Array.flat, Object.fromEntries, String.replaceAll, crypto.randomUUID, and Array.sort isn't stable before 70.

/** Kotlin `sortedWith`: a sorted copy, stable (equal elements keep their order) on every engine. */
export function stableSort<T>(a: readonly T[], comparator: (x: T, y: T) => number): T[] {
  const tagged = a.map((v, i) => ({ v, i }))
  tagged.sort((x, y) => comparator(x.v, y.v) || x.i - y.i)
  return tagged.map(t => t.v)
}

/** Kotlin `compareValues` for numbers: null sorts first. */
export function compareValues(a: number | null | undefined, b: number | null | undefined): number {
  if (a == null || b == null) return a == null ? (b == null ? 0 : -1) : 1
  return a < b ? -1 : a > b ? 1 : 0
}

/** Kotlin `String.replace(old, new)`: every occurrence (JS `replace(string, string)` replaces only the first). */
export function replaceAll(s: string, old: string, replacement: string): string {
  if (old === '') return replacement + s.split('').join(replacement) + (s ? replacement : '')   // Kotlin inserts at every position
  return s.split(old).join(replacement)
}

/** `UUID.randomUUID().toString().uppercase()`: a v4 UUID from `crypto.getRandomValues` (randomUUID is Chromium 92+). */
export function uuid(): string {
  const b = crypto.getRandomValues(new Uint8Array(16))
  b[6] = (b[6] & 0x0f) | 0x40
  b[8] = (b[8] & 0x3f) | 0x80
  let h = ''
  for (let i = 0; i < 16; i++) h += (b[i] + 0x100).toString(16).slice(1) + (i === 3 || i === 5 || i === 7 || i === 9 ? '-' : '')
  return h.toUpperCase()
}

/** Kotlin `String.toIntOrNull()`: optional sign, ASCII digits only, 32-bit range. Never `parseInt` ("12abc" → 12) or `+s` ("" → 0). */
export function toIntOrNull(s: string | null | undefined): number | null {
  if (s == null || !/^[+-]?\d+$/.test(s)) return null
  const n = Number(s)
  return n >= -2147483648 && n <= 2147483647 ? n : null
}

/** Kotlin `String.toUShortOrNull()`: optional '+', ASCII digits, 0…65535. */
export function toUShortOrNull(s: string | null | undefined): number | null {
  if (s == null || !/^\+?\d+$/.test(s)) return null
  const n = Number(s)
  return n <= 65535 ? n : null
}

// ---- org.json, Android flavour ----
// `JSON.parse` plus these helpers, which copy Android's org.json leniency so a server quirk the Android app tolerates works
// here too: numbers and booleans read as strings, numeric strings as numbers (fractions truncated for ints), "true"/"false"
// as booleans. Unlike Android, a JSON null never reads as the string "null". A missing key and JSON null are the same.

export type JSONObject = { [key: string]: unknown }
export type JSONArray = unknown[]

export class JSONException extends Error {
  constructor(message: string) { super(message); this.name = 'JSONException' }
}

/** The key's value; own properties only, so a key like "constructor" never reads Object.prototype. */
function value(o: JSONObject, key: string): unknown {
  return Object.prototype.hasOwnProperty.call(o, key) ? o[key] : undefined
}

export function isObject(v: unknown): v is JSONObject {
  return typeof v === 'object' && v !== null && !Array.isArray(v)
}

/** Kotlin `it as JSONObject` inside an `HTTP.json` decoder. */
export function asObject(v: unknown): JSONObject {
  if (!isObject(v)) throw new JSONException('not a JSON object')
  return v
}

/** Kotlin `it as JSONArray`. */
export function asArray(v: unknown): JSONArray {
  if (!Array.isArray(v)) throw new JSONException('not a JSON array')
  return v
}

function str(v: unknown, what: string): string {
  if (typeof v === 'string') return v
  if (typeof v === 'number' || typeof v === 'boolean') return String(v)
  throw new JSONException(`${what} is not a string`)
}

function num(v: unknown, what: string): number {
  const n = typeof v === 'number' ? v : typeof v === 'string' && v.trim() !== '' ? Number(v) : NaN
  if (!isFinite(n)) throw new JSONException(`${what} is not a number`)
  return n
}

function bool(v: unknown, what: string): boolean {
  if (typeof v === 'boolean') return v
  if (typeof v === 'string') {
    const l = v.toLowerCase()
    if (l === 'true' || l === 'false') return l === 'true'
  }
  throw new JSONException(`${what} is not a boolean`)
}

function required(o: JSONObject, key: string): unknown {
  const v = value(o, key)
  if (v == null) throw new JSONException(`No value for ${key}`)
  return v
}

export const has = (o: JSONObject, key: string): boolean => Object.prototype.hasOwnProperty.call(o, key)
export const getString = (o: JSONObject, key: string): string => str(required(o, key), key)
export const getInt = (o: JSONObject, key: string): number => Math.trunc(num(required(o, key), key))
export const getLong = getInt
export const getDouble = (o: JSONObject, key: string): number => num(required(o, key), key)
export const getBoolean = (o: JSONObject, key: string): boolean => bool(required(o, key), key)
export const getJSONObject = (o: JSONObject, key: string): JSONObject => asObject(required(o, key))
export const getJSONArray = (o: JSONObject, key: string): JSONArray => asArray(required(o, key))

// null for a missing key or JSON null (Swift `decodeIfPresent`); a value of the wrong type throws, as in Kotlin.
export const stringOrNull = (o: JSONObject, key: string): string | null => { const v = value(o, key); return v == null ? null : str(v, key) }
export const intOrNull = (o: JSONObject, key: string): number | null => { const v = value(o, key); return v == null ? null : Math.trunc(num(v, key)) }
export const longOrNull = intOrNull
export const doubleOrNull = (o: JSONObject, key: string): number | null => { const v = value(o, key); return v == null ? null : num(v, key) }
export const boolOrNull = (o: JSONObject, key: string): boolean | null => { const v = value(o, key); return v == null ? null : bool(v, key) }
export const objectOrNull = (o: JSONObject, key: string): JSONObject | null => { const v = value(o, key); return v == null ? null : asObject(v) }
export const arrayOrNull = (o: JSONObject, key: string): JSONArray | null => { const v = value(o, key); return v == null ? null : asArray(v) }

// org.json `opt*`: the fallback for a missing key, JSON null or a value of the wrong type.
export function optString(o: JSONObject, key: string, fallback = ''): string { try { return stringOrNull(o, key) ?? fallback } catch { return fallback } }
export function optInt(o: JSONObject, key: string, fallback = 0): number { try { return intOrNull(o, key) ?? fallback } catch { return fallback } }
export function optBoolean(o: JSONObject, key: string, fallback = false): boolean { try { return boolOrNull(o, key) ?? fallback } catch { return fallback } }
export function optJSONObject(o: JSONObject, key: string): JSONObject | null { const v = value(o, key); return isObject(v) ? v : null }
export function optJSONArray(o: JSONObject, key: string): JSONArray | null { const v = value(o, key); return Array.isArray(v) ? v : null }

/** Kotlin `JSONArray.objects()`: every element must be an object. */
export function objects(a: JSONArray): JSONObject[] { return a.map(asObject) }
/** Kotlin `JSONObject.objectsOrNull(key)`. */
export function objectsOrNull(o: JSONObject, key: string): JSONObject[] | null { const a = arrayOrNull(o, key); return a ? objects(a) : null }

/** Kotlin `JSONArray.strings()` / `JSONObject.strings(key)` (missing or null key = empty list). */
export function strings(a: JSONArray): string[]
export function strings(o: JSONObject, key: string): string[]
export function strings(a: JSONArray | JSONObject, key?: string): string[] {
  const arr = key === undefined ? asArray(a) : arrayOrNull(a as JSONObject, key) ?? []
  return arr.map((v, i) => str(v, `[${i}]`))
}

/** org.json `putOpt`: sets `key` only when `v` isn't null/undefined, so `toJson` shapes omit nulls like the Kotlin ones. */
export function putOpt(o: JSONObject, key: string, v: unknown): JSONObject {
  if (v != null) o[key] = v
  return o
}
