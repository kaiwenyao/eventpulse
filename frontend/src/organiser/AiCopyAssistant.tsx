import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api, ApiError } from '../api'
import { ErrorNote } from '../ui/Badges'
import { SkeletonCard } from '../ui/Skeleton'
import type { EventFormState } from './eventForm'
import { localInputToIso } from '../lib/datetime'

interface CopySuggestion {
  title: string
  summary: string
  description: string
  attendanceNotes: string
  warnings: string[]
}

interface ImproveEventResponse {
  requestId: string
  suggestion: CopySuggestion
  warnings: string[]
}

/** 可以被应用到表单的文案字段。 */
const SUGGESTION_FIELDS = ['title', 'summary', 'description', 'attendanceNotes'] as const
type SuggestionField = (typeof SUGGESTION_FIELDS)[number]

const FIELD_TO_FORM: Record<SuggestionField, keyof EventFormState> = {
  title: 'title',
  summary: 'summary',
  description: 'description',
  attendanceNotes: 'attendanceNotes',
}

/**
 * 主办方文案助手面板。AI 只产出建议：加载、失败、重试都在这里，
 * 主办方逐项勾选后点「应用」才会写进表单；保存与发布仍走普通接口。
 */
export function AiCopyAssistant({
  form,
  eventId,
  onApply,
}: {
  form: EventFormState
  eventId?: string
  onApply: (patch: Partial<EventFormState>) => void
}) {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [suggestion, setSuggestion] = useState<CopySuggestion | null>(null)
  const [selected, setSelected] = useState<Record<SuggestionField, boolean>>({
    title: true,
    summary: true,
    description: true,
    attendanceNotes: true,
  })

  async function improve() {
    setLoading(true)
    setError('')
    try {
      const response = await api<ImproveEventResponse>('POST', '/api/ai/organiser/improve-event', {
        eventId: eventId ? Number(eventId) : undefined,
        title: form.title,
        summary: form.summary,
        description: form.description,
        category: form.category,
        city: form.city,
        venueName: form.venueName,
        startsAt: localInputToIso(form.startsAt),
        tone: '',
      })
      const next = {
        title: response.suggestion.title ?? '',
        summary: response.suggestion.summary ?? '',
        description: response.suggestion.description ?? '',
        attendanceNotes: response.suggestion.attendanceNotes ?? '',
        // 警告既可能来自 suggestion.warnings，也可能是顶层的全局提示。
        warnings: [
          ...(response.suggestion.warnings ?? []),
          ...(response.warnings ?? []),
        ],
      }
      setSuggestion(next)
      // 空建议字段默认不勾选：应用时不能把表单已有内容清成空串。
      setSelected({
        title: next.title !== '',
        summary: next.summary !== '',
        description: next.description !== '',
        attendanceNotes: next.attendanceNotes !== '',
      })
    } catch (e) {
      const message = e instanceof ApiError ? e.message : t('ai.copy.failed')
      setError(message)
      setSuggestion(null)
    } finally {
      setLoading(false)
    }
  }

  function applySelected() {
    if (!suggestion) return
    const patch: Partial<EventFormState> = {}
    for (const field of SUGGESTION_FIELDS) {
      // 空建议不写入表单：清空主办方已填的内容比不应用更糟。
      if (selected[field] && suggestion[field]) {
        patch[FIELD_TO_FORM[field]] = suggestion[field]
      }
    }
    onApply(patch)
    setOpen(false)
    setSuggestion(null)
  }

  if (!open) {
    return (
      <button type="button" className="btn-secondary" onClick={() => setOpen(true)}>
        {t('ai.copy.entry')}
      </button>
    )
  }

  return (
    <div className="ai-copy" data-testid="ai-copy-panel">
      <div className="ai-copy-head">
        <strong>{t('ai.copy.title')}</strong>
        <button type="button" className="btn-ghost" onClick={() => setOpen(false)} aria-label={t('ai.copy.close')}>
          ✕
        </button>
      </div>
      <p className="muted small">{t('ai.copy.hint')}</p>

      {loading && <SkeletonCard />}
      {error && (
        <>
          <ErrorNote message={error} />
          <button type="button" className="btn-secondary btn-sm" onClick={() => void improve()}>
            {t('ai.copy.retry')}
          </button>
        </>
      )}

      {suggestion && !loading && (
        <div className="ai-copy-suggestion">
          {suggestion.warnings.length > 0 && (
            <div className="callout callout-warn" role="status">
              <p className="callout-title">{t('ai.copy.warnings')}</p>
              <ul>
                {suggestion.warnings.map((warning) => (
                  <li key={warning}>{warning}</li>
                ))}
              </ul>
            </div>
          )}
          {SUGGESTION_FIELDS.map((field) => (
            <label key={field} className="ai-copy-field">
              <input
                type="checkbox"
                checked={selected[field]}
                onChange={(event) => setSelected({ ...selected, [field]: event.target.checked })}
              />
              <span>{t(`ai.copy.fields.${field}`)}</span>
              <p className="ai-copy-value">{suggestion[field] || t('ai.copy.emptyField')}</p>
            </label>
          ))}
          <div className="ai-copy-actions">
            <button type="button" className="btn-primary" onClick={applySelected}>
              {t('ai.copy.apply')}
            </button>
            <button type="button" className="btn-ghost" onClick={() => void improve()}>
              {t('ai.copy.regenerate')}
            </button>
          </div>
          <p className="muted small">{t('ai.copy.confirmNote')}</p>
        </div>
      )}

      {!suggestion && !loading && !error && (
        <button type="button" className="btn-primary" onClick={() => void improve()}>
          {t('ai.copy.generate')}
        </button>
      )}
    </div>
  )
}
