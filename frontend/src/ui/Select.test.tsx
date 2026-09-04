import { useState } from 'react'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { Select } from './Select'

const OPTIONS = [
  { value: '', label: '全部分类' },
  { value: 'music', label: '音乐' },
  { value: 'sports', label: '运动' },
  { value: 'tech', label: '科技' },
]

/** The component is fully controlled, so the demo keeps the chosen value. */
function renderSelect(initial = '', onChange = vi.fn()) {
  function Demo() {
    const [value, setValue] = useState(initial)
    return (
      <Select
        aria-label="分类"
        options={OPTIONS}
        value={value}
        onChange={(next) => {
          setValue(next)
          onChange(next)
        }}
      />
    )
  }
  render(<Demo />)
  return onChange
}

/** The value behind aria-activedescendant — i.e. the highlighted option. */
function activeValue() {
  const id = screen.getByRole('combobox').getAttribute('aria-activedescendant')
  return id ? document.getElementById(id)?.dataset.value : undefined
}

describe('Select', () => {
  it('shows the chosen label on the trigger and no popup while closed', () => {
    renderSelect('sports')

    const trigger = screen.getByRole('combobox', { name: '分类' })
    expect(trigger).toHaveTextContent('运动')
    expect(trigger).toHaveAttribute('aria-expanded', 'false')
    // Inside the organiser create form it must never submit on Enter.
    expect(trigger).toHaveAttribute('type', 'button')
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
  })

  it('opens on click and lists the options, marking the current one', async () => {
    renderSelect('music')
    await userEvent.click(screen.getByRole('combobox', { name: '分类' }))

    expect(screen.getByRole('combobox')).toHaveAttribute('aria-expanded', 'true')
    const options = within(screen.getByRole('listbox')).getAllByRole('option')
    expect(options.map((o) => (o as HTMLElement).dataset.value)).toEqual(['', 'music', 'sports', 'tech'])
    expect(options[1]).toHaveAttribute('aria-selected', 'true')
    expect(options[0]).toHaveAttribute('aria-selected', 'false')
  })

  it('picks an option on click, closes, and reports the value', async () => {
    const onChange = renderSelect()
    await userEvent.click(screen.getByRole('combobox', { name: '分类' }))

    await userEvent.click(screen.getByRole('option', { name: '科技' }))

    expect(onChange).toHaveBeenLastCalledWith('tech')
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
    expect(screen.getByRole('combobox')).toHaveTextContent('科技')
  })

  it('opens with Enter, moves with arrows, commits with Enter', async () => {
    const onChange = renderSelect('music')
    const trigger = screen.getByRole('combobox')
    trigger.focus()

    await userEvent.keyboard('{Enter}')
    expect(screen.getByRole('listbox')).toBeInTheDocument()
    // Opens on the current choice; one ArrowDown highlights the next option.
    expect(activeValue()).toBe('music')

    await userEvent.keyboard('{ArrowDown}')
    expect(activeValue()).toBe('sports')

    await userEvent.keyboard('{Enter}')
    expect(onChange).toHaveBeenLastCalledWith('sports')
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
  })

  it('clamps the highlight at both ends and jumps with Home/End', async () => {
    renderSelect()
    await userEvent.click(screen.getByRole('combobox', { name: '分类' }))

    await userEvent.keyboard('{ArrowUp}')
    expect(activeValue()).toBe('')

    await userEvent.keyboard('{End}')
    expect(activeValue()).toBe('tech')
    await userEvent.keyboard('{ArrowDown}')
    expect(activeValue()).toBe('tech')

    await userEvent.keyboard('{Home}')
    expect(activeValue()).toBe('')
  })

  it('closes on Escape without changing the value', async () => {
    const onChange = renderSelect('music')
    await userEvent.click(screen.getByRole('combobox', { name: '分类' }))

    await userEvent.keyboard('{ArrowDown}')
    await userEvent.keyboard('{Escape}')

    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
    expect(onChange).not.toHaveBeenCalled()
    expect(screen.getByRole('combobox')).toHaveTextContent('音乐')
  })

  it('closes on a click outside without changing the value', async () => {
    const onChange = renderSelect('music')
    render(<button>elsewhere</button>)
    await userEvent.click(screen.getByRole('combobox', { name: '分类' }))

    await userEvent.click(screen.getByRole('button', { name: 'elsewhere' }))

    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
    expect(onChange).not.toHaveBeenCalled()
  })

  it('jumps to the first matching option as the user types', async () => {
    const onChange = renderSelect()
    screen.getByRole('combobox').focus()

    await userEvent.keyboard('运')

    // Typing opens the menu with the highlight on the match.
    expect(screen.getByRole('listbox')).toBeInTheDocument()
    expect(activeValue()).toBe('sports')

    await userEvent.keyboard('{Enter}')
    expect(onChange).toHaveBeenLastCalledWith('sports')
  })
})