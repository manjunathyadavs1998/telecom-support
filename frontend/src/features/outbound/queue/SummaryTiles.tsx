import { cn } from '@/lib/utils'
import type { QueueSummary } from '@/types/api'
import { TILES, type Shortcut } from './useQueueParams'

type SummaryTilesProps = {
  summary: QueueSummary
  active: string | null
  onShortcut: (shortcut: Shortcut) => void
}

/** OUT-01: 8 counts, each a toggle button that applies (or, when active, clears) its filter shortcut. */
export function SummaryTiles({ summary, active, onShortcut }: SummaryTilesProps) {
  return (
    <div className="grid grid-cols-2 gap-2 sm:grid-cols-4 xl:grid-cols-8">
      {TILES.map((tile) => {
        const count = Number(summary[tile.key] ?? 0)
        const pressed = active === tile.key
        const danger = tile.key === 'overdue' && count > 0
        return (
          <button
            key={tile.key}
            type="button"
            aria-pressed={pressed}
            aria-label={`${tile.label}: ${count} ${count === 1 ? 'case' : 'cases'}`}
            onClick={() => onShortcut(pressed ? { status: [], severity: [], overdue: false } : tile.shortcut)}
            className={cn(
              'transition-soft flex flex-col items-start rounded-xl border border-line bg-surface p-3 text-left shadow-soft-sm hover:-translate-y-0.5 hover:bg-surface-hover hover:shadow-soft-md focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
              pressed && 'border-primary bg-primary-soft ring-1 ring-primary',
              danger && 'border-destructive/40',
            )}
          >
            <span className="text-sm text-muted-foreground">{tile.label}</span>
            <span className={cn('text-2xl font-semibold', danger && 'text-destructive')}>{count}</span>
          </button>
        )
      })}
    </div>
  )
}
