import { beforeEach, describe, expect, it, vi, afterEach } from 'vitest'
import {
  THEME_STORAGE_KEY,
  currentTheme,
  preferredTheme,
  readStoredTheme,
  setTheme,
  toggleTheme,
} from './theme'

/**
 * Theme module owns the localStorage key + the data-theme attribute on <html>.
 * The head script applies it before paint, the runtime toggle flips it; both
 * rely on these helpers agreeing, which is what we pin here.
 */
describe('theme', () => {
  beforeEach(() => {
    localStorage.clear()
    document.documentElement.removeAttribute('data-theme')
  })

  it('defaults to dark when no attribute is set', () => {
    expect(currentTheme()).toBe('dark')
  })

  it('reads the applied data-theme attribute', () => {
    document.documentElement.dataset.theme = 'light'
    expect(currentTheme()).toBe('light')
  })

  it('ignores attribute values that are not a known theme', () => {
    document.documentElement.dataset.theme = 'nonsense'
    expect(currentTheme()).toBe('dark')
  })

  it('setTheme applies the attribute and persists it to localStorage', () => {
    setTheme('light')
    expect(document.documentElement.dataset.theme).toBe('light')
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('light')
  })

  it('toggleTheme flips between light and dark', () => {
    setTheme('dark')
    expect(toggleTheme()).toBe('light')
    expect(currentTheme()).toBe('light')
    expect(toggleTheme()).toBe('dark')
    expect(currentTheme()).toBe('dark')
  })

  it('readStoredTheme returns only valid stored values', () => {
    expect(readStoredTheme()).toBeNull()
    localStorage.setItem(THEME_STORAGE_KEY, 'light')
    expect(readStoredTheme()).toBe('light')
    localStorage.setItem(THEME_STORAGE_KEY, 'bogus')
    expect(readStoredTheme()).toBeNull()
  })

  describe('preferredTheme', () => {
    afterEach(() => {
      vi.unstubAllGlobals()
    })

    it('follows prefers-color-scheme when present', () => {
      vi.stubGlobal('matchMedia', (query: string) => ({
        matches: query.includes('light'),
        media: query,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
      }))
      expect(preferredTheme()).toBe('light')
    })

    it('falls back to dark when matchMedia is unavailable', () => {
      vi.stubGlobal('matchMedia', undefined)
      expect(preferredTheme()).toBe('dark')
    })
  })

  it('setTheme tolerates a disabled localStorage', () => {
    const getter = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('private mode')
    })
    expect(() => setTheme('light')).not.toThrow()
    expect(document.documentElement.dataset.theme).toBe('light')
    getter.mockRestore()
  })
})
