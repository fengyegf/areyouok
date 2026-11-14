/**
 * Presentation helpers for the gateway's in-app panel.
 *
 * The gateway used to serve a standalone `/admin` HTML page from its own LAN
 * listener. That page is gone: the same status / pairing / device view now
 * renders inside the DSH Web UI as the plugin's configuration page, and this
 * module produces the JSON that page consumes. Nothing here renders HTML or
 * performs I/O of its own — the browser half (`lib/client.js`) owns all markup
 * so the view inherits the host's theme, locale and layout.
 *
 * Shapes are plain JSON because they cross the HTTP edge:
 *   - {@link buildState}: the whole panel snapshot (`GET …/state`),
 *   - {@link buildPairingView}: one pairing code plus its QR code,
 *   - {@link pairingUri}: the `dshgw://pair` deep link the APK scans.
 *
 * @module dsh-gateway/lib/panel
 */

import { networkInterfaces } from 'node:os'
import QRCode from 'qrcode'
import { classifyAddress } from './util.js'

/**
 * Enumerate this machine's addresses, split into LAN and public buckets.
 *
 * LAN means "private/link-local/ULA and therefore reachable over plain HTTP
 * without a certificate". Link-local IPv6 is skipped on purpose: a URL needs a
 * zone id to use it, so phones cannot reach it.
 * @returns {{lan:string[], public:string[], all:string[]}}
 */
export function enumerateAddresses() {
  const lan = []
  const publicList = []
  let interfaces = {}
  try {
    interfaces = networkInterfaces()
  } catch {
    interfaces = {}
  }
  const seenLan = new Set()
  const seenPublic = new Set()
  for (const addresses of Object.values(interfaces)) {
    for (const entry of addresses ?? []) {
      if (entry === null || typeof entry !== 'object' || entry.internal) continue
      const raw = String(entry.address ?? '')
      const address = raw.split('%')[0].toLowerCase()
      if (address === '' || address === '0.0.0.0' || address === '::') continue
      // fe80::/10 link-local needs an interface zone to form a usable URL.
      if (/^fe[89ab][0-9a-f]:/.test(address)) continue
      const kind = classifyAddress(address)
      if (kind === 'loopback') continue
      if (kind === 'lan') {
        if (!seenLan.has(address)) {
          seenLan.add(address)
          lan.push(address)
        }
      } else if (!seenPublic.has(address)) {
        seenPublic.add(address)
        publicList.push(address)
      }
    }
  }
  // Order candidates by usefulness for a phone on the same network. Home Wi-Fi
  // (192.168/16) almost always wins; 10/8 is often a real office LAN while
  // 172.16/12 is frequently a WSL/Hyper-V/Docker virtual switch; CGNAT
  // (100.64/10, Tailscale) and IPv6 last.
  const rank = (address) => {
    if (!address.includes(':')) {
      const [a, b] = address.split('.').map((part) => Number(part))
      if (a === 192 && b === 168) return 0
      if (a === 10) return 1
      if (a === 172 && b >= 16 && b <= 31) return 2
      if (a === 100 && b >= 64 && b <= 127) return 3
      return 4
    }
    if (/^f[cd][0-9a-f]{2}:/.test(address)) return 5
    return 6
  }
  lan.sort((left, right) => rank(left) - rank(right) || left.localeCompare(right))
  publicList.sort((left, right) => rank(left) - rank(right) || left.localeCompare(right))
  return { lan, public: publicList, all: [...lan, ...publicList] }
}

/** Format an address for use inside a URL (IPv6 wrapped in brackets). */
export function formatHost(address) {
  const value = String(address ?? '')
  if (value.includes(':')) return `[${value}]`
  return value
}

/**
 * Normalize a pairing-edge selector to `'lan'` or `'public'`.
 *
 * The panel sends `'lan' | 'public'`; older callers send nothing. Accept the
 * shorthands a human might type in a query string too, and never throw: an
 * unknown value means "this machine's default edge".
 * @param {unknown} value
 * @param {'lan'|'public'} [fallback]
 * @returns {'lan'|'public'}
 */
