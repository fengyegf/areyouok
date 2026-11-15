/**
 * Anonymous machine-translation backend used by the thinking-chain view.
 *
 * The Android client shows a model's reasoning chain as-is; when the user asks
 * for it in another language the phone POSTs the chain to /v1/translate, and
 * this module forwards one bounded request to an anonymous Bing/Google endpoint,
 * caching the result on disk so a repeated chain costs nothing.
 *
 * Protocol details mirror DSH's own experimental translator
 * (@deepseek-ai/dsh-experimental-translator):
 *   - Bing: POST https://edge.microsoft.com/translate/translatetext with a JSON
 *     array body, to / optional from / isEnterpriseClient=false in the query,
 *   - Google: POST https://translate.googleapis.com/translate_a/single with a
 *     client=gtx&sl&tl&dt=t&q form body.
 *
 * Safety properties (they are the point of this module):
 *   - the source text never reaches a log line and is not stored verbatim,
 *   - no cookies/credentials, no redirects, no retry, no provider fallback,
 *   - the response body is byte-capped and the request is deadline-capped,
 *   - at most four requests run at once; further callers queue briefly and are
 *     rejected with 429 rate_limited instead of piling up,
 *   - the persistent LRU cache is written atomically (tmp + rename) in the
 *     plugin data directory and loaded on start.
 *
 * @module dsh-gateway/lib/translate
 */

import { mkdir, readFile, rename, unlink, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { sha256hex } from './util.js'

/** Anonymous providers this gateway is willing to call (order = discovery order). */
export const TRANSLATE_PROVIDERS = Object.freeze([
  Object.freeze({ id: 'bing', label: 'Bing', free: true }),
  Object.freeze({ id: 'google', label: 'Google', free: true }),
])

/** Target languages accepted by POST /v1/translate. */
export const TRANSLATE_TARGETS = Object.freeze(['zh-CN', 'zh-TW', 'en', 'ja'])

/** Target language used when the client does not send one. */
export const DEFAULT_TARGET_LANGUAGE = 'zh-CN'

/** Default provider when neither the request nor the config names one. */
export const DEFAULT_PROVIDER = 'bing'

/** Concurrent provider requests allowed at once. */
export const MAX_CONCURRENT = 4

/** Callers allowed to wait for a slot before the next one is rejected with 429. */
export const MAX_QUEUED = 8

/** Protocol revisions mixed into the cache key (see cacheKeyOf). */
const RECIPES = Object.freeze({ bing: 'bing-edge-v1', google: 'google-form-v1' })

/** Bing Edge anonymous translation endpoint. */
export const BING_ENDPOINT = 'https://edge.microsoft.com/translate/translatetext'

/** Google anonymous translation endpoint. */
export const GOOGLE_ENDPOINT = 'https://translate.googleapis.com/translate_a/single'

/** Persisted cache file name inside the plugin data directory. */
export const CACHE_FILE = 'translate-cache.json'

/** Persisted cache shape version. */
const CACHE_VERSION = 1

/** Debounce for cache persistence (milliseconds). */
const PERSIST_DELAY_MS = 200

/** Defaults mirrored from config.js (used when a partial config is passed). */
const FALLBACK = Object.freeze({
  timeoutMs: 10_000,
  maxTextChars: 4_000,
  maxResponseBytes: 1_048_576,
  cacheMaxEntries: 500,
})

/**
 * One translation failure, already mapped to the /v1 error envelope.
 *
 * status and code are the HTTP status / machine code the gateway writes; the
 * message is safe to show to a device (it never contains the source text).
 */
export class TranslationError extends Error {
  /**
   * @param {number} status - HTTP status to answer with.
   * @param {string} code - stable machine code (invalid_request, text_too_long, ...).
   * @param {string} [message] - human readable, already sanitized.
   */
  constructor(status, code, message) {
    super(message ?? code)
    this.name = 'TranslationError'
    this.status = status
    this.code = code
  }
}

/** @returns {boolean} whether value is a plain object. */
function isRecord(value) {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/**
 * Normalize one provider id.
 * @param {unknown} value
 * @returns {'bing'|'google'|null} canonical id, or null when unsupported.
 */
export function normalizeProvider(value) {
  if (typeof value !== 'string') return null
  const lowered = value.trim().toLowerCase()
  return lowered === 'bing' || lowered === 'google' ? lowered : null
}

/**
 * Normalize one target language against TRANSLATE_TARGETS.
 * @param {unknown} value
 * @returns {string|null} canonical tag (zh-CN, zh-TW, en, ja), or null.
 */
export function normalizeTarget(value) {
  if (typeof value !== 'string') return null
  const wanted = value.trim().toLowerCase()
  if (wanted === '') return null
  for (const target of TRANSLATE_TARGETS) {
    if (target.toLowerCase() === wanted) return target
  }
  return null
}

/**
 * Normalize the optional source language.
 *
 * Omitted / empty / auto all mean "let the provider detect it"; anything else
 * must look like a BCP-47ish tag (letters/digits/dashes) and is passed through.
 *
 * @param {unknown} value
 * @returns {string} canonical tag or 'auto'.
 * @throws {TranslationError} 400 when the tag is malformed.
 */
export function normalizeSource(value) {
  if (value === undefined || value === null) return 'auto'
  if (typeof value !== 'string') {
    throw new TranslationError(400, 'invalid_request', 'sourceLanguage must be a string')
  }
  const trimmed = value.trim()
  if (trimmed === '' || trimmed.toLowerCase() === 'auto') return 'auto'
  if (!/^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8}){0,2}$/.test(trimmed)) {
    throw new TranslationError(400, 'invalid_request', 'sourceLanguage is not a valid language tag')
  }
  return trimmed
}

