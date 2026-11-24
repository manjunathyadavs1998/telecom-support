import { createBrowserRouter, Outlet, type RouteObject } from 'react-router'
import { AppShell } from '@/components/layout/AppShell'
import { LoginPage } from '@/features/authentication/pages/LoginPage'
import { RequirePermission } from '@/permissions/Can'
import { P, type Permission } from '@/permissions/permissions'
import { ComingSoon, ForbiddenPage, NotFoundPage, RoleHome } from './pages/SystemPages'

/** A route subtree that requires a permission (XC-02: direct navigation to a restricted route is denied). */
function guarded(permission: Permission, children: RouteObject[]): RouteObject {
  return {
    element: (
      <RequirePermission permission={permission}>
        <Outlet />
      </RequirePermission>
    ),
    children,
  }
}

export const routes: RouteObject[] = [
  { path: '/login', element: <LoginPage /> },
  {
    path: '/',
    element: <AppShell />,
    children: [
      { index: true, element: <RoleHome /> },
      { path: 'forbidden', element: <ForbiddenPage /> },
      {
        path: 'customer',
        children: [
          guarded(P.CUSTOMER_PROFILE_UPDATE, [
            { index: true, element: <ComingSoon title="Customer dashboard" phase="Phase 2 (Customer portal)" /> },
            { path: 'profile', element: <ComingSoon title="Profile" phase="Phase 2 (Customer portal)" /> },
          ]),
          guarded(P.PLAN_CHANGE, [{ path: 'plan', element: <ComingSoon title="My plan" phase="Phase 2 (Customer portal)" /> }]),
          guarded(P.PROMOTION_CLAIM, [{ path: 'promotions', element: <ComingSoon title="Promotions" phase="Phase 2 (Customer portal)" /> }]),
          guarded(P.CASE_CREATE, [{ path: 'requests/*', element: <ComingSoon title="Service requests" phase="Phase 2 (Customer portal)" /> }]),
          guarded(P.PAYMENT_INITIATE, [{ path: 'payments/*', element: <ComingSoon title="Payments" phase="Phase 2 (Customer portal)" /> }]),
        ],
      },
      {
        path: 'inbound',
        children: [guarded(P.CUSTOMER_LOOKUP, [{ index: true, element: <ComingSoon title="Customer search" phase="Phase 3 (Inbound)" /> }])],
      },
      {
        path: 'outbound',
        children: [
          guarded(P.CASE_READ_QUEUE, [{ index: true, element: <ComingSoon title="Case queue" phase="Phase 4 (Outbound)" /> }]),
          guarded(P.CASE_BOOKMARK, [{ path: 'bookmarks', element: <ComingSoon title="Bookmarks" phase="Phase 4 (Outbound)" /> }]),
        ],
      },
      {
        path: 'business',
        children: [
          guarded(P.ANALYTICS_READ, [{ index: true, element: <ComingSoon title="Analytics" phase="Phase 5 (Business)" /> }]),
          guarded(P.AUDIT_READ, [{ path: 'audit', element: <ComingSoon title="Audit & export" phase="Phase 5 (Business)" /> }]),
        ],
      },
    ],
  },
  { path: '*', element: <NotFoundPage /> },
]

export function createAppRouter() {
  return createBrowserRouter(routes)
}
