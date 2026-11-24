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
 * Whether one IPv4 address can be advertised as somebody's gateway.
 *
 * A gateway URL is only useful when the phone can actually dial it, and some
 * adapters that *look* like public interfaces are virtual. The important one is
 * `198.18.0.0/15` (RFC 2544 benchmarking): Clash/Mihomo-style proxies hand it to
 * their TUN adapter as the fake-ip gateway (`198.18.0.1`). That adapter appears a
 * few seconds after boot, used to win the public-address ranking over the real
 * IPv6 address, and ended up in the pairing QR — where a phone running the same
 * kind of proxy dials its *own* tunnel instead of this gateway and dies in a TLS
 * handshake that looks exactly like a fingerprint mismatch.
 * @param {string} address - lowercase dotted IPv4.
 * @returns {boolean}
 */
function isAdvertisableIPv4(address) {
  const octets = String(address).split('.').map((part) => Number(part))
  if (octets.length !== 4) return false
  if (octets.some((octet) => !Number.isInteger(octet) || octet < 0 || octet > 255)) return false
  const [a, b, c] = octets
  if (a === 0) return false // 0.0.0.0/8 "this network"
  if (a === 127) return false // loopback is never enumerated; kept for symmetry
  if (a === 169 && b === 254) return false // link-local, needs a scope id
  if (a === 192 && b === 0 && (c === 0 || c === 2)) return false // 192.0.0/24, TEST-NET-1
  if (a === 198 && (b === 18 || b === 19)) return false // 198.18.0.0/15 — proxy TUN fake-ip
  if (a === 198 && b === 51 && c === 100) return false // TEST-NET-2
  if (a === 203 && b === 0 && c === 113) return false // TEST-NET-3
  if (a >= 224) return false // multicast (224/4) and reserved (240/4)
  return true
}

/**
 * Whether one IPv6 address can be advertised as somebody's gateway.
 *
 * Link-local needs an interface zone to form a URL, Teredo (`2001:0::/32`) is a
 * NAT-traversal tunnel whose address is not directly dialable, and the
 * documentation prefix never belongs to a real host.
 * @param {string} address - lowercase IPv6 literal, no zone id.
 * @returns {boolean}
 */
function isAdvertisableIPv6(address) {
  const value = String(address)
  if (value === '::' || value === '::1') return false
  if (value.startsWith('::ffff:')) return false // IPv4-mapped: classified as IPv4 anyway
  if (/^fe[89ab][0-9a-f]:/.test(value)) return false // fe80::/10 link-local
  if (/^2001:0:/.test(value)) return false // Teredo 2001:0::/32 (also matches 2001:0000:)
  if (/^2001:db8:/.test(value)) return false // documentation prefix
  return true
}

/** Whether an unscoped, lowercased address may appear in a pairing URL. */
function isAdvertisable(address) {
  return address.includes(':') ? isAdvertisableIPv6(address) : isAdvertisableIPv4(address)
}

/**
 * Enumerate this machine's addresses, split into LAN and public buckets.
 *
 * LAN means "private/link-local/ULA and therefore reachable over plain HTTP
 * without a certificate". Addresses that a phone cannot dial at all (virtual
 * TUN/fake-ip ranges, tunnels, benchmark and documentation prefixes) are dropped
 * instead of being advertised: a wrong public URL is worse than no public URL,
 * because the QR looks valid while every connection attempt fails.
 * @param {Record<string, Array<object>|undefined>} [interfaces] - `os.networkInterfaces()` seam for tests.
 * @returns {{lan:string[], public:string[], all:string[]}}
 */
