/**
 * Source policy, pairing codes and device tokens for the gateway edges.
 *
 * Three questions are answered here:
 *   1. may this TCP peer talk to this listener at all (`guard`),
 *   2. which device owns this Bearer token (`verify`),
 *   3. how does a phone trade a one-shot pairing code for a long-lived token
 *      (`createPairingCode` / `pair`).
 *
 * Policy (handoff §5.6, impl-plan §3.6):
 *   - loopback is always allowed on the LAN HTTP port,
 *   - `lan` sources are allowed unless `lanRequirePairing` is on,
 *   - `public` sources are rejected with 403 on the LAN HTTP port,
 *   - the HTTPS public port always requires a device token, whatever the
 *     source address,
 *   - `/v1/pair` is the one pre-auth route (rate limited).
 *
 * The `/admin` page this module used to exempt is gone: the management API now
 * lives on the DSH host web server, behind the host's own browser login, so no
 * listener here answers an administrative route at all.
 *
 * Secrets: tokens are 32 random bytes (base64url); the persisted device table
 * only ever holds `sha256(token)`. Pairing codes are held in memory, are
 * one-shot, expire after `config.pairingCodeTtlMs` and fail slowly
 * (5 failures/minute/IP and globally). The device table itself lives in the
 * hub so it is persisted to `state.json` by the hub writer.
 *
 * @module dsh-gateway/lib/auth
 */

import {
  HttpError,
  bearerToken,
  classifyAddress,
  randomToken,
  sameSecret,
  sha256hex,
  uuid,
} from './util.js'

/** Pairing failures allowed per minute per source address. */
const PAIR_FAILS_PER_MINUTE_IP = 5

/** Pairing failures allowed per minute across all sources. */
const PAIR_FAILS_PER_MINUTE_GLOBAL = 5

/** How long a failed pairing attempt is remembered (sliding window). */
const PAIR_FAIL_WINDOW_MS = 60_000

/** Minimum interval between persisted `lastSeen` updates for one device. */
const TOUCH_INTERVAL_MS = 15_000

/** Maximum number of simultaneously valid pairing codes. */
const MAX_PAIRING_CODES = 8

/** Device ids are client generated; keep them printable and bounded. */
const DEVICE_ID_PATTERN = /^[A-Za-z0-9._:-]{1,128}$/

/** Strip control characters from a display name. */
function cleanName(value, fallback = '') {
  if (typeof value !== 'string') return fallback
  // eslint-disable-next-line no-control-regex
  const cleaned = value.replace(/[\u0000-\u001f\u007f]/g, '').trim()
  return cleaned === '' ? fallback : cleaned.slice(0, 64)
}

/** Build one denial verdict. */
function deny(status, code, message) {
  return { ok: false, status, code, message }
}

/**
 * AuthService — source policy + device tokens + pairing codes.
 */
export class AuthService {
  /**
   * @param {object} deps
   * @param {import('./hub.js').EventHub} deps.hub - device table owner/persister.
   * @param {Record<string, any>} deps.config - normalized gateway config.
   * @param {{info?:Function,warn?:Function,error?:Function,debug?:Function}} [deps.logger]
   * @param {string} [deps.dir] - data directory (unused directly; hub persists there).
   */
  constructor({ hub, config, logger, dir }) {
    this.hub = hub
    this.config = config ?? {}
    this.logger = logger ?? {}
    this.dir = dir ?? ''
    /** Runtime override of the config switch (admin PATCH; memory only). */
    this.lanRequirePairing = Boolean(this.config.lanRequirePairing)
    /** @type {Map<string, {code:string,createdAt:number,expiresAt:number,urls:string[],fingerprint:string|null}>} */
    this.pairingCodes = new Map()
    /** @type {Map<string, number[]>} failed pairing timestamps per IP. */
    this.failedByIp = new Map()
    /** @type {number[]} failed pairing timestamps globally. */
    this.failedGlobal = []
    /** @type {Set<(deviceId:string)=>void>} revoke notifications (WS closes sockets). */
    this.revokeListeners = new Set()
    /** @type {Map<string, number>} last persisted touch per device. */
    this.lastTouch = new Map()
    /** @type {NodeJS.Timeout|null} */
    this.sweeper = null
  }

