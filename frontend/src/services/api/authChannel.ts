// Cross-tab session sync (frontend.md §7 Cross-tab): BroadcastChannel('auth') carries `login` / `logout` so other tabs
// clear their cache and re-bootstrap `/me`. A browser without BroadcastChannel simply gets no cross-tab sync.

export type AuthEvent = 'login' | 'logout'

const CHANNEL_NAME = 'auth'

let channel: BroadcastChannel | null = null

function getChannel(): BroadcastChannel | null {
  if (typeof BroadcastChannel === 'undefined') return null
  channel ??= new BroadcastChannel(CHANNEL_NAME)
  return channel
}

function isAuthEvent(data: unknown): data is { type: AuthEvent } {
  if (typeof data !== 'object' || data === null) return false
  const type = (data as { type?: unknown }).type
  return type === 'login' || type === 'logout'
}

export const authChannel = {
  /** Tells the other tabs (never this one) that the session changed. */
  post(type: AuthEvent): void {
    getChannel()?.postMessage({ type })
  },

  /** Calls `listener` for each valid message from another tab; returns an unsubscribe function. */
  subscribe(listener: (type: AuthEvent) => void): () => void {
    const ch = getChannel()
    if (!ch) return () => {}
    const onMessage = (event: MessageEvent) => {
      if (isAuthEvent(event.data)) listener(event.data.type)
    }
    ch.addEventListener('message', onMessage)
    return () => ch.removeEventListener('message', onMessage)
  },

  /** Closes the underlying channel (tests; a later `post`/`subscribe` reopens it). */
  close(): void {
    channel?.close()
    channel = null
  },
}
