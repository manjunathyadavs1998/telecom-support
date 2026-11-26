import { useCallback, useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router'
import type { z } from 'zod'
import { todayIst, validateRange } from '@/lib/dateWindow'

/** Shown when a link carried invalid filters (frontend.md §3 Service requests). */
export const INVALID_LINK_NOTICE = 'Some filters in the link were invalid and were reset'

/** Server page size for the customer lists (frontend.md §3: 20 per page). */
export const LIST_PAGE_SIZE = 20

/**
 * Which optional filters a list accepts from the URL; `reference` normalises and validates the search box value (§6). Pass a
 * module-level constant so its identity is stable.
 */
export type ListParamsSchema = {
  reference?: z.ZodType<string, string>
}

/** Filters as kept in the URL. No `from`/`to` means the server's default window (last 3 months, BR-05/06). */
export type ListFilters = { from?: string; to?: string; reference?: string }
/** URL state: filters plus the **1-based** page. */
export type ListParams = ListFilters & { page: number }
/** What the API receives: the same filters with a **0-based** page and the page size. */
export type ListApiQuery = ListFilters & { page: number; size: number }

type Parsed = { params: ListParams; invalid: boolean; clean: URLSearchParams }

const PAGE = /^\d+$/
const NO_EXTRA_FILTERS: ListParamsSchema = {}

function parse(search: URLSearchParams, schema: ListParamsSchema, today: string): Parsed {
  let invalid = false
  const params: ListParams = { page: 1 }

  const from = search.get('from')
  const to = search.get('to')
  if (from !== null || to !== null) {
    // The pair is one filter: both present and within the §6 range rules, or both dropped.
    if (from !== null && to !== null && validateRange({ from, to }, { today }) === null) {
      params.from = from
      params.to = to
    } else invalid = true
  }

  const reference = search.get('reference')
  if (reference !== null && reference !== '') {
    const result = schema.reference?.safeParse(reference)
    if (result?.success) params.reference = result.data
    else invalid = true
  }

  const page = search.get('page')
  if (page !== null) {
    const value = PAGE.test(page) ? Number(page) : Number.NaN
    if (Number.isSafeInteger(value) && value >= 1) params.page = value
    else invalid = true
  }

  return { params, invalid, clean: toSearch(params) }
}

function toSearch({ from, to, reference, page }: ListParams): URLSearchParams {
  const search = new URLSearchParams()
  if (from && to) {
    search.set('from', from)
    search.set('to', to)
  }
  if (reference) search.set('reference', reference)
  if (page > 1) search.set('page', String(page))
  return search
}

/**
 * List URL state (frontend.md §3 Service requests, §4, XC-04): `?from&to&reference&page` ⇄ typed params, so filters and the page
 * survive paging, reload, Back and deep links. `page` is 1-based in the URL and 0-based in `apiQuery`. Invalid params (bad date,
 * range rules, bad reference, page < 1) are never returned — they are dropped from the URL (replace) and `invalidNotice` is set
 * until the next filter change or `dismissNotice()`. A filter change resets the page.
 */
export function useListParams(schema: ListParamsSchema = NO_EXTRA_FILTERS) {
  const [search, setSearch] = useSearchParams()
  const today = todayIst()
  const { params, invalid, clean } = useMemo(() => parse(search, schema, today), [search, schema, today])
  // The notice outlives the cleaned URL: latch it while rendering (no effect) when invalid params are seen.
  const [noticeShown, setNoticeShown] = useState(false)
  if (invalid && !noticeShown) setNoticeShown(true)

  // Drop the invalid params from the URL (synchronising with the router, so an effect).
  useEffect(() => {
    if (invalid) setSearch(clean, { replace: true })
  }, [invalid, clean, setSearch])

  const setFilters = useCallback(
    (filters: ListFilters) => {
      setNoticeShown(false)
      setSearch(toSearch({ ...filters, page: 1 }))
    },
    [setSearch],
  )

  const setPage = useCallback((page: number) => setSearch(toSearch({ ...params, page: Math.max(1, page) })), [params, setSearch])

  const apiQuery: ListApiQuery = {
    ...(params.from && params.to ? { from: params.from, to: params.to } : {}),
    ...(params.reference ? { reference: params.reference } : {}),
    page: params.page - 1,
    size: LIST_PAGE_SIZE,
  }

  return {
    params,
    apiQuery,
    invalidNotice: invalid || noticeShown,
    dismissNotice: useCallback(() => setNoticeShown(false), []),
    setFilters,
    setPage,
  }
}
