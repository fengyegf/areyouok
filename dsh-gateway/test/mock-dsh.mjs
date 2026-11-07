/**
 * Minimal fake DSH host for dsh-gateway tests.
 *
 * It reproduces just enough of the real DSH 0.2.2 web surface that
 * `lib/dsh.js` (DshLoopbackClient) can talk to it unchanged. Wire shapes come
 * from `docs/dsh-internal-api-notes.md` and the unpacked sources in
 * `.dsh-recon/`; the parts that matter most for correctness:
 *
 *   - `GET /?token=<launchToken>` -> `303` + `Set-Cookie: dsh-auth-test=v1.test`
 *   - `POST /api/<namespace>/<method>` client-request envelope. `payload.args`
 *     keys are the descriptor **wire names**: `{"request":{…}}` for most
 *     session methods, `{"_request":{}}` for `session/list`, `{}` for
 *     `session/modelCatalog` / `permissionPresets/catalog`, a flat
 *     `{clientId,eventId,outcome}` for `$events/result`, and a flat
 *     `{agentId,line,submittedAttachments}` for `commands/execute`.
 *     Extra/missing keys answer `ok:false` with
 *     `gateway/arguments-invalid` (HTTP 200, like the real host).
 *   - `WS /api/remote.mux`, cookie authenticated. `open` payloads are
 *     `{"args":{"request":{…}}}` for `session/follow` and `{"args":{}}` for
 *     `$events` / `session/control` / `workspace/follow`. A duplicate
 *     `streamId` closes the socket with 1008; a malformed open answers
 *     `error` (+ `end`) on that stream.
 *   - Server pings every 2s and terminates a socket after two missed beats.
 *
 * Logical streams mirror the real Host frames:
 *
 *   $events        -> {type:'ready',clientId,host} | {type:'emit',event,args[]}
 *                     | {type:'waterfall',event,eventId,agentId,request}
 *                     | {type:'cancel',eventId}
 *   session/control-> {type:'baseline',value:{projections:{<sessionId>:{asOfSeq,values}}}}
 *                     | {type:'projection',sessionId,seq,changes:[{key,val}]}
 *   workspace/follow-> {type:'baseline',value:{items,archivedSessionIds,pinnedSessionIds}}
 *                     | {type:'upsert',workspace} | {type:'remove',workspaceId}
 *                     | {type:'order',workspaceIds} | {type:'archived',archivedSessionIds}
 *                     | {type:'pinned',pinnedSessionIds} | {type:'replace',value}
 *   session/follow -> {type:'snapshot',header,cursor,records,hasMore,projections}
 *                     | {type:'event',event:{type,seq,time,data}}
 *                     | {type:'assistant-stream',frame}
 *
 * @module dsh-gateway/test/mock-dsh
 */

import { randomUUID } from 'node:crypto'
import { createServer } from 'node:http'
import { WebSocketServer } from 'ws'

/** Cookie name minted by the mock (the real host hashes the authority). */
export const MOCK_COOKIE_NAME = 'dsh-auth-test'
/** Cookie value minted by the mock (real host: `v1.<base64url payload>.<sig>`). */
export const MOCK_COOKIE_VALUE = 'v1.test'

/** Default server heartbeat interval (real host: 2s, terminate after 2 misses). */
export const DEFAULT_PING_MS = 2_000

/** Missed heartbeats before the socket is terminated (real host: 2). */
const MAX_MISSED_HEARTBEATS = 2

/** Structured failure a mock RPC handler can throw to answer `ok:false`. */
export class MockDshError extends Error {
  /**
   * @param {string} code - wire error code, e.g. `gateway/arguments-invalid`.
   * @param {string} message - human readable message.
   * @param {Record<string, unknown>} [details] - JSON details.
   */
  constructor(code, message, details = {}) {
    super(message)
    this.name = 'MockDshError'
    this.code = code
    this.details = details
  }
}

/** Structured-clone-safe copy of a JSON value. */
export function deepClone(value) {
  if (value === undefined) return undefined
  return JSON.parse(JSON.stringify(value))
}

const isRecord = (value) => typeof value === 'object' && value !== null && !Array.isArray(value)
const isPlainObject = (value) => {
  if (!isRecord(value)) return false
  const prototype = Object.getPrototypeOf(value)
  return prototype === Object.prototype || prototype === null
}

/**
 * Wire argument descriptors per unary endpoint (notes §2.1).
 * `keys: []` means the endpoint takes no arguments (`{"args":{}}`).
 */
export const RPC_WIRE = Object.freeze({
  'session/list': { keys: ['_request'] },
  'session/create': { keys: ['request'] },
  'session/state': { keys: ['request'] },
  'session/events': { keys: ['request'] },
  'session/prompt': { keys: ['request'] },
  'session/cancel': { keys: ['request'] },
  'session/selectModel': { keys: ['request'] },
  'session/modelCatalog': { keys: [] },
  'permissionPresets/catalog': { keys: [] },
  // `commands/execute` is a flat descriptor: scope `agentId` + two json args.
  'commands/execute': { keys: ['agentId', 'line', 'submittedAttachments'], flat: true },
  '$events/result': { keys: ['clientId', 'eventId', 'outcome'], flat: true },
})

/**
 * Stream endpoints and their wire argument descriptors.
 * `keys: []` means `payload.args` must be an empty object.
 */
export const STREAM_WIRE = Object.freeze({
  '$events': { keys: [] },
  'session/control': { keys: [] },
  'workspace/follow': { keys: [] },
  'session/follow': { keys: ['request'], requestKey: 'request' },
})

/** Build the `gateway/arguments-invalid` failure the real host emits. */
function argumentsInvalid(endpoint, detail) {
  return new MockDshError(
    'gateway/arguments-invalid',
    `typert gateway: ${endpoint}: args fields do not match the descriptor: ${detail}`,
    { endpoint },
  )
}

