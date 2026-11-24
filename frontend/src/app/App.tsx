import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query'
import { useState } from 'react'

function createQueryClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: 1, retryDelay: 300, staleTime: 30_000 } },
  })
}

type Health = { status: string }

async function fetchHealth(): Promise<Health> {
  const res = await fetch('/actuator/health')
  if (!res.ok) throw new Error(`Health check failed: ${res.status}`)
  return (await res.json()) as Health
}

/** Skeleton landing page: proves the SPA is served by the monolith and can reach the API. */
export function SystemStatus() {
  const { data, isPending, isError } = useQuery({ queryKey: ['health'], queryFn: fetchHealth })
  let label = 'Checking…'
  let tone = 'bg-slate-200 text-slate-700'
  if (isError) {
    label = 'Backend unreachable'
    tone = 'bg-red-100 text-red-700'
  } else if (!isPending) {
    label = `Backend ${data.status}`
    tone = data.status === 'UP' ? 'bg-green-100 text-green-700' : 'bg-amber-100 text-amber-700'
  }
  return (
    <span role="status" className={`rounded-full px-3 py-1 text-sm font-medium ${tone}`}>
      {label}
    </span>
  )
}

export function App() {
  const [queryClient] = useState(createQueryClient)
  return (
    <QueryClientProvider client={queryClient}>
      <main className="mx-auto flex min-h-full max-w-xl flex-col items-center justify-center gap-4 p-8 text-center">
        <h1 className="text-3xl font-semibold text-sidebar">Telecom Support</h1>
        <p className="text-muted">Customer support monolith — skeleton build.</p>
        <SystemStatus />
      </main>
    </QueryClientProvider>
  )
}
