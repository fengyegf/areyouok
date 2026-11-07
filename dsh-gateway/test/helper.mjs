/**
 * Shared test harness for dsh-gateway.
 *
 * Responsibilities:
 *   - pick free loopback ports (`config.httpPort === 0` means *disabled*, so
 *     tests must never pass 0 expecting an ephemeral port),
 *   - build the minimal Cordis context the plugin needs
 *     (`webServer.port/host/getOrigin()`, `connection.authenticatedUrl()`),
 *   - boot the whole gateway stack against a {@link MockDsh},
 *   - HTTP / HTTPS / WebSocket client helpers with timeouts,
 *   - `waitFor` polling and deterministic teardown (timers + servers + temp dirs).
 *
 * The harness never imports `lib/*.js` at module load time: the core and edge
 * modules are developed in parallel, so `startGateway()` / `loadLib()` import
 * them lazily and tests can skip with a clear reason while they are missing.
 *
 * @module dsh-gateway/test/helper
 */

import { mkdtemp, rm } from 'node:fs/promises'
import { request as httpRequest } from 'node:http'
import { request as httpsRequest } from 'node:https'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import WebSocket from 'ws'

/** Directory of this test folder (absolute). */
export const TEST_DIR = fileURLToPath(new URL('.', import.meta.url))

/** Default wait budget for one asynchronous condition. */
export const DEFAULT_TIMEOUT_MS = 8_000

/** Module loaders that may be missing while plugin-core / plugin-edge work. */
const LIB_MODULES = ['index.js', 'util.js', 'config.js', 'hub.js', 'normalize.js', 'dsh.js', 'http.js', 'ws.js', 'auth.js', 'tls.js', 'mdns.js', 'admin.js']

/**
 * Wait for one condition, polling on a short interval.
 * @template T
 * @param {() => T | Promise<T>} predicate - truthy result resolves the promise.
 * @param {{timeoutMs?:number, intervalMs?:number, label?:string}} [options]
 * @returns {Promise<T>}
 */
export async function waitFor(predicate, options = {}) {
  const timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS
  const intervalMs = options.intervalMs ?? 10
  const label = options.label ?? 'condition'
  const started = Date.now()
  let lastError
  for (;;) {
    try {
      const value = await predicate()
      if (value) return value
    } catch (error) {
      lastError = error
    }
    if (Date.now() - started >= timeoutMs) {
      const suffix = lastError === undefined ? '' : ` (last error: ${lastError.message})`
      throw new Error(`waitFor(${label}) timed out after ${timeoutMs}ms${suffix}`)
    }
    await sleep(intervalMs)
  }
}

/**
 * Delay helper that does not keep the event loop alive forever.
 * @param {number} ms
 */
export function sleep(ms) {
  return new Promise((resolve) => {
    const timer = setTimeout(resolve, ms)
    timer.unref?.()
  })
}

/**
 * Ask the kernel for a currently free loopback TCP port.
 * @param {string} [host]
 * @returns {Promise<number>}
 */
export async function freePort(host = '127.0.0.1') {
  const server = createServer()
  await new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(0, host, resolve)
  })
  const address = server.address()
  const port = typeof address === 'object' && address !== null ? address.port : 0
  await new Promise((resolve) => server.close(() => resolve()))
  return port
}

/** A silent logger that records entries for assertions. */
export function makeLogger() {
  const entries = []
  const record = (level) => (message, meta) => {
    entries.push({ level, message: String(message ?? ''), meta })
  }
  return {
    entries,
    debug: record('debug'),
    info: record('info'),
    warn: record('warn'),
    error: record('error'),
  }
}

/**
 * Build the minimal Cordis-like context the gateway needs.
 * @param {object} options
 * @param {string} options.origin - loopback origin of the (mock) DSH host.
 * @param {number} options.port - port of the (mock) DSH host.
 * @param {string} [options.host]
 * @param {string} [options.launchToken]
 * @param {object} [options.logger]
 */
export function makeCtx(options) {
  const host = options.host ?? '127.0.0.1'
  const port = options.port
  const origin = options.origin ?? `http://${host}:${port}`
  const launchToken = options.launchToken ?? ''
  const webServer = {
    get port() {
      return port
    },
    get host() {
      return host
    },
    getOrigin() {
      return origin
    },
    register() {
      throw new Error('mock ctx: ctx.webServer.register is not implemented')
    },
    registerUpgrade() {
      throw new Error('mock ctx: ctx.webServer.registerUpgrade is not implemented')
    },
  }
  const connection = {
    authenticatedUrl(baseUrl) {
      const url = new URL(baseUrl)
      url.searchParams.set('token', launchToken)
      return url.href
    },
  }
  return {
    webServer,
    connection,
    logger: options.logger,
    effect: (factory) => {
      const dispose = typeof factory === 'function' ? factory() : undefined
      return typeof dispose === 'function' ? dispose : () => {}
    },
    on() {
      return () => {}
    },
    get() {
      return undefined
    },
    emit() {},
  }
}

