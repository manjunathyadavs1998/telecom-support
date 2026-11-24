import { ChevronRight } from 'lucide-react'
import { Link, useMatches } from 'react-router'

type Crumb = { id: string; label: string; path: string }

function crumbOf(handle: unknown): string | null {
  if (typeof handle !== 'object' || handle === null || !('crumb' in handle)) return null
  return typeof handle.crumb === 'string' ? handle.crumb : null
}

/** Breadcrumbs from the matched routes' `handle.crumb` (frontend.md §3 Application shell, §4); the last one is the page. */
export function Breadcrumbs() {
  const crumbs: Crumb[] = useMatches().flatMap((match) => {
    const label = crumbOf(match.handle)
    return label ? [{ id: match.id, label, path: match.pathname }] : []
  })
  if (crumbs.length === 0) return null
  return (
    <nav aria-label="Breadcrumb" className="min-w-0">
      <ol className="flex items-center gap-1 text-sm text-muted-foreground">
        {crumbs.map((crumb, index) => {
          const last = index === crumbs.length - 1
          return (
            <li key={crumb.id} className="flex min-w-0 items-center gap-1">
              {index > 0 ? <ChevronRight aria-hidden="true" className="size-4 shrink-0" /> : null}
              {last ? (
                <span aria-current="page" className="truncate font-medium text-foreground">
                  {crumb.label}
                </span>
              ) : (
                <Link to={crumb.path} className="truncate rounded-sm hover:text-foreground hover:underline">
                  {crumb.label}
                </Link>
              )}
            </li>
          )
        })}
      </ol>
    </nav>
  )
}
