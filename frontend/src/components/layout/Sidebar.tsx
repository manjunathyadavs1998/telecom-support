import { LogOut, RadioTower } from 'lucide-react'
import { Separator } from '@/components/ui/separator'
import { cn } from '@/lib/utils'
import type { NavGroup } from '@/permissions/navConfig'
import { NavList, RailTooltip } from './NavList'
import { sidebarItemClass } from './sidebarItem'

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
    <div
      className={cn(
        'flex h-full flex-col bg-sidebar text-sidebar-foreground transition-[width] duration-200 ease-soft print:hidden',
        collapsed ? 'w-16' : 'w-64',
        className,
      )}
    >
      <div className={cn('flex h-16 shrink-0 items-center gap-2 px-4', collapsed && 'justify-center px-0')}>
        <RadioTower aria-hidden="true" className="size-6 shrink-0" />
        <span className={collapsed ? 'sr-only' : 'text-lg font-semibold tracking-tight text-white'}>Telecom</span>
      </div>
      <NavList groups={groups} collapsed={collapsed} onNavigate={onNavigate} />
      <Separator className="bg-white/[0.08]" />
      <div className="p-2">
        <RailTooltip label="Logout" collapsed={collapsed}>
          <button type="button" onClick={onLogout} className={sidebarItemClass(false, collapsed)}>
            <LogOut aria-hidden="true" className="size-5 shrink-0" />
            <span className={collapsed ? 'sr-only' : undefined}>Logout</span>
          </button>
        </RailTooltip>
      </div>
    </div>
  )
}
