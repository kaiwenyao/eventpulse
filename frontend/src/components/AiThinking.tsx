import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'

/** 每条思考状态停留的毫秒数；到最后一条就停住，不循环回第一条。 */
const STAGE_INTERVAL_MS = 2600
/** 到第几拍补一句「还在处理」，避免用户以为界面卡死（约 13 秒）。 */
const STILL_WORKING_TICK = 5
/** 计数上限：到顶后 setState 返回同值，React 直接跳过重渲染。 */
const MAX_TICK = STILL_WORKING_TICK

const DISCOVERY_STAGES = [
  'ai.discovery.thinkingSearching',
  'ai.discovery.thinkingReading',
  'ai.discovery.thinkingWriting',
]

interface AiThinkingProps {
  /** 依次展示的 i18n key；默认是活动发现助手的三段。 */
  stageKeys?: string[]
}

/**
 * 等待 AI 结果时的进度反馈。
 *
 * 一轮请求最长可能十几秒；只放一个骨架屏看不出系统在做什么，用户会以为
 * 卡住了。这里按时间推进展示阶段文案（不是真实进度，所以措辞保持笼统），
 * 并用 role="status" + aria-live 让读屏用户同样收到反馈。
 *
 * 组件随 loading 挂载/卸载，所以只需要一个从 0 开始的计时器，不必重置状态。
 */
export function AiThinking({ stageKeys = DISCOVERY_STAGES }: AiThinkingProps) {
  const { t } = useTranslation()
  const [tick, setTick] = useState(0)

  useEffect(() => {
    const id = window.setInterval(
      () => setTick((n) => (n >= MAX_TICK ? n : n + 1)),
      STAGE_INTERVAL_MS,
    )
    return () => window.clearInterval(id)
  }, [])

  const stage = Math.min(tick, stageKeys.length - 1)

  return (
    <div className="ai-thinking-block" role="status" aria-live="polite">
      <p className="ai-thinking">
        <span className="ai-thinking-dots" aria-hidden>
          <span />
          <span />
          <span />
        </span>
        <span className="ai-thinking-label">{t(stageKeys[stage])}</span>
      </p>
      {tick >= STILL_WORKING_TICK && (
        <p className="muted small ai-thinking-slow">{t('ai.discovery.stillWorking')}</p>
      )}
    </div>
  )
}

/** 发现助手里的思考态：套上和其他对话轮一致的角色标签与竖线。 */
export function AiThinkingTurn() {
  const { t } = useTranslation()
  return (
    <div className="ai-turn ai-turn-assistant ai-turn-thinking">
      <p className="ai-turn-role">{t('ai.discovery.assistant')}</p>
      <AiThinking />
    </div>
  )
}
