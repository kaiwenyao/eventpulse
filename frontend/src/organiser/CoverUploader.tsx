import { DragEvent, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { uploadFile } from '../api'
import { ImageIcon } from '../ui/Icons'
import { resolveApiError } from '../lib/apiError'

const MAX_BYTES = 5 * 1024 * 1024
const ACCEPTED = ['image/png', 'image/jpeg', 'image/webp', 'image/gif']

interface CoverUploaderProps {
  value: string
  onChange: (url: string) => void
  onError: (message: string) => void
}

/**
 * Cover image field: click or drop to upload, with client-side type/size checks
 * before the request so an oversized file fails instantly instead of after a
 * long upload. The `<input type="file">` stays a real labelled control (screen
 * readers and keyboards reach it); the drop zone is progressive enhancement.
 */
export function CoverUploader({ value, onChange, onError }: CoverUploaderProps) {
  const { t } = useTranslation()
  const inputRef = useRef<HTMLInputElement>(null)
  const [busy, setBusy] = useState(false)
  const [dragging, setDragging] = useState(false)

  async function upload(file: File) {
    if (!ACCEPTED.includes(file.type)) {
      onError(t('organiser.cover.typeError'))
      return
    }
    if (file.size > MAX_BYTES) {
      onError(t('organiser.cover.sizeError'))
      return
    }
    setBusy(true)
    try {
      const asset = await uploadFile<{ id: number; publicUrl: string }>('/api/media/images', file)
      onChange(asset.publicUrl)
    } catch (e) {
      onError(resolveApiError(e, 'organiser.cover.uploadFailed').message)
    } finally {
      setBusy(false)
    }
  }

  function onDrop(event: DragEvent<HTMLDivElement>) {
    event.preventDefault()
    setDragging(false)
    const file = event.dataTransfer?.files?.[0]
    if (file) void upload(file)
  }

  return (
    <div className="field field-wide">
      <div className="field-head">
        <label htmlFor="f-cover">{t('organiser.cover.field')}</label>
        <span className="field-count">{t('organiser.cover.sizeHint')}</span>
      </div>

      <div
        className={`dropzone${dragging ? ' is-dragging' : ''}${value ? ' has-image' : ''}`}
        onDragOver={(e) => {
          e.preventDefault()
          setDragging(true)
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={onDrop}
      >
        {value ? (
          <img className="dropzone-preview" src={value} alt={t('organiser.cover.previewAlt')} />
        ) : (
          <span className="dropzone-hint">
            <ImageIcon />
            {t('organiser.cover.dropHint')}
          </span>
        )}
        {/* The real control stays in the tree (labelled, focusable, keyboard
            reachable); only its default chrome is hidden, and the styled
            button below forwards the click to it. */}
        <input
          id="f-cover"
          ref={inputRef}
          className="file-input"
          type="file"
          accept="image/*"
          disabled={busy}
          onChange={(e) => {
            const file = e.target.files?.[0]
            if (file) void upload(file)
          }}
        />
      </div>

      <div className="row dropzone-actions">
        <button type="button" className="btn-secondary btn-sm" disabled={busy} onClick={() => inputRef.current?.click()}>
          {value ? t('organiser.cover.replace') : t('organiser.cover.pickFile')}
        </button>
        {value && !busy && (
          <button type="button" className="btn-secondary btn-sm" onClick={() => onChange('')}>
            {t('organiser.cover.removeCover')}
          </button>
        )}
        {busy && <span className="muted small">{t('organiser.cover.uploading')}</span>}
      </div>
    </div>
  )
}
