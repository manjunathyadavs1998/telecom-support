import { useCallback, useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router'
import { LIST_PAGE_SIZE } from '@/components/data/useListParams'
import { requestReferenceSchema } from '@/features/customer/requests/queries'
import { todayIst, validateRange } from '@/lib/dateWindow'
import { QUEUE_SORTS } from '@/lib/outboundText'
import type { CaseStatus, QueueSearch, QueueSort, QueueSummary, Severity } from '@/types/api'

export const CASE_STATUSES: readonly CaseStatus[] = [
  'CREATED',
  'ASSIGNED',
  'IN_PROGRESS',
  'PENDING_CUSTOMER',
  'PENDING_EXTERNAL',
  'RESOLVED',
  'CLOSED',
  'REOPENED',
]
export const SEVERITIES: readonly Severity[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL']
export const DEFAULT_SORT: QueueSort = 'createdAt,desc'

/** The queue filters kept in the URL (04-outbound/frontend.md §3.1); the mobile number is never here (§5). */
export type QueueFilters = {
  from?: string
  to?: string
  ufrom?: string
  uto?: string
  status: readonly CaseStatus[]
  severity: readonly Severity[]
  category?: string
  /** `me`, `unassigned` or a user id. */
  assignee?: string
  ref?: string
  overdue: boolean
  sort: QueueSort
}

export type QueueParams = QueueFilters & { page: number }

export const EMPTY_FILTERS: QueueFilters = { status: [], severity: [], overdue: false, sort: DEFAULT_SORT }

const PAGE = /^\d+$/
const CATEGORY = /^[A-Z0-9_]{1,32}$/
const ASSIGNEE = /^(me|unassigned|\d{1,15})$/
const SORTS = new Set<string>(QUEUE_SORTS.map((sort) => sort.value))

type Parsed = { params: QueueParams; invalid: boolean; clean: URLSearchParams }

function pair(search: URLSearchParams, a: string, b: string, today: string): [string?, string?, boolean?] {
  const from = search.get(a)
  const to = search.get(b)
  if (from === null && to === null) return []
  if (from !== null && to !== null && validateRange({ from, to }, { today }) === null) return [from, to]
  return [undefined, undefined, true]
}

function parse(search: URLSearchParams, today: string): Parsed {
  let invalid = false
  const params: QueueParams = { ...EMPTY_FILTERS, page: 1 }
  const [from, to, badCreated] = pair(search, 'from', 'to', today)
  const [ufrom, uto, badUpdated] = pair(search, 'ufrom', 'uto', today)
  if (badCreated || badUpdated) invalid = true
  if (from && to) Object.assign(params, { from, to })
  if (ufrom && uto) Object.assign(params, { ufrom, uto })

  const statuses = search.getAll('status')
  if (statuses.some((value) => !CASE_STATUSES.includes(value as CaseStatus))) invalid = true
  params.status = CASE_STATUSES.filter((value) => statuses.includes(value))
  const severities = search.getAll('severity')
  if (severities.some((value) => !SEVERITIES.includes(value as Severity))) invalid = true
  params.severity = SEVERITIES.filter((value) => severities.includes(value))

  const category = search.get('category')
  if (category !== null) {
    if (CATEGORY.test(category)) params.category = category
    else invalid = true
  }
  const assignee = search.get('assignee')
  if (assignee !== null) {
    if (ASSIGNEE.test(assignee)) params.assignee = assignee
    else invalid = true
  }
  const ref = search.get('ref')
  if (ref !== null && ref !== '') {
    const result = requestReferenceSchema.safeParse(ref)
    if (result.success) params.ref = result.data
    else invalid = true
  }
  const overdue = search.get('overdue')
  if (overdue !== null) {
    if (overdue === '1') params.overdue = true
    else invalid = true
  }
  const sort = search.get('sort')
  if (sort !== null) {
    if (SORTS.has(sort)) params.sort = sort as QueueSort
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

function toSearch(p: QueueParams): URLSearchParams {
  const search = new URLSearchParams()
  if (p.from && p.to) {
    search.set('from', p.from)
    search.set('to', p.to)
  }
  if (p.ufrom && p.uto) {
    search.set('ufrom', p.ufrom)
    search.set('uto', p.uto)
  }
  for (const value of p.status) search.append('status', value)
  for (const value of p.severity) search.append('severity', value)
  if (p.category) search.set('category', p.category)
  if (p.assignee) search.set('assignee', p.assignee)
  if (p.ref) search.set('ref', p.ref)
  if (p.overdue) search.set('overdue', '1')
  if (p.sort !== DEFAULT_SORT) search.set('sort', p.sort)
  if (p.page > 1) search.set('page', String(p.page))
  return search
}

/** The O2 body for the URL filters plus the in-memory mobile number (§3.1 table); page is 0-based here. */
export function toSearchBody(p: QueueParams, msisdn: string | null): QueueSearch {
  const assignee = p.assignee
  return {
    ...(p.from && p.to ? { createdFrom: p.from, createdTo: p.to } : {}),
    ...(p.ufrom && p.uto ? { updatedFrom: p.ufrom, updatedTo: p.uto } : {}),
    ...(p.status.length > 0 ? { status: [...p.status] } : {}),
    ...(p.severity.length > 0 ? { severity: [...p.severity] } : {}),
    ...(p.category ? { categoryCode: p.category } : {}),
    ...(assignee === 'me' ? { assignee: 'ME' as const } : {}),
    ...(assignee === 'unassigned' ? { assignee: 'UNASSIGNED' as const } : {}),
    ...(assignee && /^\d+$/.test(assignee) ? { assignee: 'USER' as const, assigneeUserId: Number(assignee) } : {}),
    ...(msisdn ? { customerMsisdn: msisdn } : {}),
    ...(p.ref ? { reference: p.ref } : {}),
    overdue: p.overdue,
    page: p.page - 1,
    size: LIST_PAGE_SIZE,
    sort: p.sort,
  }
}

/**
 * Queue URL state (04-outbound/frontend.md §3.1, §4; XC-04): typed filters ⇄ search params, following `useListParams`: invalid
 * params are dropped from the URL (replace) with the invalid-link notice; a filter change resets the page.
 */
export function useQueueParams() {
  const [search, setSearch] = useSearchParams()
  const today = todayIst()
  const { params, invalid, clean } = useMemo(() => parse(search, today), [search, today])
  const [noticeShown, setNoticeShown] = useState(false)
  if (invalid && !noticeShown) setNoticeShown(true)

  useEffect(() => {
    if (invalid) setSearch(clean, { replace: true })
  }, [invalid, clean, setSearch])

  const setFilters = useCallback(
    (filters: QueueFilters) => {
      setNoticeShown(false)
      setSearch(toSearch({ ...filters, page: 1 }))
    },
    [setSearch],
  )
  const setPage = useCallback((page: number) => setSearch(toSearch({ ...params, page: Math.max(1, page) })), [params, setSearch])

  return {
    params,
    invalidNotice: invalid || noticeShown,
    dismissNotice: useCallback(() => setNoticeShown(false), []),
    setFilters,
    setPage,
  }
}

// ---------------------------------------------------------------- summary tile shortcuts (§3.1)

/** The filter part a tile sets (04-outbound/frontend.md §3.1 shortcut table); the other filters are kept. */
export type Shortcut = { status: readonly CaseStatus[]; severity: readonly Severity[]; overdue: boolean }

const NON_TERMINAL: readonly CaseStatus[] = ['CREATED', 'ASSIGNED', 'IN_PROGRESS', 'PENDING_CUSTOMER', 'PENDING_EXTERNAL', 'REOPENED']

export const TILES: readonly { key: keyof QueueSummary; label: string; shortcut: Shortcut }[] = [
  { key: 'new', label: 'New', shortcut: { status: ['CREATED'], severity: [], overdue: false } },
  { key: 'open', label: 'Open', shortcut: { status: ['ASSIGNED', 'REOPENED'], severity: [], overdue: false } },
  { key: 'inProgress', label: 'In progress', shortcut: { status: ['IN_PROGRESS'], severity: [], overdue: false } },
  { key: 'pending', label: 'Pending', shortcut: { status: ['PENDING_CUSTOMER', 'PENDING_EXTERNAL'], severity: [], overdue: false } },
  { key: 'resolved', label: 'Resolved', shortcut: { status: ['RESOLVED'], severity: [], overdue: false } },
  { key: 'closed', label: 'Closed', shortcut: { status: ['CLOSED'], severity: [], overdue: false } },
  { key: 'critical', label: 'Critical', shortcut: { status: NON_TERMINAL, severity: ['CRITICAL'], overdue: false } },
  { key: 'overdue', label: 'Overdue', shortcut: { status: [], severity: [], overdue: true } },
]

const same = <T,>(a: readonly T[], b: readonly T[]) => a.length === b.length && a.every((v) => b.includes(v))

/** A tile is active when the URL filters equal exactly its shortcut (§3.1). */
export function activeTile(filters: QueueFilters): string | null {
  const tile = TILES.find(
    (t) => same(t.shortcut.status, filters.status) && same(t.shortcut.severity, filters.severity) && t.shortcut.overdue === filters.overdue,
  )
  return tile ? tile.key : null
}
