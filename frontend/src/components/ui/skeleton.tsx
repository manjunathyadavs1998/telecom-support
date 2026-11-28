import type { ComponentProps } from 'react'
import { cn } from '@/lib/utils'

/** shadcn/ui Skeleton (frontend.md §4). A `<span>` (block) so it fits phrasing-only parents such as `<output>`. */
export function Skeleton({ className, ...props }: ComponentProps<'span'>) {
  return <span className={cn('skeleton-shimmer block h-4 rounded-md', className)} {...props} />
}
