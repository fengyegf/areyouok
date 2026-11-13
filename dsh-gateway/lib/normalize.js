/**
 * DSH 帧归一化（纯函数、无 I/O）。
 *
 * 职责与对外 API：
 *   - {@link normalizeWaterfall}(frame)：`$events` waterfall 帧 → 审批/提问事件。
 *   - {@link normalizeSessionRecord}(sessionId, frame)：`session/follow` 任一帧 →
 *     [{kind, topic, data, id}]（snapshot 会展开 records；assistant-stream 转增量）。
 *   - {@link normalizeControlFrame}(frame)：`session/control` 帧 → 会话摘要/状态事件。
 *   - {@link normalizeWorkspaceFrame}(frame)：`workspace/follow` 帧 → 工作区事件。
 *   - {@link approvalOutcome}(value)：校验并规范化审批 outcome。
 *   - {@link questionAnswers}(eventId, questions, answers)：生成 `$events/result` 的 value。
 *
 * 约定（见 docs/dsh-gateway-impl-plan.md §3.5 与交接文档 §6.4）：
 *   - 归一化结果里的 topic 统一为 `session:<id>` / `global` / `workspace`；
 *   - 未知/未映射的 DSH 会话事件一律降级为 `kind:'session.event'`
 *     （`data:{sessionId,type,seq,time,event}`），保证可观测、不丢数据；
 *   - 本模块不修改入参，不做去重（去重在 EventHub 按 id/seq 完成）。
 *
 * @module dsh-gateway/lib/normalize
 */

/** 审批 outcome 的合法取值（DSH `ApprovalOutcome`）。 */
export const APPROVAL_OUTCOMES = Object.freeze(['allowed-once', 'rejected', 'cancelled', 'unavailable'])

/** 审批 outcome 常见别名 → 规范值。 */
const APPROVAL_ALIASES = new Map([
  ['allowed-once', 'allowed-once'],
  ['allowed_once', 'allowed-once'],
  ['allowedonce', 'allowed-once'],
  ['allow-once', 'allowed-once'],
  ['allow_once', 'allowed-once'],
  ['allowonce', 'allowed-once'],
  ['allow', 'allowed-once'],
  ['allowed', 'allowed-once'],
  ['approve', 'allowed-once'],
  ['approved', 'allowed-once'],
  ['yes', 'allowed-once'],
  ['true', 'allowed-once'],
  ['rejected', 'rejected'],
  ['reject', 'rejected'],
  ['denied', 'rejected'],
  ['deny', 'rejected'],
  ['no', 'rejected'],
  ['false', 'rejected'],
  ['cancelled', 'cancelled'],
  ['canceled', 'cancelled'],
  ['cancel', 'cancelled'],
  ['dismiss', 'cancelled'],
  ['dismissed', 'cancelled'],
  ['unavailable', 'unavailable'],
  ['gone', 'unavailable'],
  ['expired', 'unavailable'],
])

/** DSH 帧里「消息」类 kind，供 EventHub 缓存正文消息。 */
export const MESSAGE_KINDS = Object.freeze(['message.user', 'message.assistant'])

/** @returns {boolean} 是否为普通对象（非数组、非 null）。 */
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

/** 安全取数组。 */
function asArray(value) {
  return Array.isArray(value) ? value : []
}

/** 去掉 undefined 字段，保证 JSON 干净。 */
function clean(object) {
  const out = {}
  for (const [key, value] of Object.entries(object)) {
    if (value !== undefined) out[key] = value
  }
  return out
}

/** 会话 topic。 */
export function sessionTopic(sessionId) {
  return `session:${text(sessionId)}`
}

/**
 * 从 DSH 消息的 content 块中提取可读文本（只取 text 块，块间用换行连接）。
 * @param {unknown} message - `{content:[{type:'text',text:'...'}]}` 形状的消息。
 * @returns {string}
 */
export function messageText(message) {
  if (!isRecord(message)) return ''
  const parts = []
  for (const block of asArray(message.content)) {
    if (!isRecord(block)) continue
    if (typeof block.text === 'string' && (block.type === undefined || block.type === 'text')) {
      parts.push(block.text)
    }
  }
  return parts.join('\n')
}

