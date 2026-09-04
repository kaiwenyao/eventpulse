import { FormEvent, useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { NavLink, useNavigate, useParams } from 'react-router-dom'
import { api } from '../api'
import { CATEGORIES, EventVo } from '../types'
import { EmptyState, ErrorNote, EventStatusBadge } from '../ui/Badges'
import { Field, fieldAria } from '../ui/Field'
import { SkeletonCard } from '../ui/Skeleton'
import { useToast } from '../ui/Toast'
import { AiCopyAssistant } from './AiCopyAssistant'
import { CoverUploader } from './CoverUploader'
import { EventFormPreview } from './EventFormPreview'
import {
  createInitialForm,
  EventFormErrors,
  EventFormState,
  FIELD_LIMITS,
  formFromEvent,
  toOrganiserRequest,
  validateEventForm,
} from './eventForm'
import { Alert } from '../ui/Alert'
import { resolveApiError } from '../lib/apiError'

const CITY_SUGGESTIONS = ['Berlin', 'London', 'Paris', 'New York', 'Toronto', 'Tokyo', 'Melbourne', 'Sao Paulo']

const FIELD_LABEL_KEYS: Partial<Record<keyof EventFormState, string>> = {
  title: 'organiser.form.title',
  summary: 'organiser.form.summary',
  category: 'organiser.form.category',
  city: 'organiser.form.city',
  venueName: 'organiser.form.venue',
  address: 'organiser.form.address',
  startsAt: 'organiser.form.startsAt',
  endsAt: 'organiser.form.endsAt',
  salesEndAt: 'organiser.form.salesEnd',
  priceEuro: 'organiser.form.price',
  capacity: 'organiser.form.capacity',
  maxQuantityPerBooking: 'organiser.form.limit',
  contactInfo: 'organiser.form.contact',
}

export function OrganiserFormPage() {
  const { t } = useTranslation()
  const { id } = useParams()
  const navigate = useNavigate()
  const { notify } = useToast()

  const [form, setForm] = useState<EventFormState>(createInitialForm)
  const [errors, setErrors] = useState<EventFormErrors>({})
  const [submitted, setSubmitted] = useState(false)
  const [loading, setLoading] = useState(Boolean(id))
  const [loadError, setLoadError] = useState('')
  const [error, setError] = useState('')
  const [saving, setSaving] = useState<'draft' | 'publish' | null>(null)
  const [existing, setExisting] = useState<EventVo | null>(null)

  useEffect(() => {
    if (!id) return
    api<EventVo>('GET', `/api/organiser/events/${id}`)
      .then((event) => {
        setExisting(event)
        setForm(formFromEvent(event))
      })
      .catch((e) => setLoadError(resolveApiError(e, 'organiser.form.loadEventFailed').message))
      .finally(() => setLoading(false))
  }, [id, t])

  /** Immutable field update; re-validates only after the first submit attempt. */
  function update<K extends keyof EventFormState>(key: K, value: EventFormState[K]) {
    const next = { ...form, [key]: value }
    setForm(next)
    if (submitted) setErrors(validateEventForm(next))
  }

  const errorList = useMemo(
    () =>
      (Object.keys(errors) as (keyof EventFormState)[])
        .filter((key) => errors[key])
        .map((key) => ({ key, label: FIELD_LABEL_KEYS[key] ? t(FIELD_LABEL_KEYS[key]!) : String(key), message: errors[key]! })),
    [errors, t],
  )

  async function submit(publish: boolean) {
    setSubmitted(true)
    setError('')
    const found = validateEventForm(form)
    setErrors(found)
    if (Object.keys(found).length > 0) {
      notify(t('organiser.form.incomplete'), 'error')
      return
    }
    setSaving(publish ? 'publish' : 'draft')
    try {
      const body = toOrganiserRequest(form, { publish, version: existing?.version })
      if (id) await api('PUT', `/api/organiser/events/${id}`, body)
      else await api('POST', '/api/organiser/events', body)
      notify(publish ? t('organiser.form.published') : t('organiser.form.draftSaved'), 'success')
      navigate('/organiser/events')
    } catch (e) {
      const { message } = resolveApiError(e, 'common.failed')
      setError(message)
      notify(t('organiser.form.saveFailed', { message }), 'error')
    } finally {
      setSaving(null)
    }
  }

  function onSubmit(event: FormEvent) {
    event.preventDefault()
    void submit(false)
  }

  if (loadError) return <EmptyState title={loadError} hint={t('organiser.editOwnOnly')} />

  const busy = saving !== null

  return (
    <div className="page form-page">
      <nav className="crumbs">
        <NavLink to="/organiser">{t('nav.console')}</NavLink>
        <span aria-hidden>/</span>
        <NavLink to="/organiser/events">{t('organiser.events')}</NavLink>
        <span aria-hidden>/</span>
        <span>{id ? t('organiser.form.edit') : t('organiser.form.create')}</span>
      </nav>

      <header className="page-head">
        <div>
          <h1>{id ? t('organiser.form.edit') : t('organiser.form.create')}</h1>
          <p className="muted">{t('organiser.form.intro')}</p>
        </div>
        <div className="page-head-actions">
          {existing && <EventStatusBadge status={existing.status} />}
          <AiCopyAssistant
            form={form}
            eventId={id}
            onApply={(patch) => {
              setForm((prev) => ({ ...prev, ...patch }))
              notify(t('ai.copy.applied'), 'success')
            }}
          />
        </div>
      </header>

      {loading ? (
        <SkeletonCard />
      ) : (
        <form className="form-layout" onSubmit={onSubmit} noValidate>
          <div className="form-columns">
            <div className="form-fields">
              {submitted && errorList.length > 0 && (
                <Alert tone="error" title={t('organiser.form.fixCount', { count: errorList.length })}>
                  <ul>
                    {errorList.map((item) => (
                      <li key={String(item.key)}>
                        {item.label}：{item.message}
                      </li>
                    ))}
                  </ul>
                </Alert>
              )}

              <ErrorNote message={error} />

              <fieldset className="form-section">
                <legend>{t('organiser.form.basics')}</legend>
                <div className="form-grid">
                  <Field
                    id="f-title"
                    label={t('organiser.form.title')}
                    required
                    wide
                    error={errors.title}
                    hint={t('organiser.form.titleHint')}
                    counter={{ value: form.title.length, max: FIELD_LIMITS.title }}
                  >
                    <input
                      {...fieldAria('f-title', errors.title, 'hint')}
                      value={form.title}
                      onChange={(e) => update('title', e.target.value)}
                    />
                  </Field>

                  <Field
                    id="f-summary"
                    label={t('organiser.form.summary')}
                    wide
                    error={errors.summary}
                    hint={t('organiser.form.summaryHint')}
                    counter={{ value: form.summary.length, max: FIELD_LIMITS.summary }}
                  >
                    <input
                      {...fieldAria('f-summary', errors.summary, 'hint')}
                      value={form.summary}
                      onChange={(e) => update('summary', e.target.value)}
                    />
                  </Field>

                  <Field id="f-desc" label={t('organiser.form.description')} wide hint={t('organiser.form.descriptionHint')}>
                    <textarea
                      {...fieldAria('f-desc', undefined, 'hint')}
                      rows={6}
                      value={form.description}
                      onChange={(e) => update('description', e.target.value)}
                    />
                  </Field>

                  <Field id="f-cat" label={t('organiser.form.category')} required error={errors.category} hint={t('organiser.form.categoryHint')}>
                    <input
                      {...fieldAria('f-cat', errors.category, 'hint')}
                      list="category-options"
                      value={form.category}
                      onChange={(e) => update('category', e.target.value)}
                    />
                    <datalist id="category-options">
                      {CATEGORIES.map((c) => (
                        <option key={c.key} value={c.key}>
                          {t(`category.${c.key}`)}
                        </option>
                      ))}
                    </datalist>
                  </Field>
                </div>
              </fieldset>

              <fieldset className="form-section">
                <legend>{t('organiser.form.schedule')}</legend>
                <div className="form-grid">
                  <Field id="f-starts" label={t('organiser.form.startsAt')} required error={errors.startsAt} hint={t('organiser.form.startsHint')}>
                    <input
                      {...fieldAria('f-starts', errors.startsAt, 'hint')}
                      type="datetime-local"
                      value={form.startsAt}
                      onChange={(e) => update('startsAt', e.target.value)}
                    />
                  </Field>
                  <Field id="f-ends" label={t('organiser.form.endsAt')} error={errors.endsAt} hint={t('organiser.form.endsHint')}>
                    <input
                      {...fieldAria('f-ends', errors.endsAt, 'hint')}
                      type="datetime-local"
                      value={form.endsAt}
                      onChange={(e) => update('endsAt', e.target.value)}
                    />
                  </Field>
                  <Field id="f-sales-start" label={t('organiser.form.salesStart')} hint={t('organiser.form.salesStartHint')}>
                    <input
                      {...fieldAria('f-sales-start', undefined, 'hint')}
                      type="datetime-local"
                      value={form.salesStartAt}
                      onChange={(e) => update('salesStartAt', e.target.value)}
                    />
                  </Field>
                  <Field id="f-sales-end" label={t('organiser.form.salesEnd')} error={errors.salesEndAt} hint={t('organiser.form.salesEndHint')}>
                    <input
                      {...fieldAria('f-sales-end', errors.salesEndAt, 'hint')}
                      type="datetime-local"
                      value={form.salesEndAt}
                      onChange={(e) => update('salesEndAt', e.target.value)}
                    />
                  </Field>
                </div>
              </fieldset>

              <fieldset className="form-section">
                <legend>{t('organiser.form.place')}</legend>
                <div className="form-grid">
                  <Field id="f-city" label={t('organiser.form.city')} required error={errors.city}>
                    <input
                      {...fieldAria('f-city', errors.city)}
                      list="city-options"
                      value={form.city}
                      onChange={(e) => update('city', e.target.value)}
                    />
                    <datalist id="city-options">
                      {CITY_SUGGESTIONS.map((city) => (
                        <option key={city} value={city} />
                      ))}
                    </datalist>
                  </Field>
                  <Field id="f-venue" label={t('organiser.form.venue')} error={errors.venueName} hint={t('organiser.form.venueHint')}>
                    <input
                      {...fieldAria('f-venue', errors.venueName, 'hint')}
                      value={form.venueName}
                      onChange={(e) => update('venueName', e.target.value)}
                    />
                  </Field>
                  <Field id="f-address" label={t('organiser.form.address')} wide error={errors.address}>
                    <input
                      {...fieldAria('f-address', errors.address)}
                      value={form.address}
                      onChange={(e) => update('address', e.target.value)}
                    />
                  </Field>
                </div>
              </fieldset>

              <fieldset className="form-section">
                <legend>{t('organiser.form.tickets')}</legend>
                <div className="form-grid">
                  <Field id="f-price" label={t('organiser.form.price')} required error={errors.priceEuro} hint={t('organiser.form.priceHint')}>
                    <input
                      {...fieldAria('f-price', errors.priceEuro, 'hint')}
                      type="number"
                      min={0}
                      step="0.01"
                      inputMode="decimal"
                      value={form.priceEuro}
                      onChange={(e) => update('priceEuro', e.target.value)}
                    />
                  </Field>
                  <Field id="f-cap" label={t('organiser.form.capacity')} required error={errors.capacity} hint={t('organiser.form.capacityHint')}>
                    <input
                      {...fieldAria('f-cap', errors.capacity, 'hint')}
                      type="number"
                      min={1}
                      value={form.capacity}
                      onChange={(e) => update('capacity', e.target.value)}
                    />
                  </Field>
                  <Field
                    id="f-max-qty"
                    label={t('organiser.form.limit')}
                    error={errors.maxQuantityPerBooking}
                    hint={t('organiser.form.limitHint')}
                  >
                    <input
                      {...fieldAria('f-max-qty', errors.maxQuantityPerBooking, 'hint')}
                      type="number"
                      min={1}
                      value={form.maxQuantityPerBooking}
                      onChange={(e) => update('maxQuantityPerBooking', e.target.value)}
                    />
                  </Field>
                </div>
              </fieldset>

              <fieldset className="form-section">
                <legend>{t('organiser.form.media')}</legend>
                <div className="form-grid">
                  <CoverUploader
                    value={form.coverUrl}
                    onChange={(url) => update('coverUrl', url)}
                    onError={(message) => notify(message, 'error')}
                  />
                  <Field id="f-contact" label={t('organiser.form.contact')} error={errors.contactInfo} hint={t('organiser.form.contactHint')}>
                    <input
                      {...fieldAria('f-contact', errors.contactInfo, 'hint')}
                      value={form.contactInfo}
                      onChange={(e) => update('contactInfo', e.target.value)}
                    />
                  </Field>
                  <Field id="f-notes" label={t('organiser.form.notes')} wide hint={t('organiser.form.notesHint')}>
                    <textarea
                      {...fieldAria('f-notes', undefined, 'hint')}
                      rows={4}
                      value={form.attendanceNotes}
                      onChange={(e) => update('attendanceNotes', e.target.value)}
                    />
                  </Field>
                </div>
              </fieldset>
            </div>

            <EventFormPreview form={form} />
          </div>

          <div className="form-actions">
            <NavLink to="/organiser/events" className="btn-secondary btn-link">
              {t('organiser.form.backList')}
            </NavLink>
            <span className="form-actions-spacer" />
            <button type="submit" className="btn-secondary" disabled={busy}>
              {saving === 'draft' ? t('organiser.form.savingDraft') : t('organiser.form.saveDraft')}
            </button>
            <button type="button" className="btn-primary" disabled={busy} onClick={() => void submit(true)}>
              {saving === 'publish' ? t('organiser.form.publishing') : t('organiser.form.publish')}
            </button>
          </div>
        </form>
      )}
    </div>
  )
}
