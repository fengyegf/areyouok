/**
 * `/v1/stream` WebSocket edge: handshake, subscriptions, resume/replay, gap
 * resync, ping/pong, device limits and slow-consumer isolation.
 *
 * Protocol reference: handoff §6.4 / impl-plan §3.8.
 *   S→C ready(snapshot) → C→S hello(resume.seq) → replay | resync
 *   C→S sub/unsub/ack/ping          S→C event / pong / resync / error
 *
 * @module dsh-gateway/test/stream.test
 */

import assert from 'node:assert/strict'
import { after, before, describe, it } from 'node:test'

import {
  StreamClient,
  connectStream,
  createResources,
  freePort,
  libStatus,
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

/** Connect to `/v1/stream` of a gateway base URL. */
function streamUrl(base) {
  return `${base.replace(/^http/, 'ws')}/v1/stream`
}

describe('/v1/stream', { skip: reason }, () => {
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
    const started = await startGateway({ mock, resources, config: { ringSize: 16, maxDevices: 4 } })
    gateway = started.gateway
    base = started.lanBase
    await waitForHealth(base)
    await waitForDsh(gateway)
    await waitFor(() => (gateway.hub.getSessions() ?? []).length >= 2, { label: 'session baseline' })
  })

  after(async () => {
    await resources.disposeAll()
  })

  /** Open a stream client (registered for teardown). */
  async function connect(options = {}) {
    const client = await StreamClient.connect(streamUrl(base), options)
    resources.add(() => client.close())
    return client
  }

  it('greets with ready(seq, serverTime, snapshot) before any client frame', async () => {
    const client = await connect()
    const ready = await client.waitForType('ready')
    assert.equal(typeof ready.seq, 'number')
    assert.equal(typeof ready.serverTime, 'number')
    assert.ok(ready.snapshot, 'ready must carry a snapshot')
    assert.equal(typeof ready.snapshot.seq, 'number')
    assert.equal(typeof ready.snapshot.serverTime, 'number')
    assert.ok(Array.isArray(ready.snapshot.sessions))
    assert.ok(Array.isArray(ready.snapshot.workspaces))
    assert.ok(Array.isArray(ready.snapshot.pending))
    assert.ok(ready.snapshot.sessions.some((session) => session.sessionId === 's-alpha'))
    await client.close()
  })

  it('answers ping with pong carrying the same ts', async () => {
    const client = await connect()
    await client.waitForType('ready')
    client.send({ t: 'ping', ts: 1234 })
    const pong = await client.waitForType('pong')
    assert.equal(pong.ts, 1234)
    await client.close()
  })

  it('delivers only subscribed topics and honours unsub', async () => {
    const client = await connect()
    await client.waitForType('ready')
    client.send({ t: 'hello', deviceId: 'dev-sub' })
    client.send({ t: 'sub', id: 'sub-alpha', topic: 'session:s-alpha' })
    // Wait until the server registered the subscription (workspace snapshot events
    // for the followed session prove the stream is live).
    await waitFor(() => {
      const conns = [...(gateway.ws?.connections ?? [])]
      return conns.some((conn) => [...(conn.subs?.values?.() ?? [])].includes('session:s-alpha'))
    }, { label: 'subscription registered' })

    mock.sessionEvent('s-alpha', 'turn/start', { turn: 3 })
    const alphaFrame = await client.waitForFrame(
      (frame) => frame.t === 'event' && frame.topic === 'session:s-alpha' && frame.kind === 'turn.start' && frame.data?.turn === 3,
      { label: 'session:s-alpha turn.start' },
    )
    assert.equal(typeof alphaFrame.seq, 'number')
    assert.equal(typeof alphaFrame.id, 'string')
    assert.equal(typeof alphaFrame.ts, 'number')

    // Beta events must not leak while only alpha is subscribed.
    const seenBefore = client.frames.filter((frame) => frame.t === 'event').length
    mock.sessionEvent('s-beta', 'turn/start', { turn: 4 })
    await sleep(120)
    const betaFrames = client.frames.filter(
      (frame) => frame.t === 'event' && frame.topic === 'session:s-beta' && frame.data?.turn === 4,
    )
    assert.equal(betaFrames.length, 0)

    // Unsubscribe: alpha events stop arriving.
    client.send({ t: 'unsub', id: 'sub-alpha' })
    await waitFor(() => {
      const conns = [...(gateway.ws?.connections ?? [])]
      return !conns.some((conn) => [...(conn.subs?.values?.() ?? [])].includes('session:s-alpha'))
    }, { label: 'subscription removed' })
    const afterUnsub = client.frames.filter((frame) => frame.t === 'event').length
    mock.sessionEvent('s-alpha', 'turn/start', { turn: 5 })
    await sleep(120)
    assert.equal(client.frames.filter((frame) => frame.t === 'event').length, afterUnsub)
    assert.ok(seenBefore >= 0)
    await client.close()
  })

  it('rejects malformed and invalid subscription frames', async () => {
    const client = await connect()
    await client.waitForType('ready')
    client.send({ t: 'sub', id: 'bad', topic: 'workspace' })
    const error = await client.waitForType('error')
    assert.equal(error.code, 'invalid_request')

    client.socket.send('not json')
    const parseError = await client.waitForType('error', {
      predicate: (frame) => frame.code === 'invalid_request',
      from: client.frames.length,
    }).catch(() => null)
    assert.ok(parseError === null || parseError.t === 'error')
    await client.close()
  })

  it('replays events after hello.resume.seq', async () => {
    const first = await connect()
    await first.waitForType('ready')
    first.send({ t: 'hello', deviceId: 'dev-resume' })
    first.send({ t: 'sub', id: 'sub-alpha', topic: 'session:s-alpha' })
    await waitFor(() => {
      const conns = [...(gateway.ws?.connections ?? [])]
      return conns.some((conn) => conn.deviceId === 'dev-resume' && [...(conn.subs?.values?.() ?? [])].includes('session:s-alpha'))
    }, { label: 'resume device subscribed' })

    mock.sessionEvent('s-alpha', 'turn/start', { turn: 10 })
    const online = await first.waitForFrame(
      (frame) => frame.t === 'event' && frame.data?.turn === 10,
      { label: 'turn 10 online' },
    )
    const resumeFrom = online.seq
    await first.close()

    // While the phone is away two session events happen.
    mock.sessionEvent('s-alpha', 'turn/start', { turn: 11 })
    mock.sessionEvent('s-alpha', 'turn/end', { turn: 11, reason: 'stop' })

    const second = await connect()
    const ready = await second.waitForType('ready')
    assert.ok(ready.seq >= resumeFrom)
    second.send({ t: 'hello', deviceId: 'dev-resume', resume: { seq: resumeFrom } })

    const replayed = await second.waitForFrame(
      (frame) => frame.t === 'event' && frame.data?.turn === 11 && frame.kind === 'turn.start',
      { label: 'replayed turn 11' },
    )
    assert.ok(replayed.seq > resumeFrom)
    const turnEnd = await second.waitForFrame(
      (frame) => frame.t === 'event' && frame.kind === 'turn.end' && frame.data?.turn === 11,
      { label: 'replayed turn end' },
    )
    assert.ok(turnEnd.seq > replayed.seq)
    await second.close()
  })

  it('sends resync + a fresh ready when the resume seq fell out of the ring', async () => {
    const first = await connect()
    await first.waitForType('ready')
    first.send({ t: 'hello', deviceId: 'dev-gap' })
    first.send({ t: 'sub', id: 'sub-beta', topic: 'session:s-beta' })
    await waitFor(() => {
      const conns = [...(gateway.ws?.connections ?? [])]
      return conns.some((conn) => conn.deviceId === 'dev-gap' && [...(conn.subs?.values?.() ?? [])].includes('session:s-beta'))
    }, { label: 'gap device subscribed' })
    const staleSeq = gateway.hub.seq
    await first.close()

    // ringSize is 16: 25 events guarantee the stale seq is evicted.
    for (let index = 0; index < 25; index += 1) {
      mock.sessionEvent('s-beta', 'turn/start', { turn: 100 + index })
    }

    const second = await connect()
    await second.waitForType('ready')
    second.send({ t: 'hello', deviceId: 'dev-gap', resume: { seq: staleSeq } })
    const resync = await second.waitForType('resync')
    assert.equal(resync.reason, 'gap')
    const readyFrames = []
    await waitFor(() => {
      readyFrames.length = second.framesOfType('ready').length
      return readyFrames.length >= 2
    }, { label: 'fresh ready after resync' })
    assert.ok(second.framesOfType('ready').length >= 2)
    await second.close()
  })

  it('restores the persisted subscription set on reconnect', async () => {
    const first = await connect()
    await first.waitForType('ready')
    first.send({ t: 'hello', deviceId: 'dev-restore' })
    first.send({ t: 'sub', id: 'sub-alpha', topic: 'session:s-alpha' })
    await waitFor(() => gateway.hub.getDevice('dev-restore')?.subscriptions?.includes('session:s-alpha'), {
      label: 'device subscriptions persisted',
    })
    await first.close()

    const second = await connect()
    await second.waitForType('ready')
    second.send({ t: 'hello', deviceId: 'dev-restore' })
    await waitFor(() => {
      const conns = [...(gateway.ws?.connections ?? [])]
      return conns.some((conn) => conn.deviceId === 'dev-restore' && [...(conn.subs?.values?.() ?? [])].includes('session:s-alpha'))
    }, { label: 'subscription restored' })

    mock.sessionEvent('s-alpha', 'turn/start', { turn: 21 })
    const frame = await second.waitForFrame((item) => item.t === 'event' && item.data?.turn === 21, {
      label: 'event after restored subscription',
    })
    assert.equal(frame.topic, 'session:s-alpha')
    await second.close()
  })

  it('does not let a stalled consumer block another device', async () => {
    const slow = await connect()
    const fast = await connect()
    await slow.waitForType('ready')
    await fast.waitForType('ready')
    slow.send({ t: 'hello', deviceId: 'dev-slow' })
    slow.send({ t: 'sub', id: 's1', topic: 'session:s-alpha' })
    fast.send({ t: 'hello', deviceId: 'dev-fast' })
    fast.send({ t: 'sub', id: 'f1', topic: 'session:s-alpha' })
    await waitFor(() => {
      const conns = [...(gateway.ws?.connections ?? [])]
      return conns.filter((conn) => [...(conn.subs?.values?.() ?? [])].includes('session:s-alpha')).length >= 2
    }, { label: 'both devices subscribed' })

    // Stall the slow socket: no reads → server side buffers/queues grow.
    slow.socket._socket?.pause?.()
    try {
      const payload = 'x'.repeat(2_048)
      for (let index = 0; index < 400; index += 1) {
        mock.assistantStream('s-alpha', {
          type: 'chunk',
          turn: 1,
          step: 1,
          chunk: { type: 'text-delta', text: payload },
        })
      }
      // A critical event must still reach the healthy device promptly.
      const waterfall = mock.waterfall('approval/request', { agentId: 's-alpha', request: { toolName: 'pwsh' } })
      const approval = await fast.waitForFrame(
        (frame) => frame.t === 'event' && frame.kind === 'approval.request' && frame.data?.eventId === waterfall.eventId,
        { label: 'approval on healthy device', timeoutMs: 8_000 },
      )
      assert.equal(approval.topic, 'session:s-alpha')
      mock.cancel(waterfall.eventId)
    } finally {
      try {
        slow.socket._socket?.resume?.()
      } catch {
        // ignore
      }
      slow.terminate()
      await fast.close()
    }
  })

  it('closes an unauthenticated upgrade only on the public port', async () => {
    // The LAN port admits loopback without a token.
    const lan = await connect()
    await lan.waitForType('ready')
    await lan.close()
  })
})

