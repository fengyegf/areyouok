/**
 * The two HTTP(S) listeners of the gateway, the whole `/v1` REST surface, and
 * the management API behind the in-app configuration page.
 *
 *   - LAN edge: `config.httpPort` (0 = disabled), plain HTTP, private sources
 *     only (the `/v1/pair` route is the only pre-auth endpoint),
 *   - public edge: `config.httpsPort` (0 = disabled), TLS with the self-signed
 *     material from `tls.js`, every route requires a device Bearer token
 *     except `/v1/pair`,
 *   - the management API is **not** served here: there is no standalone admin
 *     page any more. `index.js` mounts those JSON routes on the DSH host web
 *     server (`mountPanel`), so the Web UI calls them from its own origin and
 *     the browser half can render the panel inside the Plugins page.
 *
 * The DSH side is reached exclusively through the injected `dsh` client
 * (`POST /api/<method>` + follow streams); this file never touches DSH
 * internals. `dsh` is wired by `index.js` *after* `start()`, so it may be
 * `null` for a short window and every data-plane route then answers
 * `503 dsh_unavailable`.
 *
 * All responses use one envelope: `{ok:true, ...payload}` on success and
 * `{ok:false, error:{code,message}}` on failure. No stack traces, tokens,
 * cookies or message bodies are ever logged.
 *
 * @module dsh-gateway/lib/http
 */

import { createServer as createHttpServer } from 'node:http'
import { createServer as createHttpsServer } from 'node:https'
import { extname, isAbsolute, resolve } from 'node:path'
import {
  HttpError,
  normalizeOrigin,
  readJsonBody,
  uuid,
  writeJson,
} from './util.js'
import {
  buildPairingView,
  buildState,
  enumerateAddresses,
  formatHost,
  pairingUri,
} from './panel.js'
import { filesOfContent, imagesOfContent } from './normalize.js'
import { formatFingerprint } from './tls.js'

/** DSH version this plugin was verified against. */
export const DSH_VERSION = '0.2.2-rc.1'

/** Gateway package version (kept in sync with package.json by hand). */
export const GATEWAY_VERSION = '0.2.1'

/** `/v1` protocol version. */
export const PROTOCOL_VERSION = 1

/** Approval outcomes accepted by DSH's `$events/result`. */
const APPROVAL_VALUES = new Set(['allowed-once', 'rejected', 'cancelled', 'unavailable'])

/** Request body cap. */
const BODY_LIMIT = 1_048_576

/** Default page size for the messages route. */
const DEFAULT_MESSAGE_LIMIT = 50

/** Hard page size cap. */
const MAX_MESSAGE_LIMIT = 200

/** Preset value shape accepted by `/v1/sessions/{id}/permission` (`[a-z0-9-]+`). */
const PRESET_VALUE_PATTERN = /^[a-z0-9-]+$/

/** Stored-image attachment ids are opaque tokens; reject path-ish characters. Real DSH ids are `sha256:<hex>`, so `:` must stay allowed. */
const ATTACHMENT_ID_PATTERN = /^[A-Za-z0-9._:-]{1,200}$/

/** Media types the preview route will echo back (anything else is refused). */
const IMAGE_MEDIA_TYPE_PATTERN = /^image\/[a-z0-9.+-]{1,64}$/i

/** File extensions the session-image preview route may read. */
const PREVIEW_IMAGE_TYPES = new Map([
  ['.png', 'image/png'],
  ['.jpg', 'image/jpeg'],
  ['.jpeg', 'image/jpeg'],
  ['.webp', 'image/webp'],
  ['.gif', 'image/gif'],
  ['.bmp', 'image/bmp'],
  ['.avif', 'image/avif'],
])

/** Upper bound for one preview read (bytes). */
const MAX_PREVIEW_BYTES = 16 * 1024 * 1024

/** Upper bound for one upload the gateway buffers before handing it to DSH. */
const MAX_UPLOAD_BYTES = 32 * 1024 * 1024

/** Hard cap on how many files one prompt may cite. */
const MAX_FILES_PER_MESSAGE = 16

/** Staged upload receipts are opaque host tokens; reject anything path-ish. */
const RECEIPT_ID_PATTERN = /^[A-Za-z0-9._:-]{1,200}$/

