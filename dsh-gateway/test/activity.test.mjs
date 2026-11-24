/**
 * GET /v1/sessions/{id}/activity —— 团队协作面板的网关端点
 * （契约 docs/team-activity-panel.md §2）。
 *
 * 覆盖三层：
 *   - ActivityTracker 单元：registered/settled 幂等、树内过滤、排序、上限、过期；
 *   - watchSessionEvents：把 ctx.on('session/event') 接到追踪器并可卸载；
 *   - HTTP 集成：未知会话 404、空活动、子代理树 running/depth/model、team 投影
 *     裁剪、job 注册到结算。
 *
 * @module dsh-gateway/test/activity.test
 */

import assert from 'node:assert/strict'
import { after, before, beforeEach, describe, it } from 'node:test'

import {
  createResources,
  libStatus,
  loadLib,
  makeLogger,
  request,
  startGateway,
  startMockDsh,
  waitForDsh,
  waitForHealth,
} from './helper.mjs'

const REQUIRED = ['index.js', 'hub.js', 'http.js']
const status = await libStatus()
const missing = REQUIRED.filter((name) => status.missing.includes(name))
const reason = missing.length === 0 ? undefined : 'lib modules not implemented yet: ' + missing.join(', ')

/** 最小 ctx：只实现 on/emit（Cordis 事件总线）。 */
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

/**
 * 真实时钟基准：追踪器按真实 Date.now() 判断「5 分钟内」，所以测试时间戳
 * 必须贴近当前时间，不能再用 1000/2000 这种假纪元。
 */
const BASE = Date.now()

/** 一条 job/registered 事件。 */
function registered(jobId, owner, extra = {}) {
  return {
    type: 'job/registered',
    data: { jobId, owner, kind: 'bash', label: 'npm test', startedAt: BASE, ...extra },
  }
}

/** 一条 job/settled 事件。 */
function settled(jobId, owner, extra = {}) {
  return {
    type: 'job/settled',
    data: {
      jobId,
      owner,
      kind: 'bash',
      label: 'npm test',
      startedAt: BASE,
      finishedAt: BASE + 1_000,
      status: 'completed',
      cause: 'producer',
      producerReported: true,
      ...extra,
    },
  }
}