/**
 * Validate one `payload.args` object against its wire descriptor.
 * @param {string} endpoint
 * @param {object} args
 * @param {{keys:string[],flat?:boolean}} wire
 * @returns {{request:object, flat:object}}
 */
export function parseWireArgs(endpoint, args, wire) {
  if (!isPlainObject(args)) {
    throw argumentsInvalid(endpoint, 'args must be a plain object')
  }
  const keys = [ ...wire.keys ]
  for (const key of Object.keys(args)) {
    if (!keys.includes(key)) throw argumentsInvalid(endpoint, `unexpected ${JSON.stringify(key)}`)
  }
  if (wire.flat === true) {
    for (const key of keys) {
      if (!Object.hasOwn(args, key)) throw argumentsInvalid(endpoint, `missing ${JSON.stringify(key)}`)
    }
    return { request: args, flat: args }
  }
  if (keys.length === 0) return { request: {}, flat: args }
  const key = keys[0]
  if (!Object.hasOwn(args, key)) throw argumentsInvalid(endpoint, `missing ${JSON.stringify(key)}`)
  if (!isPlainObject(args[key])) throw argumentsInvalid(endpoint, `${JSON.stringify(key)} must be a plain object`)
  return { request: args[key], flat: args }
}

/** Default session summaries (`SessionSummary`, notes §5.1). */
export function defaultSessions() {
  return [
    {
      sessionId: 's-alpha',
      updatedAt: 1_700_000_000_000,
      running: false,
      blank: false,
      agentAvailable: true,
      formatStatus: 'current',
      cwd: 'E:/work/alpha',
    },
    {
      sessionId: 's-beta',
      updatedAt: 1_700_000_500_000,
      running: true,
      blank: false,
      agentAvailable: true,
      formatStatus: 'current',
      cwd: 'E:/work/beta',
    },
  ]
}

/** Default workspace projections (`workspace/follow` baseline items, notes §6.1). */
export function defaultWorkspaces() {
  return [
    {
      workspaceId: 'w-alpha',
      path: 'E:/work/alpha',
      title: 'alpha',
      sessionIds: ['s-alpha'],
      createdAt: '2026-11-01T00:00:00.000Z',
      updatedAt: '2026-11-01T00:00:00.000Z',
    },
    {
      workspaceId: 'w-beta',
      path: 'E:/work/beta',
      title: 'beta',
      sessionIds: ['s-beta'],
      createdAt: '2026-11-01T00:00:00.000Z',
      updatedAt: '2026-11-02T00:00:00.000Z',
    },
  ]
}

/** Default `session/modelCatalog` projection (real shape, notes §5.9). */
export function defaultModelCatalog() {
  return {
    default: { provider: 'deepseek', model: 'deepseek-chat', reasoningEffort: 'off' },
    routableProviders: ['deepseek'],
    groups: [
      {
        id: 'deepseek',
        name: 'DeepSeek',
        models: [
          {
            id: 'deepseek-chat',
            name: 'DeepSeek Chat',
            reasoning: { efforts: [], defaultEffort: 'off' },
          },
          {
            id: 'deepseek-reasoner',
            name: 'DeepSeek Reasoner',
            reasoning: { efforts: [{ id: 'high', name: 'High' }], defaultEffort: 'high' },
            badge: { text: 'beta', tone: 'info' },
          },
        ],
      },
    ],
    failures: [],
  }
}

/** Default `permissionPresets/catalog` value (real DSH 0.2.2 shape). */
export function defaultPermissionCatalog() {
  return {
    options: [
      { value: 'read-only', name: 'read-only' },
      { value: 'workspace-write', name: 'workspace-write' },
      { value: 'danger-full-access', name: 'danger-full-access' },
    ],
    defaultOptions: [
      { value: 'read-only', name: 'read-only' },
      { value: 'workspace-write', name: 'workspace-write' },
      { value: 'danger-full-access', name: 'danger-full-access' },
    ],
    defaultPreset: 'workspace-write',
  }
}

/** One durable session event entry (`SessionEventEntry`, notes §5.6). */
export function sessionEvent(type, seq, data, time = 1_700_000_000_000 + seq) {
  return { type: 'event', event: { type, seq, time, data } }
}

/**
 * A fake DSH host.
 *
 * @example
 * const mock = new MockDsh()
 * await mock.start()
 * mock.emit('api-session/status', ['s-alpha', true])
 * await mock.stop()
 */
export class MockDsh {
  /**
   * @param {object} [options]
   * @param {string} [options.launchToken] - token accepted by `GET /?token=`.
   * @param {Array<object>} [options.sessions] - initial `SessionSummary` rows.
   * @param {Array<object>} [options.workspaces] - initial workspace projections.
   * @param {object} [options.modelCatalog] - `session/modelCatalog` result.
   * @param {object} [options.permissionCatalog] - `permissionPresets/catalog` result.
   * @param {number} [options.pingMs] - WS ping interval (0 disables; default 2000).
   * @param {(message: object) => void} [options.onLog] - debug sink.
   */
  constructor(options = {}) {
    this.launchToken = options.launchToken ?? 'test-launch-token'
    this.cookieName = options.cookieName ?? MOCK_COOKIE_NAME
    this.cookieValue = options.cookieValue ?? MOCK_COOKIE_VALUE
    this.host = '127.0.0.1'
    this.port = 0
    this.origin = ''
    this.pingMs = options.pingMs ?? DEFAULT_PING_MS
    this.onLog = options.onLog ?? null

    /** @type {Array<{method:string,args:object,rpcId:string,at:number}>} */
    this.calls = []
    /** @type {Array<{streamId:string,endpoint:string,value:unknown,at:number}>} */
    this.uplinkItems = []
    /** @type {Array<{clientId:string,eventId:string,outcome:object,known:boolean,at:number}>} */
    this.resultCalls = []
    /** @type {Set<import('ws').WebSocket>} */
    this.muxClients = new Set()
    /** @type {Map<import('ws').WebSocket, Map<string,{streamId:string,endpoint:string,args:object,request:object,clientId?:string}>>} */
    this.streams = new Map()
    /** @type {Map<string, object>} unresolved waterfall frames, keyed by eventId. */
    this.pending = new Map()
    /** @type {Array<object>} */
    this.sessions = deepClone(options.sessions ?? defaultSessions())
    /** @type {Array<object>} */
    this.workspaces = deepClone(options.workspaces ?? defaultWorkspaces())
    this.modelCatalog = deepClone(options.modelCatalog ?? defaultModelCatalog())
    this.permissionCatalog = deepClone(options.permissionCatalog ?? defaultPermissionCatalog())
    /** @type {Map<string, Array<object>>} sessionId -> SessionEventEntry[] */
    this.history = new Map()
    /** @type {Map<string, {mode:'once'|'always', error:object}>} */
    this.failures = new Map()
    /** @type {Map<string, unknown|Function>} */
    this.results = new Map()
    /** When true every RPC/upgrade is refused, simulating a dead host. */
    this.disabled = false
    /** Number of `ready` frames delivered (== number of `$events` opens). */
    this.readyCount = 0

    this._server = null
    this._wss = null
    this._pingTimer = null
    this._missed = new WeakMap()
    this._seedHistory()
  }