/** @returns {boolean} 是否为普通对象（非数组、非 null）。 */
function isRecord(value) {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/** Route table: [method, path pattern, handler name]. */
const ROUTES = [
  ['GET', /^\/v1\/health$/, 'health'],
  ['POST', /^\/v1\/pair$/, 'pair'],
  ['GET', /^\/v1\/workspaces$/, 'workspaces'],
  ['GET', /^\/v1\/sessions$/, 'sessionsList'],
  ['POST', /^\/v1\/sessions$/, 'sessionsCreate'],
  ['GET', /^\/v1\/sessions\/([^/]+)\/state$/, 'sessionState'],
  ['GET', /^\/v1\/sessions\/([^/]+)\/messages$/, 'messagesList'],
  ['POST', /^\/v1\/sessions\/([^/]+)\/messages$/, 'messagesSend'],
  ['POST', /^\/v1\/sessions\/([^/]+)\/files$/, 'fileUpload'],
  ['GET', /^\/v1\/sessions\/([^/]+)\/attachments\/([^/]+)$/, 'attachment'],
  ['GET', /^\/v1\/sessions\/([^/]+)\/image$/, 'sessionImage'],
  ['POST', /^\/v1\/sessions\/([^/]+)\/interrupt$/, 'sessionInterrupt'],
  ['POST', /^\/v1\/sessions\/([^/]+)\/model$/, 'sessionModel'],
  ['POST', /^\/v1\/sessions\/([^/]+)\/permission$/, 'sessionPermission'],
  ['GET', /^\/v1\/models$/, 'models'],
  ['GET', /^\/v1\/permissions$/, 'permissions'],
  ['GET', /^\/v1\/interventions$/, 'interventions'],
  ['POST', /^\/v1\/interventions\/([^/]+)\/answer$/, 'interventionAnswer'],
  ['GET', /^\/v1\/events$/, 'events'],
]

/** Strip characters a display name should never carry into the panel. */
function cleanName(value, fallback = '') {
  if (typeof value !== 'string') return fallback
  // eslint-disable-next-line no-control-regex
  const cleaned = value.replace(/[\u0000-\u001f\u007f]/g, '').trim()
  return cleaned === '' ? fallback : cleaned.slice(0, 64)
}

/** Parse an integer query parameter, or `fallback` when absent/invalid. */
function intParam(value, fallback = null, min = -Infinity, max = Infinity) {
  if (value === null || value === undefined || value === '') return fallback
  const parsed = Number.parseInt(String(value), 10)
  if (!Number.isFinite(parsed)) return fallback
  return Math.min(max, Math.max(min, Math.trunc(parsed)))
}

/** Decode one path segment, or throw a 400. */
function decodeSegment(value) {
  try {
    return decodeURIComponent(String(value))
  } catch {
    throw new HttpError(400, 'invalid_request', 'malformed path parameter')
  }
}

/**
 * Read one raw binary request body.
 *
 * Uploads are the only `/v1` route that carries bytes instead of JSON, so this
 * mirrors `readJsonBody` (same 400/413 mapping) without parsing anything.
 *
 * @param {import('node:http').IncomingMessage} req
 * @param {number} [limit] - byte cap.
 * @returns {Promise<Buffer>}
 * @throws {HttpError} 400 `invalid_request` / 413 `payload_too_large`.
 */
function readRawBody(req, limit = MAX_UPLOAD_BYTES) {
  return new Promise((resolve, reject) => {
    const chunks = []
    let size = 0
    let settled = false
    const fail = (error) => {
      if (settled) return
      settled = true
      req.removeAllListeners('data')
      req.removeAllListeners('end')
      req.removeAllListeners('error')
      reject(error)
    }
    req.on('data', (chunk) => {
      size += chunk.length
      if (size > limit) {
        fail(new HttpError(413, 'payload_too_large', 'request body too large'))
        return
      }
      chunks.push(chunk)
    })
    req.on('end', () => {
      if (settled) return
      settled = true
      resolve(Buffer.concat(chunks))
    })
    req.on('error', (error) => fail(new HttpError(400, 'invalid_request', error?.message)))
  })
}

/**
 * Normalize the `files` array of a send body into staged receipt ids.
 *
 * Two shapes are accepted so clients can keep their JSON small: a plain string
 * (the receipt id) or `{receiptId}` (`{"files":[{"receiptId":"…"}]}`). Unknown
 * shapes are rejected instead of silently dropping the user's attachment.
 *
 * @param {unknown} value - `files` / `attachments` from the request body.
 * @returns {string[]} distinct receipt ids in request order.
 * @throws {HttpError} 400 `invalid_request`.
 */
function receiptIdsOf(value) {
  if (value === undefined || value === null) return []
  if (!Array.isArray(value)) throw new HttpError(400, 'invalid_request', 'files must be an array')
  if (value.length > MAX_FILES_PER_MESSAGE) {
    throw new HttpError(400, 'invalid_request', `at most ${MAX_FILES_PER_MESSAGE} files per message`)
  }
  const ids = []
  for (const item of value) {
    const receiptId = typeof item === 'string'
      ? item.trim()
      : isRecord(item) && typeof item.receiptId === 'string' ? item.receiptId.trim() : ''
    if (!RECEIPT_ID_PATTERN.test(receiptId)) {
      throw new HttpError(400, 'invalid_request', 'each file needs a valid receiptId')
    }
    if (!ids.includes(receiptId)) ids.push(receiptId)
  }
  return ids
}

/**
 * Write one binary preview body.
 *
 * `private, no-store` keeps the bytes out of proxies and device caches: the
 * image stays a preview of the PC file, not a copy of it.
 */
function writeBinary(res, status, buffer, contentType) {
  if (res.headersSent || res.writableEnded) return
  res.writeHead(status, {
    'content-type': contentType,
    'content-length': String(buffer.byteLength),
    'cache-control': 'private, no-store',
    'x-content-type-options': 'nosniff',
  })
  res.end(buffer)
}

/** Extract the selectable preset values from a `permissionPresets/catalog` value. */
function catalogPresetValues(catalog) {
  if (catalog === null || typeof catalog !== 'object' || !Array.isArray(catalog.options)) return []
  const values = []
  for (const option of catalog.options) {
    if (option === null || typeof option !== 'object') continue
    if (typeof option.value === 'string' && option.value !== '') values.push(option.value)
  }
  return values
}

/**
 * Join the answer text blocks of a DSH content array into plain text.
 *
 * `reasoning` / `thinking` blocks are NOT answer text and are returned by
 * {@link reasoningOfContent} instead, so clients can show the thinking chain
 * separately (collapsed) instead of mixing it into the reply.
 */
function textOfContent(content) {
  if (typeof content === 'string') return content.trim()
  if (!Array.isArray(content)) return ''
  const parts = []
  for (const block of content) {
    if (block === null || typeof block !== 'object') continue
    if (typeof block.text !== 'string') continue
    if (block.type === undefined || block.type === 'text') parts.push(block.text)
  }
  return parts.join('\n').trim()
}

/** Join the reasoning / thinking blocks of a DSH content array into plain text. */
function reasoningOfContent(content) {
  if (!Array.isArray(content)) return ''
  const parts = []
  for (const block of content) {
    if (block === null || typeof block !== 'object') continue
    if (typeof block.text !== 'string') continue
    if (block.type === 'reasoning' || block.type === 'thinking') parts.push(block.text)
  }
  return parts.join('\n').trim()
}

/** Attach a non-empty `reasoning` field to a `/v1` message. */
function withReasoning(message, content) {
  const reasoning = reasoningOfContent(content)
  if (reasoning !== '') message.reasoning = reasoning
  return message
}

/**
 * Attach the `images` array to a `/v1` message when the content has stored images.
 *
 * Each entry is the durable `ImageAttachmentRef` DSH keeps in the session record;
 * the client builds `<base>/v1/sessions/<id>/attachments/<attachmentId>?…` from it.
 */
function withImages(message, content) {
  const images = imagesOfContent(content)
  if (images.length > 0) message.images = images
  return message
}

/**
 * Attach the `files` array to a `/v1` message when the content has stored file
 * attachments (files the user attached to their prompt).
 *
 * Each entry is the durable `FileAttachmentRef` DSH keeps in the session
 * record; the client only needs the display name and byte count.
 */
function withFiles(message, content) {
  const files = filesOfContent(content)
  if (files.length > 0) message.files = files
  return message
}

/**
 * Map one DSH session event (`{type, seq, time, data}`) to a `/v1` message.
 * @returns {{seq:number, role:string, text:string, time:number, kind:string}|null}
 */
export function recordToMessage(record) {
  if (record === null || typeof record !== 'object') return null
  const event = record.type === 'event' && record.event !== null && typeof record.event === 'object'
    ? record.event
    : record
  const type = typeof event.type === 'string' ? event.type : ''
  const data = event.data !== null && typeof event.data === 'object' ? event.data : {}
  const seq = Number.isFinite(event.seq) ? Math.trunc(event.seq) : 0
  const time = Number.isFinite(event.time) ? Math.trunc(event.time) : 0
  switch (type) {
    case 'user/message':
      return withFiles(withImages({
        seq,
        role: 'user',
        text: textOfContent(data.content),
        time,
        kind: 'message',
        // 只透传来源标记（kind/summary/…），客户端用它区分真人消息与机器注入。
        ...(isRecord(data.source) ? { source: data.source } : {}),
      }, data.content), data.content)
    case 'assistant/message': {
      const content = data.message?.content ?? data.content
      return withImages(withReasoning({
        seq,
        role: 'assistant',
        text: textOfContent(content),
        time,
        kind: 'message',
      }, content), content)
    }
    case 'tool/result': {
      const message = data.message !== null && typeof data.message === 'object' ? data.message : {}
      const isError = message.isError === true || data.isError === true
      return withImages({
        seq,
        role: 'tool',
        text: textOfContent(message.content ?? data.content),
        time,
        kind: isError ? 'tool.error' : 'tool.result',
      }, message.content ?? data.content)
    }
    case 'tool/call':
      return {
        seq,
        role: 'tool',
        text: typeof data.name === 'string' ? data.name : 'tool',
        time,
        kind: 'tool.call',
      }
    default:
      return null
  }
}

/** Map one hub-cached message/event to a `/v1` message. */
function hubCachedMessage(item) {
  if (item === null || typeof item !== 'object') return null
  if (item.role === 'assistant') {
    const kind = typeof item.kind === 'string' && item.kind !== 'message.assistant' ? item.kind : 'message'
    const message = {
      seq: Number.isFinite(item.seq) ? Math.trunc(item.seq) : 0,
      role: 'assistant',
      text: typeof item.text === 'string' ? item.text : '',
      time: Number.isFinite(item.time) ? Math.trunc(item.time) : 0,
      kind,
    }
    if (typeof item.reasoning === 'string' && item.reasoning !== '') message.reasoning = item.reasoning
    return withImages(message, item.message?.content)
  }
  if (typeof item.role === 'string' && (item.role === 'user' || item.role === 'tool')) {
    const kind = typeof item.kind === 'string' && item.kind !== 'message.user' && item.kind !== 'message.assistant'
      ? item.kind
      : 'message'
    return withFiles(withImages({
      seq: Number.isFinite(item.seq) ? Math.trunc(item.seq) : 0,
      role: item.role,
      text: typeof item.text === 'string' ? item.text : '',
      time: Number.isFinite(item.time) ? Math.trunc(item.time) : 0,
      kind,
      ...(isRecord(item.message?.source) ? { source: item.message.source } : {}),
    }, item.message?.content), item.message?.content)
  }
  const kind = typeof item.kind === 'string' ? item.kind : ''
  const data = item.data !== null && typeof item.data === 'object' ? item.data : null
  if (data === null) return null
  if (kind === 'message.user') {
    const content = data.message?.content ?? data.content
    return withFiles(withImages({
      seq: Number.isFinite(item.seq) ? Math.trunc(item.seq) : 0,
      role: 'user',
      text: textOfContent(content),
      time: Number.isFinite(item.ts) ? Math.trunc(item.ts) : 0,
      kind: 'message',
      ...(isRecord(data.message?.source)
        ? { source: data.message.source }
        : isRecord(data.source) ? { source: data.source } : {}),
    }, content), content)
  }
  if (kind === 'message.assistant') {
    const content = data.message?.content ?? data.content
    return withImages(withReasoning({
      seq: Number.isFinite(item.seq) ? Math.trunc(item.seq) : 0,
      role: 'assistant',
      text: textOfContent(content),
      time: Number.isFinite(item.ts) ? Math.trunc(item.ts) : 0,
      kind: 'message',
    }, content), content)
  }
  return null
}

/**
 * Normalize the answer value for one pending intervention.
 * @throws {HttpError} 400 when the value does not fit the intervention kind.
 */
function normalizeAnswerValue(kind, value) {
  if (kind === 'question.request') {
    const answers = value !== null && typeof value === 'object' ? value.answers : undefined
    if (!Array.isArray(answers)) {
      throw new HttpError(400, 'invalid_request', 'question answers must be `{answers:[…]}`')
    }
    const sanitized = []
    for (const answer of answers) {
      if (answer === null || typeof answer !== 'object') continue
      const item = {}
      if (typeof answer.id === 'string' && answer.id !== '') item.id = answer.id
      else continue
      if (Array.isArray(answer.selected)) item.selected = answer.selected.map((entry) => String(entry))
      if (typeof answer.custom === 'string' && answer.custom !== '') item.custom = answer.custom
      sanitized.push(item)
    }
    return { answers: sanitized }
  }
  if (kind === 'approval.request') {
    if (typeof value !== 'string' || !APPROVAL_VALUES.has(value)) {
      throw new HttpError(400, 'invalid_request', 'approval value must be allowed-once/rejected/cancelled/unavailable')
    }
    return value
  }
  // Unknown kind: accept a primitive or plain object as-is.
  if (typeof value === 'string' || (value !== null && typeof value === 'object')) return value
  throw new HttpError(400, 'invalid_request', 'value must be a string or an object')
}

/**
 * ApiServer — the LAN/public HTTP(S) edges.
 */
export class ApiServer {
  /** Injected by index.js after the DSH client is created (may be null). */
  dsh = null

  /**
   * @param {object} deps
   * @param {import('@deepseek-ai/cordis').Context} [deps.ctx]
   * @param {Record<string, any>} deps.config
   * @param {{info?:Function,warn?:Function,error?:Function,debug?:Function}} [deps.logger]
   * @param {import('./hub.js').EventHub} deps.hub
   * @param {import('./auth.js').AuthService} deps.auth
   * @param {{key:string,cert:string,fingerprint:string}|null} [deps.tls]
   * @param {string} [deps.dir]
   * @param {(req:any,socket:any,head:Buffer)=>void} [deps.onUpgrade]
   */
  constructor({ ctx, config, logger, hub, auth, tls, dir, onUpgrade }) {
    this.ctx = ctx ?? null
    this.config = config ?? {}
    this.logger = logger ?? {}
    this.hub = hub
    this.auth = auth
    this.tls = tls ?? null
    this.dir = typeof dir === 'string' ? dir : ''
    this.onUpgrade = typeof onUpgrade === 'function' ? onUpgrade : null

    /** @type {import('node:http').Server|null} */
    this.lanServer = null
    /** @type {import('node:https').Server|null} */
    this.publicServer = null
    this.lanPort = 0
    this.publicPort = 0
    this.started = false
    this.closed = false
    this.startedAt = Date.now()
    this.lastError = ''
    /**
     * Raw TCP sockets that were upgraded to WebSocket.
     *
     * `http.Server#close()` waits for ALL sockets, and upgraded sockets are not
     * tracked by `closeAllConnections()`; without destroying them a plugin
     * reload (HMR / dispose) hangs forever in the "unloading" state. Keep our
     * own set so {@link close} can terminate them immediately.
     */
    this.upgradedSockets = new Set()
    /** Runtime-only config overrides written by the in-app panel. */
    this.overrides = { lanRequirePairing: null, mdns: null }
    /** Optional mDNS advertiser injected by index.js for status reporting. */
    this.mdns = null
  }

  /** Both listeners in `[lanServer, publicServer]` order (null when disabled). */
  get servers() {
    return [this.lanServer, this.publicServer]
  }

  /** Effective config (config file + panel overrides). */
  effectiveConfig() {
    const config = { ...this.config }
    if (this.overrides.lanRequirePairing !== null) config.lanRequirePairing = this.overrides.lanRequirePairing
    if (this.overrides.mdns !== null) config.mdns = this.overrides.mdns
    return config
  }

  /**
   * Open the configured listeners.
   * @returns {Promise<{lanPort:number, publicPort:number}>} actually bound ports (0 = disabled).
   */
  async start() {
    if (this.started) return { lanPort: this.lanPort, publicPort: this.publicPort }
    this.started = true
    try {
      this.auth?.startSweeper?.()
    } catch (error) {
      this.logger.warn?.(`auth sweeper failed to start: ${error?.message ?? error}`)
    }

    const httpPort = Number(this.config.httpPort) || 0
    if (httpPort > 0) {
      const server = createHttpServer((req, res) => this.#handle(req, res, { secure: false }))
      this.#attachUpgrade(server)
      const address = await this.#bind(server, httpPort)
      this.lanServer = server
      this.lanPort = address?.port ?? httpPort
    }

    const httpsPort = Number(this.config.httpsPort) || 0
    if (httpsPort > 0) {
      if (this.tls === null) {
        this.logger.warn?.('HTTPS port configured but no TLS material is available; public listener disabled')
      } else {
        const server = createHttpsServer(
          { key: this.tls.key, cert: this.tls.cert },
          (req, res) => this.#handle(req, res, { secure: true }),
        )
        this.#attachUpgrade(server)
        const address = await this.#bind(server, httpsPort)
        this.publicServer = server
        this.publicPort = address?.port ?? httpsPort
      }
    }

    return { lanPort: this.lanPort, publicPort: this.publicPort }
  }

  /** Close both listeners. Never throws. */
  async close() {
    if (this.closed) return
    this.closed = true
    try {
      this.auth?.stopSweeper?.()
    } catch {
      // ignore
    }
    const closeOne = (server) => new Promise((resolve) => {
      if (server === null) {
        resolve()
        return
      }
      try {
        server.closeAllConnections?.()
        server.closeIdleConnections?.()
      } catch {
        // ignore
      }
      try {
        server.close(() => resolve())
      } catch {
        resolve()
      }
      // `close()` 不会关闭已升级为 WebSocket 的原始 socket，不主动销毁会永久挂起。
      for (const socket of this.upgradedSockets) {
        try {
          socket.destroy()
        } catch {
          // ignore
        }
      }
      this.upgradedSockets.clear()
    })
    await Promise.all([closeOne(this.lanServer), closeOne(this.publicServer)])
    this.lanServer = null
    this.publicServer = null
  }

  // ------------------------------------------------------------------ routing

  #url(req) {
    try {
      return new URL(String(req?.url ?? '/'), 'http://localhost')
    } catch {
      return new URL('http://localhost/')
    }
  }

  #attachUpgrade(server) {
    server.on('upgrade', (req, socket, head) => {
      try {
        if (this.closed) {
          socket.destroy()
          return
        }
        if (this.onUpgrade === null) {
          socket.destroy()
          return
        }
        this.upgradedSockets.add(socket)
        socket.on('close', () => this.upgradedSockets.delete(socket))
        this.onUpgrade(req, socket, head)
      } catch (error) {
        this.logger.warn?.(`upgrade dispatch failed: ${error?.message ?? error}`)
        try {
          socket.destroy()
        } catch {
          // ignore
        }
      }
    })
  }

  /** Bind one server, preferring dual-stack for wildcard hosts. */
  #bind(server, port) {
    const host = typeof this.config.host === 'string' && this.config.host !== '' ? this.config.host : '0.0.0.0'
    const wildcard = host === '0.0.0.0' || host === '::' || host === '*'
    if (!wildcard) return this.#listen(server, port, host)
    return this.#listen(server, port, '::', false).catch((error) => {
      this.logger.debug?.(`dual-stack bind unavailable (${error?.code ?? error?.message}); retrying IPv4`)
      return this.#listen(server, port, '0.0.0.0')
    })
  }

  #listen(server, port, host, ipv6Only) {
    return new Promise((resolve, reject) => {
      const onError = (error) => {
        cleanup()
        reject(error)
      }
      const onListening = () => {
        cleanup()
        let address = null
        try {
          address = server.address()
        } catch {
          address = null
        }
        resolve(address !== null && typeof address === 'object' ? address : { port })
      }
      const cleanup = () => {
        server.removeListener('error', onError)
        server.removeListener('listening', onListening)
      }
      server.once('error', onError)
      server.once('listening', onListening)
      try {
        const options = { port, host }
        if (ipv6Only !== undefined) options.ipv6Only = ipv6Only
        server.listen(options)
      } catch (error) {
        cleanup()
        reject(error)
      }
    })
  }

  /** Top-level request handler: CORS → guard → route → error envelope. */
  #handle(req, res, context) {
    try {
      const url = this.#url(req)
      const pathname = url.pathname
      if (req.method === 'OPTIONS') {
        this.#preflight(res, pathname)
        return
      }
      const verdict = this.auth?.guard?.({
        remoteAddress: req?.socket?.remoteAddress,
        localPort: req?.socket?.localPort,
        headers: req?.headers ?? {},
        path: pathname,
      }) ?? { ok: false, status: 503, code: 'dsh_unavailable', message: 'gateway is not ready' }
      if (verdict.ok !== true) {
        this.#writeError(res, verdict.status ?? 403, verdict.code ?? 'forbidden_source', verdict.message)
        return
      }
      if (verdict.device !== null && verdict.device !== undefined) {
        try {
          this.auth?.touch?.(verdict.device.deviceId, { remoteAddress: req?.socket?.remoteAddress })
        } catch {
          // activity bookkeeping must never fail a request
        }
      }
      let matched = null
      let pathMatched = null
      for (const [method, pattern, name] of ROUTES) {
        const found = pattern.exec(pathname)
        if (found === null) continue
        if (method === req.method) {
          matched = { method, name, params: found.slice(1) }
          break
        }
        if (pathMatched === null) pathMatched = { method, name }
      }
      if (matched === null) {
        if (pathMatched !== null) {
          writeJson(res, 405, {
            ok: false,
            error: { code: 'invalid_request', message: `method ${req.method} is not allowed here` },
          }, { allow: pathMatched.method })
        } else {
          this.#writeError(res, 404, 'not_found', 'no such endpoint')
        }
        return
      }
      const handler = this[`h${matched.name.slice(0, 1).toUpperCase()}${matched.name.slice(1)}`]
      if (typeof handler !== 'function') {
        this.#writeError(res, 500, 'internal_error', 'internal error')
        return
      }
      Promise.resolve(handler.call(this, { req, res, url, params: matched.params, verdict, ...context }))
        .catch((error) => this.#handleError(req, res, error))
    } catch (error) {
      this.#handleError(req, res, error)
    }
  }

  #preflight(res, pathname) {
    if (!pathname.startsWith('/v1/') && pathname !== '/v1') {
      this.#writeError(res, 404, 'not_found', 'no such endpoint')
      return
    }
    if (res.headersSent || res.destroyed) return
    res.writeHead(204, {
      'access-control-allow-origin': '*',
      'access-control-allow-methods': 'GET,POST,PATCH,DELETE,OPTIONS',
      'access-control-allow-headers': 'authorization, content-type',
      'access-control-max-age': '600',
      'cache-control': 'no-store',
    })
    res.end()
  }

  /** Map errors into the shared `{ok:false,error:{…}}` envelope. */
  #handleError(req, res, error) {
    if (res.headersSent || res.writableEnded) {
      try {
        res.destroy()
      } catch {
        // ignore
      }
      return
    }
    if (error instanceof HttpError) {
      this.#writeError(res, error.status, error.code, error.message)
      return
    }
    const mapped = this.#mapDshError(error)
    if (mapped !== null) {
      this.#writeError(res, mapped.status, mapped.code, mapped.message)
      return
    }
    this.logger.error?.(`${req?.method ?? '?'} ${req?.url ?? '?'} failed: ${error?.name ?? 'Error'}: ${error?.message ?? error}`)
    this.lastError = 'internal_error'
    // Never leak stacks or DSH internals to a remote client.
    this.#writeError(res, 500, 'internal_error', 'internal error')
  }

  /** Map a `DshCallError` (or transport failure) into an HTTP error. */
  #mapDshError(error) {
    if (error === null || typeof error !== 'object') return null
    const isDshError = error.name === 'DshCallError' || (typeof error.method === 'string' && typeof error.code === 'string')
    if (!isDshError) return null
    const code = String(error.code ?? '')
    if (this.dsh === null || this.dsh?.connected === false || code === 'not_connected' || code === 'unavailable') {
      return { status: 503, code: 'dsh_unavailable', message: 'the DSH link is unavailable' }
    }
    if (code === 'attachment/not-found' || code === 'not_found' || code === 'session_not_found' ||
      code === 'ATTACHMENT_NOT_FOUND') {
      return { status: 404, code: 'not_found', message: 'not found' }
    }
    // DSH 把附件读取失败统一成 `session/attachment-invalid`，真正的原因在
    // `details.reason`（`ATTACHMENT_NOT_FOUND` / `IMAGE_TOO_LARGE` / …）。
    if (code === 'session/attachment-invalid') {
      const reason = String(error.details?.details?.reason ?? error.details?.reason ?? '')
      if (reason === 'ATTACHMENT_NOT_FOUND') return { status: 404, code: 'not_found', message: 'not found' }
      if (reason === 'IMAGE_TOO_LARGE' || reason === 'IMAGES_TOO_LARGE' || reason === 'IMAGE_TOO_MANY_PIXELS') {
        return { status: 413, code: 'too_large', message: 'the image is too large to preview' }
      }
      return { status: 400, code: 'invalid_request', message: 'the attachment reference is not valid' }
    }
    if (code === 'attachment/type-mismatch' || code === 'IMAGE_TYPE_MISMATCH' ||
      code === 'INVALID_ATTACHMENT_REF') {
      return { status: 400, code: 'invalid_request', message: 'the attachment reference is not valid' }
    }
    if (code === 'IMAGE_TOO_LARGE' || code === 'IMAGES_TOO_LARGE' || code === 'IMAGE_TOO_MANY_PIXELS') {
      return { status: 413, code: 'too_large', message: 'the image is too large to preview' }
    }
    if (code === 'forbidden') return { status: 403, code: 'forbidden', message: 'the file is not readable' }
    if (code === 'too_large') return { status: 413, code: 'too_large', message: 'the file is too large to preview' }
    if (code === 'invalid_request' || code === 'invalid_argument' || code === 'bad_request') {
      return { status: 400, code: 'invalid_request', message: 'the DSH rejected the request as invalid' }
    }
    if (code === 'already_settled' || code === 'settled') {
      return { status: 409, code: 'already_resolved', message: 'the intervention was already handled' }
    }
    if (code === 'unauthenticated' || code === 'forbidden') {
      return { status: 503, code: 'dsh_unavailable', message: 'the gateway could not authenticate to DSH' }
    }
    return { status: 502, code: 'dsh_error', message: 'the DSH rejected the request' }
  }

  #writeError(res, status, code, message) {
    writeJson(res, status, { ok: false, error: { code, message: message ?? code } })
  }

  /** Require a started, connected DSH client. */
  #dsh() {
    if (this.dsh === null || this.dsh === undefined) {
      throw new HttpError(503, 'dsh_unavailable', 'the DSH link is not ready yet')
    }
    if (this.dsh.connected === false) {
      throw new HttpError(503, 'dsh_unavailable', 'the DSH link is currently disconnected')
    }
    return this.dsh
  }

  /** Call one hub method without letting a hub bug break the HTTP edge. */
  #hubCall(name, args = []) {
    try {
      const fn = this.hub?.[name]
      if (typeof fn !== 'function') return undefined
      return fn.apply(this.hub, args)
    } catch (error) {
      this.logger.debug?.(`hub.${name} failed: ${error?.message ?? error}`)
      return undefined
    }
  }

  /** Advertised public base URLs (config override first; public addresses only). */
  #publicUrls() {
    const urls = []
    const configured = normalizeOrigin(this.config.publicUrl ?? '')
    if (configured !== '') urls.push(configured)
    if (this.publicPort > 0) {
      for (const address of enumerateAddresses().public) {
        const url = `https://${formatHost(address)}:${this.publicPort}`
        if (!urls.includes(url)) urls.push(url)
      }
    }
    return urls
  }

  /** Advertised LAN base URLs: plain HTTP only (no certificate), private addresses only. */
  #lanUrls() {
    if (this.lanPort <= 0) return []
    const urls = []
    for (const address of enumerateAddresses().lan) {
      const url = `http://${formatHost(address)}:${this.lanPort}`
      if (!urls.includes(url)) urls.push(url)
    }
    return urls
  }

  /**
   * URLs embedded in the pairing QR: LAN plain-HTTP first (same-network phones
   * need no certificate and no token), public HTTPS as the fallback.
   */
  #pairingUrls() {
    return [...this.#lanUrls(), ...this.#publicUrls()]
  }

  #fingerprint() {
    return typeof this.tls?.fingerprint === 'string' ? this.tls.fingerprint : ''
  }

  // -------------------------------------------------------------- /v1 routes

  hHealth({ res, verdict }) {
    const config = this.effectiveConfig()
    const body = {
      ok: true,
      name: config.name ?? 'dsh-gateway',
      v: PROTOCOL_VERSION,
      dsh: DSH_VERSION,
      lan: this.lanPort > 0,
      paired: verdict?.device !== null && verdict?.device !== undefined,
      dshConnected: Boolean(this.dsh?.connected),
      public: this.publicPort > 0,
      uptimeMs: Date.now() - this.startedAt,
      ports: { lan: this.lanPort, public: this.publicPort },
    }
    if (typeof config.publicUrl === 'string' && config.publicUrl !== '') {
      body.publicUrl = normalizeOrigin(config.publicUrl)
    }
    writeJson(res, 200, body)
  }

  async hPair({ req, res }) {
    if (this.auth === null || typeof this.auth?.pair !== 'function') {
      throw new HttpError(503, 'dsh_unavailable', 'pairing is unavailable')
    }
    const body = await readJsonBody(req, BODY_LIMIT)
    const result = await this.auth.pair({
      code: body.code,
      deviceName: body.deviceName ?? body.name,
      deviceId: body.deviceId,
      remoteAddress: req?.socket?.remoteAddress,
    })
    writeJson(res, 200, {
      ok: true,
      deviceToken: result.deviceToken,
      deviceId: result.deviceId,
      expiresAt: result.expiresAt ?? null,
    })
  }

  hWorkspaces({ res }) {
    let workspaces = this.#hubCall('getWorkspaces') ?? []
    if (!Array.isArray(workspaces)) workspaces = []
    writeJson(res, 200, { ok: true, workspaces })
  }

  async hSessionsList({ res, url }) {
    const workspaceId = url.searchParams.get('workspaceId') ?? ''
    const includeSubagents = url.searchParams.get('includeSubagents') === '1'
    let sessions = null
    if (this.dsh !== null && this.dsh?.connected !== false) {
      try {
        const value = await this.dsh.call('session/list', { _request: {} })
        const items = Array.isArray(value?.items) ? value.items : Array.isArray(value) ? value : []
        sessions = items
        this.#hubCall('applySessions', [items])
      } catch (error) {
        this.logger.warn?.(`session/list failed: ${error?.code ?? error?.message ?? error}`)
      }
    }
    if (sessions === null) {
      sessions = this.#hubCall('getSessions') ?? []
      if (!Array.isArray(sessions)) sessions = []
    }
    if (workspaceId !== '') {
      // DSH `SessionSummary` has no `workspaceId`; membership lives in the
      // `workspace/follow` cache as `workspace.sessionIds`.
      let memberIds = null
      try {
        const workspaces = this.#hubCall('getWorkspaces') ?? []
        const match = Array.isArray(workspaces)
          ? workspaces.find((workspace) => workspace !== null && typeof workspace === 'object'
            && (workspace.workspaceId ?? workspace.id) === workspaceId)
          : undefined
        if (match !== undefined) {
          memberIds = new Set(Array.isArray(match.sessionIds) ? match.sessionIds : [])
        }
      } catch (error) {
        this.logger.debug?.(`workspace lookup failed: ${error?.message ?? error}`)
      }
      // Unknown workspace → empty list (APK contract, no 404).
      sessions = memberIds === null
        ? []
        : sessions.filter((session) => session !== null && typeof session === 'object' && memberIds.has(session.sessionId))
    }
    // Subagent sessions cannot be followed through `/v1/stream`
    // (`session/agent-busy`), so they are hidden unless explicitly requested.
    if (!includeSubagents) {
      sessions = sessions.filter((session) => !(session !== null && typeof session === 'object'
        && typeof session.parentSessionId === 'string' && session.parentSessionId !== ''))
    }
    writeJson(res, 200, { ok: true, sessions })
  }

  async hSessionsCreate({ req, res }) {
    const body = await readJsonBody(req, BODY_LIMIT)
    const args = {}
    if (typeof body.workspaceId === 'string' && body.workspaceId.trim() !== '') args.workspaceId = body.workspaceId.trim()
    if (typeof body.cwd === 'string' && body.cwd.trim() !== '') args.cwd = body.cwd.trim()
    if (args.workspaceId !== undefined && args.cwd !== undefined) {
      throw new HttpError(400, 'invalid_request', 'workspaceId and cwd are mutually exclusive')
    }
    if (typeof body.sessionId === 'string' && body.sessionId.trim() !== '') args.sessionId = body.sessionId.trim()
    if (typeof body.agentPreset === 'string' && body.agentPreset.trim() !== '') args.agentPreset = body.agentPreset.trim()
    const value = await this.#dsh().call('session/create', { request: args })
    const sessionId = typeof value?.sessionId === 'string' && value.sessionId !== ''
      ? value.sessionId
      : typeof args.sessionId === 'string'
        ? args.sessionId
        : ''
    if (sessionId === '') throw new HttpError(502, 'dsh_error', 'DSH did not return a sessionId')
    const response = { ok: true, sessionId }
    if (typeof value?.agentPreset === 'string') response.agentPreset = value.agentPreset
    writeJson(res, 200, response)
  }

  async hSessionState({ res, params }) {
    const sessionId = decodeSegment(params[0])
    const value = await this.#dsh().call('session/state', { request: { sessionId } })
    const state = value !== null && typeof value === 'object' ? value : {}
    // DSH models `pendingInput` as an array ('approval'|'question'|'other');
    // the APK contract exposes a boolean summary plus the raw state.
    const pendingInput = Array.isArray(state.pendingInput)
      ? state.pendingInput.length > 0
      : Boolean(state.pendingInput)
    writeJson(res, 200, {
      ok: true,
      sessionId,
      running: Boolean(state.running),
      pendingInput,
      state,
    })
  }

  async hMessagesList({ res, url, params }) {
    const sessionId = decodeSegment(params[0])
    const beforeSeq = intParam(url.searchParams.get('beforeSeq'), null, 1)
    const limit = intParam(url.searchParams.get('limit'), DEFAULT_MESSAGE_LIMIT, 1, MAX_MESSAGE_LIMIT)
    const includeRaw = url.searchParams.get('raw') === '1'
    let page
    let history = []
    try {
      const args = { address: { kind: 'session', sessionId }, maxMessages: limit }
      if (beforeSeq !== null) args.beforeSeq = beforeSeq
      page = await this.#dsh().call('session/events', { request: args })
      const records = Array.isArray(page?.records) ? page.records : []
      for (const record of records) {
        const message = recordToMessage(record)
        if (message === null) continue
        history.push(includeRaw ? { ...message, raw: record?.event ?? record } : message)
      }
    } catch (error) {
      if (error instanceof HttpError) throw error
      // Cold history is also cached in the hub for the sessions being followed;
      // serving that cache keeps a phone useful while DSH reconnects.
      const cached = this.#messagesFromCache(sessionId, beforeSeq, limit)
      if (cached.messages.length > 0) {
        this.logger.warn?.(`session/events failed (${error?.code ?? error?.message ?? error}); serving hub cache`)
        writeJson(res, 200, { ok: true, messages: cached.messages, cursor: cached.cursor, hasMore: cached.hasMore })
        return
      }
      throw error
    }
    // Merge the live hub cache (messages may already exist there before the
    // session log is flushed); history wins on duplicate seq.
    const merged = new Map()
    for (const message of history) merged.set(message.seq, message)
    const cached = this.#messagesFromCache(sessionId, beforeSeq, MAX_MESSAGE_LIMIT)
    for (const message of cached.messages) {
      if (!merged.has(message.seq)) merged.set(message.seq, message)
    }
    const all = [...merged.values()].sort((left, right) => left.seq - right.seq)
    const hasMore = Boolean(page?.hasMore) || all.length > limit
    const messages = all.length > limit ? all.slice(all.length - limit) : all
    const lastSeq = messages.length > 0 ? messages[messages.length - 1].seq : 0
    const cursor = Number.isFinite(page?.cursor) ? Math.trunc(page.cursor) : lastSeq
    writeJson(res, 200, { ok: true, messages, cursor, hasMore })
  }

  /**
   * `GET /v1/sessions/{id}/attachments/{attachmentId}?mediaType=…&width=…&height=…&bytes=…&name=…`
   *
   * Streams one stored conversation image back to the device on demand, without
   * copying it anywhere: the DSH `session/attachment` call needs the complete
   * `ImageAttachmentRef` (id + media type + byte length + dimensions), which the
   * client already got in `message.images`.
   */
  async hAttachment({ res, url, params }) {
    const sessionId = decodeSegment(params[0])
    const attachmentId = decodeSegment(params[1])
    if (!ATTACHMENT_ID_PATTERN.test(attachmentId)) {
      throw new HttpError(400, 'invalid_request', 'malformed attachment id')
    }
    const mediaType = String(url.searchParams.get('mediaType') ?? '')
    if (!IMAGE_MEDIA_TYPE_PATTERN.test(mediaType)) {
      throw new HttpError(400, 'invalid_request', 'mediaType is required')
    }
    const width = intParam(url.searchParams.get('width'), null, 1)
    const height = intParam(url.searchParams.get('height'), null, 1)
    const bytes = intParam(url.searchParams.get('bytes'), null, 1)
    if (width === null || height === null || bytes === null) {
      throw new HttpError(400, 'invalid_request', 'width, height and bytes are required')
    }
    const attachment = { attachmentId, mediaType, width, height, bytes }
    const name = cleanName(url.searchParams.get('name') ?? '', '')
    if (name !== '') attachment.name = name
    const value = await this.#dsh().call('session/attachment', { request: { sessionId, attachment } })
    const data = typeof value?.data === 'string' ? value.data : ''
    if (data === '') throw new HttpError(502, 'dsh_error', 'the DSH returned no image bytes')
    const buffer = Buffer.from(data, 'base64')
    if (buffer.byteLength === 0) throw new HttpError(502, 'dsh_error', 'the DSH returned an empty image')
    if (buffer.byteLength > MAX_PREVIEW_BYTES) {
      throw new HttpError(413, 'too_large', 'the image is too large to preview')
    }
    writeBinary(res, 200, buffer, mediaType)
  }

  /**
   * `GET /v1/sessions/{id}/image?path=<absolute file path, or cwd-relative>`
   *
   * Previews one image file that lives on the PC (the path a Markdown image
   * points at). Only image extensions are served, only through the host's
   * authenticated file route, and nothing is stored on the gateway or device.
   */
  async hSessionImage({ res, url, params }) {
    const sessionId = decodeSegment(params[0])
    const raw = String(url.searchParams.get('path') ?? '').trim()
    if (raw === '') throw new HttpError(400, 'invalid_request', 'path is required')
    if (raw.includes('\0')) throw new HttpError(400, 'invalid_request', 'malformed path')
    const cwd = this.#sessionCwd(sessionId)
    if (!isAbsolute(raw) && cwd === '') {
      throw new HttpError(400, 'invalid_request', 'a relative path needs a known session directory')
    }
    const absolute = isAbsolute(raw) ? raw : resolve(cwd, raw)
    const contentType = PREVIEW_IMAGE_TYPES.get(extname(absolute).toLowerCase())
    if (contentType === undefined) {
      throw new HttpError(415, 'unsupported_type', 'only image files can be previewed')
    }
    const file = await this.#dsh().readFile(absolute, { maxBytes: MAX_PREVIEW_BYTES })
    writeBinary(res, 200, file.bytes, contentType)
  }

  /**
   * `POST /v1/sessions/{id}/messages`
   *
   * Body: `{text?, mode?, files?: [receiptId | {receiptId}]}`. `files` are the
   * receipts minted by the upload route **for this same session**; the DSH
   * prompt cites them as `{type:'file', receiptId}` content parts and the host
   * resolves them back into the stored attachments (uploaded images become
   * image parts). Either `text` or at least one file is required.
   */
  async hMessagesSend({ req, res, params }) {
    const sessionId = decodeSegment(params[0])
    const body = await readJsonBody(req, BODY_LIMIT)
    const text = typeof body.text === 'string' ? body.text : ''
    const receiptIds = receiptIdsOf(body.files ?? body.attachments)
    if (text.trim() === '' && receiptIds.length === 0) {
      throw new HttpError(400, 'invalid_request', 'text or files are required')
    }
    const mode = body.mode === 'steer' ? 'steer' : 'queue'
    // Strict DSH codec: only whitelisted fields; text blocks must be non-empty.
    const content = []
    if (text.trim() !== '') content.push({ type: 'text', text })
    for (const receiptId of receiptIds) content.push({ type: 'file', receiptId })
    const args = { requestId: uuid(), sessionId, mode, content }
    const value = await this.#dsh().call('session/prompt', { request: args })
    writeJson(res, 200, { ok: true, sessionId, accepted: value?.accepted !== false })
  }

  /**
   * `POST /v1/sessions/{id}/files?name=<filename>[&mediaType=image/png]`
   *
   * Raw bytes (`content-type: application/octet-stream`) are handed straight to
   * the host's staged-upload route; the gateway stores nothing itself. The
   * returned receipt can be cited once by `POST /v1/sessions/{id}/messages`.
   */
  async hFileUpload({ req, res, url, params }) {
    const sessionId = decodeSegment(params[0])
    const contentType = String(req.headers?.['content-type'] ?? '')
      .split(';', 1)[0].trim().toLowerCase()
    if (contentType !== 'application/octet-stream') {
      throw new HttpError(415, 'unsupported_type', 'content-type must be application/octet-stream')
    }
    const declared = intParam(req.headers?.['content-length'], 0, 0)
    if (declared > MAX_UPLOAD_BYTES) {
      throw new HttpError(413, 'payload_too_large', 'the file is too large for the gateway')
    }
    const name = cleanName(url.searchParams.get('name') ?? '', '')
    const mediaType = String(url.searchParams.get('mediaType') ?? '').trim().toLowerCase()
    if (mediaType !== '' && !IMAGE_MEDIA_TYPE_PATTERN.test(mediaType)) {
      throw new HttpError(400, 'invalid_request', 'malformed mediaType')
    }
    const bytes = await readRawBody(req, MAX_UPLOAD_BYTES)
    if (bytes.byteLength === 0) {
      throw new HttpError(400, 'invalid_request', 'the uploaded file is empty')
    }
    const value = await this.#dsh().uploadFile(sessionId, { bytes, name, mediaType })
    writeJson(res, 200, {
      ok: true,
      sessionId,
      receiptId: value?.receiptId ?? '',
      file: value?.file ?? null,
      image: value?.image ?? null,
    })
  }

  async hSessionInterrupt({ res, params }) {
    const sessionId = decodeSegment(params[0])
    await this.#dsh().call('session/cancel', { request: { sessionId } })
    writeJson(res, 200, { ok: true })
  }

  async hSessionModel({ req, res, params }) {
    const sessionId = decodeSegment(params[0])
    const body = await readJsonBody(req, BODY_LIMIT)
    if (typeof body.provider !== 'string' || body.provider.trim() === '') {
      throw new HttpError(400, 'invalid_request', 'provider is required')
    }
    if (typeof body.model !== 'string' || body.model.trim() === '') {
      throw new HttpError(400, 'invalid_request', 'model is required')
    }
    const args = { sessionId, provider: body.provider.trim(), model: body.model.trim() }
    if (typeof body.reasoningEffort === 'string' && body.reasoningEffort.trim() !== '') {
      args.reasoningEffort = body.reasoningEffort.trim()
    }
    await this.#dsh().call('session/selectModel', { request: args })
    writeJson(res, 200, { ok: true })
  }

  async hModels({ res }) {
    const value = await this.#dsh().call('session/modelCatalog', {})
    writeJson(res, 200, { ok: true, catalog: value ?? null })
  }

  async hPermissions({ res }) {
    const catalog = await this.#permissionCatalog()
    writeJson(res, 200, { ok: true, catalog })
  }

  /**
   * Read `permissionPresets/catalog`.
   *
   * The catalog is the only source of truth for the selectable presets (there
   * is no cache to fall back to), so any DSH failure answers
   * `502 dsh_unavailable` and the client simply retries once the link is back.
   */
  async #permissionCatalog() {
    try {
      const value = await this.#dsh().call('permissionPresets/catalog', {})
      return value !== null && typeof value === 'object' ? value : null
    } catch (error) {
      this.logger.warn?.(`permissionPresets/catalog failed: ${error?.code ?? error?.message ?? error}`)
      throw new HttpError(502, 'dsh_unavailable', 'the DSH permission catalog is unavailable')
    }
  }

  /**
   * Switch one session's permission preset.
   *
   * The preset is validated against the live catalog *before* the slash
   * command runs, so a typo never reaches DSH's command dispatcher. A
   * `result.kind === 'error'` answer is a rejection of the change itself
   * (400 `permission_rejected`), not a transport failure.
   */
  async hSessionPermission({ req, res, params }) {
    const sessionId = decodeSegment(params[0])
    const body = await readJsonBody(req, BODY_LIMIT)
    const preset = body.preset
    if (typeof preset !== 'string' || !PRESET_VALUE_PATTERN.test(preset)) {
      throw new HttpError(400, 'invalid_request', 'preset is required and must match [a-z0-9-]+')
    }
    const available = catalogPresetValues(await this.#permissionCatalog())
    if (!available.includes(preset)) {
      throw new HttpError(400, 'invalid_request', available.length === 0
        ? `unknown preset ${JSON.stringify(preset)} (no presets are available)`
        : `unknown preset ${JSON.stringify(preset)} (available: ${available.join(', ')})`)
    }
    // Strict DSH codec: flat wire args, `submittedAttachments` must be present.
    let value
    try {
      value = await this.#dsh().call('commands/execute', {
        agentId: sessionId,
        line: `/permission ${preset}`,
        submittedAttachments: [],
      })
    } catch (error) {
      this.logger.warn?.(`commands/execute failed: ${error?.code ?? error?.message ?? error}`)
      throw new HttpError(502, 'dsh_unavailable', 'DSH could not run the permission command')
    }
    const result = value !== null && typeof value === 'object'
      && value.result !== null && typeof value.result === 'object'
      ? value.result
      : {}
    const text = typeof result.text === 'string' ? result.text : ''
    if (result.kind === 'error') {
      throw new HttpError(400, 'permission_rejected', text !== '' ? text : 'DSH rejected the permission change')
    }
    writeJson(res, 200, {
      ok: true,
      sessionId,
      preset,
      result: { kind: typeof result.kind === 'string' ? result.kind : 'success', text },
    })
  }

  hInterventions({ res, url }) {
    const status = url.searchParams.get('status') ?? 'pending'
    let interventions = this.#hubCall('listInterventions', [status]) ?? []
    if (!Array.isArray(interventions)) interventions = []
    writeJson(res, 200, {
      ok: true,
      interventions: interventions.map((entry) => this.#publicIntervention(entry)).filter(Boolean),
    })
  }

  async hInterventionAnswer({ req, res, params }) {
    const eventId = decodeSegment(params[0])
    // Resolve the pending intervention first: an unknown/settled event id is a
    // 404/409 whatever the body looks like.
    const pending = this.#hubCall('getIntervention', [eventId])
    if (pending === null || pending === undefined) {
      throw new HttpError(404, 'not_found', 'intervention is unknown or already settled')
    }
    if (typeof pending === 'object' && pending.status !== undefined && pending.status !== 'pending') {
      throw new HttpError(409, 'already_resolved', 'the intervention was handled on another device')
    }
    const body = await readJsonBody(req, BODY_LIMIT)
    const rawValue = body.value !== undefined ? body.value : body.outcome
    if (rawValue === undefined) throw new HttpError(400, 'invalid_request', 'value is required')
    const kind = typeof pending?.kind === 'string' ? pending.kind : ''
    const value = normalizeAnswerValue(kind, rawValue)
    const dsh = this.#dsh()
    const clientId = typeof dsh.eventsClientId === 'string' ? dsh.eventsClientId : ''
    if (clientId === '') throw new HttpError(503, 'dsh_unavailable', 'the DSH event client is not ready')
    const outcome = { kind: 'result', value }
    try {
      if (typeof dsh.answerIntervention === 'function') {
        await dsh.answerIntervention({ clientId, eventId, outcome })
      } else {
        await dsh.call('$events/result', { clientId, eventId, outcome })
      }
    } catch (error) {
      const mapped = this.#mapDshError(error)
      if (mapped?.code === 'already_resolved') {
        this.#hubCall('removeIntervention', [eventId, value, 'other'])
        throw new HttpError(409, 'already_resolved', 'the intervention was handled on another device')
      }
      throw error
    }
    this.#hubCall('removeIntervention', [eventId, value, 'this'])
    writeJson(res, 200, { ok: true })
  }

  hEvents({ res, url }) {
    const sinceSeq = intParam(url.searchParams.get('sinceSeq'), 0, 0) ?? 0
    const topicsParam = url.searchParams.get('topics')
    const topics = typeof topicsParam === 'string' && topicsParam.trim() !== ''
      ? topicsParam.split(',').map((topic) => topic.trim()).filter((topic) => topic !== '')
      : ['global']
    const result = this.#hubCall('eventsSince', [sinceSeq, topics]) ?? { events: [], gap: false }
    const events = Array.isArray(result?.events) ? result.events : []
    writeJson(res, 200, {
      ok: true,
      seq: typeof this.hub?.seq === 'number' ? this.hub.seq : 0,
      gap: Boolean(result?.gap),
      events,
    })
  }

  /** Work directory of one known session ('' when the hub has no summary yet). */
  #sessionCwd(sessionId) {
    const sessions = this.#hubCall('getSessions') ?? []
    if (!Array.isArray(sessions)) return ''
    for (const summary of sessions) {
      if (summary === null || typeof summary !== 'object' || summary.sessionId !== sessionId) continue
      if (typeof summary.cwd === 'string' && summary.cwd !== '') return summary.cwd
      // `session/control` baselines carry the directory in the projection values.
      const values = summary.projections?.values
      if (values !== null && typeof values === 'object') {
        if (typeof values.workingDirectory === 'string' && values.workingDirectory !== '') return values.workingDirectory
        if (typeof values.cwd === 'string' && values.cwd !== '') return values.cwd
      }
    }
    return ''
  }

  /** Cached fallback for the messages route. */
  #messagesFromCache(sessionId, beforeSeq, limit) {
    let cached = this.#hubCall('getMessages', [sessionId]) ?? []
    if (!Array.isArray(cached)) cached = []
    const messages = []
    for (const item of cached) {
      const message = hubCachedMessage(item)
      if (message === null) continue
      if (beforeSeq !== null && message.seq >= beforeSeq) continue
      messages.push(message)
    }
    messages.sort((left, right) => left.seq - right.seq)
    const hasMore = messages.length > limit
    const page = hasMore ? messages.slice(messages.length - limit) : messages
    const cursor = page.length > 0 ? page[page.length - 1].seq : beforeSeq !== null ? beforeSeq : 0
    return { messages: page, cursor, hasMore }
  }

  /** Strip internal hub bookkeeping from one intervention. */
  #publicIntervention(entry) {
    if (entry === null || typeof entry !== 'object') return null
    const eventId = typeof entry.eventId === 'string' ? entry.eventId : ''
    if (eventId === '') return null
    return {
      eventId,
      sessionId: typeof entry.sessionId === 'string' ? entry.sessionId : '',
      kind: typeof entry.kind === 'string' ? entry.kind : 'approval.request',
      createdAt: Number.isFinite(entry.createdAt) ? entry.createdAt : 0,
      data: entry.data ?? null,
    }
  }

  /** Build the panel snapshot consumed by the in-app configuration page. */
  async panelState() {
    const config = this.effectiveConfig()
    const publicUrls = this.#publicUrls()
    const lanUrls = this.#lanUrls()
    const fingerprint = this.#fingerprint()
    return buildState({
      name: config.name ?? 'dsh-gateway',
      version: GATEWAY_VERSION,
      fingerprint,
      fingerprintPretty: formatFingerprint(fingerprint),
      ports: { lan: this.lanPort, public: this.publicPort },
      mdns: {
        enabled: Boolean(config.mdns),
        active: Boolean(config.mdns) && this.lanPort > 0 && (this.mdns === null || this.mdns?.advertising !== false),
      },
      dsh: {
        connected: Boolean(this.dsh?.connected),
        clientId: typeof this.dsh?.eventsClientId === 'string' ? this.dsh.eventsClientId : null,
      },
      devices: this.auth?.devices?.() ?? [],
      pairing: this.auth?.currentPairing?.() ?? null,
      config,
      publicUrls,
      lanUrls,
      uptimeMs: Date.now() - this.startedAt,
      lastError: this.lastError,
    })
  }

  /** The current pairing code (panel only), rendered for the QR view. */
  async panelPairing() {
    const pairing = this.auth?.currentPairing?.() ?? null
    const view = pairing === null
      ? null
      : await buildPairingView({ pairing, fingerprint: this.#fingerprint(), name: this.effectiveConfig().name })
    return view
  }

  /** Mint a fresh one-shot pairing code (panel only). */
  async panelPairingCreate() {
    if (this.auth === null || typeof this.auth?.createPairingCode !== 'function') {
      throw new HttpError(503, 'dsh_unavailable', 'pairing is unavailable')
    }
    const urls = this.#pairingUrls()
    const fingerprint = this.#fingerprint()
    const pairing = this.auth.createPairingCode(urls, { fingerprint })
    const view = await buildPairingView({ pairing, fingerprint, name: this.effectiveConfig().name })
    this.logger.info?.(`pairing code created (ttl=${Math.round((pairing.expiresAt - Date.now()) / 1000)}s, urls=${urls.length})`)
    return view
  }

  /** Revoke one paired device (panel only). */
  panelRevokeDevice(deviceId) {
    return this.auth?.revoke?.(deviceId) ?? false
  }

  /**
   * Apply an in-memory config change from the panel.
   *
   * Both switches are runtime overrides: they survive until the plugin fiber is
   * disposed, and the panel reports that a DSH restart is needed to make them
   * the persisted configuration.
   * @param {Record<string, unknown>} body - `{key,value}` or a partial config object.
   * @returns {{applied:Record<string, boolean>, restartRequired: boolean}}
   */
  panelConfigPatch(body) {
    const input = body !== null && typeof body === 'object' ? body : {}
    const patch = {}
    if (typeof input.key === 'string' && 'value' in input) {
      patch[input.key] = input.value
    } else {
      if ('lanRequirePairing' in input) patch.lanRequirePairing = input.lanRequirePairing
      if ('mdns' in input) patch.mdns = input.mdns
    }
    const unknown = Object.keys(patch).filter((key) => key !== 'lanRequirePairing' && key !== 'mdns')
    if (unknown.length > 0) throw new HttpError(400, 'invalid_request', `unsupported config key: ${unknown[0]}`)
    if (Object.keys(patch).length === 0) throw new HttpError(400, 'invalid_request', 'nothing to patch')
    const applied = {}
    let restartRequired = false
    if ('lanRequirePairing' in patch) {
      const value = Boolean(patch.lanRequirePairing)
      this.overrides.lanRequirePairing = value
      this.auth?.setLanRequirePairing?.(value)
      applied.lanRequirePairing = value
    }
    if ('mdns' in patch) {
      const value = Boolean(patch.mdns)
      this.overrides.mdns = value
      applied.mdns = value
      // The advertiser is owned by the plugin fiber; only a restart re-reads it.
      restartRequired = true
    }
    this.logger.info?.(`panel config patched in memory: ${Object.keys(applied).join(',')}`)
    return { applied, restartRequired }
  }
}

/** Build the `dshgw://pair` URI for a pairing entry (used by tests/scripts). */
export function gatewayPairingUri(input) {
  return pairingUri(input)
}

export default ApiServer
