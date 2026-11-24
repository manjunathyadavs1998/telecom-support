import * as Dialog from '@radix-ui/react-dialog'
import { X } from 'lucide-react'
import { useEffect } from 'react'
import { Navigate, Outlet, useLocation, useNavigate } from 'react-router'
import { visibleNavigation } from '@/app/navigation'
import { ErrorState, LoadingState } from '@/components/feedback/states'
import { useLogout, useMe } from '@/features/authentication/api'
import { useUiStore } from '@/stores/ui'
import { Header } from './Header'
import { Sidebar } from './Sidebar'

/** Authenticated application shell (BRD §16): sidebar, header, profile menu. Redirects to /login without a session. */
export function AppShell() {
  const { data: me, isPending, isError, error, refetch } = useMe()
  const logout = useLogout()
  const navigate = useNavigate()
  const location = useLocation()
  const { mobileNavOpen, setMobileNavOpen } = useUiStore()

  useEffect(() => setMobileNavOpen(false), [location.pathname, setMobileNavOpen])

  if (isPending) {
    return (
      <div className="p-8">
        <LoadingState variant="cards" />
      </div>
    )
  }
  if (isError) {
    return (
      <div className="p-8">
        <ErrorState error={error} onRetry={() => void refetch()} />
      </div>
    )
  }
  if (!me) return <Navigate to="/login" replace state={{ from: location.pathname }} />

  const sections = visibleNavigation(me.permissions)
  const signOut = () => logout.mutate(undefined, { onSettled: () => navigate('/login', { replace: true }) })

  return (
    <div className="flex min-h-full">
      <a href="#main" className="sr-only focus:not-sr-only focus:absolute focus:top-2 focus:left-2 focus:z-50 focus:rounded focus:bg-white focus:p-2">
        Skip to content
      </a>
      <aside className="fixed inset-y-0 left-0 hidden w-60 lg:block">
        <Sidebar sections={sections} onSignOut={signOut} />
      </aside>

      <Dialog.Root open={mobileNavOpen} onOpenChange={setMobileNavOpen}>
        <Dialog.Portal>
          <Dialog.Overlay className="fixed inset-0 z-40 bg-slate-900/50 lg:hidden" />
          <Dialog.Content className="fixed inset-y-0 left-0 z-50 w-64 lg:hidden" aria-describedby={undefined}>
            <Dialog.Title className="sr-only">Navigation</Dialog.Title>
            <Sidebar sections={sections} onNavigate={() => setMobileNavOpen(false)} onSignOut={signOut} />
            <Dialog.Close className="absolute top-4 right-3 rounded p-1 text-white/80 hover:text-white" aria-label="Close navigation">
              <X className="h-5 w-5" />
            </Dialog.Close>
          </Dialog.Content>
        </Dialog.Portal>
      </Dialog.Root>

      <div className="flex min-w-0 flex-1 flex-col lg:pl-60">
        <Header me={me} onOpenNav={() => setMobileNavOpen(true)} onSignOut={signOut} />
        <main id="main" className="mx-auto w-full max-w-7xl flex-1 p-4 sm:p-6 lg:p-8">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