  // ---------------------------------------------------------------- lifecycle

  /** Start listening on an ephemeral loopback port. @returns {Promise<{origin:string,port:number}>} */
  async start() {
    if (this._server !== null) return { origin: this.origin, port: this.port }
    const server = createServer((req, res) => {
      this._handleHttp(req, res).catch((error) => {
        this._log('http handler failed', { error: String(error?.message ?? error) })
        if (!res.headersSent) {
          res.writeHead(500, { 'content-type': 'text/plain; charset=utf-8' })
        }
        res.end('mock internal error')
      })
    })
    const wss = new WebSocketServer({ noServer: true })
    server.on('upgrade', (req, socket, head) => this._handleUpgrade(req, socket, head, wss))
    this._wss = wss
    this._server = server
    await new Promise((resolve, reject) => {
      const onError = (error) => {
        server.removeListener('listening', onListening)
        reject(error)
      }
      const onListening = () => {
        server.removeListener('error', onError)
        resolve()
      }
      server.once('error', onError)
      server.once('listening', onListening)
      server.listen(0, this.host)
    })
    const address = server.address()
    this.port = typeof address === 'object' && address !== null ? address.port : 0
    this.origin = `http://${this.host}:${this.port}`
    if (this.pingMs > 0) {
      // Real host: ping every 2s, terminate after two missed beats (no close frame).
      this._pingTimer = setInterval(() => {
        for (const client of this.muxClients) {
          if (client.readyState !== 1) continue
          const missed = this._missed.get(client) ?? 0
          if (missed >= MAX_MISSED_HEARTBEATS) {
            try {
              client.terminate()
            } catch {
              // ignore
            }
            continue
          }
          this._missed.set(client, missed + 1)
          try {
            client.ping()
          } catch {
            // ignore
          }
        }
      }, this.pingMs)
      this._pingTimer.unref?.()
    }
    this._log('started', { origin: this.origin })
    return { origin: this.origin, port: this.port }
  }

  /** Stop listening and drop every socket. Safe to await twice. */
  async stop() {
    if (this._pingTimer !== null) {
      clearInterval(this._pingTimer)
      this._pingTimer = null
    }
    for (const client of this.muxClients) client.terminate()
    this.muxClients.clear()
    this.streams.clear()
    const wss = this._wss
    this._wss = null
    if (wss !== null) {
      await new Promise((resolve) => wss.close(() => resolve()))
    }
    const server = this._server
    this._server = null
    if (server !== null) {
      server.closeAllConnections?.()
      await new Promise((resolve) => server.close(() => resolve()))
    }
    this._log('stopped')
  }

  /** @returns {boolean} whether the mock currently listens. */
  get started() {
    return this._server !== null
  }

  // ---------------------------------------------------------------- control

  /** Accept requests again (see {@link disable}). */
  enable() {
    this.disabled = false
  }

  /** Refuse every RPC and mux upgrade until {@link enable} (simulates a dead DSH). */
  disable() {
    this.disabled = true
  }

  /** Forget all recorded calls/items/results. */
  reset() {
    this.calls = []
    this.uplinkItems = []
    this.resultCalls = []
  }

  /**
   * Filter recorded unary calls.
   * @param {string} [method]
   * @returns {Array<{method:string,args:object,rpcId:string,at:number}>}
   */
  callsFor(method) {
    return method === undefined ? [...this.calls] : this.calls.filter((call) => call.method === method)
  }

  /** @returns {{method:string,args:object,rpcId:string,at:number}|undefined} */
  lastCall(method) {
    for (let index = this.calls.length - 1; index >= 0; index -= 1) {
      if (method === undefined || this.calls[index].method === method) return this.calls[index]
    }
    return undefined
  }

  /**
   * Resolve with the first call matching `method` observed after this call.
   * @param {string} method
   * @param {{timeoutMs?:number, predicate?:(call:object)=>boolean}} [options]
   */
  waitForCall(method, options = {}) {
    const timeoutMs = options.timeoutMs ?? 5_000
    const predicate = options.predicate ?? (() => true)
    const existing = this.calls.find((call) => call.method === method && predicate(call))
    if (existing !== undefined) return Promise.resolve(existing)
    return new Promise((resolve, reject) => {
      const started = Date.now()
      const timer = setInterval(() => {
        const hit = this.calls.find((call) => call.method === method && predicate(call))
        if (hit !== undefined) {
          clearInterval(timer)
          resolve(hit)
          return
        }
        if (Date.now() - started > timeoutMs) {
          clearInterval(timer)
          reject(new Error(`mock-dsh: timed out waiting for ${method}`))
        }
      }, 10)
    })
  }

