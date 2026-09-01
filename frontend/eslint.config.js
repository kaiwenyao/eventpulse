import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'

export default tseslint.config(
  // Generated output (coverage reports, Playwright artifacts) ships vendored
  // JS that is not ours to lint.
  {
    ignores: [
      'dist',
      'node_modules',
      'coverage',
      'test-results',
      'playwright-report',
      'e2e',
      'playwright.config.ts',
    ],
  },
  {
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    files: ['**/*.{ts,tsx}'],
    languageOptions: {
      ecmaVersion: 2023,
      globals: globals.browser,
    },
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      // react-hooks v6 flags *any* setState inside an effect, but our
      // mount-time bootstrap (auth refresh().finally(setReady)) and QR/error
      // resets are legitimate one-shot effects, not cascading state writes.
      // Demote to a warning so it's visible without blocking the gate.
      'react-hooks/set-state-in-effect': 'warn',
      'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],
      // Allow the conventional `_`-prefixed args/vars that mark intentional
      // non-use (e.g. test fetch mocks that ignore their url/init).
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_', caughtErrorsIgnorePattern: '^_' },
      ],
      // Test files drive queries with getByText variants that TS narrowing
      // flags as possibly-undefined; the tests null-check at usage sites.
      '@typescript-eslint/no-non-null-assertion': 'off',
      'no-empty': ['error', { allowEmptyCatch: true }],
    },
  },
)