/** Collect async disposers so a test can tear everything down deterministically. */
export function createResources() {
  const disposers = []
  return {
    /** @param {() => unknown|Promise<unknown>} dispose */
    add(dispose) {
      disposers.push(dispose)
      return dispose
    },
    async disposeAll() {
      while (disposers.length > 0) {
        const dispose = disposers.pop()
        try {
          await dispose()
        } catch {
          // teardown must never mask a test failure
        }
      }
    },
  }
}

/**
 * Lazily import one `lib/` module.
 * @param {string} name - file name inside `lib/`, e.g. `hub.js`.
 * @returns {Promise<any>}
 */
export async function loadLib(name) {
  return import(new URL(`../lib/${name}`, import.meta.url).href)
}

/**
 * Describe which `lib/` modules are missing right now.
 * @returns {Promise<{missing:string[], present:string[]}>}
 */
export async function libStatus() {
  const missing = []
  const present = []
  for (const name of LIB_MODULES) {
    try {
      await loadLib(name)
      present.push(name)
    } catch (error) {
      if (error?.code === 'ERR_MODULE_NOT_FOUND') missing.push(name)
      else throw error
    }
  }
  return { missing, present }
}

/**
 * Create a unique temporary data directory, removed on teardown.
 * @param {{add:Function}} resources
 * @returns {Promise<string>}
 */
export async function makeTempDir(resources) {
  const dir = await mkdtemp(join(tmpdir(), 'dshgw-'))
  resources.add(() => rm(dir, { recursive: true, force: true }))
  return dir
}

/**
 * Start the gateway stack against a mock DSH.
 *
 * Picks free ports unless the caller pins `httpPort` / `httpsPort`; retries a
 * few times when the kernel hands out a port that is grabbed before listen.
 *
 * @param {object} options
 * @param {import('./mock-dsh.mjs').MockDsh} options.mock
 * @param {Record<string, unknown>} [options.config]
 * @param {object} [options.ctx] - override the generated mock context.
 * @param {string} [options.dir] - data dir (temp dir by default).
 * @param {object} [options.logger]
 * @param {{add:Function}} [options.resources]
 * @param {number} [options.attempts]
 * @returns {Promise<{gateway:any, ports:{lanPort:number,publicPort:number}, dir:string, resources:object, stop:() => Promise<void>, lanBase:string, publicBase:string}>}
 */
export async function startGateway(options) {
  const { mock } = options
  if (mock === undefined) throw new Error('startGateway: options.mock is required')
  const resources = options.resources ?? createResources()
  const logger = options.logger ?? makeLogger()
  const dir = options.dir ?? (await makeTempDir(resources))
  const attempts = options.attempts ?? 4
  const { createGateway } = await loadLib('index.js')

  const wanted = options.config ?? {}
  let lastError
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    const config = { ...wanted }
    if (config.httpPort === undefined) config.httpPort = await freePort()
    if (config.httpsPort === undefined) config.httpsPort = 0
    if (config.host === undefined) config.host = '127.0.0.1'
    if (config.mdns === undefined) config.mdns = false
    // `ws.js` clamps these to >= 5s; keep tests short-lived but not idle-killed.
    if (config.heartbeatMs === undefined) config.heartbeatMs = 5_000
    if (config.idleTimeoutMs === undefined) config.idleTimeoutMs = 60_000

    const ctx = options.ctx ?? makeCtx({
      origin: mock.origin,
      port: mock.port,
      host: mock.host,
      launchToken: mock.launchToken,
      logger,
    })
    const gateway = createGateway({ ctx, config, logger, dir })
    try {
      const ports = await gateway.start()
      const lanBase = `http://127.0.0.1:${ports.lanPort}`
      const publicBase = ports.publicPort > 0 ? `https://127.0.0.1:${ports.publicPort}` : ''
      let stopped = false
      const stop = async () => {
        if (stopped) return
        stopped = true
        try {
          await gateway.stop()
        } catch {
          // ignore
        }
      }
      resources.add(stop)
      return { gateway, ports, dir, resources, stop, lanBase, publicBase, logger, ctx }
    } catch (error) {
      lastError = error
      try {
        await gateway.stop()
      } catch {
        // ignore
      }
      if (error?.code !== 'EADDRINUSE' && error?.code !== 'EACCES') break
    }
  }
  throw lastError ?? new Error('startGateway: failed to start')
}

