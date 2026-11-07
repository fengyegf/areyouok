/**
 * Shared helpers for dsh-gateway: ids, hashing, address classification, HTTP
 * JSON plumbing, DSH home resolution and filesystem writes.
 *
 * Everything here is dependency-light on purpose: it must stay usable from a
 * standalone smoke harness with no DSH process around it.
 *
 * @module dsh-gateway/lib/util
 */

import { createHash, randomBytes, randomUUID, timingSafeEqual as nodeTimingSafeEqual } from 'node:crypto'
import { closeSync, openSync, readFileSync, renameSync, writeFileSync, mkdirSync, unlinkSync } from 'node:fs'
import { homedir } from 'node:os'
import { dirname, isAbsolute, join } from 'node:path'

/** Stable plugin id used for log prefixes and state directories. */
export const PLUGIN_NAME = 'dsh-gateway'

/** Bump when the persisted state shape changes incompatibly. */
export const STATE_VERSION = 1

/** Cookie name prefix minted by DSH's connection plugin. */
export const DSH_AUTH_COOKIE_PREFIX = 'dsh-auth-'

/** Generate a random UUID. */
export function uuid() {
  return randomUUID()
}

/** Generate a URL-safe random token of `bytes` entropy bytes. */
export function randomToken(bytes = 32) {
  return randomBytes(bytes).toString('base64url')
}

/** SHA-256 of a UTF-8 string, hex encoded. */
export function sha256hex(value) {
  return createHash('sha256').update(String(value), 'utf8').digest('hex')
}

/** SHA-256 of bytes, hex encoded. */
export function sha256Bytes(buffer) {
  return createHash('sha256').update(buffer).digest('hex')
}

/** Constant-time comparison for two secrets of possibly different length. */
export function sameSecret(left, right) {
  const a = Buffer.from(String(left ?? ''), 'utf8')
  const b = Buffer.from(String(right ?? ''), 'utf8')
  if (a.length !== b.length) return false
  if (a.length === 0) return true
  return nodeTimingSafeEqual(a, b)
}

/** Expand a leading `~` (or `~\`) against the platform home directory. */
export function expandHome(path) {
  if (typeof path !== 'string' || path === '') return path
  if (path === '~') return homedir()
  if (path.startsWith('~/') || path.startsWith('~\\')) return join(homedir(), path.slice(2))
  return path
}

/**
 * Resolve the DSH home directory: `$DSH_HOME` wins, `~/.dsh` otherwise.
 * @param {NodeJS.ProcessEnv} [env] - environment (test seam).
 * @param {string} [home] - platform home fallback (test seam).
 */
export function resolveDshHome(env = process.env, home = homedir()) {
  const raw = env?.DSH_HOME
  if (typeof raw === 'string' && raw.trim() !== '') {
    const expanded = expandHome(raw.trim())
    return isAbsolute(expanded) ? expanded : join(process.cwd(), expanded)
  }
  return join(home, '.dsh')
}

/** Directory holding gateway state, TLS material and logs. */
export function dataDir() {
  return join(resolveDshHome(), PLUGIN_NAME)
}

/** Strip an IPv4-mapped / bracketed / zoned IPv6 textual address. */
export function normalizeRemoteAddress(remoteAddress) {
  let ip = String(remoteAddress ?? '').trim()
  if (ip === '') return ''
  if (ip.startsWith('[') && ip.includes(']')) ip = ip.slice(1, ip.indexOf(']'))
  const zone = ip.indexOf('%')
  if (zone !== -1) ip = ip.slice(0, zone)
  if (/^::ffff:/i.test(ip)) ip = ip.slice(7)
  return ip.toLowerCase()
}

/** Whether a normalized address belongs to loopback. */
export function isLoopbackAddress(ip) {
  const address = normalizeRemoteAddress(ip)
  return address === '127.0.0.1' || address === '::1' || address === '0:0:0:0:0:0:0:1'
}

/** Parse an IPv4 literal into four octets, or null. */
export function ipv4Octets(address) {
  const parts = String(address).split('.')
  if (parts.length !== 4) return null
  const octets = parts.map((part) => (/^\d{1,3}$/.test(part) ? Number(part) : Number.NaN))
  if (octets.some((octet) => !Number.isInteger(octet) || octet < 0 || octet > 255)) return null
  return octets
}

