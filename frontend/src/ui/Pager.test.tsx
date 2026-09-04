import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { Pager } from './Pager'

function renderPager(page: number, totalPages: number, onChange = vi.fn()) {
  render(<Pager page={page} totalPages={totalPages} total={23} ariaLabel="订单分页" onChange={onChange} />)
  return onChange
}

describe('Pager', () => {
  it('reports the current page and the total row count', () => {
    renderPager(1, 3)

    expect(screen.getByText('第 2 / 3 页')).toBeInTheDocument()
    expect(screen.getByText('共 23 条')).toBeInTheDocument()
  })

  it('steps forward and back through pages', async () => {
    const onChange = renderPager(1, 3)

    await userEvent.click(screen.getByRole('button', { name: '下一页' }))
    expect(onChange).toHaveBeenLastCalledWith(2)

    await userEvent.click(screen.getByRole('button', { name: '上一页' }))
    expect(onChange).toHaveBeenLastCalledWith(0)
  })

  it('disables both ends when there is a single page', () => {
    renderPager(0, 1)

    expect(screen.getByRole('button', { name: '上一页' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '下一页' })).toBeDisabled()
  })

  it('never steps below the first page', async () => {
    const onChange = renderPager(0, 3)

    expect(screen.getByRole('button', { name: '上一页' })).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: '下一页' }))
    expect(onChange).toHaveBeenLastCalledWith(1)
  })
})
