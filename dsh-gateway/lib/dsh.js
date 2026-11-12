/**
 * DshLoopbackClient —— 与「所在 DSH 进程」的回环数据面客户端。
 *
 * 本插件跑在 DSH 进程内，但**不**直接调用内部 service（签名随版本漂移），而是：
 *   1. 用 `ctx.connection.authenticatedUrl(origin)` 拿一次性 launch token URL，
 *      `GET redirect=manual` 换 `dsh-auth-*` Cookie（只存内存）；
 *   2. 一元调用走 `POST /api/<method>`（`client-request` / `server-response` 信封）；
 *   3. 长连接只开**一条** `WS /api/remote.mux`，按 `streamId` 复用并路由逻辑流：
 *      `$events`、`session/control`、`workspace/follow`（启动即开）与
 *      `session/follow`（按订阅引用计数按需开）。
 *
 * 对外 API（docs/dsh-gateway-impl-plan.md §3.3）：
 *   - `start()/stop()`、`connected`、`origin`、`eventsClientId`
 *   - `call(method, args, opts)`、`answerIntervention(args)`
 *   - `followSession(id)/unfollowSession(id)`、`refreshCookie()`
 *   - `__setTestHooks({fetch, WebSocket})`（测试注入）
 *
 * 关键行为：
 *   - 401 自动重换 Cookie 重试一次；
 *   - mux 断开后指数退避重连 0.5s→30s（±30% 抖动），`stop()` 后不再重连；
 *   - `$events` 每次重连都会拿到新 clientId（`ready` 帧），回答审批必须用最新值；
 *   - 所有 `ws.send` 通过 promise 链串行化，避免并发乱序；
 *   - 日志只记元数据（method/code/streamId），绝不打印 Cookie。
 *
 * @module dsh-gateway/lib/dsh
 */

import { WebSocket as NodeWebSocket } from 'ws'

import { backoffDelay, normalizeOrigin, uuid } from './util.js'

/** 逻辑流 mux 的固定路径。 */
const MUX_PATH = '/api/remote.mux'
/** 转发 Host 事件的逻辑流 endpoint。 */
const EVENTS_ENDPOINT = '$events'
/** 回答 waterfall 的一元 endpoint。 */
const EVENTS_RESULT_ENDPOINT = '$events/result'
/** 会话控制（投影）逻辑流。 */
const CONTROL_ENDPOINT = 'session/control'
/** 工作区逻辑流。 */
const WORKSPACE_ENDPOINT = 'workspace/follow'
/** 单会话消息流。 */
const SESSION_ENDPOINT = 'session/follow'

/** 启动即开的流（断线后自动重开）。 */
const CORE_ENDPOINTS = new Set([EVENTS_ENDPOINT, CONTROL_ENDPOINT, WORKSPACE_ENDPOINT])
/** 固定 streamId：重连复用同一批逻辑流（服务端表随旧 socket 一起销毁）。 */
const STREAM_IDS = Object.freeze({
  events: 'core:$events',
  control: 'core:session-control',
  workspace: 'core:workspace-follow',
})

const BASE_BACKOFF_MS = 500
const MAX_BACKOFF_MS = 30_000
const STABLE_RESET_MS = 30_000
const CALL_TIMEOUT_MS = 30_000
const COOKIE_TIMEOUT_MS = 15_000
const OPEN_TIMEOUT_MS = 15_000
const FILE_TIMEOUT_MS = 20_000
/** Hard cap for one host file read (bytes); the gateway preview route uses it too. */
const MAX_HOST_FILE_BYTES = 16 * 1024 * 1024
const WATCHDOG_INTERVAL_MS = 5_000
/** 没收到过 DSH ping 时的保守空闲阈值（mock/静默链路）。 */
const WATCHDOG_IDLE_MS = 60_000
/** 已经观察到 DSH 2s ping 后的判死阈值（约 3 个周期无帧）。 */
const WATCHDOG_PING_IDLE_MS = 8_000
const COOKIE_PREFIX = 'dsh-auth-'

