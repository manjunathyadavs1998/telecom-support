import { Menu, PanelLeft } from 'lucide-react'
import type { Ref } from 'react'
import { Button } from '@/components/ui/button'
import type { Me } from '@/types/api'
import { Breadcrumbs } from './Breadcrumbs'
import { UserMenu } from './UserMenu'

type HeaderProps = {
  me: Me
  /** The fixed sidebar (≥ md) currently shows as the icon rail. */
  collapsed: boolean
  onToggleCollapsed: () => void
  mobileNavOpen: boolean
  onOpenMobileNav: () => void
  /** The mobile menu button; focus returns here when the drawer closes. */
  menuButtonRef: Ref<HTMLButtonElement>
  /** The user menu's "Sign out" (the shell's `useLogout`). */
  onSignOut?: () => void
}

/**
 * The shell header (frontend.md §3 Application shell, §8 breakpoints), `h-16`: the menu button (< md, opens the drawer) or the
 * collapse/expand toggle (≥ md: tablets start on the icon rail and can expand it), breadcrumbs, and the user menu.
 */
export function Header({ me, collapsed, onToggleCollapsed, mobileNavOpen, onOpenMobileNav, menuButtonRef, onSignOut }: HeaderProps) {
  return (
    <header className="sticky top-0 z-30 flex h-16 shrink-0 items-center gap-3 border-b border-slate-200 bg-white px-4">
      <Button
        ref={menuButtonRef}
        variant="ghost"
        size="icon"
        aria-label="Open navigation menu"
        aria-haspopup="dialog"
        aria-expanded={mobileNavOpen}
        onClick={onOpenMobileNav}
        className="md:hidden"
      >
        <Menu aria-hidden="true" className="size-5" />
      </Button>
      <Button
        variant="ghost"
        size="icon"
        aria-label="Collapse sidebar"
        aria-pressed={collapsed}
        onClick={onToggleCollapsed}
        className="hidden md:inline-flex"
      >
        <PanelLeft aria-hidden="true" className="size-5" />
      </Button>
      <Breadcrumbs />
      <div className="ml-auto">
        <UserMenu me={me} onSignOut={onSignOut} />
      </div>
    </header>
  )
}
