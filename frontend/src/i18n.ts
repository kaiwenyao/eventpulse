import i18n from 'i18next'
import { initReactI18next } from 'react-i18next'
import en from './locales/en.json'
import zh from './locales/zh.json'

export const LOCALE_STORAGE_KEY = 'locale'
export type AppLocale = 'zh' | 'en'

const TITLES: Record<AppLocale, string> = {
  zh: 'EventPulse · 活动预订',
  en: 'EventPulse · Event booking',
}

export function isAppLocale(value: string | null | undefined): value is AppLocale {
  return value === 'zh' || value === 'en'
}

export function readStoredLocale(): AppLocale | null {
  try {
    const stored = localStorage.getItem(LOCALE_STORAGE_KEY)
    return isAppLocale(stored) ? stored : null
  } catch {
    return null
  }
}

export function detectBrowserLocale(): AppLocale {
  if (typeof navigator === 'undefined') return 'zh'
  return navigator.language.toLowerCase().startsWith('en') ? 'en' : 'zh'
}

export function htmlLang(locale: AppLocale): string {
  return locale === 'en' ? 'en' : 'zh-CN'
}

export function applyLocale(locale: AppLocale) {
  document.documentElement.lang = htmlLang(locale)
  document.title = TITLES[locale]
  try {
    localStorage.setItem(LOCALE_STORAGE_KEY, locale)
  } catch {
    /* private mode / storage disabled */
  }
}

export function currentLocale(): AppLocale {
  return i18n.language === 'en' ? 'en' : 'zh'
}

export async function changeLocale(locale: AppLocale) {
  await i18n.changeLanguage(locale)
  applyLocale(locale)
}

const initial = readStoredLocale() ?? (import.meta.env.VITEST ? 'zh' : detectBrowserLocale())

void i18n.use(initReactI18next).init({
  resources: {
    zh: { translation: zh },
    en: { translation: en },
  },
  lng: initial,
  fallbackLng: 'zh',
  interpolation: { escapeValue: false },
})

if (typeof document !== 'undefined') {
  applyLocale(initial)
}

export default i18n
