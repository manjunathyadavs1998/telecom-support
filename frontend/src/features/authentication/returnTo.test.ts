import { describe, expect, it } from 'vitest'
import { meFixtures } from '@/test/fixtures/me'
import { afterLoginTarget, safeReturnTo } from './returnTo'

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

  // Beyond the §7.1 list: other Unicode whitespace, C1, DEL, NUL, `/login` variants, data URLs and scheme tricks.
  it.each([
    ['no-break space', '/ /evil.example'],
    ['NEL (C1)', '/\u0085/evil.example'],
    ['DEL', '/\u007F/x'],
    ['NUL', '/\u0000/x'],
    ['carriage return', '/\r/evil.example'],
    ['line separator', '/ /x'],
    ['trailing space', '/x '],
    ['login with a hash', '/login#x'],
    ['bare login', '/login'],
    ['data URL', 'data:text/html,<script>alert(1)</script>'],
    ['relative path', 'outbound'],
    ['double backslash', '\\\\evil.example'],
    ['triple slash', '///evil.example'],
    ['upper-case scheme', 'HTTP://evil.example'],
  ])('also rejects %s (%j)', (_name, raw) => {
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

describe('afterLoginTarget with hostile returnTo values (frontend.md §7 After login)', () => {
  it.each(['//evil.example', '/\\evil.example', 'https://evil.example/x', 'javascript:alert(1)', '/\t/x', '%2F%2Fevil.example', '/login?x=1'])(
    'sends the customer to their landing route for %j',
    (raw) => {
      expect(afterLoginTarget(raw, meFixtures.customer, ORIGIN)).toBe('/customer')
    },
  )

  it('keeps a safe, permitted returnTo and drops a safe but forbidden one', () => {
    expect(afterLoginTarget('/customer/plan?tab=1#top', meFixtures.customer, ORIGIN)).toBe('/customer/plan?tab=1#top')
    expect(afterLoginTarget('/outbound', meFixtures.customer, ORIGIN)).toBe('/customer')
  })
})
