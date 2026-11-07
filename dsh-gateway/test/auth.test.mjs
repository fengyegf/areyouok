/**
 * Source policy, device tokens and pairing.
 *
 * Covers:
 *   - `classifyAddress` (util) across loopback / RFC1918 / CGNAT / link-local /
 *     ULA / public IPv4 / public IPv6 / mapped addresses,
 *   - `AuthService.guard` for the LAN port and the public HTTPS port,
 *   - pairing: one-shot codes, expiry, failure rate limiting, device eviction,
 *   - token verification and revocation,
 *   - full-stack: the `httpsPort > 0` public edge really demands a Bearer token
 *     (loopback still passes on the LAN port).
 *
 * @module dsh-gateway/test/auth.test
 */

import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { createResources, freePort, libStatus, secureRequest, startGateway, waitForHealth } from './helper.mjs'
import { StreamClient } from './helper.mjs'
import { MockDsh } from './mock-dsh.mjs'

/** Minimal in-memory hub: AuthService only needs the device table. */
class FakeHub {
  constructor() {
    /** @type {Map<string, object>} */
    this.devices = new Map()
    this.persistCalls = 0
  }

  listDevices() {
    return [...this.devices.values()]
  }

  getDevice(deviceId) {
    return this.devices.get(deviceId) ?? null
  }

  upsertDevice(record) {
    this.devices.set(record.deviceId, record)
  }

  removeDevice(deviceId) {
    this.devices.delete(deviceId)
  }

  persist() {
    this.persistCalls += 1
  }
}

/** Build an AuthService wired to a fake hub. */
async function makeAuth(config = {}) {
  const { AuthService } = await import('../lib/auth.js')
  const hub = new FakeHub()
  const logger = { info() {}, warn() {}, error() {}, debug() {} }
  const auth = new AuthService({
    hub,
    config: { httpsPort: 7789, lanRequirePairing: false, maxDevices: 4, deviceTokenTtlDays: 0, pairingCodeTtlMs: 600_000, ...config },
    logger,
    dir: '',
  })
  return { auth, hub }
}

describe('classifyAddress', async () => {
  const util = await import('../lib/util.js')
  const { classifyAddress } = util

  const cases = [
    ['127.0.0.1', 'loopback'],
    ['::1', 'loopback'],
    ['0:0:0:0:0:0:0:1', 'loopback'],
    ['::ffff:127.0.0.1', 'loopback'],
    ['10.0.0.1', 'lan'],
    ['10.255.255.254', 'lan'],
    ['172.16.0.1', 'lan'],
    ['172.20.10.2', 'lan'],
    ['172.31.255.1', 'lan'],
    ['192.168.1.7', 'lan'],
    ['192.168.100.100', 'lan'],
    ['100.64.0.1', 'lan'],
    ['100.127.255.254', 'lan'],
    ['169.254.12.34', 'lan'],
    ['fe80::1', 'lan'],
    ['fe80::1%eth0', 'lan'],
    ['fd00::1', 'lan'],
    ['fd12:3456::1', 'lan'],
    ['fc00::abcd', 'lan'],
    ['8.8.8.8', 'public'],
    ['1.1.1.1', 'public'],
    ['172.15.0.1', 'public'],
    ['172.32.0.1', 'public'],
    ['100.63.255.255', 'public'],
    ['198.51.100.9', 'public'],
    ['2001:4860:4860::8888', 'public'],
    ['2606:4700:4700::1111', 'public'],
    ['2001:db8::1', 'public'],
    ['', 'public'],
    ['not-an-ip', 'public'],
    [undefined, 'public'],
  ]

  for (const [address, expected] of cases) {
    it(`classifies ${JSON.stringify(address)} as ${expected}`, () => {
      assert.equal(classifyAddress(address), expected)
    })
  }

  it('treats RFC1918 and ULA as private', () => {
    assert.equal(util.isPrivateAddress('10.1.2.3'), true)
    assert.equal(util.isPrivateAddress('8.8.8.8'), false)
    assert.equal(util.normalizeRemoteAddress('::FFFF:192.168.0.4'), '192.168.0.4')
  })
})