/**
 * 从 DSH 消息的 content 块中提取「已入库」的图片引用。
 *
 * DSH 会话记录把图片存成 `{type:'image', attachment:{attachmentId, mediaType,
 * width, height, bytes, name?}}`（见 `ImageBlock` / `ImageAttachmentRef`）；
 * 只带 base64 `data` 的即时图片不会出现在记录里，直接忽略。
 *
 * @param {unknown} content - `[{type:'image', attachment:{…}}]` 形状的 content 数组。
 * @returns {Array<{attachmentId:string, mediaType:string, width?:number,
 *   height?:number, bytes?:number, name?:string}>}
 */
export function imagesOfContent(content) {
  const images = []
  for (const block of asArray(content)) {
    if (!isRecord(block) || block.type !== 'image') continue
    const attachment = isRecord(block.attachment) ? block.attachment : null
    if (attachment === null) continue
    const attachmentId = text(attachment.attachmentId)
    const mediaType = text(attachment.mediaType)
    if (attachmentId === '' || !mediaType.startsWith('image/')) continue
    images.push(clean({
      attachmentId,
      mediaType,
      width: num(attachment.width),
      height: num(attachment.height),
      bytes: num(attachment.bytes),
      name: text(attachment.name) || undefined,
    }))
  }
  return images
}

/**
 * 从 DSH 消息的 content 块中提取「已入库」的文件附件引用。
 *
 * prompt 里的文件在会话记录里落成 `{type:'file', attachment:{attachmentId, name,
 * bytes}}`（见 `FileAttachmentRef`）；同一 block 只带 receiptId 的即时上传不会
 * 留在记录里，这里自然跳过。
 *
 * @param {unknown} content - `[{type:'file', attachment:{…}}]` 形状的 content 数组。
 * @returns {Array<{attachmentId:string, name:string, bytes?:number}>}
 */
export function filesOfContent(content) {
  const files = []
  for (const block of asArray(content)) {
    if (!isRecord(block) || block.type !== 'file') continue
    const attachment = isRecord(block.attachment) ? block.attachment : null
    if (attachment === null) continue
    const attachmentId = text(attachment.attachmentId)
    if (attachmentId === '') continue
    files.push(clean({
      attachmentId,
      name: text(attachment.name) || 'file',
      bytes: num(attachment.bytes),
    }))
  }
  return files
}

/** 规范化提问项列表（保留未知字段，保证不丢信息）。 */
function normalizeQuestions(value) {
  return asArray(value)
    .filter(isRecord)
    .map((item, index) => {
      const question = text(item.question) || text(item.prompt) || text(item.header)
      return clean({
        id: text(item.id) || `q${index + 1}`,
        question,
        prompt: text(item.prompt) || undefined,
        detail: text(item.detail) || undefined,
        header: text(item.header) || undefined,
        options: asArray(item.options)
          .filter(isRecord)
          .map((option) => clean({
            label: text(option.label),
            description: text(option.description) || undefined,
          })),
        multiSelect: typeof item.multiSelect === 'boolean' ? item.multiSelect : undefined,
        intent: isRecord(item.intent) ? item.intent : undefined,
      })
    })
}

/**
 * `$events` waterfall 帧 → 待处理干预事件。
 *
 * @param {object} frame - 形如 `{type:'waterfall', event, eventId, agentId, request}`。
 * @returns {{kind:'approval.request'|'question.request', eventId:string, sessionId:string, data:object}|null}
 */
export function normalizeWaterfall(frame) {
  if (!isRecord(frame) || frame.type !== 'waterfall') return null
  const event = text(frame.event)
  const eventId = text(frame.eventId)
  if (event === '' || eventId === '') return null

  const sessionId = text(frame.agentId) || text(frame.request?.sessionId)
  const request = isRecord(frame.request) ? frame.request : {}

  if (event === 'approval/request') {
    return {
      kind: 'approval.request',
      eventId,
      sessionId,
      data: clean({
        eventId,
        sessionId,
        toolName: text(request.toolName) || text(request.name) || text(request.tool) || 'tool',
        callId: text(request.callId) || undefined,
        reason: text(request.reason) || undefined,
        request,
      }),
    }
  }

  if (event === 'user-questions/request') {
    return {
      kind: 'question.request',
      eventId,
      sessionId,
      data: clean({
        eventId,
        sessionId,
        questions: normalizeQuestions(request.questions),
        request,
      }),
    }
  }

  // 未知 waterfall 不当作干预处理（避免污染 pending 列表）。
  return null
}

