/**
 * 公网访问强制 mTLS 的端到端测试（契约 docs/dsh-gateway-mtls.md §10.1）。
 *
 * 覆盖：CA 生成/重启保持、LAN 领证链路、公网无证书握手失败、证书+令牌、
 * 公网禁止领证、双设备、撤销隔离、p12 导出连接、错误密码、设备撤销联动、
 * 续期自动撤销旧证书、限流、面板/健康状态字段、PKI 不可用时的降级。
 *
 * @module dsh-gateway/test/mtls
 */

import assert from 'node:assert/strict'
import { X509Certificate } from 'node:crypto'
import { readFile, stat, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { after, before, describe, it } from 'node:test'
import tls from 'node:tls'
import { MockDsh } from './mock-dsh.mjs'
import {
  createResources,
  enrollClient,
  freePort,
  libStatus,
  makeClientCsr,
  loadLib,
  makeLogger,
  makeTempDir,
  request,
  secureRequest,
  startGateway,
  StreamClient,
  waitForHealth,
} from './helper.mjs'

/** 本文件依赖的模块；缺失时整组 skip（并行开发期间）。 */
const REQUIRED = ['index.js', 'mtls.js', 'http.js', 'auth.js', 'ws.js']

const status = await libStatus()
const missing = REQUIRED.filter((name) => status.missing.includes(name))
const reason = missing.length === 0 ? undefined : 'lib modules not implemented yet: ' + missing.join(', ')

/** 无客户端证书 / 证书被撤销时，客户端只可能拿到传输层错误：断言没有 HTTP 状态码。 */
function noHttpStatus(error) {
  assert.equal(error?.statusCode, undefined, 'the client must not receive an HTTP response')
  return true
}

/** 客户端私钥指纹（用来断言两台设备的证书不是同一张）。 */
function fingerprintOf(pem) {
  return new X509Certificate(pem).fingerprint256
}

/** 独立启动一个网关（临时目录 + mock DSH），资源由 resources 统一收尾。 */
async function bootGateway(resources, options = {}) {
  const mock = new MockDsh()
  await mock.start()
  resources.add(() => mock.stop())
  const httpsPort = options.httpsPort ?? await freePort()
  const started = await startGateway({
    mock,
    resources,
    dir: options.dir,
    config: { httpsPort, host: '127.0.0.1', ...(options.config ?? {}) },
  })
  return { ...started, mock, httpsPort }
}

describe('public mTLS edge (full stack)', { skip: reason }, () => {
  const resources = createResources()
  /** @type {any} */
  let gateway = null
  let lanBase = ''
  let httpsPort = 0

  before(async () => {
    const started = await bootGateway(resources)
    gateway = started.gateway
    lanBase = started.lanBase
    httpsPort = started.httpsPort
    await waitForHealth(lanBase)
  })

  after(async () => {
    await resources.disposeAll()
  })

  /** 领证 + 配对（Bearer 令牌），返回可以连公网边的完整凭据。 */
  async function enrollAndPair(deviceId, deviceName) {
    const client = await enrollClient(lanBase, { deviceId, deviceName })
    const pairing = gateway.auth.createPairingCode(['https://127.0.0.1:' + httpsPort])
    const paired = await gateway.auth.pair({
      code: pairing.code,
      deviceName,
      deviceId,
      remoteAddress: '127.0.0.1',
    })
    return { client, token: paired.deviceToken }
  }

  it('serves the CA on the LAN edge and issues a chained client certificate', async () => {
    const caResponse = await request(lanBase, '/v1/pki/ca')
    assert.equal(caResponse.status, 200, caResponse.text)
    assert.equal(caResponse.json.ok, true)
    assert.match(caResponse.json.ca.fingerprint, /^[0-9a-f]{64}$/)
    assert.equal(caResponse.json.ca.fingerprint, gateway.pki.caFingerprint())
    const ca = new X509Certificate(caResponse.json.ca.certificate)
    assert.equal(ca.ca, true)

    const before = gateway.pki.list().length
    const client = await enrollClient(lanBase, { deviceId: 'mtls-chain', deviceName: 'Chain Phone' })
    assert.equal(gateway.pki.list().length, before + 1)
    const leaf = new X509Certificate(client.certificate)
    assert.equal(leaf.checkIssued(ca), true)
    assert.equal(leaf.verify(ca.publicKey), true)
    assert.equal(leaf.serialNumber, client.serial)
    assert.equal(client.json.caFingerprint, caResponse.json.ca.fingerprint)
    assert.ok(Array.isArray(leaf.keyUsage) && leaf.keyUsage.includes('1.3.6.1.5.5.7.3.2'), 'extKeyUsage must be clientAuth')
    assert.ok(client.notAfter > Date.now())
  })

  it('rejects a public connection without a client certificate (no HTTP status, A1)', async () => {
    await assert.rejects(secureRequest({ port: httpsPort, path: '/v1/health' }), noHttpStatus)
  })

  it('still requires the Bearer token when the certificate is valid (A2)', async () => {
    const client = await enrollClient(lanBase, { deviceId: 'mtls-token', deviceName: 'Token Phone' })
    const response = await secureRequest({ port: httpsPort, path: '/v1/health', clientCert: client })
    assert.equal(response.status, 401)
    assert.equal(response.json.error.code, 'unauthenticated')
  })

  it('admits REST and WebSocket traffic with certificate + token (A3)', async () => {
    const { client, token } = await enrollAndPair('mtls-admit', 'Admit Phone')
    const health = await secureRequest({ port: httpsPort, path: '/v1/health', token, clientCert: client })
    assert.equal(health.status, 200, health.text)
    assert.equal(health.json.ok, true)
    assert.equal(health.json.paired, true)
    assert.equal(health.json.mtls.required, true)
    assert.equal(health.json.mtls.caFingerprint, gateway.pki.caFingerprint())

    const stream = await StreamClient.connect('wss://127.0.0.1:' + httpsPort + '/v1/stream', {
      insecure: true,
      clientCert: client,
      headers: { authorization: 'Bearer ' + token },
      timeoutMs: 6_000,
    })
    const ready = await stream.waitForType('ready')
    assert.equal(typeof ready.seq, 'number')
    await stream.close()
  })

  it('never issues certificates on the public edge (A4)', async () => {
    const { client, token } = await enrollAndPair('mtls-public-enroll', 'Sneaky Phone')
    const before = gateway.pki.list().length
    const { csr } = await makeClientCsr({ commonName: 'sneaky-client' })
    const response = await secureRequest({
      port: httpsPort,
      path: '/v1/pki/enroll',
      method: 'POST',
      token,
      clientCert: client,
      body: { csr, deviceId: 'sneaky-device' },
    })
    assert.equal(response.status, 403)
    assert.equal(response.json.error.code, 'forbidden_source')
    const ca = await secureRequest({ port: httpsPort, path: '/v1/pki/ca', token, clientCert: client })
    assert.equal(ca.status, 403)
    assert.equal(ca.json.error.code, 'forbidden_source')
    assert.equal(gateway.pki.list().length, before, 'the registry must not grow')
  })

  it('gives two devices distinct certificates and both connect concurrently (A5)', async () => {
    const a = await enrollAndPair('mtls-dev-a', 'Phone A')
    const b = await enrollAndPair('mtls-dev-b', 'Phone B')
    assert.notEqual(a.client.serial, b.client.serial)
    assert.notEqual(fingerprintOf(a.client.certificate), fingerprintOf(b.client.certificate))
    const responses = await Promise.all([
      secureRequest({ port: httpsPort, path: '/v1/health', token: a.token, clientCert: a.client }),
      secureRequest({ port: httpsPort, path: '/v1/health', token: b.token, clientCert: b.client }),
    ])
    assert.deepEqual(responses.map((response) => response.status), [200, 200])
  })

  it('revokes one certificate without touching the others (A6)', async () => {
    const a = await enrollAndPair('mtls-revoke-a', 'Revoked Phone')
    const b = await enrollAndPair('mtls-revoke-b', 'Healthy Phone')
    assert.equal(gateway.api.panelRevokeCertificate(a.client.serial), true)
    assert.equal(gateway.api.panelRevokeCertificate(a.client.serial), false)
    await assert.rejects(
      secureRequest({ port: httpsPort, path: '/v1/health', token: a.token, clientCert: a.client }),
      noHttpStatus,
    )
    const healthy = await secureRequest({ port: httpsPort, path: '/v1/health', token: b.token, clientCert: b.client })
    assert.equal(healthy.status, 200)
  })

  it('exports a password-protected PKCS#12 that can dial the public edge (A7)', async () => {
    const password = 'dshgw-fixture'
    const issued = await gateway.api.panelIssueCertificate({
      name: 'Exported Phone',
      deviceId: 'mtls-export',
      password,
    })
    assert.match(issued.certificate.serial, /^[0-9A-F]+$/)
    assert.equal(issued.export.mediaType, 'application/x-pkcs12')
    assert.equal(issued.export.filename, 'dsh-gateway-client-' + issued.certificate.serial + '.p12')
    assert.equal(issued.export.password, password)
    const pfx = Buffer.from(issued.export.dataBase64, 'base64')
    assert.ok(pfx.length > 500, 'the p12 must carry the key + chain')

    // 私钥已持久化：重启后还能重复导出（这里直接再导一次）。
    const again = await gateway.pki.exportPkcs12({ serial: issued.certificate.serial, password })
    assert.equal(again.p12.length, pfx.length)

    const pairing = gateway.auth.createPairingCode(['https://127.0.0.1:' + httpsPort])
    const paired = await secureRequest({
      port: httpsPort,
      path: '/v1/pair',
      method: 'POST',
      pfx,
      passphrase: password,
      body: { code: pairing.code, deviceName: 'Exported Phone', deviceId: 'mtls-export' },
    })
    assert.equal(paired.status, 200, paired.text)
    const health = await secureRequest({
      port: httpsPort,
      path: '/v1/health',
      token: paired.json.deviceToken,
      pfx,
      passphrase: password,
    })
    assert.equal(health.status, 200)

    const stream = await StreamClient.connect('wss://127.0.0.1:' + httpsPort + '/v1/stream', {
      insecure: true,
      pfx,
      passphrase: password,
      headers: { authorization: 'Bearer ' + paired.json.deviceToken },
      timeoutMs: 6_000,
    })
    const ready = await stream.waitForType('ready')
    assert.equal(typeof ready.seq, 'number')
    await stream.close()
  })

  it('rejects a PKCS#12 opened with the wrong password', async () => {
    const issued = await gateway.api.panelIssueCertificate({ name: 'Wrong Pass', password: 'dshgw-fixture' })
    const pfx = Buffer.from(issued.export.dataBase64, 'base64')
    assert.throws(
      () => tls.createSecureContext({ pfx, passphrase: 'not-the-password' }),
      /mac verify failure|bad decrypt|invalid/i,
    )
    await assert.rejects(secureRequest({ port: httpsPort, path: '/v1/health', pfx, passphrase: 'not-the-password' }))
  })

  it('keeps the certificate subject ASCII so a Chinese display name still imports', async () => {
    // 回归：node-forge 对非 ASCII 主体会编出坏 DER → .p12 MAC 失效 → 手机导入失败。
    const password = 'dshgw-fixture'
    const issued = await gateway.pki.issueForExport({ name: '手机（备用）', deviceId: 'mtls-cn', password })
    const leaf = new X509Certificate(issued.certificate)
    const cn = (/CN=([^,]+)/.exec(leaf.subject) ?? [])[1] ?? ''
    assert.ok(/^[\x20-\x7e]+$/.test(cn), 'the subject CN must stay printable ASCII, got ' + leaf.subject)
    assert.equal(issued.name, '手机（备用）', 'the registry still keeps the Chinese display name')
    assert.equal(leaf.checkIssued(new X509Certificate(gateway.pki.caCertificate())), true)
    assert.doesNotThrow(() => tls.createSecureContext({ pfx: issued.p12, passphrase: password }))

    // 这份 .p12 就是面板下载给手机的文件：Android 导入后走同一条公网连接。
    const pairing = gateway.auth.createPairingCode(['https://127.0.0.1:' + httpsPort])
    const paired = await secureRequest({
      port: httpsPort,
      path: '/v1/pair',
      method: 'POST',
      pfx: issued.p12,
      passphrase: password,
      body: { code: pairing.code, deviceName: '中文设备', deviceId: 'mtls-cn-2' },
    })
    assert.equal(paired.status, 200, paired.text)
    const health = await secureRequest({
      port: httpsPort,
      path: '/v1/health',
      token: paired.json.deviceToken,
      pfx: issued.p12,
      passphrase: password,
    })
    assert.equal(health.status, 200)
  })

  it('drops registry entries whose certificate cannot be parsed (self-healing)', async () => {
    const dir = await makeTempDir(resources)
    const { ensureClientPki } = await loadLib('mtls.js')
    const { fingerprintOfPem } = await loadLib('tls.js')
    const first = await ensureClientPki({ dir, config: {}, logger: makeLogger() })
    const registryPath = join(dir, 'mtls', 'clients.json')
    const registry = JSON.parse(await readFile(registryPath, 'utf8'))
    // 指纹对得上、但 DER 不是合法证书：旧版本留下的坏条目必须被丢弃。
    const brokenPem = '-----BEGIN CERTIFICATE-----\nMIIC\n-----END CERTIFICATE-----\n'
    registry.certs.push({
      serial: 'AB01',
      deviceId: null,
      name: '坏证书',
      fingerprint: fingerprintOfPem(brokenPem),
      createdAt: Date.now(),
      notAfter: Date.now() + 86_400_000,
      revokedAt: null,
      issuedVia: 'panel-export',
      certificate: brokenPem,
    })
    await writeFile(registryPath, JSON.stringify(registry, null, 2))
    const second = await ensureClientPki({ dir, config: {}, logger: makeLogger() })
    assert.deepEqual(second.list(), [])
    assert.equal(second.caFingerprint(), first.caFingerprint())
    await first.close()
    await second.close()
  })

  it('revokes the device certificates when a device is revoked from the panel', async () => {
    const { client } = await enrollAndPair('mtls-device-link', 'Linked Phone')
    assert.equal(gateway.pki.isRevokedSerial(client.serial), false)
    assert.equal(gateway.api.panelRevokeDevice('mtls-device-link'), true)
    assert.equal(gateway.pki.isRevokedSerial(client.serial), true)
  })

  it('renews a device certificate by revoking the previous one for that device', async () => {
    const first = await enrollClient(lanBase, { deviceId: 'mtls-renew', deviceName: 'Renew Phone' })
    const pairing = gateway.auth.createPairingCode(['https://127.0.0.1:' + httpsPort])
    const paired = await gateway.auth.pair({
      code: pairing.code,
      deviceName: 'Renew Phone',
      deviceId: 'mtls-renew',
      remoteAddress: '127.0.0.1',
    })
    const second = await enrollClient(lanBase, { deviceId: 'mtls-renew', deviceName: 'Renew Phone' })
    assert.notEqual(first.serial, second.serial)
    assert.equal(gateway.pki.isRevokedSerial(first.serial), true)
    assert.equal(gateway.pki.isRevokedSerial(second.serial), false)
    const active = gateway.pki.list().filter((entry) => entry.deviceId === 'mtls-renew' && entry.revokedAt === null)
    assert.equal(active.length, 1)

    await assert.rejects(
      secureRequest({
        port: httpsPort,
        path: '/v1/health',
        token: paired.deviceToken,
        clientCert: first,
      }),
      noHttpStatus,
    )
    const healthy = await secureRequest({
      port: httpsPort,
      path: '/v1/health',
      token: paired.deviceToken,
      clientCert: second,
    })
    assert.equal(healthy.status, 200)
  })

  it('exposes mTLS metadata on the panel state and /v1/health', async () => {
    const state = await gateway.api.panelState()
    assert.equal(state.mtls.enforced, true)
    assert.equal(state.mtls.caFingerprint, gateway.pki.caFingerprint())
    assert.match(state.mtls.caFingerprintPretty, /^([0-9A-F]{2}:)+[0-9A-F]{2}$/)
    assert.ok(state.mtls.certificateCount >= 1)
    assert.ok(state.mtls.revokedCount >= 1)
    // 列表不带证书 PEM（PEM 只在签发响应/导出材料里）。
    assert.equal('certificate' in (gateway.pki.list()[0] ?? {}), false)

    const health = await request(lanBase, '/v1/health')
    assert.equal(health.json.mtls.required, true)
    assert.equal(health.json.mtls.caFingerprint, gateway.pki.caFingerprint())
  })

  it('rejects a malformed CSR on the LAN edge', async () => {
    const bad = await request(lanBase, '/v1/pki/enroll', { method: 'POST', body: { csr: 'not a csr' } })
    assert.equal(bad.status, 400)
    assert.equal(bad.json.error.code, 'invalid_request')
    const missingCsr = await request(lanBase, '/v1/pki/enroll', { method: 'POST', body: {} })
    assert.equal(missingCsr.status, 400)
    assert.equal(missingCsr.json.error.code, 'invalid_request')
  })
})

describe('client PKI persistence', { skip: reason }, () => {
  it('keeps the CA and its key across restarts, with ca-key.pem 0600', async () => {
    const resources = createResources()
    const dir = await makeTempDir(resources)
    const { ensureClientPki } = await loadLib('mtls.js')
    const logger = makeLogger()
    try {
      const first = await ensureClientPki({ dir, config: { clientCertDays: 30 }, logger })
      const fingerprint = first.caFingerprint()
      assert.match(fingerprint, /^[0-9a-f]{64}$/)
      assert.equal(new X509Certificate(first.caCertificate()).ca, true)
      await first.close()

      const second = await ensureClientPki({ dir, config: { clientCertDays: 30 }, logger })
      assert.equal(second.caFingerprint(), fingerprint)
      await second.close()

      const keyStat = await stat(join(dir, 'mtls', 'ca-key.pem'))
      if (process.platform !== 'win32') assert.equal(keyStat.mode & 0o777, 0o600)
      const registry = JSON.parse(await readFile(join(dir, 'mtls', 'clients.json'), 'utf8'))
      assert.equal(registry.version, 1)
      assert.equal(registry.caFingerprint, fingerprint)
    } finally {
      await resources.disposeAll()
    }
  })

  it('self-heals a corrupted registry without dropping the CA', async () => {
    const resources = createResources()
    const dir = await makeTempDir(resources)
    const { ensureClientPki } = await loadLib('mtls.js')
    const logger = makeLogger()
    try {
      const first = await ensureClientPki({ dir, config: {}, logger })
      const fingerprint = first.caFingerprint()
      await first.close()
      await writeFile(join(dir, 'mtls', 'clients.json'), '{ not json', 'utf8')

      const second = await ensureClientPki({ dir, config: {}, logger })
      assert.equal(second.caFingerprint(), fingerprint)
      assert.deepEqual(second.list(), [])
      assert.ok(logger.entries.some((entry) => entry.level === 'warn'), 'a broken registry must warn')
      await second.close()
    } finally {
      await resources.disposeAll()
    }
  })

  it('keeps the CA and previously issued certificates valid after a restart (A8)', async () => {
    const resources = createResources()
    const dir = await makeTempDir(resources)
    try {
      const firstRun = await bootGateway(resources, { dir })
      await waitForHealth(firstRun.lanBase)
      const client = await enrollClient(firstRun.lanBase, { deviceId: 'mtls-restart', deviceName: 'Restart Phone' })
      const pairing = firstRun.gateway.auth.createPairingCode(['https://127.0.0.1:' + firstRun.httpsPort])
      const paired = await firstRun.gateway.auth.pair({
        code: pairing.code,
        deviceName: 'Restart Phone',
        deviceId: 'mtls-restart',
        remoteAddress: '127.0.0.1',
      })
      const fingerprint = firstRun.gateway.pki.caFingerprint()
      await firstRun.stop()

      const secondRun = await bootGateway(resources, { dir })
      await waitForHealth(secondRun.lanBase)
      assert.equal(secondRun.gateway.pki.caFingerprint(), fingerprint)
      assert.equal(secondRun.gateway.pki.list().length, 1)

      const health = await secureRequest({
        port: secondRun.httpsPort,
        path: '/v1/health',
        token: paired.deviceToken,
        clientCert: client,
      })
      assert.equal(health.status, 200, health.text)
      await secondRun.stop()
    } finally {
      await resources.disposeAll()
    }
  })

  it('rate limits certificate endpoint requests (429 rate_limited)', async () => {
    const resources = createResources()
    try {
      const started = await bootGateway(resources)
      await waitForHealth(started.lanBase)
      const { csr } = await makeClientCsr({ commonName: 'rate-limit-client' })
      for (let index = 0; index < 20; index += 1) {
        const response = await request(started.lanBase, '/v1/pki/enroll', {
          method: 'POST',
          body: { csr },
        })
        assert.equal(response.status, 200, 'request ' + index + ' should be allowed: ' + response.text)
      }
      const limited = await request(started.lanBase, '/v1/pki/enroll', { method: 'POST', body: { csr } })
      assert.equal(limited.status, 429)
      assert.equal(limited.json.error.code, 'rate_limited')
      const caLimited = await request(started.lanBase, '/v1/pki/ca')
      assert.equal(caLimited.status, 429)
      await started.stop()
    } finally {
      await resources.disposeAll()
    }
  })

  it('degrades to LAN-only when the PKI cannot be created', async () => {
    const resources = createResources()
    const dir = await makeTempDir(resources)
    try {
      // 用同名普通文件占住 mtls/，ensureClientPki 必然失败。
      await writeFile(join(dir, 'mtls'), 'not a directory', 'utf8')
      const started = await bootGateway(resources, { dir, httpsPort: await freePort() })
      assert.equal(started.gateway.pki, null)
      assert.equal(started.gateway.ports.publicPort, 0, 'the public edge must stay disabled')
      const health = await request(started.lanBase, '/v1/health')
      assert.equal(health.status, 200, 'the LAN edge must keep working')
      assert.equal(health.json.mtls.required, false)
      assert.equal(health.json.mtls.caFingerprint, '')
      const pki = await request(started.lanBase, '/v1/pki/ca')
      assert.equal(pki.status, 503)
      assert.equal(pki.json.error.code, 'dsh_unavailable')
      await started.stop()
    } finally {
      await resources.disposeAll()
    }
  })
})