describe('AuthService.guard', () => {
  const HTTPS_PORT = 7789
  const LAN_PORT = 7788

  it('allows loopback and LAN on the LAN port by default', async () => {
    const { auth } = await makeAuth()
    assert.equal(auth.guard({ remoteAddress: '127.0.0.1', localPort: LAN_PORT, headers: {} }).ok, true)
    assert.equal(auth.guard({ remoteAddress: '192.168.1.20', localPort: LAN_PORT, headers: {} }).ok, true)
    assert.equal(auth.guard({ remoteAddress: 'fd00::1234', localPort: LAN_PORT, headers: {} }).ok, true)
  })

  it('rejects public sources on the LAN port with 403 forbidden_source', async () => {
    const { auth } = await makeAuth()
    const verdict = auth.guard({ remoteAddress: '8.8.8.8', localPort: LAN_PORT, headers: {} })
    assert.equal(verdict.ok, false)
    assert.equal(verdict.status, 403)
    assert.equal(verdict.code, 'forbidden_source')

    const v6 = auth.guard({ remoteAddress: '2001:4860:4860::8888', localPort: LAN_PORT, headers: {} })
    assert.equal(v6.ok, false)
    assert.equal(v6.status, 403)
  })

  it('requires a token on the LAN port when lanRequirePairing is on', async () => {
    const { auth } = await makeAuth({ lanRequirePairing: true })
    const denied = auth.guard({ remoteAddress: '192.168.1.20', localPort: LAN_PORT, headers: {} })
    assert.equal(denied.ok, false)
    assert.equal(denied.status, 401)
    assert.equal(denied.code, 'unauthenticated')

    const { auth: auth2 } = await makeAuth()
    const pairing = auth2.createPairingCode(['http://192.168.1.5:7788'])
    const paired = await auth2.pair({ code: pairing.code, deviceName: 'Pixel', deviceId: 'dev-1', remoteAddress: '192.168.1.20' })
    auth2.setLanRequirePairing(true)
    const allowed = auth2.guard({
      remoteAddress: '192.168.1.20',
      localPort: LAN_PORT,
      headers: { authorization: `Bearer ${paired.deviceToken}` },
    })
    assert.equal(allowed.ok, true)
    assert.equal(allowed.device?.deviceId, 'dev-1')

    const wrong = auth2.guard({
      remoteAddress: '192.168.1.20',
      localPort: LAN_PORT,
      headers: { authorization: 'Bearer nope' },
    })
    assert.equal(wrong.ok, false)
    assert.equal(wrong.status, 401)
  })

  it('has no administrative route left to exempt', async () => {
    // `/admin` used to bypass the LAN pairing switch from loopback. The page is
    // gone, so a LAN peer that used to hit 403 there now meets the same rule as
    // every other path: allowed while `lanRequirePairing` is off.
    const { auth } = await makeAuth()
    const loopback = auth.guard({ remoteAddress: '127.0.0.1', localPort: LAN_PORT, headers: {}, path: '/admin' })
    assert.equal(loopback.ok, true)
    const lan = auth.guard({ remoteAddress: '192.168.1.20', localPort: LAN_PORT, headers: {}, path: '/admin/api/state' })
    assert.equal(lan.ok, true)

    auth.setLanRequirePairing(true)
    const denied = auth.guard({ remoteAddress: '192.168.1.20', localPort: LAN_PORT, headers: {}, path: '/admin/api/state' })
    assert.equal(denied.ok, false)
    assert.equal(denied.status, 401)
  })

  it('always requires a token on the public HTTPS port, even from loopback', async () => {
    const { auth } = await makeAuth({ httpsPort: HTTPS_PORT })
    const denied = auth.guard({ remoteAddress: '127.0.0.1', localPort: HTTPS_PORT, headers: {} })
    assert.equal(denied.ok, false)
    assert.equal(denied.status, 401)
    assert.equal(denied.code, 'unauthenticated')

    const pairing = auth.createPairingCode(['https://[2408::1]:7789'])
    const paired = await auth.pair({ code: pairing.code, deviceName: 'Pixel', deviceId: 'dev-pub', remoteAddress: '2408::1' })
    const allowed = auth.guard({
      remoteAddress: '2408::1',
      localPort: HTTPS_PORT,
      headers: { authorization: `Bearer ${paired.deviceToken}` },
    })
    assert.equal(allowed.ok, true)
    assert.equal(allowed.device?.deviceId, 'dev-pub')
  })

  it('lets /v1/pair through on the public port before authentication', async () => {
    const { auth } = await makeAuth({ httpsPort: HTTPS_PORT })
    const verdict = auth.guard({ remoteAddress: '2408::77', localPort: HTTPS_PORT, headers: {}, path: '/v1/pair' })
    assert.equal(verdict.ok, true)
    assert.equal(verdict.device, null)
  })
})