/**
 * Start a {@link MockDsh} and register its teardown.
 * @param {object} options - forwarded to the MockDsh constructor.
 * @param {{add:Function}} [options.resources]
 * @returns {Promise<import('./mock-dsh.mjs').MockDsh>}
 */
export async function startMockDsh(options = {}) {
  const { MockDsh } = await import('./mock-dsh.mjs')
  const resources = options.resources ?? createResources()
  const mock = new MockDsh(options)
  await mock.start()
  resources.add(() => mock.stop())
  return mock
}

/**
 * One HTTP request against the gateway.
 *
 * @param {string} base - e.g. `http://127.0.0.1:7800`.
 * @param {string} path - path including query string.
 * @param {object} [options]
 * @param {string} [options.method]
 * @param {Record<string,string>} [options.headers]
 * @param {unknown} [options.body] - JSON encoded unless it is a string.
 * @param {string} [options.token] - shorthand for an `authorization: Bearer` header.
 * @param {number} [options.timeoutMs]
 * @returns {Promise<{status:number, headers:Headers, text:string, json:any, ok:boolean}>}
 */
export async function request(base, path, options = {}) {
  const headers = { ...(options.headers ?? {}) }
  let body
  if (options.body !== undefined) {
    body = typeof options.body === 'string' ? options.body : JSON.stringify(options.body)
    if (headers['content-type'] === undefined) headers['content-type'] = 'application/json'
  }
  if (options.token !== undefined) headers.authorization = `Bearer ${options.token}`
  const timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS
  const response = await fetch(`${base}${path}`, {
    method: options.method ?? 'GET',
    headers,
    body,
    redirect: 'manual',
    signal: AbortSignal.timeout(timeoutMs),
  })
  const text = await response.text()
  let json
  try {
    json = text === '' ? undefined : JSON.parse(text)
  } catch {
    json = undefined
  }
  return { status: response.status, headers: response.headers, text, json, ok: response.ok }
}

/**
 * One HTTPS request that tolerates the gateway's self-signed certificate.
 *
 * @param {object} options
 * @param {number} options.port
 * @param {string} [options.host]
 * @param {string} [options.path]
 * @param {string} [options.method]
 * @param {Record<string,string>} [options.headers]
 * @param {unknown} [options.body]
 * @param {string} [options.token]
 * @param {number} [options.timeoutMs]
 * @returns {Promise<{status:number, headers:object, text:string, json:any}>}
 */
export function secureRequest(options) {
  const host = options.host ?? '127.0.0.1'
  const headers = { ...(options.headers ?? {}) }
  let payload
  if (options.body !== undefined) {
    payload = typeof options.body === 'string' ? options.body : JSON.stringify(options.body)
    if (headers['content-type'] === undefined) headers['content-type'] = 'application/json'
    headers['content-length'] = Buffer.byteLength(payload)
  }
  if (options.token !== undefined) headers.authorization = `Bearer ${options.token}`
  const timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS
  return new Promise((resolve, reject) => {
    const req = httpsRequest(
      {
        host,
        port: options.port,
        path: options.path ?? '/v1/health',
        method: options.method ?? 'GET',
        headers,
        rejectUnauthorized: false,
        timeout: timeoutMs,
      },
      (res) => {
        const chunks = []
        res.on('data', (chunk) => chunks.push(chunk))
        res.on('end', () => {
          const text = Buffer.concat(chunks).toString('utf8')
          let json
          try {
            json = text === '' ? undefined : JSON.parse(text)
          } catch {
            json = undefined
          }
          resolve({ status: res.statusCode ?? 0, headers: res.headers, text, json })
        })
      },
    )
    req.on('timeout', () => req.destroy(new Error(`https request timed out after ${timeoutMs}ms`)))
    req.on('error', reject)
    if (payload !== undefined) req.write(payload)
    req.end()
  })
}

/**
 * A JSON-over-WebSocket client used to poke `/v1/stream`.
 *
 * Frames are buffered so tests can await a specific one without races.
 */
