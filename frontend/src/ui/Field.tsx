import { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'

interface FieldProps {
  id: string
  label: string
  children: ReactNode
  required?: boolean
  hint?: string
  error?: string
  /** Renders a `12/200` counter next to the label. */
  counter?: { value: number; max: number }
  /** Spans both columns of a two-column form grid. */
  wide?: boolean
}

/**
 * Label + control + hint/error row.
 *
 * The `<label>` holds nothing but the label text, so its accessible name stays
 * exactly the field name; the required marker and counter sit beside it.
 */
export function Field({ id, label, children, required, hint, error, counter, wide }: FieldProps) {
  const { t } = useTranslation()
  const overLimit = counter ? counter.value > counter.max : false
  return (
    <div className={`field${wide ? ' field-wide' : ''}${error ? ' field-invalid' : ''}`}>
      <div className="field-head">
        <label htmlFor={id}>{label}</label>
        {required && <span className="field-req">{t('common.required')}</span>}
        {counter && (
          <span className={`field-count${overLimit ? ' over' : ''}`}>
            {counter.value}/{counter.max}
          </span>
        )}
      </div>
      {children}
      {error ? (
        <p className="field-error" id={`${id}-error`} role="alert">
          {error}
        </p>
      ) : (
        hint && (
          <p className="field-hint" id={`${id}-hint`}>
            {hint}
          </p>
        )
      )}
    </div>
  )
}

/**
 * ARIA wiring for the control inside a `<Field>` with the same `id`, so screen
 * readers announce the hint and the validation error.
 */
export function fieldAria(id: string, error?: string, hint?: string) {
  return {
    id,
    'aria-invalid': error ? true : undefined,
    'aria-describedby': error ? `${id}-error` : hint ? `${id}-hint` : undefined,
  }
}
