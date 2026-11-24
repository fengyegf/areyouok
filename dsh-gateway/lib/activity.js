/**
 * 后台任务（DSH `job`）内存追踪器：把宿主 `session/event` 里的
 * `job/registered` / `job/settled` 折叠成一张「本进程跑过哪些后台任务」的表，
 * 供 `GET /v1/sessions/{id}/activity` 的「后台任务」下半区读取。
 *
 * 设计要点（docs/team-activity-panel.md §2.2.3）：
 *
 *   - 只订阅宿主事件总线（`ctx.on('session/event')`），不新增 DSH 依赖：
 *     `job/registered` data = `{jobId, owner, kind, label, startedAt, toolCall?}`，
 *     `job/settled` data = 上述 + `{finishedAt, status, cause, producerReported, detail?}`
 *     （`@deepseek-ai/dsh-jobs/src/types.ts` 的 `JobRegisteredData/JobSettledData`）。
 *   - 幂等：同一 `jobId` 的重复事件不产生第二条记录；已结算的任务**不能**被
 *     迟到的 `registered` 复活（重放/补齐会把旧事件原样重发一遍）。
 *   - 有界：内存最多 {@link MAX_TRACKED_JOBS} 条；已结算的任务在
 *     {@link SETTLED_RETENTION_MS}（5 分钟）后惰性清理，面板只保留「刚结束」的。
 *   - 绝不抛异常：本模块跑在 DSH 进程里，任何字段异常都只丢弃这一条事件。
 *
 * 重启后历史 job 不补：事件是实时流，没有查询接口，符合契约「只显示实时开始的」。
 *
 * @module dsh-gateway/lib/activity
 */

/** 内存里最多保留多少条 job（超过时先淘汰最老的已结算记录）。 */
export const MAX_TRACKED_JOBS = 200

/** 已结算 job 的保留时长（超过即惰性清理，面板不再展示）。 */
export const SETTLED_RETENTION_MS = 5 * 60_000

/** `job/settled` 可能携带的终态。 */
const SETTLED_STATUSES = new Set(['completed', 'killed', 'failed'])

/** @returns {boolean} 是否为普通对象（非数组、非 null）。 */
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
 * 一个 job 的对外形状（`GET /v1/sessions/{id}/activity` 的 `jobs[]` 条目，
 * `subagent` 由 HTTP 层按 owner 是否为子会话补齐）。
 *
 * @typedef {object} TrackedJob
 * @property {string} jobId - DSH job id（`<kind>-<short-uuid>`）。
 * @property {string} sessionId - 所属会话（`data.owner`）。
 * @property {string} kind - 生产者种类（`bash`/`workflow`/…）。
 * @property {string} label - 面向模型的一行标签（命令 / 委派描述）。
 * @property {string} status - `running|completed|killed|failed`。
 * @property {string|null} detail - 终态原因（`exit code: 3` 等），缺省 null。
 * @property {number} startedAt - 注册时刻（ms）。
 * @property {number|null} finishedAt - 结算时刻（ms）；仍运行中为 null。
 */

/**
 * 追踪宿主事件里的后台任务。
 *
 * 用法（`lib/index.js`）：
 * ```js
 * const activity = new ActivityTracker({ logger })
 * const stop = watchSessionEvents({ ctx, tracker: activity, logger })
 * ```
 */
export class ActivityTracker {
  /**
   * @param {object} [options]
   * @param {{debug?:Function,warn?:Function}} [options.logger] - 仅用于 debug 日志。
   * @param {number} [options.maxJobs] - 内存上限（默认 {@link MAX_TRACKED_JOBS}）。
   * @param {number} [options.settledRetentionMs] - 已结算保留时长（默认 5 分钟）。
   * @param {() => number} [options.now] - 时钟（测试可注入）。
   */
  constructor(options = {}) {
    this.logger = isRecord(options.logger) ? options.logger : {}
    const maxJobs = num(options.maxJobs)
    this.maxJobs = maxJobs === undefined ? MAX_TRACKED_JOBS : Math.max(1, Math.trunc(maxJobs))
    const retention = num(options.settledRetentionMs)
    this.settledRetentionMs = retention === undefined ? SETTLED_RETENTION_MS : Math.max(0, Math.trunc(retention))
    this.now = typeof options.now === 'function' ? options.now : () => Date.now()

    /** @type {Map<string, TrackedJob>} jobId → 记录（插入顺序即注册顺序）。 */
    this._jobs = new Map()
  }

  /** 当前保留的 job 条数（会先做一次惰性清理）。 */
  get size() {
    this._sweep()
    return this._jobs.size
  }

  /** 清空全部记录（插件 stop 时调用）。 */
  clear() {
    this._jobs.clear()
  }

