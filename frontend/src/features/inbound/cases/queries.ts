import type { ListParamsSchema } from '@/components/data/useListParams'
import { requestReferenceSchema } from '@/features/customer/requests/queries'
import type { CaseStatus } from '@/types/api'

export { useAgentCasesQuery } from '../workspace/queries'

/** The 8 internal statuses in lifecycle order (03-inbound/frontend.md §3.6, `CaseStatus`). */
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

/** The I9 sort whitelist; the first is the default and never written to the URL (§3.6, F3). */
export const CASE_SORTS = [
  { value: 'createdAt,desc', label: 'Created newest' },
  { value: 'createdAt,asc', label: 'Created oldest' },
  { value: 'updatedAt,desc', label: 'Updated newest' },
  { value: 'updatedAt,asc', label: 'Updated oldest' },
] as const

/** The case list's URL filters (module constant: stable identity for `useListParams`). */
export const CASE_LIST_PARAMS: ListParamsSchema = {
  reference: requestReferenceSchema,
  status: CASE_STATUSES,
  sort: CASE_SORTS.map((sort) => sort.value),
}
