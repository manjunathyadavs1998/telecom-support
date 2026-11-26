import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from './errors'
import {
  clearIdempotencyRecords,
  IDEMPOTENCY_RECORD_TTL_MS,
  idempotencyStorageKey,
  type IdempotencyKeyOptions,
  type IdempotentOperation,
  stableStringify,
  useIdempotencyKey,
  useSubmitGuard,
} from './idempotency'

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
const PAY_PATH = '/customers/me/payments'
const PAY_BODY = { invoiceId: 801, expectedAmount: '589.82' }

const apiError = (status: number) => new ApiError({ status, problem: null, correlationId: null })

type HookProps = { operation: IdempotentOperation; path: string; options?: IdempotencyKeyOptions }

function renderKeyHook(initial: HookProps) {
  return renderHook((props: HookProps) => useIdempotencyKey(props.operation, props.path, props.options), {
    initialProps: initial,
  })
}

beforeEach(() => sessionStorage.clear())
afterEach(() => sessionStorage.clear())

describe('stableStringify', () => {
  it('ignores key order and undefined fields at every level', () => {
    expect(stableStringify({ b: 1, a: { d: [1, { y: 2, x: 1 }], c: undefined } })).toBe(
      stableStringify({ a: { d: [1, { x: 1, y: 2 }] }, b: 1 }),
    )
  })
})

describe('useIdempotencyKey (NFR-REL)', () => {
  it('returns the same UUID v4 for the same operation, path and body, whatever the key order', () => {
    const { result } = renderKeyHook({ operation: 'CASE_CREATE', path: '/customers/me/cases' })
    const first = result.current.keyFor({ subject: 'Hello', categoryCode: 'BILLING' })
    expect(first).toMatch(UUID_V4)
    expect(result.current.keyFor({ categoryCode: 'BILLING', subject: 'Hello' })).toBe(first)
  })

  it('makes a new key when the body changes', () => {
    const { result } = renderKeyHook({ operation: 'CASE_CREATE', path: '/customers/me/cases' })
    const first = result.current.keyFor({ subject: 'Hello' })
    expect(result.current.keyFor({ subject: 'Hello again' })).not.toBe(first)
  })

  it('makes a new key for the same body on another path (path-scoped, F7)', () => {
    const body = { subscriptionId: 101, acceptTerms: true, termsHash: 'a'.repeat(64) }
    const { result, rerender } = renderKeyHook({
      operation: 'PROMOTION_CLAIM',
      path: '/customers/me/promotions/DATA10/claims',
    })
    const first = result.current.keyFor(body)
    rerender({ operation: 'PROMOTION_CLAIM', path: '/customers/me/promotions/OTT3M/claims' })
    const second = result.current.keyFor(body)
    expect(second).not.toBe(first)
    rerender({ operation: 'PROMOTION_CLAIM', path: '/customers/me/promotions/DATA10/claims' })
    expect(result.current.keyFor(body)).not.toBe(first)
  })

  it('drops the key after a 2xx', () => {
    const { result } = renderKeyHook({ operation: 'PLAN_CHANGE', path: '/customers/me/subscriptions/101/plan-changes' })
    const body = { targetPlanCode: 'POST799', expectedEffectiveDate: '2026-12-01', expectedCharge: '0.00' }
    const first = result.current.keyFor(body)
    result.current.succeeded()
    expect(result.current.keyFor(body)).not.toBe(first)
  })

  it.each([400, 404, 422])('drops the key after a %i (incl. idempotency-mismatch)', (status) => {
    const { result } = renderKeyHook({ operation: 'CASE_CREATE', path: '/customers/me/cases' })
    const first = result.current.keyFor({ subject: 'Hello' })
    result.current.failed(apiError(status))
    expect(result.current.keyFor({ subject: 'Hello' })).not.toBe(first)
  })

  it.each([409, 500, 503, 0])('keeps the key after a %i so the retry is safe (0 = network)', (status) => {
    const { result } = renderKeyHook({ operation: 'CASE_CREATE', path: '/customers/me/cases' })
    const first = result.current.keyFor({ subject: 'Hello' })
    result.current.failed(apiError(status))
    expect(result.current.keyFor({ subject: 'Hello' })).toBe(first)
  })

  it('keeps the key after a non-API error', () => {
    const { result } = renderKeyHook({ operation: 'CASE_CREATE', path: '/customers/me/cases' })
    const first = result.current.keyFor({ subject: 'Hello' })
    result.current.failed(new TypeError('Failed to fetch'))
    expect(result.current.keyFor({ subject: 'Hello' })).toBe(first)
  })

  it('stores nothing without persist', () => {
    const { result } = renderKeyHook({ operation: 'CASE_CREATE', path: '/customers/me/cases' })
    result.current.keyFor({ subject: 'Hello' })
    expect(sessionStorage.length).toBe(0)
    expect(result.current.storedRecord()).toBeNull()
  })
})

