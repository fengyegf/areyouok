/**
 * Pairing QR: LAN vs public edge.
 *
 * The public code is where the gateway tells the phone which self-signed
 * certificate to pin. These tests pin the properties the APK depends on:
 *
 *   - the LAN code stays plain HTTP and carries no certificate,
 *   - the public code carries `mode=pub`, the public URL and the certificate's
 *     SHA-256 fingerprint (`fp`) — and, by default, NOT the ~1.4 kB DER body:
 *     that body turned a 57-module code into a 141-module one (~2 px per module
 *     in the 290 px panel), which is why phones could not read the public QR,
 *   - `embedCertificate: true` still produces the old body-carrying link for
 *     clients that cannot hash a certificate,
 *   - a machine without a public address still yields a LAN code rather than a
 *     blank matrix.
 *
 * @module dsh-gateway/test/pairing-qr.test
 */

import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { describe, it } from 'node:test'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

import { buildPairingView, normalizePairingMode, pairingUri } from '../lib/panel.js'
import { certificateBase64Url, ensureTlsMaterial } from '../lib/tls.js'

const PAIRING = {
  code: 'Code1234567890',
  createdAt: 1_700_000_000_000,
  expiresAt: 1_700_000_600_000,
  ttlMs: 600_000,
  urls: ['http://192.168.1.5:7788', 'https://[2408:8001:abcd::1]:7789'],
}

/** Parameters of one `dshgw://pair` link. */
function paramsOf(uri) {
  return new URLSearchParams(uri.slice(uri.indexOf('?') + 1))
}

describe('pairing QR modes', () => {
  it('keeps the LAN code plain and certificate-free', async () => {
    const view = await buildPairingView({ pairing: PAIRING, name: 'FY-PC' })
    assert.equal(view.mode, 'lan')
    assert.equal(view.url, 'http://192.168.1.5:7788')
    assert.equal(view.certificate, null)
    assert.equal(view.certificateEmbedded, false)
    assert.ok(view.uri.startsWith('dshgw://pair?url=http%3A%2F%2F192.168.1.5%3A7788'))
    assert.equal(view.uri.includes('cert='), false)
    assert.equal(view.uri.includes('mode=pub'), false)
    assert.ok(view.qr.size >= 21)
    assert.equal(Buffer.from(view.qr.data, 'base64').length, view.qr.size * view.qr.size)
  })

  it('pins the public certificate by fingerprint and keeps the code sparse', async () => {
    const dir = await mkdtemp(join(tmpdir(), 'dshgw-qr-'))
    try {
      const material = await ensureTlsMaterial(dir, {})
      assert.ok(material !== null)
      const fingerprint = material.fingerprint
      assert.equal(typeof fingerprint, 'string')
      assert.equal(fingerprint.length, 64)

      // The DER is still *available*; the default code simply does not carry it.
      const certificate = certificateBase64Url(material.cert)
      assert.ok(certificate !== null)
      assert.equal(
        createHash('sha256').update(Buffer.from(certificate, 'base64url')).digest('hex'),
        fingerprint,
        'the advertised fingerprint must be the SHA-256 of the DER the gateway serves',
      )

      const view = await buildPairingView({
        pairing: PAIRING,
        name: 'FY-PC',
        mode: 'public',
        certificate,
        fingerprint,
      })
      assert.equal(view.mode, 'public')
      assert.equal(view.url, 'https://[2408:8001:abcd::1]:7789')
      assert.equal(view.certificate, null, 'the DER body must not ride in the default QR')
      assert.equal(view.certificateEmbedded, false)

      const params = paramsOf(view.uri)
      assert.equal(params.get('mode'), 'pub')
      assert.equal(params.get('fp'), fingerprint)
      assert.equal(params.get('pub'), 'https://[2408:8001:abcd::1]:7789')
      assert.equal(params.get('cert'), null)

      // Version <= 15 (<= 77 modules) instead of version 31 (141 modules): a
      // phone can resolve every module at the panel's 290 px footprint.
      assert.ok(view.qr !== null)
      assert.ok(view.qr.size <= 77, `public QR must stay sparse, got ${view.qr.size} modules`)
      assert.equal(Buffer.from(view.qr.data, 'base64').length, view.qr.size * view.qr.size)
    } finally {
      await rm(dir, { recursive: true, force: true })
    }
  })

  it('can still embed the certificate body on request', async () => {
    const dir = await mkdtemp(join(tmpdir(), 'dshgw-qr-'))
    try {
      const material = await ensureTlsMaterial(dir, {})
      assert.ok(material !== null)
      const certificate = certificateBase64Url(material.cert)
      assert.ok(certificate !== null)
      const view = await buildPairingView({
        pairing: PAIRING,
        name: 'FY-PC',
        mode: 'public',
        certificate,
        fingerprint: material.fingerprint,
        embedCertificate: true,
      })
      assert.equal(view.certificate, certificate)
      assert.equal(view.certificateEmbedded, true)
      const params = paramsOf(view.uri)
      assert.equal(params.get('cert'), certificate)
      // The escape hatch is exactly why the default became fingerprint-only:
      // the code gets three times as wide.
      assert.ok(view.qr.size > 100, `embedded-certificate QR is dense, got ${view.qr.size}`)
    } finally {
      await rm(dir, { recursive: true, force: true })
    }
  })

  it('falls back to LAN when there is no public URL', async () => {
    const view = await buildPairingView({
      pairing: { code: 'LANONLY', urls: ['http://10.0.0.9:7788'] },
      mode: 'public',
      certificate: 'AAAA',
    })
    assert.equal(view.mode, 'lan')
    assert.equal(view.url, 'http://10.0.0.9:7788')
    assert.equal(view.certificate, null)
  })

  it('normalizes edge selectors and omits mode for LAN links', () => {
    assert.equal(normalizePairingMode('public'), 'public')
    assert.equal(normalizePairingMode('PUB'), 'public')
    assert.equal(normalizePairingMode('https'), 'public')
    assert.equal(normalizePairingMode('lan'), 'lan')
    assert.equal(normalizePairingMode(''), 'lan')
    assert.equal(normalizePairingMode('nope', 'public'), 'public')
    const lan = pairingUri({ url: 'http://10.0.0.9:7788', code: 'C' })
    assert.equal(lan.includes('mode='), false)
    const pub = pairingUri({ url: 'https://gw:7789', code: 'C', mode: 'public' })
    assert.ok(pub.includes('mode=pub'))
    assert.equal(pub.includes('cert='), false, 'no certificate body by default')
    // The link shape for an explicitly embedded body is unchanged.
    const legacy = pairingUri({ url: 'https://gw:7789', code: 'C', mode: 'public', certificate: 'AAAA' })
    assert.ok(legacy.includes('cert=AAAA'))
  })
})
