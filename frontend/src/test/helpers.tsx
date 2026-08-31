import { ReactNode } from 'react'
import { MemoryRouter } from 'react-router-dom'
import { AuthProvider } from '../auth'

export function withProviders(node: ReactNode, { route = '/login' }: { route?: string } = {}) {
  return {
    ui: (
      <AuthProvider>
        <MemoryRouter initialEntries={[route]}>{node}</MemoryRouter>
      </AuthProvider>
    ),
  }
}