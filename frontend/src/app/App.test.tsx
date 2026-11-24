import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { App } from './App'

describe('App skeleton', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('shows backend status when health endpoint is UP', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response(JSON.stringify({ status: 'UP' }), { status: 200 })),
    )
    render(<App />)
    expect(await screen.findByText('Backend UP')).toBeInTheDocument()
  })

  it('shows an error state when backend is unreachable', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 503 })))
    render(<App />)
    expect(await screen.findByText('Backend unreachable', {}, { timeout: 5000 })).toBeInTheDocument()
  })
})
