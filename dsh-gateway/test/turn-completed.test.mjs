/**
 * `turn.completed` — 每轮对话结束的广播（见 `lib/turns.js`）。
 *
 * 覆盖两层：
 *   - 单元：`watchTurnEnds` 只认 `turn/end`、整形载荷、识别子会话、可卸载；
 *   - 集成：`createGateway` 真的把它挂到 `ctx.on('session/event')` 上，经
 *     `/v1/stream` 的 `global` 订阅送达设备（带 seq 与 ts）。
 *
 * @module dsh-gateway/test/turn-completed.test
 */

import assert from 'node:assert/strict'
import { after, before, describe, it } from 'node:test'

import {
  createResources,
  libStatus,
  loadLib,
  makeLogger,
  startGateway,
  startMockDsh,
  waitFor,
  waitForDsh,
  waitForHealth,
} from './helper.mjs'
import { StreamClient } from './helper.mjs'

const REQUIRED = ['index.js', 'hub.js', 'ws.js', 'turns.js']
const status = await libStatus()
const missing = REQUIRED.filter((name) => status.missing.includes(name))
const reason = missing.length === 0 ? undefined : `lib modules not implemented yet: ${missing.join(', ')}`

/** 最小 hub 替身：只记录 `publish`，`sessionTitleOf` 可预置。 */
function fakeHub(titles = {}) {
  const hub = {
    published: [],
    publish(topic, kind, data, opts = {}) {
      const event = { topic, kind, data, ...opts }
      hub.published.push(event)
      return event
    },
    sessionTitleOf(sessionId) {
      return titles[sessionId] ?? ''
    },
  }
  return hub
}

/** 最小 ctx：只实现 `on` / `emit`（Cordis 事件总线）。 */
function fakeCtx() {
  const listeners = new Map()
  return {
    listeners,
    on(name, handler) {
      const set = listeners.get(name) ?? new Set()
      set.add(handler)
      listeners.set(name, set)
      return () => set.delete(handler)
    },
    emit(name, ...args) {
      for (const handler of [...(listeners.get(name) ?? [])]) handler(...args)
    },
    count(name) {
      return listeners.get(name)?.size ?? 0
    },
  }
}

describe('watchTurnEnds', { skip: reason }, () => {
  /** @returns {Promise<Function>} */
  async function watch(options = {}) {
    const { watchTurnEnds } = await loadLib('turns.js')
    const hub = options.hub ?? fakeHub()
    const ctx = options.ctx ?? fakeCtx()
    const stop = watchTurnEnds({ ctx, hub, logger: options.logger ?? makeLogger() })
    return { stop, hub, ctx }
  }

  it('turns one turn/end into a global turn.completed', async () => {
    const { hub, ctx } = await watch({ hub: fakeHub({ s1: '重构网关' }) })
    ctx.emit('session/event', { id: 's1' }, {
      type: 'turn/end',
      time: 1760000000000,
      data: { turn: 7, reason: 'completed' },
    })

    assert.equal(hub.published.length, 1)
    const event = hub.published[0]
    assert.equal(event.topic, 'global')
    assert.equal(event.kind, 'turn.completed')
    assert.deepEqual(event.data, {
      sessionId: 's1',
      turn: 7,
      reason: 'completed',
      time: 1760000000000,
      title: '重构网关',
      subagent: false,
    })
  })

  it('keeps unknown title / missing fields as null instead of guessing', async () => {
    const { hub, ctx } = await watch()
    ctx.emit('session/event', { id: 's2' }, { type: 'turn/end', data: {} })

    assert.deepEqual(hub.published[0].data, {
      sessionId: 's2',
      turn: null,
      reason: null,
      time: null,
      title: null,
      subagent: false,
    })
  })

  it('ignores every other session event', async () => {
    const { hub, ctx } = await watch()
    for (const type of ['turn/start', 'assistant/message', 'tool/call', 'session/title', 'step/end']) {
      ctx.emit('session/event', { id: 's1' }, { type, data: { turn: 1 } })
    }
    assert.equal(hub.published.length, 0)
  })

  it('skips events without a session id', async () => {
    const { hub, ctx } = await watch()
    ctx.emit('session/event', {}, { type: 'turn/end', data: {} })
    ctx.emit('session/event', null, { type: 'turn/end', data: { sessionId: '' } })
    assert.equal(hub.published.length, 0)
  })

  it('flags subagent sessions from the session object', async () => {
    const { hub, ctx } = await watch()
    ctx.emit('session/event', { id: 'sub-1', parentSessionId: 's1' }, { type: 'turn/end', data: { turn: 1 } })
    ctx.emit('session/event', { id: 'sub-2', origin: 'subagent' }, { type: 'turn/end', data: { turn: 1 } })
    ctx.emit('session/event', { id: 'sub-3', meta: { parentSessionId: 's1' } }, { type: 'turn/end', data: {} })

    assert.deepEqual(hub.published.map((event) => event.data.subagent), [true, true, true])
  })

  it('remembers subagent ids from subagent/start and subagent/end', async () => {
    const { hub, ctx } = await watch()
    ctx.emit('subagent/start', { runId: 'r1', id: 'sub-9', provider: 'deepseek' })
    ctx.emit('session/event', { id: 'sub-9' }, { type: 'turn/end', data: { turn: 2 } })
    assert.equal(hub.published[0].data.subagent, true)

    // 与主会话同名 id 不会误判。
    ctx.emit('session/event', { id: 's1' }, { type: 'turn/end', data: { turn: 2 } })
    assert.equal(hub.published[1].data.subagent, false)
  })

  it('stops publishing after dispose', async () => {
    const { hub, ctx, stop } = await watch()
    stop()
    assert.equal(ctx.count('session/event'), 0)
    ctx.emit('session/event', { id: 's1' }, { type: 'turn/end', data: { turn: 1 } })
    assert.equal(hub.published.length, 0)
    // 幂等：再调一次不抛。
    stop()
  })

  it('degrades to a no-op when the context cannot subscribe', async () => {
    const { watchTurnEnds } = await loadLib('turns.js')
    const stop = watchTurnEnds({ ctx: null, hub: fakeHub(), logger: makeLogger() })
    assert.equal(typeof stop, 'function')
    assert.doesNotThrow(() => stop())
  })

  it('survives a hub that throws while publishing', async () => {
    const { watchTurnEnds } = await loadLib('turns.js')
    const logger = makeLogger()
    const ctx = fakeCtx()
    const hub = {
      publish() {
        throw new Error('boom')
      },
    }
    watchTurnEnds({ ctx, hub, logger })
    assert.doesNotThrow(() => {
      ctx.emit('session/event', { id: 's1' }, { type: 'turn/end', data: { turn: 1 } })
    })
    assert.ok(logger.entries.some((entry) => entry.message.includes('turn.completed skipped')))
  })
})

