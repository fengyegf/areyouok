/**
 * `lib/tls.js`: self-signed certificate material for the public HTTPS edge.
 *
 * Asserts the properties the APK depends on: the certificate is persisted and
 * reused across restarts (stable fingerprint), the fingerprint is the SHA-256
 * of the DER bytes, broken material is regenerated, and the produced key/cert
 * can actually terminate a TLS connection.
 *
 * @module dsh-gateway/test/tls.test
 */

import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { readFileSync, writeFileSync } from 'node:fs'
import { createServer as createHttpsServer } from 'node:https'
import { join } from 'node:path'
import { after, before, describe, it } from 'node:test'

import { createResources, freePort, libStatus, makeTempDir } from './helper.mjs'

const status = await libStatus()
const missing = status.missing.includes('tls.js') ? ['tls.js'] : []
const reason = missing.length === 0 ? undefined : `lib modules not implemented yet: ${missing.join(', ')}`

describe('tls.js', { skip: reason }, async () => {
  const tlsModule = await import('../lib/tls.js')
  const {
    ensureTlsMaterial,
    fingerprintOfPem,
    formatFingerprint,
    localSubjectAltNames,
    pemToDer,
  } = tlsModule

  /** @type {ReturnType<typeof createResources>} */
  let resources
  /** @type {string} */
  let dir
  const logger = { info() {}, warn() {}, error() {}, debug() {} }

  before(async () => {
    resources = createResources()
    dir = await makeTempDir(resources)
  })

  after(async () => {
    await resources.disposeAll()
  })

  const subDir = () => join(dir, `tls-${Math.random().toString(16).slice(2)}`)

  it('generates and persists a self-signed certificate', async () => {
    const target = subDir()
    const material = await ensureTlsMaterial(target, logger)
    assert.ok(material, 'material should be generated')
    assert.equal(material.created, true)
    assert.match(material.fingerprint, /^[0-9a-f]{64}$/)
    assert.equal(material.certPath, join(target, 'tls', 'cert.pem'))
    assert.equal(material.keyPath, join(target, 'tls', 'key.pem'))
    assert.match(material.cert, /-----BEGIN CERTIFICATE-----/)
    assert.match(material.key, /-----BEGIN (RSA )?PRIVATE KEY-----/)
    assert.equal(readFileSync(material.certPath, 'utf8'), material.cert)
    assert.equal(readFileSync(material.keyPath, 'utf8'), material.key)
  })

  it('reuses the stored material with a stable fingerprint', async () => {
    const target = subDir()
    const first = await ensureTlsMaterial(target, logger)
    const second = await ensureTlsMaterial(target, logger)
    assert.equal(first.created, true)
    assert.equal(second.created, false)
    assert.equal(second.fingerprint, first.fingerprint)
    assert.equal(second.cert, first.cert)
    assert.equal(second.key, first.key)
  })

  it('computes the fingerprint from the DER bytes', async () => {
    const target = subDir()
    const material = await ensureTlsMaterial(target, logger)
    const der = pemToDer(material.cert)
    assert.ok(Buffer.isBuffer(der))
    const expected = createHash('sha256').update(der).digest('hex')
    assert.equal(fingerprintOfPem(material.cert), expected)
    assert.equal(material.fingerprint, expected)
    assert.equal(der[0], 0x30, 'DER should start with a SEQUENCE tag')
    assert.match(formatFingerprint(expected), /^([0-9A-F]{2}:){31}[0-9A-F]{2}$/)
    assert.equal(formatFingerprint(''), '')
  })

  it('regenerates after corrupt stored material', async () => {
    const target = subDir()
    const first = await ensureTlsMaterial(target, logger)
    writeFileSync(first.certPath, 'not a certificate', 'utf8')
    const second = await ensureTlsMaterial(target, logger)
    assert.ok(second)
    assert.equal(second.created, true)
    assert.notEqual(second.fingerprint, first.fingerprint)
    assert.match(readFileSync(second.certPath, 'utf8'), /BEGIN CERTIFICATE/)
  })

  it('regenerates a mismatched key/cert pair', async () => {
    const target = subDir()
    const first = await ensureTlsMaterial(target, logger)
    const other = await ensureTlsMaterial(subDir(), logger)
    // Cert from `first`, key from `other`: cannot form a secure context.
    writeFileSync(first.keyPath, other.key, 'utf8')
    const repaired = await ensureTlsMaterial(target, logger)
    assert.equal(repaired.created, true)
    assert.match(repaired.cert, /BEGIN CERTIFICATE/)
  })

  it('stays usable when the data directory is under a missing parent', async () => {
    const target = join(subDir(), 'nested', 'deeper')
    const material = await ensureTlsMaterial(target, logger)
    assert.ok(material)
    assert.equal(material.created, true)
  })

  it('lists localhost and loopback SANs', () => {
    const san = localSubjectAltNames('test-machine')
    assert.ok(san.dns.includes('localhost'))
    assert.ok(san.dns.includes('test-machine'))
    assert.ok(san.ips.includes('127.0.0.1'))
    assert.ok(san.ips.includes('::1'))
  })

  it('terminates a real TLS connection', async () => {
    const material = await ensureTlsMaterial(subDir(), logger)
    const server = createHttpsServer({ key: material.key, cert: material.cert }, (req, res) => {
      res.writeHead(200, { 'content-type': 'application/json' })
      res.end(JSON.stringify({ ok: true, url: req.url }))
    })
    const port = await freePort()
    await new Promise((resolve, reject) => {
      server.once('error', reject)
      server.listen(port, '127.0.0.1', resolve)
    })
    try {
      const { secureRequest } = await import('./helper.mjs')
      const response = await secureRequest({ port, path: '/tls-check' })
      assert.equal(response.status, 200)
      assert.deepEqual(response.json, { ok: true, url: '/tls-check' })
    } finally {
      server.closeAllConnections?.()
      await new Promise((resolve) => server.close(() => resolve()))
    }
  })
})
