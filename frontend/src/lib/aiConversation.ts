/**
 * AI 发现助手的会话 id 存取。
 *
 * 单独放在 lib 里而不是组件内：登出时 auth.tsx 也要清掉它，而助手组件本身
 * 依赖 auth，写在组件里会形成循环依赖。
 *
 * localStorage 在隐私模式等场景下可能直接抛错，所以两个方向都吞掉异常 ——
 * 最坏结果只是刷新后恢复不了上次的对话，不该影响提问本身。
 */
export const AI_CONVERSATION_KEY = 'ep_ai_conversation'

export function readStoredConversationId(): string | null {
  try {
    return localStorage.getItem(AI_CONVERSATION_KEY)
  } catch {
    return null
  }
}

export function writeStoredConversationId(id: string | null): void {
  try {
    if (id) localStorage.setItem(AI_CONVERSATION_KEY, id)
    else localStorage.removeItem(AI_CONVERSATION_KEY)
  } catch {
    // 存不下就只是这次刷新后恢复不了。
  }
}
