import { describe, expect, it } from 'vitest'
import { safeReturnTo } from './returnTo'

const ORIGIN = 'http://localhost:8080'
const LANDING = '/outbound'

describe('safeReturnTo (frontend.md §7.1, NFR-SEC)', () => {
  // The full §7.1 reject list, each falling back to the role landing page.
  it.each([
    ['protocol-relative', '//evil.example'],
    ['slash-backslash', '/\\evil.example'],
    ['backslash-slash', '\\/evil.example'],
    ['literal tab', '/\t/evil.example'],
    ['literal newline', '/\n/evil.example'],
    ['inner space', '/ /x'],
    ['leading space', ' /x'],
    ['absolute URL', 'https://evil.example/x'],
    ['javascript: URL', 'javascript:alert(1)'],
    ['encoded protocol-relative', '%2F%2Fevil.example'],
    ['the login page', '/login?x=1'],
    ['empty', ''],
  ])('rejects %s (%j)', (_name, raw) => {
    expect(safeReturnTo(raw, LANDING, ORIGIN)).toBe(LANDING)
  })

  it.each([null, undefined])('rejects a missing value (%s)', (raw) => {
    expect(safeReturnTo(raw, LANDING, ORIGIN)).toBe(LANDING)
  })

  it.each([
    ['/outbound', '/outbound'],
    ['/customer/plan?tab=1#top', '/customer/plan?tab=1#top'],
    // Stays a same-origin path (the router shows NotFound), never a protocol-relative redirect.
    ['/%2F%2Fevil.example', '/%2F%2Fevil.example'],
  ])('accepts %j', (raw, expected) => {
    expect(safeReturnTo(raw, '/customer', ORIGIN)).toBe(expected)
  })

  it('defaults to the current window origin', () => {
    expect(safeReturnTo('/customer/profile', LANDING)).toBe('/customer/profile')
    expect(safeReturnTo('//evil.example', LANDING)).toBe(LANDING)
  })
})