/**
 * Classify a source address as `loopback`, `lan` (private / link-local / ULA /
 * CGNAT / shared) or `public`. Unparseable values are treated as public.
 * @param {string|undefined} remoteAddress - `req.socket.remoteAddress`.
 * @returns {'loopback'|'lan'|'public'}
 */
export function classifyAddress(remoteAddress) {
  const ip = normalizeRemoteAddress(remoteAddress)
  if (ip === '') return 'public'
  if (isLoopbackAddress(ip)) return 'loopback'
  const v4 = ipv4Octets(ip)
  if (v4 !== null) {
    const [a, b] = v4
    if (a === 10) return 'lan'
    if (a === 172 && b >= 16 && b <= 31) return 'lan'
    if (a === 192 && b === 168) return 'lan'
    if (a === 100 && b >= 64 && b <= 127) return 'lan' // RFC 6598 CGNAT
    if (a === 169 && b === 254) return 'lan' // link-local
    return 'public'
  }
  if (!ip.includes(':')) return 'public'
  if (ip === '::1' || ip === '0:0:0:0:0:0:0:1') return 'loopback'
  if (/^fe[89ab][0-9a-f]:/.test(ip)) return 'lan' // fe80::/10
  if (/^f[cd][0-9a-f]{2}:/.test(ip)) return 'lan' // fc00::/7
  if (/^64:ff9b::/.test(ip)) return 'public' // NAT64 well-known prefix
  return 'public'
}

/** Whether the address is loopback or private. */
export function isPrivateAddress(remoteAddress) {
  return classifyAddress(remoteAddress) !== 'public'
}

/** Structured HTTP error carrying a `/v1` error code. */
export class HttpError extends Error {
  /**
   * @param {number} status - HTTP status code.
   * @param {string} code - stable machine code used in the error envelope.
   * @param {string} [message] - human readable message.
   * @param {unknown} [details] - optional JSON-safe details.
   */
  constructor(status, code, message, details) {
    super(message ?? code)
    this.name = 'HttpError'
    this.status = status
    this.code = code
    if (details !== undefined) this.details = details
  }
}

/** JSON.stringify that tolerates cycles by dropping them. */
export function safeJson(value) {
  const seen = new WeakSet()
  return JSON.stringify(value, (_key, item) => {
    if (typeof item === 'bigint') return item.toString()
    if (item !== null && typeof item === 'object') {
      if (seen.has(item)) return undefined
      seen.add(item)
    }
    return item
  })
}

/**
 * Write one JSON HTTP response.
 * @param {import('node:http').ServerResponse} res
 * @param {number} status
 * @param {unknown} body
 * @param {Record<string,string>} [headers]
 */
export function writeJson(res, status, body, headers = {}) {
  if (res.headersSent || res.destroyed) return
  const payload = safeJson(body ?? null)
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store',
    'x-content-type-options': 'nosniff',
    'access-control-allow-origin': '*',
    'access-control-allow-headers': 'authorization, content-type',
    'access-control-allow-methods': 'GET,POST,DELETE,PATCH,OPTIONS',
    ...headers,
  })
  res.end(payload)
}

/**
 * Read and parse a JSON request body.
 * @param {import('node:http').IncomingMessage} req
 * @param {number} [limit] - byte cap.
 * @returns {Promise<unknown>}
 * @throws {HttpError} 400 `invalid_request` / 413 `payload_too_large`.
 */
export function readJsonBody(req, limit = 1_048_576) {
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
      const text = Buffer.concat(chunks).toString('utf8').trim()
      if (text === '') {
        resolve({})
        return
      }
      try {
        const parsed = JSON.parse(text)
        if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
          reject(new HttpError(400, 'invalid_request', 'body must be a JSON object'))
          return
        }
        resolve(parsed)
      } catch {
        reject(new HttpError(400, 'invalid_request', 'body is not valid JSON'))
      }
    })
    req.on('error', (error) => fail(new HttpError(400, 'invalid_request', error?.message)))
  })
}