  /**
   * Program the result of one unary method (a value, or a function of `(request, call)`).
   * @param {string} method
   * @param {unknown|((request:object, call:object) => unknown)} value
   */
  setResult(method, value) {
    this.results.set(method, value)
  }

  /**
   * Make one method fail with a wire error.
   * @param {string} method
   * @param {{code?:string, message?:string, details?:object, once?:boolean}} [options]
   *   `once:true` (default) fails the next call only; `once:false` fails until {@link clearFailure}.
   */
  failMethod(method, options = {}) {
    this.failures.set(method, {
      mode: options.once === false ? 'always' : 'once',
      error: {
        code: options.code ?? 'gateway/internal',
        message: options.message ?? `mock failure for ${method}`,
        details: options.details ?? {},
      },
    })
  }

  /** Remove a failure programmed with {@link failMethod}. */
  clearFailure(method) {
    this.failures.delete(method)
  }

  /**
   * Broadcast one host event on every open `$events` stream.
   * @param {string} event - event name, e.g. `api-session/status`.
   * @param {Array<unknown>} args - positional emit arguments.
   */
  emit(event, args) {
    this._pushEventsFrame({ type: 'emit', event, args: Array.isArray(args) ? args : [args] })
  }

  /**
   * Deliver one waterfall request (approval / user questions) and keep it
   * pending until it is answered, cancelled or replayed.
   * @param {'approval/request'|'user-questions/request'|string} event
   * @param {object} options
   * @param {object} [options.request] - waterfall request payload.
   * @param {string} [options.agentId] - owning session id.
   * @param {string} [options.eventId] - reuse a known id (to emulate a replay).
   * @returns {{type:'waterfall',event:string,eventId:string,agentId:string,request:object}}
   */
  waterfall(event, options = {}) {
    const frame = {
      type: 'waterfall',
      event,
      eventId: options.eventId ?? `ev-${randomUUID()}`,
      agentId: options.agentId ?? 's-alpha',
      request: options.request ?? {},
    }
    this.pending.set(frame.eventId, frame)
    this._pushEventsFrame(frame)
    return frame
  }

  /**
   * Cancel one pending waterfall (the desktop GUI answered first).
   * @param {string} eventId
   * @returns {boolean} whether the event was still pending.
   */
  cancel(eventId) {
    const existed = this.pending.delete(eventId)
    this._pushEventsFrame({ type: 'cancel', eventId })
    return existed
  }

  /** Re-deliver every unresolved waterfall to the current `$events` clients. */
  replayPending() {
    for (const frame of this.pending.values()) this._pushEventsFrame(frame)
  }

  /** @returns {Array<object>} pending waterfall frames. */
  pendingWaterfalls() {
    return [...this.pending.values()]
  }

  // -------------------------------------------------------------- WS control

  /** Abruptly drop every mux socket (no close handshake) to force a reconnect. */
  dropMux() {
    for (const client of this.muxClients) {
      try {
        client.terminate()
      } catch {
        // ignore
      }
    }
    this.muxClients.clear()
    this.streams.clear()
  }

  /** Send a WebSocket close frame to every mux socket. */
  closeMux(code = 1012, reason = 'mock restart') {
    for (const client of this.muxClients) {
      try {
        client.close(code, reason)
      } catch {
        // ignore
      }
    }
  }

  /** @returns {number} open mux sockets. */
  muxCount() {
    return this.muxClients.size
  }

  /** @returns {number} open logical streams across all sockets. */
  streamCount() {
    let total = 0
    for (const map of this.streams.values()) total += map.size
    return total
  }

  /**
   * Open logical streams, optionally grouped by endpoint.
   * @param {string} [endpoint]
   * @returns {Array<{streamId:string,endpoint:string,args:object,request:object,clientId?:string}>}
   */
  openStreams(endpoint) {
    const all = []
    for (const map of this.streams.values()) {
      for (const stream of map.values()) {
        if (endpoint === undefined || stream.endpoint === endpoint) all.push(stream)
      }
    }
    return all
  }

  // ------------------------------------------------------- session/workspace

  /**
   * Push one `session/control` projection frame and update the local summary.
   * @param {string} sessionId
   * @param {Array<{key:string,val:unknown}>} changes
   */
  sessionProjection(sessionId, changes) {
    this._pushControlFrame({
      type: 'projection',
      sessionId,
      seq: (this._controlSeq = (this._controlSeq ?? 0) + 1),
      changes,
    })
  }

  /**
   * Replace the session list and push a fresh `session/control` baseline.
   * @param {Array<object>} sessions
   */
  setSessions(sessions) {
    this.sessions = deepClone(sessions)
    this.pushControlBaseline()
  }

  /** Push a fresh `session/control` baseline built from {@link sessions}. */
  pushControlBaseline() {
    this._pushControlFrame({ type: 'baseline', value: { projections: this._projections() } })
  }

  /**
   * Emit a host session-status event and update the local summary.
   * @param {string} sessionId
   * @param {boolean} running
   */
  sessionStatus(sessionId, running) {
    const session = this.sessions.find((item) => item.sessionId === sessionId)
    if (session !== undefined) session.running = running
    this.emit('api-session/status', [sessionId, running])
  }

  /**
   * Emit a host session-added event.
   * @param {object} summary - `SessionSummary`.
   */
  sessionAdded(summary) {
    const index = this.sessions.findIndex((item) => item.sessionId === summary.sessionId)
    if (index === -1) this.sessions.push(deepClone(summary))
    else this.sessions[index] = deepClone(summary)
    this.emit('api-session/added', [summary])
  }

  /**
   * Emit a host session-removed event.
   * @param {string} sessionId
   */
  sessionRemoved(sessionId) {
    this.sessions = this.sessions.filter((item) => item.sessionId !== sessionId)
    this.emit('api-session/removed', [sessionId])
  }

  /**
   * Replace the workspace baseline and push it on `workspace/follow`.
   * @param {Array<object>} workspaces
   */
  setWorkspaces(workspaces) {
    this.workspaces = deepClone(workspaces)
    this.pushWorkspaceBaseline()
  }