export function normalizePairingMode(value, fallback = 'lan') {
  if (typeof value !== 'string') return fallback
  const mode = value.trim().toLowerCase()
  if (mode === 'public' || mode === 'pub' || mode === 'https' || mode === 'wss') return 'public'
  if (mode === 'lan' || mode === 'local' || mode === 'http') return 'lan'
  return fallback
}

/**
 * Build the `dshgw://pair?…` deep link scanned by the APK.
 *
 * Two shapes exist, one per edge:
 *
 *   - LAN: plain `http://…` — no certificate and no token needed; `mode` is
 *     omitted so an older APK keeps its LAN-first preference,
 *   - public: `https://…` plus `mode=pub` and `cert=<base64url DER>`. The APK
 *     pins that exact certificate out of the QR, which is what lets it trust a
 *     self-signed gateway without installing anything into the system store.
 *
 * `url` stays the primary/first choice for older clients.
 */
export function pairingUri({ url, lan, pub, code, fingerprint, name, mode, certificate } = {}) {
  const params = new URLSearchParams()
  if (typeof url === 'string' && url !== '') params.set('url', url)
  if (typeof lan === 'string' && lan !== '') params.set('lan', lan)
  if (typeof pub === 'string' && pub !== '') params.set('pub', pub)
  if (typeof code === 'string' && code !== '') params.set('code', code)
  if (typeof fingerprint === 'string' && fingerprint !== '') params.set('fp', fingerprint)
  if (normalizePairingMode(mode) === 'public') params.set('mode', 'pub')
  if (typeof certificate === 'string' && certificate !== '') params.set('cert', certificate)
  params.set('n', typeof name === 'string' && name !== '' ? name : 'dsh-gateway')
  return `dshgw://pair?${params.toString()}`
}

/**
 * Encode a QR code as a flat matrix for the panel.
 *
 * `qrcode` exposes one byte per module (`1` dark, `0` light) in row-major order;
 * the panel decodes that byte string and draws it as SVG `<rect>` runs. An
 * `<img>` data URL (or markup injected as HTML) would depend on whatever
 * `img-src` the host page's CSP declares, while a matrix the component already
 * owns always renders — and it inherits the theme instead of pinning colours.
 * @param {string} text
 * @returns {{size:number, data:string}|null} row-major module values, base64 encoded.
 */
function encodeQrMatrix(text, preferredLevel = 'M') {
  /* A public link carries the whole certificate (~1.3 kB of base64url), which is
   * far more than a LAN link. Level M can run out of versions there, so fall
   * back to L: the code is scanned from a bright screen at close range, and a
   * slightly denser, scannable code beats a blank panel. */
  const levels = preferredLevel === 'M' ? ['M', 'L'] : [preferredLevel, 'L']
  for (const level of levels) {
    try {
      const created = QRCode.create(text, { errorCorrectionLevel: level })
      const { size, data } = created.modules
      return { size, data: Buffer.from(data).toString('base64') }
    } catch {
      // Try the next error-correction level (or give up to the empty state).
    }
  }
  return null
}

/**
 * Build the presentation object for one pairing code, including its QR code.
 *
 * The default edge is LAN plain HTTP: a phone on the same network then connects
 * without any TLS certificate, pairing code or device token. Asking for
 * `mode: 'public'` swaps the QR over to the HTTPS edge and embeds the
 * certificate (`certificate`, base64url DER) so the APK can pin it directly.
 *
 * The requested edge only wins when it actually has a URL; a machine without a
 * public address still produces a usable LAN code instead of an empty matrix.
 * @param {{pairing:object|null, fingerprint?:string, name?:string, mode?:string, certificate?:string}} input
 * @returns {Promise<object|null>}
 */
