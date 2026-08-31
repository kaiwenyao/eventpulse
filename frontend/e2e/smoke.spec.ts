import { expect, test } from '@playwright/test'

/**
 * Browser smoke tests for the EventPulse SPA. These run against the Vite dev
 * server with NO backend, so they assert only on what the client owns: the
 * bundle boots, routing works, the discovery page renders its search chrome,
 * and the auth flow renders an accessible login form. Backend behaviour is
 * covered by the Testcontainers suite + compose smoke test.
 */

test.describe('SPA smoke', () => {
  test('boots and renders the discovery landing page', async ({ page }) => {
    await page.goto('/')
    // The SPA bootstrapped: the document title is set by index.html and the
    // discovery page always renders its search box independent of backend.
    await expect(page).toHaveTitle(/EventPulse/)
    await expect(page.getByPlaceholder('搜索活动…')).toBeVisible()
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
})
