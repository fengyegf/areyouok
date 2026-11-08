/**
 * The in-app panel backend.
 *
 * The gateway no longer serves `/admin`: the same status / pairing / device view
 * is the plugin's configuration page in the Web UI, and its JSON routes are
 * mounted on the **DSH host** web server. These tests drive those handlers
 * through the mock context's route table, which is exactly what the Host
 * dispatches to, and pin the three things that make the move safe:
 *
 *   - the LAN/public listeners answer no administrative route any more,
 *   - every panel route fails closed when the host trust fence rejects,
 *   - the panel really does revoke devices, mint codes and flip switches.
 *
 * @module dsh-gateway/test/panel.test
 */

import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { after, before, describe, it } from 'node:test'

import {
  createResources,
  libStatus,
  makeCtx,
  makeLogger,
  request,
  startGateway,
  waitForDsh,
  waitForHealth,
} from './helper.mjs'
import { MockDsh } from './mock-dsh.mjs'

const REQUIRED = ['index.js', 'panel.js', 'panel-routes.js']
const status = await libStatus()
const missing = REQUIRED.filter((name) => status.missing.includes(name))
const reason = missing.length === 0 ? undefined : `lib modules not implemented yet: ${missing.join(', ')}`

/**
 * Serve the mock context's registered routes on a real loopback listener.
 *
 * The host dispatch is `(req, res)` per exact path, so a tiny server keeps the
 * assertions honest about headers, methods and status codes.
 * @param {Map<string, {handler:Function}>} routes
 * @returns {Promise<{base:string, close:()=>Promise<void>}>}
 */
