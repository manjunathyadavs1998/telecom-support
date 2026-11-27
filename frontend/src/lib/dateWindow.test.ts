import { describe, expect, it } from 'vitest'
import { daysBetween, defaultWindow, MAX_RANGE_DAYS, minusMonths, todayIst, validateRange } from './dateWindow'

describe('todayIst', () => {
  it('is the Asia/Kolkata date, which runs ahead of UTC after 18:30 UTC', () => {
    expect(todayIst(new Date('2026-11-25T18:29:59Z'))).toBe('2026-11-25')
    expect(todayIst(new Date('2026-11-25T18:30:00Z'))).toBe('2026-11-26')
    expect(todayIst(new Date('2026-12-31T20:00:00Z'))).toBe('2027-01-01')
  })
})

describe('defaultWindow (BR-05/06)', () => {
  it('is the last 3 months up to today (IST)', () => {
    expect(defaultWindow(new Date('2026-11-26T06:00:00Z'))).toEqual({ from: '2026-08-26', to: '2026-11-26' })
  })

  it('uses the IST date across midnight', () => {
    expect(defaultWindow(new Date('2026-11-25T19:00:00Z'))).toEqual({ from: '2026-08-26', to: '2026-11-26' })
  })

  it('clamps to the end of a shorter month and crosses the year', () => {
    expect(minusMonths('2026-05-31', 3)).toBe('2026-02-28')
    expect(minusMonths('2028-05-31', 3)).toBe('2028-02-29')
    expect(minusMonths('2027-01-15', 3)).toBe('2026-10-15')
  })
})

describe('daysBetween', () => {
  it('counts calendar days, including over a leap day', () => {
    expect(daysBetween('2027-12-31', '2028-03-01')).toBe(61)
    expect(daysBetween('2026-11-26', '2026-11-25')).toBe(-1)
    expect(daysBetween('2026-02-30', '2026-03-01')).toBeNull()
  })
})

describe('validateRange (frontend.md §6)', () => {
  const today = '2026-11-26'

  it('accepts a range within the rules, including a single day and today', () => {
    expect(validateRange({ from: '2026-11-26', to: '2026-11-26' }, { today })).toBeNull()
    expect(validateRange({ from: '2026-01-01', to: '2026-03-31' }, { today })).toBeNull()
  })

  it('accepts 366 days and refuses 367', () => {
    expect(MAX_RANGE_DAYS).toBe(366)
    expect(validateRange({ from: '2025-11-25', to: '2026-11-26' }, { today })).toBeNull()
    expect(validateRange({ from: '2025-11-24', to: '2026-11-26' }, { today })).toEqual({
      field: 'to',
      message: 'The date range can be at most 366 days.',
    })
  })

  it('refuses a future end date', () => {
    expect(validateRange({ from: '2026-11-01', to: '2026-11-27' }, { today })).toEqual({
      field: 'to',
      message: "The end date can't be in the future.",
    })
  })

  it('refuses a start after the end', () => {
    expect(validateRange({ from: '2026-11-10', to: '2026-11-09' }, { today })).toEqual({
      field: 'from',
      message: 'The start date must be on or before the end date.',
    })
  })

  it('requires both dates and real calendar dates', () => {
    expect(validateRange({ to: '2026-11-01' }, { today })?.field).toBe('from')
    expect(validateRange({ from: '2026-11-01', to: '' }, { today })?.field).toBe('to')
    expect(validateRange({ from: '2026-02-30', to: '2026-03-01' }, { today })).toEqual({
      field: 'from',
      message: 'Enter a valid start date.',
    })
    expect(validateRange({ from: '2026-03-01', to: '03/04/2026' }, { today })?.message).toBe('Enter a valid end date.')
  })

  it('uses the IST date as today by default', () => {
    expect(validateRange({ from: '2000-01-01', to: '2999-01-01' })?.message).toBe("The end date can't be in the future.")
  })
})
