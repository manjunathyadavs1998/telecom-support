import type { ReactNode } from 'react'

type EmptyStateProps = {
  /** Decorative; hidden from assistive technology. */
  icon: ReactNode
  title: string
  description?: string
  action?: ReactNode
}

/** Empty state (frontend.md §4, XC-03): nothing to show yet, with an optional next step. */
export function EmptyState({ icon, title, description, action }: EmptyStateProps) {
  return (
    <div className="flex flex-col items-center gap-3 px-4 py-10 text-center">
      <span aria-hidden="true" className="text-muted-foreground">
        {icon}
      </span>
      <h2 className="text-lg font-semibold text-foreground">{title}</h2>
      {description ? <p className="max-w-prose text-muted-foreground">{description}</p> : null}
      {action ? <div className="mt-2">{action}</div> : null}
    </div>
  )
}
