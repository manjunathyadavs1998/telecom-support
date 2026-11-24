import { useSyncExternalStore } from 'react'
import { create } from 'zustand'
import { persist } from 'zustand/middleware'

type UiState = {
  /** Desktop (≥ lg) sidebar shown as an icon rail; persisted. */
  sidebarCollapsed: boolean
  /** Mobile (< md) navigation drawer open; never persisted. */
  mobileNavOpen: boolean
  toggleSidebarCollapsed: () => void
  setMobileNavOpen: (open: boolean) => void
}

/**
 * UI-only state (frontend.md §5; CLAUDE.md: Zustand holds only session/UI state, server state stays in TanStack Query).
 * `persist` → `localStorage` key `tcs-ui`, and only `sidebarCollapsed` is written.
 */
export const useUiStore = create<UiState>()(
  persist(
    (set) => ({
      sidebarCollapsed: false,
      mobileNavOpen: false,
      toggleSidebarCollapsed: () => set((state) => ({ sidebarCollapsed: !state.sidebarCollapsed })),
      setMobileNavOpen: (open) => set({ mobileNavOpen: open }),
    }),
    { name: 'tcs-ui', partialize: (state) => ({ sidebarCollapsed: state.sidebarCollapsed }) },
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
 * Whether the fixed sidebar shows as an icon rail (frontend.md §8): at ≥ lg when the user collapsed it, and always at
 * md–lg (tablet); below md the sidebar is hidden and the drawer shows it in full.
 */
export function useSidebarRail(): boolean {
  const collapsed = useUiStore((state) => state.sidebarCollapsed)
  const desktop = useSyncExternalStore(subscribeDesktop, isDesktop)
  return collapsed || !desktop
}
