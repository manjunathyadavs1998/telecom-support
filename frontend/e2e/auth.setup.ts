import { test as setup } from '@playwright/test'
import { loginOk } from './helpers/login.ts'
import { SEED_USER_KEYS, SEED_USERS, storageStateFor } from './helpers/users.ts'

// frontend.md §10 login budget: each of the 7 seed users signs in exactly once per run (7 attempts); specs reuse the
// saved storageState read-only. Serial, no retries, so the budget is never exceeded.
setup.describe.configure({ mode: 'serial', retries: 0 })

for (const user of SEED_USER_KEYS) {
  setup(`sign in ${SEED_USERS[user]}`, async ({ page }) => {
    setup.setTimeout(90_000) // room for one Retry-After wait
    await loginOk(page, SEED_USERS[user])
    await page.context().storageState({ path: storageStateFor(user) })
  })
}
