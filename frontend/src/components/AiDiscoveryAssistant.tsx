import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ApiError, api } from '../api'
import { useAuth } from '../auth'
import { AiThinkingTurn } from './AiThinking'
import { AiConversationList } from './AiConversationList'
import { EventTicket } from './EventTicket'
import { EventVo } from '../types'
import { Alert } from '../ui/Alert'
import { resolveApiError } from '../lib/apiError'
import { readStoredConversationId, writeStoredConversationId } from '../lib/aiConversation'
import { currentLocale } from '../i18n'
import { streamChatAnswer } from '../lib/sse'

interface ConversationDetail {
  id: string
  messages: { role: string; content: string; createdAt: string }[]
}

interface AiEventMention {
  event: EventVo
  reason: string
}

interface ChatTurn {
  role: 'user' | 'assistant'
  text: string
  events?: AiEventMention[]
  followUps?: string[]
}

/**
 * 自然语言找活动助手。只调用 Spring Boot 的 /api/ai/discovery/chat；
 * 返回的活动卡片全部来自后端二次校验过的真实活动。登录用户的会话由
 * 服务端保存在 PostgreSQL，游客是不持久化的单轮请求。
 *
 * followUps 是【以用户身份】发出的下一句话（服务端按用户视角生成），
 * 点击等于用户自己又说了一遍，所以按「用户要说的话」呈现，而不是助手的问句。
 */
