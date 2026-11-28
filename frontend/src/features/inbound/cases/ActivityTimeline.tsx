import { Eye, Lock } from 'lucide-react'
import { statusLabel } from '@/components/data/statusLabel'
import { Badge } from '@/components/ui/badge'
import { formatDateTime } from '@/lib/format'
import { cn } from '@/lib/utils'
import type { CaseActivity, CaseStatus } from '@/types/api'

const internalLabel = (value: string | null | undefined) =>
  value ? statusLabel({ kind: 'caseInternal', value: value as CaseStatus }) : '—'

function describe(entry: CaseActivity): string {
  switch (entry.type) {
    case 'CREATED':
      return 'Request created'
    case 'STATUS_CHANGED':
      return `Status changed: ${internalLabel(entry.oldValue)} → ${internalLabel(entry.newValue)}`
    case 'ASSIGNED':
      return entry.newValue ? `Assigned to ${entry.newValue}` : 'Assignment changed'
    case 'NOTE':
      return 'Note'
    default:
      return entry.type
  }
}

/**
 * Activity timeline (03-inbound/frontend.md §3.7; INB-02.2, BR-04): oldest first as served. Each entry carries a text badge;
 * INTERNAL entries also get a lock icon and a distinct background (not colour only, XC-08). The UI shows only what I10 returns.
 */
export function ActivityTimeline({ activity }: { activity: readonly CaseActivity[] }) {
  if (activity.length === 0) return <p className="text-muted-foreground">No activity yet.</p>
  return (
    <ol className="flex flex-col gap-3">
      {activity.map((entry, index) => {
        const internal = entry.visibility === 'INTERNAL'
        return (
          <li
            // Activity has no id; the server order is stable.
            key={`${entry.at}-${index}`}
            className={cn('flex flex-col gap-1 rounded-md border p-3', internal ? 'border-amber-300 bg-amber-50' : 'bg-white')}
          >
            <div className="flex flex-wrap items-center gap-2 text-sm">
              <time dateTime={entry.at} className="text-muted-foreground">
                {formatDateTime(entry.at)}
              </time>
              <span className="font-medium">{entry.actorName ?? 'System'}</span>
              {internal ? (
                <Badge className="gap-1 border-amber-400 bg-amber-100 text-amber-900">
                  <Lock aria-hidden="true" className="size-3.5" />
                  Internal — never shown to the customer
                </Badge>
              ) : (
                <Badge className="gap-1 border-slate-300 bg-slate-100 text-slate-700">
                  <Eye aria-hidden="true" className="size-3.5" />
                  Visible to customer
                </Badge>
              )}
            </div>
            <p>{describe(entry)}</p>
            {entry.note ? <p className="whitespace-pre-wrap break-words text-sm">{entry.note}</p> : null}
          </li>
        )
      })}
    </ol>
  )
}
