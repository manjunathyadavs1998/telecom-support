import { useCallback, useEffect, useMemo, useRef, type MouseEvent } from 'react'
import { Navigate, Outlet, useLocation } from 'react-router'
import { currentLocation, LOGIN_PATH, loginUrl } from '@/app/navigation'
import { ErrorState } from '@/components/states/ErrorState'
import { FullPageSpinner } from '@/components/states/LoadingState'
import { Sheet, SheetContent } from '@/components/ui/sheet'
import { useMeQuery } from '@/features/authentication/meQuery'
import { useLogout } from '@/features/authentication/useLogout'
import { visibleNav } from '@/permissions/navConfig'
import { useSidebarRail, useUiStore } from '@/stores/uiStore'
import type { Me } from '@/types/api'
import { Header } from './Header'
import type { ShellContext } from './shellContext'
import { Sidebar } from './Sidebar'

const MAIN_ID = 'main'

/** Route-change focus target (frontend.md §8, XC-08): the page's `<h1>` (focusable via PageContainer), else `<main>`. */
function focusMainHeading() {
  const main = document.getElementById(MAIN_ID)
  const target = main?.querySelector<HTMLElement>('h1') ?? main
  target?.focus()
}

/** The skip link moves focus itself: a `#main` hash change would be a router navigation, and jsdom/Safari don't move focus. */
function skipToMain(event: MouseEvent<HTMLAnchorElement>) {
  event.preventDefault()
  document.getElementById(MAIN_ID)?.focus()
}

function Shell({ me }: { me: Me }) {
  const groups = useMemo(() => visibleNav(me), [me])
  const rail = useSidebarRail()
  const setSidebarCollapsed = useUiStore((state) => state.setSidebarCollapsed)
  const mobileNavOpen = useUiStore((state) => state.mobileNavOpen)
  const setMobileNavOpen = useUiStore((state) => state.setMobileNavOpen)
  const menuButtonRef = useRef<HTMLButtonElement>(null)
  const focusHeadingOnClose = useRef(false)
  const { pathname } = useLocation()
  const shownPath = useRef(pathname)
  // The one logout flow (§7 Logout) for every entry point: sidebar, drawer, user menu and NoAccessPage (via the outlet).
  const { logout } = useLogout()
  const signOut = useCallback(() => void logout(), [logout])
  const outletContext = useMemo<ShellContext>(() => ({ logout: signOut }), [signOut])

  // A drawer link closes the drawer before the route changes; its (animated) close must then focus the page heading, not
  // return focus to the menu button, or it would steal focus from the new page's <h1> (XC-08).
  const closeDrawerOnNavigate = useCallback(() => {
    focusHeadingOnClose.current = true
    setMobileNavOpen(false)
  }, [setMobileNavOpen])

  // On a route change: close the drawer (its close then focuses the heading) or move focus to the new page's <h1>.
  useEffect(() => {
    if (shownPath.current === pathname) return
    shownPath.current = pathname
    if (useUiStore.getState().mobileNavOpen) {
      focusHeadingOnClose.current = true
      useUiStore.getState().setMobileNavOpen(false)
      return
    }
    focusMainHeading()
  }, [pathname])

  return (
    <div className="flex min-h-screen bg-background">
      <a
        href={`#${MAIN_ID}`}
        onClick={skipToMain}
        className="sr-only rounded-md bg-white px-4 py-2 font-medium text-foreground shadow focus:not-sr-only focus:fixed focus:top-3 focus:left-3 focus:z-50"
      >
        Skip to main content
      </a>
      <Sidebar groups={groups} collapsed={rail} onLogout={signOut} className="sticky top-0 hidden h-screen shrink-0 md:flex" />
      <Sheet open={mobileNavOpen} onOpenChange={setMobileNavOpen}>
        <SheetContent
          title="Navigation"
          closeLabel="Close navigation menu"
          onCloseAutoFocus={(event) => {
            event.preventDefault()
            if (focusHeadingOnClose.current) {
              focusHeadingOnClose.current = false
              focusMainHeading()
            } else {
              menuButtonRef.current?.focus()
            }
          }}
        >
          <Sidebar groups={groups} collapsed={false} onNavigate={closeDrawerOnNavigate} onLogout={signOut} />
        </SheetContent>
      </Sheet>
      <div className="flex min-w-0 flex-1 flex-col">
        <Header
          me={me}
          collapsed={rail}
          onToggleCollapsed={() => setSidebarCollapsed(!rail)}
          mobileNavOpen={mobileNavOpen}
          onOpenMobileNav={() => setMobileNavOpen(true)}
          menuButtonRef={menuButtonRef}
          onSignOut={signOut}
        />
        <main id={MAIN_ID} tabIndex={-1} className="flex-1">
          <Outlet context={outletContext} />
        </main>
      </div>
    </div>
  )
}

/**
 * The protected layout route element (frontend.md §3 Application shell, §8; XC-01, XC-08, NFR-RESP): skip link, sidebar
 * (icon rail per breakpoint/preference), mobile drawer, header and `<main>`. Bootstrap shows `FullPageSpinner`; a `/me`
 * 5xx/network failure a full-page `ErrorState` with retry; a dropped session (another tab signed out) goes to login.
 */
export function AppShell() {
  const { data: me, error, refetch } = useMeQuery()
  const { pathname, search, hash } = useLocation()
  if (me === undefined) {
    if (error) {
      return (
        <main id={MAIN_ID} tabIndex={-1} className="flex min-h-screen items-center justify-center bg-background p-4">
          <ErrorState title="We couldn't load your session" error={error} onRetry={() => void refetch()} />
        </main>
      )
    }
    return <FullPageSpinner />
  }
  if (me === null) {
    // The router is already on its way to /login (logout, the 401 handler): that is the one redirect, add no second one.
    // Show the spinner meanwhile, never a blank screen (XC-03).
    if (currentLocation().pathname === LOGIN_PATH) return <FullPageSpinner />
    return <Navigate to={loginUrl(pathname + search + hash)} replace />
  }
  return <Shell me={me} />
}
