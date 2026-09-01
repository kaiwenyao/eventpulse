import { expect, test } from '@playwright/test'

/**
 * Browser smoke tests for the EventPulse SPA. They run against the Vite dev
 * server with NO real backend — every API call is routed to a fixture — so
 * they assert only on what the client owns: the bundle boots, routing works,
 * and the two journeys that matter most in a browser (an organiser publishing
 * an event, and cancelling a live one) survive real DOM, real CSS and real
 * navigation. Backend behaviour is covered by the Testcontainers suite and the
 * compose smoke test.
 */

const ORGANISER = { id: 2, email: 'organiser@eventpulse.dev', name: '主办方', role: 'ORGANISER' }

function demoEvent(overrides: Record<string, unknown> = {}) {
  return {
    id: 1,
    title: '摇滚夜',
    summary: '六组乐队接力开唱。',
    description: '一场演出',
    category: 'music',
    city: '上海',
    venueName: '声空间 LiveHouse',
    startsAt: '2027-09-10T12:00:00Z',
    priceCents: 18000,
    capacity: 100,
    sold: 40,
    remaining: 60,
    status: 'PUBLISHED',
    version: 3,
    ...overrides,
  }
}

/**
 * Serves the whole API surface these smoke tests touch, and records every
 * mutating request so a test can assert on the payload the SPA actually sent.
 */
async function mockApi(
  page: import('@playwright/test').Page,
  requests: { url: string; body: unknown }[] = [],
  eventOverrides: Record<string, unknown> = {},
) {
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (request.method() !== 'GET') {
      requests.push({ url: path, body: request.postDataJSON() })
    }
    const json = (data: unknown) => route.fulfill({ json: { code: 1, msg: 'ok', data } })

    if (path === '/api/auth/me') return json(ORGANISER)
    if (path === '/api/organiser/dashboard') {
      return json({ eventCount: 1, publishedCount: 1, sold: 40, capacity: 100, sellThrough: 40, lowStock: [], outboxPending: 0 })
    }
    if (/^\/api\/organiser\/events\/\d+$/.test(path)) return json(demoEvent(eventOverrides))
    if (path.startsWith('/api/organiser/events')) return json({ records: [demoEvent(eventOverrides)], total: 1 })
    if (/^\/api\/events\/\d+$/.test(path)) return json(demoEvent(eventOverrides))
    if (path === '/api/events') return json([demoEvent(eventOverrides)])
    return json([])
  })
}

test.describe('SPA smoke', () => {
  test('boots and renders the discovery landing page', async ({ page }) => {
    await page.goto('/')
    // The SPA bootstrapped: the document title is set by index.html and the
    // discovery page always renders its search box independent of backend.
    await expect(page).toHaveTitle(/EventPulse/)
    await expect(page.getByPlaceholder('搜索活动…')).toBeVisible()
    await expect(page.getByRole('heading', { name: '发现今晚的城市脉搏' })).toBeVisible()
  })

  test('routes to /login and renders the auth form', async ({ page }) => {
    await page.goto('/login')
    await expect(page.getByRole('heading', { name: '登录' })).toBeVisible()
    await expect(page.locator('input[type="email"]')).toBeVisible()
    await expect(page.locator('input[type="password"]')).toBeVisible()
    await expect(page.getByRole('button', { name: '登录' })).toBeVisible()
  })

  test('login form toggles to the registration form', async ({ page }) => {
    await page.goto('/login')
    await page.getByText('去注册').click()
    await expect(page.getByRole('heading', { name: /注册/ })).toBeVisible()
    await expect(page.getByRole('button', { name: '注册' })).toBeVisible()
  })

  test('mocked audience discovery and booking CTA', async ({ page }) => {
    await mockApi(page)
    await page.goto('/')
    await expect(page.getByText('摇滚夜')).toBeVisible()
    await page.getByText('摇滚夜').click()
    await expect(page.getByRole('heading', { name: '摇滚夜' })).toBeVisible()
    await expect(page.getByRole('link', { name: '登录后预订' })).toBeVisible()
  })

  test('mocked organiser publishes an event from the console', async ({ page }) => {
    const requests: { url: string; body: unknown }[] = []
    await mockApi(page, requests)
    await page.addInitScript(() => sessionStorage.setItem('ep_token', 'demo'))

    await page.goto('/organiser')
    await expect(page.getByRole('heading', { name: '主办方工作台' })).toBeVisible()

    await page.getByRole('link', { name: '新建活动' }).first().click()
    await expect(page.getByRole('heading', { name: '新建活动' })).toBeVisible()

    // The live preview mirrors the form as it is typed.
    await page.getByLabel('标题').fill('城市脉搏 · 秋季场')
    await expect(page.locator('.form-preview .ticket-title')).toHaveText('城市脉搏 · 秋季场')

    // Validation blocks a submit with a bad capacity and says which field.
    await page.getByLabel('容量').fill('0')
    await page.getByRole('button', { name: '发布活动' }).click()
    await expect(page.getByText('容量：容量必须大于零')).toBeVisible()
    expect(requests.filter((r) => r.url.endsWith('/api/organiser/events'))).toHaveLength(0)

    // Fixed up, the publish posts the scheduled start time and lands on the list.
    await page.getByLabel('容量').fill('120')
    await page.getByLabel('开始时间').fill('2027-05-20T19:30')
    await page.getByLabel('结束时间').fill('2027-05-20T22:30')
    await page.getByRole('button', { name: '发布活动' }).click()

    await expect(page.getByRole('heading', { name: '活动管理' })).toBeVisible()
    const created = requests.find((r) => r.url.endsWith('/api/organiser/events'))!
    expect(created.body).toMatchObject({
      title: '城市脉搏 · 秋季场',
      capacity: 120,
      publish: true,
      startsAt: new Date('2027-05-20T19:30').toISOString(),
    })
  })

  test('mocked organiser cancels a live event through the confirm dialog', async ({ page }) => {
    const requests: { url: string; body: unknown }[] = []
    await mockApi(page, requests, { status: 'PUBLISHED' })
    await page.addInitScript(() => sessionStorage.setItem('ep_token', 'demo'))

    await page.goto('/organiser/events/1')
    await page.getByRole('button', { name: '取消活动' }).click()

    const dialog = page.getByRole('dialog')
    await expect(dialog.getByRole('button', { name: '确认取消活动' })).toBeDisabled()
    await dialog.getByLabel('取消原因').fill('场地检修')
    await dialog.getByRole('button', { name: '确认取消活动' }).click()

    await expect(page.getByRole('heading', { name: '活动管理' })).toBeVisible()
    const cancelled = requests.find((r) => r.url.includes('/cancel'))!
    expect(cancelled.body).toEqual({ reason: '场地检修' })
  })
})
