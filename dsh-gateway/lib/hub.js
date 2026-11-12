/**
 * EventHub —— dsh-gateway 的唯一状态中心。
 *
 * 职责：
 *   - 全局单调 `seq`（从 1 开始）与**全局**有界 ring buffer；
 *   - `eventsSince(sinceSeq, topics)` 断线补发 + `gap` 判定；
 *   - 事件广播（同步 sink，绝不 await，避免阻塞 DSH 事件循环）；
 *   - 域状态缓存：workspaces / sessions / session 状态 / 消息（仅内存）；
 *   - 审批与提问 pending 表（按 eventId 幂等去重、TTL、结算广播）；
 *   - 设备表（只存 tokenHash，不存原文令牌）；
 *   - `state.json` 原子持久化（0600，200ms 防抖，损坏时备份 .bak 重建）。
 *
 * 设计约束（docs/dsh-gateway-impl-plan.md §3.4）：
 *   - 本模块是唯一修改 `seq`、`pending`、`devices`、缓存快照的模块；
 *   - 持久化**只存元数据**，不存消息正文（消息缓存重启后由 session/follow snapshot 重建）；
 *   - 所有广播同步完成，单个 sink 抛错不影响其他 sink / 主流程；
 *   - ring 是全局按 seq 排序的数组，`eventsSince` 的 gap 判定基于 ring 首元素。
 *
 * @module dsh-gateway/lib/hub
 */

import { chmodSync, copyFileSync, unlinkSync } from 'node:fs'
import { join } from 'node:path'

import {
  STATE_VERSION,
  dataDir,
  fileExists,
  makeLogger,
  readJsonFile,
  writeFileAtomic,
} from './util.js'

/** ring 默认容量（config.ringSize）。 */
const DEFAULT_RING_SIZE = 2000
/** pending 干预默认保留时长（config.approvalTtlMs，30 分钟）。 */
const DEFAULT_APPROVAL_TTL_MS = 1_800_000
/** 已结算干预记录保留时长：用于「已在其他设备处理」返回 409 而不是 404。 */
const RESOLVED_RETENTION_MS = 5 * 60_000
/** 每会话内存消息缓存上限（仅用于 /v1 REST 快速读取）。 */
const MESSAGE_LIMIT_PER_SESSION = 1000
/** 写盘防抖窗口。 */
const PERSIST_DEBOUNCE_MS = 200

