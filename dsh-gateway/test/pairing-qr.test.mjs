/**
 * Pairing QR: LAN vs public edge.
 *
 * The public code is the only place where the gateway's self-signed certificate
 * travels to the phone. These tests pin the properties the APK depends on:
 *
 *   - the LAN code stays plain HTTP and carries no certificate,
 *   - the public code carries `mode=pub`, the public URL and the exact DER
 *     bytes whose SHA-256 the APK pins (base64url, no PEM armour),
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

describe('pairing QR modes', () => {
  it('keeps the LAN code plain and certificate-free', async () => {
    const view = await buildPairingView({ pairing: PAIRING, name: 'FY-PC' })
    assert.equal(view.mode, 'lan')
    assert.equal(view.url, 'http://192.168.1.5:7788')
    assert.equal(view.certificate, null)
    assert.ok(view.uri.startsWith('dshgw://pair?url=http%3A%2F%2F192.168.1.5%3A7788'))
    assert.equal(view.uri.includes('cert='), false)
    assert.equal(view.uri.includes('mode=pub'), false)
    assert.ok(view.qr.size >= 21)
    assert.equal(Buffer.from(view.qr.data, 'base64').length, view.qr.size * view.qr.size)
  })

  it('embeds the certificate bytes in the public code', async () => {
    const dir = await mkdtemp(join(tmpdir(), 'dshgw-qr-'))
    try {
      const material = await ensureTlsMaterial(dir, {})
      assert.ok(material !== null)
      const certificate = certificateBase64Url(material.cert)
      assert.ok(certificate !== null)

      // The certificate the QR carries is byte-identical to the DER whose
      // fingerprint the gateway advertises — that equality is the pin.
      assert.equal(
        createHash('sha256').update(Buffer.from(certificate, 'base64url')).digest('hex'),
        material.fingerprint,
      )

      const view = await buildPairingView({
        pairing: PAIRING,
        name: 'FY-PC',
        mode: 'public',
        certificate,
        fingerprint: material.fingerprint,
      })
      assert.equal(view.mode, 'public')
      assert.equal(view.url, 'https://[2408:8001:abcd::1]:7789')
      assert.equal(view.certificate, certificate)

      const params = new URLSearchParams(view.uri.slice(view.uri.indexOf('?') + 1))
      assert.equal(params.get('mode'), 'pub')
      assert.equal(params.get('cert'), certificate)
      assert.equal(params.get('fp'), material.fingerprint)
      assert.equal(params.get('pub'), 'https://[2408:8001:abcd::1]:7789')

      // A long payload must still produce a matrix (level L fallback).
      assert.ok(view.qr !== null)
      assert.equal(Buffer.from(view.qr.data, 'base64').length, view.qr.size * view.qr.size)
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
    const pub = pairingUri({ url: 'https://gw:7789', code: 'C', mode: 'public', certificate: 'AAAA' })
    assert.ok(pub.includes('mode=pub'))
    assert.ok(pub.includes('cert=AAAA'))
  })
})
