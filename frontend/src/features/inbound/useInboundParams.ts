import { useParams } from 'react-router'
import { INBOUND_PARAM_PATTERNS } from '@/permissions/navConfig'

/** The validated inbound workspace params (03-inbound/frontend.md §2, §4, §9 FE1). */
export type InboundParams = {
  customerId: number
  /** The `SR-…` case reference on the case detail route, else `undefined`. */
  reference: string | undefined
}

/**
 * Parses `:customerId` (and `:reference` when present) against the §2 patterns. `null` means a param is malformed: the
 * caller renders `NotFoundPage` and makes no API call. The route's `ParamGate` already rejects such URLs before a page
 * mounts; this is the typed read for the pages. The MSISDN is never a route param (NFR-PRIV).
 */
export function useInboundParams(): InboundParams | null {
  const { customerId, reference } = useParams()
  if (customerId === undefined || !INBOUND_PARAM_PATTERNS.customerId.test(customerId)) return null
  if (reference !== undefined && !INBOUND_PARAM_PATTERNS.reference.test(reference)) return null
  return { customerId: Number(customerId), reference }
}