describe('useIdempotencyKey with persist (E15, F3)', () => {
  const props = (scope: number): HookProps => ({
    operation: 'PAYMENT_INITIATE',
    path: PAY_PATH,
    options: { persist: true, scope },
  })
  const storageKey = idempotencyStorageKey('PAYMENT_INITIATE', PAY_PATH, 801)

  it('stores {key, bodyHash, savedAt} in sessionStorage under idem:<operation>:<path>:<scope>, without the body', () => {
    const { result } = renderKeyHook(props(801))
    const key = result.current.keyFor(PAY_BODY)
    expect(storageKey).toBe('idem:PAYMENT_INITIATE:/customers/me/payments:801')
    const raw = sessionStorage.getItem(storageKey)!
    expect(JSON.parse(raw)).toEqual({ key, bodyHash: expect.any(String), savedAt: expect.any(Number) })
    expect(raw).not.toContain('589.82')
    expect(localStorage.length).toBe(0)
  })

  it('survives a remount: the same key and the stored reference come back', () => {
    const first = renderKeyHook(props(801))
    const key = first.result.current.keyFor(PAY_BODY)
    first.result.current.succeeded('PAY-20261125-000007')
    first.unmount()

    const second = renderKeyHook(props(801))
    expect(second.result.current.storedRecord()).toMatchObject({ key, reference: 'PAY-20261125-000007' })
    expect(second.result.current.keyFor(PAY_BODY)).toBe(key)
  })

  it('uses a separate key per scope (invoice)', () => {
    const { result, rerender } = renderKeyHook(props(801))
    const first = result.current.keyFor(PAY_BODY)
    rerender(props(802))
    const second = result.current.keyFor({ invoiceId: 802, expectedAmount: '1000.00' })
    expect(second).not.toBe(first)
    expect(sessionStorage.length).toBe(2)
    rerender(props(801))
    expect(result.current.keyFor(PAY_BODY)).toBe(first)
  })

  it('removes the record on a terminal status (reset) and then uses a fresh key', () => {
    const { result } = renderKeyHook(props(801))
    const first = result.current.keyFor(PAY_BODY)
    result.current.succeeded('PAY-20261125-000007')
    result.current.reset()
    expect(sessionStorage.getItem(storageKey)).toBeNull()
    expect(result.current.storedRecord()).toBeNull()
    expect(result.current.keyFor(PAY_BODY)).not.toBe(first)
  })

  it('removes the record after a 422 and keeps it after a 503', () => {
    const { result } = renderKeyHook(props(801))
    result.current.keyFor(PAY_BODY)
    result.current.failed(apiError(503))
    expect(sessionStorage.getItem(storageKey)).not.toBeNull()
    result.current.failed(apiError(422))
    expect(sessionStorage.getItem(storageKey)).toBeNull()
  })

  it('replaces the record when the amount changed', () => {
    const { result } = renderKeyHook(props(801))
    const first = result.current.keyFor(PAY_BODY)
    const second = result.current.keyFor({ ...PAY_BODY, expectedAmount: '600.00' })
    expect(second).not.toBe(first)
    expect(JSON.parse(sessionStorage.getItem(storageKey)!).key).toBe(second)
  })

  it('ignores and removes a record older than 24 h', () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    try {
      vi.setSystemTime(new Date('2026-11-25T10:00:00Z'))
      const first = renderKeyHook(props(801))
      const key = first.result.current.keyFor(PAY_BODY)
      first.unmount()
      vi.setSystemTime(new Date(Date.now() + IDEMPOTENCY_RECORD_TTL_MS))
      const second = renderKeyHook(props(801))
      expect(second.result.current.storedRecord()).toBeNull()
      expect(second.result.current.keyFor(PAY_BODY)).not.toBe(key)
    } finally {
      vi.useRealTimers()
    }
  })

  it('ignores a corrupt record', () => {
    sessionStorage.setItem(storageKey, '{not json')
    const { result } = renderKeyHook(props(801))
    expect(result.current.storedRecord()).toBeNull()
    expect(result.current.keyFor(PAY_BODY)).toMatch(UUID_V4)
  })

  it('clearIdempotencyRecords removes only the idempotency records', () => {
    const { result } = renderKeyHook(props(801))
    result.current.keyFor(PAY_BODY)
    sessionStorage.setItem('other', 'kept')
    clearIdempotencyRecords()
    expect(sessionStorage.getItem(storageKey)).toBeNull()
    expect(sessionStorage.getItem('other')).toBe('kept')
  })
})

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((r) => {
    resolve = r
  })
  return { promise, resolve }
}

