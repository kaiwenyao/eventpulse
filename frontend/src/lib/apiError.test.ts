import { describe, expect, it } from 'vitest'
import { ApiError } from '../api'
import { resolveApiError } from './apiError'

describe('resolveApiError', () => {
  it('maps a known backend message to localised copy with a follow-up action', () => {
    // Arrange
    const error = new ApiError(409, 'Insufficient wallet balance')

    // Act
    const resolved = resolveApiError(error, 'cart.checkoutFailed')

    // Assert
    expect(resolved.message).toBe('钱包余额不足，无法完成本次支付。')
    expect(resolved.action).toEqual({ label: '去充值', to: '/profile' })
  })

  it('maps a known message that carries no action', () => {
    const resolved = resolveApiError(new ApiError(409, 'Booking already cancelled'), 'bookings.cancelFailed')

    expect(resolved.message).toBe('订单已经取消过了。')
    expect(resolved.action).toBeUndefined()
  })

  it('interpolates the number out of a message built by string concatenation', () => {
    const resolved = resolveApiError(new ApiError(400, 'Maximum 4 tickets per booking'), 'common.failed')

    expect(resolved.message).toBe('每单最多 4 张票。')
  })

  it('interpolates a quantity range', () => {
    const resolved = resolveApiError(new ApiError(400, 'Quantity must be between 1 and 6'), 'common.failed')

    expect(resolved.message).toBe('数量需在 1 到 6 之间。')
  })

  it('passes an unmapped backend message straight through rather than swallowing it', () => {
    const resolved = resolveApiError(new ApiError(400, 'Only drafts can be deleted'), 'common.failed')

    expect(resolved.message).toBe('Only drafts can be deleted')
    expect(resolved.action).toBeUndefined()
  })

  it('falls back to the caller key when the thrown value is not an ApiError', () => {
    const resolved = resolveApiError(new TypeError('network down'), 'bookings.cancelFailed')

    expect(resolved.message).toBe('取消失败')
  })

  it('falls back to the caller key when the backend sends an empty message', () => {
    const resolved = resolveApiError(new ApiError(500, ''), 'common.requestFailed')

    expect(resolved.message).toBe('请求失败')
  })
})
