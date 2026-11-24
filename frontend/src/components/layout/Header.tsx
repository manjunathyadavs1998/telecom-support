import { Menu, PanelLeft } from 'lucide-react'
import type { Ref } from 'react'
import type { Me } from '@/types/api'
import { Breadcrumbs } from './Breadcrumbs'
import { UserMenu } from './UserMenu'

type HeaderProps = {
  me: Me
  /** Desktop (≥ lg) sidebar collapsed to the icon rail. */
  collapsed: boolean
  onToggleCollapsed: () => void
  mobileNavOpen: boolean
  onOpenMobileNav: () => void
  /** The mobile menu button; focus returns here when the drawer closes. */
  menuButtonRef: Ref<HTMLButtonElement>
  /** The user menu's "Sign out" (the shell's `useLogout`). */
  onSignOut?: () => void
}

const iconButton =
  'items-center justify-center rounded-md p-2 text-foreground hover:bg-slate-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2'

/**
 * The shell header (frontend.md §3 Application shell, §8 breakpoints), `h-16`: the menu button (< md, opens the drawer) or the
 * collapse toggle (≥ lg), breadcrumbs, and the user menu.
 */
export function Header({ me, collapsed, onToggleCollapsed, mobileNavOpen, onOpenMobileNav, menuButtonRef, onSignOut }: HeaderProps) {
  return (
    <header className="sticky top-0 z-30 flex h-16 shrink-0 items-center gap-3 border-b border-slate-200 bg-white px-4">
      <button
        ref={menuButtonRef}
        type="button"
        aria-label="Open navigation menu"
        aria-haspopup="dialog"
        aria-expanded={mobileNavOpen}
        onClick={onOpenMobileNav}
        className={`inline-flex md:hidden ${iconButton}`}
      >
        <Menu aria-hidden="true" className="size-5" />
      </button>
      <button
        type="button"
        aria-label="Collapse sidebar"
        aria-pressed={collapsed}
        onClick={onToggleCollapsed}
        className={`hidden lg:inline-flex ${iconButton}`}
      >
        <PanelLeft aria-hidden="true" className="size-5" />
      </button>
      <Breadcrumbs />
      <div className="ml-auto">
        <UserMenu me={me} onSignOut={onSignOut} />
      </div>
    </header>
  )
}
