import type { ComponentProps } from 'react'
import { cn } from '@/lib/utils'

/** shadcn/ui Input (frontend.md §4): focus ring, read-only (submitting) and `aria-invalid` styles. */
export function Input({ className, type = 'text', ...props }: ComponentProps<'input'>) {
  return (
    <input
      type={type}
      className={cn(
        'w-full rounded-md border border-slate-300 bg-white px-3 py-2 text-sm text-foreground read-only:bg-slate-50',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 aria-invalid:border-destructive',
        className,
      )}
      {...props}
    />
  )
}
