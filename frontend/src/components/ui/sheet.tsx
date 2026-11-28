import { X } from 'lucide-react'
import { Dialog as SheetPrimitive } from 'radix-ui'
import type { ComponentProps } from 'react'
import { cn } from '@/lib/utils'

/** shadcn/ui Sheet (frontend.md §4): a Radix Dialog sliding in from the left — modal, focus trapped, `Esc` closes. */
export const Sheet = SheetPrimitive.Root

type SheetContentProps = ComponentProps<typeof SheetPrimitive.Content> & {
  /** Accessible name of the dialog (visually hidden). */
  title: string
  closeLabel?: string
}

export function SheetContent({ title, closeLabel = 'Close', className, children, ...props }: SheetContentProps) {
  return (
    <SheetPrimitive.Portal>
      <SheetPrimitive.Overlay className="fixed inset-0 z-40 bg-black/40 backdrop-blur-[2px] data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=open]:fade-in-0 data-[state=closed]:fade-out-0 duration-200" />
      <SheetPrimitive.Content
        aria-describedby={undefined}
        className={cn('fixed inset-y-0 left-0 z-50 flex h-full shadow-soft-lg focus:outline-none data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=open]:slide-in-from-left data-[state=closed]:slide-out-to-left duration-250 ease-soft', className)}
        {...props}
      >
        <SheetPrimitive.Title className="sr-only">{title}</SheetPrimitive.Title>
        {children}
        <SheetPrimitive.Close
          className="absolute top-4 right-3 rounded-md p-1 text-sidebar-foreground hover:bg-surface/10 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-sidebar-ring"
          aria-label={closeLabel}
        >
          <X aria-hidden="true" className="size-5" />
        </SheetPrimitive.Close>
      </SheetPrimitive.Content>
    </SheetPrimitive.Portal>
  )
}
