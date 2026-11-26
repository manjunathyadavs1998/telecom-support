import type { ComponentProps } from 'react'
import { cn } from '@/lib/utils'

/** shadcn/ui Textarea (frontend.md §4): same focus ring, read-only and `aria-invalid` styles as Input. */
export function Textarea({ className, ...props }: ComponentProps<'textarea'>) {
  return (
    <textarea
      className={cn(
        'min-h-24 w-full rounded-md border border-slate-300 bg-white px-3 py-2 text-sm text-foreground read-only:bg-slate-50',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 aria-invalid:border-destructive',
        className,
      )}
      {...props}
    />
  )
}