describe('AuthService pairing', () => {
  it('exchanges a one-shot code for a device token', async () => {
    const { auth } = await makeAuth()
    const pairing = auth.createPairingCode(['http://127.0.0.1:7788'])
    assert.equal(typeof pairing.code, 'string')
    assert.ok(pairing.code.length >= 32)
    assert.deepEqual(pairing.urls, ['http://127.0.0.1:7788'])

    const result = await auth.pair({ code: pairing.code, deviceName: 'Pixel 8', deviceId: 'device-1', remoteAddress: '192.168.1.20' })
    assert.equal(typeof result.deviceToken, 'string')
    assert.ok(result.deviceToken.length >= 32)
    assert.equal(result.deviceId, 'device-1')
    assert.equal(result.expiresAt, null)

    const device = auth.verify(result.deviceToken)
    assert.equal(device?.deviceId, 'device-1')
    assert.equal(device?.name, 'Pixel 8')
    assert.notEqual(device?.tokenHash, result.deviceToken, 'state must store only the hash')
    assert.equal(auth.verify('bogus'), null)
    assert.equal(auth.verify(undefined), null)
  })

  it('consumes the code exactly once', async () => {
    const { auth } = await makeAuth()
    const pairing = auth.createPairingCode([])
    await auth.pair({ code: pairing.code, deviceName: 'A', deviceId: 'dev-a', remoteAddress: '192.168.1.20' })
    await assert.rejects(
      () => auth.pair({ code: pairing.code, deviceName: 'B', deviceId: 'dev-b', remoteAddress: '192.168.1.20' }),
      (error) => error.status === 400 && error.code === 'invalid_code',
    )
  })

  it('rejects expired codes with 410 code_expired', async () => {
    const { auth } = await makeAuth()
    const pairing = auth.createPairingCode([])
    const entry = auth.pairingCodes.get(pairing.code)
    assert.ok(entry, 'pairing code should be tracked in memory')
    entry.expiresAt = Date.now() - 1
    await assert.rejects(
      () => auth.pair({ code: pairing.code, deviceName: 'A', deviceId: 'dev-a', remoteAddress: '192.168.1.20' }),
      (error) => error.status === 410 && error.code === 'code_expired',
    )
  })

  it('rate limits failed pairing attempts', async () => {
    const { auth } = await makeAuth()
    const code = 'wrong-code'
    const outcomes = []
    for (let attempt = 0; attempt < 6; attempt += 1) {
      try {
        await auth.pair({ code, deviceName: 'A', deviceId: `dev-${attempt}`, remoteAddress: '192.168.1.66' })
        outcomes.push('ok')
      } catch (error) {
        outcomes.push(error.status)
      }
    }
    assert.deepEqual(outcomes, [400, 400, 400, 400, 400, 429])
    // The failure window is global too: another address is also refused.
    await assert.rejects(
      () => auth.pair({ code, deviceName: 'A', deviceId: 'dev-other', remoteAddress: '192.168.1.67' }),
      (error) => error.status === 429 && error.code === 'rate_limited',
    )
  })

  it('forgets pairing failures after a successful pairing', async () => {
    const { auth } = await makeAuth()
    for (let attempt = 0; attempt < 4; attempt += 1) {
      await assert.rejects(
        () => auth.pair({ code: 'bad', deviceName: 'A', deviceId: `dev-${attempt}`, remoteAddress: '192.168.1.77' }),
        (error) => error.status === 400,
      )
    }
    const pairing = auth.createPairingCode([])
    const paired = await auth.pair({ code: pairing.code, deviceName: 'A', deviceId: 'dev-ok', remoteAddress: '192.168.1.77' })
    assert.equal(auth.verify(paired.deviceToken)?.deviceId, 'dev-ok')
  })

  it('revokes a device and calls revoke listeners', async () => {
    const { auth } = await makeAuth()
    const pairing = auth.createPairingCode([])
    const paired = await auth.pair({ code: pairing.code, deviceName: 'A', deviceId: 'dev-revoke', remoteAddress: '192.168.1.20' })
    const seen = []
    auth.onRevoke((deviceId) => seen.push(deviceId))
    assert.equal(auth.revoke('dev-revoke'), true)
    assert.equal(auth.verify(paired.deviceToken), null)
    assert.deepEqual(seen, ['dev-revoke'])
    assert.equal(auth.revoke('dev-revoke'), false)
  })

  it('evicts the least recently active device beyond maxDevices', async () => {
    const { auth } = await makeAuth({ maxDevices: 1 })
    const first = auth.createPairingCode([])
    const firstPaired = await auth.pair({ code: first.code, deviceName: 'Old', deviceId: 'dev-old', remoteAddress: '192.168.1.20' })
    const second = auth.createPairingCode([])
    const secondPaired = await auth.pair({ code: second.code, deviceName: 'New', deviceId: 'dev-new', remoteAddress: '192.168.1.20' })
    assert.equal(auth.verify(firstPaired.deviceToken), null)
    assert.equal(auth.verify(secondPaired.deviceToken)?.deviceId, 'dev-new')
    assert.equal(auth.deviceCount(), 1)
  })

  it('sweeps expired device tokens', async () => {
    const { auth, hub } = await makeAuth({ deviceTokenTtlDays: 1 })
    const pairing = auth.createPairingCode([])
    const paired = await auth.pair({ code: pairing.code, deviceName: 'A', deviceId: 'dev-ttl', remoteAddress: '192.168.1.20' })
    assert.equal(typeof paired.expiresAt, 'number')
    const record = hub.getDevice('dev-ttl')
    record.expiresAt = Date.now() - 1
    const result = auth.sweep()
    assert.equal(result.devices, 1)
    assert.equal(auth.verify(paired.deviceToken), null)
  })
})

