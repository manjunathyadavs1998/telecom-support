import type { ComponentProps } from 'react'
import { cn } from '@/lib/utils'

/** shadcn/ui Card (frontend.md §4): the white, rounded, shadowed surface (the login card). */
export function Card({ className, ...props }: ComponentProps<'div'>) {
  return <div className={cn('rounded-xl border border-line bg-surface p-6 text-foreground shadow-soft-lg', className)} {...props} />
}

export function CardHeader({ className, ...props }: ComponentProps<'div'>) {
  return <div className={cn('mb-4 flex flex-col gap-6', className)} {...props} />
}
