/**
 * Self-check for `test/mock-dsh.mjs`.
 *
 * The mock is the contract oracle for every other test file, so its wire
 * shapes are asserted here against the real DSH 0.2.2 protocol (see
 * `.dsh-recon/.../dsh-api-gateway/lib/types/stream-protocol.js`).
 *
 * @module dsh-gateway/test/mock-dsh.test
 */

import assert from 'node:assert/strict'
import { after, describe, it } from 'node:test'

import { MockDsh, MOCK_COOKIE_NAME, MOCK_COOKIE_VALUE } from './mock-dsh.mjs'
import { StreamClient, createResources, request, sleep, waitFor } from './helper.mjs'

/**
 * Start a mock and register teardown on the ambient `after` hook.
 * @param {object} [options]
 */
async function startMock(options = {}) {
  const mock = new MockDsh(options)
  await mock.start()
  MOCKS.push(mock)
  return mock
}

/** @type {MockDsh[]} */
const MOCKS = []
after(async () => {
  for (const mock of MOCKS) await mock.stop()
})

/** Build the `cookie` header the plugin would send. */
const cookieHeader = () => ({ cookie: `${MOCK_COOKIE_NAME}=${MOCK_COOKIE_VALUE}` })

/** Wrap a request body into the descriptor wire name the real host expects. */
function wireArgs(method, request) {
  switch (method) {
    case 'session/list':
      return { _request: request }
    case 'session/modelCatalog':
      return {}
    case '$events/result':
      return request
    default:
      return { request }
  }
}

/** POST one client-request envelope (wire-shaped args). */
async function rpc(mock, method, request = {}, options = {}) {
  return rpcRaw(mock, method, options.args !== undefined ? options.args : wireArgs(method, request), options)
}

/** POST one client-request envelope with hand-built args. */
async function rpcRaw(mock, method, args, options = {}) {
  const body = options.envelope ?? {
    type: 'client-request',
    rpcId: `rpc-${Math.random().toString(16).slice(2)}`,
    method,
    payload: { args },
  }
  return request(mock.origin, `/api/${method}`, {
    method: 'POST',
    headers: { ...cookieHeader(), ...(options.headers ?? {}) },
    body,
  })
}

/** Open one logical stream on the mock mux. */
async function openStream(mock, frames) {
  const client = await StreamClient.connect(`${mock.origin.replace('http', 'ws')}/api/remote.mux`, {
    headers: cookieHeader(),
  })
  for (const frame of frames) client.send(frame)
  return client
}