/** 构造「降级」的 session.event。 */
function degradeEvent(sessionId, type, { seq, time, event, frame } = {}) {
  return {
    kind: 'session.event',
    topic: sessionTopic(sessionId),
    id: seq === undefined ? undefined : `${sessionId}:${seq}`,
    data: clean({
      sessionId,
      type,
      seq,
      time,
      event,
      frame,
    }),
  }
}

/**
 * 单条 DSH 持久会话事件（`{type,seq,time,data}`）→ `/v1` 事件。
 * @returns {{kind:string, topic:string, id?:string, data:object}}
 */
function normalizeDurableEvent(sessionId, event) {
  const type = text(event.type)
  const seq = num(event.seq)
  const time = num(event.time)
  const payload = isRecord(event.data) ? event.data : {}
  const topic = sessionTopic(sessionId)
  const id = seq === undefined ? undefined : `${sessionId}:${seq}`

  switch (type) {
    case 'user/message': {
      const message = isRecord(event.data) ? event.data : payload
      const images = imagesOfContent(message.content)
      const files = filesOfContent(message.content)
      return {
        kind: 'message.user',
        topic,
        id,
        data: clean({
          sessionId,
          seq,
          time,
          role: 'user',
          text: messageText(message),
          message,
          ...(images.length > 0 ? { images } : {}),
          ...(files.length > 0 ? { files } : {}),
          // DSH 用 `source.kind` 区分「真人输入」与机器注入（tool-jobs 通知、
          // subagent 结算、团队消息、runtime-context…）。客户端据此决定是否
          // 当成用户气泡展示，避免把后台任务通知画成用户自己发的话。
          source: isRecord(message.source) ? message.source : undefined,
        }),
      }
    }
    case 'assistant/message': {
      const message = isRecord(payload.message) ? payload.message : undefined
      const images = imagesOfContent(message?.content)
      return {
        kind: 'message.assistant',
        topic,
        id,
        data: clean({
          sessionId,
          seq,
          time,
          turn: num(payload.turn),
          step: num(payload.step),
          role: 'assistant',
          text: messageText(message),
          message,
          ...(images.length > 0 ? { images } : {}),
          interrupted: payload.interrupted === true ? true : undefined,
          usage: isRecord(payload.usage) ? payload.usage : undefined,
        }),
      }
    }
    case 'turn/start':
      return {
        kind: 'turn.start',
        topic,
        id,
        data: clean({ sessionId, seq, time, turn: num(payload.turn) }),
      }
    case 'turn/end':
      return {
        kind: 'turn.end',
        topic,
        id,
        data: clean({
          sessionId,
          seq,
          time,
          turn: num(payload.turn),
          reason: payload.reason,
        }),
      }
    case 'tool/call':
      return {
        kind: 'tool.call',
        topic,
        id,
        data: clean({
          sessionId,
          seq,
          time,
          turn: num(payload.turn),
          step: num(payload.step),
          callId: text(payload.callId) || undefined,
          name: text(payload.name) || undefined,
          arguments: typeof payload.arguments === 'string' ? payload.arguments : undefined,
          call: payload,
        }),
      }
    case 'tool/result': {
      const message = isRecord(payload.message) ? payload.message : undefined
      const callId = text(message?.source?.callId) || text(payload.callId)
      const images = imagesOfContent(message?.content)
      return {
        kind: 'tool.result',
        topic,
        id,
        data: clean({
          sessionId,
          seq,
          time,
          turn: num(payload.turn),
          step: num(payload.step),
          callId: callId || undefined,
          text: messageText(message),
          message,
          ...(images.length > 0 ? { images } : {}),
          error: isRecord(payload.error) ? payload.error : undefined,
          meta: payload.meta,
        }),
      }
    }
    default:
      return degradeEvent(sessionId, type, { seq, time, event })
  }
}

