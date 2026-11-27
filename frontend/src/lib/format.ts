// Money and date display helpers (02-customer/frontend.md §4, XC-05). Display only: amounts stay server strings and are never
// used in arithmetic (F8); dates and times are shown in Asia/Kolkata.

export const IST_TIME_ZONE = 'Asia/Kolkata'

const MONEY = /^(\d{1,10})\.(\d{2})$/
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'] as const

const inrFormat = new Intl.NumberFormat('en-IN', {
  style: 'currency',
  currency: 'INR',
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
})

/**
 * A `Money` string (`^\d{1,10}\.\d{2}$`) → `₹1,23,456.70`. The rupees are grouped by `Intl.NumberFormat('en-IN')` from a
 * `BigInt` and the paise are copied from the string, so no floating-point value is ever made. Anything that is not a
 * `Money` string is returned unchanged.
 */
export function formatInr(amount: string): string {
  const match = MONEY.exec(amount)
  if (!match) return amount
  const paise = match[2]!
  return inrFormat
    .formatToParts(BigInt(match[1]!))
    .map((part) => (part.type === 'fraction' ? paise : part.value))
    .join('')
}

/** True for a `Money` string greater than zero (`"0.00"` is not positive). */
export function isPositiveMoney(amount: string): boolean {
  return MONEY.test(amount) && /[1-9]/.test(amount)
}

const istParts = new Intl.DateTimeFormat('en-US', {
  timeZone: IST_TIME_ZONE,
  year: 'numeric',
  month: 'numeric',
  day: 'numeric',
  hour: 'numeric',
  minute: 'numeric',
  hourCycle: 'h23',
})

/** The Asia/Kolkata calendar fields of an instant. */
export function istFields(instant: Date): { year: number; month: number; day: number; hour: number; minute: number } {
  const fields: Record<string, number> = {}
  for (const part of istParts.formatToParts(instant)) {
    if (part.type !== 'literal') fields[part.type] = Number(part.value)
  }
  return {
    year: fields.year!,
    month: fields.month!,
    day: fields.day!,
    hour: fields.hour! % 24,
    minute: fields.minute!,
  }
}

/** An ISO date-time (any offset) → `25 Nov 2026, 3:30 pm` in Asia/Kolkata. An unparsable value is returned unchanged. */
export function formatDateTime(iso: string): string {
  const instant = new Date(iso)
  if (Number.isNaN(instant.getTime())) return iso
  const { year, month, day, hour, minute } = istFields(instant)
  const hour12 = hour % 12 === 0 ? 12 : hour % 12
  const period = hour < 12 ? 'am' : 'pm'
  return `${day} ${MONTHS[month - 1]} ${year}, ${hour12}:${String(minute).padStart(2, '0')} ${period}`
}

/**
 * A date-only value `YYYY-MM-DD` → `25 Nov 2026`, read as a calendar date: it is never converted through a time zone, so it
 * cannot shift a day. Anything else is returned unchanged.
 */
export function formatDate(ymd: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(ymd)
  if (!match) return ymd
  const month = Number(match[2])
  const day = Number(match[3])
  if (month < 1 || month > 12 || day < 1 || day > 31) return ymd
  return `${day} ${MONTHS[month - 1]} ${match[1]}`
}