export function enumerateAddresses(interfaces = networkInterfaces()) {
  const lan = []
  const publicList = []
  let table = interfaces
  if (table === null || typeof table !== 'object') table = {}
  const seenLan = new Set()
  const seenPublic = new Set()
  for (const addresses of Object.values(table)) {
    for (const entry of addresses ?? []) {
      if (entry === null || typeof entry !== 'object' || entry.internal) continue
      const raw = String(entry.address ?? '')
      const address = raw.split('%')[0].toLowerCase()
      if (address === '') continue
      if (!isAdvertisable(address)) continue
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
  //
  // The public bucket ranks the other way round: a global IPv6 address is the
  // address a phone on mobile data can actually reach, so it must come before
  // any stray public-looking IPv4 (which is why it is rank 0, not rank 6).
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
    return 0
  }
  lan.sort((left, right) => rank(left) - rank(right) || left.localeCompare(right))
  publicList.sort((left, right) => rank(left) - rank(right) || left.localeCompare(right))
  return { lan, public: publicList, all: [...lan, ...publicList] }
}

/**
 * Keep addresses already advertised in front, so the pairing view does not flip
 * between interfaces as adapters come and go (a Wi-Fi reconnect, a proxy TUN
 * appearing, a rotating IPv6 privacy address). New addresses are appended.
 * @param {string[]} addresses - freshly enumerated addresses, already ranked.
 * @param {string[]} [previous] - addresses advertised on the previous pass.
 * @returns {string[]} same members, previous ones first.
 */
export function preferStable(addresses, previous = []) {
  const list = Array.isArray(addresses) ? addresses.filter((value) => typeof value === 'string') : []
  if (list.length < 2) return list
  const keep = new Set(Array.isArray(previous) ? previous : [])
  if (keep.size === 0) return list
  return [...list.filter((address) => keep.has(address)), ...list.filter((address) => !keep.has(address))]
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
 * `mode: 'public'` swaps the QR over to the HTTPS edge and carries the
 * certificate's SHA-256 fingerprint (`fp`), which is all the APK needs to
 * pin the self-signed certificate. The DER body is embedded only when
 * `embedCertificate` is set: it is ~1.4 kB of base64url and turns a
 * 57-module code into a 141-module one (~2 px per module in the 290 px panel),
 * which phone cameras cannot read reliably.
 *
 * The requested edge only wins when it actually has a URL; a machine without a
 * public address still produces a usable LAN code instead of an empty matrix.
 * @param {{pairing:object|null, fingerprint?:string, name?:string, mode?:string, certificate?:string, embedCertificate?:boolean}} input
 * @returns {Promise<object|null>}
 */
export async function buildPairingView({
  pairing,
  fingerprint = '',
  name = '',
  mode = '',
  certificate = '',
  embedCertificate = false,
} = {}) {
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
  /* fp already pins the certificate: the APK compares the SHA-256 of the
   * certificate it receives during the handshake with this value, so shipping the
   * DER too only makes the code denser. Keep the escape hatch for clients that
   * cannot hash, and for anyone debugging a certificate mismatch. */
  const cert = embedCertificate === true && selectedMode === 'public' &&
    typeof certificate === 'string' && certificate !== ''
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
    certificateEmbedded: cert !== '',
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
  embedCertificate = false,
  publicUrls = [],
  lanUrls = [],
  mtls = null,
  uptimeMs = 0,
  lastError = '',
  serverTime = Date.now(),
} = {}) {
  const pairingView = await buildPairingView({ pairing, fingerprint, name, mode, certificate, embedCertificate })
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
      qrEmbedCertificate: Boolean(config.qrEmbedCertificate),
    },
    // 公网 mTLS 摘要（契约 §5.2）：没有 PKI 时为 null，字段仍然显式存在。
    mtls: mtls === null || typeof mtls !== 'object'
      ? null
      : {
          enforced: Boolean(mtls.enforced),
          caFingerprint: typeof mtls.caFingerprint === 'string' ? mtls.caFingerprint : '',
          caFingerprintPretty: typeof mtls.caFingerprintPretty === 'string' ? mtls.caFingerprintPretty : '',
          certificateCount: Number(mtls.certificateCount) || 0,
          revokedCount: Number(mtls.revokedCount) || 0,
        },
    publicUrls: Array.isArray(publicUrls) ? publicUrls : [],
    lanUrls: Array.isArray(lanUrls) ? lanUrls : [],
    uptimeMs: Number(uptimeMs) || 0,
    lastError: typeof lastError === 'string' ? lastError : '',
    serverTime: Number(serverTime) || Date.now(),
  }
}

export default {
  enumerateAddresses,
  preferStable,
  formatHost,
  normalizePairingMode,
  pairingUri,
  buildPairingView,
  buildState,
}