async function serveRoutes(routes) {
  const server = createServer((req, res) => {
    const url = new URL(String(req.url ?? '/'), 'http://localhost')
    const route = routes.get(url.pathname)
    if (route === undefined) {
      res.writeHead(404, { 'content-type': 'text/plain' })
      res.end('not found')
      return
    }
    Promise.resolve()
      .then(() => route.handler(req, res))
      .catch(() => {
        if (!res.headersSent) {
          res.writeHead(500)
          res.end()
        }
      })
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  const address = server.address()
  const port = typeof address === 'object' && address !== null ? address.port : 0
  return {
    base: `http://127.0.0.1:${port}`,
    close: () => new Promise((resolve) => server.close(() => resolve())),
  }
}

describe('in-app panel routes', { skip: reason }, () => {
  /** @type {ReturnType<typeof createResources>} */
  let resources
  /** @type {MockDsh} */
  let mock
  /** @type {any} */
  let gateway
  /** @type {any} */
  let ctx
  /** @type {{base:string, close:()=>Promise<void>}} */
  let host

  before(async () => {
    resources = createResources()
    mock = new MockDsh()
    await mock.start()
    resources.add(() => mock.stop())
    const logger = makeLogger()
    ctx = makeCtx({
      origin: mock.origin,
      port: mock.port,
      host: mock.host,
      launchToken: mock.launchToken,
      logger,
    })
    const started = await startGateway({ mock, resources, ctx, config: { ringSize: 16 } })
    gateway = started.gateway
    await waitForHealth(started.lanBase)
    await waitForDsh(gateway)
    host = await serveRoutes(ctx.routes)
    resources.add(() => host.close())
  })

  after(async () => {
    await resources.disposeAll()
  })

  it('mounts the panel routes on the host web server', () => {
    // `pairing` answers GET and POST, so the table holds four paths for five
    // method/path pairs. Nothing else is claimed.
    assert.deepEqual([...ctx.routes.keys()].sort(), [
      '/plugins/dsh-gateway/config',
      '/plugins/dsh-gateway/devices',
      '/plugins/dsh-gateway/pairing',
      '/plugins/dsh-gateway/state',
    ])
  })

  it('GET state returns the panel snapshot', async () => {
    const response = await request(host.base, '/plugins/dsh-gateway/state')
    assert.equal(response.status, 200)
    assert.equal(response.json.ok, true)
    assert.equal(typeof response.json.ports, 'object')
    assert.equal(typeof response.json.dsh?.connected, 'boolean')
    assert.ok(Array.isArray(response.json.devices))
    assert.equal(typeof response.json.config?.lanRequirePairing, 'boolean')
    assert.equal(typeof response.json.name, 'string')
    assert.equal(typeof response.json.fingerprintPretty, 'string')
    // Not the `/v1` wildcard: this route is for the Web UI on its own origin.
    assert.equal(response.headers.get('access-control-allow-origin'), null)
    assert.equal(response.headers.get('cache-control'), 'no-store')
    assert.equal(response.headers.get('x-content-type-options'), 'nosniff')
  })

  it('POST pairing mints a code with a scannable matrix and no certificate on LAN', async () => {
    const response = await request(host.base, '/plugins/dsh-gateway/pairing', { method: 'POST', body: {} })
    assert.equal(response.status, 200)
    const pairing = response.json.pairing
    assert.equal(typeof pairing.code, 'string')
    assert.match(pairing.uri, /^dshgw:\/\/pair\?/)
    assert.ok(pairing.qr, 'the panel needs a matrix to draw')
    assert.equal(typeof pairing.qr.size, 'number')
    assert.ok(pairing.qr.size >= 21)
    assert.equal(typeof pairing.qr.data, 'string')
    assert.equal(Buffer.from(pairing.qr.data, 'base64').length, pairing.qr.size * pairing.qr.size)

    // The same code comes back from GET while it is still valid.
    const current = await request(host.base, '/plugins/dsh-gateway/pairing')
    assert.equal(current.status, 200)
    assert.equal(current.json.pairing?.code, pairing.code)
  })

  it('PATCH config flips lanRequirePairing and reports the restart for mdns', async () => {
    const strict = await request(host.base, '/plugins/dsh-gateway/config', {
      method: 'PATCH',
      body: { lanRequirePairing: true },
    })
    assert.equal(strict.status, 200)
    assert.equal(strict.json.applied.lanRequirePairing, true)
    assert.equal(strict.json.restartRequired, false)
    assert.equal(gateway.api.effectiveConfig().lanRequirePairing, true)
    assert.equal(gateway.auth.lanRequirePairing, true)

    const mdns = await request(host.base, '/plugins/dsh-gateway/config', {
      method: 'PATCH',
      body: { key: 'mdns', value: false },
    })
    assert.equal(mdns.status, 200)
    assert.equal(mdns.json.applied.mdns, false)
    assert.equal(mdns.json.restartRequired, true)
    assert.equal(gateway.api.effectiveConfig().mdns, false)

    const unknown = await request(host.base, '/plugins/dsh-gateway/config', {
      method: 'PATCH',
      body: { key: 'httpPort', value: 1 },
    })
    assert.equal(unknown.status, 400)
    assert.equal(unknown.json.error.code, 'invalid_request')

    // Restore the defaults for the following assertions.
    await request(host.base, '/plugins/dsh-gateway/config', {
      method: 'PATCH',
      body: { lanRequirePairing: false, mdns: true },
    })
    assert.equal(gateway.api.effectiveConfig().lanRequirePairing, false)
  })

  it('DELETE devices revokes a paired token', async () => {
    const pairing = await request(host.base, '/plugins/dsh-gateway/pairing', { method: 'POST', body: {} })
    const paired = await fetch(`${gateway.api ? `http://127.0.0.1:${gateway.ports.lanPort}` : ''}/v1/pair`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ code: pairing.json.pairing.code, deviceName: 'Pixel', deviceId: 'dev-panel' }),
    })
    assert.equal(paired.status, 200)
    const state = await request(host.base, '/plugins/dsh-gateway/state')
    assert.ok(state.json.devices.some((device) => device.deviceId === 'dev-panel'))

    const revoked = await request(host.base, '/plugins/dsh-gateway/devices?deviceId=dev-panel', { method: 'DELETE' })
    assert.equal(revoked.status, 200)
    assert.equal(revoked.json.revoked, true)
    const after1 = await request(host.base, '/plugins/dsh-gateway/state')
    assert.equal(after1.json.devices.some((device) => device.deviceId === 'dev-panel'), false)

    const missing2 = await request(host.base, '/plugins/dsh-gateway/devices?deviceId=dev-panel', { method: 'DELETE' })
    assert.equal(missing2.status, 200)
    assert.equal(missing2.json.revoked, false)

    const noId = await request(host.base, '/plugins/dsh-gateway/devices', { method: 'DELETE' })
    assert.equal(noId.status, 400)
  })

  it('rejects a wrong method with 405 and an untrusted authority with 403', async () => {
    /* The method table is answered BEFORE the fence on purpose: a caller that
     * used the wrong verb learns nothing about authorization, and the response
     * is the same whether or not the source is trusted. */
    const wrong = await request(host.base, '/plugins/dsh-gateway/state', { method: 'POST', body: {} })
    assert.equal(wrong.status, 405)
    assert.equal(wrong.headers.get('allow'), 'GET')

    // Swap the fence for one that rejects: the routes must fail closed.
    const original = ctx.connection.requestRejection
    ctx.connection.requestRejection = () => 403
    try {
      const state = await request(host.base, '/plugins/dsh-gateway/state')
      assert.equal(state.status, 403)
      assert.equal(state.json.error.code, 'forbidden_source')
      const patch = await request(host.base, '/plugins/dsh-gateway/config', {
        method: 'PATCH',
        body: { key: 'lanRequirePairing', value: true },
      })
      assert.equal(patch.status, 403)
      // The refused write changed nothing.
      assert.equal(gateway.api.effectiveConfig().lanRequirePairing, false)
    } finally {
      ctx.connection.requestRejection = original
    }
  })

  it('answers 401, not 403, when the browser session is simply missing', async () => {
    const original = ctx.connection.requestRejection
    ctx.connection.requestRejection = () => 401
    try {
      const response = await request(host.base, '/plugins/dsh-gateway/state')
      assert.equal(response.status, 401)
      assert.equal(response.json.error.code, 'unauthenticated')
    } finally {
      ctx.connection.requestRejection = original
    }
  })

  it('fails closed when the host fence itself throws', async () => {
    const original = ctx.connection.requestRejection
    ctx.connection.requestRejection = () => {
      throw new Error('fence exploded')
    }
    try {
      const response = await request(host.base, '/plugins/dsh-gateway/state')
      assert.equal(response.status, 403)
    } finally {
      ctx.connection.requestRejection = original
    }
  })

  it('keeps the administrative surface off the LAN listener', async () => {
    const base = `http://127.0.0.1:${gateway.ports.lanPort}`
    for (const path of ['/admin', '/admin/api/state', '/plugins/dsh-gateway/state']) {
      const response = await request(base, path)
      assert.equal(response.status, 404, `${path} must not be served by the gateway listeners`)
    }
  })
})