  /** Load persisted device state (owned by the hub, loaded before us). */
  async load() {
    // The hub owns `state.json`; nothing of our own is persisted today, but
    // prune device entries that are malformed or already expired.
    try {
      this.sweep()
    } catch (error) {
      this.logger.warn?.(`auth load sweep failed: ${error?.message ?? error}`)
    }
    return this
  }

  /** Flush device-table changes through the hub writer. */
  persist() {
    try {
      this.hub.persist?.()
    } catch (error) {
      this.logger.warn?.(`auth persist failed: ${error?.message ?? error}`)
    }
  }

  /** Classify a socket address as `loopback` | `lan` | `public`. */
  classify(remoteAddress) {
    return classifyAddress(remoteAddress)
  }

  /**
   * Authorize one request/upgrade before any body is read.
   * @param {{remoteAddress?:string, localPort?:number, headers?:object, path?:string}} input
   * @returns {{ok:true, kind:'loopback'|'lan'|'public', device:object|null}
   *          |{ok:false, status:number, code:string, message:string}}
   */
  guard({ remoteAddress, localPort, headers, path } = {}) {
    const kind = classifyAddress(remoteAddress)
    const routePath = typeof path === 'string' ? path : ''
    const securePort = Number(this.config.httpsPort) > 0 ? Number(this.config.httpsPort) : 0
    const onSecurePort = securePort !== 0 && Number(localPort) === securePort
    const isPair = routePath === '/v1/pair'

    if (onSecurePort) {
      // Public HTTPS edge: pairing is the only pre-auth route; everything
      // else (including loopback) must present a device token.
      if (isPair) return { ok: true, kind, device: null }
      const device = this.deviceForHeaders(headers)
      if (device !== null) return { ok: true, kind, device }
      return deny(401, 'unauthenticated', 'a device token is required on the public port')
    }

    // LAN HTTP edge (`:7788`).
    if (kind === 'public') {
      return deny(403, 'forbidden_source', 'the LAN port only accepts private source addresses')
    }
    if (isPair) return { ok: true, kind, device: null }
    if (kind === 'loopback') return { ok: true, kind, device: null }
    if (!this.lanRequirePairing) return { ok: true, kind, device: null }
    const device = this.deviceForHeaders(headers)
    if (device !== null) return { ok: true, kind, device }
    return deny(401, 'unauthenticated', 'this gateway requires a paired device token on the LAN')
  }

  /** Verify one Bearer header and return its device. */
  deviceForHeaders(headers) {
    const token = bearerToken(headers?.authorization ?? headers?.Authorization)
    return this.verify(token)
  }

  /**
   * Verify a device token.
   * @param {string|null|undefined} token
   * @returns {object|null} device record (with tokenHash) or null.
   */
  verify(token) {
    if (typeof token !== 'string' || token === '') return null
    const hash = sha256hex(token)
    let devices = []
    try {
      devices = this.hub.listDevices?.() ?? []
    } catch (error) {
      this.logger.warn?.(`device table unreadable: ${error?.message ?? error}`)
      return null
    }
    for (const device of devices) {
      if (device === null || typeof device !== 'object') continue
      if (typeof device.tokenHash !== 'string' || device.tokenHash === '') continue
      if (!sameSecret(device.tokenHash, hash)) continue
      if (typeof device.expiresAt === 'number' && device.expiresAt > 0 && device.expiresAt <= Date.now()) {
        return null
      }
      return device
    }
    return null
  }

  /**
   * Create a one-shot pairing code.
   * @param {string[]} [urls] - gateway base URLs embedded in the QR code.
   * @param {{fingerprint?:string|null, ttlMs?:number}} [options]
   * @returns {{code:string, urls:string[], expiresAt:number, createdAt:number, fingerprint:string|null, ttlMs:number}}
   */
  createPairingCode(urls = [], options = {}) {
    const now = Date.now()
    const ttlMs = Number.isFinite(options.ttlMs)
      ? Math.max(30_000, Math.trunc(options.ttlMs))
      : Number(this.config.pairingCodeTtlMs) > 0
        ? Number(this.config.pairingCodeTtlMs)
        : 600_000
    this.#prunePairingCodes(now)
    const code = randomToken(32)
    const entry = {
      code,
      createdAt: now,
      expiresAt: now + ttlMs,
      ttlMs,
      urls: Array.isArray(urls)
        ? urls.filter((url) => typeof url === 'string' && url.trim() !== '').map((url) => url.trim())
        : [],
      fingerprint: typeof options.fingerprint === 'string' && options.fingerprint !== ''
        ? options.fingerprint
        : null,
    }
    this.pairingCodes.set(code, entry)
    // Keep the in-memory table bounded even if nobody sweeps.
    while (this.pairingCodes.size > MAX_PAIRING_CODES) {
      const oldest = this.pairingCodes.keys().next()
      if (oldest.done) break
      this.pairingCodes.delete(oldest.value)
    }
    return { ...entry, urls: [...entry.urls] }
  }

