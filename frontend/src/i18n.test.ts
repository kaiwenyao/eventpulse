import { afterEach, describe, expect, it, vi } from 'vitest'
import { changeLocale, currentLocale, detectBrowserLocale, htmlLang, LOCALE_STORAGE_KEY } from './i18n'

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

describe('browser language detection', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it.each(['zh-CN', 'zh-TW', 'ZH'])('uses Chinese for a %s browser', (language) => {
    vi.stubGlobal('navigator', { language })
    expect(detectBrowserLocale()).toBe('zh')
  })

  // 默认英文：只有明确的中文浏览器才给中文，其余一律英文。
  it.each(['en-US', 'de-DE', 'fr', ''])('defaults to English for a %s browser', (language) => {
    vi.stubGlobal('navigator', { language })
    expect(detectBrowserLocale()).toBe('en')
  })
})
