import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AiDiscoveryAssistant } from './AiDiscoveryAssistant'
import { ApiError } from '../api'
import { EventVo } from '../types'

const apiMock = vi.hoisted(() => ({ fn: vi.fn() }))
vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, api: apiMock.fn }
})

const event: EventVo = {
  id: 7,
  title: '周末技术沙龙',
  summary: '适合新手',
  description: '',
  category: 'tech',
  city: 'Shanghai',
  venueName: '',
  address: '',
  latitude: undefined,
  longitude: undefined,
  startsAt: '2026-09-05T06:00:00Z',
  endsAt: '2026-09-05T09:00:00Z',
  coverUrl: undefined,
  salesStartAt: undefined,
  salesEndAt: undefined,
  maxQuantityPerBooking: 4,
  contactInfo: '',
  attendanceNotes: '',
  priceCents: 0,
  capacity: 100,
  sold: 10,
  remaining: 90,
  status: 'PUBLISHED',
  cancellationReason: undefined,
  updatedAt: '2026-09-01T00:00:00Z',
  createdAt: '2026-09-01T00:00:00Z',
  version: 1,
  favourite: false,
  bookable: true,
  unbookableReason: undefined,
}

beforeEach(() => {
  apiMock.fn.mockReset()
})

describe('AiDiscoveryAssistant', () => {
  it('sends a question and renders the answer with real event cards', async () => {
    apiMock.fn.mockResolvedValueOnce({
      requestId: 'r1',
      conversationId: '31',
      answer: '找到 1 场周末技术活动。',
      events: [{ event, reason: '周六下午，免费' }],
      followUpQuestions: ['想要更便宜的吗？'],
    })
    render(<MemoryRouter><AiDiscoveryAssistant onClose={() => {}} /></MemoryRouter>)

    await userEvent.type(screen.getByLabelText('用一句话描述你想找的活动…'), '这个周末有什么技术活动')
    await userEvent.click(screen.getByRole('button', { name: '发送' }))

    await waitFor(() => expect(apiMock.fn).toHaveBeenCalledWith('POST', '/api/ai/discovery/chat', {
      conversationId: null,
      message: '这个周末有什么技术活动',
    }))
    expect(await screen.findByText('找到 1 场周末技术活动。')).toBeInTheDocument()
    expect(screen.getByText('周末技术沙龙')).toBeInTheDocument()
    expect(screen.getByText('周六下午，免费')).toBeInTheDocument()

    // 追问：带上服务端返回的会话 ID，保证登录用户的多轮上下文。
    await userEvent.click(screen.getByRole('button', { name: '想要更便宜的吗？' }))
    await waitFor(() =>
      expect(apiMock.fn).toHaveBeenLastCalledWith('POST', '/api/ai/discovery/chat', {
        conversationId: '31',
        message: '想要更便宜的吗？',
      }),
    )
  })

  it('shows a thinking indicator while the answer is in flight', async () => {
    let resolve: (value: unknown) => void = () => {}
    apiMock.fn.mockReturnValueOnce(new Promise((r) => { resolve = r }))
    render(<MemoryRouter><AiDiscoveryAssistant onClose={() => {}} /></MemoryRouter>)

    await userEvent.type(screen.getByLabelText('用一句话描述你想找的活动…'), '周末有什么活动')
    await userEvent.click(screen.getByRole('button', { name: '发送' }))

    // 等待期间给出明确反馈，而不是一个没有语义的骨架屏。
    expect(await screen.findByRole('status')).toHaveTextContent('正在检索真实活动…')

    resolve({ requestId: 'r3', conversationId: null, answer: '找到了。', events: [], followUpQuestions: [] })
    await waitFor(() => expect(screen.getByText('找到了。')).toBeInTheDocument())
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })

  it('offers starter questions before the first turn and sends one on click', async () => {
    apiMock.fn.mockResolvedValueOnce({
      requestId: 'r4', conversationId: null, answer: '好的。', events: [], followUpQuestions: [],
    })
    render(<MemoryRouter><AiDiscoveryAssistant onClose={() => {}} /></MemoryRouter>)

    const starter = screen.getByRole('button', { name: '现在有什么热门活动？' })
    await userEvent.click(starter)

    await waitFor(() => expect(apiMock.fn).toHaveBeenCalledWith('POST', '/api/ai/discovery/chat', {
      conversationId: null,
      message: '现在有什么热门活动？',
    }))
    // 开场问题只在没有任何对话时出现。
    await waitFor(() => expect(screen.queryByRole('button', { name: '现在有什么热门活动？' })).not.toBeInTheDocument())
  })

  it('falls back to a localised message when the answer comes back empty', async () => {
    apiMock.fn.mockResolvedValueOnce({
      requestId: 'r5', conversationId: null, answer: '   ', events: [], followUpQuestions: [],
    })
    render(<MemoryRouter><AiDiscoveryAssistant onClose={() => {}} /></MemoryRouter>)

    await userEvent.type(screen.getByLabelText('用一句话描述你想找的活动…'), '随便问问')
    await userEvent.click(screen.getByRole('button', { name: '发送' }))

    expect(await screen.findByText('这次没能整理出结果，换个说法再试一次吧。')).toBeInTheDocument()
  })

  it('shows failure with retry and clears after a successful retry', async () => {
    apiMock.fn
      .mockRejectedValueOnce(new ApiError(503, 'AI 助手暂时不可用'))
      .mockResolvedValueOnce({
        requestId: 'r2',
        conversationId: null,
        answer: '这次找到了。',
        events: [],
        followUpQuestions: [],
      })
    render(<MemoryRouter><AiDiscoveryAssistant onClose={() => {}} /></MemoryRouter>)

    await userEvent.type(screen.getByLabelText('用一句话描述你想找的活动…'), '附近的音乐活动')
    await userEvent.click(screen.getByRole('button', { name: '发送' }))
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.getByText('AI 助手暂时不可用')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: '重试' }))
    await waitFor(() => expect(screen.getByText('这次找到了。')).toBeInTheDocument())
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})