describe('turn.completed over /v1/stream', { skip: reason }, () => {
  /** @type {ReturnType<typeof createResources>} */
  let resources
  /** @type {any} */
  let started

  before(async () => {
    resources = createResources()
    const mock = await startMockDsh({ resources })
    started = await startGateway({ mock, resources, config: { ringSize: 64, mdns: false } })
    await waitForHealth(started.lanBase)
    await waitForDsh(started.gateway)
  })

  after(async () => {
    await resources.disposeAll()
  })

  /** 等服务器真的把订阅登记上（`sub` 帧是异步处理的）。 */
  function subscribed(client, topic) {
    return waitFor(() => {
      const conns = [...(started.gateway.ws?.connections ?? [])]
      return conns.some((conn) => [...(conn.subs?.values?.() ?? [])].includes(topic))
    }, { label: `subscription ${topic}` })
  }

  async function openClient(deviceId) {
    const client = await StreamClient.connect(`${started.lanBase.replace(/^http/, 'ws')}/v1/stream`)
    resources.add(() => client.close())
    const ready = await client.waitForType('ready')
    client.send({ t: 'hello', deviceId })
    return { client, ready }
  }

  it('advertises the capability so an old APK / old gateway mismatch is visible', async () => {
    const client = await StreamClient.connect(`${started.lanBase.replace(/^http/, 'ws')}/v1/stream`)
    resources.add(() => client.close())
    const ready = await client.waitForType('ready')
    assert.ok(
      Array.isArray(ready.snapshot?.capabilities),
      'ready.snapshot.capabilities must be an array',
    )
    assert.ok(ready.snapshot.capabilities.includes('turn.completed'))
  })

  it('reaches a global subscriber as a live event', async () => {
    const { client } = await openClient('dev-turn-completed')
    client.send({ t: 'sub', id: 'global', topic: 'global' })
    await subscribed(client, 'global')

    const readySeq = started.gateway.hub.seq
    started.ctx.emit('session/event', { id: 'session-live' }, {
      type: 'turn/end',
      time: 1760000000000,
      data: { turn: 3, reason: 'completed' },
    })

    const frame = await client.waitForFrame(
      (item) => item?.t === 'event' && item.kind === 'turn.completed',
      { label: 'turn.completed' },
    )
    assert.ok(frame.seq > readySeq, 'live completion must be newer than the ready boundary')
    assert.equal(typeof frame.ts, 'number')
    assert.equal(frame.topic, 'global')
    assert.equal(frame.data.sessionId, 'session-live')
    assert.equal(frame.data.turn, 3)
    assert.equal(frame.data.subagent, false)
  })

  it('replays with the original seq and publish time so the client can tell it is old', async () => {
    // 断线补齐：事件按原 seq / 原发布时刻重发，客户端据此判定「这是补发，不是刚结束」。
    const beforeSeq = started.gateway.hub.seq
    const publishedAt = Date.now()
    started.ctx.emit('session/event', { id: 'session-replayed' }, {
      type: 'turn/end',
      time: publishedAt - 3_600_000,
      data: { turn: 9, reason: 'completed' },
    })
    assert.equal(started.gateway.hub.seq, beforeSeq + 1)

    const client = await StreamClient.connect(`${started.lanBase.replace(/^http/, 'ws')}/v1/stream`)
    resources.add(() => client.close())
    await client.waitForType('ready')
    client.send({ t: 'sub', id: 'global', topic: 'global' })
    await subscribed(client, 'global')
    // resume 到这条事件之前 ⇒ 服务器补发它。
    client.send({ t: 'hello', deviceId: 'dev-turn-replay', resume: { seq: beforeSeq } })

    const frame = await client.waitForFrame(
      (item) => item?.t === 'event' && item.kind === 'turn.completed' && item.data?.sessionId === 'session-replayed',
      { label: 'replayed turn.completed' },
    )
    assert.equal(frame.seq, beforeSeq + 1, 'replayed frames keep their original seq')
    assert.ok(
      Math.abs(frame.ts - publishedAt) < 5_000,
      'replayed frames keep the original publish time instead of the replay moment',
    )
    assert.equal(frame.data.turn, 9)
    assert.equal(frame.data.subagent, false)
  })
})
