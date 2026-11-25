/**
 * One seed user per role combination (backend.md §3.1, R__01_identity.sql), logged in once by the setup project
 * (frontend.md §10 login budget). Specs reuse `storageStateFor(user)` read-only.
 */
export const SEED_USERS = {
  customer: '+919800000001',
  inbound: 'IN001',
  inboundApprover: 'IN002',
  outbound: 'OUT001',
  supervisor: 'SUP001',
  analyst: 'BUS001',
  auditor: 'AUD001',
} as const

export type SeedUserKey = keyof typeof SEED_USERS

export const SEED_USER_KEYS = Object.keys(SEED_USERS) as SeedUserKey[]

/** `e2e/.auth/<user>.json` (git-ignored), relative to the Playwright config. */
export function storageStateFor(user: SeedUserKey): string {
  return `e2e/.auth/${SEED_USERS[user]}.json`
}
