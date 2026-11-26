import { queryOptions, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, apiClient } from '@/services/api/client'
import type { ContactVerification, ContactVerificationStart, Profile, ProfileUpdate } from '@/types/api'

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

/** E3 `POST /customers/me/contact-verifications` (CUS-02.2): sends a code; nothing changes until E4. */
export async function postContactVerification(body: ContactVerificationStart): Promise<ContactVerification> {
  return api.call(apiClient.POST('/api/v1/customers/me/contact-verifications', { body }))
}

/** E4 `POST /customers/me/contact-verifications/{id}/confirm` (CUS-02.2): applies the change and returns the new profile. */
export async function postContactConfirm({ verificationId, code }: { verificationId: number; code: string }): Promise<Profile> {
  return api.call(
    apiClient.POST('/api/v1/customers/me/contact-verifications/{verificationId}/confirm', {
      params: { path: { verificationId } },
      body: { code },
    }),
  )
}

/** Contact start; `gcTime: 0` so the new contact value leaves the MutationCache once settled and reset (NFR-SEC, §6). */
export function useStartContactVerification() {
  return useMutation({ mutationFn: postContactVerification, gcTime: 0 })
}

/**
 * Contact confirm; `gcTime: 0` so the code leaves the MutationCache once settled and reset (NFR-SEC). The 200 `Profile` goes
 * into the cache (its new `version` re-baselines the alias form) and the profile is invalidated (§3 Profile).
 */
export function useConfirmContactVerification() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: postContactConfirm,
    gcTime: 0,
    onSuccess: (saved) => {
      client.setQueryData(PROFILE_QUERY_KEY, saved)
      void client.invalidateQueries({ queryKey: PROFILE_QUERY_KEY, exact: true })
    },
  })
}
