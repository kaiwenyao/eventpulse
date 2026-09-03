import { render, screen, waitFor, cleanup } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthProvider, SessionUser } from '../auth'
import App from '../App'

const apiMock = vi.hoisted(() => ({ fn: vi.fn(), token: 'tok' as string | null }))
vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return {
    ...actual,
    api: apiMock.fn,
    getAccessToken: () => apiMock.token,
  }
})

vi.mock('../lib/sse', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../lib/sse')>()
  return {
    ...actual,
    streamUserEvents: vi.fn().mockResolvedValue(undefined),
    streamBookingEvents: vi.fn().mockResolvedValue(undefined),
  }
})

const user: SessionUser = { id: 1, email: 'u@t.dev', name: '阿达', role: 'USER' }

const cartItem = {
  id: 11,
  eventId: 1,
  eventTitle: 'Indie Rock Night',
  eventStatus: 'PUBLISHED',
  startsAt: '2026-10-01T19:00:00Z',
  quantity: 2,
  unitPriceCents: 1200,
  currentUnitPriceCents: 1200,
  lineTotalCents: 2400,
  selected: true,
  maxQuantityPerBooking: 10,
  remaining: 50,
  issues: [] as string[],
}

const cart = { items: [cartItem], selectedTotalCents: 2400, hasIssues: false }

function renderAt(route: string) {
  return render(
    <MemoryRouter initialEntries={[route]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  )
}

describe('CartPage', () => {
  beforeEach(() => {
    apiMock.fn.mockReset()
    apiMock.token = 'tok'
    sessionStorage.clear()
  })

  afterEach(cleanup)

  it('shows items with totals and supports removing them', async () => {
    apiMock.fn.mockImplementation((method: string, path: string) => {
      if (path === '/api/auth/me') return Promise.resolve(user)
      if (method === 'GET' && path === '/api/cart') return Promise.resolve(cart)
      if (method === 'DELETE' && path === '/api/cart/items/11') {
        return Promise.resolve({ items: [], selectedTotalCents: 0, hasIssues: false })
      }
      return Promise.resolve({})
    })
    renderAt('/cart')

    await screen.findByText('Indie Rock Night')
    // 行小计与勾选合计都渲染（同一金额出现两次）
    expect(screen.getAllByText('€24.00')).toHaveLength(2)
    expect(screen.getByRole('button', { name: '结算勾选项' })).toBeEnabled()

    await userEvent.click(screen.getByRole('button', { name: '移除' }))
    await waitFor(() => expect(apiMock.fn).toHaveBeenCalledWith('DELETE', '/api/cart/items/11'))
    await screen.findByText('购物车还是空的')
  })

  it('keeps the idempotency key on failure and reuses it on retry', async () => {
    apiMock.fn.mockImplementation((method: string, path: string) => {
      if (path === '/api/auth/me') return Promise.resolve(user)
      if (method === 'GET' && path === '/api/cart') return Promise.resolve(cart)
      if (method === 'POST' && path === '/api/cart/checkout') {
        return Promise.reject(new Error('network down'))
      }
      return Promise.resolve({})
    })
    renderAt('/cart')

    await screen.findByText('Indie Rock Night')
    await userEvent.click(screen.getByRole('button', { name: '结算勾选项' }))
    await waitFor(() =>
      expect(apiMock.fn).toHaveBeenCalledWith(
        'POST',
        '/api/cart/checkout',
        { items: [{ itemId: 11, quantity: 2 }] },
        { 'Idempotency-Key': expect.any(String) },
      ),
    )
    // 结算失败：幂等键保留，重试 / 重复点击复用同一个键
    const stored = sessionStorage.getItem('ep_checkout_key')
    expect(stored).toBeTruthy()

    apiMock.fn.mockImplementation((method: string, path: string) => {
      if (path === '/api/auth/me') return Promise.resolve(user)
      if (method === 'GET' && path === '/api/cart') return Promise.resolve(cart)
      if (method === 'POST' && path === '/api/cart/checkout') {
        return Promise.resolve({ bookings: [{ id: 1 }], checkoutId: 5, totalPaidCents: 2400 })
      }
      return Promise.resolve({})
    })
    await userEvent.click(screen.getByRole('button', { name: '结算勾选项' }))
    await screen.findByText('结算成功，共生成 1 张订单')
    const checkoutCall = apiMock.fn.mock.calls.find((call) => call[1] === '/api/cart/checkout')
    expect(checkoutCall?.[3]?.['Idempotency-Key']).toBe(stored)
    // 成功后键被清除：下一次结算是新的一笔交易
    expect(sessionStorage.getItem('ep_checkout_key')).toBeNull()
  })

  it('shows invalid reasons and asks for confirmation when prices changed', async () => {
    apiMock.fn.mockImplementation((method: string, path: string) => {
      if (path === '/api/auth/me') return Promise.resolve(user)
      if (method === 'GET' && path === '/api/cart') {
        return Promise.resolve({
          items: [{ ...cartItem, issues: ['PRICE_CHANGED', 'LOW_STOCK'] }],
          selectedTotalCents: 2400,
          hasIssues: true,
        })
      }
      return Promise.resolve({})
    })
    renderAt('/cart')

    await screen.findByText('价格已变化，需重新确认')
    expect(screen.getByText('余票不足所选数量')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '结算勾选项' }))
    // 有价格变化：先弹「按新价格」确认，而不是直接结算
    expect(await screen.findByText('活动价格已变化')).toBeInTheDocument()
    expect(apiMock.fn.mock.calls.filter((call) => call[1] === '/api/cart/checkout')).toHaveLength(0)
  })
})

