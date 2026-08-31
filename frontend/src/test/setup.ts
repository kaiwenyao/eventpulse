import '@testing-library/jest-dom/vitest'

// jsdom lacks the crypto.getRandomValues backends some lib paths expect — the
// browser crypto is available in Node >= 20, nothing to polyfill. Silence the
// React Router future-flag noise to keep failure output readable.
const originalError = console.error
console.error = (...args: unknown[]) => {
  if (typeof args[0] === 'string' && args[0].includes('React Router will begin using state')) {
    return
  }
  originalError(...args)
}