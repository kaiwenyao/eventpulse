import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  AI_CONVERSATION_KEY,
  readStoredConversationId,
  writeStoredConversationId,
} from './aiConversation'

describe('aiConversation storage', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    localStorage.clear()
  })

  it('round-trips and clears the conversation id', () => {
    writeStoredConversationId('31')
    expect(localStorage.getItem(AI_CONVERSATION_KEY)).toBe('31')
    expect(readStoredConversationId()).toBe('31')

    writeStoredConversationId(null)
    expect(readStoredConversationId()).toBeNull()
  })

  it('survives a localStorage that throws', () => {
    // 隐私模式等场景下 getItem/setItem 会直接抛错。恢复不了上次的对话可以接受，
    // 因此而无法提问不行。
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('denied')
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('denied')
    })
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
      throw new Error('denied')
    })

    expect(readStoredConversationId()).toBeNull()
    expect(() => writeStoredConversationId('31')).not.toThrow()
    expect(() => writeStoredConversationId(null)).not.toThrow()
  })
})
