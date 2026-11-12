/**
 * 重连补发不产生「同一条消息两个身份」。
 *
 * 真机问题：App 连上/重连后，会话第一条消息在时间线里出现两次。
 * 网关侧必须保证：同一条持久消息在**每次重连重放**时都带同一个稳定身份
 * （事件 id = `<sessionId>:<seq>`、data.seq = DSH 事件 seq），否则客户端
 * 只能按帧 seq / 文本去猜，重复就挡不住（并且 REST 历史里的 message.seq
 * 必须与 WS 事件 data.seq 是同一个序列）。
 *
 * @module dsh-gateway/test/reconnect-duplicate.test
 */

import assert from 'node:assert/strict'
import { after, before, describe, it } from 'node:test'

import {
  StreamClient,
  createResources,
  libStatus,
  request,
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

describe('reconnect must not duplicate messages', { skip: reason }, () => {
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
    const started = await startGateway({ mock, resources, config: { ringSize: 64, maxDevices: 4 } })
    gateway = started.gateway
    base = started.lanBase
    await waitForHealth(base)
    await waitForDsh(gateway)
    await waitFor(() => (gateway.hub.getSessions() ?? []).length >= 2, { label: 'session baseline' })
  })

  after(async () => {
    await resources.disposeAll()
  })

  /** 打开一条流，hello 并订阅 s-alpha，等到会话历史被补发。 */
  async function subscribeAlpha(deviceId, resumeSeq) {
    const client = await StreamClient.connect(streamUrl(base))
    resources.add(() => client.close())
    await client.waitForType('ready')
    client.send({ t: 'hello', deviceId, resume: { seq: resumeSeq } })
    client.send({ t: 'sub', id: `sub-${deviceId}`, topic: 'session:s-alpha' })
    await waitFor(() => {
      const conns = [...(gateway.ws?.connections ?? [])]
      return conns.some((conn) => [...(conn.subs?.values?.() ?? [])].includes('session:s-alpha'))
    }, { label: 'subscription registered' })
    return client
  }

  it('replays the same durable message with the same stable id after a reconnect', async () => {
    const first = await subscribeAlpha('dev-reconnect-1', 0)
    const firstUser = await first.waitForFrame(
      (frame) => frame.t === 'event' && frame.kind === 'message.user' && frame.data?.sessionId === 's-alpha',
      { label: 'first message.user delivery' },
    )
    await first.close()
    await waitFor(() => (gateway.ws?.deviceCount?.() ?? 0) === 0, { label: 'first stream closed' })

    // 重连：客户端带上「已经看到的最后一条帧 seq」，网关仍会通过新的 follow
    // snapshot 重放同一批持久消息。
    const second = await subscribeAlpha('dev-reconnect-1', firstUser.seq)
    const replay = await second.waitForFrame(
      (frame) => frame.t === 'event' && frame.kind === 'message.user' && frame.data?.sessionId === 's-alpha',
      { label: 'replayed message.user delivery' },
    )

    assert.equal(replay.id, firstUser.id, 'same message must keep the same stable event id across reconnects')
    assert.equal(replay.data?.seq, firstUser.data?.seq, 'same message must keep the same DSH seq across reconnects')
    assert.equal(replay.data?.seq, 2, 'DSH session seq for the seeded first user message')
    // 帧 seq 是 hub 的全局序号，重放时必须往前走；客户端不能拿它当消息身份。
    assert.notEqual(replay.seq, firstUser.seq)
    await second.close()
  })

  it('exposes the same seq for a message in REST history and in the WS event', async () => {
    const client = await subscribeAlpha('dev-reconnect-2', 0)
    const event = await client.waitForFrame(
      (frame) => frame.t === 'event' && frame.kind === 'message.user' && frame.data?.sessionId === 's-alpha',
      { label: 'message.user delivery' },
    )
    const history = await request(base, '/v1/sessions/s-alpha/messages?limit=50')
    assert.equal(history.status, 200)
    const message = (history.json?.messages ?? []).find((item) => item.role === 'user' && item.text === 'hello alpha')
    assert.ok(message, 'seeded user message must be in REST history')
    assert.equal(
      message.seq,
      event.data?.seq,
      'REST message.seq and WS event data.seq must be the same sequence (client dedupe key)',
    )
    await client.close()
  })
})
