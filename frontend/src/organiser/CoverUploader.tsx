import { DragEvent, useRef, useState } from 'react'
import { ApiError, uploadFile } from '../api'
import { ImageIcon } from '../ui/Icons'

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
  const inputRef = useRef<HTMLInputElement>(null)
  const [busy, setBusy] = useState(false)
  const [dragging, setDragging] = useState(false)

  async function upload(file: File) {
    if (!ACCEPTED.includes(file.type)) {
      onError('只支持 PNG / JPEG / WebP / GIF 格式的封面图')
      return
    }
    if (file.size > MAX_BYTES) {
      onError('封面图不能超过 5 MB')
      return
    }
    setBusy(true)
    try {
      const asset = await uploadFile<{ id: number; publicUrl: string }>('/api/media/images', file)
      onChange(asset.publicUrl)
    } catch (e) {
      onError(e instanceof ApiError ? e.message : '封面上传失败')
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
        <label htmlFor="f-cover">封面</label>
        <span className="field-count">建议 1200×675，≤ 5 MB</span>
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
          <img className="dropzone-preview" src={value} alt="活动封面预览" />
        ) : (
          <span className="dropzone-hint">
            <ImageIcon />
            拖拽图片到这里，或点击下方按钮选择文件
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
          {value ? '更换封面' : '选择文件'}
        </button>
        {value && !busy && (
          <button type="button" className="btn-secondary btn-sm" onClick={() => onChange('')}>
            移除封面
          </button>
        )}
        {busy && <span className="muted small">上传中…</span>}
      </div>
    </div>
  )
}