/**
 * Map one language tag to the tag a provider understands.
 *
 * Simplified Chinese collapses to zh-Hans (Bing) / zh-CN (Google) and
 * traditional Chinese to zh-Hant / zh-TW; every other tag is passed through.
 *
 * @param {string} tag - canonical tag (or 'auto').
 * @param {'bing'|'google'} provider
 * @returns {string} provider language tag.
 */
export function providerLanguage(tag, provider) {
  const lowered = String(tag ?? '').toLowerCase()
  if (lowered === 'zh' || lowered === 'zh-cn' || lowered === 'zh-sg' || lowered === 'zh-hans') {
    return provider === 'google' ? 'zh-CN' : 'zh-Hans'
  }
  if (lowered === 'zh-tw' || lowered === 'zh-hk' || lowered === 'zh-mo' || lowered === 'zh-hant') {
    return provider === 'google' ? 'zh-TW' : 'zh-Hant'
  }
  return tag
}

/**
 * Build the cache key for one request.
 *
 * sha256(provider|recipe|source|target|text): the recipe pins the protocol
 * revision (and therefore the endpoint shape), so a protocol change can never
 * mix old and new results.
 *
 * @param {{provider:'bing'|'google', source:string, target:string, text:string}} spec
 * @returns {string} hex digest.
 */
export function cacheKeyOf(spec) {
  const joined = [spec.provider, RECIPES[spec.provider], spec.source, spec.target, spec.text].join('|')
  return sha256hex(joined)
}

/**
 * Build the provider request for one spec.
 * @param {{provider:'bing'|'google', source:string, target:string, text:string}} spec
 * @returns {{url:URL, init:{method:string, body:string, headers:Record<string,string>}}}
 */
export function requestFor(spec) {
  const source = providerLanguage(spec.source, spec.provider)
  const target = providerLanguage(spec.target, spec.provider)
  if (spec.provider === 'google') {
    const url = new URL(GOOGLE_ENDPOINT)
    const form = new URLSearchParams({ client: 'gtx', sl: source, tl: target, dt: 't', q: spec.text })
    return {
      url,
      init: {
        method: 'POST',
        body: form.toString(),
        headers: { 'content-type': 'application/x-www-form-urlencoded' },
      },
    }
  }
  const url = new URL(BING_ENDPOINT)
  if (source !== 'auto') url.searchParams.set('from', source)
  url.searchParams.set('to', target)
  url.searchParams.set('isEnterpriseClient', 'false')
  return {
    url,
    init: {
      method: 'POST',
      body: JSON.stringify([spec.text]),
      headers: { 'content-type': 'application/json' },
    },
  }
}

