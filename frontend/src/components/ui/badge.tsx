import type { ComponentProps } from 'react'
import { cn } from '@/lib/utils'

/** shadcn/ui Badge (frontend.md §4), the neutral variant only (status variants arrive with the slices that need them). */
export function Badge({ className, ...props }: ComponentProps<'span'>) {
  return (
    <span
      className={cn(
        'inline-flex items-center rounded-md border border-slate-300 bg-slate-100 px-2 py-0.5 text-xs font-medium text-foreground',
        className,
      )}
      {...props}
    />
  )
}
