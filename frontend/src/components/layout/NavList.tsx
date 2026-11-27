import { useId, type ReactElement } from 'react'
import { NavLink } from 'react-router'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import type { NavGroup, NavItem } from '@/permissions/navConfig'
import { sidebarItemClass } from './sidebarItem'

type NavListProps = {
  groups: NavGroup[]
  /** Icon rail: labels are screen-reader only and shown in a `Tooltip`. */
  collapsed: boolean
  onNavigate?: () => void
}

/** In the icon rail the label is screen-reader only, so it is also shown in a `Tooltip` (frontend.md §8). */
export function RailTooltip({ label, collapsed, children }: { label: string; collapsed: boolean; children: ReactElement }) {
  if (!collapsed) return children
  return (
    <Tooltip>
      <TooltipTrigger asChild>{children}</TooltipTrigger>
      <TooltipContent side="right">{label}</TooltipContent>
    </Tooltip>
  )
}

function NavEntry({ item, collapsed, onNavigate }: { item: NavItem; collapsed: boolean; onNavigate?: () => void }) {
  const Icon = item.icon
  // `end` only for items without child routes (frontend.md §2): Home (/customer) and Case queue (/outbound) are not active
  // on other items' pages, while an item with children (Payment history) stays `aria-current="page"` on them.
  const link = (
    <NavLink to={item.path} end={!item.children?.length} onClick={onNavigate} className={({ isActive }) => sidebarItemClass(isActive, collapsed)}>
      <Icon aria-hidden="true" className="size-5 shrink-0" />
      <span className={collapsed ? 'sr-only' : undefined}>{item.label}</span>
    </NavLink>
  )
  return (
    <RailTooltip label={item.label} collapsed={collapsed}>
      {link}
    </RailTooltip>
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