  /** Push the current workspace baseline on every `workspace/follow` stream. */
  pushWorkspaceBaseline() {
    this._pushWorkspaceFrame({
      type: 'baseline',
      value: {
        items: deepClone(this.workspaces),
        archivedSessionIds: [],
        pinnedSessionIds: [],
      },
    })
  }

  /**
   * Push one workspace upsert frame.
   * @param {object} workspace
   */
  workspaceUpsert(workspace) {
    const index = this.workspaces.findIndex((item) => item.workspaceId === workspace.workspaceId)
    if (index === -1) this.workspaces.push(deepClone(workspace))
    else this.workspaces[index] = deepClone(workspace)
    this._pushWorkspaceFrame({ type: 'upsert', workspace: deepClone(workspace) })
  }

  /**
   * Push one workspace remove frame.
   * @param {string} workspaceId
   */
  workspaceRemove(workspaceId) {
    this.workspaces = this.workspaces.filter((item) => item.workspaceId !== workspaceId)
    this._pushWorkspaceFrame({ type: 'remove', workspaceId })
  }

  /**
   * Replace the recorded history of one session (used by `session/events` and
   * by the `session/follow` snapshot).
   * @param {string} sessionId
   * @param {Array<object>} records - `SessionEventEntry[]`.
   */
  setHistory(sessionId, records) {
    this.history.set(sessionId, deepClone(records))
  }

  /** @returns {Array<object>} recorded `SessionEventEntry[]` for a session. */
  getHistory(sessionId) {
    return deepClone(this.history.get(sessionId) ?? [])
  }

  /**
   * Append one durable session event and fan it out to `session/follow` streams.
   * @param {string} sessionId
   * @param {string} type - e.g. `user/message`, `assistant/message`, `turn/start`.
   * @param {object} data - event payload.
   * @param {number} [seq] - explicit sequence (defaults to last + 1).
   * @returns {{type:'event',event:{type:string,seq:number,time:number,data:object}}}
   */
  sessionEvent(sessionId, type, data, seq) {
    const records = this.history.get(sessionId) ?? []
    const last = records.length === 0 ? 0 : records[records.length - 1].event.seq
    const entry = sessionEvent(type, seq ?? last + 1, data)
    records.push(entry)
    this.history.set(sessionId, records)
    this._pushSessionFrame(sessionId, entry)
    return entry
  }

  /**
   * Push one assistant-stream presentation frame on a followed session.
   * @param {string} sessionId
   * @param {object} frame - `SessionAssistantStreamFrame`.
   */
  assistantStream(sessionId, frame) {
    this._pushSessionFrame(sessionId, { type: 'assistant-stream', frame })
  }

  /**
   * Send an arbitrary `session/follow` frame (snapshot / event / assistant-stream).
   * @param {string} sessionId
   * @param {object} frame
   */
  pushSessionFrame(sessionId, frame) {
    this._pushSessionFrame(sessionId, frame)
  }

  // -------------------------------------------------------------- internals

  _log(message, meta) {
    if (typeof this.onLog === 'function') this.onLog(message, meta)
  }

  _projections() {
    const projections = {}
    for (const session of this.sessions) {
      projections[session.sessionId] = {
        asOfSeq: 0,
        values: { title: session.title ?? session.sessionId, workingDirectory: session.cwd ?? null },
      }
    }
    return projections
  }

  _seedHistory() {
    // Shapes follow the real Host (notes §5.6/§7.3): `user/message` data *is*
    // the UserMessage (`{id, role, content, source}`), `assistant/message` data
    // wraps it with `{turn, step, message, stream}`.
    this.history.set('s-alpha', [
      sessionEvent('turn/start', 1, { turn: 1 }),
      sessionEvent('user/message', 2, {
        id: 'msg-user-1',
        role: 'user',
        content: [{ type: 'text', text: 'hello alpha' }],
        source: { kind: 'user' },
      }),
      sessionEvent('assistant/message', 3, {
        turn: 1,
        step: 1,
        message: { role: 'assistant', content: [{ type: 'text', text: 'hi from alpha' }] },
        stream: [],
      }),
      sessionEvent('turn/end', 4, { turn: 1, reason: 'stop' }),
    ])
  }

  _hasCookie(req) {
    const header = req.headers.cookie
    if (typeof header !== 'string') return false
    const wanted = `${this.cookieName}=${this.cookieValue}`
    return header
      .split(';')
      .map((part) => part.trim())
      .includes(wanted)
  }

