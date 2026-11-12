/**
 * `/v1/stream` WebSocket fan-out for the APK.
 *
 * One socket per device. The server speaks the frame protocol from the handoff
 * document §6.4:
 *
 *   S→C ready(snapshot)  →  C→S hello(resume.seq)  →  replay | resync
 *   C→S sub/unsub/ack/ping           S→C event / pong / resync / error
 *
 * Design points:
 *   - the HTTP upgrade is authorized by {@link AuthService.guard} *before*
 *     `ws` accepts it (the public port therefore always needs a Bearer token),
 *   - the hub pushes every published event through `hub.addSink`; a connection
 *     only receives its subscribed topics (or everything when `global` is
 *     subscribed),
 *   - each connection owns a bounded send queue (512 frames / 1 MiB). When a
 *     slow consumer overflows it drops `message.delta` first, then any other
 *     non-critical frame, and finally disconnects,
 *   - the server pings every `heartbeatMs` and kills connections silent for
 *     `idleTimeoutMs`,
 *   - `session:<id>` subscriptions call `dsh.followSession(id)` once for the
 *     first subscriber and `dsh.unfollowSession(id)` when the last one leaves.
 *
 * @module dsh-gateway/lib/ws
 */

import { WebSocket, WebSocketServer } from 'ws'

/** Maximum number of frames queued for one slow consumer. */
export const SEND_QUEUE_MAX = 512

/** Maximum number of bytes queued for one slow consumer. */
export const SEND_QUEUE_BYTES = 1_048_576

/** Below this socket buffer we write straight to the wire. */
const SEND_HIGH_WATER = 262_144

/** Flush once the socket buffer drained below this value. */
const FLUSH_LOW_WATER = 65_536

/**
 * `/v1` 能力清单，随 `ready.snapshot.capabilities` 下发。
 *
 * 客户端据此判断「这台网关支不支持某个功能」——尤其是 APK 与插件分开升级时：
 * 老网关没有 `turn.completed`，APK 应该明确提示升级，而不是静默地不再提醒。
 */
export const CAPABILITIES = Object.freeze(['turn.completed'])

/** Event kinds that must not be dropped for slow consumers. */
const CRITICAL_KINDS = new Set([
  'approval.request',
  'question.request',
  'intervention.resolved',
  'session.status',
  'session.removed',
  'turn.end',
  // 每轮结束提醒的事实源（见 lib/turns.js）：丢了就等于漏一次「跑完了」，
  // 而它一轮只有一条，比 message.delta 便宜得多。
  'turn.completed',
])

/** Close codes used by this server (4000-4999 are application private). */
const CLOSE_REPLACED = 4000
const CLOSE_IDLE = 4002
const CLOSE_TOO_MANY = 4003
const CLOSE_SLOW = 4008

/** HTTP reason phrases for raw upgrade rejections. */
const REASONS = {
  400: 'Bad Request',
  401: 'Unauthorized',
  403: 'Forbidden',
  404: 'Not Found',
  429: 'Too Many Requests',
  503: 'Service Unavailable',
}

/** Whether a subscription topic is allowed. */
function validTopic(topic) {
  if (typeof topic !== 'string') return false
  if (topic === 'global') return true
  if (!topic.startsWith('session:')) return false
  const sessionId = topic.slice('session:'.length)
  return sessionId.length > 0 && sessionId.length <= 256 && !/\s/.test(sessionId)
}

/** Extract the session id out of a `session:<id>` topic. */
function topicSessionId(topic) {
  return topic.startsWith('session:') ? topic.slice('session:'.length) : null
}

/** Whether one session summary describes a subagent session. */
function isSubagentSession(session) {
  if (session === null || typeof session !== 'object') return false
  return typeof session.parentSessionId === 'string' && session.parentSessionId !== ''
}

/** Follow failures are reported but not retried for this long. */
const FOLLOW_RETRY_MS = 30_000

/** Build one `/v1` error frame. */
function errorFrame(code, message) {
  return { t: 'error', code, message }
}

/**
 * StreamServer — WebSocket edge for `/v1/stream`.
 */
