/**
 * Approval / question interventions across the whole chain:
 *
 *   DSH `$events` waterfall → hub pending → `GET /v1/interventions`
 *   → `POST /v1/interventions/{id}/answer` → DSH `$events/result`
 *   → `intervention.resolved` on every device.
 *
 * Also covers dedupe of replayed waterfalls, cross-reconnect delivery, answers
 * from another client (mock `cancel`) and question payloads.
 *
 * @module dsh-gateway/test/intervention.test
 */

import assert from 'node:assert/strict'
import { after, before, beforeEach, describe, it } from 'node:test'

import {
  StreamClient,
  connectStream,
  createResources,
  libStatus,
  request,
  sleep,
  startGateway,
  waitFor,
  waitForDsh,
  waitForHealth,
} from './helper.mjs'
import { MockDsh } from './mock-dsh.mjs'

const REQUIRED = ['index.js', 'hub.js', 'dsh.js', 'http.js', 'ws.js']
const status = await libStatus()
const missing = REQUIRED.filter((name) => status.missing.includes(name))
const reason = missing.length === 0 ? undefined : `lib modules not implemented yet: ${missing.join(', ')}`

describe('/v1/interventions', { skip: reason }, () => {
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
    mock = new MockDsh()
    await mock.start()
    resources.add(() => mock.stop())
    const started = await startGateway({ mock, resources })
    gateway = started.gateway
    base = started.lanBase
    await waitForHealth(base)
    await waitForDsh(gateway)
    await waitFor(() => (gateway.hub.getSessions() ?? []).length >= 2, { label: 'session baseline' })
  })

  after(async () => {
    await resources.disposeAll()
  })

  beforeEach(() => {
    mock.reset()
    // Resolve anything a previous test left pending so the global list is a
    // meaningful assertion target for the current test.
    for (const entry of gateway.hub.listInterventions('pending')) {
      gateway.hub.removeIntervention(entry.eventId, 'cancelled', 'other')
    }
  })

  /** A phone-like stream subscribed to one session, with a stable device id. */
  async function connectPhone(deviceId) {
    const client = await connectStream(resources, base)
    await client.waitForType('ready')
    client.send({ t: 'hello', deviceId })
    client.send({ t: 'sub', id: 'sub-alpha', topic: 'session:s-alpha' })
    await waitFor(() => {
      const conns = [...(gateway.ws?.connections ?? [])]
      return conns.some(
        (conn) => conn.deviceId === deviceId && [...(conn.subs?.values?.() ?? [])].includes('session:s-alpha'),
      )
    }, { label: `phone ${deviceId} subscribed` })
    return client
  }

  /** Wait until the hub tracks one pending intervention. */
  function waitForPending(eventId) {
    return waitFor(() => gateway.hub.getIntervention(eventId)?.status === 'pending', {
      label: `pending ${eventId}`,
    })
  }

  it('turns a $events waterfall into a pending intervention and broadcasts it', async () => {
    const phone = await connectPhone('dev-int-1')
    try {
      const waterfall = mock.waterfall('approval/request', {
        agentId: 's-alpha',
        request: { toolName: 'pwsh', callId: 'call-1', reason: 'run the test suite' },
      })
      await waitForPending(waterfall.eventId)

      const event = await phone.waitForFrame(
        (frame) => frame.t === 'event' && frame.kind === 'approval.request' && frame.data?.eventId === waterfall.eventId,
        { label: 'approval.request broadcast' },
      )
      assert.equal(event.topic, 'session:s-alpha')
      assert.equal(event.data.sessionId, 's-alpha')

      const list = await request(base, '/v1/interventions?status=pending')
      assert.equal(list.status, 200)
      assert.equal(list.json.ok, true)
      const entry = list.json.interventions.find((item) => item.eventId === waterfall.eventId)
      assert.ok(entry, 'intervention should be listed')
      assert.equal(entry.sessionId, 's-alpha')
      assert.equal(entry.kind, 'approval.request')
      assert.equal(typeof entry.createdAt, 'number')
      assert.equal(entry.data.toolName, 'pwsh')
    } finally {
      await phone.close()
    }
  })

  it('answers an approval through $events/result with the latest clientId', async () => {
    const phone = await connectPhone('dev-int-2')
    try {
      const waterfall = mock.waterfall('approval/request', {
        agentId: 's-alpha',
        request: { toolName: 'pwsh', callId: 'call-2' },
      })
      await waitForPending(waterfall.eventId)

      const response = await request(base, `/v1/interventions/${waterfall.eventId}/answer`, {
        method: 'POST',
        body: { value: 'allowed-once' },
      })
      assert.equal(response.status, 200, response.text)
      assert.deepEqual(response.json, { ok: true })

      await waitFor(() => mock.resultCalls.length === 1, { label: '$events/result call' })
      const result = mock.resultCalls[0]
      assert.equal(result.eventId, waterfall.eventId)
      assert.equal(result.clientId, gateway.dsh.eventsClientId)
      assert.equal(result.clientId, gateway.hub.getEventsClientId())
      assert.deepEqual(result.outcome, { kind: 'result', value: 'allowed-once' })
      assert.equal(result.known, true)

      // The device that answered learns the intervention resolved "by this".
      const resolved = await phone.waitForFrame(
        (frame) => frame.t === 'event' && frame.kind === 'intervention.resolved' && frame.data?.eventId === waterfall.eventId,
        { label: 'intervention.resolved' },
      )
      assert.equal(resolved.data.by, 'this')
      assert.equal(resolved.data.outcome, 'allowed-once')

      const list = await request(base, '/v1/interventions')
      assert.equal(list.json.interventions.length, 0)
      assert.equal(gateway.hub.getIntervention(waterfall.eventId)?.status, 'resolved')
    } finally {
      await phone.close()
    }
  })

  it('rejects a duplicate answer and an unknown intervention', async () => {
    const waterfall = mock.waterfall('approval/request', { agentId: 's-alpha', request: { toolName: 'pwsh' } })
    await waitForPending(waterfall.eventId)
    const first = await request(base, `/v1/interventions/${waterfall.eventId}/answer`, {
      method: 'POST',
      body: { value: 'rejected' },
    })
    assert.equal(first.status, 200)

    const duplicate = await request(base, `/v1/interventions/${waterfall.eventId}/answer`, {
      method: 'POST',
      body: { value: 'allowed-once' },
    })
    assert.ok([404, 409].includes(duplicate.status), `expected 404/409, got ${duplicate.status}`)
    assert.equal(duplicate.json.ok, false)
    assert.ok(['not_found', 'already_resolved'].includes(duplicate.json.error.code))

    const unknown = await request(base, '/v1/interventions/does-not-exist/answer', {
      method: 'POST',
      body: { value: 'allowed-once' },
    })
    assert.equal(unknown.status, 404)
    assert.equal(unknown.json.ok, false)
  })

  it('rejects an invalid approval value and missing value', async () => {
    const waterfall = mock.waterfall('approval/request', { agentId: 's-alpha', request: { toolName: 'pwsh' } })
    await waitForPending(waterfall.eventId)

    const invalid = await request(base, `/v1/interventions/${waterfall.eventId}/answer`, {
      method: 'POST',
      body: { value: 'maybe' },
    })
    assert.equal(invalid.status, 400)
    assert.equal(invalid.json.ok, false)
    assert.equal(invalid.json.error.code, 'invalid_request')

    const missing = await request(base, `/v1/interventions/${waterfall.eventId}/answer`, {
      method: 'POST',
      body: {},
    })
    assert.equal(missing.status, 400)
    mock.cancel(waterfall.eventId)
  })

  it('dedupes a waterfall replayed after the DSH link reconnects', async () => {
    const phone = await connectPhone('dev-int-3')
    try {
      const waterfall = mock.waterfall('approval/request', { agentId: 's-alpha', request: { toolName: 'pwsh' } })
      await waitForPending(waterfall.eventId)
      await phone.waitForFrame(
        (frame) => frame.t === 'event' && frame.kind === 'approval.request' && frame.data?.eventId === waterfall.eventId,
        { label: 'first approval delivery' },
      )
      const before = phone.frames.filter(
        (frame) => frame.t === 'event' && frame.kind === 'approval.request' && frame.data?.eventId === waterfall.eventId,
      ).length
      assert.equal(before, 1)

      // DSH link drops; reconnecting replays every unresolved waterfall with the
      // same eventId. The hub must not duplicate it.
      mock.dropMux()
      await waitFor(() => gateway.dsh?.connected === false, { label: 'dsh down' })
      await waitFor(() => gateway.dsh?.connected === true, { label: 'dsh back', timeoutMs: 15_000 })
      await waitFor(() => mock.readyCount >= 2, { label: 'new $events client', timeoutMs: 15_000 })
      await sleep(300)

      const after = phone.frames.filter(
        (frame) => frame.t === 'event' && frame.kind === 'approval.request' && frame.data?.eventId === waterfall.eventId,
      ).length
      assert.equal(after, before, 'replayed waterfall must be deduped by eventId')
      assert.equal(gateway.hub.listInterventions('pending').filter((item) => item.eventId === waterfall.eventId).length, 1)
      mock.cancel(waterfall.eventId)
    } finally {
      await phone.close()
    }
  })

  it('delivers a pending approval to a device that reconnects later', async () => {
    // The phone is away while the approval arrives.
    const offline = await connectPhone('dev-int-offline')
    const staleSeq = gateway.hub.seq
    await offline.close()
    await waitFor(() => gateway.ws.count() === 0, { label: 'no live streams' })

    const waterfall = mock.waterfall('approval/request', {
      agentId: 's-alpha',
      request: { toolName: 'pwsh', reason: 'came back' },
    })
    await waitForPending(waterfall.eventId)

    const reconnected = await connectStream(resources, base)
    await reconnected.waitForType('ready')
    reconnected.send({ t: 'hello', deviceId: 'dev-int-offline', resume: { seq: staleSeq } })
    const replayed = await reconnected.waitForFrame(
      (frame) => frame.t === 'event' && frame.kind === 'approval.request' && frame.data?.eventId === waterfall.eventId,
      { label: 'approval replayed after reconnect' },
    )
    assert.equal(replayed.topic, 'session:s-alpha')

    const answer = await request(base, `/v1/interventions/${waterfall.eventId}/answer`, {
      method: 'POST',
      body: { value: 'allowed-once' },
    })
    assert.equal(answer.status, 200)
    await reconnected.close()
  })

  it('resolves an intervention when the desktop answered first (mock cancel frame)', async () => {
    const phone = await connectPhone('dev-int-4')
    try {
      const waterfall = mock.waterfall('approval/request', { agentId: 's-alpha', request: { toolName: 'pwsh' } })
      await waitForPending(waterfall.eventId)
      await phone.waitForFrame(
        (frame) => frame.t === 'event' && frame.kind === 'approval.request' && frame.data?.eventId === waterfall.eventId,
        { label: 'approval broadcast' },
      )

      mock.cancel(waterfall.eventId)
      const resolved = await phone.waitForFrame(
        (frame) => frame.t === 'event' && frame.kind === 'intervention.resolved' && frame.data?.eventId === waterfall.eventId,
        { label: 'intervention.resolved from other client' },
      )
      assert.equal(resolved.data.by, 'other')

      const list = await request(base, '/v1/interventions?status=pending')
      assert.equal(list.json.interventions.length, 0)
      assert.equal(gateway.hub.getIntervention(waterfall.eventId)?.status, 'resolved')

      // Answering a settled intervention is a client error, not a DSH call.
      const before = mock.resultCalls.length
      const late = await request(base, `/v1/interventions/${waterfall.eventId}/answer`, {
        method: 'POST',
        body: { value: 'allowed-once' },
      })
      assert.ok([404, 409].includes(late.status))
      assert.equal(mock.resultCalls.length, before)
    } finally {
      await phone.close()
    }
  })

  it('carries user questions and returns structured answers', async () => {
    const phone = await connectPhone('dev-int-5')
    try {
      const waterfall = mock.waterfall('user-questions/request', {
        agentId: 's-alpha',
        request: {
          questions: [
            { id: 'q1', question: 'Pick a colour', options: [{ label: 'Red' }, { label: 'Blue' }] },
            { id: 'q2', question: 'Why?', options: [] },
          ],
        },
      })
      await waitForPending(waterfall.eventId)

      const event = await phone.waitForFrame(
        (frame) => frame.t === 'event' && frame.kind === 'question.request' && frame.data?.eventId === waterfall.eventId,
        { label: 'question.request' },
      )
      assert.equal(event.data.questions.length, 2)
      assert.equal(event.data.questions[0].id, 'q1')
      assert.deepEqual(event.data.questions[0].options.map((option) => option.label), ['Red', 'Blue'])

      const list = await request(base, '/v1/interventions')
      const entry = list.json.interventions.find((item) => item.eventId === waterfall.eventId)
      assert.equal(entry.kind, 'question.request')

      const answer = await request(base, `/v1/interventions/${waterfall.eventId}/answer`, {
        method: 'POST',
        body: { value: { answers: [{ id: 'q1', selected: ['Blue'] }, { id: 'q2', selected: [], custom: 'because' }] } },
      })
      assert.equal(answer.status, 200, answer.text)
      assert.equal(answer.json.ok, true)

      await waitFor(() => mock.resultCalls.length === 1, { label: 'question result' })
      const result = mock.resultCalls[0]
      assert.equal(result.eventId, waterfall.eventId)
      assert.deepEqual(result.outcome, {
        kind: 'result',
        value: {
          answers: [
            { id: 'q1', selected: ['Blue'] },
            { id: 'q2', selected: [], custom: 'because' },
          ],
        },
      })
    } finally {
      await phone.close()
    }
  })

  it('uses the newest $events clientId after a reconnect', async () => {
    const phone = await connectPhone('dev-int-6')
    try {
      const first = mock.waterfall('approval/request', { agentId: 's-alpha', request: { toolName: 'pwsh' } })
      await waitForPending(first.eventId)
      const firstClientId = gateway.dsh.eventsClientId
      mock.cancel(first.eventId)

      mock.dropMux()
      await waitFor(() => gateway.dsh?.connected === false, { label: 'dsh down' })
      await waitFor(() => gateway.dsh?.connected === true, { label: 'dsh back', timeoutMs: 15_000 })
      await waitFor(() => gateway.dsh.eventsClientId !== firstClientId, {
        label: 'new $events clientId',
        timeoutMs: 15_000,
      })
      const latestClientId = gateway.dsh.eventsClientId

      const second = mock.waterfall('approval/request', { agentId: 's-alpha', request: { toolName: 'pwsh' } })
      await waitForPending(second.eventId)
      const answer = await request(base, `/v1/interventions/${second.eventId}/answer`, {
        method: 'POST',
        body: { value: 'rejected' },
      })
      assert.equal(answer.status, 200, answer.text)
      await waitFor(() => mock.resultCalls.length === 1, { label: 'answer after reconnect' })
      assert.equal(mock.resultCalls[0].clientId, latestClientId)
      assert.notEqual(mock.resultCalls[0].clientId, firstClientId)
    } finally {
      await phone.close()
    }
  })
})

describe('/v1/interventions WS edge', { skip: reason }, () => {
  it('requires an open socket: plain HTTP routes never block on WS state', async () => {
    // Sanity: the interventions REST surface is available without any WS client.
    const resources = createResources()
    const mock = new MockDsh()
    await mock.start()
    resources.add(() => mock.stop())
    try {
      const { lanBase } = await startGateway({ mock, resources })
      await waitForHealth(lanBase)
      const list = await request(lanBase, '/v1/interventions')
      assert.equal(list.status, 200)
      assert.deepEqual(list.json, { ok: true, interventions: [] })
    } finally {
      await resources.disposeAll()
    }
  })
})