export function AiDiscoveryAssistant({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation()
  const { user } = useAuth()
  const [turns, setTurns] = useState<ChatTurn[]>([])
  // 正在流式产出的助手回复草稿：delta 逐字增长，done 后替换成正式轮次；
  // error / 断线时整体丢弃——半截内容不能留在屏幕上冒充完整回答。
  const [draft, setDraft] = useState<ChatTurn | null>(null)
  const [input, setInput] = useState('')
  const [conversationId, setConversationId] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [lastMessage, setLastMessage] = useState('')
  const [restored, setRestored] = useState(false)
  const [showHistory, setShowHistory] = useState(false)
  const listRef = useRef<HTMLDivElement>(null)
  // 恢复请求的代次：每发起一次 +1，落地前比对，过期的响应直接丢弃。
  const restoreGuard = useRef(0)
  // 发送代次：新一轮提问会让上一轮在途的流式响应整体作废（迟到帧丢弃）。
  const sendGuard = useRef(0)
  // 当前提问的 AbortController：换新对话 / 卸载时主动断开流。
  const streamAbort = useRef<AbortController | null>(null)

  // 只有登录用户在服务端有会话；游客的对话不落库，也就没得恢复。
  useEffect(() => {
    if (!user) return
    const stored = readStoredConversationId()
    if (!stored) return
    void openConversation(stored)
    return () => {
      // 登出或换账号：让在途的恢复请求作废。否则上一个账号的记录会在身份切换之后
      // 才落地，把别人的对话摆进新会话，下一次发送还会因归属校验失败。
      restoreGuard.current += 1
    }
    // 只在登录态建立时尝试恢复一次。
  }, [user])

  /** 打开一段历史会话：只回填文字，卡片与追问按钮无法重放（服务端只存 role/content）。 */
  async function openConversation(id: string) {
    const generation = ++restoreGuard.current
    try {
      const detail = await api<ConversationDetail>('GET', `/api/ai/conversations/${id}`)
      // 期间登出、换账号、开了别的会话或点了「新对话」：这次响应已经过期，不能落地。
      if (restoreGuard.current !== generation) return
      setTurns(
        detail.messages.map((message) => ({
          role: message.role === 'assistant' ? 'assistant' : 'user',
          text: message.content,
        })),
      )
      setConversationId(detail.id)
      writeStoredConversationId(detail.id)
      setRestored(true)
      setShowHistory(false)
      setError('')
    } catch (e) {
      if (restoreGuard.current !== generation) return
      // 只有明确的 403/404 才丢掉本地 id：会话确实没了或不属于当前账号，安静地从头
      // 开始，不把状态码摆给用户。网络抖动、5xx 则保持当前会话不动——否则正在看
      // 会话 A 的用户点开 B 失败一次，A 的 id 就被清掉，下一条消息会静默开一个新
      // 会话，把记录劈成两半。
      if (e instanceof ApiError && (e.status === 403 || e.status === 404)) {
        writeStoredConversationId(null)
        setConversationId(null)
        setRestored(false)
      }
    }
  }

  function startNewChat() {
    // 在途的恢复请求同样作废，不然它会把刚清空的对话又填回来。
    restoreGuard.current += 1
    // 正在流的回答也要中止：新对话与旧回答互不相干。
    sendGuard.current += 1
    streamAbort.current?.abort()
    streamAbort.current = null
    setTurns([])
    setDraft(null)
    setConversationId(null)
    writeStoredConversationId(null)
    setRestored(false)
    setError('')
    setShowHistory(false)
  }

  // 组件卸载（收起助手 / 登出 / 切页）时断开在途流，避免继续往已卸载的组件写状态。
  useEffect(() => {
    return () => {
      sendGuard.current += 1
      streamAbort.current?.abort()
    }
  }, [])

  useEffect(() => {
    const list = listRef.current
    if (list && typeof list.scrollTo === 'function') {
      list.scrollTo({ top: list.scrollHeight })
    }
  }, [turns, draft, loading])

  async function send(message: string) {
    const trimmed = message.trim()
    if (!trimmed || loading) return
    const generation = ++sendGuard.current
    setError('')
    setLastMessage(trimmed)
    setInput('')
    setTurns((prev) => [...prev, { role: 'user', text: trimmed }])
    setDraft({ role: 'assistant', text: '' })
    setLoading(true)
    const controller = new AbortController()
    streamAbort.current = controller
    let sawDone = false
    try {
      await streamChatAnswer(
        {
          conversationId,
          message: trimmed,
          // 界面语言：只在消息本身判断不出语言（"berlin"、emoji）时给模型兜底，
          // 用户这次说的语言仍然优先。
          locale: currentLocale(),
        },
        {
          onDelta: (text) => {
            if (sendGuard.current !== generation) return
            setDraft((current) =>
              current ? { ...current, text: current.text + text } : current,
            )
          },
          onDone: (response) => {
            if (sendGuard.current !== generation) return
            sawDone = true
            if (response.conversationId) {
              setConversationId(response.conversationId)
              writeStoredConversationId(response.conversationId)
            }
            // 权威收尾：用服务端完整 answer 覆盖增量（即使增量在极端畸形输出下
            // 放过错的内容，这里也以解析校验过的为准）。
            setTurns((prev) => [
              ...prev,
              {
                role: 'assistant',
                // 服务端保证不会把原始 JSON 当回答返回；空回答用本地化文案兜底。
                text: response.answer?.trim() || t('ai.discovery.noAnswer'),
                events: response.events,
                followUps: response.followUpQuestions,
              },
            ])
            setDraft(null)
            setLoading(false)
          },
          onError: (messageText) => {
            if (sendGuard.current !== generation) return
            // 明确失败：丢掉半截草稿，不许冒充完整回答；给出可重试的提示。
            setDraft(null)
            setLoading(false)
            setError(messageText)
          },
        },
        controller.signal,
      )
      // 流正常结束但没有 done（服务端异常流）：同样不能留半截冒充完整。
      if (!sawDone && sendGuard.current === generation) {
        setDraft(null)
        setLoading(false)
        setError(t('ai.discovery.failed'))
      }
    } catch (e) {
      if (sendGuard.current !== generation) return
      // 主动中止（新对话 / 卸载）不算失败；其它错误给明确提示。
      if (controller.signal.aborted) {
        setDraft(null)
        setLoading(false)
        return
      }
      setDraft(null)
      setLoading(false)
      const { message: messageText } = resolveApiError(e, 'ai.discovery.failed')
      setError(messageText)
    } finally {
      if (sendGuard.current === generation) streamAbort.current = null
    }
  }

  function retry() {
    if (lastMessage) void send(lastMessage)
  }

  // i18n 资源可能被覆盖成任意结构，取值后按字符串过滤，避免渲染出对象。
  const rawStarters = t('ai.discovery.starters', { returnObjects: true })
  const starters: string[] = Array.isArray(rawStarters)
    ? rawStarters.filter((s): s is string => typeof s === 'string')
    : []

  return (
    <section className="ai-assistant" aria-label={t('ai.discovery.title')}>
      <header className="ai-assistant-head">
        <div>
          <p className="eyebrow">{t('ai.discovery.entry')}</p>
          <h2>{t('ai.discovery.title')}</h2>
          <p className="muted small">{t('ai.discovery.hint')}</p>
        </div>
        <div className="ai-assistant-actions">
          {user && (
            <>
              <button type="button" className="btn-ghost btn-sm" onClick={() => setShowHistory((open) => !open)}>
                {t('ai.discovery.historyToggle')}
              </button>
              <button type="button" className="btn-ghost btn-sm" onClick={startNewChat}>
                {t('ai.discovery.newChat')}
              </button>
            </>
          )}
          <button type="button" className="btn-ghost" onClick={onClose} aria-label={t('ai.discovery.close')}>
            ✕
          </button>
        </div>
      </header>

      {user && showHistory && (
        <AiConversationList
          activeId={conversationId}
          onOpen={(id) => void openConversation(id)}
          onDeleted={(id) => {
            // 删掉的正是当前这段：本地 id 也要一起清，否则刷新后又去请求一个已经没了的会话。
            if (id === conversationId) startNewChat()
          }}
        />
      )}

      <div className="ai-assistant-log" ref={listRef}>
        {restored && turns.length > 0 && (
          <p className="muted small ai-restored-note">{t('ai.discovery.restoredNote')}</p>
        )}
        {turns.length === 0 && !loading && (
          <div className="ai-empty">
            <p className="muted small">{t('ai.discovery.empty')}</p>
            <div className="ai-starters">
              {starters.map((question) => (
                <button key={question} type="button" className="ai-starter" onClick={() => void send(question)}>
                  {question}
                </button>
              ))}
            </div>
          </div>
        )}

        {turns.map((turn, index) => (
          <div key={index} className={`ai-turn ai-turn-${turn.role}`}>
            <p className="ai-turn-role">
              {turn.role === 'user' ? t('ai.discovery.you') : t('ai.discovery.assistant')}
            </p>
            <p className="ai-turn-text">{turn.text}</p>
            {turn.events && turn.events.length > 0 && (
              <div className="ai-turn-events">
                {turn.events.map((mention) => (
                  <div key={mention.event.id} className="ai-event">
                    <EventTicket event={mention.event} />
                    {mention.reason && <p className="muted small ai-event-reason">{mention.reason}</p>}
                  </div>
                ))}
              </div>
            )}
            {turn.followUps && turn.followUps.length > 0 && (
              <div className="ai-followups">
                <p className="eyebrow ai-followups-label">{t('ai.discovery.followUpLabel')}</p>
                <div className="ai-followup-list">
                  {turn.followUps.map((question) => (
                    <button
                      key={question}
                      type="button"
                      className="ai-followup"
                      disabled={loading}
                      onClick={() => void send(question)}
                    >
                      <span className="ai-followup-arrow" aria-hidden>
                        ↗
                      </span>
                      {question}
                    </button>
                  ))}
                </div>
              </div>
            )}
          </div>
        ))}

        {draft && (
          <div className="ai-turn ai-turn-assistant ai-turn-streaming">
            <p className="ai-turn-role">{t('ai.discovery.assistant')}</p>
            {/* aria-live：增量逐字进来时读屏也能跟上，而不是等整段结束。 */}
            {draft.text ? (
              <p className="ai-turn-text" role="status" aria-live="polite">
                {draft.text}
                <span className="ai-caret" aria-hidden>
                  ▍
                </span>
              </p>
            ) : (
              // 还在跑工具 / 等第一个 token：给出真实进展反馈，而不是空白。
              <AiThinkingTurn />
            )}
          </div>
        )}

        {error && (
          <Alert tone="error" title={error}>
            <button type="button" className="btn-secondary btn-sm" onClick={retry}>
              {t('ai.discovery.retry')}
            </button>
          </Alert>
        )}
      </div>

      <form
        className="ai-assistant-input"
        onSubmit={(event) => {
          event.preventDefault()
          void send(input)
        }}
      >
        <input
          value={input}
          onChange={(event) => setInput(event.target.value)}
          placeholder={t('ai.discovery.placeholder')}
          aria-label={t('ai.discovery.placeholder')}
          maxLength={1000}
          disabled={loading}
        />
        <button type="submit" className="btn-primary" disabled={loading || !input.trim()}>
          {loading ? t('ai.discovery.thinking') : t('ai.discovery.send')}
        </button>
      </form>
      <p className="muted small">{t('ai.discovery.guestNote')}</p>
    </section>
  )
}
