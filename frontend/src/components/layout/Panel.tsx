import { useId, type ReactNode } from 'react'
import { cn } from '@/lib/utils'

type PanelProps = {
  /** Section heading (h2); the section is labelled by it. */
  title?: ReactNode
  /** One line under the title. */
  description?: ReactNode
  /** Header actions (links, buttons), right-aligned. */
  actions?: ReactNode
  /** `inset` = the quieter surface for filter bars and read-only blocks. */
  tone?: 'default' | 'inset'
  className?: string
  children?: ReactNode
}

/**
 * A titled content section (ui-refresh.md U4): the one panel style for every screen — soft border, warm surface, small shadow,
 * eased entry. Replaces hand-written `rounded-lg border bg-white p-4` sections; semantics stay a labelled `<section>`.
 */
export function Panel({ title, description, actions, tone = 'default', className, children }: PanelProps) {
  const headingId = useId()
  return (
    <section
      aria-labelledby={title ? headingId : undefined}
      className={cn(
        'enter-soft flex flex-col gap-4 rounded-xl border border-line p-4 md:p-6',
        tone === 'inset' ? 'bg-surface-2' : 'bg-surface shadow-soft-sm',
        className,
      )}
    >
      {title || actions ? (
        <div className="flex flex-wrap items-start justify-between gap-2">
          {title ? (
            <div className="flex flex-col gap-0.5">
              <h2 id={headingId} className="text-base font-semibold tracking-tight text-foreground">
                {title}
              </h2>
              {description ? <p className="text-sm text-muted-foreground">{description}</p> : null}
            </div>
          ) : null}
          {actions ? <div className="flex flex-wrap items-center gap-2">{actions}</div> : null}
        </div>
      ) : null}
      {children}
    </section>
  )
}