describe('ActivityTracker', { skip: reason }, () => {
  async function makeTracker(options = {}) {
    const { ActivityTracker } = await loadLib('activity.js')
    return new ActivityTracker({ logger: makeLogger(), ...options })
  }

  it('records job/registered as a running job of its owner session', async () => {
    const tracker = await makeTracker()
    tracker.handleSessionEvent({ id: 's1' }, registered('bash-1', 's1'))
    assert.deepEqual(tracker.jobsInSessionIds(['s1']), [{
      jobId: 'bash-1',
      sessionId: 's1',
      kind: 'bash',
      label: 'npm test',
      status: 'running',
      detail: null,
      startedAt: BASE,
      finishedAt: null,
    }])
  })

  it('settles a job with its status, detail and finish time', async () => {
    const tracker = await makeTracker()
    tracker.handleSessionEvent({ id: 's1' }, registered('bash-1', 's1'))
    tracker.handleSessionEvent({ id: 's1' }, settled('bash-1', 's1', { status: 'killed', detail: 'killed by user', finishedAt: BASE + 5_000 }))
    const jobs = tracker.jobsInSessionIds(['s1'])
    assert.equal(jobs.length, 1)
    assert.equal(jobs[0].status, 'killed')
    assert.equal(jobs[0].detail, 'killed by user')
    assert.equal(jobs[0].startedAt, BASE)
    assert.equal(jobs[0].finishedAt, BASE + 5_000)
  })

  it('is idempotent: duplicate and late events do not change a settled job', async () => {
    const tracker = await makeTracker()
    tracker.handleSessionEvent({ id: 's1' }, registered('bash-1', 's1', { startedAt: BASE, label: 'first' }))
    tracker.handleSessionEvent({ id: 's1' }, registered('bash-1', 's1', { startedAt: BASE + 9_000, label: 'second' }))
    let jobs = tracker.jobsInSessionIds(['s1'])
    assert.equal(jobs.length, 1)
    assert.equal(jobs[0].label, 'first', 'a repeated registered keeps the first record')
    assert.equal(jobs[0].startedAt, BASE)

    tracker.handleSessionEvent({ id: 's1' }, settled('bash-1', 's1', { status: 'completed', finishedAt: BASE + 2_000 }))
    // 重放会把结算前的 registered 再发一遍：不能把已结算的任务复活成 running。
    tracker.handleSessionEvent({ id: 's1' }, registered('bash-1', 's1', { startedAt: BASE }))
    tracker.handleSessionEvent({ id: 's1' }, settled('bash-1', 's1', { status: 'failed', finishedAt: BASE + 3_000 }))
    jobs = tracker.jobsInSessionIds(['s1'])
    assert.equal(jobs.length, 1)
    assert.equal(jobs[0].status, 'completed')
    assert.equal(jobs[0].finishedAt, BASE + 2_000)
  })

  it('falls back to the event session id when the job carries no owner', async () => {
    const tracker = await makeTracker()
    tracker.handleSessionEvent({ id: 's1' }, { type: 'job/registered', data: { jobId: 'bash-1', kind: 'bash', label: 'x', startedAt: 1_000 } })
    assert.equal(tracker.jobsInSessionIds(['s1']).length, 1)
  })

  it('only returns jobs whose owner is inside the requested tree', async () => {
    const tracker = await makeTracker()
    tracker.handleSessionEvent({ id: 'root' }, registered('bash-1', 'root', { startedAt: 1_000 }))
    tracker.handleSessionEvent({ id: 'child' }, registered('bash-2', 'child', { startedAt: 2_000 }))
    tracker.handleSessionEvent({ id: 'other' }, registered('bash-3', 'other', { startedAt: 3_000 }))
    assert.deepEqual(tracker.jobsInSessionIds(['root']).map((job) => job.jobId), ['bash-1'])
    assert.deepEqual(tracker.jobsInSessionIds(['root', 'child']).map((job) => job.jobId), ['bash-2', 'bash-1'])
    // 未知/空树返回空数组，而不是抛异常。
    assert.deepEqual(tracker.jobsInSessionIds([]), [])
    assert.deepEqual(tracker.jobsInSessionIds(null), [])
  })

  it('sorts running jobs first, then the newest startedAt', async () => {
    const tracker = await makeTracker()
    tracker.handleSessionEvent({ id: 's1' }, registered('bash-old', 's1', { startedAt: BASE - 3_000 }))
    tracker.handleSessionEvent({ id: 's1' }, registered('bash-new', 's1', { startedAt: BASE - 1_000 }))
    tracker.handleSessionEvent({ id: 's1' }, settled('bash-new', 's1', { startedAt: BASE - 1_000, finishedAt: BASE + 1_000 }))
    tracker.handleSessionEvent({ id: 's1' }, registered('bash-live', 's1', { startedAt: BASE - 2_000 }))
    tracker.handleSessionEvent({ id: 's1' }, settled('bash-old', 's1', { startedAt: BASE - 3_000, finishedAt: BASE + 500 }))
    assert.deepEqual(
      tracker.jobsInSessionIds(['s1']).map((job) => job.jobId),
      ['bash-live', 'bash-new', 'bash-old'],
    )
  })

  it('forgets settled jobs after the 5 minute retention window', async () => {
    let now = 1_000_000
    const tracker = await makeTracker({ now: () => now })
    tracker.handleSessionEvent({ id: 's1' }, registered('bash-1', 's1', { startedAt: now }))
    tracker.handleSessionEvent({ id: 's1' }, settled('bash-1', 's1', { startedAt: now, finishedAt: now }))
    assert.equal(tracker.jobsInSessionIds(['s1']).length, 1)

    now += 4 * 60_000
    assert.equal(tracker.jobsInSessionIds(['s1']).length, 1, 'still inside the window')

    now += 2 * 60_000
    assert.equal(tracker.jobsInSessionIds(['s1']).length, 0, 'pruned after 5 minutes')
    assert.equal(tracker.size, 0)
  })

  it('keeps running jobs while pruning settled ones', async () => {
    let now = 1_000_000
    const tracker = await makeTracker({ now: () => now })
    tracker.handleSessionEvent({ id: 's1' }, registered('bash-live', 's1', { startedAt: now }))
    tracker.handleSessionEvent({ id: 's1' }, registered('bash-done', 's1', { startedAt: now }))
    tracker.handleSessionEvent({ id: 's1' }, settled('bash-done', 's1', { startedAt: now, finishedAt: now }))
    now += 10 * 60_000
    assert.deepEqual(tracker.jobsInSessionIds(['s1']).map((job) => job.jobId), ['bash-live'])
  })

  it('caps memory at maxJobs, dropping the oldest settled records first', async () => {
    const tracker = await makeTracker({ maxJobs: 3 })
    for (const id of ['a', 'b', 'c', 'd', 'e']) {
      tracker.handleSessionEvent({ id: 's1' }, registered('bash-' + id, 's1', { startedAt: id.codePointAt(0) }))
    }
    assert.equal(tracker.size, 3)
    assert.deepEqual(tracker.jobsInSessionIds(['s1']).map((job) => job.jobId), ['bash-e', 'bash-d', 'bash-c'])
  })

  it('never throws on malformed events, even without a logger', async () => {
    const { ActivityTracker } = await loadLib('activity.js')
    const tracker = new ActivityTracker()
    const cases = [
      [null, null],
      [{}, undefined],
      [{}, {}],
      [{}, { type: 'job/registered' }],
      [{}, { type: 'job/registered', data: null }],
      [{}, { type: 'job/settled', data: { jobId: 42, status: 'nope' } }],
      [{ id: 's1' }, { type: 'job/registered', data: { jobId: 'bash-x' } }],
      [null, { type: 'job/settled', data: { jobId: 'bash-y', owner: 's1', finishedAt: Date.now() } }],
      ['not-an-object', { type: 'job/settled', data: { jobId: 'bash-z', owner: 's1' } }],
    ]
    for (const [session, event] of cases) {
      assert.doesNotThrow(() => tracker.handleSessionEvent(session, event))
    }
    assert.doesNotThrow(() => tracker.jobsInSessionIds('s1'))
    assert.doesNotThrow(() => tracker.jobsInSessionIds([null, 7, 's1']))
    // 字段缺失时用兜底值，但记录本身要能读出来。
    const jobs = tracker.jobsInSessionIds(['s1'])
    assert.ok(jobs.some((job) => job.jobId === 'bash-x'))
    assert.ok(jobs.some((job) => job.jobId === 'bash-y'))
    assert.ok(jobs.find((job) => job.jobId === 'bash-y').status === 'completed')
  })
})

