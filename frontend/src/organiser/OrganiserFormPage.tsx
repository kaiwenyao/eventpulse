import { FormEvent, useEffect, useMemo, useState } from 'react'
import { NavLink, useNavigate, useParams } from 'react-router-dom'
import { api, ApiError } from '../api'
import { CATEGORIES, EventVo } from '../types'
import { EmptyState, ErrorNote, EventStatusBadge } from '../ui/Badges'
import { Field, fieldAria } from '../ui/Field'
import { SkeletonCard } from '../ui/Skeleton'
import { useToast } from '../ui/Toast'
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

const CITY_SUGGESTIONS = ['上海', '北京', '广州', '深圳', '成都', '杭州', '武汉', '西安']

const FIELD_LABELS: Partial<Record<keyof EventFormState, string>> = {
  title: '标题',
  summary: '摘要',
  category: '分类',
  city: '城市',
  venueName: '场地',
  address: '详细地址',
  startsAt: '开始时间',
  endsAt: '结束时间',
  salesEndAt: '停售时间',
  priceYuan: '票价（元）',
  capacity: '容量',
  maxQuantityPerBooking: '单笔限购',
  contactInfo: '联系方式',
}

export function OrganiserFormPage() {
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
      .catch((e) => setLoadError(e instanceof ApiError ? e.message : '加载活动失败'))
      .finally(() => setLoading(false))
  }, [id])

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
        .map((key) => ({ key, label: FIELD_LABELS[key] ?? key, message: errors[key]! })),
    [errors],
  )

  async function submit(publish: boolean) {
    setSubmitted(true)
    setError('')
    const found = validateEventForm(form)
    setErrors(found)
    if (Object.keys(found).length > 0) {
      notify('还有必填项没有填好，请检查表单', 'error')
      return
    }
    setSaving(publish ? 'publish' : 'draft')
    try {
      const body = toOrganiserRequest(form, { publish, version: existing?.version })
      if (id) await api('PUT', `/api/organiser/events/${id}`, body)
      else await api('POST', '/api/organiser/events', body)
      notify(publish ? '活动已发布' : '草稿已保存', 'success')
      navigate('/organiser/events')
    } catch (e) {
      const message = e instanceof ApiError ? e.message : '保存失败'
      setError(message)
      notify(`保存失败：${message}`, 'error')
    } finally {
      setSaving(null)
    }
  }

  function onSubmit(event: FormEvent) {
    event.preventDefault()
    void submit(false)
  }

  if (loadError) return <EmptyState title={loadError} hint="只能编辑属于自己的活动。" />

  const busy = saving !== null

  return (
    <div className="page form-page">
      <nav className="crumbs">
        <NavLink to="/organiser">工作台</NavLink>
        <span aria-hidden>/</span>
        <NavLink to="/organiser/events">活动管理</NavLink>
        <span aria-hidden>/</span>
        <span>{id ? '编辑活动' : '新建活动'}</span>
      </nav>

      <header className="page-head">
        <div>
          <h1>{id ? '编辑活动' : '新建活动'}</h1>
          <p className="muted">
            填写基本信息后可先保存草稿，确认无误再发布；发布后观众即可在发现页看到这场活动。
          </p>
        </div>
        {existing && <EventStatusBadge status={existing.status} />}
      </header>

      {loading ? (
        <SkeletonCard />
      ) : (
        <form className="form-layout" onSubmit={onSubmit} noValidate>
          <div className="form-columns">
            <div className="form-fields">
              {submitted && errorList.length > 0 && (
                <div className="callout callout-error" role="alert">
                  <p className="callout-title">还有 {errorList.length} 项需要修改</p>
                  <ul>
                    {errorList.map((item) => (
                      <li key={String(item.key)}>
                        {item.label}：{item.message}
                      </li>
                    ))}
                  </ul>
                </div>
              )}

              <ErrorNote message={error} />

              <fieldset className="form-section">
                <legend>基本信息</legend>
                <div className="form-grid">
                  <Field
                    id="f-title"
                    label="标题"
                    required
                    wide
                    error={errors.title}
                    hint="一句话说清是什么活动，会显示在列表和详情页。"
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
                    label="摘要"
                    wide
                    error={errors.summary}
                    hint="列表卡片上的补充说明，留空则不显示。"
                    counter={{ value: form.summary.length, max: FIELD_LIMITS.summary }}
                  >
                    <input
                      {...fieldAria('f-summary', errors.summary, 'hint')}
                      value={form.summary}
                      onChange={(e) => update('summary', e.target.value)}
                    />
                  </Field>

                  <Field id="f-desc" label="介绍" wide hint="活动亮点、流程、嘉宾阵容等详细说明。">
                    <textarea
                      {...fieldAria('f-desc', undefined, 'hint')}
                      rows={6}
                      value={form.description}
                      onChange={(e) => update('description', e.target.value)}
                    />
                  </Field>

                  <Field id="f-cat" label="分类" required error={errors.category} hint="可选内置分类，也能自定义。">
                    <input
                      {...fieldAria('f-cat', errors.category, 'hint')}
                      list="category-options"
                      value={form.category}
                      onChange={(e) => update('category', e.target.value)}
                    />
                    <datalist id="category-options">
                      {CATEGORIES.map((c) => (
                        <option key={c.key} value={c.key}>
                          {c.label}
                        </option>
                      ))}
                    </datalist>
                  </Field>
                </div>
              </fieldset>

              <fieldset className="form-section">
                <legend>时间安排</legend>
                <div className="form-grid">
                  <Field id="f-starts" label="开始时间" required error={errors.startsAt} hint="按场地当地时间填写。">
                    <input
                      {...fieldAria('f-starts', errors.startsAt, 'hint')}
                      type="datetime-local"
                      value={form.startsAt}
                      onChange={(e) => update('startsAt', e.target.value)}
                    />
                  </Field>
                  <Field id="f-ends" label="结束时间" error={errors.endsAt} hint="留空表示不限定结束时间。">
                    <input
                      {...fieldAria('f-ends', errors.endsAt, 'hint')}
                      type="datetime-local"
                      value={form.endsAt}
                      onChange={(e) => update('endsAt', e.target.value)}
                    />
                  </Field>
                  <Field id="f-sales-start" label="开售时间" hint="留空表示保存后立即可购票。">
                    <input
                      {...fieldAria('f-sales-start', undefined, 'hint')}
                      type="datetime-local"
                      value={form.salesStartAt}
                      onChange={(e) => update('salesStartAt', e.target.value)}
                    />
                  </Field>
                  <Field id="f-sales-end" label="停售时间" error={errors.salesEndAt} hint="不能晚于活动开始时间。">
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
                <legend>地点</legend>
                <div className="form-grid">
                  <Field id="f-city" label="城市" required error={errors.city}>
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
                  <Field id="f-venue" label="场地" error={errors.venueName} hint="例如：上海梅赛德斯-奔驰文化中心。">
                    <input
                      {...fieldAria('f-venue', errors.venueName, 'hint')}
                      value={form.venueName}
                      onChange={(e) => update('venueName', e.target.value)}
                    />
                  </Field>
                  <Field id="f-address" label="详细地址" wide error={errors.address}>
                    <input
                      {...fieldAria('f-address', errors.address)}
                      value={form.address}
                      onChange={(e) => update('address', e.target.value)}
                    />
                  </Field>
                </div>
              </fieldset>

              <fieldset className="form-section">
                <legend>票务</legend>
                <div className="form-grid">
                  <Field id="f-price" label="票价（元）" required error={errors.priceYuan} hint="免费活动填 0。">
                    <input
                      {...fieldAria('f-price', errors.priceYuan, 'hint')}
                      type="number"
                      min={0}
                      step="0.01"
                      inputMode="decimal"
                      value={form.priceYuan}
                      onChange={(e) => update('priceYuan', e.target.value)}
                    />
                  </Field>
                  <Field id="f-cap" label="容量" required error={errors.capacity} hint="总放票数量，售完即止。">
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
                    label="单笔限购"
                    error={errors.maxQuantityPerBooking}
                    hint="留空表示使用平台默认上限。"
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
                <legend>媒体与观众须知</legend>
                <div className="form-grid">
                  <CoverUploader
                    value={form.coverUrl}
                    onChange={(url) => update('coverUrl', url)}
                    onError={(message) => notify(message, 'error')}
                  />
                  <Field id="f-contact" label="联系方式" error={errors.contactInfo} hint="观众遇到问题时如何联系主办方。">
                    <input
                      {...fieldAria('f-contact', errors.contactInfo, 'hint')}
                      value={form.contactInfo}
                      onChange={(e) => update('contactInfo', e.target.value)}
                    />
                  </Field>
                  <Field id="f-notes" label="参与须知" wide hint="入场规则、退改政策、携带物品等。">
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
              返回列表
            </NavLink>
            <span className="form-actions-spacer" />
            <button type="submit" className="btn-secondary" disabled={busy}>
              {saving === 'draft' ? '保存中…' : '保存草稿'}
            </button>
            <button type="button" className="btn-primary" disabled={busy} onClick={() => void submit(true)}>
              {saving === 'publish' ? '发布中…' : '发布活动'}
            </button>
          </div>
        </form>
      )}
    </div>
  )
}
