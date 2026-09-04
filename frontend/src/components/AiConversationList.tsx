import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api } from '../api'
import { formatTime } from '../api'

export interface ConversationSummary {
  id: string
  preview: string
  updatedAt: string
}

interface AiConversationListProps {
  /** 当前打开的会话，用于高亮。 */
  activeId: string | null
  onOpen: (id: string) => void
  onDeleted: (id: string) => void
}

/**
 * 登录用户的历史对话列表。服务端只存文字，所以这里也只展示一句预览 ——
 * 点开之后恢复的同样只有文字，活动卡片不会重放。
 *
 * 列表加载失败不弹错：历史是锦上添花，失败时安静地当作「没有历史」，
 * 不能因此挡住用户正常提问。
 */
export function AiConversationList({ activeId, onOpen, onDeleted }: AiConversationListProps) {
  const { t } = useTranslation()
  const [items, setItems] = useState<ConversationSummary[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    api<ConversationSummary[]>('GET', '/api/ai/conversations')
      .then((list) => {
        if (!cancelled) setItems(list)
      })
      .catch(() => {
        if (!cancelled) setItems([])
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [])

  async function remove(id: string) {
    // 乐观移除：删除失败也不该把已经消失的行再变回来吓用户，重新打开面板即可复核。
    setItems((prev) => prev.filter((item) => item.id !== id))
    try {
      await api<void>('DELETE', `/api/ai/conversations/${id}`)
    } finally {
      onDeleted(id)
    }
  }

  if (loading) return null

  return (
    <div className="ai-history">
      <p className="eyebrow">{t('ai.discovery.history')}</p>
      {items.length === 0 ? (
        <p className="muted small">{t('ai.discovery.historyEmpty')}</p>
      ) : (
        <ul className="ai-history-list">
          {items.map((item) => (
            <li key={item.id} className={item.id === activeId ? 'ai-history-item is-active' : 'ai-history-item'}>
              <button type="button" className="ai-history-open" onClick={() => onOpen(item.id)}>
                <span className="ai-history-preview">{item.preview || t('ai.discovery.historyEmpty')}</span>
                <span className="muted small">{formatTime(item.updatedAt)}</span>
              </button>
              <button
                type="button"
                className="btn-ghost btn-sm"
                aria-label={t('ai.discovery.deleteChat')}
                onClick={() => void remove(item.id)}
              >
                ✕
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