/** 一条 session/follow 记录（可能是 `{type:'event',event}` 包装或裸事件）。 */
function normalizeRecord(sessionId, record) {
  if (!isRecord(record)) {
    return [degradeEvent(sessionId, 'invalid-record', { frame: record })]
  }
  if (record.type === 'event' && isRecord(record.event)) {
    return [normalizeDurableEvent(sessionId, record.event)]
  }
  if (typeof record.type === 'string' && record.seq !== undefined) {
    return [normalizeDurableEvent(sessionId, record)]
  }
  return [degradeEvent(sessionId, text(record.type) || 'unknown-record', { frame: record })]
}

/**
 * 从 assistant-stream 的 `chunk`（StreamChunk，JSON 值）里提取文本增量。
 *
 * DSH 的 StreamChunk 是 `{type:'text-delta'|'reasoning-delta'|..., text?}`；
 * 但 `assistant-stream.chunk` 类型声明是 `JsonValue`，历史/紧凑格式里也可能出现
 * `{type:'text',text}`、`{type:'reasoning',text}` 或被包了一层的结构，因此在
 * 已知类型之外做一次浅递归兜底（跳过 block-start/block-end/usage/finish，
 * 避免把完整块重复当成增量）。
 *
 * @param {unknown} chunk
 * @param {number} [depth]
 * @returns {{kind:'text'|'reasoning', text:string}|null}
 */
function deltaOfChunk(chunk, depth = 0) {
  if (!isRecord(chunk) || depth > 3) return null
  const type = text(chunk.type)
  if (type === 'text-delta' || type === 'text') {
    return typeof chunk.text === 'string' ? { kind: 'text', text: chunk.text } : null
  }
  if (type === 'reasoning-delta' || type === 'reasoning') {
    return typeof chunk.text === 'string' ? { kind: 'reasoning', text: chunk.text } : null
  }
  // 完整块/控制帧不是增量：不要再向下挖，否则会重复输出。
  if (type === 'block-start' || type === 'block-end' || type === 'usage' || type === 'finish') {
    return null
  }
  for (const value of Object.values(chunk)) {
    if (!isRecord(value)) continue
    const found = deltaOfChunk(value, depth + 1)
    if (found !== null) return found
  }
  return null
}

/** `assistant-stream` 帧 → message.delta（只映射 text/reasoning 增量，其余降级保留）。 */
function normalizeAssistantStream(sessionId, frame) {
  if (!isRecord(frame)) {
    return [degradeEvent(sessionId, 'assistant-stream', { frame })]
  }
  const topic = sessionTopic(sessionId)
  if (frame.type === 'chunk' && isRecord(frame.chunk)) {
    const chunk = frame.chunk
    const delta = deltaOfChunk(chunk)
    if (delta !== null && delta.text !== '') {
      return [{
        kind: 'message.delta',
        topic,
        data: clean({
          sessionId,
          turn: num(frame.turn),
          step: num(frame.step),
          text: delta.text,
          kind: delta.kind,
          time: num(chunk.time) ?? num(frame.time),
          attemptId: text(frame.attemptId) || undefined,
          index: num(frame.index),
          revision: num(frame.revision),
        }),
      }]
    }
  }
  // start/end/其他块级增量：保留为 session.event，客户端可忽略。
  return [degradeEvent(sessionId, 'assistant-stream', { time: num(frame.time), frame })]
}

/**
 * `session/follow` 任一帧 → 事件数组。
 *
 * 支持：
 *   - `{type:'snapshot', header, cursor, records, hasMore, projections}`：展开 records；
 *   - `{type:'event', event:{type,seq,time,data}}`：单条持久事件；
 *   - `{type:'assistant-stream', frame:{type:'start'|'chunk'|'end', ...}}`：流式增量；
 *   - 其他：降级 `session.event`。
 *
 * @param {string} sessionId - 该流所属会话。
 * @param {object} frame - mux `item` 的 value。
 * @returns {Array<{kind:string, topic:string, id?:string, data:object}>}
 */
