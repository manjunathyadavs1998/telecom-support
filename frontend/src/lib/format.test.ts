import { describe, expect, it } from 'vitest'
import { formatDate, formatDateTime, formatInr, isPositiveMoney } from './format'

describe('formatInr (XC-05)', () => {
  it('groups rupees the Indian way and keeps the paise', () => {
    expect(formatInr('123456.70')).toBe('₹1,23,456.70')
    expect(formatInr('499.00')).toBe('₹499.00')
    expect(formatInr('0.00')).toBe('₹0.00')
    expect(formatInr('10000000.05')).toBe('₹1,00,00,000.05')
  })

  it('keeps every digit of the largest amount (no floating point)', () => {
    expect(formatInr('9999999999.99')).toBe('₹9,99,99,99,999.99')
    expect(formatInr('9007199254.99')).toBe('₹9,00,71,99,254.99')
  })

  it('returns a value that is not a Money string unchanged', () => {
    expect(formatInr('12.5')).toBe('12.5')
    expect(formatInr('')).toBe('')
  })
})

describe('isPositiveMoney', () => {
  it.each([
    ['0.00', false],
    ['0000.00', false],
    ['0.01', true],
    ['499.00', true],
    ['9999999999.99', true],
    ['12.5', false],
    ['-1.00', false],
    ['1,000.00', false],
    ['12345678901.00', false],
    ['', false],
  ])('%s → %s', (value, expected) => {
    expect(isPositiveMoney(value)).toBe(expected)
  })
})

describe('formatDateTime (Asia/Kolkata)', () => {
  it('shows the IST date and time', () => {
    expect(formatDateTime('2026-11-25T10:00:00Z')).toBe('25 Nov 2026, 3:30 pm')
  })

  it('moves to the next IST day across midnight', () => {
    expect(formatDateTime('2026-11-25T18:29:00Z')).toBe('25 Nov 2026, 11:59 pm')
    expect(formatDateTime('2026-11-25T18:31:00Z')).toBe('26 Nov 2026, 12:01 am')
    expect(formatDateTime('2026-12-31T19:00:00Z')).toBe('1 Jan 2027, 12:30 am')
  })

  it('shows an exclusive validTo at IST midnight as 12:00 am of that day, not the day before (F23)', () => {
    expect(formatDateTime('2026-11-30T18:30:00Z')).toBe('1 Dec 2026, 12:00 am')
    expect(formatDateTime('2026-12-01T00:00:00+05:30')).toBe('1 Dec 2026, 12:00 am')
  })

  it('shows noon as 12 pm and keeps the server microseconds harmless', () => {
    expect(formatDateTime('2026-11-25T06:30:00.123456Z')).toBe('25 Nov 2026, 12:00 pm')
  })

  it('returns an unparsable value unchanged', () => {
    expect(formatDateTime('not a date')).toBe('not a date')
  })
})

describe('formatDate (date-only)', () => {
  it('formats the calendar date without a zone shift', () => {
    expect(formatDate('2026-11-25')).toBe('25 Nov 2026')
    expect(formatDate('2026-01-01')).toBe('1 Jan 2026')
    expect(formatDate('2026-12-31')).toBe('31 Dec 2026')
  })

  it('returns anything else unchanged', () => {
    expect(formatDate('2026-13-01')).toBe('2026-13-01')
    expect(formatDate('25/11/2026')).toBe('25/11/2026')
  })
})
