import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AiCopyAssistant } from './AiCopyAssistant'
import { ApiError } from '../api'
import { createInitialForm } from './eventForm'

const apiMock = vi.hoisted(() => ({ fn: vi.fn() }))
vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, api: apiMock.fn }
})

beforeEach(() => {
  apiMock.fn.mockReset()
})

describe('AiCopyAssistant', () => {
  const suggestion = {
    requestId: 'r1',
    suggestion: {
      title: '周末爵士夜：在城市里听见即兴',
      summary: '三支爵士乐队带来一晚现场演出。',
      description: '三支乐队轮番登台……',
      attendanceNotes: '建议提前 30 分钟入场。',
      warnings: ['缺少票价信息'],
    },
    warnings: [],
  }

  it('generates suggestions and applies selected fields to the form', async () => {
    const onApply = vi.fn()
    apiMock.fn.mockResolvedValueOnce(suggestion)
    render(<AiCopyAssistant form={createInitialForm()} eventId="12" onApply={onApply} />)

    await userEvent.click(screen.getByRole('button', { name: 'AI 完善文案' }))
    await userEvent.click(await screen.findByRole('button', { name: '生成建议' }))

    await waitFor(() =>
      expect(apiMock.fn).toHaveBeenCalledWith('POST', '/api/ai/organiser/improve-event', {
        eventId: 12,
        title: expect.any(String),
        summary: '',
        description: expect.any(String),
        category: 'music',
        city: 'Berlin',
        venueName: '',
        startsAt: expect.any(String),
        tone: '',
        refresh: false,
      }),
    )
    expect(screen.getByText('周末爵士夜：在城市里听见即兴')).toBeInTheDocument()
    expect(screen.getByText('缺失信息 / 需要确认')).toBeInTheDocument()
    expect(screen.getByText('缺少票价信息')).toBeInTheDocument()

    // 取消标题勾选后，只应用其余字段。
    await userEvent.click(screen.getByRole('checkbox', { name: /建议标题/ }))
    await userEvent.click(screen.getByRole('button', { name: '应用所选字段' }))
    expect(onApply).toHaveBeenCalledTimes(1)
    const patch = onApply.mock.calls[0][0]
    expect(patch.title).toBeUndefined()
    expect(patch.summary).toBe('三支爵士乐队带来一晚现场演出。')
    expect(patch.attendanceNotes).toBe('建议提前 30 分钟入场。')
  })

  it('shows failure with retry, and nothing is applied automatically', async () => {
    const onApply = vi.fn()
    apiMock.fn
      .mockRejectedValueOnce(new ApiError(503, 'AI 文案助手暂时不可用'))
      .mockResolvedValueOnce(suggestion)
    render(<AiCopyAssistant form={createInitialForm()} onApply={onApply} />)

    await userEvent.click(screen.getByRole('button', { name: 'AI 完善文案' }))
    await userEvent.click(await screen.findByRole('button', { name: '生成建议' }))
    expect(await screen.findByText('AI 文案助手暂时不可用')).toBeInTheDocument()
    expect(onApply).not.toHaveBeenCalled()

    await userEvent.click(screen.getByRole('button', { name: '重试' }))
    await waitFor(() => expect(screen.getByText('周末爵士夜：在城市里听见即兴')).toBeInTheDocument())
    expect(onApply).not.toHaveBeenCalled()
  })

  it('does not apply empty suggestions over existing form content', async () => {
    const onApply = vi.fn()
    apiMock.fn.mockResolvedValueOnce({
      requestId: 'r2',
      suggestion: {
        title: '新标题',
        summary: '',
        description: '',
        attendanceNotes: '须知',
        warnings: [],
      },
      warnings: [],
    })
    render(<AiCopyAssistant form={createInitialForm()} onApply={onApply} />)

    await userEvent.click(screen.getByRole('button', { name: 'AI 完善文案' }))
    await userEvent.click(await screen.findByRole('button', { name: '生成建议' }))
    await screen.findByText('新标题')

    // 空建议字段默认不勾选：应用后不能把表单已填内容清成空串。
    expect(screen.getByRole('checkbox', { name: /简短摘要/ })).not.toBeChecked()
    expect(screen.getByRole('checkbox', { name: /完整描述/ })).not.toBeChecked()
    await userEvent.click(screen.getByRole('button', { name: '应用所选字段' }))
    const patch = onApply.mock.calls[0][0]
    expect(patch.title).toBe('新标题')
    expect(patch.summary).toBeUndefined()
    expect(patch.description).toBeUndefined()
    expect(patch.attendanceNotes).toBe('须知')
  })

  it('sends refresh on regenerate so the button is not served a cached copy', async () => {
    apiMock.fn
      .mockResolvedValueOnce(suggestion)
      .mockResolvedValueOnce(suggestion)
    render(<AiCopyAssistant form={createInitialForm()} onApply={vi.fn()} />)

    await userEvent.click(screen.getByRole('button', { name: 'AI 完善文案' }))
    await userEvent.click(await screen.findByRole('button', { name: '生成建议' }))
    await screen.findByText('周末爵士夜：在城市里听见即兴')
    // 首次生成允许命中缓存。
    expect(apiMock.fn.mock.calls[0][2]).toMatchObject({ refresh: false })

    await userEvent.click(screen.getByRole('button', { name: '重新生成' }))
    await waitFor(() => expect(apiMock.fn).toHaveBeenCalledTimes(2))
    // 重新生成必须绕过缓存读，否则会拿到一字不差的同一份文案。
    expect(apiMock.fn.mock.calls[1][2]).toMatchObject({ refresh: true })
  })

})