export async function buildPairingView({ pairing, fingerprint = '', name = '', mode = '', certificate = '' } = {}) {
  if (pairing === null || typeof pairing !== 'object' || typeof pairing.code !== 'string') return null
  const urls = (Array.isArray(pairing.urls) ? pairing.urls : [])
    .filter((url) => typeof url === 'string' && url.trim() !== '')
    .map((url) => url.trim())
  const lanUrls = urls.filter((url) => /^http:\/\//i.test(url))
  const publicUrls = urls.filter((url) => /^https:\/\//i.test(url))
  const lanUrl = typeof pairing.lanUrl === 'string' && pairing.lanUrl !== '' ? pairing.lanUrl : (lanUrls[0] ?? '')
  const publicUrl = typeof pairing.publicUrl === 'string' && pairing.publicUrl !== '' ? pairing.publicUrl : (publicUrls[0] ?? '')
  const requested = normalizePairingMode(
    typeof mode === 'string' && mode !== '' ? mode : pairing.mode,
    lanUrl !== '' ? 'lan' : 'public',
  )
  const selectedMode = requested === 'public' && publicUrl !== '' ? 'public' : 'lan'
  const url = selectedMode === 'public'
    ? publicUrl
    : (lanUrl !== '' ? lanUrl : (publicUrl !== '' ? publicUrl : (urls[0] ?? '')))
  const fp = typeof pairing.fingerprint === 'string' && pairing.fingerprint !== '' ? pairing.fingerprint : fingerprint
  const cert = selectedMode === 'public' && typeof certificate === 'string' && certificate !== ''
    ? certificate
    : ''
  const uri = url === ''
    ? null
    : pairingUri({
      url,
      lan: lanUrl !== '' ? lanUrl : undefined,
      pub: publicUrl !== '' ? publicUrl : undefined,
      code: pairing.code,
      fingerprint: fp,
      name,
      mode: selectedMode,
      certificate: cert,
    })
  return {
    code: pairing.code,
    createdAt: pairing.createdAt ?? null,
    expiresAt: pairing.expiresAt ?? null,
    ttlMs: pairing.ttlMs ?? null,
    mode: selectedMode,
    urls,
    lanUrls,
    publicUrls,
    lanUrl: lanUrl === '' ? null : lanUrl,
    publicUrl: publicUrl === '' ? null : publicUrl,
    url,
    fingerprint: fp || null,
    certificate: cert === '' ? null : cert,
    uri,
    qr: uri === null ? null : encodeQrMatrix(uri, cert === '' ? 'M' : 'L'),
  }
}

/**
 * Build the JSON payload served by `GET /plugins/dsh-gateway/state`.
 * @param {object} input
 * @returns {Promise<object>}
 */
export async function buildState({
  name = 'dsh-gateway',
  version = '0.2.0',
  fingerprint = '',
  fingerprintPretty = '',
  ports = { lan: 0, public: 0 },
  mdns = { enabled: false, active: false },
  dsh = { connected: false, clientId: null },
  devices = [],
  pairing = null,
  config = {},
  mode = '',
  certificate = '',
  publicUrls = [],
  lanUrls = [],
  uptimeMs = 0,
  lastError = '',
  serverTime = Date.now(),
} = {}) {
  const pairingView = await buildPairingView({ pairing, fingerprint, name, mode, certificate })
  return {
    ok: true,
    name,
    version,
    fingerprint,
    fingerprintPretty,
    ports: { lan: Number(ports?.lan) || 0, public: Number(ports?.public) || 0 },
    mdns: { enabled: Boolean(mdns?.enabled), active: Boolean(mdns?.active) },
    dsh: { connected: Boolean(dsh?.connected), clientId: dsh?.clientId ?? null },
    devices: Array.isArray(devices) ? devices : [],
    pairing: pairingView,
    config: {
      host: typeof config.host === 'string' ? config.host : '',
      httpPort: config.httpPort ?? 0,
      httpsPort: config.httpsPort ?? 0,
      lanRequirePairing: Boolean(config.lanRequirePairing),
      mdns: Boolean(config.mdns),
      maxDevices: config.maxDevices ?? 4,
      publicUrl: typeof config.publicUrl === 'string' ? config.publicUrl : '',
    },
    publicUrls: Array.isArray(publicUrls) ? publicUrls : [],
    lanUrls: Array.isArray(lanUrls) ? lanUrls : [],
    uptimeMs: Number(uptimeMs) || 0,
    lastError: typeof lastError === 'string' ? lastError : '',
    serverTime: Number(serverTime) || Date.now(),
  }
}

export default { enumerateAddresses, formatHost, normalizePairingMode, pairingUri, buildPairingView, buildState }
