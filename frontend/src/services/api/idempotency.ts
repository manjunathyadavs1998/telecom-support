// Idempotency keys and the double-submit guard (02-customer/frontend.md §4, §5; 00-overview §7; NFR-REL, CUS-06.6).
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { newCorrelationId as uuidV4 } from './client'
import { ApiError } from './errors'

/**
 * The idempotent operations of slice 02 (backend.md E7, E9, E12, E15) and slice 03 (03-inbound/frontend.md §4: I12, I14;
 * in-memory only, never persisted).
 */
export type IdempotentOperation =
  | 'PLAN_CHANGE'
  | 'PROMOTION_CLAIM'
  | 'CASE_CREATE'
  | 'PAYMENT_INITIATE'
  | 'CASE_CREATE_ONBEHALF'
  | 'REACTIVATION_CREATE'

/** What `persist` keeps in sessionStorage: no request body and no PII, only a hash of it (NFR-PRIV). */
export type IdempotencyRecord = { key: string; bodyHash: string; reference?: string; savedAt: number }

export const IDEMPOTENCY_STORAGE_PREFIX = 'idem:'
/** A persisted record older than this is ignored and removed (frontend.md §4). */
export const IDEMPOTENCY_RECORD_TTL_MS = 24 * 60 * 60 * 1000
/** Statuses after which the server will not replay the request, so the next attempt needs a fresh key. */
const DROP_KEY_STATUSES = new Set([400, 404, 422])

/**
 * A 422 whose problem `type` ends in `idempotency-mismatch` (frontend.md §6 common rule, F7): the key was used for another
 * body. The caller shows "Please try again" (never business copy); `failed()` has already dropped the key.
 */
export function isIdempotencyMismatch(error: unknown): boolean {
  return error instanceof ApiError && error.status === 422 && (error.problem?.type.endsWith('idempotency-mismatch') ?? false)
}

/** Shown after an idempotency-mismatch 422 (frontend.md §6). */
export const IDEMPOTENCY_MISMATCH_MESSAGE = 'Please try again.'

/** JSON with object keys sorted at every level, so equal bodies serialise equally whatever their key order. */
export function stableStringify(value: unknown): string {
  if (value === undefined) return 'null'
  if (value === null || typeof value !== 'object') return JSON.stringify(value)
  if (Array.isArray(value)) return `[${value.map(stableStringify).join(',')}]`
  const entries = Object.entries(value as Record<string, unknown>)
    .filter(([, v]) => v !== undefined)
    .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
  return `{${entries.map(([k, v]) => `${JSON.stringify(k)}:${stableStringify(v)}`).join(',')}}`
}

/** A 64-bit FNV-1a hash (hex) of the stable body: an equality check for the persisted record, not a security control. */
export function hashBody(body: unknown): string {
  const text = stableStringify(body)
  let hash = 0xcbf29ce484222325n
  for (let i = 0; i < text.length; i++) {
    hash ^= BigInt(text.charCodeAt(i))
    hash = (hash * 0x100000001b3n) & 0xffffffffffffffffn
  }
  return hash.toString(16).padStart(16, '0')
}

export function idempotencyStorageKey(operation: IdempotentOperation, path: string, scope: string | number = ''): string {
  return `${IDEMPOTENCY_STORAGE_PREFIX}${operation}:${path}:${scope}`
}

function storage(): Storage | null {
  try {
    return globalThis.sessionStorage ?? null
  } catch {
    return null
  }
}

function isRecord(value: unknown): value is IdempotencyRecord {
  if (typeof value !== 'object' || value === null) return false
  const r = value as Record<string, unknown>
  return (
    typeof r.key === 'string' &&
    typeof r.bodyHash === 'string' &&
    typeof r.savedAt === 'number' &&
    (r.reference === undefined || typeof r.reference === 'string')
  )
}

