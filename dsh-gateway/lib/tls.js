/**
 * Self-signed TLS material for the public HTTPS edge (`:7789`).
 *
 * The APK pins the certificate SHA-256 fingerprint that is embedded in the
 * pairing QR code, so the certificate must stay stable across restarts: the
 * generated key/cert pair is persisted under `$DSH_HOME/dsh-gateway/tls/` and
 * only regenerated when the stored material is missing or broken.
 *
 * Responsibilities:
 *   - create the data directory lazily,
 *   - gather subjectAltName entries for localhost, the host name and every
 *     non-internal interface address (IPv4 + IPv6),
 *   - generate a self-signed certificate with `selfsigned`,
 *   - return `{ key, cert, fingerprint, created, certPath, keyPath }` where the
 *     fingerprint is the SHA-256 hex of the DER bytes (what the APK pins).
 *
 * Failure policy: TLS is optional infrastructure for this plugin. A broken
 * material directory is repaired by regenerating; an unrecoverable generation
 * failure is logged and reported as `null` so the LAN HTTP edge still boots.
 *
 * @module dsh-gateway/lib/tls
 */

import { createHash, X509Certificate } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { networkInterfaces, hostname } from 'node:os'
import { join } from 'node:path'
import { createSecureContext } from 'node:tls'
import selfsigned from 'selfsigned'
import { ensureDir, fileExists, writeFileAtomic } from './util.js'

/** Certificate lifetime: long-lived on purpose (rotation invalidates every paired device). */
const CERT_DAYS = 3650

/** RSA key size, fast enough to generate on first boot. */
const KEY_SIZE = 2048

/** DER bytes of a PEM certificate. */
export function pemToDer(pem) {
  if (typeof pem !== 'string') return null
  const match = /-----BEGIN CERTIFICATE-----([\s\S]+?)-----END CERTIFICATE-----/.exec(pem)
  if (match === null) return null
  try {
    return Buffer.from(match[1].replace(/[\s\r\n]+/g, ''), 'base64')
  } catch {
    return null
  }
}

/** SHA-256 hex fingerprint of a PEM certificate (matches what the APK pins). */
export function fingerprintOfPem(pem) {
  const der = pemToDer(pem)
  if (der === null || der.length === 0) return null
  return createHash('sha256').update(der).digest('hex')
}

/** Human readable fingerprint grouped as `AA:BB:…` for the admin page. */
export function formatFingerprint(fingerprint) {
  if (typeof fingerprint !== 'string' || fingerprint === '') return ''
  const pairs = fingerprint.toUpperCase().match(/.{2}/g) ?? []
  return pairs.join(':')
}

/**
 * Base64url of the DER bytes — the compact form that rides inside the QR code.
 *
 * PEM armour and its line breaks cost roughly 150 extra URL-encoded characters,
 * and the certificate is by far the longest field of a public pairing link. DER
 * is the smallest reversible encoding, and the APK only needs the bytes back to
 * pin the exact certificate the phone is about to trust.
 * @param {string} pem
 * @returns {string|null} base64url DER, or null when the PEM is unusable.
 */
export function certificateBase64Url(pem) {
  const der = pemToDer(pem)
  if (der === null || der.length === 0) return null
  return Buffer.from(der).toString('base64url')
}

/**
 * Collect subjectAltName descriptors for this machine.
 * @param {string} [commonName]
 * @returns {{ dns: string[], ips: string[] }}
 */
export function localSubjectAltNames(commonName = hostname()) {
  const dns = new Set()
  const ips = new Set(['127.0.0.1', '::1'])
  const base = String(commonName || '').trim()
  if (base !== '') {
    dns.add(base)
    dns.add('localhost')
    if (!base.includes('.')) dns.add(`${base}.local`)
  } else {
    dns.add('localhost')
  }
  let interfaces = {}
  try {
    interfaces = networkInterfaces()
  } catch {
    interfaces = {}
  }
  for (const addresses of Object.values(interfaces)) {
    for (const address of addresses ?? []) {
      if (address === null || typeof address !== 'object' || address.internal) continue
      const value = String(address.address ?? '').split('%')[0]
      if (value === '' || value === '0.0.0.0' || value === '::') continue
      if (address.family === 'IPv4' || address.family === 4 || value.includes('.')) {
        ips.add(value)
      } else if (value.includes(':')) {
        ips.add(value)
      }
    }
  }
  ips.add('127.0.0.1')
  ips.add('::1')
  return { dns: [...dns], ips: [...ips] }
}

