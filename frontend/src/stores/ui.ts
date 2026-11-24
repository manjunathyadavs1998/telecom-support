import { create } from 'zustand'

/**
 * Small client-only UI state (BRD §10.2). Server data such as the signed-in user lives in TanStack Query,
 * never duplicated here.
 */
type UiState = {
  mobileNavOpen: boolean
  setMobileNavOpen: (open: boolean) => void
}

export const useUiStore = create<UiState>((set) => ({
  mobileNavOpen: false,
  setMobileNavOpen: (open) => set({ mobileNavOpen: open }),
}))