/** @returns {boolean} */
function isRecord(value) {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/** 安全取字符串。 */
function text(value) {
  return typeof value === 'string' ? value : ''
}

/** 安全取有限数字。 */
function num(value) {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : undefined
}

/** 深拷贝 JSON 值（状态快照/对外返回值用，避免调用方改内部状态）。 */
function clone(value) {
  if (value === null || value === undefined) return value
  try {
    return JSON.parse(JSON.stringify(value))
  } catch {
    return value
  }
}

/** 把配置里的正整数收敛到范围内。 */
function intOf(value, fallback, min, max) {
  const parsed = num(value)
  if (parsed === undefined) return fallback
  return Math.min(max, Math.max(min, Math.trunc(parsed)))
}

/** topics 归一化：undefined/null → 不过滤（全量）；含 'global' → 全量；空集合 → 空。 */
function toTopicFilter(topics) {
  if (topics === undefined || topics === null) return null
  const set = topics instanceof Set ? topics : new Set(Array.isArray(topics) ? topics : [topics])
  if (set.has('global')) return null
  return set
}

/** 摘要是否带「完整会话摘要」字段（用于区分 session 列表 baseline 与 projections baseline）。 */
function looksLikeFullSummary(item) {
  return isRecord(item) && (
    typeof item.updatedAt === 'number'
    || typeof item.running === 'boolean'
    || typeof item.blank === 'boolean'
    || typeof item.agentAvailable === 'boolean'
  )
}

export class EventHub {
  /**
   * @param {object} [options]
   * @param {object} [options.config] - 解析后的插件配置（ringSize/approvalTtlMs）。
   * @param {{info?:Function,warn?:Function,error?:Function,debug?:Function}} [options.logger]
   * @param {string} [options.dir] - 状态目录，默认 `$DSH_HOME/dsh-gateway`。
   */
  constructor(options = {}) {
    this.config = isRecord(options.config) ? options.config : {}
    this.logger = options.logger ?? makeLogger(undefined, 'dsh-gateway')
    this.dir = text(options.dir) || dataDir()
    this.stateFile = join(this.dir, 'state.json')
    this.ringSize = intOf(this.config.ringSize, DEFAULT_RING_SIZE, 16, 100_000)
    this.approvalTtlMs = intOf(this.config.approvalTtlMs, DEFAULT_APPROVAL_TTL_MS, 1_000, 86_400_000)

    /** @type {number} 当前已分配最大 seq（从 1 开始单调）。 */
    this._seq = 0
    /** @type {object[]} 全局按 seq 递增的有界 ring。 */
    this._ring = []
    /** @type {Set<Function>} 广播 sink。 */
    this._sinks = new Set()

    /** @type {Map<string, object>} sessionId → SessionSummary。 */
    this._sessions = new Map()
    /** @type {Map<string, {sessionId:string,running:boolean,updatedAt:number}>} */
    this._statuses = new Map()
    /** @type {Map<string, object[]>} sessionId → 消息缓存（按 seq 升序）。 */
    this._messages = new Map()
    /** @type {Map<string, object>} workspaceId → workspace。 */
    this._workspaces = new Map()
    /** @type {string[]} 工作区展示顺序。 */
    this._workspaceOrder = []
    /** @type {string[]} */
    this._archivedSessionIds = []
    /** @type {string[]} */
    this._pinnedSessionIds = []
    /** @type {Map<string, object>} eventId → 干预记录。 */
    this._interventions = new Map()
    /** @type {Map<string, object>} deviceId → 设备记录。 */
    this._devices = new Map()

    this._dshStatus = { connected: false }
    this._eventsClientId = null

    this._loaded = false
    this._dirty = false
    this._persistTimer = null
    this._writing = null
  }

  // ---------------------------------------------------------------------------
  // 序号 / ring / 广播
  // ---------------------------------------------------------------------------

  /** 当前已分配最大 seq。 */
  get seq() {
    return this._seq
  }

  /** 分配下一个全局 seq（从 1 开始）。 */
  nextSeq() {
    this._seq += 1
    this._dirty = true
    return this._seq
  }

  /**
   * 发布一条事件：分配 seq → 入 ring → 广播 → 触发持久化。
   *
   * @param {string} topic - `global` | `session:<id>` | `workspace` | `device:<id>`。
   * @param {string} kind - 事件 kind（见交接文档 §6.4）。
   * @param {unknown} data - JSON 安全的载荷。
   * @param {{id?:string, ts?:number}} [opts] - 自定义事件 id / 时间戳。
   * @returns {{id:string, seq:number, ts:number, topic:string, kind:string, data:unknown}}
   */
  publish(topic, kind, data, opts = {}) {
    const seq = this.nextSeq()
    const event = {
      id: text(opts.id) || `ev-${seq}`,
      seq,
      ts: num(opts.ts) ?? Date.now(),
      topic: text(topic) || 'global',
      kind: text(kind) || 'event',
      data: data === undefined ? null : data,
    }

    this._ring.push(event)
    if (this._ring.length > this.ringSize) {
      this._ring.splice(0, this._ring.length - this.ringSize)
    }

    this._applyEventSideEffects(event)

    for (const sink of this._sinks) {
      try {
        sink(event)
      } catch (error) {
        this._warn(`sink failed: ${error?.message ?? error}`)
      }
    }

    this.persist()
    return event
  }

  /**
   * 断线补发：返回 seq 严格大于 `sinceSeq` 的事件。
   *
   * `gap` 判定：
   *   - ring 非空：`sinceSeq < ring[0].seq - 1`（中间有事件已被淘汰）；
   *   - ring 为空（插件重启/尚未产生事件）：`sinceSeq < this.seq`（旧 seq 已无 buffer）；
   *   - `sinceSeq > this.seq`：客户端持有的 seq 比本进程还新（例如重启前最后 200ms
   *     的事件尚未落盘），必须先 resync，否则新事件会复用客户端已见过的 seq。
   *
   * @param {number} sinceSeq - 客户端已持有的最大 seq。
   * @param {Set<string>|string[]} [topics] - 订阅集合；'global' 表示全量。
   * @returns {{events:object[], gap:boolean, seq:number}}
   */
  eventsSince(sinceSeq, topics) {
    const since = num(sinceSeq) === undefined ? 0 : Math.max(0, Math.trunc(num(sinceSeq)))
    const filter = toTopicFilter(topics)
    const ring = this._ring
    let gap = false
    if (ring.length > 0) gap = since < ring[0].seq - 1
    else gap = since < this._seq
    if (since > this._seq) gap = true

    const events = []
    for (const event of ring) {
      if (event.seq <= since) continue
      if (filter !== null && !filter.has(event.topic)) continue
      events.push(event)
    }
    return { events, gap, seq: this._seq }
  }

  /**
   * 注册事件 sink。
   * @param {(event:object)=>void} fn
   * @returns {() => void} 取消函数
   */
  addSink(fn) {
    if (typeof fn !== 'function') return () => {}
    this._sinks.add(fn)
    return () => this.removeSink(fn)
  }

  /** 移除事件 sink。 */
  removeSink(fn) {
    this._sinks.delete(fn)
  }

  // ---------------------------------------------------------------------------
  // 会话域状态
  // ---------------------------------------------------------------------------

  /**
   * 合并单条会话摘要（upsert；字段为 undefined 时保留旧值）。
   *
   * 支持 `projections`（全量投影）与 `projectionChanges`（projection 增量）两种形状。
   *
   * @param {object} summary - SessionSummary 或其部分字段。
   * @returns {object|null} 有变化时返回广播的 session.updated 事件，否则 null。
   */
  applySessionSummary(summary) {
    const incoming = isRecord(summary?.summary) ? summary.summary : summary
    if (!isRecord(incoming)) return null
    const sessionId = text(incoming.sessionId)
    if (sessionId === '') return null

    const existing = this._sessions.get(sessionId)
    const next = this._mergeSummary(existing, { ...incoming, sessionId })
    const changed = existing === undefined || this._stableStringify(existing) !== this._stableStringify(next)
    this._sessions.set(sessionId, next)

    if (typeof next.running === 'boolean') {
      const current = this._statuses.get(sessionId)
      this._statuses.set(sessionId, {
        sessionId,
        running: next.running,
        updatedAt: current?.updatedAt ?? num(next.updatedAt) ?? Date.now(),
      })
    }

    if (!changed) return null
    return this.publish(`session:${sessionId}`, 'session.updated', { sessionId, summary: clone(next) })
  }

  /**
   * 全量会话摘要（baseline）。
   *
   * 若 baseline 是完整 SessionSummary 列表则整体替换；若只是 projection baseline
   * （真实 DSH `session/control` 首帧只有 projections），则逐条合并，避免把
   * `$events` 里 api-session/added 的完整摘要降级掉。
   *
   * @param {object[]|{items?:object[],sessions?:object[]}} list
   * @returns {object|null} 广播的 sessions.snapshot 事件。
   */
  applySessions(list) {
    const items = Array.isArray(list)
      ? list
      : isRecord(list) && Array.isArray(list.items)
        ? list.items
        : isRecord(list) && Array.isArray(list.sessions)
          ? list.sessions
          : null
    if (items === null) return null

    const valid = items.filter((item) => isRecord(item) && text(item.sessionId) !== '')
    const full = valid.some(looksLikeFullSummary)
    const applyOne = (item) => {
      const sessionId = text(item.sessionId)
      const merged = this._mergeSummary(this._sessions.get(sessionId), item)
      // baseline 语义：projections 缺 key = 能力不存在，因此整块替换而不是合并。
      if (isRecord(item.projections)) {
        merged.projections = clone(item.projections)
        const title = item.projections.values?.title
        if (typeof title === 'string') merged.title = title
      }
      return merged
    }
    if (full) {
      const next = new Map()
      for (const item of valid) next.set(text(item.sessionId), applyOne(item))
      this._sessions = next
    } else {
      for (const item of valid) {
        const sessionId = text(item.sessionId)
        this._sessions.set(sessionId, applyOne(item))
      }
    }

    for (const item of valid) {
      const sessionId = text(item.sessionId)
      const merged = this._sessions.get(sessionId)
      if (merged && typeof merged.running === 'boolean') {
        const current = this._statuses.get(sessionId)
        this._statuses.set(sessionId, {
          sessionId,
          running: merged.running,
          updatedAt: current?.updatedAt ?? num(merged.updatedAt) ?? Date.now(),
        })
      }
    }

    return this.publish('global', 'sessions.snapshot', { sessions: this.getSessions() })
  }

  /**
   * 删除一个会话（api-session/removed 或控制流移除）。
   * @param {string} sessionId
   * @returns {object|null} session.removed 事件。
   */
  removeSession(sessionId) {
    const id = text(sessionId)
    if (id === '') return null
    this._sessions.delete(id)
    this._statuses.delete(id)
    this._messages.delete(id)
    for (const [eventId, record] of this._interventions) {
      if (record.sessionId === id && record.status === 'pending') this._interventions.delete(eventId)
    }
    // 会话级事件走 session topic：订阅该会话的设备能收到；订阅 global 的设备也收全部。
    return this.publish(`session:${id}`, 'session.removed', { sessionId: id })
  }

  /**
   * 更新会话运行状态（api-session/status、session/control）。
   * @param {string} sessionId
   * @param {boolean} running
   * @returns {object|null} session.status 事件（无变化时 null）。
   */
  applySessionStatus(sessionId, running) {
    const id = text(sessionId)
    if (id === '') return null
    const value = Boolean(running)
    const existing = this._statuses.get(id)
    const changed = existing === undefined || existing.running !== value
    this._statuses.set(id, { sessionId: id, running: value, updatedAt: Date.now() })

    const summary = this._sessions.get(id)
    if (summary !== undefined) this._sessions.set(id, { ...summary, running: value })

    if (!changed) return null
    return this.publish(`session:${id}`, 'session.status', { sessionId: id, running: value })
  }

  /**
   * 刷新会话最近活跃时间（api-session/activity）。
   * @param {string} sessionId
   * @param {number} [updatedAt] - 事件时间；缺省用 Date.now()。
   * @returns {object|null} session.updated 事件（未知会话返回 null）。
   */
  touchSession(sessionId, updatedAt) {
    const id = text(sessionId)
    if (id === '') return null
    const at = num(updatedAt) ?? Date.now()
    const status = this._statuses.get(id)
    this._statuses.set(id, {
      sessionId: id,
      running: status?.running ?? false,
      updatedAt: at,
    })
    const summary = this._sessions.get(id)
    if (summary === undefined) return null
    const next = { ...summary, updatedAt: at }
    this._sessions.set(id, next)
    return this.publish(`session:${id}`, 'session.updated', { sessionId: id, summary: clone(next) })
  }

  /** 会话摘要列表（按 updatedAt 倒序），running 以状态表为准。 */
  getSessions() {
    const ids = new Set([...this._sessions.keys(), ...this._statuses.keys()])
    const list = []
    for (const id of ids) {
      const summary = this._sessions.get(id)
      const status = this._statuses.get(id)
      const base = summary === undefined ? { sessionId: id } : clone(summary)
      const merged = {
        ...base,
        sessionId: id,
        running: status?.running ?? Boolean(base.running),
      }
      const updatedAt = num(base.updatedAt) ?? num(status?.updatedAt)
      if (updatedAt !== undefined) merged.updatedAt = updatedAt
      list.push(merged)
    }
    list.sort((left, right) => (num(right.updatedAt) ?? 0) - (num(left.updatedAt) ?? 0))
    return list
  }

  /**
   * 会话运行状态。
   * @param {string} sessionId
   * @returns {{sessionId:string, running:boolean, updatedAt?:number}|null}
   */
  getSessionStatus(sessionId) {
    const id = text(sessionId)
    if (id === '') return null
    const status = this._statuses.get(id)
    const summary = this._sessions.get(id)
    if (status === undefined && summary === undefined) return null
    return {
      sessionId: id,
      running: status?.running ?? Boolean(summary?.running),
      updatedAt: status?.updatedAt ?? num(summary?.updatedAt),
    }
  }

  /** 会话是否处于 running（未知会话按 false）。 */
  isSessionRunning(sessionId) {
    return Boolean(this.getSessionStatus(sessionId)?.running)
  }

  /**
   * 会话标题（列表/通知用；未知会话或没有标题时返回 ''）。
   *
   * 与 {@link getSessions} 同一取值来源：顶层 `title` 优先，其次
   * `projections.values.title`（`_mergeSummary` 已把投影标题回填到顶层）。
   *
   * @param {string} sessionId
   * @returns {string}
   */
  sessionTitleOf(sessionId) {
    const id = text(sessionId)
    if (id === '') return ''
    const summary = this._sessions.get(id)
    if (summary === undefined) return ''
    const title = text(summary.title)
    if (title !== '') return title
    const projected = summary.projections?.values?.title
    return typeof projected === 'string' ? projected : ''
  }

  /**
   * 缓存的消息列表（按 seq 升序；来自 message.user / message.assistant 事件）。
   * @param {string} sessionId
   * @returns {object[]}
   */
  getMessages(sessionId) {
    const id = text(sessionId)
    if (id === '') return []
    const list = this._messages.get(id) ?? []
    return list.map((item) => ({ ...item }))
  }

  /** 当前已缓存消息的会话数（调试/测试用）。 */
  get messageSessionCount() {
    return this._messages.size
  }

  // ---------------------------------------------------------------------------
  // 工作区域状态
  // ---------------------------------------------------------------------------

  /**
   * 全量工作区 baseline。
   * @param {object[]|{items?:object[],workspaces?:object[],archivedSessionIds?:string[],pinnedSessionIds?:string[]}} list
   * @returns {object|null} workspaces 事件。
   */
  applyWorkspaces(list) {
    let items = null
    let archived = null
    let pinned = null
    if (Array.isArray(list)) {
      items = list
    } else if (isRecord(list)) {
      if (Array.isArray(list.workspaces)) items = list.workspaces
      else if (Array.isArray(list.items)) items = list.items
      if (Array.isArray(list.archivedSessionIds)) archived = list.archivedSessionIds.filter((id) => typeof id === 'string')
      if (Array.isArray(list.pinnedSessionIds)) pinned = list.pinnedSessionIds.filter((id) => typeof id === 'string')
    }
    if (items === null) return null

    const next = new Map()
    const order = []
    for (const item of items) {
      if (!isRecord(item)) continue
      const id = text(item.workspaceId)
      if (id === '') continue
      next.set(id, this._mergeWorkspace(this._workspaces.get(id), { ...item, workspaceId: id }))
      order.push(id)
    }
    this._workspaces = next
    this._workspaceOrder = order
    if (archived !== null) this._archivedSessionIds = archived
    if (pinned !== null) this._pinnedSessionIds = pinned

    return this.publish('global', 'workspaces', {
      workspaces: this.getWorkspaces(),
      archivedSessionIds: [...this._archivedSessionIds],
      pinnedSessionIds: [...this._pinnedSessionIds],
    })
  }

  /**
   * 工作区增量（upsert / remove / order / archived / pinned）。
   * @param {object} workspace - 工作区对象，或 `{removed:true,workspaceId}` /
   *   `{order:string[]}` / `{archivedSessionIds:string[]}` / `{pinnedSessionIds:string[]}`。
   * @returns {object|null} workspace.updated 事件。
   */
  upsertWorkspace(workspace) {
    let input = workspace
    if (isRecord(input) && isRecord(input.workspace)) input = input.workspace
    if (!isRecord(input)) return null

    if (input.removed === true) {
      const id = text(input.workspaceId)
      if (id === '') return null
      this._workspaces.delete(id)
      this._workspaceOrder = this._workspaceOrder.filter((item) => item !== id)
      return this.publish('global', 'workspace.updated', { workspaceId: id, removed: true })
    }

    if (Array.isArray(input.order)) {
      this._workspaceOrder = input.order.filter((id) => typeof id === 'string')
      return this.publish('global', 'workspace.updated', { order: [...this._workspaceOrder] })
    }

    if (Array.isArray(input.archivedSessionIds)) {
      this._archivedSessionIds = input.archivedSessionIds.filter((id) => typeof id === 'string')
      return this.publish('global', 'workspace.updated', {
        archivedSessionIds: [...this._archivedSessionIds],
      })
    }

    if (Array.isArray(input.pinnedSessionIds)) {
      this._pinnedSessionIds = input.pinnedSessionIds.filter((id) => typeof id === 'string')
      return this.publish('global', 'workspace.updated', {
        pinnedSessionIds: [...this._pinnedSessionIds],
      })
    }

    const id = text(input.workspaceId)
    if (id === '') return null
    const merged = this._mergeWorkspace(this._workspaces.get(id), { ...input, workspaceId: id })
    this._workspaces.set(id, merged)
    if (!this._workspaceOrder.includes(id)) this._workspaceOrder.push(id)
    return this.publish('global', 'workspace.updated', { workspace: clone(merged) })
  }

  /** 工作区列表（按 order 排序，未在 order 中的追加在后）。 */
  getWorkspaces() {
    const list = []
    const seen = new Set()
    for (const id of this._workspaceOrder) {
      const workspace = this._workspaces.get(id)
      if (workspace !== undefined) {
        list.push(clone(workspace))
        seen.add(id)
      }
    }
    for (const [id, workspace] of this._workspaces) {
      if (!seen.has(id)) list.push(clone(workspace))
    }
    return list
  }

  /** 工作区展示顺序 / 归档 / 置顶元数据。 */
  getWorkspaceMeta() {
    return {
      order: [...this._workspaceOrder],
      archivedSessionIds: [...this._archivedSessionIds],
      pinnedSessionIds: [...this._pinnedSessionIds],
    }
  }

  /** 找出某个会话所属的 workspaceId（来自 workspace.sessionIds）。 */
  getWorkspaceOfSession(sessionId) {
    const id = text(sessionId)
    if (id === '') return null
    for (const [workspaceId, workspace] of this._workspaces) {
      if (Array.isArray(workspace.sessionIds) && workspace.sessionIds.includes(id)) return workspaceId
    }
    return null
  }

  // ---------------------------------------------------------------------------
  // 审批 / 提问（pending interventions）
  // ---------------------------------------------------------------------------

  /**
   * 记录一条待处理干预。同 eventId 幂等：已存在（含已结算）直接返回旧记录。
   *
   * @param {{eventId:string, sessionId?:string, kind:string, data?:object}} intervention
   * @returns {object|null} 记录的副本（eventId 非法时 null）。
   */
  addIntervention(intervention) {
    if (!isRecord(intervention)) return null
    const eventId = text(intervention.eventId) || text(intervention.data?.eventId)
    if (eventId === '') return null
    this._sweepInterventions()

    const existing = this._interventions.get(eventId)
    if (existing !== undefined) return clone(existing)

    const sessionId = text(intervention.sessionId) || text(intervention.data?.sessionId)
    const kind = text(intervention.kind) || 'approval.request'
    const now = Date.now()
    const record = {
      eventId,
      sessionId,
      kind,
      data: isRecord(intervention.data) ? intervention.data : { eventId, sessionId },
      status: 'pending',
      createdAt: now,
      expiresAt: now + this.approvalTtlMs,
    }
    this._interventions.set(eventId, record)

    const topic = sessionId === '' ? 'global' : `session:${sessionId}`
    this.publish(topic, kind, record.data)
    return clone(record)
  }

  /**
   * 结算一条干预并广播 intervention.resolved。
   *
   * @param {string} eventId
   * @param {unknown} outcome - 审批字符串或提问答案对象。
   * @param {'this'|'other'} [by='other'] - 是否本网关回答（this）还是桌面/其他客户端先答（other）。
   * @returns {object|null} 结算后的记录副本（不存在/已结算时 null）。
   */
  removeIntervention(eventId, outcome, by = 'other') {
    this._sweepInterventions()
    const id = text(eventId)
    if (id === '') return null
    const record = this._interventions.get(id)
    if (record === undefined || record.status !== 'pending') return null

    record.status = 'resolved'
    record.outcome = outcome === undefined ? null : outcome
    record.by = by === 'this' ? 'this' : 'other'
    record.resolvedAt = Date.now()
    this._dirty = true

    const topic = record.sessionId === '' ? 'global' : `session:${record.sessionId}`
    this.publish(topic, 'intervention.resolved', {
      eventId: id,
      sessionId: record.sessionId,
      kind: record.kind,
      by: record.by,
      outcome: record.outcome,
    })
    return clone(record)
  }

  /**
   * 干预列表。
   * @param {'pending'|'resolved'|'all'} [status='pending']
   * @returns {object[]} 按 createdAt 升序的副本。
   */
  listInterventions(status = 'pending') {
    this._sweepInterventions()
    const wanted = text(status) || 'pending'
    const list = []
    for (const record of this._interventions.values()) {
      if (wanted !== 'all' && record.status !== wanted) continue
      list.push(clone(record))
    }
    list.sort((left, right) => (num(left.createdAt) ?? 0) - (num(right.createdAt) ?? 0))
    return list
  }

  /**
   * 按 eventId 取干预记录（含已结算，供 HTTP 层区分 404/409）。
   * @param {string} eventId
   * @returns {object|null}
   */
  getIntervention(eventId) {
    this._sweepInterventions()
    const record = this._interventions.get(text(eventId))
    return record === undefined ? null : clone(record)
  }

  // ---------------------------------------------------------------------------
  // DSH 连接状态 / $events clientId
  // ---------------------------------------------------------------------------

  /**
   * 记录 `$events` 最新 clientId（每次重连都会变，回答 waterfall 必须用它）。
   * @param {string} clientId
   */
  setEventsClientId(clientId) {
    const id = text(clientId)
    if (id !== '') this._eventsClientId = id
  }

  /** 最新 `$events` clientId。 */
  getEventsClientId() {
    return this._eventsClientId
  }

  /**
   * 更新 DSH 回环连接状态；变化时广播 gateway.status。
   *
   * 断线时清空 clientId（旧值已失效）；重连 ready 后由新 clientId 覆盖。
   * @param {{connected?:boolean, error?:string, clientId?:string}} state
   * @returns {object|null} gateway.status 事件（无变化时 null）。
   */
  setDshStatus(state) {
    if (!isRecord(state)) return null
    const previous = this._dshStatus
    const connected = state.connected === undefined ? Boolean(previous.connected) : Boolean(state.connected)
    const next = { connected, updatedAt: Date.now() }
    const clientId = text(state.clientId) || (connected ? text(previous.clientId) : '')
    if (clientId !== '') next.clientId = clientId
    const error = text(state.error)
    if (!connected && error !== '') next.error = error

    const changed = previous.connected !== next.connected
      || text(previous.error) !== text(next.error)
      || text(previous.clientId) !== text(next.clientId)
    this._dshStatus = next
    if (next.clientId !== undefined) this._eventsClientId = next.clientId
    else if (!connected) this._eventsClientId = null
    this._dirty = true

    if (!changed) return null
    return this.publish('global', 'gateway.status', {
      connected: next.connected,
      clientId: next.clientId,
      error: next.error,
    })
  }

  /** 当前 DSH 连接状态副本。 */
  getDshStatus() {
    return { ...this._dshStatus }
  }

  // ---------------------------------------------------------------------------
  // 设备表
  // ---------------------------------------------------------------------------

  /** 设备列表（含 tokenHash，供 AuthService 校验；管理页需自行裁剪）。 */
  listDevices() {
    return [...this._devices.values()]
      .map((device) => clone(device))
      .sort((left, right) => (num(left.createdAt) ?? 0) - (num(right.createdAt) ?? 0))
  }

  /**
   * 插入/更新设备（按 deviceId upsert；undefined 字段保留旧值）。
   * @param {object} device
   * @returns {object|null} 保存后的副本。
   */
  upsertDevice(device) {
    if (!isRecord(device)) return null
    const deviceId = text(device.deviceId)
    if (deviceId === '') return null
    const existing = this._devices.get(deviceId)
    const now = Date.now()
    const next = {
      deviceId,
      name: text(device.name) || text(existing?.name),
      tokenHash: text(device.tokenHash) || text(existing?.tokenHash),
      lastSeq: num(device.lastSeq) ?? num(existing?.lastSeq) ?? 0,
      subscriptions: Array.isArray(device.subscriptions)
        ? device.subscriptions.filter((item) => typeof item === 'string')
        : Array.isArray(existing?.subscriptions)
          ? [...existing.subscriptions]
          : [],
      lastSeenAt: num(device.lastSeenAt) ?? now,
      createdAt: num(existing?.createdAt) ?? num(device.createdAt) ?? now,
    }
    if (typeof device.remoteAddress === 'string') next.remoteAddress = device.remoteAddress
    else if (typeof existing?.remoteAddress === 'string') next.remoteAddress = existing.remoteAddress
    if (device.expiresAt !== undefined) next.expiresAt = num(device.expiresAt) ?? null
    else if (existing?.expiresAt !== undefined) next.expiresAt = existing.expiresAt

    this._devices.set(deviceId, next)
    this.persist()
    return clone(next)
  }

  /**
   * 删除设备。
   * @param {string} deviceId
   * @returns {boolean} 是否删除了记录。
   */
  removeDevice(deviceId) {
    const removed = this._devices.delete(text(deviceId))
    if (removed) this.persist()
    return removed
  }

  /** 取单个设备记录副本。 */
  getDevice(deviceId) {
    const device = this._devices.get(text(deviceId))
    return device === undefined ? null : clone(device)
  }

  /** 按 tokenHash 找设备（AuthService 校验令牌用）。 */
  getDeviceByTokenHash(tokenHash) {
    const hash = text(tokenHash)
    if (hash === '') return null
    for (const device of this._devices.values()) {
      if (device.tokenHash === hash) return clone(device)
    }
    return null
  }

  // ---------------------------------------------------------------------------
  // 快照
  // ---------------------------------------------------------------------------

  /**
   * 连接/重连时下发的快照。
   *
   * @param {{topics?:Set<string>|string[]}} [options]
   * @returns {{seq:number, serverTime:number, workspaces:object[], sessions:object[], pending:object[], dsh:object}}
   */
  snapshot(options = {}) {
    const filter = toTopicFilter(options.topics)
    const all = filter === null
    const sessions = all
      ? this.getSessions()
      : this.getSessions().filter((item) => filter.has(`session:${item.sessionId}`))
    const pending = this.listInterventions('pending').filter((item) => (
      all
      || item.sessionId === ''
      || filter.has(`session:${item.sessionId}`)
      || filter.has('interventions')
    ))
    const workspaces = all || filter.has('workspace') || filter.has('workspaces')
      ? this.getWorkspaces()
      : []
    return {
      seq: this._seq,
      serverTime: Date.now(),
      workspaces,
      sessions,
      pending,
      dsh: this.getDshStatus(),
    }
  }

  // ---------------------------------------------------------------------------
  // 持久化
  // ---------------------------------------------------------------------------

  /**
   * 读取 state.json；损坏时备份到 `state.json.bak` 并重建空状态。
   * @returns {Promise<EventHub>}
   */
  async load() {
    if (this._loaded) return this
    this._loaded = true
    if (!fileExists(this.stateFile)) return this

    const parsed = readJsonFile(this.stateFile)
    if (!isRecord(parsed)) {
      this._backupCorruptState()
      return this
    }
    try {
      this._restore(parsed)
    } catch (error) {
      this._warn(`state restore failed: ${error?.message ?? error}`)
      this._backupCorruptState()
    }
    return this
  }

  /** 标记脏并安排 200ms 防抖写盘；写失败只告警。 */
  persist() {
    this._dirty = true
    if (this._persistTimer !== null) return
    this._persistTimer = setTimeout(() => {
      this._persistTimer = null
      this._writeState().catch((error) => this._warn(`state write failed: ${error?.message ?? error}`))
    }, PERSIST_DEBOUNCE_MS)
    this._persistTimer.unref?.()
  }

  /** 立即落盘（等待进行中的写完成）。 */
  async flush() {
    if (this._persistTimer !== null) {
      clearTimeout(this._persistTimer)
      this._persistTimer = null
    }
    if (this._writing !== null) {
      try {
        await this._writing
      } catch {
        // 已在 _writeState 内部记录
      }
    }
    if (!this._dirty) return
    this._writing = this._writeState()
    try {
      await this._writing
    } finally {
      this._writing = null
    }
  }

  /** 停止定时器并落盘（测试/插件卸载用）。 */
  async close() {
    if (this._persistTimer !== null) {
      clearTimeout(this._persistTimer)
      this._persistTimer = null
    }
    this._sinks.clear()
    await this.flush()
  }

  // ---------------------------------------------------------------------------
  // 内部实现
  // ---------------------------------------------------------------------------

  /** 一条事件对域缓存的副作用（消息缓存）。 */
  _applyEventSideEffects(event) {
    if (event.kind !== 'message.user' && event.kind !== 'message.assistant') return
    if (!isRecord(event.data)) return
    const data = event.data
    const sessionId = text(data.sessionId)
    if (sessionId === '') return
    const seq = num(data.seq)
    const entry = {
      seq: seq ?? null,
      time: num(data.time) ?? event.ts,
      role: data.role === 'assistant' ? 'assistant' : 'user',
      text: typeof data.text === 'string' ? data.text : '',
      kind: event.kind,
      message: data.message ?? null,
    }
    const list = this._messages.get(sessionId) ?? []
    if (seq !== undefined) {
      const index = list.findIndex((item) => item.seq === seq)
      if (index !== -1) {
        list[index] = entry
      } else {
        let at = list.length
        while (at > 0 && (num(list[at - 1].seq) ?? 0) > seq) at -= 1
        list.splice(at, 0, entry)
      }
    } else {
      list.push(entry)
    }
    if (list.length > MESSAGE_LIMIT_PER_SESSION) {
      list.splice(0, list.length - MESSAGE_LIMIT_PER_SESSION)
    }
    this._messages.set(sessionId, list)
  }

  /** 合并会话摘要（incoming 的 undefined 字段不覆盖旧值）。 */
  _mergeSummary(existing, incoming) {
    const sessionId = text(incoming.sessionId) || text(existing?.sessionId)
    const next = existing === undefined ? { sessionId } : { ...existing }

    for (const [key, value] of Object.entries(incoming)) {
      if (key === 'projections' || key === 'projectionChanges' || key === 'seq' || key === 'summary') continue
      if (value === undefined) continue
      next[key] = value
    }

    const previousProjections = isRecord(existing?.projections) ? existing.projections : null
    const previousValues = isRecord(previousProjections?.values) ? previousProjections.values : {}
    if (isRecord(incoming.projections)) {
      const incomingValues = isRecord(incoming.projections.values) ? incoming.projections.values : {}
      next.projections = {
        kind: incoming.projections.kind === 'cached' ? 'cached' : 'sequenced',
        asOfSeq: Math.max(num(previousProjections?.asOfSeq) ?? 0, num(incoming.projections.asOfSeq) ?? 0),
        values: { ...previousValues, ...incomingValues },
      }
    } else if (Array.isArray(incoming.projectionChanges)) {
      const values = { ...previousValues }
      for (const change of incoming.projectionChanges) {
        if (isRecord(change) && typeof change.key === 'string') values[change.key] = change.val
      }
      next.projections = {
        kind: previousProjections?.kind === 'cached' ? 'cached' : 'sequenced',
        asOfSeq: Math.max(num(previousProjections?.asOfSeq) ?? 0, num(incoming.seq) ?? 0),
        values,
      }
    }

    if (isRecord(next.projections?.values) && typeof next.projections.values.title === 'string') {
      next.title = next.projections.values.title
    }
    if (
      next.updatedAt === undefined
      && isRecord(next.projections?.values?.sessionListMetadata)
      && num(next.projections.values.sessionListMetadata.lastPromptAt) !== undefined
    ) {
      next.updatedAt = num(next.projections.values.sessionListMetadata.lastPromptAt)
    }
    return next
  }

  /** 合并工作区对象。 */
  _mergeWorkspace(existing, incoming) {
    const workspaceId = text(incoming.workspaceId) || text(existing?.workspaceId)
    const next = existing === undefined ? { workspaceId } : { ...existing, workspaceId }
    for (const [key, value] of Object.entries(incoming)) {
      if (value === undefined) continue
      next[key] = value
    }
    if (Array.isArray(next.sessionIds)) {
      next.sessionIds = next.sessionIds.filter((id) => typeof id === 'string')
    }
    return next
  }

  /** 清理过期 pending 与过老的已结算记录。 */
  _sweepInterventions(now = Date.now()) {
    for (const [eventId, record] of this._interventions) {
      if (record.status === 'pending' && num(record.expiresAt) !== undefined && record.expiresAt <= now) {
        this._interventions.delete(eventId)
        continue
      }
      if (record.status === 'resolved' && num(record.resolvedAt) !== undefined && record.resolvedAt + RESOLVED_RETENTION_MS <= now) {
        this._interventions.delete(eventId)
      }
    }
  }

  /** 稳定序列化（用于变更检测）。 */
  _stableStringify(value) {
    try {
      return JSON.stringify(value)
    } catch {
      return String(value)
    }
  }

  /** 组装 state.json 内容（只存元数据，不存消息正文）。 */
  _statePayload() {
    return {
      v: STATE_VERSION,
      seq: this._seq,
      workspaces: this.getWorkspaces(),
      workspaceMeta: this.getWorkspaceMeta(),
      sessions: this.getSessions(),
      status: this.getDshStatus(),
      interventions: this.listInterventions('pending'),
      devices: this.listDevices(),
      savedAt: Date.now(),
    }
  }

  /** 原子写 state.json（0600；Windows 上 chmod 尽力而为）。 */
  async _writeState() {
    if (!this._dirty) return
    this._dirty = false
    try {
      const text = JSON.stringify(this._statePayload())
      writeFileAtomic(this.stateFile, text, 0o600)
      try {
        chmodSync(this.stateFile, 0o600)
      } catch {
        // Windows 上 chmod 语义有限，忽略
      }
    } catch (error) {
      this._dirty = true
      this._warn(`state write failed: ${error?.message ?? error}`)
    }
  }

  /** 从解析后的 state 恢复内存状态。 */
  _restore(parsed) {
    this._seq = Math.max(this._seq, Math.trunc(num(parsed.seq) ?? 0))

    if (Array.isArray(parsed.workspaces)) {
      const map = new Map()
      const order = []
      for (const item of parsed.workspaces) {
        if (!isRecord(item)) continue
        const id = text(item.workspaceId)
        if (id === '') continue
        map.set(id, { ...item, workspaceId: id })
        order.push(id)
      }
      this._workspaces = map
      this._workspaceOrder = order
    }

    if (isRecord(parsed.workspaceMeta)) {
      const meta = parsed.workspaceMeta
      if (Array.isArray(meta.order)) this._workspaceOrder = meta.order.filter((id) => typeof id === 'string')
      if (Array.isArray(meta.archivedSessionIds)) this._archivedSessionIds = meta.archivedSessionIds.filter((id) => typeof id === 'string')
      if (Array.isArray(meta.pinnedSessionIds)) this._pinnedSessionIds = meta.pinnedSessionIds.filter((id) => typeof id === 'string')
    }

    if (Array.isArray(parsed.sessions)) {
      for (const item of parsed.sessions) {
        if (!isRecord(item)) continue
        const id = text(item.sessionId)
        if (id === '') continue
        this._sessions.set(id, { ...item, sessionId: id })
        if (typeof item.running === 'boolean') {
          this._statuses.set(id, {
            sessionId: id,
            running: item.running,
            updatedAt: num(item.updatedAt) ?? Date.now(),
          })
        }
      }
    }

    if (isRecord(parsed.status)) {
      // 重启后回环链路尚未建立，先按未连接对外呈现，等 dsh.start() 的 ready 再置 true。
      this._dshStatus = {
        connected: false,
        ...(text(parsed.status.clientId) !== '' ? { clientId: text(parsed.status.clientId) } : {}),
        updatedAt: Date.now(),
      }
      if (text(parsed.status.clientId) !== '') this._eventsClientId = text(parsed.status.clientId)
    }

    if (Array.isArray(parsed.interventions)) {
      const now = Date.now()
      for (const item of parsed.interventions) {
        if (!isRecord(item)) continue
        const eventId = text(item.eventId)
        if (eventId === '') continue
        if (num(item.expiresAt) !== undefined && eventId !== '' && num(item.expiresAt) <= now) continue
        this._interventions.set(eventId, {
          ...item,
          eventId,
          status: 'pending',
          data: isRecord(item.data) ? item.data : { eventId },
        })
      }
    }

    if (Array.isArray(parsed.devices)) {
      for (const item of parsed.devices) {
        if (!isRecord(item)) continue
        const deviceId = text(item.deviceId)
        if (deviceId === '') continue
        this._devices.set(deviceId, { ...item, deviceId })
      }
    }

    this._warn(`state restored: seq=${this._seq} sessions=${this._sessions.size} devices=${this._devices.size}`)
  }

  /** 损坏状态文件备份到 .bak。 */
  _backupCorruptState() {
    const backup = `${this.stateFile}.bak`
    try {
      if (fileExists(backup)) {
        try {
          unlinkSync(backup)
        } catch {
          // best effort
        }
      }
      copyFileSync(this.stateFile, backup)
      this._warn(`state.json is corrupt or unreadable; backed up to ${backup}`)
    } catch (error) {
      this._warn(`state.json backup failed: ${error?.message ?? error}`)
    }
  }

  /** 统一日志兜底（logger 可缺省）。 */
  _warn(message) {
    try {
      this.logger.warn?.(message)
    } catch {
      // 日志绝不影响主流程
    }
  }
}

export default EventHub
