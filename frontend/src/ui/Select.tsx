import { KeyboardEvent, useEffect, useId, useLayoutEffect, useRef, useState } from 'react'
import { ChevronDownIcon } from './Icons'

export interface SelectOption {
  value: string
  label: string
}

interface SelectProps {
  options: SelectOption[]
  value: string
  onChange: (value: string) => void
  id?: string
  /** Extra classes for the wrapper, e.g. `analytics-picker` sizing. */
  className?: string
  'aria-label'?: string
  'aria-invalid'?: boolean
  'aria-describedby'?: string
}

/**
 * Site-styled replacement for the native `<select>`.
 *
 * The OS popup ignores the design system (white sheet, rounded corners, wrong
 * font — worst in dark theme), so the component keeps a native-feeling trigger
 * and renders the option list itself: the ARIA "select-only combobox" pattern
 * with full keyboard parity — arrows, Home/End, Enter/Space, Escape, type-ahead.
 */
export function Select({
  options,
  value,
  onChange,
  id,
  className,
  'aria-label': ariaLabel,
  'aria-invalid': ariaInvalid,
  'aria-describedby': ariaDescribedBy,
}: SelectProps) {
  const uid = useId().replace(/[^a-zA-Z0-9-]/g, '')
  const menuId = `${uid}-menu`
  const optionId = (index: number) => `${uid}-opt-${index}`

  const rootRef = useRef<HTMLDivElement>(null)
  const triggerRef = useRef<HTMLButtonElement>(null)
  const activeRef = useRef<HTMLDivElement>(null)

  const [open, setOpen] = useState(false)
  const [activeIndex, setActiveIndex] = useState(0)
  const typedRef = useRef('')
  const typedTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)

  const selectedIndex = options.findIndex((option) => option.value === value)
  const selectedLabel = selectedIndex >= 0 ? options[selectedIndex].label : ''

  /** Opens the listbox with the highlight parked on the current choice. */
  function openMenu(initial = selectedIndex >= 0 ? selectedIndex : 0) {
    setActiveIndex(Math.max(0, Math.min(initial, options.length - 1)))
    setOpen(true)
  }

  /** Selects an option, closes, and keeps focus on the trigger. */
  function commit(index: number) {
    setOpen(false)
    if (options[index] && options[index].value !== value) onChange(options[index].value)
    triggerRef.current?.focus()
  }

  // Close on any pointer press outside the wrapper (the menu is inside it, so
  // option clicks still go through their own handler first).
  useEffect(() => {
    if (!open) return
    function onPointerDown(event: PointerEvent) {
      if (!rootRef.current?.contains(event.target as Node)) setOpen(false)
    }
    document.addEventListener('pointerdown', onPointerDown)
    return () => document.removeEventListener('pointerdown', onPointerDown)
  }, [open])

  // The selected/keyboard highlight may sit below the fold of long lists.
  useLayoutEffect(() => {
    if (open) activeRef.current?.scrollIntoView?.({ block: 'nearest' })
  }, [open, activeIndex])

  useEffect(() => () => clearTimeout(typedTimer.current), [])

  /** Prefix search over labels — what the native select does on letter keys. */
  function typeAhead(key: string) {
    const next = `${typedRef.current}${key}`.toLowerCase()
    typedRef.current = next
    clearTimeout(typedTimer.current)
    typedTimer.current = setTimeout(() => {
      typedRef.current = ''
    }, 400)
    const match = options.findIndex((option) => option.label.toLowerCase().startsWith(next))
    if (match < 0) return
    if (open) setActiveIndex(match)
    else openMenu(match)
  }

  function onKeyDown(event: KeyboardEvent<HTMLButtonElement>) {
    const last = options.length - 1
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault()
      if (open) commit(activeIndex)
      else openMenu()
      return
    }
    if (event.key === 'Escape') {
      if (open) setOpen(false)
      return
    }
    if (event.key === 'ArrowDown') {
      event.preventDefault()
      if (open) setActiveIndex((i) => Math.min(i + 1, last))
      else openMenu(Math.min((selectedIndex >= 0 ? selectedIndex : 0) + 1, last))
      return
    }
    if (event.key === 'ArrowUp') {
      event.preventDefault()
      if (open) setActiveIndex((i) => Math.max(i - 1, 0))
      else openMenu(Math.max((selectedIndex >= 0 ? selectedIndex : 0) - 1, 0))
      return
    }
    if (open && event.key === 'Home') {
      event.preventDefault()
      setActiveIndex(0)
      return
    }
    if (open && event.key === 'End') {
      event.preventDefault()
      setActiveIndex(last)
      return
    }
    if (keyIsPrintable(event)) {
      event.preventDefault()
      typeAhead(event.key)
    }
  }

  function onTriggerClick() {
    if (open) setOpen(false)
    else openMenu()
  }

  function onBlur() {
    setOpen(false)
    clearTimeout(typedTimer.current)
    typedRef.current = ''
  }

  return (
    <div ref={rootRef} className={`select${open ? ' is-open' : ''}${className ? ` ${className}` : ''}`}>
      <button
        ref={triggerRef}
        type="button"
        role="combobox"
        id={id}
        aria-label={ariaLabel}
        aria-invalid={ariaInvalid}
        aria-describedby={ariaDescribedBy}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        aria-activedescendant={open ? optionId(activeIndex) : undefined}
        className="select-trigger"
        onKeyDown={onKeyDown}
        onBlur={onBlur}
        onClick={onTriggerClick}
      >
        <span className="select-value">{selectedLabel}</span>
        <ChevronDownIcon className="select-chevron" />
      </button>
      {open && (
        <div role="listbox" id={menuId} aria-label={ariaLabel} className="select-menu">
          {options.map((option, index) => (
            <div
              key={option.value}
              id={optionId(index)}
              ref={index === activeIndex ? activeRef : undefined}
              role="option"
              data-value={option.value}
              aria-selected={option.value === value}
              className={`select-option${index === activeIndex ? ' is-active' : ''}${
                option.value === value ? ' is-selected' : ''
              }`}
              onMouseDown={(e) => e.preventDefault()}
              onClick={() => commit(index)}
            >
              {option.label}
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

/** One printable character, no modifiers — the input signal for type-ahead. */
function keyIsPrintable(event: KeyboardEvent) {
  return event.key.length === 1 && !event.ctrlKey && !event.metaKey && !event.altKey
}