/** The stored record under `storageKey`, or null; an unreadable or expired (24 h) record is removed. */
export function readIdempotencyRecord(storageKey: string, now = Date.now()): IdempotencyRecord | null {
  const store = storage()
  if (!store) return null
  try {
    const raw = store.getItem(storageKey)
    if (raw === null) return null
    const parsed: unknown = JSON.parse(raw)
    if (isRecord(parsed) && now - parsed.savedAt < IDEMPOTENCY_RECORD_TTL_MS && parsed.savedAt <= now) return parsed
    store.removeItem(storageKey)
  } catch {
    try {
      store.removeItem(storageKey)
    } catch {
      // storage unavailable: nothing to clean up
    }
  }
  return null
}

function writeRecord(storageKey: string, record: IdempotencyRecord): void {
  try {
    storage()?.setItem(storageKey, JSON.stringify(record))
  } catch {
    // quota or privacy mode: the key still lives in memory for this page (frontend.md §4)
  }
}

function removeRecord(storageKey: string): void {
  try {
    storage()?.removeItem(storageKey)
  } catch {
    // storage unavailable
  }
}

/** sessionStorage key of the user id the persisted records belong to (FC3); starts with the prefix, so a clear drops it too. */
export const IDEMPOTENCY_OWNER_STORAGE_KEY = `${IDEMPOTENCY_STORAGE_PREFIX}owner`

/**
 * Binds the persisted records to the signed-in user (frontend.md §5, review FC3): when `userId` differs from the user the
 * records were kept for (or none was recorded yet), they are cleared first. The same user signing in again after an expiry
 * keeps them, so the gateway-return resume still works.
 */
export function claimIdempotencyRecords(userId: number): void {
  const store = storage()
  if (!store) return
  try {
    const owner = String(userId)
    if (store.getItem(IDEMPOTENCY_OWNER_STORAGE_KEY) === owner) return
    clearIdempotencyRecords()
    store.setItem(IDEMPOTENCY_OWNER_STORAGE_KEY, owner)
  } catch {
    // storage unavailable
  }
}

/** Removes every persisted idempotency record (frontend.md §5: cleared on logout together with the query cache). */
export function clearIdempotencyRecords(): void {
  const store = storage()
  if (!store) return
  try {
    const keys: string[] = []
    for (let i = 0; i < store.length; i++) {
      const key = store.key(i)
      if (key?.startsWith(IDEMPOTENCY_STORAGE_PREFIX)) keys.push(key)
    }
    for (const key of keys) store.removeItem(key)
  } catch {
    // storage unavailable
  }
}

/**
 * Removes the persisted records of `operation` on `path` (any scope) that point to `reference`: the payment status page drops
 * the E15 record once its payment reaches a terminal status (frontend.md §3 Payment status, §4). E16 carries no invoice id,
 * so the record is found by the reference it stored.
 */
export function dropIdempotencyRecordsFor(operation: IdempotentOperation, path: string, reference: string): void {
  const store = storage()
  if (!store) return
  const prefix = idempotencyStorageKey(operation, path)
  try {
    const keys: string[] = []
    for (let i = 0; i < store.length; i++) {
      const key = store.key(i)
      if (key?.startsWith(prefix)) keys.push(key)
    }
    // Read after collecting: reading removes an expired record, which would shift the indexes above.
    for (const key of keys) if (readIdempotencyRecord(key)?.reference === reference) store.removeItem(key)
  } catch {
    // storage unavailable
  }
}

export type IdempotencyKeyOptions = {
  /** E15 only: keep the key in sessionStorage under `idem:<operation>:<path>:<scope>` until a terminal status or 24 h. */
  persist?: boolean
  /** Distinguishes persisted records on the same path, e.g. the invoice id for E15. */
  scope?: string | number
}

export type IdempotencyKey = {
  /** The key for this request: the same UUID while `{operation, path, stable body}` is unchanged, else a new one. */
  keyFor: (body: unknown) => string
  /** After a 2xx: drops the key; with `persist`, keeps the record and stores the returned `reference` in it instead. */
  succeeded: (reference?: string) => void
  /**
   * After an error: drops the key on 400/404/422 (incl. idempotency-mismatch), keeps it on 409, 5xx and network errors so
   * that a retry is safe.
   */
  failed: (error: unknown) => void
  /** Drops the key and the persisted record (e.g. on a terminal payment status). */
  reset: () => void
  /** The persisted record for the current scope (`persist` only), or null. */
  storedRecord: () => IdempotencyRecord | null
}