describe('WalletLedgerPage', () => {
  beforeEach(() => {
    apiMock.fn.mockReset()
    apiMock.token = 'tok'
  })

  afterEach(cleanup)

  it('lists ledger entries with amounts, balance after and order links', async () => {
    apiMock.fn.mockImplementation((_method: string, path: string) => {
      if (path === '/api/auth/me') return Promise.resolve(user)
      if (path.startsWith('/api/wallet/ledger')) {
        return Promise.resolve({
          records: [
            {
              id: 1,
              bizType: 'RECHARGE',
              amountCents: 50000,
              balanceBeforeCents: 0,
              balanceAfterCents: 50000,
              seqNo: 1,
              createdAt: '2026-09-01T00:00:00Z',
            },
            {
              id: 2,
              bizType: 'BOOKING_PAYMENT',
              amountCents: -2400,
              balanceBeforeCents: 50000,
              balanceAfterCents: 47600,
              bookingId: 9,
              checkoutId: 3,
              seqNo: 2,
              createdAt: '2026-09-02T00:00:00Z',
            },
          ],
          total: 2,
        })
      }
      return Promise.resolve({})
    })
    renderAt('/wallet/ledger')

    // 「充值 / 下单扣款」也出现在筛选下拉里，必须等真实流水行渲染完成再断言。
    const amountText = (text: string) =>
      screen.findByText((_, element) => element?.tagName === 'STRONG' && element.textContent === text)
    expect(await amountText('+€500.00')).toBeInTheDocument()
    expect(await amountText('-€24.00')).toBeInTheDocument()
    expect(await screen.findByText('变动后余额 €476.00')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '订单 #9' }).getAttribute('href')).toBe('/bookings/9')
  })

  it('passes the type filter to the server and paginates', async () => {
    apiMock.fn.mockImplementation((_method: string, path: string) => {
      if (path === '/api/auth/me') return Promise.resolve(user)
      if (path.startsWith('/api/wallet/ledger')) return Promise.resolve({ records: [], total: 0 })
      return Promise.resolve({})
    })
    renderAt('/wallet/ledger')

    await screen.findByText('还没有流水')
    await userEvent.selectOptions(screen.getByLabelText('收支类型'), 'RECHARGE')
    await userEvent.click(screen.getByRole('button', { name: '筛选' }))
    await waitFor(() => {
      const call = apiMock.fn.mock.calls.find((entry) => entry[1].includes('type=RECHARGE'))
      expect(call).toBeTruthy()
      expect(call![1]).toContain('page=0')
    })
  })
})
