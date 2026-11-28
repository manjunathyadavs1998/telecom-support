import { create } from 'zustand'

/** A search hit's lookup token is valid for 15 min on the server (backend R1); older entries are ignored here. */
export const LOOKUP_TOKEN_TTL_MS = 15 * 60 * 1000

type Entry = { lookupToken: string; storedAt: number }

type InboundSessionState = {
  /** customerId → the token of this agent's last matching search. */
  tokens: Readonly<Record<string, Entry>>
  setToken: (customerId: number, lookupToken: string) => void
  /** The fresh token for `customerId`, or `null` when none or older than 15 min (→ the Search step, §3.2). */
  freshToken: (customerId: number, now?: number) => string | null
  dropToken: (customerId: number) => void
  clear: () => void
}

/**
 * Lookup tokens (03-inbound/frontend.md §5, recorded exception F2): a short-lived capability, not displayable data, so it is
 * kept apart from the Query cache. **Memory only** — no `persist`, never in storage (NFR-PRIV). Holds only
 * `{lookupToken, storedAt}` per customer; `clear()` on logout (`useLogout`).
 */
export const useInboundSessionStore = create<InboundSessionState>()((set, get) => ({
  tokens: {},
  setToken: (customerId, lookupToken) =>
    set((state) => ({ tokens: { ...state.tokens, [String(customerId)]: { lookupToken, storedAt: Date.now() } } })),
  freshToken: (customerId, now = Date.now()) => {
    const key = String(customerId)
    const entry = Object.hasOwn(get().tokens, key) ? get().tokens[key] : undefined
    return entry && now - entry.storedAt < LOOKUP_TOKEN_TTL_MS ? entry.lookupToken : null
  },
  dropToken: (customerId) =>
    set((state) => {
      const { [String(customerId)]: _dropped, ...rest } = state.tokens
      return { tokens: rest }
    }),
  clear: () => set({ tokens: {} }),
}))
