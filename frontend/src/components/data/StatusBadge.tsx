import { CircleDot } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { cn } from '@/lib/utils'
import { LOOKS, type Look, type StatusBadgeProps, type Tone } from './statusLabel'

export type { StatusBadgeProps } from './statusLabel'

const TONES: Record<Tone, string> = {
  neutral: 'border-slate-300 bg-slate-100 text-foreground',
  info: 'border-primary/30 bg-primary/10 text-primary',
  success: 'border-success/30 bg-success/10 text-success',
  warning: 'border-warning/30 bg-warning/10 text-warning',
  danger: 'border-destructive/30 bg-destructive/10 text-destructive',
}

/**
 * Status badge (frontend.md §4, XC-08): text label + icon + token colour, so the meaning never depends on colour alone. An unknown
 * value (a newer server) falls back to the raw value in the neutral style.
 */
export function StatusBadge(props: StatusBadgeProps & { className?: string }) {
  const look: Look = (LOOKS[props.kind] as Record<string, Look>)[props.value] ?? {
    label: props.value,
    icon: CircleDot,
    tone: 'neutral',
  }
  const Icon = look.icon
  return (
    <Badge className={cn('gap-1', TONES[look.tone], props.className)} data-status={props.value}>
      <Icon aria-hidden="true" className="size-3.5" />
      {look.label}
    </Badge>
  )
}
