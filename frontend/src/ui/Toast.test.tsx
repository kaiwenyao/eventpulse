import { useEffect } from 'react'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ToastOptions, ToastProvider, useToast } from './Toast'

/** 一个只暴露 notify 的探针，让测试可以从外部驱动 provider。 */
type Notify = (input: string | ToastOptions, tone?: 'success' | 'error' | 'info') => void

const probe: { notify: Notify } = { notify: () => {} }
const fire: Notify = (input, tone) => probe.notify(input, tone)

function Probe() {
  const { notify } = useToast()
  // 渲染期间赋值是副作用（eslint react-hooks 会拦），所以放进 effect。
  useEffect(() => {
    probe.notify = notify
  }, [notify])
  return null
}

function renderToasts() {
  return render(
    <MemoryRouter>
      <ToastProvider>
        <Probe />
      </ToastProvider>
    </MemoryRouter>,
  )
}

describe('ToastProvider', () => {
  beforeEach(() => vi.useFakeTimers({ shouldAdvanceTime: true }))
  afterEach(() => vi.useRealTimers())

  it('auto-dismisses a success toast after its shorter TTL', async () => {
    renderToasts()
    act(() => fire('已保存', 'success'))
    expect(screen.getByText('已保存')).toBeInTheDocument()

    act(() => void vi.advanceTimersByTime(4300))

    await waitFor(() => expect(screen.queryByText('已保存')).not.toBeInTheDocument())
  })

  it('keeps an error on screen past the success TTL', async () => {
    renderToasts()
    act(() => fire('出错了', 'error'))

    act(() => void vi.advanceTimersByTime(4300))
    expect(screen.getByText('出错了')).toBeInTheDocument()

    act(() => void vi.advanceTimersByTime(4000))
    await waitFor(() => expect(screen.queryByText('出错了')).not.toBeInTheDocument())
  })

  it('never auto-dismisses an error that carries an action', () => {
    renderToasts()
    act(() => fire({ message: '余额不足', tone: 'error', action: { label: '去充值', to: '/profile' } }))

    act(() => void vi.advanceTimersByTime(60_000))

    expect(screen.getByText('余额不足')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '去充值' }).getAttribute('href')).toBe('/profile')
  })

  it('routes errors through an assertive region and everything else through a polite one', () => {
    renderToasts()
    act(() => fire('保存成功', 'success'))
    act(() => fire('保存失败', 'error'))

    expect(screen.getByRole('status')).toHaveTextContent('保存成功')
    expect(screen.getByRole('alert')).toHaveTextContent('保存失败')
  })

  it('collapses a repeated message into a counter instead of stacking duplicates', () => {
    renderToasts()
    act(() => fire('连接失败', 'error'))
    act(() => fire('连接失败', 'error'))
    act(() => fire('连接失败', 'error'))

    expect(screen.getAllByText('连接失败')).toHaveLength(1)
    expect(screen.getByText('×3')).toBeInTheDocument()
  })

  it('shows at most three toasts, dropping the oldest', () => {
    renderToasts()
    act(() => fire('第一条', 'info'))
    act(() => fire('第二条', 'info'))
    act(() => fire('第三条', 'info'))
    act(() => fire('第四条', 'info'))

    expect(screen.queryByText('第一条')).not.toBeInTheDocument()
    expect(screen.getByText('第四条')).toBeInTheDocument()
  })

  it('pauses the dismiss timer while the pointer rests on the toast', async () => {
    renderToasts()
    act(() => fire('悬停中', 'success'))

    await userEvent.hover(screen.getByText('悬停中'))
    act(() => void vi.advanceTimersByTime(10_000))
    expect(screen.getByText('悬停中')).toBeInTheDocument()

    await userEvent.unhover(screen.getByText('悬停中'))
    act(() => void vi.advanceTimersByTime(4300))
    await waitFor(() => expect(screen.queryByText('悬停中')).not.toBeInTheDocument())
  })

  it('dismisses on the close button', async () => {
    renderToasts()
    act(() => fire('手动关闭', 'info'))

    await userEvent.click(screen.getByRole('button', { name: '关闭提示' }))

    expect(screen.queryByText('手动关闭')).not.toBeInTheDocument()
  })
})