/** Build the `selfsigned` extension set, including SAN. */
function buildExtensions(commonName) {
  const { dns, ips } = localSubjectAltNames(commonName)
  const altNames = []
  for (const value of dns) altNames.push({ type: 2, value })
  for (const value of ips) altNames.push({ type: 7, ip: value })
  return [
    { name: 'basicConstraints', cA: false },
    { name: 'keyUsage', digitalSignature: true, keyEncipherment: true },
    { name: 'extKeyUsage', serverAuth: true },
    { name: 'subjectAltName', altNames },
  ]
}

/** Validate that a stored key/cert pair is usable and matches itself. */
function loadUsableMaterial(cert, key) {
  if (typeof cert !== 'string' || typeof key !== 'string') return false
  if (cert.trim() === '' || key.trim() === '') return false
  try {
    // Throws when the certificate is malformed.
    const parsed = new X509Certificate(cert)
    if (parsed.ca === true) return false
    // Throws when key and certificate do not belong together.
    createSecureContext({ cert, key })
    return true
  } catch {
    return false
  }
}

/** Read a UTF-8 file without throwing. */
function readIfExists(file) {
  try {
    if (!fileExists(file)) return null
    return readFileSync(file, 'utf8')
  } catch {
    return null
  }
}

/**
 * Ensure persistent TLS material exists.
 *
 * @param {string} dir - gateway data directory (`$DSH_HOME/dsh-gateway`).
 * @param {{info?:Function,warn?:Function,error?:Function,debug?:Function}} [logger]
 * @param {{commonName?:string, force?:boolean}} [options]
 * @returns {Promise<{key:string,cert:string,fingerprint:string,created:boolean,certPath:string,keyPath:string}|null>}
 *   material package, or `null` when generation failed (HTTPS edge degrades).
 */
export async function ensureTlsMaterial(dir, logger, options = {}) {
  const commonName = typeof options.commonName === 'string' && options.commonName.trim() !== ''
    ? options.commonName.trim()
    : hostname()
  const tlsDir = join(dir, 'tls')
  const certPath = join(tlsDir, 'cert.pem')
  const keyPath = join(tlsDir, 'key.pem')

  ensureDir(tlsDir)
  if (!options.force) {
    const cert = readIfExists(certPath)
    const key = readIfExists(keyPath)
    if (cert !== null && key !== null && loadUsableMaterial(cert, key)) {
      const fingerprint = fingerprintOfPem(cert)
      if (fingerprint !== null) {
        return { key, cert, fingerprint, created: false, certPath, keyPath }
      }
    }
    if (cert !== null || key !== null) {
      logger?.warn?.('TLS material is incomplete or invalid; regenerating certificate')
    }
  }

  let pems
  try {
    pems = selfsigned.generate(
      [{ name: 'commonName', value: commonName }],
      {
        days: CERT_DAYS,
        keySize: KEY_SIZE,
        algorithm: 'sha256',
        extensions: buildExtensions(commonName),
      },
    )
  } catch (error) {
    logger?.error?.(`TLS certificate generation failed: ${error?.message ?? error}`)
    return null
  }

  const key = pems?.private
  const cert = pems?.cert
  if (typeof key !== 'string' || typeof cert !== 'string' || !loadUsableMaterial(cert, key)) {
    logger?.error?.('TLS certificate generation produced unusable material')
    return null
  }
  const fingerprint = fingerprintOfPem(cert)
  if (fingerprint === null) {
    logger?.error?.('TLS certificate fingerprint could not be computed')
    return null
  }

  try {
    writeFileAtomic(keyPath, key, 0o600)
    writeFileAtomic(certPath, cert, 0o644)
  } catch (error) {
    logger?.error?.(`TLS material could not be persisted: ${error?.message ?? error}`)
    return null
  }

  logger?.info?.(`generated self-signed TLS certificate fp=${formatFingerprint(fingerprint)}`)
  return { key, cert, fingerprint, created: true, certPath, keyPath }
}

export default {
  ensureTlsMaterial,
  fingerprintOfPem,
  formatFingerprint,
  certificateBase64Url,
  localSubjectAltNames,
  pemToDer,
}
