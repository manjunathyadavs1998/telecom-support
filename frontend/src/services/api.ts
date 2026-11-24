import type { components } from './api-types'

/** Shorthand for generated OpenAPI schemas: `Schema<'MeResponse'>`. Never hand-write API types. */
export type Schema<K extends keyof components['schemas']> = components['schemas'][K]

export type Page<T> = {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}