describe('mock-dsh: cookie exchange', () => {
  it('303 + Set-Cookie for a valid launch token', async () => {
    const mock = await startMock({ launchToken: 'token-abc' })
    const response = await fetch(`${mock.origin}/?token=token-abc`, { redirect: 'manual' })
    assert.equal(response.status, 303)
    assert.equal(response.headers.get('location'), './')
    const cookies = response.headers.getSetCookie()
    assert.equal(cookies.length, 1)
    assert.match(cookies[0], /^dsh-auth-test=v1\.test;/)
    assert.match(cookies[0], /HttpOnly/)
    assert.match(cookies[0], /Path=\//)
  })

  it('401 for a wrong or missing launch token', async () => {
    const mock = await startMock({ launchToken: 'token-abc' })
    const wrong = await fetch(`${mock.origin}/?token=nope`, { redirect: 'manual' })
    assert.equal(wrong.status, 401)
    const missing = await fetch(`${mock.origin}/`, { redirect: 'manual' })
    assert.equal(missing.status, 401)
  })
})

describe('mock-dsh: unary RPC', () => {
  it('rejects requests without the session cookie', async () => {
    const mock = await startMock()
    const response = await request(mock.origin, '/api/session/list', {
      method: 'POST',
      body: { type: 'client-request', rpcId: 'r1', method: 'session/list', payload: { args: {} } },
    })
    assert.equal(response.status, 401)
    assert.equal(mock.callsFor('session/list').length, 0)
  })

  it('answers the server-response envelope and records the call', async () => {
    const mock = await startMock()
    const response = await rpc(mock, 'session/list', { cursor: 'next' })
    assert.equal(response.status, 200)
    assert.equal(response.json.type, 'server-response')
    assert.equal(response.json.result.ok, true)
    assert.ok(Array.isArray(response.json.result.value.items))
    assert.equal(response.json.result.value.items.length, 2)
    assert.equal(response.json.rpcId, mock.calls[0].rpcId)
    assert.deepEqual(mock.callsFor('session/list')[0].args, { _request: { cursor: 'next' } })
  })

  it('echoes the client rpcId', async () => {
    const mock = await startMock()
    const response = await request(mock.origin, '/api/session/state', {
      method: 'POST',
      headers: cookieHeader(),
      body: { type: 'client-request', rpcId: 'fixed-rpc-id', method: 'session/state', payload: { args: { request: { sessionId: 's-alpha' } } } },
    })
    assert.equal(response.json.rpcId, 'fixed-rpc-id')
    assert.equal(response.json.result.value.running, false)
  })

  it('rejects a mismatched method and an invalid envelope', async () => {
    const mock = await startMock()
    const mismatch = await request(mock.origin, '/api/session/list', {
      method: 'POST',
      headers: cookieHeader(),
      body: { type: 'client-request', rpcId: 'r1', method: 'session/state', payload: { args: {} } },
    })
    assert.equal(mismatch.json.result.ok, false)
    assert.equal(mismatch.json.result.error.code, 'gateway/bad-request')

    const invalid = await request(mock.origin, '/api/session/list', {
      method: 'POST',
      headers: cookieHeader(),
      body: { rpcId: 'r2' },
    })
    // Real host: malformed envelope still answers HTTP 200 + gateway/bad-request.
    assert.equal(invalid.status, 200)
    assert.equal(invalid.json.rpcId, 'invalid-request')
    assert.equal(invalid.json.result.ok, false)
    assert.equal(invalid.json.result.error.code, 'gateway/bad-request')
  })

  it('404s endpoints nobody claimed and 415s non-JSON content types', async () => {
    const mock = await startMock()
    const response = await rpc(mock, 'settings/read')
    assert.equal(response.status, 404)

    const wrongType = await request(mock.origin, '/api/session/list', {
      method: 'POST',
      headers: { ...cookieHeader(), 'content-type': 'text/plain' },
      body: '{"type":"client-request"}',
    })
    assert.equal(wrongType.status, 415)
  })

  it('enforces the descriptor wire names (notes §2.1)', async () => {
    const mock = await startMock()

    // session/list needs `_request`.
    const missingList = await rpcRaw(mock, 'session/list', {})
    assert.equal(missingList.status, 200)
    assert.equal(missingList.json.result.ok, false)
    assert.equal(missingList.json.result.error.code, 'gateway/arguments-invalid')
    assert.match(missingList.json.result.error.message, /missing "_request"/)

    const wrongKey = await rpcRaw(mock, 'session/list', { request: {} })
    assert.equal(wrongKey.json.result.error.code, 'gateway/arguments-invalid')

    // session/state needs `request`.
    const missingRequest = await rpcRaw(mock, 'session/state', { sessionId: 's-alpha' })
    assert.equal(missingRequest.json.result.ok, false)
    assert.equal(missingRequest.json.result.error.code, 'gateway/arguments-invalid')

    // No-arg endpoints reject any key.
    const extraArg = await rpcRaw(mock, 'session/modelCatalog', { request: {} })
    assert.equal(extraArg.json.result.ok, false)
    assert.equal(extraArg.json.result.error.code, 'gateway/arguments-invalid')

    // Extra keys are rejected even alongside the right one.
    const extraKey = await rpcRaw(mock, 'session/state', { request: { sessionId: 's-alpha' }, extra: 1 })
    assert.equal(extraKey.json.result.ok, false)
    assert.match(extraKey.json.result.error.message, /unexpected "extra"/)

    // $events/result is flat with three required keys.
    const flatMissing = await rpcRaw(mock, '$events/result', { clientId: 'c', eventId: 'e' })
    assert.equal(flatMissing.json.result.ok, false)
    assert.equal(flatMissing.json.result.error.code, 'gateway/arguments-invalid')
  })

  it('injects one-shot and sticky failures', async () => {
    const mock = await startMock()
    mock.failMethod('session/list', { code: 'gateway/internal', message: 'boom' })
    const failed = await rpc(mock, 'session/list')
    assert.equal(failed.json.result.ok, false)
    assert.equal(failed.json.result.error.message, 'boom')
    const recovered = await rpc(mock, 'session/list')
    assert.equal(recovered.json.result.ok, true)

    mock.failMethod('session/state', { code: 'gateway/internal', once: false })
    assert.equal((await rpc(mock, 'session/state', { sessionId: 's-alpha' })).json.result.ok, false)
    assert.equal((await rpc(mock, 'session/state', { sessionId: 's-alpha' })).json.result.ok, false)
    mock.clearFailure('session/state')
    assert.equal((await rpc(mock, 'session/state', { sessionId: 's-alpha' })).json.result.ok, true)
  })

  it('serves programmable results', async () => {
    const mock = await startMock()
    const catalog = { default: { provider: 'p', model: 'm' }, routableProviders: [], groups: [], failures: [] }
    mock.setResult('session/modelCatalog', catalog)
    const response = await rpc(mock, 'session/modelCatalog')
    assert.deepEqual(response.json.result.value, catalog)

    mock.setResult('session/list', (request) => ({ items: [{ sessionId: `dynamic-${request.cursor ?? 0}` }] }))
    const dynamic = await rpc(mock, 'session/list', { cursor: 7 })
    assert.equal(dynamic.json.result.value.items[0].sessionId, 'dynamic-7')
  })

  it('refuses everything while disabled, then recovers', async () => {
    const mock = await startMock()
    mock.disable()
    const disabled = await rpc(mock, 'session/list')
    assert.equal(disabled.status, 503)
    mock.enable()
    const recovered = await rpc(mock, 'session/list')
    assert.equal(recovered.json.result.ok, true)
  })
})

describe('mock-dsh: mux', () => {
  it('rejects upgrades without the session cookie', async () => {
    const mock = await startMock()
    await assert.rejects(
      StreamClient.connect(`${mock.origin.replace('http', 'ws')}/api/remote.mux`, { timeoutMs: 2_000 }),
      /HTTP 401|socket hang up|ECONNRESET/i,
    )
  })

  it('rejects unknown upgrade paths', async () => {
    const mock = await startMock()
    await assert.rejects(
      StreamClient.connect(`${mock.origin.replace('http', 'ws')}/api/other`, {
        headers: cookieHeader(),
        timeoutMs: 2_000,
      }),
      /HTTP 404|socket hang up|ECONNRESET/i,
    )
  })

  it('delivers ready then emit/waterfall/cancel on $events', async () => {
    const mock = await startMock()
    const client = await openStream(mock, [
      { type: 'open', streamId: 's1', endpoint: '$events', payload: { args: {} } },
    ])
    try {
      const ready = await client.waitForFrame((frame) => frame.type === 'item' && frame.streamId === 's1' && frame.value?.type === 'ready')
      assert.equal(typeof ready.value.clientId, 'string')
      assert.ok(ready.value.clientId.length > 0)
      assert.deepEqual(ready.value.host, { home: '/mock/home' })

      mock.emit('api-session/status', ['s-alpha', true])
      const emitted = await client.waitForFrame((frame) => frame.value?.type === 'emit')
      assert.equal(emitted.value.event, 'api-session/status')
      assert.deepEqual(emitted.value.args, ['s-alpha', true])

      const waterfall = mock.waterfall('approval/request', { agentId: 's-alpha', request: { toolName: 'pwsh' } })
      const delivered = await client.waitForFrame((frame) => frame.value?.type === 'waterfall')
      assert.deepEqual(delivered.value, waterfall)

      mock.cancel(waterfall.eventId)
      const cancelled = await client.waitForFrame((frame) => frame.value?.type === 'cancel')
      assert.equal(cancelled.value.eventId, waterfall.eventId)
    } finally {
      await client.close()
    }
  })

  it('answers $events/result and only cancels for other clients', async () => {
    const mock = await startMock()
    const first = await openStream(mock, [{ type: 'open', streamId: 'a', endpoint: '$events', payload: { args: {} } }])
    const second = await openStream(mock, [{ type: 'open', streamId: 'b', endpoint: '$events', payload: { args: {} } }])
    try {
      const readyA = await first.waitForFrame((frame) => frame.value?.type === 'ready')
      await second.waitForFrame((frame) => frame.value?.type === 'ready')
      const waterfall = mock.waterfall('approval/request', { agentId: 's-alpha' })
      await first.waitForFrame((frame) => frame.value?.type === 'waterfall')
      await second.waitForFrame((frame) => frame.value?.type === 'waterfall')

      const response = await rpc(mock, '$events/result', {
        clientId: readyA.value.clientId,
        eventId: waterfall.eventId,
        outcome: { kind: 'result', value: 'allowed-once' },
      })
      assert.equal(response.json.result.ok, true)
      assert.equal(mock.resultCalls.length, 1)
      assert.deepEqual(mock.resultCalls[0].outcome, { kind: 'result', value: 'allowed-once' })
      assert.equal(mock.resultCalls[0].known, true)

      const other = await second.waitForFrame((frame) => frame.value?.type === 'cancel')
      assert.equal(other.value.eventId, waterfall.eventId)
      assert.equal(mock.pendingWaterfalls().length, 0)
    } finally {
      await first.close()
      await second.close()
    }
  })

  it('fails $events/result with a stale clientId', async () => {
    const mock = await startMock()
    const client = await openStream(mock, [{ type: 'open', streamId: 's1', endpoint: '$events', payload: { args: {} } }])
    try {
      await client.waitForFrame((frame) => frame.value?.type === 'ready')
      const waterfall = mock.waterfall('approval/request', { agentId: 's-alpha' })
      const response = await rpc(mock, '$events/result', {
        clientId: 'stale-client',
        eventId: waterfall.eventId,
        outcome: { kind: 'result', value: 'rejected' },
      })
      assert.equal(response.json.result.ok, false)
      assert.equal(mock.pendingWaterfalls().length, 1)
    } finally {
      await client.close()
    }
  })

  it('replays unresolved waterfalls on reconnect and rotates clientId', async () => {
    const mock = await startMock()
    const first = await openStream(mock, [{ type: 'open', streamId: 's1', endpoint: '$events', payload: { args: {} } }])
    const readyOne = await first.waitForFrame((frame) => frame.value?.type === 'ready')
    const waterfall = mock.waterfall('approval/request', { agentId: 's-alpha' })
    await first.waitForFrame((frame) => frame.value?.type === 'waterfall')

    mock.dropMux()
    await waitFor(() => mock.muxCount() === 0, { label: 'mux dropped' })
    await first.waitForClose(2_000)

    const second = await openStream(mock, [{ type: 'open', streamId: 's2', endpoint: '$events', payload: { args: {} } }])
    try {
      const readyTwo = await second.waitForFrame((frame) => frame.value?.type === 'ready')
      assert.notEqual(readyTwo.value.clientId, readyOne.value.clientId)
      const replayed = await second.waitForFrame((frame) => frame.value?.type === 'waterfall')
      assert.equal(replayed.value.eventId, waterfall.eventId)
    } finally {
      await second.close()
    }
  })

  it('pushes session/control baseline and projections', async () => {
    const mock = await startMock()
    const client = await openStream(mock, [{ type: 'open', streamId: 'c', endpoint: 'session/control', payload: { args: {} } }])
    try {
      const baseline = await client.waitForFrame((frame) => frame.value?.type === 'baseline')
      assert.ok(baseline.value.value.projections['s-alpha'])
      assert.equal(baseline.value.value.projections['s-alpha'].asOfSeq, 0)

      mock.sessionProjection('s-alpha', [{ key: 'title', val: 'Renamed' }])
      const projection = await client.waitForFrame((frame) => frame.value?.type === 'projection')
      assert.equal(projection.value.sessionId, 's-alpha')
      assert.deepEqual(projection.value.changes, [{ key: 'title', val: 'Renamed' }])
      assert.equal(projection.value.seq, 1)
    } finally {
      await client.close()
    }
  })

  it('pushes workspace/follow baseline and upserts', async () => {
    const mock = await startMock()
    const client = await openStream(mock, [{ type: 'open', streamId: 'w', endpoint: 'workspace/follow', payload: { args: {} } }])
    try {
      const baseline = await client.waitForFrame((frame) => frame.value?.type === 'baseline')
      assert.equal(baseline.value.value.items.length, 2)
      assert.deepEqual(baseline.value.value.items.map((item) => item.workspaceId), ['w-alpha', 'w-beta'])
      assert.deepEqual(baseline.value.value.archivedSessionIds, [])

      mock.workspaceUpsert({ workspaceId: 'w-gamma', path: 'E:/work/gamma', title: 'gamma', sessionIds: [] })
      const upsert = await client.waitForFrame((frame) => frame.value?.type === 'upsert')
      assert.equal(upsert.value.workspace.workspaceId, 'w-gamma')
    } finally {
      await client.close()
    }
  })

  it('pushes session/follow snapshot, events and assistant-stream frames', async () => {
    const mock = await startMock()
    const client = await openStream(mock, [
      {
        type: 'open',
        streamId: 'f',
        endpoint: 'session/follow',
        payload: { args: { request: { address: { kind: 'session', sessionId: 's-alpha' }, assistantStream: true } } },
      },
    ])
    try {
      const snapshot = await client.waitForFrame((frame) => frame.value?.type === 'snapshot')
      assert.equal(snapshot.value.header.id, 's-alpha')
      assert.equal(snapshot.value.hasMore, false)
      assert.ok(snapshot.value.records.length >= 4)
      assert.equal(snapshot.value.records[0].type, 'event')

      mock.sessionEvent('s-alpha', 'assistant/message', { turn: 1, step: 1, message: { role: 'assistant', content: [] }, stream: [] })
      const event = await client.waitForFrame((frame) => frame.value?.type === 'event')
      assert.equal(event.value.event.type, 'assistant/message')
      assert.equal(typeof event.value.event.seq, 'number')

      mock.assistantStream('s-alpha', { kind: 'delta', text: 'hel' })
      const streamed = await client.waitForFrame((frame) => frame.value?.type === 'assistant-stream')
      assert.deepEqual(streamed.value.frame, { kind: 'delta', text: 'hel' })

      // A frame for another session must not leak onto this stream.
      mock.sessionEvent('s-beta', 'assistant/message', { turn: 1, step: 1, message: {}, stream: [] })
      await sleep(50)
      assert.equal(client.frames.filter((frame) => frame.value?.type === 'event').length, 1)
    } finally {
      await client.close()
    }
  })

  it('records uplink items and rejects malformed stream opens', async () => {
    const mock = await startMock()
    const client = await openStream(mock, [
      {
        type: 'open',
        streamId: 'u',
        endpoint: 'session/follow',
        payload: { args: { request: { address: { kind: 'session', sessionId: 's-alpha' } } } },
      },
    ])
    try {
      await client.waitForFrame((frame) => frame.value?.type === 'snapshot')
      client.send({ type: 'item', streamId: 'u', value: { ping: 1 } })
      await waitFor(() => mock.uplinkItems.length === 1, { label: 'uplink item recorded' })
      assert.deepEqual(mock.uplinkItems[0].value, { ping: 1 })

      // Unknown endpoint: error + end on that stream, socket stays alive.
      client.send({ type: 'open', streamId: 'bad', endpoint: 'nope/nope', payload: { args: {} } })
      const error = await client.waitForFrame((frame) => frame.type === 'error' && frame.streamId === 'bad')
      assert.equal(error.error.code, 'gateway/unknown-endpoint')
      await client.waitForFrame((frame) => frame.type === 'end' && frame.streamId === 'bad')

      // Missing wire name on session/follow: arguments-invalid + end.
      client.send({
        type: 'open',
        streamId: 'nowire',
        endpoint: 'session/follow',
        payload: { args: { address: { kind: 'session', sessionId: 's-alpha' } } },
      })
      const wireError = await client.waitForFrame((frame) => frame.type === 'error' && frame.streamId === 'nowire')
      assert.equal(wireError.error.code, 'gateway/arguments-invalid')
      assert.equal(client.closed, false, 'malformed open must not kill the socket')
    } finally {
      await client.close()
    }
  })

  it('closes the socket on a duplicate streamId (1008) and never answers cancel with end', async () => {
    const mock = await startMock()
    const client = await openStream(mock, [
      { type: 'open', streamId: 'dup', endpoint: '$events', payload: { args: {} } },
    ])
    await client.waitForFrame((frame) => frame.value?.type === 'ready')
    // Cancel a live stream: the real host stays silent (no end / no error).
    client.send({ type: 'cancel', streamId: 'dup' })
    await sleep(80)
    const endFrames = client.frames.filter((frame) => frame.type === 'end')
    const errorFrames = client.frames.filter((frame) => frame.type === 'error')
    assert.equal(endFrames.length, 0)
    assert.equal(errorFrames.length, 0)

    // Re-opening the same streamId on one socket is fatal.
    client.send({ type: 'open', streamId: 'x', endpoint: '$events', payload: { args: {} } })
    client.send({ type: 'open', streamId: 'x', endpoint: '$events', payload: { args: {} } })
    const closed = await client.waitForClose(3_000)
    assert.equal(closed.code, 1008)
  })
})

describe('mock-dsh: lifecycle', () => {
  it('tears down cleanly and is idempotent', async () => {
    const mock = new MockDsh()
    await mock.start()
    assert.ok(mock.port > 0)
    assert.equal(mock.started, true)
    const client = await openStream(mock, [{ type: 'open', streamId: 's', endpoint: '$events', payload: { args: {} } }])
    await client.waitForFrame((frame) => frame.value?.type === 'ready')
    await mock.stop()
    assert.equal(mock.started, false)
    await mock.stop()
    await waitFor(() => client.closed, { label: 'client closed with server' })
  })

  it('works with the resources/teardown helper', async () => {
    const resources = createResources()
    const mock = new MockDsh()
    await mock.start()
    resources.add(() => mock.stop())
    const response = await rpc(mock, 'session/list')
    assert.equal(response.json.result.ok, true)
    await resources.disposeAll()
    assert.equal(mock.started, false)
  })
})
