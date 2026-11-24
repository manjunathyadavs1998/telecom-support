import { Slot } from 'radix-ui'
import type { ComponentProps } from 'react'
import { cn } from '@/lib/utils'

const base =
  'inline-flex shrink-0 items-center justify-center gap-2 rounded-md text-sm font-medium whitespace-nowrap transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:pointer-events-none disabled:cursor-not-allowed disabled:opacity-60'

const variants = {
  default: 'bg-primary text-primary-foreground hover:bg-primary/90',
  outline: 'border border-muted-foreground/40 bg-white text-foreground hover:bg-slate-100',
  ghost: 'text-foreground hover:bg-slate-100',
} as const

const sizes = {
  default: 'px-4 py-2',
  sm: 'px-2 py-1',
  icon: 'p-2',
} as const

type ButtonProps = ComponentProps<'button'> & {
  variant?: keyof typeof variants
  size?: keyof typeof sizes
  /** Render the single child (e.g. a link or a Radix trigger) with the button's styles. */
  asChild?: boolean
}

/** shadcn/ui Button (frontend.md §4): every variant carries the §8 focus ring (`focus-visible:ring-2 ring-offset-2`). */
export function Button({ variant = 'default', size = 'default', asChild = false, className, type, ...props }: ButtonProps) {
  const classes = cn(base, variants[variant], sizes[size], className)
  if (asChild) return <Slot.Root className={classes} {...props} />
  return <button type={type ?? 'button'} className={classes} {...props} />
}