  /**
   * 处理一条宿主的 `session/event`：只认 `job/registered` / `job/settled`。
   *
   * 幂等且绝不抛异常：重复的 `registered`、已结算后的迟到 `registered`、
   * 结构异常的事件都只会被忽略（最多写一条 debug 日志）。
   *
   * @param {object|null} session - `session/event` 的会话对象。
   * @param {object|null} event - `{type, data, time?}`。
   * @returns {void}
   */
  handleSessionEvent(session, event) {
    try {
      if (!isRecord(event)) return
      const type = text(event.type)
      if (type !== 'job/registered' && type !== 'job/settled') return
      const data = isRecord(event.data) ? event.data : {}
      const jobId = text(data.jobId)
      if (jobId === '') return

      const existing = this._jobs.get(jobId)
      // 已结算即终态：迟到的 registered / 重复的 settled 都不能改写它。
      if (existing !== undefined && existing.finishedAt !== null) return
      // 重复的 registered（重放/补齐）：保留第一条，不产生新记录。
      if (type === 'job/registered' && existing !== undefined) return

      const sessionId = text(data.owner)
        || text(isRecord(session) ? session.id : '')
        || text(isRecord(session) ? session.sessionId : '')
      const startedAt = num(data.startedAt) ?? num(existing?.startedAt) ?? this.now()
      const kind = text(data.kind) || text(existing?.kind) || 'job'
      const label = text(data.label) || text(existing?.label) || ''

      if (type === 'job/settled') {
        const status = SETTLED_STATUSES.has(text(data.status)) ? text(data.status) : 'completed'
        const detail = text(data.detail)
        this._jobs.set(jobId, {
          jobId,
          sessionId: sessionId || text(existing?.sessionId),
          kind,
          label,
          status,
          detail: detail === '' ? null : detail,
          startedAt,
          finishedAt: num(data.finishedAt) ?? this.now(),
        })
      } else {
        this._jobs.set(jobId, {
          jobId,
          sessionId,
          kind,
          label,
          status: 'running',
          detail: null,
          startedAt,
          finishedAt: null,
        })
      }

      this._sweep()
    } catch (error) {
      // 追踪器跑在 DSH 进程里：任何异常都只能丢弃这一条事件。
      try {
        this.logger.debug?.(`activity job event dropped: ${error?.message ?? error}`)
      } catch {
        // 日志本身失败也不能外抛
      }
    }
  }

  /**
   * 树内会话的 job（按「运行中优先、startedAt 倒序」排好）。
   *
   * @param {Iterable<string>|string[]|null} sessionIds - 根会话 + 后代会话 id。
   * @returns {TrackedJob[]} 记录的副本（调用方可自由排序/裁剪）。
   */
  jobsInSessionIds(sessionIds) {
    try {
      const wanted = new Set()
      if (Array.isArray(sessionIds) || sessionIds instanceof Set) {
        for (const id of sessionIds) {
          const value = text(id)
          if (value !== '') wanted.add(value)
        }
      }
      if (wanted.size === 0) return []
      this._sweep()
      const jobs = []
      for (const job of this._jobs.values()) {
        if (!wanted.has(job.sessionId)) continue
        jobs.push({ ...job })
      }
      jobs.sort((left, right) => {
        const leftRunning = left.finishedAt === null
        const rightRunning = right.finishedAt === null
        if (leftRunning !== rightRunning) return leftRunning ? -1 : 1
        return right.startedAt - left.startedAt
      })
      return jobs
    } catch (error) {
      try {
        this.logger.debug?.(`activity job read failed: ${error?.message ?? error}`)
      } catch {
        // 忽略
      }
      return []
    }
  }

  /** 惰性清理：先删过期结算，再按上限淘汰最老记录。 */
  _sweep() {
    const now = this.now()
    for (const [jobId, job] of this._jobs) {
      if (job.finishedAt !== null && job.finishedAt + this.settledRetentionMs <= now) {
        this._jobs.delete(jobId)
      }
    }
    if (this._jobs.size <= this.maxJobs) return
    // 超限：先淘汰最老的已结算，再淘汰最老的运行中（正常不会走到后一半）。
    const ordered = [...this._jobs.values()].sort((left, right) => {
      const leftSettled = left.finishedAt !== null
      const rightSettled = right.finishedAt !== null
      if (leftSettled !== rightSettled) return leftSettled ? -1 : 1
      return (left.finishedAt ?? left.startedAt) - (right.finishedAt ?? right.startedAt)
    })
    for (const job of ordered) {
      if (this._jobs.size <= this.maxJobs) break
      this._jobs.delete(job.jobId)
    }
  }
}

/**
 * 订阅宿主的 `session/event`，把 `job/registered` / `job/settled` 交给追踪器。
 *
 * 与 `lib/turns.js` 的 `watchTurnEnds` 同一防御风格：`ctx.on` 不存在时退化为
 * 空操作，卸载幂等且绝不抛异常。
 *
 * @param {object} deps
 * @param {object|null} deps.ctx - Cordis 插件上下文（需要 `ctx.on`）。
 * @param {ActivityTracker} deps.tracker - 追踪器（需要 `handleSessionEvent`）。
 * @param {{debug?:Function,warn?:Function}} [deps.logger]
 * @returns {() => void} 取消订阅（幂等、绝不抛异常）。
 */
export function watchSessionEvents({ ctx, tracker, logger }) {
  if (tracker === null || typeof tracker?.handleSessionEvent !== 'function') {
    try {
      logger?.debug?.('activity tracker unavailable; background job tracking is disabled')
    } catch {
      // 忽略
    }
    return () => {}
  }
  if (ctx === null || typeof ctx?.on !== 'function') {
    try {
      logger?.debug?.('ctx.on unavailable; background job tracking is disabled')
    } catch {
      // 忽略
    }
    return () => {}
  }

  const disposers = []
  const handler = (session, event) => {
    try {
      tracker.handleSessionEvent(session, event)
    } catch (error) {
      try {
        logger?.debug?.(`activity job event dropped: ${error?.message ?? error}`)
      } catch {
        // 忽略
      }
    }
  }

  try {
    const dispose = ctx.on('session/event', handler)
    if (typeof dispose === 'function') disposers.push(dispose)
    else if (dispose !== null && typeof dispose?.dispose === 'function') disposers.push(() => dispose.dispose())
  } catch (error) {
    try {
      logger?.debug?.(`ctx.on(session/event) failed: ${error?.message ?? error}`)
    } catch {
      // 忽略
    }
  }

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

export default watchSessionEvents
