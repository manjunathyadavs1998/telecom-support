import { queryOptions, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, apiClient } from '@/services/api/client'
import type { Profile, ProfileUpdate } from '@/types/api'

/** The own profile (frontend.md §5): one cache entry, also the source of the alias form's `version` at submit (§3 Profile). */
export const PROFILE_QUERY_KEY = ['customer', 'profile'] as const

/** E1 `GET /customers/me/profile` (CUS-01.1, CUS-02.3). */
export async function fetchProfile(): Promise<Profile> {
  return api.call(apiClient.GET('/api/v1/customers/me/profile'))
}

export const profileQuery = queryOptions({ queryKey: PROFILE_QUERY_KEY, queryFn: fetchProfile })

export function useProfileQuery() {
  return useQuery(profileQuery)
}

/** E2 `PATCH /customers/me/profile` (CUS-02.1/02.4): alias only; `version` from the current cache (§3 Profile). */
export async function patchProfile(body: ProfileUpdate): Promise<Profile> {
  return api.call(apiClient.PATCH('/api/v1/customers/me/profile', { body }))
}

/** Alias save: never optimistic (XC-07); the 200 body replaces the cached profile, so its new `version` is the next baseline. */
export function useUpdateProfileMutation() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: patchProfile,
    onSuccess: (saved) => client.setQueryData(PROFILE_QUERY_KEY, saved),
  })
}