  async _handleHttp(req, res) {
    const url = new URL(req.url ?? '/', 'http://mock-dsh.invalid')
    this._log('http', { method: req.method, path: url.pathname })

    if (url.pathname === '/' && req.method === 'GET') {
      const tokens = url.searchParams.getAll('token')
      if (tokens.length === 1 && tokens[0] === this.launchToken) {
        res.writeHead(303, {
          'cache-control': 'no-store',
          location: './',
          'referrer-policy': 'no-referrer',
          'set-cookie': `${this.cookieName}=${this.cookieValue}; Max-Age=2592000; Path=/; HttpOnly; SameSite=Strict`,
        })
        res.end()
        return
      }
      res.writeHead(401, { 'content-type': 'text/plain; charset=utf-8', 'cache-control': 'no-store' })
      res.end('dsh web authentication required; reopen the URL printed by dsh web.\n')
      return
    }

    if (url.pathname.startsWith('/api/')) {
      if (req.method !== 'POST') {
        res.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' })
        res.end('not found')
        return
      }
      if (this.disabled) {
        res.writeHead(503, { 'content-type': 'text/plain; charset=utf-8' })
        res.end('mock dsh disabled')
        return
      }
      if (!this._hasCookie(req)) {
        res.writeHead(401, { 'content-type': 'text/plain; charset=utf-8', 'cache-control': 'no-store' })
        res.end('unauthorized')
        return
      }
      const contentType = String(req.headers['content-type'] ?? '')
      if (!/^application\/json\b/i.test(contentType)) {
        res.writeHead(415, { 'content-type': 'text/plain; charset=utf-8' })
        res.end('content type must be application/json')
        return
      }
      const endpoint = decodeURIComponent(url.pathname.slice('/api/'.length))
      const wire = RPC_WIRE[endpoint]
      if (wire === undefined) {
        // The real host 404s endpoints nobody claimed.
        res.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' })
        res.end('not found')
        return
      }
      const body = await this._readBody(req)
      let envelope
      try {
        envelope = JSON.parse(body)
      } catch {
        res.writeHead(400, { 'content-type': 'text/plain; charset=utf-8' })
        res.end('body is not JSON')
        return
      }
      if (!isRecord(envelope) || envelope.type !== 'client-request' || typeof envelope.rpcId !== 'string') {
        this._writeJson(res, 200, {
          type: 'server-response',
          rpcId: 'invalid-request',
          result: { ok: false, error: { code: 'gateway/bad-request', message: 'invalid client-request message', details: {} } },
        })
        return
      }
      const rpcId = envelope.rpcId
      if (envelope.method !== endpoint) {
        this._writeJson(res, 200, {
          type: 'server-response',
          rpcId,
          result: {
            ok: false,
            error: {
              code: 'gateway/bad-request',
              message: `method ${JSON.stringify(envelope.method)} does not match endpoint ${JSON.stringify(endpoint)}`,
              details: {},
            },
          },
        })
        return
      }
      if (!isRecord(envelope.payload) || !isPlainObject(envelope.payload) || !Object.hasOwn(envelope.payload, 'args')) {
        this._writeJson(res, 200, {
          type: 'server-response',
          rpcId,
          result: {
            ok: false,
            error: { code: 'gateway/bad-request', message: 'payload must contain exactly one args field', details: {} },
          },
        })
        return
      }
      const rawArgs = envelope.payload.args
      const call = { method: endpoint, args: deepClone(rawArgs), rpcId, at: Date.now() }
      this.calls.push(call)
      try {
        const parsed = parseWireArgs(endpoint, rawArgs, wire)
        const value = this._computeResult(endpoint, parsed, call)
        const result = value === undefined ? { ok: true } : { ok: true, value }
        this._writeJson(res, 200, { type: 'server-response', rpcId, result })
      } catch (error) {
        const failure = error instanceof MockDshError
          ? { code: error.code, message: error.message, details: error.details }
          : { code: 'gateway/internal', message: String(error?.message ?? error), details: {} }
        this._writeJson(res, 200, { type: 'server-response', rpcId, result: { ok: false, error: failure } })
      }
      return
    }

    this._writeJson(res, 404, { error: 'not found' })
  }

  _writeJson(res, status, body) {
    const payload = JSON.stringify(body)
    res.writeHead(status, {
      'content-type': 'application/json; charset=utf-8',
      'cache-control': 'no-store',
    })
    res.end(payload)
  }

  _readBody(req) {
    return new Promise((resolve, reject) => {
      const chunks = []
      req.on('data', (chunk) => chunks.push(chunk))
      req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')))
      req.on('error', reject)
    })
  }

  /**
   * @param {string} endpoint
   * @param {{request:object,flat:object}} parsed - wire-parsed arguments.
   * @param {{method:string,args:object,rpcId:string,at:number}} call
   */
  _computeResult(endpoint, parsed, call) {
    const failure = this.failures.get(endpoint)
    if (failure !== undefined) {
      if (failure.mode === 'once') this.failures.delete(endpoint)
      throw new MockDshError(failure.error.code, failure.error.message, failure.error.details)
    }
    const override = this.results.get(endpoint)
    if (typeof override === 'function') return override(parsed.request, call)
    if (this.results.has(endpoint)) return deepClone(override)

    const request = parsed.request
    switch (endpoint) {
      case 'session/list':
        // Wire name is `_request`; its body carries the optional cursor.
        return { items: deepClone(this.sessions) }
      case 'session/state': {
        const session = this.sessions.find((item) => item.sessionId === request.sessionId)
        return {
          cursor: (this.history.get(request.sessionId) ?? []).length,
          active: true,
          running: session?.running === true,
          queue: null,
          pendingInput: [...this.pending.values()]
            .filter((frame) => frame.agentId === request.sessionId)
            .map((frame) => (frame.event === 'approval/request' ? 'approval' : 'question')),
        }
      }
      case 'session/events':
        return this._eventsPage(request)
      case 'session/prompt':
        return { accepted: true }
      case 'session/cancel':
        return { accepted: true }
      case 'session/create': {
        const sessionId = typeof request.sessionId === 'string' ? request.sessionId : `s-${randomUUID()}`
        this.sessionAdded({
          sessionId,
          updatedAt: Date.now(),
          running: false,
          blank: true,
          agentAvailable: true,
          formatStatus: 'current',
          ...(typeof request.cwd === 'string' ? { cwd: request.cwd } : {}),
        })
        return { sessionId, ...(typeof request.agentPreset === 'string' ? { agentPreset: request.agentPreset } : {}) }
      }
      case 'session/selectModel':
        return {
          selected: {
            provider: request.provider,
            model: request.model,
            ...(typeof request.reasoningEffort === 'string' ? { reasoningEffort: request.reasoningEffort } : {}),
          },
        }
      case 'session/modelCatalog':
        return deepClone(this.modelCatalog)
      case 'permissionPresets/catalog':
        return deepClone(this.permissionCatalog)
      case 'commands/execute':
        return this._executeCommand(request)
      case '$events/result':
        return this._acceptEventResult(parsed.flat, call)
      default:
        throw new MockDshError('gateway/unknown-method', `mock DSH has no endpoint ${JSON.stringify(endpoint)}`)
    }
  }

