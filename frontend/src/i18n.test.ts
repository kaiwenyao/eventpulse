import { afterEach, describe, expect, it } from 'vitest'
import { changeLocale, currentLocale, htmlLang, LOCALE_STORAGE_KEY } from './i18n'

describe('locale persistence', () => {
  afterEach(async () => {
    await changeLocale('zh')
    localStorage.removeItem(LOCALE_STORAGE_KEY)
  })

  it('writes localStorage and html lang when switching', async () => {
    await changeLocale('en')
    expect(currentLocale()).toBe('en')
    expect(localStorage.getItem(LOCALE_STORAGE_KEY)).toBe('en')
    expect(document.documentElement.lang).toBe(htmlLang('en'))
    expect(document.title).toContain('Event booking')

    await changeLocale('zh')
    expect(currentLocale()).toBe('zh')
    expect(document.documentElement.lang).toBe(htmlLang('zh'))
    expect(document.title).toContain('活动预订')
  })
})
