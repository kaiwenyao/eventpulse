import { FormEvent, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'

interface ConfirmDialogProps {
  open: boolean
  title: string
  description?: string
  /** When set, the dialog collects a required reason before confirming. */
  reasonLabel?: string
  reasonPlaceholder?: string
  confirmLabel?: string
  cancelLabel?: string
  tone?: 'default' | 'danger'
  busy?: boolean
  onCancel: () => void
  onConfirm: (reason: string) => void
}

/**
 * Accessible replacement for `window.confirm` / `window.prompt`: destructive
 * organiser actions (cancel an event, delete a draft) need a real dialog with
 * context, an explicit reason, and an Escape route — not a native OS popup.
 *
 * The body is a separate component that only mounts while the dialog is open,
 * so the reason field starts empty on every open without a reset effect.
 */
export function ConfirmDialog({ open, onCancel, ...rest }: ConfirmDialogProps) {
  if (!open) return null
  return <ConfirmDialogBody onCancel={onCancel} {...rest} />
}

type ConfirmDialogBodyProps = Omit<ConfirmDialogProps, 'open'>

function ConfirmDialogBody({
  title,
  description,
  reasonLabel,
  reasonPlaceholder,
  confirmLabel,
  cancelLabel,
  tone = 'default',
  busy = false,
  onCancel,
  onConfirm,
}: ConfirmDialogBodyProps) {
  const { t } = useTranslation()
  const [reason, setReason] = useState('')
  const resolvedConfirm = confirmLabel ?? t('common.confirm')
  const resolvedCancel = cancelLabel ?? t('common.back')

  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') onCancel()
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [onCancel])

  const confirmDisabled = busy || (Boolean(reasonLabel) && !reason.trim())

  function submit(event: FormEvent) {
    event.preventDefault()
    if (confirmDisabled) return
    onConfirm(reason.trim())
  }

  return (
    <div className="modal-scrim" onMouseDown={onCancel}>
      <form
        className="modal"
        role="dialog"
        aria-modal="true"
        aria-label={title}
        onMouseDown={(e) => e.stopPropagation()}
        onSubmit={submit}
      >
        <h2 className="modal-title">{title}</h2>
        {description && <p className="muted modal-desc">{description}</p>}
        {reasonLabel && (
          <div className="field">
            <div className="field-head">
              <label htmlFor="modal-reason">{reasonLabel}</label>
            </div>
            <input
              id="modal-reason"
              autoFocus
              value={reason}
              placeholder={reasonPlaceholder}
              onChange={(e) => setReason(e.target.value)}
              required
            />
          </div>
        )}
        <div className="modal-actions">
          <button type="button" className="btn-secondary" onClick={onCancel} disabled={busy}>
            {resolvedCancel}
          </button>
          <button
            type="submit"
            className={tone === 'danger' ? 'btn-danger' : 'btn-primary'}
            disabled={confirmDisabled}
            autoFocus={!reasonLabel}
          >
            {busy ? t('common.processing') : resolvedConfirm}
          </button>
        </div>
      </form>
    </div>
  )
}
