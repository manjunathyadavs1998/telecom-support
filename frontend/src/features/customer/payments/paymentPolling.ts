import { useQuery } from '@tanstack/react-query'
import { useCallback, useEffect, useState } from 'react'
import type { Payment, PaymentStatus } from '@/types/api'
import { fetchPayment, paymentQueryKey } from './queries'

/** frontend.md §3 Payment status: E16 every 3 s while PENDING, inside a 2 min poll window. */
export const POLL_INTERVAL_MS = 3_000
export const POLL_WINDOW_MS = 2 * 60_000

/** Statuses that end the payment: the stored E15 record is dropped and the lists are refreshed. */
export const TERMINAL_PAYMENT_STATUSES: ReadonlySet<PaymentStatus> = new Set(['SUCCEEDED', 'FAILED', 'EXPIRED'])

/** The query's `refetchInterval`: every 3 s only while the server says PENDING and the window is open, else off. */
export function pollInterval(payment: Payment | undefined, windowOpen: boolean): number | false {
  return payment?.status === 'PENDING' && windowOpen ? POLL_INTERVAL_MS : false
}

export type PollWindow = {
  /** True for 2 min after the page opens or after `restart()`. */
  open: boolean
  /** "Check again": a new 2 min window. */
  restart: () => void
}

/**
 * The 2 min poll window (component state, frontend.md §5). It closes with a one-shot interval timer, the same timer kind
 * TanStack Query polls with, so both advance together (also under fake timers in tests).
 */
export function usePollWindow(): PollWindow {
  const [generation, setGeneration] = useState(0)
  const [open, setOpen] = useState(true)

  useEffect(() => {
    const id = setInterval(() => {
      clearInterval(id)
      setOpen(false)
    }, POLL_WINDOW_MS)
    return () => clearInterval(id)
  }, [generation])

  const restart = useCallback(() => {
    setOpen(true)
    setGeneration((n) => n + 1)
  }, [])

  return { open, restart }
}

/**
 * E16 for the status page (frontend.md §3 Payment status; CUS-08.3–08.5, XC-07): polls while PENDING inside the window;
 * `refetchOnWindowFocus` stays on for every status (late capture). Polling pauses while the tab is hidden
 * (`refetchIntervalInBackground` off) and stops on unmount. A failed refetch keeps the last data (`isRefetchError`).
 */
export function usePaymentStatusQuery(reference: string, windowOpen: boolean) {
  return useQuery({
    queryKey: paymentQueryKey(reference),
    queryFn: () => fetchPayment(reference),
    refetchInterval: (query) => pollInterval(query.state.data, windowOpen),
    refetchIntervalInBackground: false,
    refetchOnWindowFocus: true,
  })
}
