import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import { AuthProvider, SessionUser } from '../auth'
import { ApiError } from '../api'

const apiMock = vi.hoisted(() => ({ fn: vi.fn(), token: null as string | null }))
vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, api: apiMock.fn, getAccessToken: () => apiMock.token }
})

const user: SessionUser = { id: 1, email: 'u@t.dev', name: '阿达', role: 'USER' }

const profile = {
  id: 1,
  email: 'u@t.dev',
  name: '阿达',
  role: 'USER',
  walletCents: 88800,
  totalSpentCents: 18000,
  bookingCount: 3,
  ticketCount: 5,
  favouriteCount: 2,
  notificationCount: 4,
}

function renderProfile() {
  return render(
    <MemoryRouter initialEntries={['/profile']}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  apiMock.fn.mockReset()
  apiMock.token = 'tok'
})

describe('ProfilePage', () => {
  it('shows balance, spend and account stats', async () => {
    apiMock.fn.mockImplementation((_m: string, path: string) => {
      if (path === '/api/auth/me') return Promise.resolve(user)
      if (path === '/api/auth/profile') return Promise.resolve(profile)
      return Promise.resolve([])
    })
    renderProfile()
    expect(await screen.findByRole('heading', { name: '个人中心' })).toBeInTheDocument()
    // Wallet reads cents as €
    expect(screen.getByText('€888.00')).toBeInTheDocument()
    expect(screen.getByText('累计消费 €180.00')).toBeInTheDocument()
    // Stats
    expect(screen.getByText('3')).toBeInTheDocument()
    expect(screen.getByText('5')).toBeInTheDocument()
    expect(screen.getByText('2')).toBeInTheDocument()
    expect(screen.getByText('4')).toBeInTheDocument()
  })

  it('recharges the wallet and shows the new balance', async () => {
    apiMock.fn.mockImplementation((_m: string, path: string) => {
      if (path === '/api/auth/me') return Promise.resolve(user)
      if (path === '/api/auth/profile') return Promise.resolve(profile)
      if (path === '/api/auth/wallet/recharge') {
        return Promise.resolve({ ...profile, walletCents: profile.walletCents + 5000 })
      }
      return Promise.resolve([])
    })
    renderProfile()
    await screen.findByText('€888.00')

    await userEvent.click(screen.getByRole('button', { name: '€50' }))
    await userEvent.click(screen.getByRole('button', { name: '充值' }))
    await waitFor(() => expect(apiMock.fn).toHaveBeenCalledWith('POST', '/api/auth/wallet/recharge', {
      amountCents: 5000,
    }))
    expect(await screen.findByText('€938.00')).toBeInTheDocument()
    expect(apiMock.fn).toHaveBeenCalled()
  })

  it('shows an error note and keeps the balance when recharge fails', async () => {
    apiMock.fn.mockImplementation((_m: string, path: string) => {
      if (path === '/api/auth/me') return Promise.resolve(user)
      if (path === '/api/auth/profile') return Promise.resolve(profile)
      if (path === '/api/auth/wallet/recharge') return Promise.reject(new ApiError(400, 'Invalid amount'))
      return Promise.resolve([])
    })
    renderProfile()
    await screen.findByText('€888.00')

    await userEvent.click(screen.getByRole('button', { name: '充值' }))
    // The message appears both as the inline error note and the toast.
    expect((await screen.findAllByText('Invalid amount')).length).toBeGreaterThan(0)
    expect(screen.getByText('€888.00')).toBeInTheDocument()
  })

  it('navigates to profile from the top bar avatar', async () => {
    apiMock.fn.mockImplementation((_m: string, path: string) => {
      if (path === '/api/auth/me') return Promise.resolve(user)
      if (path === '/api/auth/profile') return Promise.resolve(profile)
      return Promise.resolve([])
    })
    render(
      <MemoryRouter initialEntries={['/']}>
        <AuthProvider>
          <App />
        </AuthProvider>
      </MemoryRouter>,
    )
    const links = await screen.findAllByRole('link', { name: '个人中心' })
    expect(links.length).toBeGreaterThan(0)
    await userEvent.click(links[0])
    expect(await screen.findByRole('heading', { name: '个人中心' })).toBeInTheDocument()
  })
})