import { useSyncExternalStore } from 'react'
import { create } from 'zustand'
import { persist } from 'zustand/middleware'

type UiState = {
  /**
   * The user's sidebar choice at ≥ md, persisted: `true` icon rail, `false` full sidebar, `null` no choice yet (the breakpoint
   * default: full at ≥ lg, icon rail at md–lg).
   */
  sidebarCollapsed: boolean | null
  /** Mobile (< md) navigation drawer open; never persisted. */
  mobileNavOpen: boolean
  /** Records an explicit choice; the header toggle passes the opposite of what is shown now (`useSidebarRail`). */
  setSidebarCollapsed: (collapsed: boolean) => void
  setMobileNavOpen: (open: boolean) => void
}

/**
 * UI-only state (frontend.md §5; CLAUDE.md: Zustand holds only session/UI state, server state stays in TanStack Query).
 * `persist` → `localStorage` key `tcs-ui`, and only `sidebarCollapsed` is written.
 */
export const useUiStore = create<UiState>()(
  persist(
    (set) => ({
      sidebarCollapsed: null,
      mobileNavOpen: false,
      setSidebarCollapsed: (collapsed) => set({ sidebarCollapsed: collapsed }),
      setMobileNavOpen: (open) => set({ mobileNavOpen: open }),
    }),
    {
      name: 'tcs-ui',
      version: 1,
      partialize: (state) => ({ sidebarCollapsed: state.sidebarCollapsed }),
      // v0 stored `false` for "never collapsed", which would now force the full sidebar at md–lg: read it as "no choice".
      migrate: (persisted, version) => {
        const saved = (persisted ?? {}) as { sidebarCollapsed?: boolean | null }
        return { sidebarCollapsed: version === 0 && saved.sidebarCollapsed !== true ? null : (saved.sidebarCollapsed ?? null) }
      },
    },
  ),
)

/** Tailwind `lg` (frontend.md §8 breakpoints). */
export const DESKTOP_QUERY = '(min-width: 1024px)'

function subscribeDesktop(onChange: () => void): () => void {
  if (typeof window.matchMedia !== 'function') return () => {}
  const query = window.matchMedia(DESKTOP_QUERY)
  query.addEventListener('change', onChange)
  return () => query.removeEventListener('change', onChange)
}

/** No `matchMedia` (jsdom): treat as desktop. */
const isDesktop = (): boolean => (typeof window.matchMedia === 'function' ? window.matchMedia(DESKTOP_QUERY).matches : true)

/**
 * Whether the fixed sidebar shows as an icon rail (frontend.md §8): the user's explicit choice when there is one, else the
 * breakpoint default (full at ≥ lg, icon rail at md–lg, so a touch tablet can still expand it to read the labels). Below md
 * the sidebar is hidden and the drawer shows it in full.
 */
export function useSidebarRail(): boolean {
  const collapsed = useUiStore((state) => state.sidebarCollapsed)
  const desktop = useSyncExternalStore(subscribeDesktop, isDesktop)
  return collapsed ?? !desktop
}
