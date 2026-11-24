import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, expect } from 'vitest'
import * as axeMatchers from 'vitest-axe/matchers'
import type { AxeMatchers } from 'vitest-axe/matchers'

expect.extend(axeMatchers)

// vitest-axe ships a Vi-namespace augmentation; vitest 5's Assertion and AsymmetricMatchersContaining both extend Matchers.
declare module 'vitest' {
  interface Matchers<R, T> extends AxeMatchers {}
}

afterEach(() => {
  cleanup()
})