  /**
   * Return the currently valid pairing code (loopback admin only).
   * @returns {{code:string, urls:string[], expiresAt:number, createdAt:number, fingerprint:string|null}|null}
   */
  currentPairing() {
    const now = Date.now()
    this.#prunePairingCodes(now)
    for (const entry of this.pairingCodes.values()) {
      return { ...entry, urls: [...entry.urls] }
    }
    return null
  }

  /**
   * Exchange a pairing code for a long-lived device token.
   * @param {{code?:string, deviceName?:string, deviceId?:string, remoteAddress?:string}} input
   * @returns {Promise<{deviceToken:string, expiresAt:number|null, deviceId:string}>}
   * @throws {HttpError} 400/410/429 per the `/v1` error table.
   */
  async pair({ code, deviceName, deviceId, remoteAddress } = {}) {
    const address = typeof remoteAddress === 'string' ? remoteAddress : 'unknown'
    this.#assertPairRate(address, Date.now())

    if (typeof code !== 'string' || code.trim() === '') {
      this.#recordPairFailure(address)
      throw new HttpError(400, 'invalid_request', 'code is required')
    }
    const entry = this.#findPairingCode(code.trim())
    if (entry === undefined) {
      this.#recordPairFailure(address)
      throw new HttpError(400, 'invalid_code', 'pairing code is invalid or already used')
    }
    if (entry.expiresAt <= Date.now()) {
      this.pairingCodes.delete(entry.code)
      this.#recordPairFailure(address)
      throw new HttpError(410, 'code_expired', 'pairing code expired')
    }

    const requestedId = typeof deviceId === 'string' ? deviceId.trim() : ''
    if (requestedId !== '' && !DEVICE_ID_PATTERN.test(requestedId)) {
      this.#recordPairFailure(address)
      throw new HttpError(400, 'invalid_request', 'deviceId is malformed')
    }
    const id = requestedId !== '' ? requestedId : uuid()
    const name = cleanName(deviceName, id.slice(0, 8))

    // One-shot: consume before creating the device so concurrent attempts lose.
    this.pairingCodes.delete(entry.code)
    this.#clearPairFailures(address)

    const now = Date.now()
    const ttlDays = Number(this.config.deviceTokenTtlDays) || 0
    const expiresAt = ttlDays > 0 ? now + ttlDays * 86_400_000 : null
    const token = randomToken(32)
    const record = {
      deviceId: id,
      name,
      tokenHash: sha256hex(token),
      createdAt: now,
      lastSeenAt: now,
      lastSeen: now,
      remoteAddress: address,
      expiresAt,
      platform: 'unknown',
      subscriptions: [],
    }
    this.#evictForNewDevice(id, now)
    this.#putDevice(record)
    this.lastTouch.set(id, now)
    this.logger.info?.(`paired device ${id} (${name}) from ${classifyAddress(address)}`)
    return { deviceToken: token, expiresAt, deviceId: id }
  }

  /**
   * Revoke one device immediately (its WS connections are closed by listeners).
   * @param {string} deviceId
   * @returns {boolean} whether a device existed.
   */
  revoke(deviceId) {
    if (typeof deviceId !== 'string' || deviceId === '') return false
    const existed = this.#getDevice(deviceId) !== null
    try {
      this.hub.removeDevice?.(deviceId)
    } catch (error) {
      this.logger.warn?.(`device removal failed: ${error?.message ?? error}`)
    }
    this.lastTouch.delete(deviceId)
    if (existed) {
      this.#notifyRevoke(deviceId)
      this.persist()
      this.logger.info?.(`revoked device ${deviceId}`)
    }
    return existed
  }