/** Extract a bearer token from an Authorization header. */
export function bearerToken(header) {
  if (typeof header !== 'string') return null
  const match = /^\s*bearer\s+([A-Za-z0-9._~+/=-]+)\s*$/i.exec(header)
  return match === null ? null : match[1]
}

/** Drop trailing slashes from an origin/base string. */
export function normalizeOrigin(origin) {
  return String(origin ?? '').trim().replace(/\/+$/, '')
}

/** Resolve after `ms` milliseconds. */
export function sleep(ms) {
  return new Promise((resolve) => {
    const timer = setTimeout(resolve, ms)
    if (typeof timer?.unref === 'function') timer.unref()
  })
}

/**
 * Exponential backoff delay with ±30% jitter.
 * @param {number} attempt - zero-based retry index.
 * @param {number} [base] - first delay.
 * @param {number} [max] - delay cap.
 */
export function backoffDelay(attempt, base = 500, max = 30_000) {
  const raw = Math.min(max, base * 2 ** Math.max(0, attempt))
  const jitter = 0.7 + Math.random() * 0.6
  return Math.round(raw * jitter)
}

/** Recursively check a value is lossless JSON (no functions, symbols, cycles). */
export function isJsonValue(value, seen = new WeakSet()) {
  if (value === null) return true
  const type = typeof value
  if (type === 'string' || type === 'number' || type === 'boolean') return true
  if (type !== 'object') return false
  if (seen.has(value)) return false
  seen.add(value)
  if (Array.isArray(value)) {
    const ok = value.every((item) => isJsonValue(item, seen))
    seen.delete(value)
    return ok
  }
  const prototype = Object.getPrototypeOf(value)
  if (prototype !== Object.prototype && prototype !== null) return false
  const ok = Object.values(value).every((item) => isJsonValue(item, seen))
  seen.delete(value)
  return ok
}

/** Ensure a directory exists. */
export function ensureDir(dir) {
  mkdirSync(dir, { recursive: true })
}

/**
 * Atomically replace a file with UTF-8 text (write temp + rename).
 * @param {string} file
 * @param {string} text
 * @param {number} [mode] - POSIX mode for the file.
 */
export function writeFileAtomic(file, text, mode = 0o600) {
  ensureDir(dirname(file))
  const temp = `${file}.${process.pid}.${Date.now()}.tmp`
  writeFileSync(temp, text, { encoding: 'utf8', mode })
  try {
    renameSync(temp, file)
  } catch (error) {
    try {
      unlinkSync(temp)
    } catch {
      // best effort
    }
    throw error
  }
}

/**
 * Read and parse one JSON file, returning `undefined` when absent/corrupt.
 * @param {string} file
 * @returns {unknown}
 */
export function readJsonFile(file) {
  let text
  try {
    text = readFileSync(file, 'utf8')
  } catch {
    return undefined
  }
  try {
    return JSON.parse(text)
  } catch {
    return undefined
  }
}

/** Peek at file existence without throwing. */
export function fileExists(file) {
  try {
    closeSync(openSync(file, 'r'))
    return true
  } catch {
    return false
  }
}

/**
 * Build a namespaced logger that never logs secrets or message bodies.
 * @param {{info?:Function,warn?:Function,error?:Function,debug?:Function}|undefined} raw
 * @param {string} [prefix]
 */
export function makeLogger(raw, prefix = PLUGIN_NAME) {
  const wrap = (fn, level) => (message, meta) => {
    try {
      if (typeof fn === 'function') fn.call(raw, `[${prefix}] ${message}`)
      else if (level === 'error') console.error(`[${prefix}] ${message}`, meta ?? '')
    } catch {
      // logging must never throw into the plugin
    }
  }
  return {
    debug: wrap(raw?.debug, 'debug'),
    info: wrap(raw?.info, 'info'),
    warn: wrap(raw?.warn, 'warn'),
    error: wrap(raw?.error, 'error'),
  }
}

/** Redact a token for logs. */
export function redact(value) {
  const text = String(value ?? '')
  if (text.length <= 8) return '***'
  return `${text.slice(0, 4)}…${text.slice(-2)}`
}