export function normalizeSessionRecord(sessionId, frame) {
  if (!isRecord(frame)) {
    return [degradeEvent(sessionId, 'invalid-frame', { frame })]
  }
  const sid = text(sessionId)
    || text(frame.header?.id)
    || text(frame.event?.data?.sessionId)
  switch (frame.type) {
    case 'snapshot': {
      const events = []
      for (const record of asArray(frame.records)) events.push(...normalizeRecord(sid, record))
      return events
    }
    case 'event':
      return normalizeRecord(sid, frame)
    case 'assistant-stream':
      return normalizeAssistantStream(sid, frame.frame ?? frame)
    default:
      return [degradeEvent(sid, text(frame.type) || 'unknown-frame', { frame })]
  }
}

/** 从 projection changes 中取某个 key 的值。 */
function changeValue(changes, key) {
  for (const change of changes) {
    if (change.key === key) return change.val
  }
  return undefined
}

/** 把 projection changes 数组转成 values 对象。 */
function valuesFromChanges(changes) {
  const values = {}
  for (const change of changes) {
    if (typeof change.key === 'string') values[change.key] = change.val
  }
  return values
}

/** session/control baseline 里的一条投影 → 会话摘要（字段可能不全，EventHub 会合并）。 */
function summaryFromProjection(sessionId, baseline) {
  const base = isRecord(baseline) ? baseline : {}
  const values = isRecord(base.values) ? base.values : {}
  return clean({
    sessionId,
    title: typeof values.title === 'string' ? values.title : undefined,
    projections: {
      kind: 'sequenced',
      asOfSeq: num(base.asOfSeq) ?? 0,
      values,
    },
  })
}

/** session/control 全量快照 → 摘要数组。 */
function summariesFromBaseline(value) {
  if (Array.isArray(value)) return value.filter(isRecord).map((item) => clean({ ...item }))
  if (!isRecord(value)) return null
  if (Array.isArray(value.items)) return value.items.filter(isRecord).map((item) => clean({ ...item }))
  if (Array.isArray(value.sessions)) return value.sessions.filter(isRecord).map((item) => clean({ ...item }))
  if (isRecord(value.projections)) {
    return Object.entries(value.projections)
      .map(([sessionId, baseline]) => summaryFromProjection(sessionId, baseline))
  }
  return null
}

/**
 * `session/control` 帧 → 归一化事件。
 *
 * 真实 DSH 的 `SessionControlFrame` 是：
 *   - `{type:'baseline', value:{projections:{[sessionId]:{asOfSeq,values}}}}`
 *   - `{type:'projection', sessionId, seq, changes:[{key,val}]}`
 * 这里同时兼容「摘要数组」形状的 baseline（mock/旧版本），返回：
 *   - `sessions.snapshot`：全量会话摘要；
 *   - `session.summary`：单会话增量（`projectionChanges` 交给 EventHub 合并）。
 *
 * @param {object} frame - mux `session/control` item 的 value。
 * @returns {{kind:'sessions.snapshot'|'session.summary'|'session.removed'|'session.status', data:object|Array}|null}
 */
export function normalizeControlFrame(frame) {
  if (!isRecord(frame)) return null
  const type = text(frame.type)

  if (type === 'baseline' || type === 'snapshot' || type === 'replace') {
    const list = summariesFromBaseline(frame.value ?? frame.items)
    if (list === null) return null
    return { kind: 'sessions.snapshot', data: list }
  }

  if (type === 'projection' || type === 'update') {
    const sessionId = text(frame.sessionId) || text(frame.value?.sessionId)
    if (sessionId === '') return null
    const changes = asArray(frame.changes).filter(isRecord)
    const seq = num(frame.seq)
    const data = clean({
      sessionId,
      seq,
      updatedAt: num(frame.time),
      projectionChanges: changes.length > 0 ? changes : undefined,
      projections: changes.length > 0
        ? { kind: 'sequenced', asOfSeq: seq ?? 0, values: valuesFromChanges(changes) }
        : undefined,
      title: (() => {
        const title = changeValue(changes, 'title')
        return typeof title === 'string' ? title : undefined
      })(),
    })
    return { kind: 'session.summary', data }
  }

  if (type === 'removed') {
    const sessionId = text(frame.sessionId) || text(frame.value?.sessionId)
    if (sessionId === '') return null
    return { kind: 'session.removed', data: { sessionId } }
  }

  if (type === 'status') {
    const sessionId = text(frame.sessionId) || text(frame.value?.sessionId)
    if (sessionId === '') return null
    return {
      kind: 'session.status',
      data: { sessionId, running: Boolean(frame.running ?? frame.value?.running) },
    }
  }

  return null
}

