/**
 * GET /v1/sessions/{childId}/messages?parent=…&mode=… —— 读取子智能体会话历史。
 *
 * 契约 docs/team-activity-panel.md §5.1：DSH 的 SessionAddress 联合类型里，子代理
 * 子会话只能用 {kind:'subagent',parentSessionId,childSessionId,mode} 访问；拿普通
 * {kind:'session',sessionId} 去读会被宿主拒绝（网关回 502）。这些用例把网关的
 * 参数映射、旧行为回退与 400 校验钉死在 mock DSH 上。
 *
 * @module dsh-gateway/test/subagent-messages
 */

import assert from 'node:assert/strict'
import { after, before, beforeEach, describe, it } from 'node:test'

import {
  createResources,
  libStatus,
  request,
  startGateway,
  startMockDsh,
  waitForDsh,
  waitForHealth,
} from './helper.mjs'

const REQUIRED = ['index.js', 'http.js']
const status = await libStatus()
const missing = REQUIRED.filter((name) => status.missing.includes(name))
const reason = missing.length === 0 ? undefined : 'lib modules not implemented yet: ' + missing.join(', ')

/** 父（根）会话。 */
const ROOT = 'session-root-1'
/** 子代理会话（mock 里带 parentSessionId）。 */
const CHILD = 'session-child-1'

describe('/v1 subagent messages', { skip: reason }, () => {
  /** @type {ReturnType<typeof createResources>} */
  let resources
  /** @type {import('./mock-dsh.mjs').MockDsh} */
  let mock
  /** @type {string} */
  let base

  /** 子代理历史页的 URL。 */
  function childMessages(query) {
    return '/v1/sessions/' + CHILD + '/messages' + (query === undefined ? '' : '?' + query)
  }

  before(async () => {
    resources = createResources()
    mock = await startMockDsh({ resources })
    // 真实宿主里子代理会话也在 session 列表里，只是带 parentSessionId。
    mock.sessionAdded({ sessionId: ROOT, updatedAt: 1_700_000_000_000, running: true, blank: false, agentAvailable: true, formatStatus: 'current' })
    mock.sessionAdded({ sessionId: CHILD, parentSessionId: ROOT, updatedAt: 1_700_000_100_000, running: false, blank: false, agentAvailable: true, formatStatus: 'current' })
    mock.sessionEvent(CHILD, 'turn/start', { turn: 1 })
    mock.sessionEvent(CHILD, 'user/message', {
      id: 'msg-child-user',
      role: 'user',
      content: [{ type: 'text', text: '整理依赖' }],
      source: { kind: 'user' },
    })
    mock.sessionEvent(CHILD, 'assistant/message', {
      turn: 1,
      step: 1,
      message: { role: 'assistant', content: [{ type: 'text', text: '依赖整理完了' }] },
      stream: [],
    })
    mock.sessionEvent(CHILD, 'turn/end', { turn: 1, reason: 'stop' })

    const started = await startGateway({ mock, resources, config: { ringSize: 64, mdns: false } })
    base = started.lanBase
    await waitForHealth(base)
    await waitForDsh(started.gateway)
  })

  after(async () => {
    await resources.disposeAll()
  })

  beforeEach(() => {
    mock.reset()
  })

  it('reads a subagent history through the subagent session address', async () => {
    const response = await request(base, childMessages('parent=' + ROOT + '&mode=continuable'))
    assert.equal(response.status, 200)
    assert.equal(response.json.ok, true)
    assert.deepEqual(response.json.messages.map((message) => message.text), ['整理依赖', '依赖整理完了'])
    assert.equal(response.json.messages[0].role, 'user')
    assert.equal(response.json.messages[1].role, 'assistant')
    assert.equal(response.json.hasMore, false)

    const call = mock.lastCall('session/events')
    assert.deepEqual(call.args.request.address, {
      kind: 'subagent',
      parentSessionId: ROOT,
      childSessionId: CHILD,
      mode: 'continuable',
    })
  })

  it('forwards beforeSeq / limit and any accepted mode with the subagent address', async () => {
    const response = await request(base, childMessages('parent=' + ROOT + '&mode=unknown&beforeSeq=3&limit=10'))
    assert.equal(response.status, 200)
    assert.deepEqual(response.json.messages.map((message) => message.text), ['整理依赖'])
    assert.equal(response.json.cursor, 2)
    assert.equal(response.json.hasMore, false)

    const call = mock.lastCall('session/events')
    assert.equal(call.args.request.maxMessages, 10)
    assert.equal(call.args.request.beforeSeq, 3)
    assert.equal(call.args.request.address.mode, 'unknown')
  })

  it('falls back to the plain session address when parent or mode is missing', async () => {
    // 只有 mode：不切 subagent 地址 → DSH 拒绝子代理的普通会话地址 → 502。
    const onlyMode = await request(base, childMessages('mode=continuable'))
    assert.equal(onlyMode.status, 502)
    assert.equal(onlyMode.json.error.code, 'dsh_error')
    assert.deepEqual(mock.lastCall('session/events').args.request.address, { kind: 'session', sessionId: CHILD })

    // 只有 parent：同上，旧行为。
    const onlyParent = await request(base, childMessages('parent=' + ROOT))
    assert.equal(onlyParent.status, 502)
    assert.equal(onlyParent.json.error.code, 'dsh_error')
    assert.deepEqual(mock.lastCall('session/events').args.request.address, { kind: 'session', sessionId: CHILD })
  })

  it('rejects an invalid mode with 400 before calling DSH', async () => {
    for (const mode of ['sideways', 'external', 'one_shot']) {
      const response = await request(base, childMessages('parent=' + ROOT + '&mode=' + mode))
      assert.equal(response.status, 400, 'mode ' + mode + ' must be rejected')
      assert.equal(response.json.error.code, 'invalid_request')
      assert.match(response.json.error.message, /mode/)
    }
    // 没有 parent 时非法 mode 同样是 400（参数校验先于地址选择）。
    const noParent = await request(base, childMessages('mode=sideways'))
    assert.equal(noParent.status, 400)
    assert.equal(noParent.json.error.code, 'invalid_request')
    assert.equal(mock.callsFor('session/events').length, 0)
  })

  it('keeps the plain session address for ordinary sessions', async () => {
    const response = await request(base, '/v1/sessions/s-alpha/messages?limit=10')
    assert.equal(response.status, 200)
    assert.ok(response.json.messages.length > 0)
    assert.deepEqual(mock.lastCall('session/events').args.request.address, { kind: 'session', sessionId: 's-alpha' })
  })
})