  /**
   * Emulate `commands/execute` for the `/permission <preset>` slash command.
   * A rejected command answers `result.kind === 'error'`; the real host never
   * throws for it. `request` is the flat wire args (`{agentId, line,
   * submittedAttachments}`) already validated by {@link parseWireArgs}.
   * @param {{agentId:string, line:string, submittedAttachments:Array<unknown>}} request
   */
  _executeCommand(request) {
    const line = typeof request?.line === 'string' ? request.line.trim() : ''
    const commandId = `cmd-${randomUUID()}`
    const match = /^\/permission\s+(\S+)$/.exec(line)
    if (match === null) {
      return { commandId, result: { kind: 'error', text: `unknown command ${JSON.stringify(line)}` } }
    }
    const preset = match[1]
    const options = Array.isArray(this.permissionCatalog?.options) ? this.permissionCatalog.options : []
    const available = options
      .map((option) => (option !== null && typeof option === 'object' && typeof option.value === 'string' ? option.value : ''))
      .filter((value) => value !== '')
    if (!available.includes(preset)) {
      return {
        commandId,
        result: { kind: 'error', text: `unknown preset ${JSON.stringify(preset)} (available: ${available.join(', ')})` },
      }
    }
    return { commandId, result: { kind: 'success', text: `preset ${preset}` } }
  }

  _eventsPage(request) {
    const sessionId = request.address?.sessionId ?? request.sessionId ?? ''
    const records = this.history.get(sessionId) ?? []
    const throughSeq = typeof request.throughSeq === 'number' ? request.throughSeq : Number.POSITIVE_INFINITY
    const beforeSeq = typeof request.beforeSeq === 'number' ? request.beforeSeq : Number.POSITIVE_INFINITY
    const maxMessages = typeof request.maxMessages === 'number' ? request.maxMessages : 100
    let page = records.filter((record) => record.event.seq <= throughSeq && record.event.seq < beforeSeq)
    if (page.length > maxMessages) page = page.slice(page.length - maxMessages)
    return deepClone({
      cursor: page.length === 0 ? 0 : page[page.length - 1].event.seq,
      records: page,
      hasMore: page.length > 0 && records.some((record) => record.event.seq < page[0].event.seq),
    })
  }

  _acceptEventResult(flat, call) {
    const eventId = typeof flat.eventId === 'string' ? flat.eventId : ''
    const clientId = typeof flat.clientId === 'string' ? flat.clientId : ''
    const outcome = isRecord(flat.outcome) ? flat.outcome : {}
    const outcomeKeys = Object.keys(outcome).sort()
    const validOutcome = (outcome.kind === 'next' && outcomeKeys.length === 1)
      || (outcome.kind === 'result' && (outcomeKeys.length === 1 || (outcomeKeys.length === 2 && Object.hasOwn(outcome, 'value'))))
      || (outcome.kind === 'rejected' && outcomeKeys.length === 2 && isRecord(outcome.error)
        && typeof outcome.error.name === 'string' && typeof outcome.error.message === 'string')
    if (!validOutcome) {
      throw new MockDshError('gateway/internal', 'invalid Remote event result')
    }
    const known = this.pending.has(eventId)
    this.resultCalls.push({ clientId, eventId, outcome: deepClone(outcome), known, at: call.at })
    const active = this._eventsClientIds()
    if (!active.has(clientId)) {
      throw new MockDshError(
        'gateway/internal',
        'typert gateway: Remote event result identifies no active event stream',
      )
    }
    if (!known) return undefined
    this.pending.delete(eventId)
    // The real host removes the answering client from the delivery set before
    // settling, so only the *other* clients receive the cancellation frame.
    for (const client of this.muxClients) {
      const streams = this.streams.get(client) ?? new Map()
      for (const stream of streams.values()) {
        if (stream.endpoint !== '$events' || stream.clientId === clientId) continue
        this._sendItem(client, stream.streamId, { type: 'cancel', eventId })
      }
    }
    return undefined
  }

  _eventsClientIds() {
    const ids = new Set()
    for (const map of this.streams.values()) {
      for (const stream of map.values()) {
        if (stream.endpoint === '$events' && typeof stream.clientId === 'string') ids.add(stream.clientId)
      }
    }
    return ids
  }

  _handleUpgrade(req, socket, head, wss) {
    let pathname = '/'
    try {
      pathname = new URL(req.url ?? '/', 'http://mock-dsh.invalid').pathname
    } catch {
      pathname = '/'
    }
    if (pathname !== '/api/remote.mux') {
      socket.write('HTTP/1.1 404 Not Found\r\nConnection: close\r\nContent-Length: 0\r\n\r\n')
      socket.destroy()
      return
    }
    if (this.disabled) {
      socket.write('HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\nContent-Length: 0\r\n\r\n')
      socket.destroy()
      return
    }
    if (!this._hasCookie(req)) {
      socket.write('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\nContent-Length: 0\r\n\r\n')
      socket.destroy()
      return
    }
    wss.handleUpgrade(req, socket, head, (ws) => this._onConnection(ws))
  }

  _onConnection(ws) {
    this.muxClients.add(ws)
    this.streams.set(ws, new Map())
    this._missed.set(ws, 0)
    ws.on('pong', () => this._missed.set(ws, 0))
    ws.on('message', (data, isBinary) => {
      this._missed.set(ws, 0)
      if (isBinary) {
        ws.close(1003, 'text messages required')
        return
      }
      let frame
      try {
        frame = JSON.parse(data.toString('utf8'))
      } catch {
        ws.close(1008, 'invalid Remote stream request')
        return
      }
      try {
        this._onMuxFrame(ws, frame)
      } catch (error) {
        if (error?.fatal === true) {
          // Duplicate streamId is the one protocol violation that kills the socket.
          ws.close(1008, 'invalid Remote stream request')
          return
        }
        this._sendStreamFailure(ws, frame?.streamId, error)
      }
    })
    ws.on('close', () => {
      this.muxClients.delete(ws)
      this.streams.delete(ws)
    })
    ws.on('error', () => {
      // ignore; close handler cleans up
    })
  }

