import { create } from 'zustand'

type OutboundQueueState = {
  /** The applied customer-mobile filter (E.164), or null. Never in a URL or a query key (NFR-PRIV). */
  msisdn: string | null
  /** Increments on every apply; the only mobile-related part of the queue query key. */
  seq: number
  setMsisdn: (msisdn: string | null) => void
  clear: () => void
}

/**
 * The queue's customer-mobile filter (04-outbound/frontend.md §3.1, §5, OQ 1). O2 is a POST so the number stays out of URLs and
 * access logs; here it stays out of the URL and the Query cache too. **Memory only** (no persist): it survives queue ⇄ detail
 * navigation but not a reload; cleared by "Clear all" and on logout.
 */
export const useOutboundQueueStore = create<OutboundQueueState>()((set) => ({
  msisdn: null,
  seq: 0,
  setMsisdn: (msisdn) => set((state) => ({ msisdn, seq: state.seq + 1 })),
  clear: () => set((state) => ({ msisdn: null, seq: state.seq + 1 })),
}))