export class StreamServer {
  /** @type {import('./dsh.js').DshLoopbackClient|null} */
  #dsh = null

  /**
   * @param {object} deps
   * @param {Record<string, any>} deps.config - normalized gateway config.
   * @param {{info?:Function,warn?:Function,error?:Function,debug?:Function}} [deps.logger]
   * @param {import('./hub.js').EventHub} deps.hub
   * @param {import('./auth.js').AuthService} deps.auth
   */
  constructor({ config, logger, hub, auth }) {
    this.config = config ?? {}
    this.logger = logger ?? {}
    this.hub = hub
    this.auth = auth
    this.closed = false
    /** @type {Set<object>} live connections. */
    this.connections = new Set()
    /** Upgrade sockets already handed to `ws` (attach + onUpgrade both wire here). */
    this.handledSockets = new WeakSet()
    /** @type {Map<string, object>} deviceId → live connection. */
    this.byDevice = new Map()
    /** @type {Map<string, number>} sessionId → last follow failure timestamp. */
    this.failedFollows = new Map()
    /** @type {Array<()=>void>} */
    this.disposers = []
    this.startedAt = Date.now()

    this.wss = new WebSocketServer({
      noServer: true,
      clientTracking: false,
      perMessageDeflate: false,
      maxPayload: 1_048_576,
    })

    try {
      if (typeof hub?.addSink === 'function') {
        const remove = hub.addSink((event) => this.broadcast(event))
        if (typeof remove === 'function') this.disposers.push(remove)
      }
    } catch (error) {
      this.logger.warn?.(`hub sink could not be attached: ${error?.message ?? error}`)
    }

    try {
      const unsubscribe = auth?.onRevoke?.((deviceId) => this.#disconnectDevice(deviceId))
      if (typeof unsubscribe === 'function') this.disposers.push(unsubscribe)
    } catch (error) {
      this.logger.warn?.(`revoke hook could not be attached: ${error?.message ?? error}`)
    }

    const heartbeatMs = Math.max(5_000, Number(this.config.heartbeatMs) || 20_000)
    this.heartbeatTimer = setInterval(() => this.#heartbeat(), heartbeatMs)
    if (typeof this.heartbeatTimer?.unref === 'function') this.heartbeatTimer.unref()
  }

  /** Injected by index.js after the DSH loopback client exists. */
  get dsh() {
    return this.#dsh
  }

  set dsh(client) {
    this.#dsh = client ?? null
    // A subscriber may have arrived before the DSH link was wired; open its
    // follow streams now that the client exists.
    if (this.#dsh !== null && typeof this.#dsh.followSession === 'function') {
      for (const sessionId of this.#subscribedSessions()) this.#follow(sessionId)
    }
  }

  /** Register the upgrade handler on one HTTP(S) server. */
  attach(server) {
    if (server === null || typeof server?.on !== 'function') return this
    const listener = (req, socket, head) => this.handleUpgrade(req, socket, head)
    server.on('upgrade', listener)
    this.disposers.push(() => {
      try {
        server.removeListener('upgrade', listener)
      } catch {
        // best effort
      }
    })
    return this
  }

  /**
   * Handle one HTTP upgrade. Non-`/v1/stream` paths are destroyed.
   * @param {import('node:http').IncomingMessage} req
   * @param {import('node:stream').Duplex} socket
   * @param {Buffer} head
   */
  handleUpgrade(req, socket, head) {
    // Both `attach(server)` and `ApiServer.onUpgrade` may be wired; a socket
    // must only ever be handed to `ws` once.
    if (socket !== null && typeof socket === 'object') {
      if (this.handledSockets.has(socket)) return
      this.handledSockets.add(socket)
    }
    try {
      let url = null
      try {
        url = new URL(String(req?.url ?? '/'), 'http://localhost')
      } catch {
        url = null
      }
      const pathname = url?.pathname ?? '/'
      if (pathname !== '/v1/stream') {
        this.#rejectUpgrade(socket, 404, 'not_found', 'unknown WebSocket path')
        return
      }
      const verdict = this.auth?.guard?.({
        remoteAddress: req?.socket?.remoteAddress ?? socket?.remoteAddress,
        localPort: req?.socket?.localPort ?? socket?.localPort,
        headers: req?.headers ?? {},
        path: pathname,
      }) ?? { ok: false, status: 503, code: 'dsh_unavailable', message: 'gateway not ready' }
      if (verdict.ok !== true) {
        this.#rejectUpgrade(socket, verdict.status ?? 403, verdict.code ?? 'forbidden_source', verdict.message)
        return
      }
      this.wss.handleUpgrade(req, socket, head, (ws) => {
        this.#onConnection(ws, req, verdict, {
          includeSubagents: url?.searchParams?.get('includeSubagents') === '1',
        })
      })
    } catch (error) {
      this.logger.warn?.(`upgrade failed: ${error?.message ?? error}`)
      try {
        socket?.destroy?.()
      } catch {
        // best effort
      }
    }
  }

  /** Number of live connections. */
  count() {
    return this.connections.size
  }

  /** Number of devices currently mapped to a connection. */
  deviceCount() {
    return this.byDevice.size
  }

  /**
   * Hub sink: fan one event out to every subscribed connection.
   * @param {{id?:string, seq?:number, ts?:number, topic?:string, kind?:string, data?:unknown}} event
   */
  broadcast(event) {
    if (this.closed || event === null || typeof event !== 'object') return
    const topic = typeof event.topic === 'string' && event.topic !== '' ? event.topic : 'global'
    const kind = typeof event.kind === 'string' ? event.kind : 'event'
    const frame = {
      t: 'event',
      seq: typeof event.seq === 'number' ? event.seq : 0,
      id: event.id ?? null,
      topic,
      kind,
      data: event.data ?? null,
      ts: typeof event.ts === 'number' ? event.ts : Date.now(),
    }
    for (const conn of this.connections) {
      if (!this.#delivers(conn, topic)) continue
      this.#send(conn, frame, kind)
    }
  }

  /** Close every connection and detach from the hub. Never throws. */
  close() {
    if (this.closed) return
    this.closed = true
    try {
      clearInterval(this.heartbeatTimer)
    } catch {
      // ignore
    }
    for (const dispose of this.disposers.splice(0)) {
      try {
        dispose()
      } catch {
        // ignore
      }
    }
    // Cleanup releases the follow streams of each connection as it goes.
    for (const conn of [...this.connections]) this.#closeConn(conn, 1001, 'gateway shutting down')
    try {
      this.wss.close()
    } catch {
      // ignore
    }
  }

  // ---------------------------------------------------------------- internals

  /** Session ids currently referenced by some connection. */
  #subscribedSessions() {
    const sessions = new Set()
    for (const conn of this.connections) {
      for (const topic of conn.subs.values()) {
        const sessionId = topicSessionId(topic)
        if (sessionId !== null) sessions.add(sessionId)
      }
    }
    return sessions
  }

  /** Connect-handling: send `ready`, then wait for `hello`. */
  #onConnection(ws, req, verdict, options = {}) {
    if (this.closed) {
      try {
        ws.close(1001, 'gateway shutting down')
      } catch {
        // ignore
      }
      return
    }
    const conn = {
      ws,
      req,
      deviceId: typeof verdict?.device?.deviceId === 'string' ? verdict.device.deviceId : null,
      tokenDevice: verdict?.device ?? null,
      // Subagent sessions are hidden by default (the APK cannot follow them);
      // `?includeSubagents=1` opts into the full snapshot.
      includeSubagents: options.includeSubagents === true,
      remoteAddress: typeof req?.socket?.remoteAddress === 'string' ? req.socket.remoteAddress : '',
      subs: new Map(),
      queue: [],
      queuedBytes: 0,
      flushTimer: null,
      lastSeen: Date.now(),
      lastSeq: 0,
      lastPersist: 0,
      helloReceived: false,
      closed: false,
    }
    this.connections.add(conn)
    if (conn.deviceId !== null) {
      const previous = this.byDevice.get(conn.deviceId)
      if (previous !== undefined && previous !== conn) this.#closeConn(previous, CLOSE_REPLACED, 'replaced by reconnect')
      this.byDevice.set(conn.deviceId, conn)
    }

    ws.on('message', (data) => this.#onMessage(conn, data))
    ws.on('pong', () => {
      conn.lastSeen = Date.now()
    })
    ws.on('error', (error) => {
      this.logger.debug?.(`stream socket error: ${error?.message ?? error}`)
    })
    ws.on('close', () => this.#cleanup(conn))

    // Protocol: the server speaks first with `ready` (snapshot included).
    this.#sendReady(conn)
  }

  /** Send `ready` with a fresh snapshot (subagent sessions filtered by default). */
  #sendReady(conn) {
    let snapshot
    try {
      snapshot = this.hub?.snapshot?.() ?? null
    } catch (error) {
      this.logger.warn?.(`snapshot failed: ${error?.message ?? error}`)
    }
    if (snapshot === null || typeof snapshot !== 'object') {
      snapshot = { seq: this.#seq(), serverTime: Date.now(), workspaces: [], sessions: [], pending: [] }
    }
    if (conn.includeSubagents !== true && Array.isArray(snapshot.sessions)) {
      snapshot = {
        ...snapshot,
        sessions: snapshot.sessions.filter((session) => !isSubagentSession(session)),
      }
    }
    snapshot = { ...snapshot, capabilities: [...CAPABILITIES] }
    const seq = typeof snapshot.seq === 'number' ? snapshot.seq : this.#seq()
    this.#send(conn, {
      t: 'ready',
      seq,
      serverTime: Date.now(),
      snapshot,
    }, 'ready')
  }

