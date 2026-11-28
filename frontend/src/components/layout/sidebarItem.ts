import { cn } from '@/lib/utils'

/** A sidebar entry (nav link or Logout): white focus ring on the navy sidebar (frontend.md §8 `--ring`). */
export const sidebarItemClass = (active: boolean, collapsed: boolean) =>
  cn(
    'transition-soft flex w-full items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium text-sidebar-foreground/90 hover:bg-white/[0.08] hover:text-white',
    'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-sidebar-ring focus-visible:ring-offset-2 focus-visible:ring-offset-sidebar',
    active && 'bg-sidebar-accent text-white shadow-soft-md hover:bg-sidebar-accent',
    collapsed && 'justify-center px-0',
  )