  /** Public device list, never exposing `tokenHash`. */
  devices() {
    let devices = []
    try {
      devices = this.hub.listDevices?.() ?? []
    } catch {
      devices = []
    }
    return devices
      .filter((device) => device !== null && typeof device === 'object' && typeof device.deviceId === 'string')
      .map((device) => ({
        deviceId: device.deviceId,
        name: typeof device.name === 'string' ? device.name : '',
        createdAt: device.createdAt ?? 0,
        lastSeen: device.lastSeenAt ?? device.lastSeen ?? 0,
        remoteAddress: typeof device.remoteAddress === 'string' ? device.remoteAddress : '',
        expiresAt: device.expiresAt ?? null,
        online: Boolean(device.online),
      }))
      .sort((left, right) => (right.lastSeen ?? 0) - (left.lastSeen ?? 0))
  }

  /** Number of stored devices. */
  deviceCount() {
    try {
      return (this.hub.listDevices?.() ?? []).length
    } catch {
      return 0
    }
  }

  /**
   * Record device activity (throttled to one persisted update per 15s).
   * @param {string} deviceId
   * @param {{remoteAddress?:string, name?:string, lastSeq?:number}} [info]
   */
  touch(deviceId, info = {}) {
    if (typeof deviceId !== 'string' || deviceId === '') return
    const device = this.#getDevice(deviceId)
    if (device === null) return
    const now = Date.now()
    const previous = this.lastTouch.get(deviceId) ?? 0
    if (now - previous < TOUCH_INTERVAL_MS) return
    this.lastTouch.set(deviceId, now)
    // The hub persists `lastSeenAt`; keep `lastSeen` for forward compatibility.
    const next = { ...device, lastSeenAt: now, lastSeen: now }
    if (typeof info.remoteAddress === 'string' && info.remoteAddress !== '') {
      next.remoteAddress = info.remoteAddress
    }
    if (typeof info.name === 'string' && info.name !== '') next.name = cleanName(info.name, device.name)
    if (Number.isFinite(info.lastSeq)) next.lastSeq = Math.trunc(info.lastSeq)
    this.#putDevice(next)
    this.persist()
  }

  /**
   * Runtime switch for `lanRequirePairing` (admin PATCH, memory only).
   * @param {boolean} value
   * @returns {boolean} the value now in effect.
   */
  setLanRequirePairing(value) {
    this.lanRequirePairing = Boolean(value)
    return this.lanRequirePairing
  }

  /**
   * Subscribe to revocations (used by the WS server to close sockets).
   * @param {(deviceId:string)=>void} listener
   * @returns {()=>void} unsubscribe
   */
  onRevoke(listener) {
    if (typeof listener !== 'function') return () => {}
    this.revokeListeners.add(listener)
    return () => this.revokeListeners.delete(listener)
  }

  /** Start the periodic expiration sweep. Safe to call twice. */
  startSweeper(intervalMs = 60_000) {
    if (this.sweeper !== null) return
    const period = Number.isFinite(intervalMs) && intervalMs >= 5_000 ? Math.trunc(intervalMs) : 60_000
    this.sweeper = setInterval(() => {
      try {
        this.sweep()
      } catch (error) {
        this.logger.warn?.(`auth sweep failed: ${error?.message ?? error}`)
      }
    }, period)
    if (typeof this.sweeper?.unref === 'function') this.sweeper.unref()
  }

  /** Stop the periodic sweep. */
  stopSweeper() {
    if (this.sweeper !== null) {
      clearInterval(this.sweeper)
      this.sweeper = null
    }
  }

  /**
   * Drop expired pairing codes and expired device tokens.
   * @returns {{codes:number, devices:number}}
   */
  sweep() {
    const now = Date.now()
    let codes = 0
    for (const [key, entry] of this.pairingCodes) {
      if (entry.expiresAt <= now) {
        this.pairingCodes.delete(key)
        codes += 1
      }
    }
    // Prune failure windows so the maps cannot grow without bound.
    for (const [ip, stamps] of this.failedByIp) {
      const kept = stamps.filter((stamp) => now - stamp < PAIR_FAIL_WINDOW_MS)
      if (kept.length === 0) this.failedByIp.delete(ip)
      else this.failedByIp.set(ip, kept)
    }
    this.failedGlobal = this.failedGlobal.filter((stamp) => now - stamp < PAIR_FAIL_WINDOW_MS)

    let devices = 0
    let all = []
    try {
      all = this.hub.listDevices?.() ?? []
    } catch {
      all = []
    }
    for (const device of all) {
      if (device === null || typeof device !== 'object') continue
      const expired = typeof device.expiresAt === 'number' && device.expiresAt > 0 && device.expiresAt <= now
      if (!expired) continue
      try {
        this.hub.removeDevice?.(device.deviceId)
      } catch {
        // best effort
      }
      this.lastTouch.delete(device.deviceId)
      this.#notifyRevoke(device.deviceId)
      devices += 1
    }
    if (devices > 0) this.persist()
    return { codes, devices }
  }