export class StreamClient {
  /**
   * @param {import('ws').WebSocket} socket
   */
  constructor(socket) {
    this.socket = socket
    /** @type {Array<any>} */
    this.frames = []
    /** @type {Array<{resolve:Function, reject:Function, predicate:Function, timer:any, label:string}>} */
    this._waiters = []
    this.closed = false
    this.closeCode = null
    this.closeReason = ''
    socket.on('message', (data) => {
      let frame
      try {
        frame = JSON.parse(data.toString('utf8'))
      } catch {
        frame = { raw: data.toString('utf8') }
      }
      this.frames.push(frame)
      this._settle()
    })
    socket.on('close', (code, reason) => {
      this.closed = true
      this.closeCode = code
      this.closeReason = reason?.toString?.('utf8') ?? ''
      this._settle()
    })
    socket.on('error', () => {
      // close handler reports the state
    })
  }

  /**
   * Open a stream client.
   * @param {string} url - `ws://host:port/v1/stream`.
   * @param {{headers?:Record<string,string>, timeoutMs?:number, insecure?:boolean}} [options]
   *   `insecure:true` skips certificate verification (self-signed gateway cert).
   * @returns {Promise<StreamClient>}
   */
  static async connect(url, options = {}) {
    const timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS
    const socket = new WebSocket(url, {
      headers: options.headers ?? {},
      ...(options.insecure === true ? { rejectUnauthorized: false } : {}),
    })
    // Attach the frame listener *before* awaiting `open`: the gateway sends
    // `ready` immediately after the handshake, so a listener installed later
    // would race and drop the very first frame.
    const client = new StreamClient(socket)
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        cleanup()
        try {
          socket.terminate()
        } catch {
          // ignore
        }
        reject(new Error(`WebSocket connect timed out after ${timeoutMs}ms`))
      }, timeoutMs)
      const cleanup = () => {
        clearTimeout(timer)
        socket.removeListener('open', onOpen)
        socket.removeListener('error', onError)
        socket.removeListener('unexpected-response', onUnexpected)
      }
      const onOpen = () => {
        cleanup()
        resolve()
      }
      const onError = (error) => {
        cleanup()
        reject(error)
      }
      const onUnexpected = (_req, res) => {
        cleanup()
        reject(new Error(`WebSocket upgrade rejected with HTTP ${res.statusCode}`))
      }
      socket.once('open', onOpen)
      socket.once('error', onError)
      socket.once('unexpected-response', onUnexpected)
    })
    return client
  }

  /** @param {object} frame */
  send(frame) {
    if (this.socket.readyState !== WebSocket.OPEN) throw new Error('stream socket is not open')
    this.socket.send(JSON.stringify(frame))
  }

  /**
   * Wait for a frame matching `predicate`; already-buffered frames are checked first.
   * @param {(frame:any) => boolean} predicate
   * @param {{timeoutMs?:number, label?:string, from?:number}} [options]
   *   `from` skips frames already present (start index).
   * @returns {Promise<any>}
   */
  waitForFrame(predicate, options = {}) {
    const timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS
    const label = options.label ?? 'frame'
    const from = options.from ?? 0
    for (let index = from; index < this.frames.length; index += 1) {
      if (predicate(this.frames[index])) return Promise.resolve(this.frames[index])
    }
    if (this.closed) return Promise.reject(new Error(`stream closed before ${label} (code ${this.closeCode})`))
    return new Promise((resolve, reject) => {
      const waiter = { resolve, reject, predicate, label, timer: null }
      waiter.timer = setTimeout(() => {
        this._waiters = this._waiters.filter((item) => item !== waiter)
        reject(new Error(`timed out waiting for ${label}; saw ${JSON.stringify(this.frames.slice(0, 8))}`))
      }, timeoutMs)
      waiter.timer.unref?.()
      this._waiters.push(waiter)
      // Frames may have arrived between the scan above and the push.
      this._settle()
    })
  }

  /**
   * Wait for the first frame whose `t` equals `type`.
   * @param {string} type
   * @param {{timeoutMs?:number, predicate?:(frame:any)=>boolean, from?:number}} [options]
   */
  waitForType(type, options = {}) {
    const predicate = (frame) => frame?.t === type && (options.predicate?.(frame) ?? true)
    return this.waitForFrame(predicate, { timeoutMs: options.timeoutMs, label: `frame t=${type}`, from: options.from })
  }

  /** @returns {Array<any>} frames of one type. */
  framesOfType(type) {
    return this.frames.filter((frame) => frame?.t === type)
  }

  /** Wait until the socket is closed by the server. */
  waitForClose(timeoutMs = DEFAULT_TIMEOUT_MS) {
    if (this.closed) return Promise.resolve({ code: this.closeCode, reason: this.closeReason })
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('timed out waiting for stream close')), timeoutMs)
      timer.unref?.()
      this.socket.once('close', (code, reason) => {
        clearTimeout(timer)
        resolve({ code, reason: reason?.toString?.('utf8') ?? '' })
      })
    })
  }

  /** Close politely and wait for the handshake (or force it after a grace period). */
  async close() {
    if (this.closed) return
    try {
      this.socket.close()
    } catch {
      // ignore
    }
    try {
      await this.waitForClose(1_000)
    } catch {
      try {
        this.socket.terminate()
      } catch {
        // ignore
      }
    }
  }

  /** Drop the TCP connection without a close handshake. */
  terminate() {
    try {
      this.socket.terminate()
    } catch {
      // ignore
    }
  }

  _settle() {
    for (const waiter of [...this._waiters]) {
      const hit = this.frames.find((frame) => waiter.predicate(frame))
      if (hit !== undefined) {
        clearTimeout(waiter.timer)
        this._waiters = this._waiters.filter((item) => item !== waiter)
        waiter.resolve(hit)
        continue
      }
      if (this.closed) {
        clearTimeout(waiter.timer)
        this._waiters = this._waiters.filter((item) => item !== waiter)
        waiter.reject(new Error(`stream closed before ${waiter.label} (code ${this.closeCode})`))
      }
    }
  }
}