function pageShow(persisted: boolean) {
  const event = new Event('pageshow')
  Object.defineProperty(event, 'persisted', { value: persisted })
  window.dispatchEvent(event)
}

describe('useSubmitGuard (CUS-06.6)', () => {
  it('runs once when called twice quickly', async () => {
    const { result } = renderHook(() => useSubmitGuard())
    const gate = deferred<string>()
    const fn = vi.fn(() => gate.promise)
    let first!: Promise<string | undefined>
    let second!: Promise<string | undefined>
    act(() => {
      first = result.current.run(fn)
      second = result.current.run(fn)
    })
    expect(fn).toHaveBeenCalledTimes(1)
    expect(result.current.busy).toBe(true)
    await act(async () => {
      gate.resolve('done')
      await first
    })
    await expect(second).resolves.toBeUndefined()
    await expect(first).resolves.toBe('done')
    expect(result.current.busy).toBe(false)
  })

  it('allows a new run after the previous one settled, also after a failure', async () => {
    const { result } = renderHook(() => useSubmitGuard())
    await act(async () => {
      await expect(result.current.run(() => Promise.reject(new Error('boom')))).rejects.toThrow('boom')
    })
    expect(result.current.busy).toBe(false)
    const fn = vi.fn(() => Promise.resolve(1))
    await act(async () => {
      await result.current.run(fn)
    })
    expect(fn).toHaveBeenCalledTimes(1)
  })

  it('resets the busy state when the page is restored from the bfcache (F4)', async () => {
    const { result } = renderHook(() => useSubmitGuard())
    const gate = deferred<void>()
    let pending!: Promise<void | undefined>
    act(() => {
      pending = result.current.run(() => gate.promise)
    })
    act(() => pageShow(false))
    expect(result.current.busy).toBe(true)
    act(() => pageShow(true))
    expect(result.current.busy).toBe(false)
    const fn = vi.fn(() => Promise.resolve())
    await act(async () => {
      await result.current.run(fn)
    })
    expect(fn).toHaveBeenCalledTimes(1)
    await act(async () => {
      gate.resolve()
      await pending
    })
  })

  it('stops listening for pageshow after unmount', () => {
    const remove = vi.spyOn(window, 'removeEventListener')
    const { unmount } = renderHook(() => useSubmitGuard())
    unmount()
    expect(remove).toHaveBeenCalledWith('pageshow', expect.any(Function))
  })
})