describe('watchSessionEvents', { skip: reason }, () => {
  it('forwards session events to the tracker and unsubscribes on dispose', async () => {
    const { ActivityTracker, watchSessionEvents } = await loadLib('activity.js')
    const tracker = new ActivityTracker({ logger: makeLogger() })
    const ctx = fakeCtx()
    const stop = watchSessionEvents({ ctx, tracker, logger: makeLogger() })
    assert.equal(ctx.count('session/event'), 1)

    ctx.emit('session/event', { id: 's1' }, registered('bash-1', 's1'))
    assert.equal(tracker.jobsInSessionIds(['s1']).length, 1)

    stop()
    assert.equal(ctx.count('session/event'), 0)
    ctx.emit('session/event', { id: 's1' }, registered('bash-2', 's1'))
    assert.equal(tracker.jobsInSessionIds(['s1']).length, 1)
    // 幂等：再调一次不抛。
    stop()
  })

  it('degrades to a no-op when the context or tracker cannot be used', async () => {
    const { ActivityTracker, watchSessionEvents } = await loadLib('activity.js')
    const tracker = new ActivityTracker()
    const stopNoCtx = watchSessionEvents({ ctx: null, tracker, logger: makeLogger() })
    assert.equal(typeof stopNoCtx, 'function')
    stopNoCtx()

    const ctx = fakeCtx()
    const stopNoTracker = watchSessionEvents({ ctx, tracker: null, logger: makeLogger() })
    assert.equal(ctx.count('session/event'), 0)
    stopNoTracker()
  })
})

