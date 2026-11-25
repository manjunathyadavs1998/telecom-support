import type { ComponentProps } from 'react'
import { cn } from '@/lib/utils'

/** shadcn/ui Skeleton (frontend.md §4). A `<span>` (block) so it fits phrasing-only parents such as `<output>`. */
export function Skeleton({ className, ...props }: ComponentProps<'span'>) {
  return <span className={cn('block h-4 animate-pulse rounded-md bg-muted-foreground/15', className)} {...props} />
}
