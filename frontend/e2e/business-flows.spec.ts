import { expect, test, type Page, type Route } from '@playwright/test'

const user = { id: 'user-1', email: 'user@example.test', role: 'USER', displayName: 'Test User' }
const event = {
  id: 'event-1', title: '城市音乐节', description: '现场演出', category: 'music', status: 'PUBLISHED',
  startsAt: '2099-06-01T12:00:00Z', endsAt: '2099-06-01T15:00:00Z', ageRequirement: null,
  policyVersion: 1, policy: {}, venueName: '大剧院', city: '上海', organiserName: '主办方',
  tiers: [{ id: 'tier-1', name: '标准票', unitPriceMinor: 10000, currency: 'CNY',
    saleStartAt: '2000-01-01T00:00:00Z', saleEndAt: '2099-12-31T00:00:00Z', perUserLimit: 5,
    status: 'ACTIVE', capacity: 100, available: 42, sold: 0 }],
}
const pending = {
  id: 'booking-1', eventId: 'event-1', tierName: '标准票', quantity: 1, status: 'PAYMENT_PENDING',
  entitlementStatus: 'ACTIVE', refundState: 'NONE', totalMinor: 10000, currency: 'CNY',
  priceSnapshot: { totalMinor: 10000 }, policySnapshot: { policyVersion: 1 },
  expiresAt: '2099-06-01T12:00:00Z', activeIntent: null, refunds: [], tickets: [],
}

async function json(route: Route, body: unknown, status = 200) {
  await route.fulfill(status === 204
    ? { status }
    : { status, contentType: 'application/json', body: JSON.stringify(body) })
}

async function mockCommon(page: Page, authenticated = false) {
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    if (path.endsWith('/auth/refresh')) return json(route, authenticated ? { accessToken: 'access-token', user } : {}, authenticated ? 200 : 401)
    if (path === '/api/v1/events') return json(route, { items: [event], nextCursor: null })
    if (path === '/api/v1/recommendations') return json(route, { items: [] })
    if (path === '/api/v1/events/event-1') return json(route, event)
    if (path === '/api/v1/bookings/booking-1') return json(route, pending)
    return json(route, {})
  })
}

test.describe('business journeys', () => {
  test('registration, search and favourite use the authenticated API', async ({ page }) => {
    await mockCommon(page)
    await page.route('**/api/v1/auth/register', (route) => json(route, { accessToken: 'access-token', user }))
    await page.route('**/api/v1/me/saved-events/event-1', (route) => json(route, {}, 204))

    await page.goto('/login')
    await page.getByText('去注册').click()
    await page.locator('input[type="email"]').fill('new@example.test')
    await page.locator('input[type="password"]').fill('Register!234567890')
    await page.locator('button[type="submit"]').click()
    await expect(page.getByPlaceholder('搜索活动…')).toBeVisible()

    await page.getByPlaceholder('搜索活动…').fill('音乐')
    await expect(page.getByText('城市音乐节').first()).toBeVisible()
    await page.getByText('城市音乐节').first().click()
    await expect(page.getByRole('button', { name: '收藏活动' })).toBeVisible()
    await page.getByRole('button', { name: '收藏活动' }).click()
    await expect(page.getByRole('button', { name: '已收藏' })).toBeDisabled()
  })

  test('payment debits the wallet and confirms immediately', async ({ page }) => {
    await mockCommon(page, true)
    await page.route('**/api/v1/auth/me', (route) => json(route, {
      ...user, availableAmountMinor: 1000000, currency: 'CNY',
    }))
    let payCalls = 0
    await page.route('**/api/v1/bookings/booking-1/pay', async (route) => {
      payCalls += 1
      await new Promise((resolve) => setTimeout(resolve, 100))
      await json(route, { id: 'intent-1', state: 'SUCCEEDED', providerKey: 'pi-1' })
    })
    await page.goto('/checkout/booking-1')
    await expect(page.getByText(/钱包余额/)).toBeVisible()
    const payButton = page.getByRole('button', { name: '发起支付' })
    const paymentClick = payButton.click()
    await expect(page.getByRole('button', { name: '处理中…' })).toBeDisabled()
    await page.route('**/api/v1/bookings/booking-1', (route) => json(route, {
      ...pending, status: 'CONFIRMED',
    }))
    await paymentClick
    await expect(page.getByText(/出票成功/)).toBeVisible()
    expect(payCalls).toBe(1)

    await page.route('**/api/v1/bookings/booking-1', (route) => json(route, {
      ...pending, status: 'EXPIRED', expiresAt: '2000-01-01T00:00:00Z',
    }))
    await page.reload()
    await expect(page.getByText(/订单状态：EXPIRED/)).toBeVisible()
  })

  test('cancellation credits the wallet and is immediately cancelled', async ({ page }) => {
    await mockCommon(page, true)
    let cancelled = false
    const refunds = [{ id: 'refund-1', amountMinor: 10000, state: 'SUCCEEDED' }]
    await page.route('**/api/v1/bookings/booking-1', (route) => json(route, cancelled
      ? { ...pending, status: 'CANCELLED', refundState: 'REFUNDED', refunds }
      : { ...pending, status: 'CONFIRMED', refunds }))
    await page.route('**/api/v1/bookings/booking-1/cancel', (route) => {
      cancelled = true
      return json(route, { ...pending, status: 'CANCELLED', refundState: 'REFUNDED', refunds })
    })
    await page.goto('/bookings/booking-1')
    await expect(page.getByText('退款记录')).toBeVisible()
    page.once('dialog', (dialog) => dialog.accept())
    await page.getByRole('button', { name: '取消订单' }).click()
    await expect(page.getByText('已取消')).toBeVisible()
  })

  test('organiser can redeem a ticket', async ({ page }) => {
    await mockCommon(page, true)
    await page.route('**/api/v1/organiser/tickets/redeem', (route) => json(route, {
      result: 'OK', ticketId: 'ticket-1', bookingId: 'booking-1', eventId: 'event-1',
      eventTitle: '城市音乐节', sequence: 1, usedAt: '2099-06-01T12:00:00Z',
    }))
    await page.goto('/redeem')
    await page.locator('input').fill('raw-ticket-token')
    await page.getByRole('button', { name: '核销' }).click()
    await expect(page.getByText('核销成功')).toBeVisible()
  })
})
