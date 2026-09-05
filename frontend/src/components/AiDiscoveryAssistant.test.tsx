import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { AuthProvider } from '../auth'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AiDiscoveryAssistant } from './AiDiscoveryAssistant'
import { ApiError, setAccessToken } from '../api'
import { EventVo } from '../types'
import { changeLocale } from '../i18n'

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
  city: 'Berlin',
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

beforeEach(async () => {
  apiMock.fn.mockReset()
  // setup.ts 没有全局重置 localStorage，会话 id 会在用例之间串。
  // token 还额外缓存在 api.ts 的模块变量里，必须走 setAccessToken 一起清。
  setAccessToken(null)
  localStorage.clear()
  // 语言是模块级状态，切过之后不复位会串到后面的用例。
  await changeLocale('zh')
})

// 助手要读登录态来决定是否恢复服务端会话，所以必须带上 AuthProvider。
function renderAssistant() {
  return render(
    <AuthProvider>
      <MemoryRouter>
        <AiDiscoveryAssistant onClose={() => {}} />
      </MemoryRouter>
    </AuthProvider>,
  )
}

describe('AiDiscoveryAssistant', () => {
  it('sends a question and renders the answer with real event cards', async () => {
    apiMock.fn.mockResolvedValueOnce({
      requestId: 'r1',
      conversationId: '31',
      answer: '找到 1 场周末技术活动。',
      events: [{ event, reason: '周六下午，免费' }],
      followUpQuestions: ['想要更便宜的吗？'],
    })
    renderAssistant()

    await userEvent.type(screen.getByLabelText('用一句话描述你想找的活动…'), '这个周末有什么技术活动')
    await userEvent.click(screen.getByRole('button', { name: '发送' }))

    await waitFor(() => expect(apiMock.fn).toHaveBeenCalledWith('POST', '/api/ai/discovery/chat', {
      conversationId: null,
      message: '这个周末有什么技术活动',
      locale: 'zh',
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
        locale: 'zh',
      }),
    )
  })

  it('shows a thinking indicator while the answer is in flight', async () => {
    let resolve: (value: unknown) => void = () => {}
    apiMock.fn.mockReturnValueOnce(new Promise((r) => { resolve = r }))
    renderAssistant()

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
    renderAssistant()

    const starter = screen.getByRole('button', { name: '现在有什么热门活动？' })
    await userEvent.click(starter)

    await waitFor(() => expect(apiMock.fn).toHaveBeenCalledWith('POST', '/api/ai/discovery/chat', {
      conversationId: null,
      message: '现在有什么热门活动？',
      locale: 'zh',
    }))
    // 开场问题只在没有任何对话时出现。
    await waitFor(() => expect(screen.queryByRole('button', { name: '现在有什么热门活动？' })).not.toBeInTheDocument())
  })

  it('falls back to a localised message when the answer comes back empty', async () => {
    apiMock.fn.mockResolvedValueOnce({
      requestId: 'r5', conversationId: null, answer: '   ', events: [], followUpQuestions: [],
    })
    renderAssistant()

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
    renderAssistant()

    await userEvent.type(screen.getByLabelText('用一句话描述你想找的活动…'), '附近的音乐活动')
    await userEvent.click(screen.getByRole('button', { name: '发送' }))
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.getByText('AI 助手暂时不可用')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: '重试' }))
    await waitFor(() => expect(screen.getByText('这次找到了。')).toBeInTheDocument())
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
  // ---- 会话恢复 / 历史 ----

  /** 让 AuthProvider 认为已登录：给个 token，并让 /api/auth/me 返回用户。 */
  function signIn() {
    setAccessToken('token-abc')
  }

  function routeApi(routes: Record<string, unknown>) {
    apiMock.fn.mockImplementation((method: string, path: string) => {
      const key = `${method} ${path}`
      if (key in routes) {
        const value = routes[key]
        return value instanceof Error ? Promise.reject(value) : Promise.resolve(value)
      }
      return Promise.reject(new ApiError(404, `unrouted ${key}`))
    })
  }

  it('restores the stored conversation as text and says cards are not replayed', async () => {
    signIn()
    localStorage.setItem('ep_ai_conversation', '31')
    routeApi({
      'GET /api/auth/me': { id: 2, email: 'a@b.c', name: 'A', role: 'USER' },
      'GET /api/ai/conversations/31': {
        id: '31',
        messages: [
          { role: 'user', content: '第一问', createdAt: '2026-09-04T09:00:00Z' },
          { role: 'assistant', content: '第一答', createdAt: '2026-09-04T09:00:05Z' },
        ],
      },
    })

    renderAssistant()

    expect(await screen.findByText('第一问')).toBeInTheDocument()
    expect(screen.getByText('第一答')).toBeInTheDocument()
    // 服务端只存文字，所以必须如实说明卡片不会重放，而不是假装全都恢复了。
    expect(screen.getByText(/活动卡片与追问按钮不会重放/)).toBeInTheDocument()
  })

  it('drops a stored conversation that no longer belongs to the user', async () => {
    signIn()
    localStorage.setItem('ep_ai_conversation', '31')
    routeApi({
      'GET /api/auth/me': { id: 2, email: 'a@b.c', name: 'A', role: 'USER' },
      'GET /api/ai/conversations/31': new ApiError(403, 'not yours'),
    })

    renderAssistant()

    // 403/404 不摆给用户，安静地从头开始，并清掉本地 id。
    await waitFor(() => expect(localStorage.getItem('ep_ai_conversation')).toBeNull())
    expect(screen.queryByText(/活动卡片与追问按钮不会重放/)).not.toBeInTheDocument()
  })

  it('persists the conversation id returned by the server so a refresh can resume', async () => {
    apiMock.fn.mockResolvedValueOnce({
      requestId: 'r1',
      conversationId: '31',
      answer: '找到 1 场。',
      events: [],
      followUpQuestions: [],
    })
    renderAssistant()

    await userEvent.type(screen.getByLabelText('用一句话描述你想找的活动…'), '有什么活动')
    await userEvent.click(screen.getByRole('button', { name: '发送' }))

    await waitFor(() => expect(localStorage.getItem('ep_ai_conversation')).toBe('31'))
  })

  it('guests get no history controls because guest chats are not persisted', async () => {
    renderAssistant()
    await waitFor(() => expect(screen.getByRole('button', { name: '发送' })).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: '历史' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '新对话' })).not.toBeInTheDocument()
  })

  it('lists past conversations, opens one, and clears local state when the open one is deleted', async () => {
    signIn()
    routeApi({
      'GET /api/auth/me': { id: 2, email: 'a@b.c', name: 'A', role: 'USER' },
      'GET /api/ai/conversations': [
        { id: '31', preview: '找到 3 场爵士演出', updatedAt: '2026-09-04T10:00:00Z' },
      ],
      'GET /api/ai/conversations/31': {
        id: '31',
        messages: [{ role: 'assistant', content: '找到 3 场爵士演出', createdAt: '2026-09-04T10:00:00Z' }],
      },
      'DELETE /api/ai/conversations/31': undefined,
    })
    renderAssistant()

    await userEvent.click(await screen.findByRole('button', { name: '历史' }))
    await userEvent.click(await screen.findByText('找到 3 场爵士演出'))
    await waitFor(() => expect(localStorage.getItem('ep_ai_conversation')).toBe('31'))

    await userEvent.click(screen.getByRole('button', { name: '历史' }))
    await userEvent.click(await screen.findByRole('button', { name: '删除这段对话' }))

    // 删掉的正是当前打开的那段：本地 id 必须一起清，否则刷新后会去请求一个已经没了的会话。
    await waitFor(() => expect(localStorage.getItem('ep_ai_conversation')).toBeNull())
  })

  it('new chat clears the transcript and the stored id', async () => {
    signIn()
    localStorage.setItem('ep_ai_conversation', '31')
    routeApi({
      'GET /api/auth/me': { id: 2, email: 'a@b.c', name: 'A', role: 'USER' },
      'GET /api/ai/conversations/31': {
        id: '31',
        messages: [{ role: 'user', content: '第一问', createdAt: '2026-09-04T09:00:00Z' }],
      },
    })
    renderAssistant()
    expect(await screen.findByText('第一问')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: '新对话' }))

    expect(screen.queryByText('第一问')).not.toBeInTheDocument()
    expect(localStorage.getItem('ep_ai_conversation')).toBeNull()
  })

  it('keeps the active conversation when opening another one fails transiently', async () => {
    signIn()
    localStorage.setItem('ep_ai_conversation', '31')
    routeApi({
      'GET /api/auth/me': { id: 2, email: 'a@b.c', name: 'A', role: 'USER' },
      'GET /api/ai/conversations/31': {
        id: '31',
        messages: [{ role: 'user', content: '会话A的内容', createdAt: '2026-09-04T09:00:00Z' }],
      },
      'GET /api/ai/conversations': [
        { id: '32', preview: '会话B', updatedAt: '2026-09-04T10:00:00Z' },
      ],
      'GET /api/ai/conversations/32': new ApiError(500, 'upstream hiccup'),
    })
    renderAssistant()
    expect(await screen.findByText('会话A的内容')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: '历史' }))
    await userEvent.click(await screen.findByText('会话B'))

    // 打开 B 失败是瞬时故障：A 的 id 必须保住，否则下一条消息会静默开一个新会话，
    // 把记录劈成两半。只有明确的 403/404 才该丢弃本地 id。
    await waitFor(() => expect(apiMock.fn).toHaveBeenCalledWith('GET', '/api/ai/conversations/32'))
    expect(localStorage.getItem('ep_ai_conversation')).toBe('31')
    expect(screen.getByText('会话A的内容')).toBeInTheDocument()
  })

  it('discards an in-flight restore that lands after the chat was reset', async () => {
    signIn()
    localStorage.setItem('ep_ai_conversation', '31')
    let releaseRestore: (value: unknown) => void = () => {}
    const pending = new Promise((resolve) => {
      releaseRestore = resolve
    })
    apiMock.fn.mockImplementation((method: string, path: string) => {
      if (path === '/api/auth/me') {
        return Promise.resolve({ id: 2, email: 'a@b.c', name: 'A', role: 'USER' })
      }
      if (path === '/api/ai/conversations/31') {
        return pending.then(() => ({
          id: '31',
          messages: [{ role: 'user', content: '旧会话内容', createdAt: '2026-09-04T09:00:00Z' }],
        }))
      }
      return Promise.reject(new ApiError(404, `unrouted ${method} ${path}`))
    })
    renderAssistant()

    // 恢复还在路上时点「新对话」。
    await userEvent.click(await screen.findByRole('button', { name: '新对话' }))
    releaseRestore(null)

    // 迟到的响应不能把刚清空的对话又填回来（登出/换账号是同一类竞态）。
    await waitFor(() => expect(apiMock.fn).toHaveBeenCalledWith('GET', '/api/ai/conversations/31'))
    expect(screen.queryByText('旧会话内容')).not.toBeInTheDocument()
    expect(localStorage.getItem('ep_ai_conversation')).toBeNull()
  })

  it('sends the current UI language so short messages do not get answered in the wrong one', async () => {
    // 助手的中文提示词会把 "berlin" 这种判断不出语言的短消息带偏成中文回复；
    // 界面语言是这种情况下唯一的依据，所以每一轮都必须带上。
    await changeLocale('en')
    apiMock.fn.mockResolvedValueOnce({
      requestId: 'r9', conversationId: null, answer: 'ok', events: [], followUpQuestions: [],
    })
    renderAssistant()

    await userEvent.type(screen.getByLabelText('Describe the event you are looking for…'), 'berlin')
    await userEvent.click(screen.getByRole('button', { name: 'Send' }))

    await waitFor(() => expect(apiMock.fn).toHaveBeenCalledWith('POST', '/api/ai/discovery/chat', {
      conversationId: null,
      message: 'berlin',
      locale: 'en',
    }))
  })

})
