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
  /**
   * The allowed `?status=` values (03-inbound/frontend.md §4): repeated in the URL; unknown values are dropped with the notice,
   * duplicates removed, order as in this list.
   */
  status?: readonly string[]
  /**
   * The sort whitelist (03-inbound/frontend.md §4, I9): `?sort=` must be one of these; the first is the default and is never
   * written to the URL. An unknown value is dropped with the notice.
   */
  sort?: readonly string[]
}

/**
 * Filters as kept in the URL. No `from`/`to` means the server's default window (last 3 months, BR-05/06). No `status` means all
 * statuses; no `sort` means the schema's default.
 */
export type ListFilters = {
  from?: string
  to?: string
  reference?: string
  status?: readonly string[]
  sort?: string
}
/** URL state: filters plus the **1-based** page. */
export type ListParams = ListFilters & { page: number }
/** What the API receives: the same filters with a **0-based** page and the page size. */
export type ListApiQuery = Pick<ListFilters, 'from' | 'to' | 'reference'> & { page: number; size: number }
/**
 * The schema-gated filters (03-inbound/frontend.md §4), apart from `apiQuery` so the 02 lists' typed endpoints are unchanged:
 * a list that declares `status`/`sort` sends `{ ...apiQuery, ...apiFilters }`.
 */
export type ListApiFilters = Pick<ListFilters, 'status' | 'sort'>

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

  const statuses = search.getAll('status').filter((value) => value !== '')
  if (statuses.length > 0) {
    const allowed = schema.status ?? []
    if (statuses.some((value) => !allowed.includes(value))) invalid = true
    const kept = allowed.filter((value) => statuses.includes(value))
    if (kept.length > 0) params.status = kept
  }

  const sort = search.get('sort')
  if (sort !== null && sort !== '') {
    const allowed = schema.sort ?? []
    if (!allowed.includes(sort)) invalid = true
    else if (sort !== allowed[0]) params.sort = sort
  }

  const page = search.get('page')
  if (page !== null) {
    const value = PAGE.test(page) ? Number(page) : Number.NaN
    if (Number.isSafeInteger(value) && value >= 1) params.page = value
    else invalid = true
  }

  return { params, invalid, clean: toSearch(params) }
}

function toSearch({ from, to, reference, status, sort, page }: ListParams): URLSearchParams {
  const search = new URLSearchParams()
  if (from && to) {
    search.set('from', from)
    search.set('to', to)
  }
  if (reference) search.set('reference', reference)
  for (const value of status ?? []) search.append('status', value)
  if (sort) search.set('sort', sort)
  if (page > 1) search.set('page', String(page))
  return search
}

/**
 * List URL state (frontend.md §3 Service requests, §4, XC-04; 03-inbound/frontend.md §4 adds repeated `status` and whitelisted
 * `sort`): `?from&to&reference&status&sort&page` ⇄ typed params, so filters and the page
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
  const apiFilters: ListApiFilters = {
    ...(params.status ? { status: params.status } : {}),
    ...(params.sort ? { sort: params.sort } : {}),
  }

  return {
    params,
    apiQuery,
    apiFilters,
    invalidNotice: invalid || noticeShown,
    dismissNotice: useCallback(() => setNoticeShown(false), []),
    setFilters,
    setPage,
  }
}
