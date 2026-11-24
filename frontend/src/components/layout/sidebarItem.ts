import { cn } from '@/lib/utils'

/** A sidebar entry (nav link or Logout): white focus ring on the navy sidebar (frontend.md §8 `--ring`). */
export const sidebarItemClass = (active: boolean, collapsed: boolean) =>
  cn(
    'flex w-full items-center gap-3 rounded-md px-3 py-2 text-sm font-medium text-sidebar-foreground hover:bg-white/10',
    'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-sidebar-ring focus-visible:ring-offset-2 focus-visible:ring-offset-sidebar',
    active && 'bg-sidebar-accent text-white hover:bg-sidebar-accent',
    collapsed && 'justify-center px-0',
  )
