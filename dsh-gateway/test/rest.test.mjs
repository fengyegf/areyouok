/**
 * `/v1` REST surface end-to-end: a real HTTP listener answered by the real
 * gateway stack, backed by the mock DSH.
 *
 * @module dsh-gateway/test/rest.test
 */

import assert from 'node:assert/strict'
import { after, before, beforeEach, describe, it } from 'node:test'

import {
  createResources,
  libStatus,
  request,
  startGateway,
  waitFor,
  waitForDsh,
  waitForHealth,
  waitForSession,
} from './helper.mjs'
import { MockDsh, defaultSessions } from './mock-dsh.mjs'

const REQUIRED = ['index.js', 'hub.js', 'dsh.js', 'http.js', 'ws.js']
const status = await libStatus()
const missing = REQUIRED.filter((name) => status.missing.includes(name))
const reason = missing.length === 0 ? undefined : `lib modules not implemented yet: ${missing.join(', ')}`

describe('/v1 REST', { skip: reason }, () => {
  /** @type {ReturnType<typeof createResources>} */
  let resources
  /** @type {MockDsh} */
  let mock
  /** @type {any} */
  let gateway
  /** @type {string} */
  let base

  before(async () => {
    resources = createResources()
    mock = new MockDsh({
      sessions: [
        ...defaultSessions(),
        // A subagent session: `/v1/sessions` hides it unless asked for.
        {
          sessionId: 's-sub',
          parentSessionId: 's-alpha',
          origin: 'subagent',
          updatedAt: 1_700_000_900_000,
          running: false,
          blank: false,
          agentAvailable: true,
          formatStatus: 'current',
          cwd: 'E:/work/alpha',
        },
      ],
    })
    await mock.start()
    resources.add(() => mock.stop())
    const started = await startGateway({ mock, resources, config: { ringSize: 16 } })
    gateway = started.gateway
    base = started.lanBase
    await waitForHealth(base)
    await waitForDsh(gateway)
    await waitForSession(gateway, 's-alpha')
    await waitFor(() => (gateway.hub.getWorkspaces() ?? []).length >= 2, { label: 'workspace baseline' })
  })

  after(async () => {
    await resources.disposeAll()
  })

  beforeEach(() => {
    mock.reset()
  })

  it('GET /v1/health returns the discovery envelope', async () => {
    const response = await request(base, '/v1/health')
    assert.equal(response.status, 200)
    assert.equal(response.json.ok, true)
    assert.equal(response.json.v, 1)
    assert.equal(response.json.dsh, '0.2.2-rc.1')
    assert.equal(response.json.lan, true)
    assert.equal(response.json.paired, false)
    assert.equal(typeof response.json.name, 'string')
    assert.ok(response.json.name.length > 0)
    assert.equal(typeof response.json.uptimeMs, 'number')
    assert.ok(response.json.uptimeMs >= 0)
    assert.equal(response.headers.get('cache-control'), 'no-store')
  })

  it('GET /v1/workspaces serves the workspace/follow baseline', async () => {
    const response = await request(base, '/v1/workspaces')
    assert.equal(response.status, 200)
    assert.equal(response.json.ok, true)
    const ids = response.json.workspaces.map((workspace) => workspace.workspaceId)
    assert.deepEqual(ids.sort(), ['w-alpha', 'w-beta'])
    const alpha = response.json.workspaces.find((workspace) => workspace.workspaceId === 'w-alpha')
    assert.equal(alpha.path, 'E:/work/alpha')
  })

  it('GET /v1/sessions lists DSH sessions and caches them', async () => {
    const response = await request(base, '/v1/sessions')
    assert.equal(response.status, 200)
    assert.equal(response.json.ok, true)
    const ids = response.json.sessions.map((session) => session.sessionId)
    assert.ok(ids.includes('s-alpha'))
    assert.ok(ids.includes('s-beta'))
    const alpha = response.json.sessions.find((session) => session.sessionId === 's-alpha')
    assert.equal(alpha.agentAvailable, true)
    assert.equal(mock.callsFor('session/list').length, 1)
    assert.deepEqual(mock.lastCall('session/list').args, { _request: {} })
  })

  it('hides subagent sessions unless ?includeSubagents=1', async () => {
    const filtered = await request(base, '/v1/sessions')
    assert.equal(filtered.status, 200)
    assert.equal(filtered.json.ok, true)
    assert.equal(
      filtered.json.sessions.some((session) => session.sessionId === 's-sub'),
      false,
      'subagent sessions should be filtered by default',
    )

    const all = await request(base, '/v1/sessions?includeSubagents=1')
    assert.equal(all.status, 200)
    assert.equal(all.json.ok, true)
    assert.equal(all.json.sessions.some((session) => session.sessionId === 's-sub'), true)
  })

  it('filters sessions by workspaceId using the workspace baseline membership', async () => {
    // Mock: w-alpha.sessionIds = ['s-alpha'], w-beta.sessionIds = ['s-beta'].
    const alpha = await request(base, '/v1/sessions?workspaceId=w-alpha')
    assert.equal(alpha.status, 200)
    assert.equal(alpha.json.ok, true)
    assert.deepEqual(alpha.json.sessions.map((session) => session.sessionId), ['s-alpha'])

    const beta = await request(base, '/v1/sessions?workspaceId=w-beta')
    assert.deepEqual(beta.json.sessions.map((session) => session.sessionId), ['s-beta'])

    // Unknown workspace: empty list, not an error.
    const unknown = await request(base, '/v1/sessions?workspaceId=nope')
    assert.equal(unknown.status, 200)
    assert.equal(unknown.json.ok, true)
    assert.deepEqual(unknown.json.sessions, [])

    // Combined with includeSubagents: workspace membership still wins.
    const combined = await request(base, '/v1/sessions?workspaceId=w-alpha&includeSubagents=1')
    assert.deepEqual(combined.json.sessions.map((session) => session.sessionId), ['s-alpha'])
  })

  it('POST /v1/sessions creates a session through session/create', async () => {
    const response = await request(base, '/v1/sessions', {
      method: 'POST',
      body: { cwd: 'E:/work/new', title: 'ignored' },
    })
    assert.equal(response.status, 200)
    assert.equal(response.json.ok, true)
    assert.equal(typeof response.json.sessionId, 'string')
    assert.ok(response.json.sessionId.length > 0)
    const call = mock.lastCall('session/create')
    assert.ok(call, 'session/create should be called')
    assert.equal(call.args.request.cwd, 'E:/work/new')
    assert.equal(call.args.request.workspaceId, undefined)
  })

  it('POST /v1/sessions rejects workspaceId+cwd together', async () => {
    const response = await request(base, '/v1/sessions', {
      method: 'POST',
      body: { workspaceId: 'w-alpha', cwd: 'E:/work/x' },
    })
    assert.equal(response.status, 400)
    assert.equal(response.json.ok, false)
    assert.equal(response.json.error.code, 'invalid_request')
  })

  it('GET /v1/sessions/{id}/state reports running/pendingInput and the raw state', async () => {
    const response = await request(base, '/v1/sessions/s-alpha/state')
    assert.equal(response.status, 200)
    assert.equal(response.json.ok, true)
    assert.equal(response.json.sessionId, 's-alpha')
    assert.equal(response.json.running, false)
    assert.equal(response.json.pendingInput, false)
    assert.equal(response.json.state.sessionId, undefined)
    assert.equal(mock.lastCall('session/state').args.request.sessionId, 's-alpha')

    // A pending approval surfaces as pendingInput=true.
    const waterfall = mock.waterfall('approval/request', { agentId: 's-alpha', request: { toolName: 'pwsh' } })
    await waitFor(() => gateway.hub.listInterventions('pending').length === 1, { label: 'pending intervention' })
    const withPending = await request(base, '/v1/sessions/s-alpha/state')
    assert.equal(withPending.json.pendingInput, true)
    mock.cancel(waterfall.eventId)
  })

  it('GET /v1/sessions/{id}/messages pages session/events history', async () => {
    const response = await request(base, '/v1/sessions/s-alpha/messages?limit=50')
    assert.equal(response.status, 200)
    assert.equal(response.json.ok, true)
    const messages = response.json.messages
    assert.ok(messages.length >= 2)
    assert.deepEqual(messages.map((message) => message.seq), [...messages.map((message) => message.seq)].sort((a, b) => a - b))
    const user = messages.find((message) => message.role === 'user')
    const assistant = messages.find((message) => message.role === 'assistant')
    assert.equal(user.text, 'hello alpha')
    // 用户消息的来源标记（`user` vs `tool-jobs`/`runtime-context`）必须透传给客户端。
    assert.equal(user.source.kind, 'user')
    assert.equal(assistant.text, 'hi from alpha')
    assert.equal(typeof user.time, 'number')
    assert.equal(typeof messages[0].kind, 'string')
    assert.equal(response.json.hasMore, false)
    assert.equal(typeof response.json.cursor, 'number')

    const call = mock.lastCall('session/events')
    assert.deepEqual(call.args.request.address, { kind: 'session', sessionId: 's-alpha' })
    assert.equal(call.args.request.maxMessages, 50)

    // Backward cursor: only messages strictly before seq 3.
    const page = await request(base, '/v1/sessions/s-alpha/messages?beforeSeq=3')
    assert.equal(page.status, 200)
    assert.equal(page.json.messages.every((message) => message.seq < 3), true)
    assert.equal(mock.lastCall('session/events').args.request.beforeSeq, 3)
  })

  it('can include the raw DSH record when asked', async () => {
    const response = await request(base, '/v1/sessions/s-alpha/messages?raw=1&limit=10')
    assert.equal(response.status, 200)
    assert.equal(response.json.ok, true)
    const user = response.json.messages.find((message) => message.role === 'user')
    assert.ok(user, 'user message present')
    assert.equal(typeof user.raw, 'object')
    assert.equal(user.raw.type, 'user/message')
  })

  it('POST /v1/sessions/{id}/messages sends a prompt', async () => {
    const response = await request(base, '/v1/sessions/s-alpha/messages', {
      method: 'POST',
      body: { text: 'run the tests', mode: 'steer' },
    })
    assert.equal(response.status, 200)
    assert.equal(response.json.ok, true)
    assert.equal(response.json.sessionId, 's-alpha')
    assert.equal(response.json.accepted, true)

    const call = mock.lastCall('session/prompt')
    assert.ok(call, 'session/prompt should be called')
    assert.equal(call.args.request.sessionId, 's-alpha')
    assert.equal(call.args.request.mode, 'steer')
    assert.deepEqual(call.args.request.content, [{ type: 'text', text: 'run the tests' }])
    assert.equal(typeof call.args.request.requestId, 'string')
    assert.ok(call.args.request.requestId.length > 0)
  })

  it('POST /v1/sessions/{id}/messages defaults to queue mode and rejects empty text', async () => {
    await request(base, '/v1/sessions/s-alpha/messages', { method: 'POST', body: { text: 'hi' } })
    assert.equal(mock.lastCall('session/prompt').args.request.mode, 'queue')

    const empty = await request(base, '/v1/sessions/s-alpha/messages', { method: 'POST', body: { text: '   ' } })
    assert.equal(empty.status, 400)
    assert.equal(empty.json.error.code, 'invalid_request')

    const noBody = await request(base, '/v1/sessions/s-alpha/messages', { method: 'POST', body: {} })
    assert.equal(noBody.status, 400)
  })

  it('POST /v1/sessions/{id}/interrupt cancels the turn', async () => {
    const response = await request(base, '/v1/sessions/s-alpha/interrupt', { method: 'POST' })
    assert.equal(response.status, 200)
    assert.deepEqual(response.json, { ok: true })
    assert.deepEqual(mock.lastCall('session/cancel').args, { request: { sessionId: 's-alpha' } })
  })

  it('POST /v1/sessions/{id}/model selects a model', async () => {
    const response = await request(base, '/v1/sessions/s-alpha/model', {
      method: 'POST',
      body: { provider: 'deepseek', model: 'deepseek-reasoner', reasoningEffort: 'high' },
    })
    assert.equal(response.status, 200)
    assert.deepEqual(response.json, { ok: true })
    const call = mock.lastCall('session/selectModel')
    assert.deepEqual(call.args, {
      request: {
        sessionId: 's-alpha',
        provider: 'deepseek',
        model: 'deepseek-reasoner',
        reasoningEffort: 'high',
      },
    })

    const missing = await request(base, '/v1/sessions/s-alpha/model', { method: 'POST', body: { model: 'x' } })
    assert.equal(missing.status, 400)
    assert.equal(missing.json.error.code, 'invalid_request')
  })

  it('GET /v1/models returns the catalog as-is', async () => {
    const response = await request(base, '/v1/models')
    assert.equal(response.status, 200)
    assert.equal(response.json.ok, true)
    assert.equal(response.json.catalog.default.provider, 'deepseek')
    assert.ok(Array.isArray(response.json.catalog.groups))
  })

  it('GET /v1/events replays hub events sinceSeq with gap=false', async () => {
    const before = await request(base, '/v1/events?sinceSeq=0')
    assert.equal(before.status, 200)
    assert.equal(before.json.ok, true)
    assert.equal(typeof before.json.seq, 'number')
    assert.equal(before.json.gap, false)

    mock.sessionStatus('s-alpha', true)
    await waitFor(() => gateway.hub.isSessionRunning('s-alpha'), { label: 'session running' })
    // Session-scoped events live on the `session:<id>` topic (Lead-confirmed);
    // `global` is a *subscription* filter, not the stored topic value.
    const after = await request(base, `/v1/events?sinceSeq=${before.json.seq}&topics=session:s-alpha`)
    assert.equal(after.json.ok, true)
    assert.equal(after.json.gap, false)
    const statusEvent = after.json.events.find((event) => event.kind === 'session.status')
    assert.ok(statusEvent, 'session.status event should be replayed')
    assert.equal(statusEvent.data.sessionId, 's-alpha')
    assert.equal(statusEvent.data.running, true)
    assert.equal(typeof statusEvent.id, 'string')
    assert.equal(typeof statusEvent.ts, 'number')
    assert.equal(statusEvent.topic, 'session:s-alpha')
  })

  it('GET /v1/events detects a ring gap', async () => {
    // ringSize is clamped to >=16; push enough global events to evict seq 1.
    for (let index = 0; index < 25; index += 1) gateway.hub.publish('global', 'noise', { index })
    const response = await request(base, '/v1/events?sinceSeq=1')
    assert.equal(response.status, 200)
    assert.equal(response.json.gap, true)
  })

  it('answers 404 for unknown routes and 405 for a wrong method', async () => {
    const notFound = await request(base, '/v1/nope')
    assert.equal(notFound.status, 404)
    assert.equal(notFound.json.ok, false)
    assert.equal(notFound.json.error.code, 'not_found')
    assert.equal(typeof notFound.json.error.message, 'string')

    const wrongMethod = await request(base, '/v1/sessions/s-alpha/interrupt')
    assert.equal(wrongMethod.status, 405)
    assert.equal(wrongMethod.json.ok, false)

    const outside = await request(base, '/other')
    assert.equal(outside.status, 404)
  })

  it('answers CORS preflight on /v1', async () => {
    const response = await request(base, '/v1/health', { method: 'OPTIONS' })
    assert.equal(response.status, 204)
    assert.equal(response.headers.get('access-control-allow-origin'), '*')
    assert.match(response.headers.get('access-control-allow-methods') ?? '', /GET/)
    assert.match(response.headers.get('access-control-allow-headers') ?? '', /authorization/i)
  })

  it('POST /v1/pair exchanges a code for a device token over the LAN port', async () => {
    const pairing = gateway.auth.createPairingCode([base])
    const response = await request(base, '/v1/pair', {
      method: 'POST',
      body: { code: pairing.code, deviceName: 'Pixel 8', deviceId: 'device-rest-1' },
    })
    assert.equal(response.status, 200)
    assert.equal(response.json.ok, true)
    assert.equal(typeof response.json.deviceToken, 'string')
    assert.equal(response.json.deviceId, 'device-rest-1')
    assert.equal(response.json.expiresAt, null)

    const bad = await request(base, '/v1/pair', {
      method: 'POST',
      body: { code: 'nope', deviceName: 'x', deviceId: 'y' },
    })
    assert.equal(bad.status, 400)
    assert.equal(bad.json.ok, false)
    assert.equal(bad.json.error.code, 'invalid_code')
  })

  it('returns 503 dsh_unavailable while the DSH link is down', async () => {
    mock.dropMux()
    await waitFor(() => gateway.dsh?.connected === false, { label: 'dsh link down' })
    const response = await request(base, '/v1/models')
    assert.equal(response.status, 503)
    assert.equal(response.json.ok, false)
    assert.equal(response.json.error.code, 'dsh_unavailable')

    await waitFor(() => gateway.dsh?.connected === true, { label: 'dsh link back', timeoutMs: 15_000 })
    const recovered = await request(base, '/v1/models')
    assert.equal(recovered.status, 200)
    assert.equal(recovered.json.ok, true)
  })

  it('no longer serves an administrative page on the LAN listener', async () => {
    // The /admin page is gone: the panel moved into the Web UI, and its host
    // routes live on the DSH web server (see panel.test.mjs), not here.
    for (const path of ['/admin', '/admin/api/state', '/admin/api/pairing']) {
      const response = await request(base, path)
      assert.equal(response.status, 404, `${path} must not exist on the LAN edge`)
      assert.equal(response.json.ok, false)
    }
  })

  it('advertises LAN pairing over plain HTTP without a certificate', async () => {
    // The snapshot is built by the same code the panel routes use; assert the
    // URL policy on the gateway object so the LAN edge stays page-free.
    const state = await gateway.api.panelState()
    assert.ok(Array.isArray(state.lanUrls))
    for (const url of state.lanUrls) assert.match(url, /^http:\/\//, 'LAN URLs must be plain HTTP')
    for (const url of state.publicUrls) assert.match(url, /^https:\/\//, 'public URLs keep HTTPS')

    const pairing = await gateway.api.panelPairingCreate()
    assert.equal(typeof pairing.code, 'string')
    // This gateway has httpsPort=0, so the pairing payload is LAN-only.
    assert.deepEqual(pairing.publicUrls, [])
    if (pairing.urls.length > 0) {
      assert.match(pairing.urls[0], /^http:\/\//, 'LAN plain HTTP must be the first candidate')
      assert.equal(pairing.url, pairing.urls[0])
      assert.equal(pairing.lanUrl, pairing.urls[0])
      assert.match(pairing.uri, /lan=/)
      assert.match(pairing.uri, /^dshgw:\/\/pair\?/)
    }
  })
})