  /** Current hub sequence number. */
  #seq() {
    try {
      return typeof this.hub?.seq === 'number' ? this.hub.seq : 0
    } catch {
      return 0
    }
  }

  /** Handle one client frame. */
  #onMessage(conn, data) {
    if (conn.closed) return
    conn.lastSeen = Date.now()
    let frame
    try {
      const text = typeof data === 'string' ? data : Buffer.isBuffer(data) ? data.toString('utf8') : String(data)
      frame = JSON.parse(text)
    } catch {
      this.#send(conn, errorFrame('invalid_request', 'frame must be valid JSON'), 'error')
      return
    }
    if (frame === null || typeof frame !== 'object' || typeof frame.t !== 'string') {
      this.#send(conn, errorFrame('invalid_request', 'frame must be an object with a `t` field'), 'error')
      return
    }
    switch (frame.t) {
      case 'hello':
        this.#onHello(conn, frame)
        break
      case 'sub':
        this.#onSub(conn, frame)
        break
      case 'unsub':
        this.#onUnsub(conn, frame)
        break
      case 'ack':
        this.#onAck(conn, frame)
        break
      case 'ping':
        this.#send(conn, { t: 'pong', ts: frame.ts ?? Date.now() }, 'pong')
        break
      case 'pong':
        break
      default:
        // Forward compatibility: unknown frames are ignored silently.
        break
    }
  }

  /** `hello` — identify the device, restore subscriptions, replay events. */
  #onHello(conn, frame) {
    if (conn.helloReceived) return
    conn.helloReceived = true
    const deviceId = typeof frame.deviceId === 'string' && frame.deviceId.trim() !== '' ? frame.deviceId.trim().slice(0, 128) : null
    if (deviceId !== null) {
      const previous = this.byDevice.get(deviceId)
      if (previous !== undefined && previous !== conn) this.#closeConn(previous, CLOSE_REPLACED, 'replaced by reconnect')
      const maxDevices = Math.max(1, Number(this.config.maxDevices) || 4)
      if (!this.byDevice.has(deviceId) && this.byDevice.size >= maxDevices) {
        this.#send(conn, errorFrame('too_many_devices', 'the gateway already has the maximum number of devices'), 'error')
        this.#closeConn(conn, CLOSE_TOO_MANY, 'too many devices')
        return
      }
      conn.deviceId = deviceId
      this.byDevice.set(deviceId, conn)
    }

    // Restore the subscription set persisted with the device record.
    let restored = []
    try {
      const record = conn.deviceId !== null ? this.hub?.getDevice?.(conn.deviceId) : null
      if (record !== null && typeof record === 'object' && Array.isArray(record.subscriptions)) {
        restored = record.subscriptions.filter(validTopic)
      }
      if (record !== null && typeof record === 'object' && Number.isFinite(record.lastSeq)) {
        conn.lastSeq = Math.max(conn.lastSeq, Number(record.lastSeq))
      }
    } catch (error) {
      this.logger.debug?.(`device record unreadable: ${error?.message ?? error}`)
    }
    for (const topic of restored) {
      if (conn.subs.size >= 256) break
      const key = `restore:${topic}`
      if (conn.subs.has(key)) continue
      conn.subs.set(key, topic)
      this.#followTopic(topic)
    }

    const resume = frame.resume !== null && typeof frame.resume === 'object' ? frame.resume : null
    const resumeSeq = resume !== null && Number.isFinite(resume.seq) ? Math.trunc(Number(resume.seq)) : null
    if (resumeSeq !== null && resumeSeq >= 0) this.#replay(conn, resumeSeq)
    this.#persistDevice(conn)
  }

  /** `sub` — add a topic subscription and open the DSH follow stream. */
  #onSub(conn, frame) {
    const topic = typeof frame.topic === 'string' ? frame.topic.trim() : ''
    if (!validTopic(topic)) {
      this.#send(conn, errorFrame('invalid_request', 'topic must be `global` or `session:<id>`'), 'error')
      return
    }
    const id = typeof frame.id === 'string' && frame.id !== '' ? frame.id.slice(0, 128) : `sub-${conn.subs.size + 1}`
    if (conn.subs.size >= 256 && !conn.subs.has(id)) {
      this.#send(conn, errorFrame('invalid_request', 'too many subscriptions'), 'error')
      return
    }
    const before = this.#topicSubscriberCount(topic)
    conn.subs.set(id, topic)
    if (before === 0) this.#followTopic(topic)
    this.#persistDevice(conn)
  }

  /** `unsub` — remove a subscription by id (or topic when id is absent). */
  #onUnsub(conn, frame) {
    let ids = []
    if (typeof frame.id === 'string' && frame.id !== '') {
      ids = [frame.id]
    } else if (typeof frame.topic === 'string' && frame.topic !== '') {
      for (const [id, topic] of conn.subs) {
        if (topic === frame.topic) ids.push(id)
      }
    }
    for (const id of ids) {
      const topic = conn.subs.get(id)
      if (topic === undefined) continue
      conn.subs.delete(id)
      if (this.#topicSubscriberCount(topic) === 0) this.#releaseTopic(topic)
    }
    this.#persistDevice(conn)
  }

  /** `ack` — remember the last sequence number the device has seen. */
  #onAck(conn, frame) {
    const seq = Number(frame.seq)
    if (!Number.isFinite(seq) || seq < 0) return
    conn.lastSeq = Math.max(conn.lastSeq, Math.trunc(seq))
    const now = Date.now()
    if (now - conn.lastPersist >= 5_000) {
      conn.lastPersist = now
      this.#persistDevice(conn)
    }
  }

  /** Replay ring events after `hello.resume.seq`; gap → `resync`. */
  #replay(conn, resumeSeq) {
    const scope = new Set()
    let wantsGlobal = false
    for (const topic of conn.subs.values()) {
      if (topic === 'global') wantsGlobal = true
      else scope.add(topic)
    }
    // Subscriptions persisted from the previous connection may arrive here.
    if (conn.subs.size === 0 && conn.deviceId !== null) {
      try {
        const record = this.hub?.getDevice?.(conn.deviceId)
        for (const topic of record?.subscriptions ?? []) {
          if (topic === 'global') wantsGlobal = true
          else if (validTopic(topic)) scope.add(topic)
        }
      } catch {
        // ignore
      }
    }
    if (scope.size === 0 && !wantsGlobal) return
    const topics = wantsGlobal ? new Set(['global']) : scope
    let result = { events: [], gap: false }
    try {
      result = this.hub?.eventsSince?.(resumeSeq, topics) ?? result
    } catch (error) {
      this.logger.warn?.(`event replay failed: ${error?.message ?? error}`)
      result = { events: [], gap: true }
    }
    if (result?.gap === true) {
      this.#send(conn, { t: 'resync', reason: 'gap' }, 'resync')
      this.#sendReady(conn)
      return
    }
    for (const event of result?.events ?? []) {
      if (event === null || typeof event !== 'object') continue
      const topic = typeof event.topic === 'string' && event.topic !== '' ? event.topic : 'global'
      if (!wantsGlobal && !topics.has(topic)) continue
      this.#send(conn, {
        t: 'event',
        seq: typeof event.seq === 'number' ? event.seq : 0,
        id: event.id ?? null,
        topic,
        kind: typeof event.kind === 'string' ? event.kind : 'event',
        data: event.data ?? null,
        ts: typeof event.ts === 'number' ? event.ts : Date.now(),
      }, typeof event.kind === 'string' ? event.kind : 'event')
    }
  }

  /** Whether one connection should receive a topic. */
  #delivers(conn, topic) {
    if (conn.closed || conn.subs.size === 0) return false
    for (const subscribed of conn.subs.values()) {
      if (subscribed === 'global' || subscribed === topic) return true
    }
    return false
  }

  /** Number of connections subscribed to a topic. */
  #topicSubscriberCount(topic) {
    let count = 0
    for (const conn of this.connections) {
      for (const subscribed of conn.subs.values()) {
        if (subscribed === topic) {
          count += 1
          break
        }
      }
    }
    return count
  }

  /** Open a DSH follow stream when the first subscriber appears. */
  #followTopic(topic) {
    const sessionId = topicSessionId(topic)
    if (sessionId !== null) this.#follow(sessionId)
  }

  /** Release a DSH follow stream when the last subscriber leaves. */
  #releaseTopic(topic) {
    const sessionId = topicSessionId(topic)
    if (sessionId !== null) this.#unfollow(sessionId)
  }

  #follow(sessionId) {
    const dsh = this.#dsh
    if (dsh === null || typeof dsh.followSession !== 'function') return
    // A failed follow (e.g. `session/agent-busy` for a subagent session) is
    // reported once and not retried in a tight loop.
    const failedAt = this.failedFollows.get(sessionId)
    if (failedAt !== undefined && Date.now() - failedAt < FOLLOW_RETRY_MS) {
      this.#notifyFollowFailed(sessionId, null)
      return
    }
    try {
      const pending = dsh.followSession(sessionId)
      if (pending !== null && typeof pending?.then === 'function') {
        Promise.resolve(pending).then(
          () => this.failedFollows.delete(sessionId),
          (error) => this.#onFollowFailed(sessionId, error),
        )
      } else {
        this.failedFollows.delete(sessionId)
      }
    } catch (error) {
      this.#onFollowFailed(sessionId, error)
    }
  }

  /** A follow stream failed: tell only the devices that subscribed it. */
  #onFollowFailed(sessionId, error) {
    this.failedFollows.set(sessionId, Date.now())
    const code = typeof error?.code === 'string' && error.code !== '' ? error.code : 'unknown'
    this.logger.warn?.(`session/follow ${sessionId} failed (${code}); reporting follow_unavailable`)
    this.#notifyFollowFailed(sessionId, error)
  }

  /** Send `follow_unavailable` to every connection subscribed to one session. */
  #notifyFollowFailed(sessionId, error) {
    const topic = `session:${sessionId}`
    const subagent = typeof error?.code === 'string' && /agent-busy|subagent/i.test(error.code)
    const reason = subagent
      ? 'subagent sessions cannot be followed directly'
      : 'the session stream is unavailable'
    for (const conn of this.connections) {
      let subscribed = false
      for (const value of conn.subs.values()) {
        if (value === topic) {
          subscribed = true
          break
        }
      }
      if (!subscribed) continue
      this.#send(conn, errorFrame('follow_unavailable', reason), 'error')
    }
  }

  #unfollow(sessionId) {
    const dsh = this.#dsh
    if (dsh === null || typeof dsh.unfollowSession !== 'function') return
    try {
      const pending = dsh.unfollowSession(sessionId)
      if (pending !== null && typeof pending?.catch === 'function') {
        pending.catch((error) => this.logger.debug?.(`unfollow ${sessionId} failed: ${error?.message ?? error}`))
      }
    } catch (error) {
      this.logger.debug?.(`unfollow ${sessionId} failed: ${error?.message ?? error}`)
    }
  }

  /** Persist device subscriptions / lastSeq through the hub. */
  #persistDevice(conn) {
    if (conn.deviceId === null || this.closed) return
    try {
      const existing = this.hub?.getDevice?.(conn.deviceId)
      const record = existing !== null && typeof existing === 'object' ? { ...existing } : {}
      record.deviceId = conn.deviceId
      record.subscriptions = [...new Set(conn.subs.values())]
      record.lastSeq = conn.lastSeq
      record.lastSeenAt = Date.now()
      record.lastSeen = record.lastSeenAt
      if (conn.remoteAddress !== '') record.remoteAddress = conn.remoteAddress
      this.hub?.upsertDevice?.(record)
    } catch (error) {
      this.logger.debug?.(`device persist failed: ${error?.message ?? error}`)
    }
  }

  /** Force-close every connection belonging to one device (revocation). */
  #disconnectDevice(deviceId) {
    const conn = this.byDevice.get(deviceId)
    if (conn === undefined) return
    this.#send(conn, errorFrame('unauthenticated', 'device token revoked'), 'error')
    this.#closeConn(conn, CLOSE_REPLACED, 'device revoked')
  }

  /** Send one frame, queueing when the socket is backed up. */
  #send(conn, frame, kind = '') {
    if (conn.closed || conn.ws.readyState !== WebSocket.OPEN) return
    const frameKind = typeof kind === 'string' && kind !== ''
      ? kind
      : typeof frame?.kind === 'string'
        ? frame.kind
        : typeof frame?.t === 'string'
          ? frame.t
          : 'event'
    const critical = CRITICAL_KINDS.has(frameKind)
    // Live deltas are the cheapest thing to lose under back-pressure.
    if (!critical && frameKind === 'message.delta' && (conn.queue.length > 0 || conn.ws.bufferedAmount >= SEND_HIGH_WATER)) {
      return
    }
    let json
    try {
      json = JSON.stringify(frame)
    } catch {
      return
    }
    const bytes = Buffer.byteLength(json)
    if (conn.queue.length === 0 && conn.ws.bufferedAmount < SEND_HIGH_WATER) {
      this.#write(conn, json)
      return
    }
    conn.queue.push({ json, bytes, critical, kind: frameKind })
    conn.queuedBytes += bytes
    if (conn.queue.length > SEND_QUEUE_MAX || conn.queuedBytes > SEND_QUEUE_BYTES) {
      this.#shed(conn)
      if (conn.closed) return
    }
    this.#scheduleFlush(conn)
  }

  #write(conn, json) {
    try {
      conn.ws.send(json)
    } catch (error) {
      this.logger.debug?.(`send failed: ${error?.message ?? error}`)
      this.#closeConn(conn, 1011, 'send failed')
    }
  }

  #scheduleFlush(conn) {
    if (conn.flushTimer !== null || conn.closed) return
    conn.flushTimer = setTimeout(() => {
      conn.flushTimer = null
      this.#flush(conn)
    }, 8)
    if (typeof conn.flushTimer?.unref === 'function') conn.flushTimer.unref()
  }

  #flush(conn) {
    if (conn.closed || conn.ws.readyState !== WebSocket.OPEN) return
    while (conn.queue.length > 0 && conn.ws.bufferedAmount < FLUSH_LOW_WATER) {
      const item = conn.queue.shift()
      conn.queuedBytes = Math.max(0, conn.queuedBytes - item.bytes)
      this.#write(conn, item.json)
      if (conn.closed) return
    }
    if (conn.queue.length > 0) this.#scheduleFlush(conn)
  }

  /** Drop the least valuable queued frame, or disconnect the consumer. */
  #shed(conn) {
    for (const predicate of [
      (item) => item.kind === 'message.delta',
      (item) => item.critical !== true,
    ]) {
      const index = conn.queue.findIndex(predicate)
      if (index >= 0) {
        const [dropped] = conn.queue.splice(index, 1)
        conn.queuedBytes = Math.max(0, conn.queuedBytes - dropped.bytes)
        this.logger.debug?.(`dropped ${dropped.kind} for slow consumer ${conn.deviceId ?? 'anonymous'}`)
        return
      }
    }
    this.logger.warn?.(`disconnecting slow consumer ${conn.deviceId ?? 'anonymous'} (queue full)`)
    this.#send(conn, errorFrame('slow_consumer', 'the client is not consuming events fast enough'), 'error')
    this.#closeConn(conn, CLOSE_SLOW, 'slow consumer')
  }

  /** Server heartbeat: ping live sockets, kill silent ones, flush queues. */
  #heartbeat() {
    if (this.closed) return
    const now = Date.now()
    const idleMs = Math.max(5_000, Number(this.config.idleTimeoutMs) || 45_000)
    for (const conn of [...this.connections]) {
      if (conn.closed) continue
      if (now - conn.lastSeen > idleMs) {
        this.#closeConn(conn, CLOSE_IDLE, 'idle timeout')
        continue
      }
      try {
        conn.ws.ping()
      } catch {
        this.#closeConn(conn, 1011, 'ping failed')
        continue
      }
      this.#flush(conn)
    }
  }

  /** Close one connection and clean up its bookkeeping. */
  #closeConn(conn, code = 1000, reason = '') {
    if (conn.closed) return
    conn.closed = true
    try {
      conn.ws.close(code, reason)
    } catch {
      try {
        conn.ws.terminate()
      } catch {
        // ignore
      }
    }
    // Guarantee cleanup even when the peer never completes the close handshake.
    const timer = setTimeout(() => {
      try {
        conn.ws.terminate()
      } catch {
        // ignore
      }
      this.#cleanup(conn)
    }, 1_500)
    if (typeof timer?.unref === 'function') timer.unref()
    this.#cleanup(conn)
  }

  /** Remove all traces of a connection and release its follow streams. */
  #cleanup(conn) {
    if (this.connections.delete(conn) === false) return
    if (conn.flushTimer !== null) {
      clearTimeout(conn.flushTimer)
      conn.flushTimer = null
    }
    conn.queue.length = 0
    conn.queuedBytes = 0
    if (conn.deviceId !== null && this.byDevice.get(conn.deviceId) === conn) this.byDevice.delete(conn.deviceId)
    const topics = [...new Set(conn.subs.values())]
    for (const topic of topics) {
      if (this.#topicSubscriberCount(topic) === 0) this.#releaseTopic(topic)
    }
    conn.subs.clear()
    if (conn.deviceId !== null && !this.closed) {
      try {
        const existing = this.hub?.getDevice?.(conn.deviceId)
        const record = existing !== null && typeof existing === 'object' ? { ...existing } : {}
        const persisted = Array.isArray(record.subscriptions) ? record.subscriptions.filter(validTopic) : []
        record.deviceId = conn.deviceId
        // Keep the subscriptions so a reconnect can restore them.
        record.subscriptions = [...new Set([...persisted, ...topics])]
        record.lastSeq = conn.lastSeq
        record.lastSeenAt = Date.now()
        record.lastSeen = record.lastSeenAt
        this.hub?.upsertDevice?.(record)
      } catch {
        // ignore
      }
    }
    conn.closed = true
  }

  /** Write a raw HTTP rejection for an upgrade we refuse before `ws` takes over. */
  #rejectUpgrade(socket, status, code, message) {
    const text = REASONS[status] ?? 'Error'
    const body = JSON.stringify({ ok: false, error: { code: code ?? 'forbidden_source', message: message ?? text } })
    try {
      socket.write(
        `HTTP/1.1 ${status} ${text}\r\n` +
        'Connection: close\r\n' +
        'Content-Type: application/json; charset=utf-8\r\n' +
        `Content-Length: ${Buffer.byteLength(body)}\r\n\r\n` +
        body,
      )
    } catch {
      // best effort
    }
    try {
      socket.destroy()
    } catch {
      // ignore
    }
  }
}

export default StreamServer
