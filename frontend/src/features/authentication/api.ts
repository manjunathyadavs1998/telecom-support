import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { Schema } from '@/services/api'
import { ApiError, http } from '@/services/http'

export type Me = Schema<'MeResponse'>
export type LoginRequest = Schema<'LoginRequest'>

export const authKeys = { me: ['auth', 'me'] as const }

/** The signed-in user (server state). `null` means "not signed in". */
export function useMe() {
  return useQuery({
    queryKey: authKeys.me,
    queryFn: async (): Promise<Me | null> => {
      try {
        return await http.get<Me>('/api/v1/auth/me')
      } catch (e) {
        if (e instanceof ApiError && e.status === 401) return null
        throw e
      }
    },
    staleTime: 5 * 60_000,
    retry: false,
  })
}

export function useLogin() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: LoginRequest) => http.post<Me>('/api/v1/auth/login', body),
    onSuccess: (me) => {
      qc.clear()
      qc.setQueryData(authKeys.me, me)
    },
  })
}

export function useLogout() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: () => http.post<void>('/api/v1/auth/logout'),
    onSettled: () => {
      qc.clear()
      qc.setQueryData(authKeys.me, null)
    },
  })
}

/** Landing route for a user, based on the most specific workspace they can use. */
export function homePathFor(me: Me): string {
  const has = (p: string) => me.permissions.includes(p)
  if (me.userType === 'CUSTOMER') return '/customer'
  if (has('customer.lookup')) return '/inbound'
  if (has('case.read.queue')) return '/outbound'
  if (has('analytics.read')) return '/business'
  return '/forbidden'
}