/** 从 workspaces 字面量里提取数组。 */
function workspaceItems(value) {
  if (Array.isArray(value)) return value.filter(isRecord)
  if (!isRecord(value)) return null
  if (Array.isArray(value.items)) return value.items.filter(isRecord)
  if (Array.isArray(value.workspaces)) return value.workspaces.filter(isRecord)
  return null
}

/**
 * `workspace/follow` 帧 → 归一化事件。
 *
 * 真实 DSH 帧：`baseline|replace`（`value:{items,...}`）、`upsert`（`workspace`）、
 * `remove`（`workspaceId`）、`order`（`workspaceIds`）、`archived`/`pinned`（id 数组）。
 * 为兼容 mock，也接受 `{type:'snapshot', value:{items}}`。
 *
 * @param {object} frame - mux `workspace/follow` item 的 value。
 * @returns {{kind:'workspaces'|'workspace.updated', data:object|Array}|null}
 */
export function normalizeWorkspaceFrame(frame) {
  if (!isRecord(frame)) return null
  const type = text(frame.type)
  const value = frame.value ?? frame.data

  if (type === 'baseline' || type === 'replace' || type === 'snapshot') {
    const items = workspaceItems(value)
    if (items === null) return null
    const meta = isRecord(value) ? value : {}
    const data = {
      workspaces: items,
      archivedSessionIds: asArray(meta.archivedSessionIds).filter((id) => typeof id === 'string'),
      pinnedSessionIds: asArray(meta.pinnedSessionIds).filter((id) => typeof id === 'string'),
    }
    return { kind: 'workspaces', data }
  }

  if (type === 'upsert') {
    const workspace = isRecord(frame.workspace)
      ? frame.workspace
      : isRecord(value?.workspace)
        ? value.workspace
        : isRecord(value) && 'workspaceId' in value
          ? value
          : null
    if (workspace === null) return null
    return { kind: 'workspace.updated', data: workspace }
  }

  if (type === 'remove') {
    const workspaceId = text(frame.workspaceId) || text(value?.workspaceId) || text(value)
    if (workspaceId === '') return null
    return { kind: 'workspace.updated', data: { workspaceId, removed: true } }
  }

  if (type === 'order') {
    const order = asArray(frame.workspaceIds ?? value?.workspaceIds).filter((id) => typeof id === 'string')
    if (order.length === 0) return null
    return { kind: 'workspace.updated', data: { order } }
  }

  if (type === 'archived') {
    const ids = asArray(frame.archivedSessionIds ?? value?.archivedSessionIds).filter((id) => typeof id === 'string')
    return { kind: 'workspace.updated', data: { archivedSessionIds: ids } }
  }

  if (type === 'pinned') {
    const ids = asArray(frame.pinnedSessionIds ?? value?.pinnedSessionIds).filter((id) => typeof id === 'string')
    return { kind: 'workspace.updated', data: { pinnedSessionIds: ids } }
  }

  return null
}

/**
 * 校验并规范化审批 outcome。
 *
 * 接受规范值、常见别名（allow/reject/cancel…）以及 `{kind:'result',value}` 包装；
 * 非法输入返回 null，由调用方决定返回 400。
 *
 * @param {unknown} value - 来自 `POST /v1/interventions/{id}/answer` 的 value。
 * @returns {'allowed-once'|'rejected'|'cancelled'|'unavailable'|null}
 */
