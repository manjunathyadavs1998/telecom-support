import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, beforeEach, expect, vi } from 'vitest'
import * as axeMatchers from 'vitest-axe/matchers'
import type { AxeMatchers } from 'vitest-axe/matchers'

expect.extend(axeMatchers)

// vitest-axe ships a Vi-namespace augmentation; vitest 5's Assertion and AsymmetricMatchersContaining both extend Matchers.
declare module 'vitest' {
  interface Matchers<R, T> extends AxeMatchers {}
}

// NFR-OBS / XC-03: an unexpected console.error or console.warn (React act(), key, DOM-nesting, a11y warnings...) is a defect.
// A test that expects one installs its own spy on `console`, which replaces this guard for that test.
let unexpected: string[] = []
beforeEach(() => {
  unexpected = []
  for (const level of ['error', 'warn'] as const) {
    vi.spyOn(console, level).mockImplementation((...args: unknown[]) => {
      unexpected.push(`console.${level}: ${args.map(String).join(' ').slice(0, 500)}`)
    })
  }
})

afterEach(() => {
  cleanup()
  const seen = unexpected
  vi.restoreAllMocks()
  expect(seen, 'unexpected console output during the test').toEqual([])
})