/** frontend.md §4 `useIdempotencyKey(operation, path, {persist?, scope?})`. */
export function useIdempotencyKey(
  operation: IdempotentOperation,
  path: string,
  { persist = false, scope = '' }: IdempotencyKeyOptions = {},
): IdempotencyKey {
  const current = useRef<{ fingerprint: string; key: string } | null>(null)
  const storageKey = idempotencyStorageKey(operation, path, scope)

  const reset = useCallback(() => {
    current.current = null
    if (persist) removeRecord(storageKey)
  }, [persist, storageKey])

  const keyFor = useCallback(
    (body: unknown) => {
      const bodyHash = hashBody(body)
      const fingerprint = [operation, path, persist ? storageKey : '', bodyHash].join('|')
      if (current.current?.fingerprint === fingerprint) return current.current.key
      const stored = persist ? readIdempotencyRecord(storageKey) : null
      const key = stored?.bodyHash === bodyHash ? stored.key : uuidV4()
      if (persist && stored?.key !== key) {
        // A new key for a changed body keeps the reference of the payment the old key opened, so a later 409 can still link
        // to that open payment (review FC4). The reference is replaced when the new request succeeds.
        const reference = stored?.reference
        writeRecord(storageKey, { key, bodyHash, savedAt: Date.now(), ...(reference === undefined ? {} : { reference }) })
      }
      current.current = { fingerprint, key }
      return key
    },
    [operation, path, persist, storageKey],
  )

  const succeeded = useCallback(
    (reference?: string) => {
      if (!persist) {
        current.current = null
        return
      }
      const stored = readIdempotencyRecord(storageKey)
      if (stored && reference !== undefined) writeRecord(storageKey, { ...stored, reference })
    },
    [persist, storageKey],
  )

  const failed = useCallback(
    (error: unknown) => {
      if (error instanceof ApiError && DROP_KEY_STATUSES.has(error.status)) reset()
    },
    [reset],
  )

  const storedRecord = useCallback(() => (persist ? readIdempotencyRecord(storageKey) : null), [persist, storageKey])

  return useMemo(
    () => ({ keyFor, succeeded, failed, reset, storedRecord }),
    [keyFor, succeeded, failed, reset, storedRecord],
  )
}

export type SubmitGuard = {
  /** Runs `fn` unless a previous run is still in flight; an ignored call resolves to `undefined`. */
  run: <T>(fn: () => Promise<T>) => Promise<T | undefined>
  /** True while a run is in flight (for disabling the submit button). */
  busy: boolean
  /** Clears the in-flight flag and busy state. */
  reset: () => void
}

/**
 * frontend.md §4 `useSubmitGuard()`: a synchronous in-flight flag, so two fast clicks start one request (CUS-06.6). It is
 * cleared when the run settles and when the page is restored from the back/forward cache (`pageshow` with `persisted`, F4).
 */
export function useSubmitGuard(): SubmitGuard {
  const inFlight = useRef(false)
  const [busy, setBusy] = useState(false)

  const reset = useCallback(() => {
    inFlight.current = false
    setBusy(false)
  }, [])

  useEffect(() => {
    const onPageShow = (event: PageTransitionEvent) => {
      if (event.persisted) reset()
    }
    window.addEventListener('pageshow', onPageShow)
    return () => window.removeEventListener('pageshow', onPageShow)
  }, [reset])

  const run = useCallback(
    async <T>(fn: () => Promise<T>): Promise<T | undefined> => {
      if (inFlight.current) return undefined
      inFlight.current = true
      setBusy(true)
      try {
        return await fn()
      } finally {
        reset()
      }
    },
    [reset],
  )

  return useMemo(() => ({ run, busy, reset }), [run, busy, reset])
}