export function approvalOutcome(value) {
  if (isRecord(value)) {
    if (value.kind === 'result') return approvalOutcome(value.value)
    if (value.value !== undefined) return approvalOutcome(value.value)
    if (value.approval !== undefined) return approvalOutcome(value.approval)
    return null
  }
  if (typeof value !== 'string') return null
  const key = value.trim().toLowerCase()
  return APPROVAL_ALIASES.get(key) ?? null
}

/** 把任意 selected 输入规范成字符串数组。 */
function selectedOf(value) {
  if (typeof value === 'string') return [value]
  if (Array.isArray(value)) return value.filter((item) => typeof item === 'string')
  return []
}

/** 提问答案的输入归一化成 `{id, selected, custom?}` 列表。 */
function answerEntries(answers) {
  if (answers === undefined || answers === null) return []
  if (Array.isArray(answers)) {
    return answers
      .map((item, index) => {
        if (typeof item === 'string') return { id: String(index), selected: [item] }
        if (!isRecord(item)) return null
        return clean({
          id: text(item.id) || String(index),
          selected: selectedOf(item.selected ?? item.values ?? item.answer),
          custom: typeof item.custom === 'string' && item.custom !== '' ? item.custom : undefined,
        })
      })
      .filter(Boolean)
  }
  if (isRecord(answers)) {
    if (Array.isArray(answers.answers)) return answerEntries(answers.answers)
    const out = []
    for (const [id, raw] of Object.entries(answers)) {
      if (raw === undefined || raw === null) continue
      if (isRecord(raw)) {
        out.push(clean({
          id,
          selected: selectedOf(raw.selected ?? raw.values ?? raw.answer),
          custom: typeof raw.custom === 'string' && raw.custom !== '' ? raw.custom : undefined,
        }))
      } else {
        out.push({ id, selected: selectedOf(raw) })
      }
    }
    return out
  }
  return []
}

/**
 * 生成 `$events/result` 的提问回答 value。
 *
 * 输入 answers 支持三种形态：
 *   - `[{id, selected:[...], custom?}]`
 *   - `{answers:[...]}`
 *   - `{q1:['A'], q2:{selected:['B'], custom:'x'}}`
 * 若提供了 questions，则按题序输出并过滤未知 id（防止前台传脏数据）。
 *
 * @param {string} eventId - 关联的 waterfall eventId（保留参数，便于审计/扩展）。
 * @param {Array<{id:string}>|{questions:Array<{id:string}>}|undefined} questions - 提问列表。
 * @param {unknown} answers - 客户端答案。
 * @returns {{answers: Array<{id:string, selected:string[], custom?:string}>}}
 */
export function questionAnswers(eventId, questions, answers) {
  const list = Array.isArray(questions)
    ? questions
    : Array.isArray(questions?.questions)
      ? questions.questions
      : []
  const ids = list
    .filter(isRecord)
    .map((item, index) => text(item.id) || `q${index + 1}`)
  const entries = answerEntries(answers)
  const byId = new Map(entries.map((entry) => [entry.id, entry]))

  const result = []
  if (ids.length > 0) {
    for (const id of ids) {
      const entry = byId.get(id) ?? { id, selected: [] }
      result.push(clean({ id, selected: selectedOf(entry.selected), custom: entry.custom }))
      byId.delete(id)
    }
    // 未知 id 一律丢弃，避免把脏数据发给 DSH。
  } else {
    for (const entry of entries) {
      result.push(clean({ id: entry.id, selected: selectedOf(entry.selected), custom: entry.custom }))
    }
  }

  // eventId 目前不进入 wire value（DSH 只校验 clientId/eventId/outcome 三元组），
  // 但保留在函数签名中供调用方关联审计日志。
  void eventId
  return { answers: result }
}

export default {
  normalizeWaterfall,
  normalizeSessionRecord,
  normalizeControlFrame,
  normalizeWorkspaceFrame,
  approvalOutcome,
  questionAnswers,
  messageText,
  sessionTopic,
  APPROVAL_OUTCOMES,
  MESSAGE_KINDS,
}
