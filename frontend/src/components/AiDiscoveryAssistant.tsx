import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api, ApiError } from '../api'
import { EventTicket } from './EventTicket'
import { EventVo } from '../types'
import { SkeletonCard } from '../ui/Skeleton'

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
 */
export function AiDiscoveryAssistant({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation()
  const [turns, setTurns] = useState<ChatTurn[]>([])
  const [input, setInput] = useState('')
  const [conversationId, setConversationId] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [lastMessage, setLastMessage] = useState('')
  const listRef = useRef<HTMLDivElement>(null)

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
      if (response.conversationId) setConversationId(response.conversationId)
      setTurns((prev) => [
        ...prev,
        {
          role: 'assistant',
          text: response.answer,
          events: response.events,
          followUps: response.followUpQuestions,
        },
      ])
    } catch (e) {
      const messageText = e instanceof ApiError ? e.message : t('ai.discovery.failed')
      setError(messageText)
    } finally {
      setLoading(false)
    }
  }

  function retry() {
    if (lastMessage) void send(lastMessage)
  }

  return (
    <section className="ai-assistant" aria-label={t('ai.discovery.title')}>
      <header className="ai-assistant-head">
        <div>
          <h2>{t('ai.discovery.title')}</h2>
          <p className="muted small">{t('ai.discovery.hint')}</p>
        </div>
        <button type="button" className="btn-ghost" onClick={onClose} aria-label={t('ai.discovery.close')}>
          ✕
        </button>
      </header>

      <div className="ai-assistant-log" ref={listRef}>
        {turns.length === 0 && !loading && <p className="muted small">{t('ai.discovery.empty')}</p>}
        {turns.map((turn, index) => (
          <div key={index} className={`ai-turn ai-turn-${turn.role}`}>
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
              <div className="chips chips-loose">
                {turn.followUps.map((question) => (
                  <button key={question} type="button" className="chip" onClick={() => void send(question)}>
                    {question}
                  </button>
                ))}
              </div>
            )}
          </div>
        ))}
        {loading && <SkeletonCard />}
        {error && (
          <div className="callout callout-error" role="alert">
            <p className="callout-title">{error}</p>
            <button type="button" className="btn-secondary btn-sm" onClick={retry}>
              {t('ai.discovery.retry')}
            </button>
          </div>
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
        />
        <button type="submit" className="btn-primary" disabled={loading || !input.trim()}>
          {t('ai.discovery.send')}
        </button>
      </form>
      <p className="muted small">{t('ai.discovery.guestNote')}</p>
    </section>
  )
}
