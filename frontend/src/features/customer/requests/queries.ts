import { keepPreviousData, queryOptions, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { z } from 'zod'
import type { ListApiQuery, ListParamsSchema } from '@/components/data/useListParams'
import { api, apiClient } from '@/services/api/client'
import type { CaseCreated, CaseCreateRequest, CasePage, Category } from '@/types/api'

/** Own service requests (frontend.md §5): `['customer','cases',params]`; a create invalidates the whole prefix. */
export const CASES_QUERY_KEY = ['customer', 'cases'] as const
export const casesQueryKey = (query: ListApiQuery) => [...CASES_QUERY_KEY, query] as const

/** Active customer-facing categories (frontend.md §5). */
export const CASE_CATEGORIES_QUERY_KEY = ['case-categories'] as const

/** Lists use a 30 s `staleTime` (frontend.md §5). */
const LIST_STALE_TIME_MS = 30_000

/** A request reference, e.g. `SR-20261125-000042` (frontend.md §6 Reference search). */
export const REQUEST_REFERENCE_PATTERN = /^SR-\d{8}-\d{6,}$/
export const REFERENCE_FORMAT_MESSAGE = 'Enter a reference like SR-20261125-000042.'

/** frontend.md §6 Reference search: trim, upper-case, `^SR-\d{8}-\d{6,}$`. */
export const requestReferenceSchema = z
  .string()
  .trim()
  .toUpperCase()
  .regex(REQUEST_REFERENCE_PATTERN, REFERENCE_FORMAT_MESSAGE)

/** The request list's URL filters (`useListParams`); a module constant, so its identity is stable. */
export const REQUEST_LIST_PARAMS: ListParamsSchema = { reference: requestReferenceSchema }

/**
 * E11 `GET /customers/me/cases` (CUS-05, BR-05/06): own requests, newest first. Without `from`/`to` the server applies the last
 * 3 months; `reference` is searched inside the window (OQ 6). `page` is 0-based here.
 */
export async function fetchCases(query: ListApiQuery): Promise<CasePage> {
  return api.call(apiClient.GET('/api/v1/customers/me/cases', { params: { query } }))
}

/** One page of requests; the previous page stays on screen while the next one loads (`keepPreviousData`, §3). */
export function useCasesQuery(query: ListApiQuery) {
  return useQuery({
    queryKey: casesQueryKey(query),
    queryFn: () => fetchCases(query),
    staleTime: LIST_STALE_TIME_MS,
    placeholderData: keepPreviousData,
  })
}

/** E10 `GET /case-categories` (CUS-06.2): categories with their subcategories. */
export async function fetchCaseCategories(): Promise<Category[]> {
  return api.call(apiClient.GET('/api/v1/case-categories'))
}

export const caseCategoriesQuery = queryOptions({
  queryKey: CASE_CATEGORIES_QUERY_KEY,
  queryFn: fetchCaseCategories,
  staleTime: LIST_STALE_TIME_MS,
})

export function useCaseCategoriesQuery() {
  return useQuery(caseCategoriesQuery)
}

/** The E12 path, also the idempotency key's scope (frontend.md §4). */
export const CASE_CREATE_PATH = '/customers/me/cases'

export type CreateCaseVariables = { idempotencyKey: string; body: CaseCreateRequest }

/** E12 `POST /customers/me/cases` (CUS-06, NFR-REL): account, contact number and channel are set by the server. */
export async function postCase({ idempotencyKey, body }: CreateCaseVariables): Promise<CaseCreated> {
  return api.call(
    apiClient.POST('/api/v1/customers/me/cases', { params: { header: { 'Idempotency-Key': idempotencyKey } }, body }),
  )
}

/** Create: never optimistic (XC-07); a 201 invalidates every request list page (§3 Create request). */
export function useCreateCase() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: postCase,
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: CASES_QUERY_KEY })
    },
  })
}
