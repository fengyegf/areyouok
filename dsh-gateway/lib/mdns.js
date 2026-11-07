/**
 * mDNS advertisement for zero-config APK discovery.
 *
 * Publishes `_dshgw._tcp` with TXT `v=1 name=<host> lan=<port> pair=<httpsPort|0> api=/v1`
 * so a phone on the same LAN can find the gateway without typing an address.
 *
 * The dependency (`bonjour-service`) is imported lazily: when it is missing, the
 * multicast stack is unavailable (no network / container / firewall) or the
 * publish call throws, the advertiser degrades to a silent no-op and logs one
 * warning. Discovery is a convenience, never a boot dependency.
 *
 * @module dsh-gateway/lib/mdns
 */

import { hostname } from 'node:os'

/** Bonjour service type used by the APK (`_dshgw._tcp`). */
export const SERVICE_TYPE = 'dshgw'

/** DNS label sanitizer for the instance name. */
function serviceInstanceName(name) {
  const raw = typeof name === 'string' && name.trim() !== '' ? name.trim() : hostname()
  const label = raw
    .replace(/[^\p{L}\p{N}-]+/gu, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 60)
  return label === '' ? 'dsh-gateway' : label
}

/**
 * MdnsAdvertiser — best-effort `_dshgw._tcp` publisher.
 */
export class MdnsAdvertiser {
  /**
   * @param {object} options
   * @param {string} [options.name] - display name (hostname by default).
   * @param {number} options.port - LAN HTTP port to advertise (0 = do not advertise).
   * @param {number} [options.pairPort] - public HTTPS port (0 when disabled).
   * @param {{info?:Function,warn?:Function,error?:Function,debug?:Function}} [options.logger]
   * @param {string} [options.api] - REST base path advertised in TXT.
   */
  constructor({ name, port, pairPort = 0, logger, api = '/v1' } = {}) {
    this.name = typeof name === 'string' && name.trim() !== '' ? name.trim() : hostname()
    this.port = Number(port) || 0
    this.pairPort = Number(pairPort) || 0
    this.api = typeof api === 'string' && api.startsWith('/') ? api : '/v1'
    this.logger = logger ?? {}
    /** @type {import('bonjour-service').Bonjour|null} */
    this.bonjour = null
    /** @type {import('bonjour-service').Service|null} */
    this.service = null
    this.failed = false
    this.stopped = false
    this.instanceName = serviceInstanceName(this.name)
    this.startedAt = 0
  }

  /** Whether a service record is currently published. */
  get advertising() {
    return this.service !== null
  }

  /** TXT record advertised with the service. */
  txtRecord() {
    return {
      v: '1',
      name: this.name,
      lan: String(this.port),
      pair: String(this.pairPort || 0),
      api: this.api,
    }
  }

  #serviceConfig() {
    return {
      name: this.instanceName,
      type: SERVICE_TYPE,
      protocol: 'tcp',
      port: this.port,
      txt: this.txtRecord(),
    }
  }

  /**
   * Start advertising. Idempotent; failures are absorbed (no-op mode).
   * @returns {Promise<boolean>} whether a service record is published.
   */
  async start() {
    this.stopped = false
    if (this.port <= 0) {
      this.logger.debug?.('mDNS skipped: no LAN port to advertise')
      return false
    }
    if (this.service !== null) return true
    this.failed = false
    try {
      const module = await import('bonjour-service')
      const Bonjour = module?.Bonjour ?? module?.default?.Bonjour ?? module?.default
      if (typeof Bonjour !== 'function') throw new Error('bonjour-service does not export Bonjour')
      this.bonjour = new Bonjour()
      this.bonjour.on?.('error', (error) => {
        this.logger.warn?.(`mDNS error: ${error?.message ?? error}`)
      })
      this.service = this.bonjour.publish(this.#serviceConfig())
      this.service.on?.('error', (error) => {
        this.logger.warn?.(`mDNS service error: ${error?.message ?? error}`)
      })
      this.service.on?.('up', () => {
        this.logger.info?.(`mDNS advertising _${SERVICE_TYPE}._tcp as "${this.instanceName}" port=${this.port}`)
      })
      this.startedAt = Date.now()
      return true
    } catch (error) {
      this.failed = true
      this.logger.warn?.(`mDNS unavailable, discovery disabled: ${error?.message ?? error}`)
      await this.#teardown()
      return false
    }
  }

  /**
   * Re-publish with updated ports / name (used after a listener restart).
   * @param {{name?:string, port?:number, pairPort?:number}} [patch]
   */
  async refresh(patch = {}) {
    if (typeof patch.name === 'string' && patch.name.trim() !== '') {
      this.name = patch.name.trim()
      this.instanceName = serviceInstanceName(this.name)
    }
    if (Number.isFinite(patch.port)) this.port = Math.max(0, Math.trunc(patch.port))
    if (Number.isFinite(patch.pairPort)) this.pairPort = Math.max(0, Math.trunc(patch.pairPort))
    if (this.stopped) return false
    if (this.service === null && this.port <= 0) return false
    try {
      await this.#teardown()
      return await this.start()
    } catch (error) {
      this.logger.warn?.(`mDNS refresh failed: ${error?.message ?? error}`)
      return false
    }
  }

  /** Stop advertising. Never throws. */
  async stop() {
    this.stopped = true
    await this.#teardown()
  }

  /** Alias kept for symmetry with other modules. */
  async close() {
    await this.stop()
  }

  async #teardown() {
    const service = this.service
    const bonjour = this.bonjour
    this.service = null
    this.bonjour = null
    try {
      service?.stop?.()
    } catch {
      // best effort
    }
    try {
      bonjour?.unpublishAll?.()
    } catch {
      // best effort
    }
    try {
      bonjour?.destroy?.()
    } catch {
      // best effort
    }
  }
}

export default MdnsAdvertiser
