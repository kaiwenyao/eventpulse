/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      // 默认打本机直跑的后端；对着 docker compose 栈开发时用
      // `VITE_API_TARGET=http://localhost:3000 npm run dev`（api 容器不对外暴露端口，
      // 只有 frontend 容器的 nginx 会把 /api 转发进去）。
      '/api': { target: process.env.VITE_API_TARGET ?? 'http://localhost:8080', changeOrigin: true },
    },
  },
  build: { outDir: 'dist' },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    css: false,
    // Keep unit tests scoped to src/**; Playwright specs live in e2e/** and
    // are run by `npm run e2e`, not vitest.
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    exclude: ['e2e/**', 'node_modules/**', 'dist/**'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html'],
      // Measure the complete application surface (routing, auth, API client
      // and every customer/operator page, component and UI primitive). Tests
      // may use mocked API responses, but production files may not be silently
      // removed from the gate.
      include: ['src/**/*.{ts,tsx}'],
      exclude: ['src/**/*.test.*', 'src/test/**', 'src/main.tsx'],
      thresholds: {
        statements: 80,
        lines: 80,
        functions: 80,
        branches: 70,
      },
    },
  },
})