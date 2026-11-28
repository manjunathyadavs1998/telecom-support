import type { ComponentProps } from 'react'
import { cn } from '@/lib/utils'

/** shadcn/ui Badge (frontend.md §4), the neutral variant only (status variants arrive with the slices that need them). */
export function Badge({ className, ...props }: ComponentProps<'span'>) {
  return (
    <span
      className={cn(
        'inline-flex items-center rounded-full border border-line-strong bg-surface-2 px-2.5 py-0.5 text-xs font-medium text-text-2',
        className,
      )}
      {...props}
    />
  )
}