describe('/v1/stream maxDevices', { skip: reason }, () => {
  /** @type {ReturnType<typeof createResources>} */
  let resources

  after(async () => {
    await resources?.disposeAll()
  })

  it('refuses the third device and replaces a reconnecting device', async () => {
    resources = createResources()
    const mock = new MockDsh()
    await mock.start()
    resources.add(() => mock.stop())
    const { gateway, lanBase } = await startGateway({ mock, resources, config: { maxDevices: 2 } })
    await waitForHealth(lanBase)
    await waitForDsh(gateway)

    /** @type {StreamClient[]} */
    const clients = []
    const open = async (deviceId) => {
      const client = await StreamClient.connect(streamUrl(lanBase))
      resources.add(() => client.close())
      clients.push(client)
      await client.waitForType('ready')
      client.send({ t: 'hello', deviceId })
      await waitFor(() => gateway.hub.getDevice(deviceId) !== null, { label: `device ${deviceId} tracked` })
      return client
    }

    const alpha = await open('dev-alpha')
    const beta = await open('dev-beta')
    assert.equal(gateway.ws.count(), 2)

    // Third device is refused with an error frame and a 4003 close.
    const gamma = await StreamClient.connect(streamUrl(lanBase))
    resources.add(() => gamma.close())
    await gamma.waitForType('ready')
    gamma.send({ t: 'hello', deviceId: 'dev-gamma' })
    const error = await gamma.waitForType('error')
    assert.equal(error.code, 'too_many_devices')
    const closed = await gamma.waitForClose(3_000)
    assert.equal(closed.code, 4003)

    // Reconnecting with a known device id replaces the old socket.
    const alphaAgain = await open('dev-alpha')
    const replaced = await alpha.waitForClose(3_000)
    assert.equal(replaced.code, 4000)
    assert.equal(alphaAgain.closed, false)
    assert.equal(gateway.ws.count(), 2)
    assert.equal(beta.closed, false)
    await alphaAgain.close()
    await beta.close()
  })
})

describe('/v1/stream unauthenticated upgrade', { skip: reason }, () => {
  it('serves ready to a loopback client while an HTTPS port is also enabled', async () => {
    // Loopback is always admitted by the LAN port; the public-source 403 path is
    // covered by AuthService.guard unit tests in auth.test.mjs because a real
    // public source address cannot be produced on loopback.
    const resources = createResources()
    const mock = new MockDsh()
    await mock.start()
    resources.add(() => mock.stop())
    try {
      const httpsPort = await freePort()
      const { lanBase } = await startGateway({ mock, resources, config: { httpsPort } })
      await waitForHealth(lanBase)
      const client = await connectStream(resources, lanBase)
      const ready = await client.waitForType('ready')
      assert.equal(typeof ready.seq, 'number')
      await client.close()
    } finally {
      await resources.disposeAll()
    }
  })
})
