import { ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { AuthProvider } from '../auth'

/**
 * Test harness: fresh react-query cache per render (leaky caches make tests
 * order-dependent), router on memory history, and the real AuthProvider
 * (module-level api mocking in the test files decides what the network edge
 * returns).
 */
export function withProviders(node: ReactNode, { route = '/login' }: { route?: string } = {}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return {
    queryClient,
    ui: (
      <QueryClientProvider client={queryClient}>
        <AuthProvider>
          <MemoryRouter initialEntries={[route]}>{node}</MemoryRouter>
        </AuthProvider>
      </QueryClientProvider>
    ),
  }
}