  _onMuxFrame(ws, frame) {
    if (!isRecord(frame) || typeof frame.streamId !== 'string') {
      throw new MockDshError('gateway/bad-request', 'invalid Remote stream frame')
    }
    const streams = this.streams.get(ws)
    if (streams === undefined) return
    switch (frame.type) {
      case 'open': {
        if (streams.has(frame.streamId)) {
          const fatal = new MockDshError('gateway/bad-request', `duplicate Remote stream id ${JSON.stringify(frame.streamId)}`)
          fatal.fatal = true
          throw fatal
        }
        const endpoint = String(frame.endpoint ?? '')
        const wire = STREAM_WIRE[endpoint]
        if (wire === undefined) {
          throw new MockDshError('gateway/unknown-endpoint', `mock DSH has no stream endpoint ${JSON.stringify(endpoint)}`)
        }
        if (!isRecord(frame.payload) || !isPlainObject(frame.payload) || !Object.hasOwn(frame.payload, 'args')) {
          throw new MockDshError('gateway/bad-request', 'Remote payload must contain exactly one args field')
        }
        const parsed = parseWireArgs(endpoint, frame.payload.args, wire)
        const stream = {
          streamId: frame.streamId,
          endpoint,
          args: deepClone(frame.payload.args),
          request: parsed.request,
        }
        streams.set(frame.streamId, stream)
        try {
          this._openStream(ws, stream)
        } catch (error) {
          streams.delete(frame.streamId)
          throw error
        }
        return
      }
      case 'item': {
        this.uplinkItems.push({
          streamId: frame.streamId,
          endpoint: streams.get(frame.streamId)?.endpoint ?? '',
          value: deepClone(frame.value),
          at: Date.now(),
        })
        return
      }
      case 'end':
      case 'cancel': {
        // A client `cancel` never gets `end`/`error` back (notes §3.2).
        streams.delete(frame.streamId)
        return
      }
      default:
        throw new MockDshError('gateway/bad-request', `unknown Remote stream message ${JSON.stringify(frame.type)}`)
    }
  }

  _openStream(ws, stream) {
    switch (stream.endpoint) {
      case '$events': {
        stream.clientId = `client-${randomUUID()}`
        this.readyCount += 1
        this._sendItem(ws, stream.streamId, {
          type: 'ready',
          clientId: stream.clientId,
          host: { home: '/mock/home' },
        })
        for (const frame of this.pending.values()) this._sendItem(ws, stream.streamId, frame)
        return
      }
      case 'session/control': {
        this._sendItem(ws, stream.streamId, { type: 'baseline', value: { projections: this._projections() } })
        return
      }
      case 'workspace/follow': {
        this._sendItem(ws, stream.streamId, {
          type: 'baseline',
          value: {
            items: deepClone(this.workspaces),
            archivedSessionIds: [],
            pinnedSessionIds: [],
          },
        })
        return
      }
      case 'session/follow': {
        const sessionId = stream.request.address?.sessionId ?? stream.request.sessionId ?? ''
        const records = this.history.get(sessionId) ?? []
        if (sessionId === '') {
          throw new MockDshError('gateway/arguments-invalid', 'session/follow requires request.address')
        }
        this._sendItem(ws, stream.streamId, {
          type: 'snapshot',
          header: {
            version: 4,
            id: sessionId,
            createdAt: 1_700_000_000_000,
            cwd: this.sessions.find((item) => item.sessionId === sessionId)?.cwd ?? undefined,
            isSeeded: false,
            agentPreset: 'standard',
          },
          cursor: records.length === 0 ? 0 : records[records.length - 1].event.seq,
          records: deepClone(records),
          hasMore: false,
          projections: { asOfSeq: 0, values: {} },
        })
        return
      }
      default:
        throw new MockDshError('gateway/unknown-endpoint', `mock DSH has no stream endpoint ${JSON.stringify(stream.endpoint)}`)
    }
  }

  _sendItem(ws, streamId, value) {
    if (ws.readyState !== 1) return
    try {
      ws.send(JSON.stringify({ type: 'item', streamId, value }))
    } catch {
      // socket went away between checks
    }
  }

  _sendStreamFailure(ws, streamId, error) {
    if (typeof streamId !== 'string' || ws.readyState !== 1) return
    const failure = error instanceof MockDshError
      ? { code: error.code, message: error.message, details: error.details }
      : { code: 'gateway/internal', message: String(error?.message ?? error), details: {} }
    try {
      // The pump emits `error`; a failed open additionally ends the stream.
      ws.send(JSON.stringify({ type: 'error', streamId, error: failure }))
      ws.send(JSON.stringify({ type: 'end', streamId }))
    } catch {
      // ignore
    }
  }

  _pushEventsFrame(frame) {
    for (const client of this.muxClients) {
      const streams = this.streams.get(client) ?? new Map()
      for (const stream of streams.values()) {
        if (stream.endpoint === '$events') this._sendItem(client, stream.streamId, frame)
      }
    }
  }

  _pushControlFrame(frame) {
    for (const client of this.muxClients) {
      const streams = this.streams.get(client) ?? new Map()
      for (const stream of streams.values()) {
        if (stream.endpoint === 'session/control') this._sendItem(client, stream.streamId, frame)
      }
    }
  }

  _pushWorkspaceFrame(frame) {
    for (const client of this.muxClients) {
      const streams = this.streams.get(client) ?? new Map()
      for (const stream of streams.values()) {
        if (stream.endpoint === 'workspace/follow') this._sendItem(client, stream.streamId, frame)
      }
    }
  }

  _pushSessionFrame(sessionId, frame) {
    for (const client of this.muxClients) {
      const streams = this.streams.get(client) ?? new Map()
      for (const stream of streams.values()) {
        if (stream.endpoint !== 'session/follow') continue
        const followed = stream.request.address?.sessionId ?? stream.request.sessionId ?? ''
        if (followed === sessionId) this._sendItem(client, stream.streamId, frame)
      }
    }
  }
}

export default MockDsh
