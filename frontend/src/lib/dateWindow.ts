// List date window (02-customer/frontend.md §4, §6; 00-overview §3; BR-05/06, XC-05). Dates are `YYYY-MM-DD` calendar dates in
// Asia/Kolkata, inclusive at both ends. Day arithmetic uses UTC midnights of those calendar dates, so no time zone can shift them.
import { istFields } from './format'

/** The backend's limit: `to - from` is at most 366 days (00-overview §3). */
export const MAX_RANGE_DAYS = 366
/** Lists default to the last 3 months (BR-05/06). */
export const DEFAULT_WINDOW_MONTHS = 3

export type DateRange = { from: string; to: string }
export type RangeProblem = { field: 'from' | 'to'; message: string }

const YMD = /^(\d{4})-(\d{2})-(\d{2})$/
const pad = (value: number, width = 2) => String(value).padStart(width, '0')

function toYmd(year: number, month: number, day: number): string {
  return `${pad(year, 4)}-${pad(month)}-${pad(day)}`
}

/** UTC epoch day of a valid `YYYY-MM-DD`, or null when the value is not a real calendar date. */
function epochDay(ymd: string): number | null {
  const match = YMD.exec(ymd)
  if (!match) return null
  const year = Number(match[1])
  const month = Number(match[2])
  const day = Number(match[3])
  const ms = Date.UTC(year, month - 1, day)
  const back = new Date(ms)
  if (back.getUTCFullYear() !== year || back.getUTCMonth() !== month - 1 || back.getUTCDate() !== day) return null
  return ms / 86_400_000
}

/** Today's date in Asia/Kolkata. */
export function todayIst(now: Date = new Date()): string {
  const { year, month, day } = istFields(now)
  return toYmd(year, month, day)
}

/** `ymd` minus `months` calendar months; the day is clamped to the target month's length (like Java `minusMonths`). */
export function minusMonths(ymd: string, months: number): string {
  const match = YMD.exec(ymd)
  if (!match) throw new RangeError(`Not a date: ${ymd}`)
  const total = Number(match[1]) * 12 + (Number(match[2]) - 1) - months
  const year = Math.floor(total / 12)
  const month = (total % 12) + 1
  const lastDay = new Date(Date.UTC(year, month, 0)).getUTCDate()
  return toYmd(year, month, Math.min(Number(match[3]), lastDay))
}

/** The default list window: the last 3 months up to today (IST), the same window the server applies without `from`/`to`. */
export function defaultWindow(now: Date = new Date()): DateRange {
  const to = todayIst(now)
  return { from: minusMonths(to, DEFAULT_WINDOW_MONTHS), to }
}

/** Whole days from `from` to `to` (negative when `to` is earlier), or null if either is not a real date. */
export function daysBetween(from: string, to: string): number | null {
  const a = epochDay(from)
  const b = epochDay(to)
  return a === null || b === null ? null : b - a
}

/**
 * The custom range rules of frontend.md §6: both dates required and real; `from ≤ to`; `to` not after today (IST); at most
 * `maxDays` (366) days from `from` to `to`. Returns the first problem, or null when the range is valid.
 */
export function validateRange(
  range: { from?: string | null; to?: string | null },
  { today = todayIst(), maxDays = MAX_RANGE_DAYS }: { today?: string; maxDays?: number } = {},
): RangeProblem | null {
  const from = range.from ?? ''
  const to = range.to ?? ''
  if (from === '') return { field: 'from', message: 'Enter a start date.' }
  if (epochDay(from) === null) return { field: 'from', message: 'Enter a valid start date.' }
  if (to === '') return { field: 'to', message: 'Enter an end date.' }
  if (epochDay(to) === null) return { field: 'to', message: 'Enter a valid end date.' }
  const span = daysBetween(from, to)!
  if (span < 0) return { field: 'from', message: 'The start date must be on or before the end date.' }
  if (daysBetween(today, to)! > 0) return { field: 'to', message: "The end date can't be in the future." }
  if (span > maxDays) return { field: 'to', message: `The date range can be at most ${maxDays} days.` }
  return null
}
