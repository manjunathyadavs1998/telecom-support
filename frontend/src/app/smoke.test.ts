import { describe, expect, it } from 'vitest'
import { axe } from 'vitest-axe'

describe('test setup smoke', () => {
  it('runs in jsdom with the jest-dom and axe matchers registered', async () => {
    document.body.innerHTML = '<main><h1>Telecom Support</h1></main>'

    expect(document.querySelector('h1')).toHaveTextContent('Telecom Support')
    expect(await axe(document.body)).toHaveNoViolations()
  })
})