/** @returns {boolean} */
function isRecord(value) {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/** 兼容 ws（EventEmitter）与浏览器风格 WebSocket（addEventListener）。 */
function onEmitter(emitter, event, handler) {
  if (emitter === null || emitter === undefined) return
  try {
    if (typeof emitter.on === 'function') emitter.on(event, handler)
    else if (typeof emitter.addEventListener === 'function') emitter.addEventListener(event, handler)
  } catch {
    // 事件注册失败不致命
  }
}

/** 兼容 ws 与浏览器风格解绑。 */
function offEmitter(emitter, event, handler) {
  if (emitter === null || emitter === undefined) return
  try {
    if (typeof emitter.off === 'function') emitter.off(event, handler)
    else if (typeof emitter.removeListener === 'function') emitter.removeListener(event, handler)
    else if (typeof emitter.removeEventListener === 'function') emitter.removeEventListener(event, handler)
  } catch {
    // 忽略
  }
}

/** ws 的 close reason 可能是 Buffer。 */
function reasonText(reason) {
  if (typeof reason === 'string') return reason
  if (reason === null || reason === undefined) return ''
  try {
    if (typeof Buffer !== 'undefined' && Buffer.isBuffer(reason)) return reason.toString('utf8')
    return String(reason)
  } catch {
    return ''
  }
}

/** 从 Response 头里取全部 Set-Cookie。 */
function readSetCookies(response) {
  const headers = response?.headers
  if (headers === null || headers === undefined) return []
  try {
    if (typeof headers.getSetCookie === 'function') {
      const list = headers.getSetCookie()
      if (Array.isArray(list) && list.length > 0) return list
    }
  } catch {
    // 回退到拼接头
  }
  try {
    const raw = typeof headers.get === 'function' ? headers.get('set-cookie') : null
    if (typeof raw !== 'string' || raw === '') return []
    // dsh-auth-* 的 value 是 base64url（不含逗号），可以安全按逗号切分；
    // 其它 cookie 的 Expires 含逗号，切碎后按前缀匹配仍能找到目标项。
    return raw.split(',').map((part) => part.trim()).filter((part) => part !== '')
  } catch {
    return []
  }
}

/** HTTP 状态码 → `/v1` 风格错误码。 */
function httpErrorCode(status) {
  switch (status) {
    case 400: return 'invalid_request'
    case 401: return 'unauthenticated'
    case 403: return 'forbidden_source'
    case 404: return 'not_found'
    case 409: return 'conflict'
    case 413: return 'payload_too_large'
    case 429: return 'rate_limited'
    default: return status >= 500 ? 'dsh_unavailable' : 'call_failed'
  }
}

/**
 * DSH 回环调用错误。
 *
 * `code` 来自 `result.error.code`（可能是 `session/not-found` 这类 RemoteError code）
 * 或 HTTP 状态映射；`method` 是出错的 `/api/<method>` endpoint。
 */
export class DshCallError extends Error {
  /**
   * @param {string} code - 稳定错误码。
   * @param {string} method - 出错的 endpoint（如 `session/list`）。
   * @param {string} [message] - 人类可读信息。
   * @param {unknown} [details] - 可选 JSON 详情。
   */
  constructor(code, method, message, details) {
    super(message ?? `${method}: ${code}`)
    this.name = 'DshCallError'
    this.code = code
    this.method = method
    if (details !== undefined) this.details = details
  }
}

export class DshLoopbackClient {
  /**
   * @param {object} deps
   * @param {import('@deepseek-ai/cordis').Context} deps.ctx - 需要 `webServer` 与 `connection`。
   * @param {object} [deps.config] - 解析后的插件配置（当前只用于日志/保留）。
   * @param {{info?:Function,warn?:Function,error?:Function,debug?:Function}} [deps.logger]
   * @param {object} [deps.handlers]
   * @param {(frame:object)=>void} [deps.handlers.onEvent] - `$events` 帧原样。
   * @param {(frame:object)=>void} [deps.handlers.onControl] - `session/control` 帧原样。
   * @param {(frame:object)=>void} [deps.handlers.onWorkspace] - `workspace/follow` 帧原样。
   * @param {(sessionId:string, frame:object)=>void} [deps.handlers.onSessionFrame] - 所有 session/follow 帧。
   * @param {(state:{connected:boolean,error?:string,clientId?:string})=>void} [deps.handlers.onStatus]
   * @param {typeof fetch} [deps.fetch] - 测试注入。
   * @param {typeof WebSocket} [deps.WebSocket] - 测试注入（默认使用 `ws` 包）。
   */
  constructor(deps = {}) {
    this.ctx = deps.ctx
    this.config = isRecord(deps.config) ? deps.config : {}
    this.logger = deps.logger ?? {
      debug: () => {},
      info: () => {},
      warn: () => {},
      error: () => {},
    }
    this.handlers = isRecord(deps.handlers) ? deps.handlers : {}

    this._fetch = typeof deps.fetch === 'function' ? deps.fetch : null
    this._WebSocket = typeof deps.WebSocket === 'function' ? deps.WebSocket : NodeWebSocket

    /** @type {string|null} 只存内存的 `dsh-auth-*=<value>`。 */
    this._cookie = null
    /** @type {Promise<string>|null} */
    this._cookiePromise = null

    /** @type {string|null} */
    this._origin = null
    /** @type {object|null} */
    this._ws = null
    /** @type {boolean} */
    this._connected = false
    /** @type {boolean} */
    this._stopped = false
    /** @type {Promise<object>|null} */
    this._startPromise = null
    /** @type {Promise<void>|null} */
    this._stopPromise = null
    /** @type {Promise<boolean>|null} */
    this._connectPromise = null
    /** @type {NodeJS.Timeout|null} */
    this._reconnectTimer = null
    /** @type {NodeJS.Timeout|null} */
    this._stableTimer = null
    /** @type {NodeJS.Timeout|null} */
    this._watchdog = null
    /** @type {number} 连续失败次数（退避用）。 */
    this._attempt = 0
    /** @type {string|null} 最新 `$events` clientId。 */
    this._clientId = null
    /** @type {number} 最后一次收到任意 mux 帧的时间。 */
    this._lastFrameAt = 0
    /** @type {boolean} 是否已观察到 DSH 的 2s 协议 ping（用于收紧看门狗）。 */
    this._sawPing = false

    /** @type {Map<string, {streamId:string, endpoint:string, handler:Function, followSessionId?:string}>} */
    this._streams = new Map()
    /** @type {Map<string, {sessionId:string, streamId:string, refCount:number, opened:boolean}>} */
    this._follows = new Map()

    /** @type {Promise<void>} 串行化 ws.send 的 promise 链。 */
    this._sendChain = Promise.resolve()
  }

  // ---------------------------------------------------------------------------
  // 生命周期
  // ---------------------------------------------------------------------------

  /**
   * 启动回环链路：换 Cookie → 连 mux → 开 4 类流（session/follow 按需）。
   * 幂等；首次连接失败不会抛错（后台继续指数退避重连）。
   * @returns {Promise<DshLoopbackClient>}
   */
  async start() {
    if (this._startPromise !== null) return this._startPromise
    this._stopped = false
    this._stopPromise = null
    this._log('info', 'starting DSH loopback link')
    this._startPromise = this._runConnect().then(() => this).catch((error) => {
      this._log('warn', `loopback start failed: ${error?.message ?? error}`)
      return this
    })
    return this._startPromise
  }

  /**
   * 停止：关闭 mux、取消所有逻辑流、拒绝后续重连。
   * @returns {Promise<void>}
   */
  async stop() {
    if (this._stopPromise !== null) return this._stopPromise
    this._stopped = true
    this._startPromise = null
    this._clearReconnectTimer()
    if (this._stableTimer !== null) {
      clearTimeout(this._stableTimer)
      this._stableTimer = null
    }
    this._stopWatchdog()

    const socket = this._ws
    this._ws = null
    this._connected = false
    this._clientId = null
    this._streams.clear()
    this._follows.clear()

    this._stopPromise = (async () => {
      if (socket === null) return
      await new Promise((resolve) => {
        let done = false
        let timer = null
        const finish = () => {
          if (done) return
          done = true
          if (timer !== null) clearTimeout(timer)
          offEmitter(socket, 'close', finish)
          resolve()
        }
        timer = setTimeout(() => {
          try {
            if (typeof socket.terminate === 'function') socket.terminate()
          } catch {
            // 忽略
          }
          finish()
        }, 2_000)
        timer.unref?.()
        onEmitter(socket, 'close', finish)
        try {
          if (typeof socket.close === 'function') socket.close(1000, 'dsh-gateway stopped')
          else if (typeof socket.terminate === 'function') socket.terminate()
          else finish()
        } catch {
          try {
            if (typeof socket.terminate === 'function') socket.terminate()
          } catch {
            // 忽略
          }
          finish()
        }
      })
    })()
    return this._stopPromise
  }

  /** mux 是否处于已连接（open）状态。 */
  get connected() {
    return this._connected && this._isOpen(this._ws)
  }

  /** DSH 回环 origin，如 `http://127.0.0.1:12345`。 */
  get origin() {
    if (this._origin !== null) return this._origin
    const webServer = this.ctx?.webServer
    let origin = ''
    try {
      if (typeof webServer?.getOrigin === 'function') origin = webServer.getOrigin()
    } catch {
      origin = ''
    }
    if (typeof origin !== 'string' || origin === '') {
      const port = Number(webServer?.port)
      const host = typeof webServer?.host === 'string' && webServer.host !== '' ? webServer.host : '127.0.0.1'
      if (Number.isFinite(port) && port > 0) origin = `http://${host}:${port}`
    }
    if (typeof origin !== 'string' || origin === '') {
      throw new DshCallError('no_origin', 'webServer', 'cannot resolve DSH web server origin')
    }
    this._origin = normalizeOrigin(origin)
    return this._origin
  }

  /** 最新 `$events` clientId（`ready` 帧下发；重连会变）。 */
  get eventsClientId() {
    return this._clientId
  }

  /** 当前已打开的逻辑流数量（调试/测试用）。 */
  get streamCount() {
    return this._streams.size
  }

  /** 当前正在 follow 的会话 id 列表（调试/测试用）。 */
  get followedSessions() {
    return [...this._follows.keys()]
  }

  /**
   * 测试注入（在 `start()` 之前调用）：替换 fetch / WebSocket 实现。
   * @param {{fetch?:Function, WebSocket?:Function}} [hooks]
   */
  __setTestHooks(hooks = {}) {
    if (typeof hooks.fetch === 'function') this._fetch = hooks.fetch
    if (typeof hooks.WebSocket === 'function') this._WebSocket = hooks.WebSocket
    return this
  }

  // ---------------------------------------------------------------------------
  // Cookie
  // ---------------------------------------------------------------------------

  /**
   * 确保内存里有一份 `name=value` Cookie；并发调用共享同一次兑换。
   * @param {boolean} [force] - 丢弃缓存强制重换。
   * @returns {Promise<string>}
   */
  async ensureCookie(force = false) {
    if (force) {
      this._cookie = null
      this._cookiePromise = null
    }
    if (this._cookie !== null) return this._cookie
    if (this._cookiePromise !== null) return this._cookiePromise
    this._cookiePromise = this._redeemCookie()
      .then((pair) => {
        this._cookie = pair
        return pair
      })
      .finally(() => {
        this._cookiePromise = null
      })
    return this._cookiePromise
  }

  /**
   * 强制重新兑换 Cookie（401 时调用）。
   * @returns {Promise<string>}
   */
  async refreshCookie() {
    this._cookie = null
    this._cookiePromise = null
    return this.ensureCookie(true)
  }

  /** 用 launch token URL 兑换 `dsh-auth-*` Cookie。 */
  async _redeemCookie() {
    const fetchImpl = this._requireFetch()
    const url = this._authenticatedUrl()
    let response
    try {
      response = await fetchImpl(url, {
        redirect: 'manual',
        headers: { accept: 'text/html' },
        ...(typeof AbortSignal !== 'undefined' && typeof AbortSignal.timeout === 'function'
          ? { signal: AbortSignal.timeout(COOKIE_TIMEOUT_MS) }
          : {}),
      })
    } catch (error) {
      throw new DshCallError(
        error?.name === 'TimeoutError' ? 'timeout' : 'transport_error',
        'authenticatedUrl',
        `cookie exchange failed: ${error?.message ?? error}`,
      )
    }

    const cookies = readSetCookies(response)
    const entry = cookies.find((cookie) => cookie.startsWith(COOKIE_PREFIX))
    if (entry === undefined) {
      throw new DshCallError(
        'auth_failed',
        'authenticatedUrl',
        `no ${COOKIE_PREFIX}* cookie in launch-token response (HTTP ${response?.status ?? '?'})`,
      )
    }
    const pair = entry.split(';')[0].trim()
    if (!pair.includes('=')) {
      throw new DshCallError('auth_failed', 'authenticatedUrl', 'malformed DSH auth cookie')
    }
    // 只记录 cookie 名，绝不记录值。
    this._log('debug', `DSH auth cookie acquired (${pair.split('=')[0]})`)
    return pair
  }

  /** 取带 launch token 的 URL（`ctx.connection.authenticatedUrl`）。 */
  _authenticatedUrl() {
    const connection = this.ctx?.connection
    if (typeof connection?.authenticatedUrl !== 'function') {
      throw new DshCallError('no_connection', 'authenticatedUrl', 'connection.authenticatedUrl is unavailable')
    }
    try {
      const url = connection.authenticatedUrl(this.origin)
      if (typeof url !== 'string' || url === '') {
        throw new Error('authenticatedUrl returned an empty value')
      }
      return url
    } catch (error) {
      if (error instanceof DshCallError) throw error
      throw new DshCallError('auth_url_failed', 'authenticatedUrl', error?.message ?? String(error))
    }
  }

  // ---------------------------------------------------------------------------
  // 一元调用
  // ---------------------------------------------------------------------------

  /**
   * 一元 RPC：`POST /api/<method>`。
   *
   * @param {string} method - endpoint，如 `session/list`。
   * @param {object} [args] - `payload.args`。
   * @param {{signal?:AbortSignal, timeoutMs?:number}} [opts]
   * @returns {Promise<unknown>} `result.value`
   * @throws {DshCallError}
   */
  async call(method, args, opts = {}) {
    const endpoint = String(method ?? '').replace(/^\/+/, '').trim()
    if (endpoint === '') throw new DshCallError('invalid_method', String(method ?? ''), 'method is required')
    const signal = this._composeSignal(opts?.signal, Number(opts?.timeoutMs) > 0 ? Number(opts.timeoutMs) : CALL_TIMEOUT_MS)
    const payload = JSON.stringify({
      type: 'client-request',
      rpcId: uuid(),
      method: endpoint,
      payload: { args: isRecord(args) ? args : {} },
    })

    let cookie = await this.ensureCookie()
    let response = await this._post(endpoint, cookie, payload, signal)
    if (response.status === 401) {
      this._log('warn', `${endpoint}: 401 from DSH, refreshing cookie and retrying once`)
      cookie = await this.refreshCookie()
      response = await this._post(endpoint, cookie, payload, signal)
    }

    return this._parseResponse(endpoint, response)
  }

  /** 发送一次 POST（失败统一映射为 DshCallError）。 */
  async _post(endpoint, cookie, payload, signal) {
    const fetchImpl = this._requireFetch()
    const url = `${this.origin}/api/${endpoint}`
    const init = {
      method: 'POST',
      headers: {
        cookie,
        'content-type': 'application/json',
        accept: 'application/json',
      },
      body: payload,
    }
    if (signal !== undefined) init.signal = signal
    try {
      return await fetchImpl(url, init)
    } catch (error) {
      if (error instanceof DshCallError) throw error
      if (error?.name === 'AbortError') {
        throw new DshCallError('aborted', endpoint, 'DSH request was aborted')
      }
      if (error?.name === 'TimeoutError') {
        throw new DshCallError('timeout', endpoint, 'DSH request timed out')
      }
      throw new DshCallError('transport_error', endpoint, `DSH request failed: ${error?.message ?? error}`)
    }
  }

  /** 解析 `server-response` 信封。 */
  async _parseResponse(endpoint, response) {
    let text = ''
    try {
      text = await response.text()
    } catch {
      text = ''
    }
    let body = null
    if (text !== '') {
      try {
        body = JSON.parse(text)
      } catch {
        body = null
      }
    }

    const ok = response.status >= 200 && response.status < 300
    if (!ok) {
      const code = body?.result?.error?.code ?? body?.error?.code ?? httpErrorCode(response.status)
      const message = body?.result?.error?.message ?? body?.error?.message ?? `HTTP ${response.status}`
      throw new DshCallError(String(code), endpoint, message, body?.result?.error ?? body?.error)
    }
    if (body === null) {
      throw new DshCallError('invalid_response', endpoint, 'empty or non-JSON DSH response')
    }

    const result = body.result
    if (result === undefined) {
      // 兼容极简 mock：直接返回 `{ok:true,value}`。
      if (body.ok === true) return body.value
      throw new DshCallError('invalid_response', endpoint, 'missing server-response result envelope')
    }
    if (result?.ok !== true) {
      throw new DshCallError(
        result?.error?.code ?? 'call_failed',
        endpoint,
        result?.error?.message ?? 'DSH call failed',
        result?.error,
      )
    }
    return result.value
  }

  /**
   * 读取 DSH 侧的一个文件（走宿主的认证路由 `GET /api/file`）。
   *
   * 图片预览用它按需取字节：不落盘、不做网关侧缓存；文件在 PC 上被删掉后
   * 请求自然失败，和桌面端看到的结果一致。
   *
   * @param {string} path - 绝对路径（调用方已解析）。
   * @param {{maxBytes?:number, signal?:AbortSignal}} [opts]
   * @returns {Promise<{contentType:string, bytes:Buffer}>}
   */
  async readFile(path, opts = {}) {
    const target = typeof path === 'string' ? path : ''
    if (target === '') throw new DshCallError('invalid_request', 'api/file', 'path is required')
    const maxBytes = Number(opts?.maxBytes) > 0 ? Number(opts.maxBytes) : MAX_HOST_FILE_BYTES
    const endpoint = `api/file?path=${encodeURIComponent(target)}`
    const signal = this._composeSignal(opts?.signal, FILE_TIMEOUT_MS)

    let cookie = await this.ensureCookie()
    let response = await this._getFile(endpoint, cookie, signal)
    if (response.status === 401) {
      this._log('warn', 'api/file: 401 from DSH, refreshing cookie and retrying once')
      cookie = await this.refreshCookie()
      response = await this._getFile(endpoint, cookie, signal)
    }

    if (!response.ok) {
      const code = response.status === 404
        ? 'not_found'
        : response.status === 403 ? 'forbidden' : response.status === 413 ? 'too_large' : 'call_failed'
      throw new DshCallError(code, 'api/file', `HTTP ${response.status}`)
    }
    const declared = Number(response.headers.get('content-length'))
    if (Number.isFinite(declared) && declared > maxBytes) {
      throw new DshCallError('too_large', 'api/file', `file exceeds ${maxBytes} bytes`)
    }
    const buffer = Buffer.from(await response.arrayBuffer())
    if (buffer.byteLength > maxBytes) {
      throw new DshCallError('too_large', 'api/file', `file exceeds ${maxBytes} bytes`)
    }
    return {
      contentType: String(response.headers.get('content-type') ?? ''),
      bytes: buffer,
    }
  }

  /** 发一次认证 GET（文件通道；失败统一映射为 DshCallError）。 */
  async _getFile(endpoint, cookie, signal) {
    const fetchImpl = this._requireFetch()
    const init = {
      method: 'GET',
      headers: { cookie, accept: '*/*' },
      signal,
    }
    try {
      return await fetchImpl(`${this.origin}/${endpoint}`, init)
    } catch (error) {
      if (error?.name === 'AbortError') throw new DshCallError('aborted', 'api/file', 'DSH request was aborted')
      if (error?.name === 'TimeoutError') throw new DshCallError('timeout', 'api/file', 'DSH request timed out')
      throw new DshCallError('transport_error', 'api/file', `DSH request failed: ${error?.message ?? error}`)
    }
  }

  /**
   * 回答 `$events` waterfall（审批/提问）。
   *
   * @param {{eventId:string, clientId?:string, outcome?:object, value?:unknown, opts?:object}} args
   *   - 省略 `clientId` 时自动使用最新 `$events` clientId；
   *   - 只给 `value` 时会包装成 `{kind:'result', value}`。
   * @returns {Promise<unknown>}
   */
  async answerIntervention(args) {
    if (!isRecord(args)) throw new DshCallError('invalid_request', EVENTS_RESULT_ENDPOINT, 'answer args are required')
    const eventId = typeof args.eventId === 'string' ? args.eventId.trim() : ''
    if (eventId === '') {
      throw new DshCallError('invalid_event', EVENTS_RESULT_ENDPOINT, 'eventId is required')
    }
    const clientId = typeof args.clientId === 'string' && args.clientId !== ''
      ? args.clientId
      : this._clientId
    if (typeof clientId !== 'string' || clientId === '') {
      throw new DshCallError('not_ready', EVENTS_RESULT_ENDPOINT, '$events clientId is not ready yet')
    }
    let outcome = args.outcome
    if (!isRecord(outcome)) {
      if (args.value === undefined) {
        throw new DshCallError('invalid_outcome', EVENTS_RESULT_ENDPOINT, 'outcome is required')
      }
      outcome = { kind: 'result', value: args.value }
    }
    return this.call(
      EVENTS_RESULT_ENDPOINT,
      { clientId, eventId, outcome },
      isRecord(args.opts) ? args.opts : {},
    )
  }

  // ---------------------------------------------------------------------------
  // session/follow（引用计数）
  // ---------------------------------------------------------------------------

  /**
   * 打开/复用某个会话的 follow 流；引用计数归零时由 `unfollowSession` 取消。
   * @param {string} sessionId
   * @returns {Promise<string>} 该会话的逻辑 streamId。
   */
  async followSession(sessionId) {
    const id = String(sessionId ?? '').trim()
    if (id === '') throw new DshCallError('invalid_session', SESSION_ENDPOINT, 'sessionId is required')
    const existing = this._follows.get(id)
    if (existing !== undefined) {
      existing.refCount += 1
      if (!existing.opened && this._connected) this._sendFollow(existing)
      return existing.streamId
    }
    const entry = { sessionId: id, streamId: `session:${id}`, refCount: 1, opened: false }
    this._follows.set(id, entry)
    if (this._connected) this._sendFollow(entry)
    return entry.streamId
  }

  /**
   * 释放一次 follow 引用；归零时向 mux 发 `{type:'cancel',streamId}`。
   * @param {string} sessionId
   * @returns {Promise<boolean>} 是否真正关闭了流。
   */
  async unfollowSession(sessionId) {
    const id = String(sessionId ?? '').trim()
    const entry = this._follows.get(id)
    if (entry === undefined) return false
    entry.refCount -= 1
    if (entry.refCount > 0) return false
    this._follows.delete(id)
    this._streams.delete(entry.streamId)
    entry.opened = false
    if (this._connected) this._send({ type: 'cancel', streamId: entry.streamId })
    return true
  }

  // ---------------------------------------------------------------------------
  // mux 连接与帧路由
  // ---------------------------------------------------------------------------

  /** 单次连接尝试：换 Cookie → new WebSocket → 等 open。 */
  async _connectOnce() {
    if (this._stopped) throw new DshCallError('stopped', 'remote.mux', 'client is stopped')
    const cookie = await this.ensureCookie()
    const origin = this.origin
    const wsUrl = `${origin.replace(/^http:/, 'ws:').replace(/^https:/, 'wss:')}${MUX_PATH}`
    const WebSocketImpl = this._requireWebSocket()

    let socket
    try {
      socket = new WebSocketImpl(wsUrl, {
        headers: { cookie },
        handshakeTimeout: OPEN_TIMEOUT_MS,
        perMessageDeflate: false,
      })
    } catch (error) {
      throw new DshCallError('ws_open', 'remote.mux', `cannot create WebSocket: ${error?.message ?? error}`)
    }

    this._ws = socket
    this._connected = false
    this._attachSocket(socket)

    await new Promise((resolve, reject) => {
      let settled = false
      let timer = null
      const cleanup = () => {
        if (timer !== null) clearTimeout(timer)
        offEmitter(socket, 'open', handleOpen)
        offEmitter(socket, 'error', handleError)
        offEmitter(socket, 'close', handleClose)
      }
      const settleOk = () => {
        if (settled) return
        settled = true
        cleanup()
        resolve()
      }
      const settleFail = (error) => {
        if (settled) return
        settled = true
        cleanup()
        reject(error instanceof DshCallError
          ? error
          : new DshCallError('ws_open', 'remote.mux', error?.message ?? String(error)))
      }
      const handleOpen = () => settleOk()
      const handleError = (error) => settleFail(error)
      const handleClose = (code) => settleFail(
        new DshCallError('ws_closed', 'remote.mux', `closed before open (code ${code})`),
      )
      timer = setTimeout(() => {
        settleFail(new DshCallError('ws_timeout', 'remote.mux', 'WebSocket open timed out'))
        try {
          if (typeof socket.terminate === 'function') socket.terminate()
        } catch {
          // 忽略
        }
      }, OPEN_TIMEOUT_MS)
      timer.unref?.()
      onEmitter(socket, 'open', handleOpen)
      onEmitter(socket, 'error', handleError)
      onEmitter(socket, 'close', handleClose)
      // 测试 fake 可能同步就是 OPEN：补一次检查。
      if (typeof socket.readyState === 'number' && socket.readyState === 1) settleOk()
    })
  }

  /** 注册 socket 的长期监听。 */
  _attachSocket(socket) {
    this._lastFrameAt = Date.now()
    this._sawPing = false
    onEmitter(socket, 'message', (data) => this._handleRawMessage(data))
    onEmitter(socket, 'open', () => this._handleOpen(socket))
    onEmitter(socket, 'close', (code, reason) => this._handleClose(socket, code, reason))
    onEmitter(socket, 'error', (error) => {
      if (this._ws !== socket) return
      this._log('warn', `mux error: ${error?.message ?? error}`)
    })
    onEmitter(socket, 'ping', () => {
      // DSH 每 2s 发一次协议级 ping；收到就说明链路还活着。
      this._sawPing = true
      this._lastFrameAt = Date.now()
    })
    onEmitter(socket, 'pong', () => {
      this._lastFrameAt = Date.now()
    })
    onEmitter(socket, 'unexpected-response', (_request, response) => {
      if (this._ws !== socket) return
      this._log('warn', `mux upgrade rejected: HTTP ${response?.statusCode ?? '?'}`)
    })
  }

  /** mux 打开：重开 4 类逻辑流并通知状态。 */
  _handleOpen(socket) {
    if (this._ws !== socket) return
    this._connected = true
    this._streams.clear()
    this._lastFrameAt = Date.now()
    this._startWatchdog()

    if (this._stableTimer !== null) clearTimeout(this._stableTimer)
    this._stableTimer = setTimeout(() => {
      this._stableTimer = null
      this._attempt = 0
    }, STABLE_RESET_MS)
    this._stableTimer.unref?.()

    this._emitStatus({ connected: true })
    this._openCoreStreams()
    for (const entry of this._follows.values()) this._sendFollow(entry)
  }

  /** 打开启动即开的 3 条逻辑流。 */
  _openCoreStreams() {
    this._openStream(STREAM_IDS.events, EVENTS_ENDPOINT, {}, (value) => {
      this._callHandler(this.handlers.onEvent, value)
    })
    this._openStream(STREAM_IDS.control, CONTROL_ENDPOINT, {}, (value) => {
      this._callHandler(this.handlers.onControl, value)
    })
    this._openStream(STREAM_IDS.workspace, WORKSPACE_ENDPOINT, {}, (value) => {
      this._callHandler(this.handlers.onWorkspace, value)
    })
  }

  /** 发送一条 session/follow open 帧。
   *
   * 注意线格式：mux open 的 args 键是 wire 名 `request`，即
   * `{"args":{"request":{address:{kind:'session',sessionId},assistantStream:true}}}`；
   * `assistantStream` 只能是字面量 true（DSH 用 z.literal(true) 校验）。
   */
  _sendFollow(entry) {
    this._openStream(
      entry.streamId,
      SESSION_ENDPOINT,
      {
        request: {
          address: { kind: 'session', sessionId: entry.sessionId },
          assistantStream: true,
        },
      },
      (value) => this._callHandler(this.handlers.onSessionFrame, entry.sessionId, value),
      { followSessionId: entry.sessionId },
    )
    entry.opened = true
  }

  /** 登记逻辑流并发送 open 帧。 */
  _openStream(streamId, endpoint, args, handler, meta = {}) {
    this._streams.set(streamId, { streamId, endpoint, handler, ...meta })
    this._send({ type: 'open', streamId, endpoint, payload: { args: args ?? {} } })
  }

  /** 收到 mux 原始文本（兼容 ws / 浏览器 WebSocket 事件对象）。 */
  _handleRawMessage(payload) {
    this._lastFrameAt = Date.now()
    let raw = payload
    if (raw !== null && typeof raw === 'object' && !Buffer.isBuffer(raw) && 'data' in raw) {
      raw = raw.data
    }
    let text = ''
    if (typeof raw === 'string') {
      text = raw
    } else if (typeof Buffer !== 'undefined' && Buffer.isBuffer(raw)) {
      text = raw.toString('utf8')
    } else if (raw !== null && raw !== undefined) {
      try {
        text = String(raw)
      } catch {
        return
      }
    }
    if (text === '') return

    let frame
    try {
      frame = JSON.parse(text)
    } catch {
      this._log('debug', 'dropped malformed mux frame')
      return
    }
    if (!isRecord(frame)) return

    const streamId = typeof frame.streamId === 'string' ? frame.streamId : ''
    switch (frame.type) {
      case 'item':
        this._handleItem(streamId, frame.value)
        break
      case 'end':
        this._handleStreamEnd(streamId)
        break
      case 'error':
        this._handleStreamError(streamId, frame.error)
        break
      default:
        this._log('debug', `unknown mux frame type ${String(frame.type)}`)
    }
  }

  /** 路由一条流 item。 */
  _handleItem(streamId, value) {
    const stream = this._streams.get(streamId)
    if (stream === undefined) return

    if (stream.endpoint === EVENTS_ENDPOINT && isRecord(value) && value.type === 'ready') {
      const clientId = typeof value.clientId === 'string' ? value.clientId : null
      this._clientId = clientId
      this._log('info', `$events ready (clientId=${clientId === null ? '?' : 'set'})`)
      this._emitStatus({ connected: true, clientId: clientId ?? undefined })
    }

    this._callHandler(stream.handler, value)
  }

  /** 服务端结束某条流。 */
  _handleStreamEnd(streamId) {
    const stream = this._streams.get(streamId)
    if (stream === undefined) return
    this._streams.delete(streamId)
    if (typeof stream.followSessionId === 'string') {
      const entry = this._follows.get(stream.followSessionId)
      if (entry !== undefined) entry.opened = false
    }
    if (CORE_ENDPOINTS.has(stream.endpoint)) {
      this._log('warn', `core stream ${stream.endpoint} ended; recycling mux`)
      this._recycleSocket('core stream ended')
      return
    }
    this._log('debug', `stream ${stream.endpoint} ended`)
  }

  /** 服务端报告某条流失败。 */
  _handleStreamError(streamId, error) {
    const stream = this._streams.get(streamId)
    const code = isRecord(error) && typeof error.code === 'string' ? error.code : 'stream_error'
    const message = isRecord(error) && typeof error.message === 'string' ? error.message : ''
    this._log('warn', `stream ${stream?.endpoint ?? streamId} failed: ${code}${message === '' ? '' : `: ${message}`}`)
    if (stream === undefined) return
    this._streams.delete(streamId)
    if (typeof stream.followSessionId === 'string') {
      const entry = this._follows.get(stream.followSessionId)
      if (entry !== undefined) entry.opened = false
      return
    }
    if (CORE_ENDPOINTS.has(stream.endpoint)) this._recycleSocket(`core stream ${stream.endpoint} failed`)
  }

  /** mux 关闭。 */
  _handleClose(socket, code, reason) {
    if (this._ws !== socket) return
    this._ws = null
    this._connected = false
    this._clientId = null
    this._streams.clear()
    this._stopWatchdog()
    if (this._stableTimer !== null) {
      clearTimeout(this._stableTimer)
      this._stableTimer = null
    }

    const text = reasonText(reason)
    const message = text === '' ? `mux closed (code ${code})` : `mux closed (code ${code}): ${text}`
    this._emitStatus({ connected: false, error: message })
    if (this._stopped) return
    this._log('warn', message)
    this._scheduleReconnect()
  }

  /** 主动回收当前 socket（触发 close → 重连）。 */
  _recycleSocket(reason) {
    const socket = this._ws
    if (socket === null) return
    this._log('debug', `recycling mux: ${reason}`)
    try {
      if (typeof socket.terminate === 'function') socket.terminate()
      else if (typeof socket.close === 'function') socket.close(1000, 'recycle')
    } catch {
      // 忽略
    }
  }

  /** 指数退避调度下一次连接。 */
  _scheduleReconnect() {
    if (this._stopped || this._reconnectTimer !== null) return
    const delay = backoffDelay(this._attempt, BASE_BACKOFF_MS, MAX_BACKOFF_MS)
    this._attempt = Math.min(this._attempt + 1, 16)
    this._log('debug', `reconnect in ${delay}ms (attempt ${this._attempt})`)
    this._reconnectTimer = setTimeout(() => {
      this._reconnectTimer = null
      void this._runConnect()
    }, delay)
    this._reconnectTimer.unref?.()
  }

  /** 清除重连定时器。 */
  _clearReconnectTimer() {
    if (this._reconnectTimer === null) return
    clearTimeout(this._reconnectTimer)
    this._reconnectTimer = null
  }

  /** 单飞连接（失败会记录状态并调度重连）。 */
  _runConnect() {
    if (this._connectPromise !== null) return this._connectPromise
    this._connectPromise = (async () => {
      try {
        await this._connectOnce()
        return true
      } catch (error) {
        const message = error?.message ?? String(error)
        this._log('warn', `loopback connect failed: ${message}`)
        this._emitStatus({ connected: false, error: message })
        if (!this._stopped) this._scheduleReconnect()
        return false
      } finally {
        this._connectPromise = null
      }
    })()
    return this._connectPromise
  }

  /** 心跳看门狗：长时间收不到任何帧就回收 socket。
   *
   * DSH 每 2s 发协议 ping、连续 2 个周期无响应会直接 terminate（无 close 帧），
   * 所以只要观察到 ping，就把「无帧」阈值收紧到约 3 个周期；从未见过 ping 的
   * 链路（测试 mock 可能关闭 ping）退回保守的 60s，避免误杀静默但健康的连接。
   */
  _startWatchdog() {
    this._stopWatchdog()
    this._watchdog = setInterval(() => {
      if (!this._connected) return
      const idleMs = this._sawPing ? WATCHDOG_PING_IDLE_MS : WATCHDOG_IDLE_MS
      if (Date.now() - this._lastFrameAt <= idleMs) return
      this._log('warn', `mux idle timeout (${idleMs}ms); recycling socket`)
      this._recycleSocket('idle timeout')
    }, WATCHDOG_INTERVAL_MS)
    this._watchdog.unref?.()
  }

  /** 停止看门狗。 */
  _stopWatchdog() {
    if (this._watchdog === null) return
    clearInterval(this._watchdog)
    this._watchdog = null
  }

  // ---------------------------------------------------------------------------
  // 发送 / 工具
  // ---------------------------------------------------------------------------

  /**
   * 串行发送一帧（promise 链保证顺序；socket 换代会自动丢弃旧帧）。
   * @param {object} frame
   */
  _send(frame) {
    let text
    try {
      text = JSON.stringify(frame)
    } catch (error) {
      this._log('warn', `cannot serialize mux frame: ${error?.message ?? error}`)
      return
    }
    const socket = this._ws
    this._sendChain = this._sendChain
      .then(() => {
        if (socket === null || this._ws !== socket || !this._isOpen(socket)) return
        socket.send(text)
      })
      .catch((error) => {
        this._log('warn', `mux send failed: ${error?.message ?? error}`)
      })
  }

  /** socket 是否 open（兼容没有 readyState 的 fake）。 */
  _isOpen(socket) {
    if (socket === null || socket === undefined) return false
    if (typeof socket.readyState === 'number') return socket.readyState === 1
    return true
  }

  /** 取 fetch 实现。 */
  _requireFetch() {
    const fetchImpl = this._fetch ?? (typeof globalThis.fetch === 'function' ? globalThis.fetch : null)
    if (typeof fetchImpl !== 'function') {
      throw new DshCallError('fetch_unavailable', 'fetch', 'no fetch implementation available')
    }
    return fetchImpl
  }

  /** 取 WebSocket 实现。 */
  _requireWebSocket() {
    if (typeof this._WebSocket !== 'function') {
      throw new DshCallError('ws_unavailable', 'remote.mux', 'no WebSocket implementation available')
    }
    return this._WebSocket
  }

  /** 组合外部 signal 与超时 signal。 */
  _composeSignal(external, timeoutMs) {
    const signals = []
    if (external !== undefined && external !== null) signals.push(external)
    if (
      Number.isFinite(Number(timeoutMs))
      && Number(timeoutMs) > 0
      && typeof AbortSignal !== 'undefined'
      && typeof AbortSignal.timeout === 'function'
    ) {
      signals.push(AbortSignal.timeout(Number(timeoutMs)))
    }
    if (signals.length === 0) return undefined
    if (signals.length === 1) return signals[0]
    if (typeof AbortSignal.any === 'function') return AbortSignal.any(signals)
    return signals[0]
  }

  /** 安全调用外部 handler（异常不外溢到 mux 事件循环）。 */
  _callHandler(handler, ...args) {
    if (typeof handler !== 'function') return
    try {
      const result = handler(...args)
      if (result !== null && typeof result === 'object' && typeof result.catch === 'function') {
        result.catch((error) => this._log('warn', `frame handler failed: ${error?.message ?? error}`))
      }
    } catch (error) {
      this._log('warn', `frame handler failed: ${error?.message ?? error}`)
    }
  }

  /** 上报连接状态。 */
  _emitStatus(state) {
    const payload = { connected: Boolean(state?.connected) }
    if (typeof state?.error === 'string' && state.error !== '') payload.error = state.error
    if (typeof state?.clientId === 'string' && state.clientId !== '') payload.clientId = state.clientId
    this._callHandler(this.handlers.onStatus, payload)
  }

  /** 统一日志（绝不打印 Cookie/正文）。 */
  _log(level, message) {
    try {
      const fn = this.logger?.[level] ?? this.logger?.info
      if (typeof fn === 'function') fn.call(this.logger, message)
    } catch {
      // 日志绝不影响主流程
    }
  }
}

export default DshLoopbackClient
