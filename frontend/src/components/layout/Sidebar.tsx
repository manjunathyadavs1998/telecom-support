import { LogOut, RadioTower } from 'lucide-react'
import { cn } from '@/lib/utils'
import type { NavGroup } from '@/permissions/navConfig'
import { NavList } from './NavList'

type SidebarProps = {
  /** `visibleNav(me)`. */
  groups: NavGroup[]
  /** Icon rail (`w-16`) instead of the full sidebar (`w-64`), frontend.md §8. */
  collapsed: boolean
  /** Called when a nav link is chosen (the mobile drawer closes). */
  onNavigate?: () => void
  /** Logout entry point (the shell's `useLogout`). */
  onLogout?: () => void
  className?: string
}

/** The navy sidebar of the mock (frontend.md §3 Application shell): brand, main navigation, Logout at the bottom. */
export function Sidebar({ groups, collapsed, onNavigate, onLogout, className }: SidebarProps) {
  return (
    <div className={cn('flex h-full flex-col bg-sidebar text-sidebar-foreground', collapsed ? 'w-16' : 'w-64', className)}>
      <div className={cn('flex h-16 shrink-0 items-center gap-2 px-4', collapsed && 'justify-center px-0')}>
        <RadioTower aria-hidden="true" className="size-6 shrink-0" />
        <span className={collapsed ? 'sr-only' : 'text-lg font-semibold text-white'}>Telecom</span>
      </div>
      <NavList groups={groups} collapsed={collapsed} onNavigate={onNavigate} />
      <div className="border-t border-white/10 p-2">
        <button
          type="button"
          onClick={onLogout}
          className={cn(
            'flex w-full items-center gap-3 rounded-md px-3 py-2 text-sm font-medium text-sidebar-foreground hover:bg-white/10',
            'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-sidebar-ring focus-visible:ring-offset-2 focus-visible:ring-offset-sidebar',
            collapsed && 'justify-center px-0',
          )}
        >
          <LogOut aria-hidden="true" className="size-5 shrink-0" />
          <span className={collapsed ? 'sr-only' : undefined}>Logout</span>
        </button>
      </div>
    </div>
  )
}