/** One 502 for a response shape the provider is not supposed to send. */
function invalidResponse(provider, reason = 'returned an invalid translation response') {
  return new TranslationError(502, 'translation_failed', provider + ' ' + reason)
}

/**
 * Extract the translated text from a provider payload.
 *
 * @param {unknown} payload - parsed JSON body.
 * @param {'bing'|'google'} provider
 * @returns {string} translated text.
 * @throws {TranslationError} 502 when the shape is not the documented one.
 */
export function parseTranslation(payload, provider) {
  if (!Array.isArray(payload) || payload.length === 0) throw invalidResponse(provider)
  const first = payload[0]
  if (provider === 'google') {
    if (!Array.isArray(first) || first.length === 0) throw invalidResponse(provider)
    const parts = []
    for (const segment of first) {
      if (!Array.isArray(segment) || typeof segment[0] !== 'string') throw invalidResponse(provider)
      parts.push(segment[0])
    }
    return parts.join('')
  }
  if (!isRecord(first) || !Array.isArray(first.translations) || first.translations.length === 0) {
    throw invalidResponse(provider)
  }
  const translation = first.translations[0]
  if (!isRecord(translation) || typeof translation.text !== 'string') throw invalidResponse(provider)
  return translation.text
}

/**
 * Read and parse one JSON response body, enforcing the byte cap.
 *
 * @param {Response} response - provider response.
 * @param {number} maxBytes - cap for the whole body.
 * @param {'bing'|'google'} provider
 * @returns {Promise<unknown>} parsed payload.
 * @throws {TranslationError} 502 on too many bytes or invalid JSON.
 */
export async function readJsonPayload(response, maxBytes, provider) {
  let buffer
  const body = response?.body
  if (body !== null && body !== undefined && typeof body.getReader === 'function') {
    const reader = body.getReader()
    const chunks = []
    let bytes = 0
    try {
      for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        if (value === undefined) continue
        bytes += value.byteLength
        if (bytes > maxBytes) {
          try {
            await reader.cancel()
          } catch {
            // best effort: the body is being discarded anyway
          }
          throw invalidResponse(provider, 'returned more than ' + maxBytes + ' response bytes')
        }
        chunks.push(Buffer.from(value))
      }
    } finally {
      try {
        reader.releaseLock()
      } catch {
        // ignore
      }
    }
    buffer = Buffer.concat(chunks)
  } else {
    const text = await response.text()
    buffer = Buffer.from(text, 'utf8')
    if (buffer.byteLength > maxBytes) {
      throw invalidResponse(provider, 'returned more than ' + maxBytes + ' response bytes')
    }
  }
  try {
    return JSON.parse(buffer.toString('utf8'))
  } catch {
    throw invalidResponse(provider, 'returned invalid translation JSON')
  }
}

/** Drop a response body we are not going to read. */
async function discardBody(response) {
  try {
    await response?.body?.cancel?.()
  } catch {
    // ignore
  }
}

/**
 * Anonymous translation service: bounded provider requests plus a persistent LRU
 * cache in the plugin data directory.
 */
export class TranslationService {
  #cache = new Map()
  #active = 0
  #waiters = []
  #closed = false
  #lifetime = new AbortController()
  #ready = null
  #timer = null
  #dirty = false
  #writing = Promise.resolve()

  /**
   * @param {object} options
   * @param {Record<string, any>} [options.config] - resolved gateway config.
   * @param {{debug?:Function,warn?:Function,info?:Function,error?:Function}} [options.logger]
   * @param {string} [options.dir] - plugin data dir; empty disables persistence.
   */
  constructor({ config, logger, dir } = {}) {
    this.config = config ?? {}
    this.logger = logger ?? {}
    this.dir = typeof dir === 'string' ? dir : ''
    this.filePath = this.dir === '' ? '' : join(this.dir, CACHE_FILE)
    /** Loaded once, eagerly but never awaited by the gateway boot path. */
    this.#ready = this.#load()
  }

  /** Resolves once the on-disk cache has been read (or ignored). */
  get ready() {
    return this.#ready
  }

  /** Effective per-request text cap (UTF-16 code units). */
  get maxTextChars() {
    return this.#positive(this.config.translateMaxTextChars, FALLBACK.maxTextChars)
  }

