import { ChevronLeft, ChevronRight } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

type PaginationProps = {
  /** Current page, **1-based** (as in the URL). */
  page: number
  totalPages: number
  totalItems: number
  size: number
  onPage: (page: number) => void
}

type Slot = number | 'gap-start' | 'gap-end'

/** First, last, and the current page ± 1, with gaps between. */
function pageSlots(page: number, totalPages: number): Slot[] {
  const pages = new Set([1, totalPages, page - 1, page, page + 1].filter((p) => p >= 1 && p <= totalPages))
  const sorted = [...pages].sort((a, b) => a - b)
  const slots: Slot[] = []
  sorted.forEach((p, index) => {
    const previous = sorted[index - 1]
    if (previous !== undefined && p - previous > 1) slots.push(p < page ? 'gap-start' : 'gap-end')
    slots.push(p)
  })
  return slots
}

/**
 * List pagination (frontend.md §3 Service requests, §4; XC-04): "Showing 21–40 of 57" and a `<nav aria-label="Pagination">` with
 * Previous/Next and page buttons (`aria-current="page"` on the current one). A page past the end (e.g. an old deep link) shows
 * "This page is empty" with "Go to page 1" instead.
 */
export function Pagination({ page, totalPages, totalItems, size, onPage }: PaginationProps) {
  if (totalItems <= 0 && page <= 1) return null

  if (page > totalPages) {
    return (
      <div className="flex flex-col items-start gap-2 sm:flex-row sm:items-center">
        <p className="text-sm text-muted-foreground">This page is empty.</p>
        <Button variant="outline" size="sm" onClick={() => onPage(1)}>
          Go to page 1
        </Button>
      </div>
    )
  }

  const first = (page - 1) * size + 1
  const last = Math.min(page * size, totalItems)

  return (
    <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
      <p className="text-sm text-muted-foreground">
        Showing {first}–{last} of {totalItems}
      </p>
      {totalPages > 1 ? (
        <nav aria-label="Pagination">
          <ul className="flex flex-wrap items-center gap-1">
            <li>
              <Button variant="ghost" size="sm" disabled={page <= 1} onClick={() => onPage(page - 1)}>
                <ChevronLeft aria-hidden="true" className="size-4" />
                Previous
              </Button>
            </li>
            {pageSlots(page, totalPages).map((slot) =>
              typeof slot === 'number' ? (
                <li key={slot}>
                  <Button
                    variant={slot === page ? 'outline' : 'ghost'}
                    size="sm"
                    aria-current={slot === page ? 'page' : undefined}
                    aria-label={`Page ${slot}`}
                    className={cn('min-w-8', slot === page && 'border-primary/30 bg-primary-soft font-semibold text-primary')}
                    onClick={() => onPage(slot)}
                  >
                    {slot}
                  </Button>
                </li>
              ) : (
                <li key={slot} aria-hidden="true" className="px-1 text-muted-foreground">
                  …
                </li>
              ),
            )}
            <li>
              <Button variant="ghost" size="sm" disabled={page >= totalPages} onClick={() => onPage(page + 1)}>
                Next
                <ChevronRight aria-hidden="true" className="size-4" />
              </Button>
            </li>
          </ul>
        </nav>
      ) : null}
    </div>
  )
}
