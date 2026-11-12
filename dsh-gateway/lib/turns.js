/**
 * 每轮对话结束的事实源：把宿主的 `session/event · turn/end` 变成一次
 * `turn.completed` 广播（topic = `global`，所有设备都能收到）。
 *
 * 为什么不复用已有的 `session.status`（`running: true → false`）：
 *
 *   1. `session.status` 是**状态快照**，不是「这一轮刚刚结束」。网关重启后会从
 *      `state.json` 恢复 `running: true`（崩溃前那个会话确实在跑），而 DSH 侧
 *      早就结束了；下一次任何来源的 `status(false)` 都会让客户端把很久以前的
 *      结束当成「刚跑完」——这正是「连着别的对话干活时，突然收到早已结束的
 *      对话的完成提醒」的成因。
 *   2. 断线补齐、`resync` 后重新应用历史事件时，状态翻转同样会重放一遍。
 *   3. `api-session/status` 只对「状态变化」发事件，抓不到「一直空闲的会话又
 *      跑了一轮」这种不改变 running 形状的边界（例如同一轮内的多 step 收尾）。
 *
 * `turn/end` 则是 DSH 会话日志里**每轮一次**的持久事件，语义就是「这一轮结束」。
 * 它由宿主事件总线（`ctx.on('session/event')`）推送，覆盖进程内所有会话，既不需要
 * 为每个会话开 `session/follow` 流，也不依赖设备当前在看哪个对话。
 *
 * 重放安全交给客户端的两道闸（见 APK `GatewayRepository`）：
 *   - `seq ≤ ready.seq` 的补发事件不算实时；
 *   - 帧时间戳（本模块用发布时刻 `ts`）超过新鲜度窗口的不提醒。
 * 本模块自己只做「子会话识别」与「载荷整形」，不做时间过滤——宿主时间与设备
 * 时钟的偏差只有客户端能校正（`ready.serverTime`）。
 *
 * @module dsh-gateway/lib/turns
 */

/** 广播 kind：一轮对话结束。 */
export const TURN_COMPLETED_KIND = 'turn.completed'

/** 子会话 id 记忆上限（防止长时间运行后无限增长）。 */
const SUBAGENT_ID_MAX = 500

