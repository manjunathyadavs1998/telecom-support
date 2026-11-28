import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useCallback, useRef } from 'react'
import { useNavigate } from 'react-router'
import { LOGIN_PATH } from '@/app/navigation'
import { OWN_FORBIDDEN_HANDLING, showErrorToast } from '@/app/queryClient'
import { api, apiClient } from '@/services/api/client'
import { bootstrapCsrf } from '@/services/api/csrf'
import { ApiError } from '@/services/api/errors'
import { useInboundSessionStore } from '@/stores/inboundSessionStore'
import { useUiStore } from '@/stores/uiStore'
import { applySignedOut } from './meQuery'

export const SIGNED_OUT_URL = `${LOGIN_PATH}?reason=signed-out`
export const LOGOUT_FAILED_MESSAGE = "We couldn't sign you out. Please try again."

/**
 * `POST /auth/logout` (frontend.md §7 Logout): a 403 (stale CSRF token) re-bootstraps the cookie and retries once. Resolves
 * on 204 and on 401 (the session is already gone); any other outcome throws `ApiError` (network failure: status 0).
 * The API client's 401 handler ignores this request, so a 401 here never adds an "expired" redirect.
 */
export async function postLogout(): Promise<void> {
  const send = () => apiClient.POST('/api/v1/auth/logout')
  let result = await send()
  if (result.response.status === 403) {
    await bootstrapCsrf()
    result = await send()
  }
  if (result.response.status === 401) return
  await api.call(Promise.resolve(result))
}

/**
 * The one logout flow, shared by the Sidebar "Logout", the UserMenu "Sign out" and `NoAccessPage` (AppShell calls it once and
 * passes `logout` down). Signed out (204/401): clear the cache, tell the other tabs, close the drawer and go to
 * `/login?reason=signed-out`. 5xx/network (or a 403 after the retry): an error toast with the correlation id; still signed
 * in, cache kept. A second call while one is pending joins the in-flight request.
 */
export function useLogout() {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const { mutateAsync, isPending } = useMutation({ mutationFn: postLogout, meta: OWN_FORBIDDEN_HANDLING })
  const inFlight = useRef<Promise<void> | null>(null)

  const logout = useCallback((): Promise<void> => {
    inFlight.current ??= mutateAsync()
      .then(
        () => {
          applySignedOut(queryClient)
          // 03-inbound/frontend.md §5: the memory-only lookup tokens go with the cache.
          useInboundSessionStore.getState().clear()
          useUiStore.getState().setMobileNavOpen(false)
          // Cleared first, so the login page never sees the old user. The router is on /login before the shell re-renders
          // on the empty cache, so the shell adds no "/login?returnTo=" redirect of its own: exactly one redirect.
          void navigate(SIGNED_OUT_URL)
        },
        (error: unknown) => {
          showErrorToast(LOGOUT_FAILED_MESSAGE, error instanceof ApiError ? error.correlationId : null)
        },
      )
      .finally(() => {
        inFlight.current = null
      })
    return inFlight.current
  }, [mutateAsync, navigate, queryClient])

  return { logout, isPending }
}
