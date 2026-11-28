import { ShieldCheck } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { Badge } from '@/components/ui/badge'
import { cn } from '@/lib/utils'
import { minutesLeft, WARN_AT_MINUTES } from './queries'

const TICK_MS = 15_000

type VerifiedBadgeProps = {
  validUntil: string
  serverNow: () => number
  /** Called once when the window has elapsed (the frame refetches I4 once). */
  onElapsed: () => void
}

/**
 * "Verified · N min left" (03-inbound/frontend.md §3.3, §4): ticks every 15 s against the server clock, warning tone and one
 * polite announcement at 5 min left. The check is never extended here (backend R5); the server 403 stays the authority.
 */
export function VerifiedBadge({ validUntil, serverNow, onElapsed }: VerifiedBadgeProps) {
  const [, setTick] = useState(0)
  const elapsedFor = useRef<string | null>(null)
  const left = minutesLeft(validUntil, serverNow)

  useEffect(() => {
    const timer = setInterval(() => setTick((n) => n + 1), TICK_MS)
    return () => clearInterval(timer)
  }, [])

  useEffect(() => {
    if (left === 0 && elapsedFor.current !== validUntil) {
      elapsedFor.current = validUntil
      onElapsed()
    }
  }, [left, validUntil, onElapsed])

  const warn = left <= WARN_AT_MINUTES
  return (
    <>
      <Badge
        className={cn(
          'gap-1',
          warn
            ? 'border-warning/30 bg-warning/10 text-warning-foreground'
            : 'border-success/30 bg-success/10 text-success-foreground',
        )}
      >
        <ShieldCheck aria-hidden="true" className="size-3.5" />
        {left > 0 ? `Verified · ${left} min left` : 'Verification ended'}
      </Badge>
      <span aria-live="polite" className="sr-only">
        {warn && left > 0 ? `Verification ends in ${WARN_AT_MINUTES} minutes or less.` : ''}
      </span>
    </>
  )
}