/**
 * Connect one `/v1/stream` client and register its teardown.
 * @param {{add:Function}} resources
 * @param {string} base - HTTP base URL of the gateway.
 * @param {{token?:string, timeoutMs?:number, path?:string}} [options]
 * @returns {Promise<StreamClient>}
 */
export async function connectStream(resources, base, options = {}) {
  const url = `${base.replace(/^http/, 'ws')}${options.path ?? '/v1/stream'}`
  const headers = options.token === undefined ? {} : { authorization: `Bearer ${options.token}` }
  const client = await StreamClient.connect(url, {
    headers,
    timeoutMs: options.timeoutMs,
    insecure: options.insecure === true,
  })
  resources.add(() => client.close())
  return client
}

/**
 * Wait until `/v1/health` answers successfully, i.e. the API listener is up.
 * @param {string} base
 * @param {{timeoutMs?:number, token?:string}} [options]
 */
export async function waitForHealth(base, options = {}) {
  return waitFor(async () => {
    try {
      const response = await request(base, '/v1/health', { token: options.token, timeoutMs: 1_000 })
      return response.status === 200 ? response : false
    } catch {
      return false
    }
  }, { timeoutMs: options.timeoutMs ?? DEFAULT_TIMEOUT_MS, label: 'GET /v1/health' })
}

/**
 * Wait until the gateway's loopback link to the mock DSH reported connected.
 * @param {any} gateway
 * @param {{timeoutMs?:number}} [options]
 */
export async function waitForDsh(gateway, options = {}) {
  return waitFor(() => {
    const status = gateway.hub?.getDshStatus?.()
    if (status?.connected === true) return status
    if (gateway.dsh?.connected === true) return { connected: true }
    return false
  }, { timeoutMs: options.timeoutMs ?? DEFAULT_TIMEOUT_MS, label: 'DSH link connected' })
}

/**
 * Wait until a session appears in the hub (workspace/session baseline applied).
 * @param {any} gateway
 * @param {string} sessionId
 * @param {{timeoutMs?:number}} [options]
 */
export async function waitForSession(gateway, sessionId, options = {}) {
  return waitFor(() => {
    const sessions = gateway.hub?.getSessions?.() ?? []
    return sessions.find((session) => session.sessionId === sessionId) ?? false
  }, { timeoutMs: options.timeoutMs ?? DEFAULT_TIMEOUT_MS, label: `session ${sessionId}` })
}

/**
 * Run a test body with timeout protection, converting a hang into a failure.
 * @param {number} ms
 * @param {() => Promise<unknown>} body
 */
export function withTimeout(ms, body) {
  let timer
  const timeout = new Promise((_resolve, reject) => {
    timer = setTimeout(() => reject(new Error(`test body exceeded ${ms}ms`)), ms)
    timer.unref?.()
  })
  return Promise.race([body(), timeout]).finally(() => clearTimeout(timer))
}

export default {
  DEFAULT_TIMEOUT_MS,
  StreamClient,
  connectStream,
  createResources,
  freePort,
  libStatus,
  loadLib,
  makeCtx,
  makeLogger,
  makeTempDir,
  request,
  secureRequest,
  sleep,
  startGateway,
  startMockDsh,
  waitFor,
  waitForDsh,
  waitForHealth,
  waitForSession,
  withTimeout,
}
