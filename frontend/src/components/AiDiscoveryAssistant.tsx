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

interface ConversationDetail {
  id: string
  messages: { role: string; content: string; createdAt: string }[]
}

interface AiEventMention {
  event: EventVo
  reason: string
}

interface AiChatResponse {
  requestId: string
  conversationId: string | null
  answer: string
  events: AiEventMention[]
  followUpQuestions: string[]
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
    // eslint-disable-next-line react-hooks/exhaustive-deps
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
    setTurns([])
    setConversationId(null)
    writeStoredConversationId(null)
    setRestored(false)
    setError('')
    setShowHistory(false)
  }

  useEffect(() => {
    const list = listRef.current
    if (list && typeof list.scrollTo === 'function') {
      list.scrollTo({ top: list.scrollHeight })
    }
  }, [turns, loading])

  async function send(message: string) {
    const trimmed = message.trim()
    if (!trimmed || loading) return
    setError('')
    setLastMessage(trimmed)
    setInput('')
    setTurns((prev) => [...prev, { role: 'user', text: trimmed }])
    setLoading(true)
    try {
      const response = await api<AiChatResponse>('POST', '/api/ai/discovery/chat', {
        conversationId,
        message: trimmed,
      })
      if (response.conversationId) {
        setConversationId(response.conversationId)
        writeStoredConversationId(response.conversationId)
      }
      setTurns((prev) => [
        ...prev,
        {
          role: 'assistant',
          // 服务端保证不会把原始 JSON 当回答返回；万一是空回答，用本地化文案兜底。
          text: response.answer?.trim() || t('ai.discovery.noAnswer'),
          events: response.events,
          followUps: response.followUpQuestions,
        },
      ])
    } catch (e) {
      const { message: messageText } = resolveApiError(e, 'ai.discovery.failed')
      setError(messageText)
    } finally {
      setLoading(false)
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

        {loading && <AiThinkingTurn />}
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
