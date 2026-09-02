/** Theme handling — shared between the no-flash bootstrap script in index.html
 *  and the runtime toggle in the top bar.
 *
 *  The bootstrap script runs before React and reads the same key so there is no
 *  flash of the wrong scheme; this module owns the canonical key + the helpers
 *  the toggle needs (read the current value, flip and persist it). */

export const THEME_STORAGE_KEY = 'theme'
export type Theme = 'light' | 'dark'

/** The theme currently applied to <html data-theme>. Defaults to light when the
 *  attribute is absent (e.g. jsdom in tests, which never runs the head script) —
 *  paper is the design system's ground, ink is the opt-in inversion. */
export function currentTheme(): Theme {
  const t = document.documentElement.dataset.theme
  return t === 'light' || t === 'dark' ? t : 'light'
}

function systemPrefersDark(): boolean {
  return typeof matchMedia !== 'undefined' && matchMedia('(prefers-color-scheme: dark)').matches
}

/** The theme to land on when there is no stored choice — the OS preference, or
 *  light if it can't be determined. Matches the bootstrap script's fallback. */
export function preferredTheme(): Theme {
  return systemPrefersDark() ? 'dark' : 'light'
}

export function readStoredTheme(): Theme | null {
  const t = localStorage.getItem(THEME_STORAGE_KEY)
  return t === 'light' || t === 'dark' ? t : null
}

/** Apply + persist a theme. Writes the data-theme attribute React reads and the
 *  localStorage value the bootstrap script reads on the next load. */
export function setTheme(theme: Theme) {
  document.documentElement.dataset.theme = theme
  try {
    localStorage.setItem(THEME_STORAGE_KEY, theme)
  } catch {
    /* private mode / storage disabled — attribute alone is fine for this session */
  }
}

/** Toggle between the two themes. */
export function toggleTheme(): Theme {
  const next = currentTheme() === 'dark' ? 'light' : 'dark'
  setTheme(next)
  return next
}