  // ---------------------------------------------------------------- internals

  #findPairingCode(code) {
    let found
    for (const entry of this.pairingCodes.values()) {
      if (sameSecret(entry.code, code)) found = entry
    }
    return found
  }

  #prunePairingCodes(now) {
    for (const [key, entry] of this.pairingCodes) {
      if (entry.expiresAt <= now) this.pairingCodes.delete(key)
    }
  }

  #assertPairRate(address, now) {
    const ipStamps = (this.failedByIp.get(address) ?? []).filter((stamp) => now - stamp < PAIR_FAIL_WINDOW_MS)
    this.failedByIp.set(address, ipStamps)
    const globalStamps = this.failedGlobal.filter((stamp) => now - stamp < PAIR_FAIL_WINDOW_MS)
    this.failedGlobal = globalStamps
    if (ipStamps.length >= PAIR_FAILS_PER_MINUTE_IP) {
      throw new HttpError(429, 'rate_limited', 'too many pairing attempts from this address')
    }
    if (globalStamps.length >= PAIR_FAILS_PER_MINUTE_GLOBAL) {
      throw new HttpError(429, 'rate_limited', 'too many pairing attempts')
    }
  }

  #recordPairFailure(address) {
    const now = Date.now()
    const ipStamps = (this.failedByIp.get(address) ?? []).filter((stamp) => now - stamp < PAIR_FAIL_WINDOW_MS)
    ipStamps.push(now)
    this.failedByIp.set(address, ipStamps)
    this.failedGlobal = this.failedGlobal.filter((stamp) => now - stamp < PAIR_FAIL_WINDOW_MS)
    this.failedGlobal.push(now)
  }

  #clearPairFailures(address) {
    this.failedByIp.delete(address)
  }

  #getDevice(deviceId) {
    if (typeof deviceId !== 'string' || deviceId === '') return null
    try {
      const device = this.hub.getDevice?.(deviceId)
      return device !== null && typeof device === 'object' ? device : null
    } catch {
      return null
    }
  }

  #putDevice(record) {
    try {
      this.hub.upsertDevice?.(record)
    } catch (error) {
      this.logger.warn?.(`device upsert failed: ${error?.message ?? error}`)
    }
  }

  #evictForNewDevice(deviceId, now) {
    let devices = []
    try {
      devices = this.hub.listDevices?.() ?? []
    } catch {
      devices = []
    }
    const maxDevices = Math.max(1, Number(this.config.maxDevices) || 4)
    const exists = devices.some((device) => device?.deviceId === deviceId)
    if (exists) return
    if (devices.length < maxDevices) return
    const victim = devices
      .filter((device) => device !== null && typeof device === 'object')
      .sort((left, right) => (
        (left.lastSeenAt ?? left.lastSeen ?? left.createdAt ?? 0)
        - (right.lastSeenAt ?? right.lastSeen ?? right.createdAt ?? 0)
      ))[0]
    if (victim === undefined) return
    try {
      this.hub.removeDevice?.(victim.deviceId)
    } catch {
      // best effort
    }
    this.lastTouch.delete(victim.deviceId)
    this.#notifyRevoke(victim.deviceId)
    this.logger.info?.(`evicted device ${victim.deviceId} (maxDevices=${maxDevices}) at ${now}`)
  }

  #notifyRevoke(deviceId) {
    for (const listener of this.revokeListeners) {
      try {
        listener(deviceId)
      } catch (error) {
        this.logger.warn?.(`revoke listener failed: ${error?.message ?? error}`)
      }
    }
  }
}

export default AuthService