// ---------------------------------------------------------------- full stack

const REQUIRED = ['index.js', 'hub.js', 'dsh.js', 'http.js', 'ws.js']
const status = await libStatus()
const missing = REQUIRED.filter((name) => status.missing.includes(name))
const reason = missing.length === 0 ? undefined : `lib modules not implemented yet: ${missing.join(', ')}`

describe('public HTTPS edge (full stack)', { skip: reason }, () => {
  it('requires a Bearer token on httpsPort but not on the LAN port', async () => {
    const resources = createResources()
    const mock = new MockDsh()
    await mock.start()
    resources.add(() => mock.stop())
    try {
      const httpsPort = await freePort()
      const { gateway, ports, lanBase } = await startGateway({
        mock,
        resources,
        config: { httpsPort, host: '127.0.0.1' },
      })
      try {
        assert.equal(ports.publicPort, httpsPort)
        assert.ok(ports.lanPort > 0)

        // LAN HTTP from loopback: unauthenticated.
        const health = await waitForHealth(lanBase)
        assert.equal(health.status, 200)
        assert.equal(health.json.ok, true)

        // Public HTTPS from loopback: 401 without a token.
        const denied = await secureRequest({ port: httpsPort, path: '/v1/health' })
        assert.equal(denied.status, 401)
        assert.equal(denied.json?.error?.code, 'unauthenticated')

        // Pair over the public port (pre-auth route) then use the token.
        const pairing = gateway.auth.createPairingCode([`https://127.0.0.1:${httpsPort}`], {
          fingerprint: gateway.api?.tls?.fingerprint ?? undefined,
        })
        const paired = await secureRequest({
          port: httpsPort,
          path: '/v1/pair',
          method: 'POST',
          body: { code: pairing.code, deviceName: 'Pixel', deviceId: 'device-https' },
        })
        assert.equal(paired.status, 200, `pair failed: ${paired.text}`)
        assert.equal(paired.json.ok, true)
        assert.equal(typeof paired.json.deviceToken, 'string')

        const authorized = await secureRequest({ port: httpsPort, path: '/v1/health', token: paired.json.deviceToken })
        assert.equal(authorized.status, 200)
        assert.equal(authorized.json.ok, true)
        assert.equal(authorized.json.paired, true)

        // Revoking invalidates the token immediately.
        gateway.auth.revoke(paired.json.deviceId)
        const revoked = await secureRequest({ port: httpsPort, path: '/v1/health', token: paired.json.deviceToken })
        assert.equal(revoked.status, 401)
        assert.equal(revoked.json?.error?.code, 'unauthenticated')
      } finally {
        await gateway.stop()
      }
    } finally {
      await resources.disposeAll()
    }
  })

  it('requires the device token on the public WebSocket and closes it on revoke', async () => {
    const resources = createResources()
    const mock = new MockDsh()
    await mock.start()
    resources.add(() => mock.stop())
    try {
      const httpsPort = await freePort()
      const { gateway, lanBase } = await startGateway({
        mock,
        resources,
        config: { httpsPort, host: '127.0.0.1' },
      })
      try {
        await waitForHealth(lanBase)
        const wsUrl = `wss://127.0.0.1:${httpsPort}/v1/stream`

        // No token: the upgrade is refused before the WebSocket handshake.
        await assert.rejects(
          StreamClient.connect(wsUrl, { insecure: true, timeoutMs: 4_000 }),
          /HTTP 401|socket hang up|ECONNRESET/i,
        )

        // A paired device is admitted and gets `ready`.
        const pairing = gateway.auth.createPairingCode([`https://127.0.0.1:${httpsPort}`])
        const paired = await gateway.auth.pair({
          code: pairing.code,
          deviceName: 'Pixel WS',
          deviceId: 'device-https-ws',
          remoteAddress: '127.0.0.1',
        })
        const client = await StreamClient.connect(wsUrl, {
          insecure: true,
          headers: { authorization: `Bearer ${paired.deviceToken}` },
          timeoutMs: 6_000,
        })
        resources.add(() => client.close())
        const ready = await client.waitForType('ready')
        assert.equal(typeof ready.seq, 'number')

        // Revoking the token tears the socket down.
        gateway.auth.revoke('device-https-ws')
        const revoked = await client.waitForType('error', { timeoutMs: 5_000 })
        assert.equal(revoked.code, 'unauthenticated')
        await client.waitForClose(5_000)
      } finally {
        await gateway.stop()
      }
    } finally {
      await resources.disposeAll()
    }
  })
})
