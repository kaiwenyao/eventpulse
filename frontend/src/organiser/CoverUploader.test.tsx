import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api'
import { ToastProvider } from '../ui/Toast'
import { CoverUploader } from './CoverUploader'

const uploadMock = vi.hoisted(() => vi.fn())
vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, uploadFile: uploadMock }
})

function renderUploader(value = '') {
  const onChange = vi.fn()
  const onError = vi.fn()
  render(
    <ToastProvider>
      <CoverUploader value={value} onChange={onChange} onError={onError} />
    </ToastProvider>,
  )
  return { onChange, onError }
}

function dropFile(file: File) {
  const zone = document.querySelector('.dropzone') as HTMLElement
  zone.dispatchEvent(new Event('dragover', { bubbles: true }))
  const drop = new Event('drop', { bubbles: true })
  Object.defineProperty(drop, 'dataTransfer', { value: { files: [file] } })
  zone.dispatchEvent(drop)
}

beforeEach(() => {
  uploadMock.mockReset()
})

describe('CoverUploader', () => {
  it('uploads an accepted image and reports the public URL', async () => {
    uploadMock.mockResolvedValue({ id: 1, publicUrl: '/api/media/images/1' })
    const { onChange, onError } = renderUploader()

    await userEvent.upload(screen.getByLabelText('封面'), new File(['x'], 'cover.png', { type: 'image/png' }))

    await waitFor(() => expect(onChange).toHaveBeenCalledWith('/api/media/images/1'))
    expect(onError).not.toHaveBeenCalled()
    expect(uploadMock).toHaveBeenCalledWith('/api/media/images', expect.any(File))
  })

  it('rejects an unsupported file type before hitting the network', async () => {
    // The file picker's `accept` filter already blocks non-images, so the
    // guard is exercised through the drop path, where nothing pre-filters.
    const { onError } = renderUploader()

    dropFile(new File(['x'], 'notes.pdf', { type: 'application/pdf' }))

    await waitFor(() => expect(onError).toHaveBeenCalledWith('只支持 PNG / JPEG / WebP / GIF 格式的封面图'))
    expect(uploadMock).not.toHaveBeenCalled()
  })

  it('rejects a file over the size cap before hitting the network', async () => {
    const { onError } = renderUploader()
    const huge = new File(['x'], 'huge.png', { type: 'image/png' })
    Object.defineProperty(huge, 'size', { value: 6 * 1024 * 1024 })

    await userEvent.upload(screen.getByLabelText('封面'), huge)

    await waitFor(() => expect(onError).toHaveBeenCalledWith('封面图不能超过 5 MB'))
    expect(uploadMock).not.toHaveBeenCalled()
  })

  it('surfaces a server-side upload failure', async () => {
    uploadMock.mockRejectedValue(new ApiError(413, '文件太大'))
    const { onError } = renderUploader()

    await userEvent.upload(screen.getByLabelText('封面'), new File(['x'], 'cover.png', { type: 'image/png' }))

    await waitFor(() => expect(onError).toHaveBeenCalledWith('文件太大'))
  })

  it('previews an existing cover and can clear it', async () => {
    const { onChange } = renderUploader('/api/media/images/9')
    expect(screen.getByAltText('活动封面预览')).toHaveAttribute('src', '/api/media/images/9')

    await userEvent.click(screen.getByRole('button', { name: '移除封面' }))
    expect(onChange).toHaveBeenCalledWith('')
  })

  it('accepts a dropped image', async () => {
    uploadMock.mockResolvedValue({ id: 2, publicUrl: '/api/media/images/2' })
    const { onChange } = renderUploader()

    dropFile(new File(['x'], 'drop.png', { type: 'image/png' }))

    await waitFor(() => expect(onChange).toHaveBeenCalledWith('/api/media/images/2'))
  })
})