  /** Effective response body cap (bytes). */
  get maxResponseBytes() {
    return this.#positive(this.config.translateMaxResponseBytes, FALLBACK.maxResponseBytes)
  }

  /** Effective provider deadline (milliseconds). */
  get timeoutMs() {
    return this.#positive(this.config.translateTimeoutMs, FALLBACK.timeoutMs)
  }

  /** Effective cache entry cap (0 clears reuse growth). */
  get maxCacheEntries() {
    const value = this.config.translateCacheMaxEntries
    const parsed = typeof value === 'number' ? value : Number.parseInt(String(value ?? ''), 10)
    if (!Number.isFinite(parsed) || parsed < 0) return FALLBACK.cacheMaxEntries
    return Math.trunc(parsed)
  }

  /** Current number of cached translations (test/debug seam). */
  get cacheSize() {
    return this.#cache.size
  }

  /** Coerce one numeric config value into a strictly positive integer. */
  #positive(value, fallback) {
    const parsed = typeof value === 'number' ? value : Number.parseInt(String(value ?? ''), 10)
    if (!Number.isFinite(parsed) || parsed <= 0) return fallback
    return Math.trunc(parsed)
  }

  /**
   * Discovery payload for GET /v1/translate/providers.
   * @returns {{providers:Array<{id:string,label:string,free:boolean}>, defaultProvider:string, defaultTargetLanguage:string, maxTextChars:number, targets:string[]}}
   */
  providers() {
    return {
      providers: TRANSLATE_PROVIDERS.map((provider) => ({
        id: provider.id,
        label: provider.label,
        free: provider.free,
      })),
      defaultProvider: normalizeProvider(this.config.translateProvider) ?? DEFAULT_PROVIDER,
      defaultTargetLanguage: DEFAULT_TARGET_LANGUAGE,
      maxTextChars: this.maxTextChars,
      targets: [...TRANSLATE_TARGETS],
    }
  }

  /**
   * Translate one bounded text.
   *
   * @param {{text:string, targetLanguage:string, sourceLanguage?:string, provider?:string}} request
   * @returns {Promise<{text:string, provider:string, targetLanguage:string, sourceLanguage:string, cached:boolean, chars:number}>}
   * @throws {TranslationError} mapped /v1 failure (400/413/429/502/503/504).
   */
  async translate(request = {}) {
    if (this.#closed) {
      throw new TranslationError(503, 'translation_unavailable', 'the translation service is closed')
    }
    if (this.config.translateEnabled === false) {
      throw new TranslationError(503, 'translation_unavailable', 'translation is disabled')
    }
    const spec = this.#resolve(request)
    await this.#ready

    const key = cacheKeyOf(spec)
    const cached = this.#cacheGet(key)
    if (cached !== null) {
      return this.#result(spec, cached.text, true)
    }

    const translated = await this.#request(spec)
    this.#cacheSet(key, { text: translated, at: Date.now() })
    return this.#result(spec, translated, false)
  }

  /** Flush pending cache writes now. */
  async flush() {
    await this.#flush()
  }

  /** Stop the service: abort in-flight work and flush the cache. Never throws. */
  async close() {
    if (this.#closed) return
    this.#closed = true
    try {
      this.#lifetime.abort()
    } catch {
      // ignore
    }
    if (this.#timer !== null) {
      clearTimeout(this.#timer)
      this.#timer = null
    }
    const waiters = this.#waiters.splice(0, this.#waiters.length)
    for (const waiter of waiters) {
      try {
        waiter.reject(new TranslationError(503, 'translation_unavailable', 'the translation service is closed'))
      } catch {
        // ignore
      }
    }
    try {
      await this.#ready
    } catch {
      // ignore
    }
    try {
      await this.#writing
    } catch {
      // ignore
    }
    try {
      await this.#flush()
    } catch {
      // ignore
    }
  }

  /** Turn one request into a validated spec, or throw the mapped error. */
  #resolve(request) {
    const text = request?.text
    if (typeof text !== 'string' || text.trim() === '') {
      throw new TranslationError(400, 'invalid_request', 'text is required')
    }
    const maxTextChars = this.maxTextChars
    if (text.length > maxTextChars) {
      throw new TranslationError(413, 'text_too_long', 'text must be at most ' + maxTextChars + ' UTF-16 code units')
    }
    const target = normalizeTarget(request?.targetLanguage)
    if (target === null) {
      throw new TranslationError(400, 'invalid_request', 'targetLanguage must be one of: ' + TRANSLATE_TARGETS.join(', '))
    }
    const provider = normalizeProvider(request?.provider ?? this.config.translateProvider)
    if (provider === null) {
      throw new TranslationError(400, 'invalid_request', 'provider must be bing or google')
    }
    const source = normalizeSource(request?.sourceLanguage)
    return { text, target, provider, source }
  }

  /** Build the wire result. chars is the length of the translated text. */
  #result(spec, text, cached) {
    return {
      text,
      provider: spec.provider,
      targetLanguage: spec.target,
      sourceLanguage: spec.source,
      cached,
      chars: text.length,
    }
  }

  /**
   * Run one provider request under the deadline + concurrency limits.
   * @param {{provider:'bing'|'google', source:string, target:string, text:string}} spec
   * @returns {Promise<string>} translated text.
   */
  async #request(spec) {
    const timeoutMs = this.timeoutMs
    const controller = new AbortController()
    let timedOut = false
    const timer = setTimeout(() => {
      timedOut = true
      try {
        controller.abort()
      } catch {
        // ignore
      }
    }, timeoutMs)
    const onLifetimeAbort = () => {
      try {
        controller.abort()
      } catch {
        // ignore
      }
    }
    this.#lifetime.signal.addEventListener('abort', onLifetimeAbort, { once: true })

    try {
      await this.#acquire(controller.signal)
      try {
        const { url, init } = requestFor(spec)
        let response
        try {
          response = await globalThis.fetch(url, {
            ...init,
            redirect: 'error',
            credentials: 'omit',
            signal: controller.signal,
          })
        } catch (error) {
          throw this.#transportError(spec.provider, error, controller.signal, timedOut)
        }
        if (response === null || response === undefined || typeof response.ok !== 'boolean') {
          throw invalidResponse(spec.provider)
        }
        if (!response.ok) {
          await discardBody(response)
          throw invalidResponse(spec.provider, 'translation failed (HTTP ' + response.status + ')')
        }
        let payload
        try {
          payload = await readJsonPayload(response, this.maxResponseBytes, spec.provider)
        } catch (error) {
          if (error instanceof TranslationError) throw error
          throw this.#transportError(spec.provider, error, controller.signal, timedOut)
        }
        return parseTranslation(payload, spec.provider)
      } finally {
        this.#release()
      }
    } finally {
      clearTimeout(timer)
      this.#lifetime.signal.removeEventListener('abort', onLifetimeAbort)
    }
  }

  /** Map one transport/abort failure to the documented envelope. */
  #transportError(provider, error, signal, timedOut) {
    const cause = error?.name ?? (typeof error === 'string' ? error : 'Error')
    this.logger.debug?.('translate ' + provider + ' request failed: ' + cause)
    if (timedOut || (signal?.aborted && !this.#closed)) {
      return new TranslationError(504, 'translation_timeout', provider + ' translation timed out after ' + this.timeoutMs + 'ms')
    }
    if (this.#closed) {
      throw new TranslationError(503, 'translation_unavailable', 'the translation service is closed')
    }
    return new TranslationError(502, 'translation_failed', provider + ' translation request failed')
  }

  /** The 504/503 that aborts a caller waiting for a concurrency slot. */
  #abortError() {
    if (this.#closed) {
      return new TranslationError(503, 'translation_unavailable', 'the translation service is closed')
    }
    return new TranslationError(504, 'translation_timeout', 'translation timed out after ' + this.timeoutMs + 'ms waiting for a slot')
  }

  /**
   * Take one of the MAX_CONCURRENT slots.
   * @param {AbortSignal} signal
   * @returns {Promise<void>}
   */
  async #acquire(signal) {
    if (this.#active < MAX_CONCURRENT) {
      this.#active += 1
      return
    }
    if (this.#waiters.length >= MAX_QUEUED) {
      throw new TranslationError(429, 'rate_limited', 'too many concurrent translation requests')
    }
    await new Promise((resolve, reject) => {
      const waiter = { resolve: null, reject: null }
      const onAbort = () => {
        const index = this.#waiters.indexOf(waiter)
        if (index !== -1) this.#waiters.splice(index, 1)
        reject(this.#abortError())
      }
      waiter.resolve = () => {
        signal.removeEventListener('abort', onAbort)
        resolve()
      }
      waiter.reject = (error) => {
        signal.removeEventListener('abort', onAbort)
        reject(error)
      }
      signal.addEventListener('abort', onAbort, { once: true })
      this.#waiters.push(waiter)
    })
  }

  /** Hand the slot to the next waiter, or give it back. */
  #release() {
    const waiter = this.#waiters.shift()
    if (waiter !== undefined) {
      waiter.resolve()
      return
    }
    if (this.#active > 0) this.#active -= 1
  }

  /** Cache read with LRU promotion. */
  #cacheGet(key) {
    const hit = this.#cache.get(key)
    if (hit === undefined) return null
    this.#cache.delete(key)
    this.#cache.set(key, hit)
    return hit
  }

  /** Cache write with LRU eviction + debounced atomic persistence. */
  #cacheSet(key, value) {
    this.#cache.delete(key)
    this.#cache.set(key, value)
    const cap = this.maxCacheEntries
    while (this.#cache.size > Math.max(0, cap)) {
      const oldest = this.#cache.keys().next().value
      if (oldest === undefined) break
      this.#cache.delete(oldest)
    }
    this.#schedule()
  }

  #schedule() {
    if (this.dir === '' || this.#closed) return
    this.#dirty = true
    if (this.#timer !== null) return
    this.#timer = setTimeout(() => {
      this.#timer = null
      void this.#flush()
    }, PERSIST_DELAY_MS)
    this.#timer.unref?.()
  }

  /** Read the persisted cache; malformed content is ignored, never fatal. */
  async #load() {
    if (this.filePath === '') return
    let raw
    try {
      raw = await readFile(this.filePath, 'utf8')
    } catch (error) {
      if (error?.code !== 'ENOENT') this.logger.warn?.('translate cache load failed: ' + (error?.name ?? 'Error'))
      return
    }
    let parsed
    try {
      parsed = JSON.parse(raw)
    } catch {
      this.logger.warn?.('translate cache is not valid JSON; starting empty')
      return
    }
    if (!isRecord(parsed) || !Array.isArray(parsed.entries)) return
    for (const entry of parsed.entries) {
      if (!Array.isArray(entry) || entry.length < 2) continue
      const [key, text] = entry
      if (typeof key !== 'string' || key === '' || typeof text !== 'string' || text === '') continue
      const at = Number.isFinite(entry[2]) ? Math.trunc(entry[2]) : 0
      this.#cache.set(key, { text, at })
    }
    const cap = this.maxCacheEntries
    while (this.#cache.size > Math.max(0, cap)) {
      const oldest = this.#cache.keys().next().value
      if (oldest === undefined) break
      this.#cache.delete(oldest)
    }
  }

  /** Atomically persist the current cache (tmp + rename), serialized. */
  async #flush() {
    if (this.filePath === '' || !this.#dirty) {
      await this.#writing
      return
    }
    this.#dirty = false
    const entries = []
    for (const [key, value] of this.#cache) entries.push([key, value.text, value.at])
    const body = JSON.stringify({ version: CACHE_VERSION, entries })
    this.#writing = this.#writing.then(async () => {
      try {
        await mkdir(this.dir, { recursive: true })
        const tmp = this.filePath + '.' + process.pid + '.' + Math.random().toString(36).slice(2) + '.tmp'
        try {
          await writeFile(tmp, body, { mode: 0o600 })
          await rename(tmp, this.filePath)
        } catch (error) {
          try {
            await unlink(tmp)
          } catch {
            // ignore
          }
          throw error
        }
      } catch (error) {
        this.logger.warn?.('translate cache save failed: ' + (error?.name ?? 'Error'))
      }
    })
    await this.#writing
  }
}

export default TranslationService
