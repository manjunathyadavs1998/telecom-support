import type { ComponentType } from 'react'

/** What a `route.lazy` page module resolves to (React Router data mode). */
export type LazyPage = () => Promise<{ Component: ComponentType }>

/**
 * Nav item / child id → its page, loaded with `route.lazy` (frontend.md §2, NFR-PERF), so each page is its own chunk.
 * The route guard renders before a lazy page mounts, so a forbidden user never fires its API calls. An id without an
 * entry renders `ComingSoonPage`. Each page task (F9–F20) adds its entry here.
 */
export const PAGE_REGISTRY: Readonly<Partial<Record<string, LazyPage>>> = {}
