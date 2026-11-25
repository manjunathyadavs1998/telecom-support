import { Slot } from 'radix-ui'
import type { ComponentProps } from 'react'
import { cn } from '@/lib/utils'

const variants = {
  info: 'border-primary/30 bg-primary/5 text-foreground',
  destructive: 'border-destructive/40 bg-destructive/5 text-destructive',
} as const

type AlertProps = ComponentProps<'div'> & {
  variant?: keyof typeof variants
  /** Style the single child instead, e.g. an `<output>` for a polite, non-urgent message (the login `?reason=` banner). */
  asChild?: boolean
}

/** shadcn/ui Alert (frontend.md §4): `role="alert"`, or the child's own semantics with `asChild`. */
export function Alert({ variant = 'info', asChild = false, className, ...props }: AlertProps) {
  const classes = cn('flex items-start gap-2 rounded-md border p-3 text-sm', variants[variant], className)
  if (asChild) return <Slot.Root className={classes} {...props} />
  return <div role="alert" className={classes} {...props} />
}