describe('/v1 session activity', { skip: reason }, () => {
  /** @type {ReturnType<typeof createResources>} */
  let resources
  /** @type {any} */
  let gateway
  /** @type {string} */
  let base
  /** @type {any} */
  let ctx

  before(async () => {
    resources = createResources()
    const mock = await startMockDsh({ resources })
    const started = await startGateway({ mock, resources, config: { ringSize: 64, mdns: false } })
    gateway = started.gateway
    base = started.lanBase
    ctx = started.ctx
    await waitForHealth(base)
    await waitForDsh(gateway)
  })

  after(async () => {
    await resources.disposeAll()
  })

  beforeEach(() => {
    // 每个用例从「hub 里没有会话、追踪器里没有 job」开始。
    for (const summary of gateway.hub.getSessions()) gateway.hub.removeSession(summary.sessionId)
    gateway.activity.clear()
  })

  /** 往 hub 里种一条会话摘要（等价于 api-session/added 或 session/list 回来）。 */
  function seed(summary) {
    gateway.hub.applySessionSummary(summary)
  }

  /** 请求活动端点，返回 helper 的 {status, json}。 */
  function activity(sessionId) {
    return request(base, '/v1/sessions/' + encodeURIComponent(sessionId) + '/activity')
  }

  it('answers 404 not_found for an unknown session', async () => {
    const response = await activity('session-missing')
    assert.equal(response.status, 404)
    assert.deepEqual(response.json, { ok: false, error: { code: 'not_found', message: 'unknown session' } })
  })

  it('answers an empty activity for a session with no team, subagents or jobs', async () => {
    seed({ sessionId: 'root-1', updatedAt: 1_700_000_000_000, running: false, title: '根会话' })
    const response = await activity('root-1')
    assert.equal(response.status, 200)
    assert.deepEqual(response.json, {
      ok: true,
      sessionId: 'root-1',
      updatedAt: 1_700_000_000_000,
      team: null,
      subagents: [],
      jobs: [],
    })
  })

  it('lists the subagent tree with running first, depth, mode, title and model', async () => {
    seed({
      sessionId: 'root-1',
      updatedAt: 1_700_000_000_000,
      running: true,
      title: '根会话',
      projections: {
        values: {
          subagentCatalog: [
            { id: 'child-running', createdAt: 1_700_000_000_000, mode: 'continuable', label: 'gateway-dev' },
            { id: 'child-idle', createdAt: 1_700_000_000_000, mode: 'one-shot', label: 'audit' },
          ],
        },
      },
    })
    seed({ sessionId: 'child-idle', parentSessionId: 'root-1', updatedAt: 1_700_000_500_000, running: false, title: '空闲子代理' })
    seed({
      sessionId: 'child-running',
      parentSessionId: 'root-1',
      updatedAt: 1_700_000_100_000,
      running: true,
      projections: {
        values: {
          title: '运行中子代理',
          modelSelection: {
            lastUsed: { provider: 'deepseek', model: 'deepseek-v4.1-flash', reasoningEffort: 'high' },
            next: null,
          },
          subagentCatalog: [
            { id: 'grandchild', createdAt: 1_700_000_200_000, mode: 'one-shot', label: 'nested' },
          ],
        },
      },
    })
    seed({ sessionId: 'grandchild', parentSessionId: 'child-running', updatedAt: 1_700_000_200_000, running: false, title: '孙代理' })
    seed({ sessionId: 'unrelated', updatedAt: 1_700_000_900_000, running: true, title: '别人家会话' })

    const response = await activity('root-1')
    assert.equal(response.status, 200)
    const subagents = response.json.subagents
    // running 优先；其余按 updatedAt 倒序（空闲子代理 500 > 孙代理 200）。
    assert.deepEqual(subagents.map((entry) => entry.id), ['child-running', 'child-idle', 'grandchild'])
    assert.deepEqual(subagents.map((entry) => entry.depth), [1, 1, 2])

    const running = subagents[0]
    assert.equal(running.parentSessionId, 'root-1')
    assert.equal(running.title, '运行中子代理')
    assert.equal(running.running, true)
    assert.equal(running.model, 'deepseek-v4.1-flash')
    assert.equal(running.reasoningEffort, 'high')
    assert.equal(running.updatedAt, 1_700_000_100_000)
    // mode 来自直接父会话（root-1）的 subagentCatalog。
    assert.equal(running.mode, 'continuable')

    const idle = subagents[1]
    assert.equal(idle.title, '空闲子代理')
    assert.equal(idle.model, null)
    assert.equal(idle.reasoningEffort, null)
    assert.equal(idle.running, false)
    assert.equal(idle.mode, 'one-shot')

    assert.equal(subagents[2].parentSessionId, 'child-running')
    // 深度 2 的子代理用它的直接父会话（child-running）catalog 解析。
    assert.equal(subagents[2].mode, 'one-shot')
    assert.equal(subagents.some((entry) => entry.id === 'unrelated'), false)
    assert.equal(subagents.some((entry) => entry.id === 'root-1'), false)
  })

  it('falls back to mode unknown without a usable parent catalog', async () => {
    seed({
      sessionId: 'root-2',
      updatedAt: 1_700_000_000_000,
      running: true,
      projections: {
        values: {
          // external 没有本地子会话；即使线性匹配到也只按 unknown 暴露。
          subagentCatalog: [{ id: 'child-external', createdAt: 1_700_000_000_000, mode: 'external', label: 'remote' }],
        },
      },
    })
    seed({ sessionId: 'child-external', parentSessionId: 'root-2', updatedAt: 1_700_000_300_000, running: false, title: '外部执行' })
    seed({ sessionId: 'child-unmatched', parentSessionId: 'root-2', updatedAt: 1_700_000_200_000, running: false, title: '不在 catalog 里' })
    // 父会话完全没有 subagentCatalog 投影（旧 DSH / 非子代理父会话）。
    seed({ sessionId: 'root-3', updatedAt: 1_700_000_000_000, running: false })
    seed({ sessionId: 'child-nocat', parentSessionId: 'root-3', updatedAt: 1_700_000_100_000, running: false, title: '无投影' })

    const response = await activity('root-2')
    assert.equal(response.status, 200)
    const modes = new Map(response.json.subagents.map((entry) => [entry.id, entry.mode]))
    assert.equal(modes.get('child-external'), 'unknown')
    assert.equal(modes.get('child-unmatched'), 'unknown')

    const noCatalog = await activity('root-3')
    assert.equal(noCatalog.status, 200)
    assert.equal(noCatalog.json.subagents.length, 1)
    assert.equal(noCatalog.json.subagents[0].mode, 'unknown')
  })

  it('caps the subagent list at 50', async () => {
    seed({ sessionId: 'root-1', updatedAt: 1_700_000_000_000, running: false })
    for (let index = 0; index < 55; index += 1) {
      seed({
        sessionId: 'child-' + index,
        parentSessionId: 'root-1',
        updatedAt: 1_700_000_000_000 + index,
        running: false,
        title: '子代理 ' + index,
      })
    }
    const response = await activity('root-1')
    assert.equal(response.status, 200)
    assert.equal(response.json.subagents.length, 50)
    // 截断前先排序：保留 updatedAt 最大的 50 条，最老的 5 条被丢掉。
    assert.equal(response.json.subagents[0].id, 'child-54')
    assert.equal(response.json.subagents.some((entry) => entry.id === 'child-0'), false)
  })

  it('tracks jobs from registered to settled, idempotently and across the tree', async () => {
    seed({ sessionId: 'root-1', updatedAt: 1_700_000_000_000, running: true })
    seed({ sessionId: 'child-1', parentSessionId: 'root-1', updatedAt: 1_700_000_100_000, running: true, title: '子代理' })
    const now = Date.now()

    ctx.emit('session/event', { id: 'child-1' }, registered('bash-1', 'child-1', { startedAt: now }))
    // 重复的 registered（重放）不能产生第二条记录。
    ctx.emit('session/event', { id: 'child-1' }, registered('bash-1', 'child-1', { startedAt: now }))
    ctx.emit('session/event', { id: 'root-1' }, registered('bash-2', 'root-1', { startedAt: now - 10 }))

    let response = await activity('root-1')
    assert.equal(response.status, 200)
    assert.equal(response.json.jobs.length, 2)
    const running = response.json.jobs
    assert.deepEqual(running.map((job) => job.id), ['bash-1', 'bash-2'])
    assert.deepEqual(running[0], {
      id: 'bash-1',
      sessionId: 'child-1',
      kind: 'bash',
      label: 'npm test',
      status: 'running',
      detail: null,
      startedAt: now,
      finishedAt: null,
      subagent: true,
    })
    assert.equal(running[1].subagent, false, 'a root-session job is not a subagent job')

    ctx.emit('session/event', { id: 'child-1' }, settled('bash-1', 'child-1', { startedAt: now, finishedAt: now + 5, detail: 'exit code: 0' }))
    // 结算后迟到的 registered 不能复活。
    ctx.emit('session/event', { id: 'child-1' }, registered('bash-1', 'child-1', { startedAt: now }))
    // 重复的 settled 也不能改写终态。
    ctx.emit('session/event', { id: 'child-1' }, settled('bash-1', 'child-1', { startedAt: now, finishedAt: now + 9, status: 'failed' }))

    response = await activity('root-1')
    assert.equal(response.json.jobs.length, 2)
    assert.deepEqual(response.json.jobs.map((job) => job.id), ['bash-2', 'bash-1'])
    const finished = response.json.jobs[1]
    assert.equal(finished.status, 'completed')
    assert.equal(finished.detail, 'exit code: 0')
    assert.equal(finished.finishedAt, now + 5)
  })

  it('trims the root session agentTeam projection', async () => {
    seed({
      sessionId: 'root-1',
      updatedAt: 1_700_000_000_000,
      running: true,
      title: '根会话',
      projections: {
        values: {
          agentTeam: {
            enabled: true,
            tasks: [
              {
                id: 'task-1',
                revision: 3,
                subject: '网关实现 activity 端点',
                description: '实现 dsh-gateway 的活动端点',
                status: 'in_progress',
                ownerId: 'session-child',
                ownerName: 'lead/gateway-dev',
                blockedBy: [],
                writeScopes: ['dsh-gateway/lib'],
                ready: true,
                writeScopeWarnings: [],
              },
              {
                id: 'task-2',
                revision: 2,
                subject: '已删除的任务',
                description: '',
                status: 'deleted',
                blockedBy: [],
                writeScopes: [],
                ready: false,
                writeScopeWarnings: [],
              },
              {
                id: 'task-3',
                revision: 1,
                subject: '待处理的任务',
                description: '',
                status: 'pending',
                blockedBy: [],
                writeScopes: [],
                ready: true,
                writeScopeWarnings: [],
              },
            ],
          },
        },
      },
    })

    const response = await activity('root-1')
    assert.equal(response.status, 200)
    assert.deepEqual(response.json.team, {
      enabled: true,
      failure: null,
      tasks: [
        {
          id: 'task-1',
          subject: '网关实现 activity 端点',
          status: 'in_progress',
          ownerId: 'session-child',
          ownerName: 'lead/gateway-dev',
          revision: 3,
        },
        {
          id: 'task-3',
          subject: '待处理的任务',
          status: 'pending',
          ownerId: null,
          ownerName: null,
          revision: 1,
        },
      ],
    })
  })

  it('reports a disabled team and a projection failure as-is', async () => {
    seed({
      sessionId: 'root-1',
      updatedAt: 1_700_000_000_000,
      running: false,
      projections: { values: { agentTeam: { enabled: false, tasks: [], failure: 'team record rejected' } } },
    })
    const response = await activity('root-1')
    assert.deepEqual(response.json.team, { enabled: false, failure: 'team record rejected', tasks: [] })
  })
})