/** @returns {boolean} 是否为普通对象。 */
function isRecord(value) {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/** 宽松取字符串（数字也接受），空串归一为 ''. */
function text(value) {
  if (typeof value === 'string') return value
  if (typeof value === 'number' && Number.isFinite(value)) return String(value)
  return ''
}

/** 宽松取数字，非法/缺省返回 undefined。 */
function num(value) {
  if (typeof value === 'number' && Number.isFinite(value)) return value
  if (typeof value === 'string' && value.trim() !== '') {
    const parsed = Number(value)
    if (Number.isFinite(parsed)) return parsed
  }
  return undefined
}

/**
 * 会话对象 / hub 摘要里能否看出这是子智能体会话。
 *
 * DSH 各版本给的位置不一致：`session.parentSessionId`（当前形态）、
 * `session.meta.parentSessionId`、`session.origin === 'subagent'`。全都兜一遍；
 * 都读不到时再用 `subagent/start|end` 事件记下来的 id 集合兜底。
 *
 * @param {object|null} session - `session/event` 的会话对象。
 * @param {Set<string>} subagentSessions - 事件里记下来的子会话 id。
 * @param {string} sessionId
 * @returns {boolean}
 */
function isSubagent(session, subagentSessions, sessionId) {
  try {
    if (session !== null && typeof session === 'object') {
      if (text(session.parentSessionId) !== '') return true
      if (text(session.origin) === 'subagent') return true
      const meta = isRecord(session.meta) ? session.meta : null
      if (meta !== null && text(meta.parentSessionId) !== '') return true
      if (meta !== null && text(meta.origin) === 'subagent') return true
    }
    if (sessionId !== '' && subagentSessions.has(sessionId)) return true
  } catch {
    // 判定失败按「普通会话」处理：宁可多提醒一次，也不要静默吞掉
  }
  return false
}

/**
 * 订阅宿主的会话事件，把每一次 `turn/end` 广播成 `turn.completed`。
 *
 * 载荷（`data`）：
 * ```json
 * {
 *   "sessionId": "session-…",
 *   "turn": 12,              // DSH 的轮次号，缺省为 null
 *   "reason": "completed",   // turn/end 的 reason，缺省为 null
 *   "time": 1760000000000,   // DSH 事件时间（ms），缺省为 null
 *   "title": "重构网关",      // hub 里已知的标题，未知为 null
 *   "subagent": false        // 子智能体会话：客户端据此静音
 * }
 * ```
 *
 * @param {object} deps
 * @param {object} deps.ctx - Cordis 插件上下文（需要 `ctx.on`）。
 * @param {object} deps.hub - {@link import('./hub.js').EventHub}（需要 `publish`）。
 * @param {{debug?:Function,warn?:Function}} [deps.logger]
 * @returns {() => void} 取消订阅（幂等、绝不抛异常）。
 */
export function watchTurnEnds({ ctx, hub, logger }) {
  const subagentSessions = new Set()

  /** 记下 `subagent/start|end` 里的子会话 id。 */
  function rememberSubagent(info) {
    try {
      const id = isRecord(info) ? text(info.id) : ''
      if (id === '' || subagentSessions.has(id)) return
      if (subagentSessions.size >= SUBAGENT_ID_MAX) {
        // 只保留最近的一半：子会话往往成批出现，旧的不会再收到 turn/end。
        const keep = Array.from(subagentSessions).slice(-Math.floor(SUBAGENT_ID_MAX / 2))
        subagentSessions.clear()
        for (const item of keep) subagentSessions.add(item)
      }
      subagentSessions.add(id)
    } catch {
      // 忽略
    }
  }

  /** 一条 `session/event`：只认 `turn/end`。 */
  function handleSessionEvent(session, event) {
    try {
      if (!isRecord(event) || text(event.type) !== 'turn/end') return
      if (hub === null || typeof hub?.publish !== 'function') return
      const data = isRecord(event.data) ? event.data : {}
      const sessionId = text(isRecord(session) ? session.id : '')
        || text(isRecord(session) ? session.sessionId : '')
        || text(data.sessionId)
      if (sessionId === '') return
      let title = ''
      try {
        title = typeof hub.sessionTitleOf === 'function' ? text(hub.sessionTitleOf(sessionId)) : ''
      } catch {
        title = ''
      }
      hub.publish('global', TURN_COMPLETED_KIND, {
        sessionId,
        turn: num(data.turn) ?? null,
        reason: text(data.reason) || null,
        time: num(event.time) ?? null,
        title: title || null,
        subagent: isSubagent(session, subagentSessions, sessionId),
      })
    } catch (error) {
      logger?.debug?.(`turn.completed skipped: ${error?.message ?? error}`)
    }
  }

  if (ctx === null || typeof ctx?.on !== 'function') {
    logger?.debug?.('ctx.on unavailable; turn completion notifications are disabled')
    return () => {}
  }

  const disposers = []
  /** 注册一个 Cordis 监听；返回的 disposer 可能是函数、对象或空。 */
  function listen(name, handler) {
    try {
      const dispose = ctx.on(name, handler)
      if (typeof dispose === 'function') disposers.push(dispose)
      else if (dispose !== null && typeof dispose?.dispose === 'function') disposers.push(() => dispose.dispose())
    } catch (error) {
      logger?.debug?.(`ctx.on(${name}) failed: ${error?.message ?? error}`)
    }
  }

  listen('session/event', handleSessionEvent)
  listen('subagent/start', rememberSubagent)
  listen('subagent/end', rememberSubagent)

  return () => {
    for (const dispose of disposers.splice(0)) {
      try {
        dispose()
      } catch {
        // 卸载路径绝不抛异常
      }
    }
  }
}

export default watchTurnEnds
