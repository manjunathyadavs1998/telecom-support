import { useId } from 'react'
import { NavLink } from 'react-router'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { cn } from '@/lib/utils'
import type { NavGroup, NavItem } from '@/permissions/navConfig'

type NavListProps = {
  groups: NavGroup[]
  /** Icon rail: labels are screen-reader only and shown in a `Tooltip`. */
  collapsed: boolean
  onNavigate?: () => void
}

const linkClass = (active: boolean, collapsed: boolean) =>
  cn(
    'flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium text-sidebar-foreground hover:bg-white/10',
    'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-sidebar-ring focus-visible:ring-offset-2 focus-visible:ring-offset-sidebar',
    active && 'bg-sidebar-accent text-white hover:bg-sidebar-accent',
    collapsed && 'justify-center px-0',
  )

function NavEntry({ item, collapsed, onNavigate }: { item: NavItem; collapsed: boolean; onNavigate?: () => void }) {
  const Icon = item.icon
  // `end`: Home (/customer) and Case queue (/outbound) are not active on their sub-pages.
  const link = (
    <NavLink to={item.path} end onClick={onNavigate} className={({ isActive }) => linkClass(isActive, collapsed)}>
      <Icon aria-hidden="true" className="size-5 shrink-0" />
      <span className={collapsed ? 'sr-only' : undefined}>{item.label}</span>
    </NavLink>
  )
  if (!collapsed) return link
  return (
    <Tooltip>
      <TooltipTrigger asChild>{link}</TooltipTrigger>
      <TooltipContent side="right">{item.label}</TooltipContent>
    </Tooltip>
  )
}

/**
 * The main navigation (frontend.md §2, §3 Application shell, XC-01): `<nav aria-label="Main">`, one list per section in §2
 * order, each named by its heading when the user has more than one section; the active link has `aria-current="page"`.
 */
export function NavList({ groups, collapsed, onNavigate }: NavListProps) {
  const idPrefix = useId()
  return (
    <nav aria-label="Main" className="flex flex-1 flex-col gap-4 overflow-y-auto px-2 py-4">
      {groups.map((group) => {
        const headingId = `${idPrefix}-${group.section}`
        return (
          <div key={group.section} className="flex flex-col gap-1">
            {group.heading ? (
              <p
                id={headingId}
                className={
                  collapsed ? 'sr-only' : 'px-3 pb-1 text-xs font-semibold uppercase tracking-wide text-sidebar-foreground/80'
                }
              >
                {group.heading}
              </p>
            ) : null}
            <ul aria-labelledby={group.heading ? headingId : undefined} className="flex flex-col gap-1">
              {group.items.map((item) => (
                <li key={item.id}>
                  <NavEntry item={item} collapsed={collapsed} onNavigate={onNavigate} />
                </li>
              ))}
            </ul>
          </div>
        )
      })}
    </nav>
  )
}
