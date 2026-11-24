import { useMutation } from '@tanstack/react-query'
import { OWN_FORBIDDEN_HANDLING } from '@/app/queryClient'
import { api, apiClient } from '@/services/api/client'
import { bootstrapCsrf } from '@/services/api/csrf'
import type { Me } from '@/types/api'
import type { LoginValues } from './loginSchema'

/**
 * `POST /auth/login` (frontend.md §3 Login, §6): a 403 (stale CSRF token) re-bootstraps the cookie and retries once.
 * Any non-2xx throws `ApiError`, which is built from the response only, never from the submitted values (NFR-SEC).
 */
export async function postLogin(values: LoginValues): Promise<Me> {
  const send = () => apiClient.POST('/api/v1/auth/login', { body: values })
  let result = await send()
  if (result.response.status === 403) {
    await bootstrapCsrf()
    result = await send()
  }
  return api.call(Promise.resolve(result))
}

/**
 * The login mutation. `gcTime: 0` plus the form's `reset()` after every response drop the mutation (and so its
 * `variables`, which hold the password) from the MutationCache as soon as it settles. Its 403 is a form error, not a toast.
 */
export function useLoginMutation() {
  return useMutation({ mutationFn: postLogin, gcTime: 0, meta: OWN_FORBIDDEN_HANDLING })
}
