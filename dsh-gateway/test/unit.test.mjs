/**
 * Pure unit tests for `lib/hub.js` (EventHub) and `lib/normalize.js`.
 *
 * These modules are I/O free or filesystem-only, so they are tested in
 * isolation: no HTTP listener, no mock DSH, no timers left behind.
 *
 * @module dsh-gateway/test/unit.test
 */

import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { mkdir, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { after, before, describe, it } from 'node:test'

import { createResources, libStatus, makeTempDir } from './helper.mjs'

const status = await libStatus()
const missing = ['hub.js', 'normalize.js'].filter((name) => status.missing.includes(name))
const reason = missing.length === 0 ? undefined : `lib modules not implemented yet: ${missing.join(', ')}`

// --------------------------------------------------------------- normalize

describe('normalize.js', { skip: reason }, async () => {
  const {
    normalizeWaterfall,
    normalizeSessionRecord,
    normalizeControlFrame,
    normalizeWorkspaceFrame,
    approvalOutcome,
    questionAnswers,
    messageText,
  } = await import('../lib/normalize.js')

  describe('normalizeWaterfall', () => {
    it('maps approval/request', () => {
      const result = normalizeWaterfall({
        type: 'waterfall',
        event: 'approval/request',
        eventId: 'ev-1',
        agentId: 's-1',
        request: { toolName: 'pwsh', callId: 'call-9', reason: 'run tests' },
      })
      assert.equal(result.kind, 'approval.request')
      assert.equal(result.eventId, 'ev-1')
      assert.equal(result.sessionId, 's-1')
      assert.equal(result.data.toolName, 'pwsh')
      assert.equal(result.data.callId, 'call-9')
      assert.equal(result.data.reason, 'run tests')
    })

    it('maps user-questions/request and normalizes questions', () => {
      const result = normalizeWaterfall({
        type: 'waterfall',
        event: 'user-questions/request',
        eventId: 'ev-2',
        agentId: 's-2',
        request: {
          questions: [
            { id: 'q1', question: 'Pick one', options: [{ label: 'A' }, { label: 'B', description: 'bee' }] },
            { prompt: 'Free text' },
          ],
        },
      })
      assert.equal(result.kind, 'question.request')
      assert.equal(result.data.questions.length, 2)
      assert.equal(result.data.questions[0].id, 'q1')
      assert.equal(result.data.questions[0].options[1].description, 'bee')
      assert.equal(result.data.questions[1].id, 'q2')
      assert.equal(result.data.questions[1].question, 'Free text')
    })

    it('ignores unknown waterfalls and malformed frames', () => {
      assert.equal(normalizeWaterfall({ type: 'waterfall', event: 'agent/event', eventId: 'x' }), null)
      assert.equal(normalizeWaterfall({ type: 'waterfall', event: 'approval/request' }), null)
      assert.equal(normalizeWaterfall({ type: 'ready', clientId: 'c' }), null)
      assert.equal(normalizeWaterfall(null), null)
    })
  })

  describe('normalizeSessionRecord', () => {
    it('expands a snapshot into events', () => {
      const events = normalizeSessionRecord('s-1', {
        type: 'snapshot',
        header: { version: 1, id: 's-1', createdAt: 1, isSeeded: false },
        cursor: 2,
        records: [
          { type: 'event', event: { type: 'turn/start', seq: 1, time: 100, data: { turn: 1 } } },
          {
            type: 'event',
            event: {
              type: 'user/message',
              seq: 2,
              time: 101,
              data: { id: 'm-1', role: 'user', content: [{ type: 'text', text: 'hello' }], source: { kind: 'user' } },
            },
          },
        ],
        hasMore: false,
        projections: { asOfSeq: 0, values: {} },
      })
      assert.equal(events.length, 2)
      assert.equal(events[0].kind, 'turn.start')
      assert.equal(events[0].data.turn, 1)
      assert.equal(events[1].kind, 'message.user')
      assert.equal(events[1].data.text, 'hello')
      // 来源标记透传：客户端据此区分真人消息与机器注入（runtime-context/tool-jobs…）。
      assert.equal(events[1].data.source.kind, 'user')
      assert.equal(events[1].id, 's-1:2')
      assert.equal(events[1].topic, 'session:s-1')
    })

    it('passes the user message source through so clients can hide machine notices', () => {
      const notice = normalizeSessionRecord('s-1', {
        type: 'event',
        event: {
          type: 'user/message',
          seq: 9,
          time: 300,
          data: {
            id: 'm-9',
            role: 'user',
            content: [{ type: 'text', text: 'background job bash-1 (bash: sleep 1) finished [status: completed, exit code: 0].' }],
            source: { kind: 'tool-jobs', form: 'notice', summary: 'bash sleep 1' },
          },
        },
      })
      assert.equal(notice[0].kind, 'message.user')
      assert.equal(notice[0].data.source.kind, 'tool-jobs')
      assert.equal(notice[0].data.source.summary, 'bash sleep 1')
    })

    it('maps assistant messages, tool calls and unknown types', () => {
      const assistant = normalizeSessionRecord('s-1', {
        type: 'event',
        event: {
          type: 'assistant/message',
          seq: 5,
          time: 200,
          data: { turn: 1, step: 2, message: { role: 'assistant', content: [{ type: 'text', text: 'done' }] }, stream: [] },
        },
      })
      assert.equal(assistant[0].kind, 'message.assistant')
      assert.equal(assistant[0].data.text, 'done')
      assert.equal(assistant[0].data.turn, 1)

      const tool = normalizeSessionRecord('s-1', {
        type: 'event',
        event: { type: 'tool/call', seq: 6, time: 201, data: { turn: 1, step: 2, callId: 'c1', name: 'pwsh', arguments: '{}' } },
      })
      assert.equal(tool[0].kind, 'tool.call')
      assert.equal(tool[0].data.name, 'pwsh')

      const unknown = normalizeSessionRecord('s-1', {
        type: 'event',
        event: { type: 'plan/mode', seq: 7, time: 202, data: { mode: 'plan' } },
      })
      assert.equal(unknown[0].kind, 'session.event')
      assert.equal(unknown[0].data.type, 'plan/mode')
      assert.equal(unknown[0].data.seq, 7)
    })

    it('maps assistant-stream text and reasoning deltas', () => {
      const textDelta = normalizeSessionRecord('s-1', {
        type: 'assistant-stream',
        frame: { type: 'chunk', turn: 2, step: 1, chunk: { type: 'text-delta', text: 'hel' }, time: 300 },
      })
      assert.equal(textDelta[0].kind, 'message.delta')
      assert.equal(textDelta[0].data.text, 'hel')
      assert.equal(textDelta[0].data.kind, 'text')

      const reasoning = normalizeSessionRecord('s-1', {
        type: 'assistant-stream',
        frame: { type: 'chunk', turn: 2, step: 1, chunk: { type: 'reasoning-delta', text: 'thinking' } },
      })
      assert.equal(reasoning[0].data.kind, 'reasoning')

      const other = normalizeSessionRecord('s-1', {
        type: 'assistant-stream',
        frame: { type: 'start', turn: 2, step: 1 },
      })
      assert.equal(other[0].kind, 'session.event')
    })

    it('degrades malformed frames instead of throwing', () => {
      assert.equal(normalizeSessionRecord('s-1', { type: 'weird' }).length, 1)
      assert.equal(normalizeSessionRecord('s-1', null)[0].kind, 'session.event')
      assert.equal(normalizeSessionRecord('', {}).length, 1)
    })
  })

  describe('normalizeControlFrame', () => {
    it('expands a projection baseline into session summaries', () => {
      const result = normalizeControlFrame({
        type: 'baseline',
        value: {
          projections: {
            's-1': { asOfSeq: 12, values: { title: 'One', workingDirectory: 'E:/a' } },
            's-2': { asOfSeq: 3, values: {} },
          },
        },
      })
      assert.equal(result.kind, 'sessions.snapshot')
      assert.equal(result.data.length, 2)
      const first = result.data.find((item) => item.sessionId === 's-1')
      assert.equal(first.title, 'One')
      assert.equal(first.projections.values.workingDirectory, 'E:/a')
      assert.equal(first.projections.asOfSeq, 12)
    })

    it('maps projection updates, removals and status', () => {
      const update = normalizeControlFrame({
        type: 'projection',
        sessionId: 's-1',
        seq: 44,
        changes: [{ key: 'title', val: 'Renamed' }, { key: 'workingDirectory', val: 'E:/b' }],
      })
      assert.equal(update.kind, 'session.summary')
      assert.equal(update.data.sessionId, 's-1')
      assert.equal(update.data.seq, 44)
      assert.equal(update.data.title, 'Renamed')
      assert.deepEqual(update.data.projections.values, { title: 'Renamed', workingDirectory: 'E:/b' })

      assert.deepEqual(normalizeControlFrame({ type: 'removed', sessionId: 's-3' }), {
        kind: 'session.removed',
        data: { sessionId: 's-3' },
      })
      assert.deepEqual(normalizeControlFrame({ type: 'status', sessionId: 's-4', running: true }), {
        kind: 'session.status',
        data: { sessionId: 's-4', running: true },
      })
      assert.equal(normalizeControlFrame({ type: 'unknown' }), null)
      assert.equal(normalizeControlFrame(null), null)
    })

    it('accepts a plain summary list baseline', () => {
      const result = normalizeControlFrame({ type: 'baseline', value: [{ sessionId: 's-9', running: false }] })
      assert.equal(result.kind, 'sessions.snapshot')
      assert.equal(result.data[0].sessionId, 's-9')
    })
  })

  describe('normalizeWorkspaceFrame', () => {
    it('maps baseline, upsert, remove, order and pinned frames', () => {
      const baseline = normalizeWorkspaceFrame({
        type: 'baseline',
        value: { items: [{ workspaceId: 'w-1', title: 'one' }], archivedSessionIds: ['s-9'], pinnedSessionIds: [] },
      })
      assert.equal(baseline.kind, 'workspaces')
      assert.equal(baseline.data.workspaces[0].workspaceId, 'w-1')
      assert.deepEqual(baseline.data.archivedSessionIds, ['s-9'])

      const upsert = normalizeWorkspaceFrame({ type: 'upsert', workspace: { workspaceId: 'w-2', title: 'two' } })
      assert.equal(upsert.kind, 'workspace.updated')
      assert.equal(upsert.data.workspaceId, 'w-2')

      const remove = normalizeWorkspaceFrame({ type: 'remove', workspaceId: 'w-2' })
      assert.deepEqual(remove.data, { workspaceId: 'w-2', removed: true })

      const order = normalizeWorkspaceFrame({ type: 'order', workspaceIds: ['w-1'] })
      assert.deepEqual(order.data, { order: ['w-1'] })

      const pinned = normalizeWorkspaceFrame({ type: 'pinned', pinnedSessionIds: ['s-1'] })
      assert.deepEqual(pinned.data, { pinnedSessionIds: ['s-1'] })

      assert.equal(normalizeWorkspaceFrame({ type: 'nope' }), null)
    })
  })

  describe('approvalOutcome', () => {
    it('accepts canonical values and aliases', () => {
      assert.equal(approvalOutcome('allowed-once'), 'allowed-once')
      assert.equal(approvalOutcome('allow'), 'allowed-once')
      assert.equal(approvalOutcome('ALLOWED_ONCE'), 'allowed-once')
      assert.equal(approvalOutcome('rejected'), 'rejected')
      assert.equal(approvalOutcome('deny'), 'rejected')
      assert.equal(approvalOutcome('cancelled'), 'cancelled')
      assert.equal(approvalOutcome('canceled'), 'cancelled')
      assert.equal(approvalOutcome('unavailable'), 'unavailable')
      assert.equal(approvalOutcome({ kind: 'result', value: 'allowed-once' }), 'allowed-once')
    })

    it('rejects unknown values', () => {
      assert.equal(approvalOutcome('maybe'), null)
      assert.equal(approvalOutcome(''), null)
      assert.equal(approvalOutcome(undefined), null)
      assert.equal(approvalOutcome(null), null)
      assert.equal(approvalOutcome(42), null)
      assert.equal(approvalOutcome({}), null)
    })
  })

  describe('questionAnswers', () => {
    const questions = [{ id: 'q1' }, { id: 'q2' }]

    it('orders answers by question and drops unknown ids', () => {
      const value = questionAnswers('ev-1', questions, [
        { id: 'q2', selected: ['B'] },
        { id: 'nope', selected: ['X'] },
        { id: 'q1', selected: 'A', custom: 'free' },
      ])
      assert.deepEqual(value, {
        answers: [
          { id: 'q1', selected: ['A'], custom: 'free' },
          { id: 'q2', selected: ['B'] },
        ],
      })
    })

    it('fills unanswered questions with an empty selection', () => {
      const value = questionAnswers('ev-2', questions, { q1: ['A'] })
      assert.deepEqual(value.answers, [
        { id: 'q1', selected: ['A'] },
        { id: 'q2', selected: [] },
      ])
    })
  })

  it('extracts readable text from content blocks', () => {
    assert.equal(messageText({ content: [{ type: 'text', text: 'a' }, { type: 'image' }, { text: 'b' }] }), 'a\nb')
    assert.equal(messageText({ content: [] }), '')
    assert.equal(messageText(null), '')
  })
})

// --------------------------------------------------------------------- hub

describe('hub.js', { skip: reason }, async () => {
  const { EventHub } = await import('../lib/hub.js')
  const { readJsonFile } = await import('../lib/util.js')

  /** @type {ReturnType<typeof createResources>} */
  let resources
  /** @type {string} */
  let dir

  before(async () => {
    resources = createResources()
    dir = await makeTempDir(resources)
  })

  after(async () => {
    await resources.disposeAll()
  })

  /** Fresh hub in a private subdirectory. */
  async function makeHub(options = {}) {
    const sub = join(dir, `hub-${Math.random().toString(16).slice(2)}`)
    const hub = new EventHub({
      config: { ringSize: 8, approvalTtlMs: 60_000, ...(options.config ?? {}) },
      logger: { info() {}, warn() {}, error() {}, debug() {} },
      dir: sub,
    })
    await hub.load()
    resources.add(() => hub.close())
    return { hub, sub }
  }

  it('allocates monotonic seq and publishes to sinks', async () => {
    const { hub } = await makeHub()
    assert.equal(hub.seq, 0)
    assert.equal(hub.nextSeq(), 1)
    assert.equal(hub.nextSeq(), 2)

    const seen = []
    const off = hub.addSink((event) => seen.push(event))
    const event = hub.publish('global', 'session.status', { sessionId: 's-1', running: true })
    assert.equal(event.seq, 3)
    assert.equal(event.topic, 'global')
    assert.equal(event.kind, 'session.status')
    assert.equal(typeof event.id, 'string')
    assert.equal(typeof event.ts, 'number')
    assert.equal(seen.length, 1)
    off()
    hub.publish('global', 'session.status', { sessionId: 's-1', running: false })
    assert.equal(seen.length, 1)
  })

  it('serves eventsSince with topic filtering and gap detection', async () => {
    // `ringSize` is clamped to a minimum of 16 by config/EventHub.
    const { hub } = await makeHub({ config: { ringSize: 16 } })
    hub.publish('global', 'a', { n: 1 })
    hub.publish('session:s-1', 'b', { n: 2 })
    hub.publish('session:s-2', 'c', { n: 3 })

    const all = hub.eventsSince(0)
    assert.equal(all.events.length, 3)
    assert.equal(all.gap, false)
    assert.equal(all.seq, 3)

    const after = hub.eventsSince(2)
    assert.deepEqual(after.events.map((event) => event.seq), [3])

    const filtered = hub.eventsSince(0, new Set(['session:s-1']))
    assert.deepEqual(filtered.events.map((event) => event.seq), [2])

    // Overflow the 16-entry ring: seq 1..5 get evicted.
    for (let index = 0; index < 20; index += 1) hub.publish('global', 'x', { index })
    assert.equal(hub.eventsSince(0).gap, true)
    assert.equal(hub.eventsSince(hub.eventsSince(0).events[0].seq - 1).gap, false)
  })

  it('persists and restores seq, sessions, devices and pending interventions', async () => {
    const { hub, sub } = await makeHub()
    hub.applySessions([
      { sessionId: 's-1', updatedAt: 10, running: false, blank: false, agentAvailable: true },
      { sessionId: 's-2', updatedAt: 20, running: true, blank: false, agentAvailable: true },
    ])
    hub.addIntervention({ eventId: 'ev-keep', sessionId: 's-2', kind: 'approval.request', data: { toolName: 'pwsh' } })
    hub.upsertDevice({ deviceId: 'dev-1', name: 'Pixel', tokenHash: 'hash', lastSeen: 1 })
    const seq = hub.seq
    await hub.flush()

    const stateFile = join(sub, 'state.json')
    const parsed = readJsonFile(stateFile)
    assert.ok(parsed, 'state.json should exist')
    assert.equal(parsed.v, 1)
    assert.equal(parsed.seq, seq)
    assert.ok(parsed.sessions.some((session) => session.sessionId === 's-1'))
    assert.ok(parsed.interventions.some((item) => item.eventId === 'ev-keep'))
    assert.ok(parsed.devices.some((device) => device.deviceId === 'dev-1'))

    // Messages are memory-only.
    hub.publish('session:s-1', 'message.user', { sessionId: 's-1', seq: 1, text: 'hello', role: 'user' })
    await hub.flush()
    const reloaded = readJsonFile(stateFile)
    assert.equal(JSON.stringify(reloaded).includes('hello'), false, 'message bodies must not be persisted')

    const restored = new EventHub({
      config: { ringSize: 8 },
      logger: { info() {}, warn() {}, error() {}, debug() {} },
      dir: sub,
    })
    await restored.load()
    resources.add(() => restored.close())
    assert.equal(restored.seq >= seq, true)
    assert.equal(restored.getSessions().length, 2)
    assert.equal(restored.getDevice('dev-1')?.name, 'Pixel')
    assert.equal(restored.listInterventions('pending').length, 1)
    assert.equal(restored.getIntervention('ev-keep')?.status, 'pending')
  })

  it('backs up a corrupt state file instead of crashing', async () => {
    const { hub: warm } = await makeHub()
    warm.publish('global', 'warm', {})
    await warm.flush()
    const sub = join(dir, `corrupt-${Math.random().toString(16).slice(2)}`)
    await mkdir(sub, { recursive: true })
    const stateFile = join(sub, 'state.json')
    await writeFile(stateFile, '{ not json', 'utf8')
    const hub = new EventHub({
      config: { ringSize: 8 },
      logger: { info() {}, warn() {}, error() {}, debug() {} },
      dir: sub,
    })
    resources.add(() => hub.close())
    await hub.load()
    assert.equal(hub.seq, 0)
    assert.ok(readFileSync(`${stateFile}.bak`, 'utf8').includes('not json'))
  })

  it('keeps interventions idempotent and resolves them once', async () => {
    const { hub } = await makeHub()
    const events = []
    hub.addSink((event) => events.push(event))

    const first = hub.addIntervention({ eventId: 'ev-1', sessionId: 's-1', kind: 'approval.request', data: { toolName: 'pwsh' } })
    const second = hub.addIntervention({ eventId: 'ev-1', sessionId: 's-1', kind: 'approval.request', data: { toolName: 'other' } })
    assert.equal(first.eventId, second.eventId)
    assert.equal(hub.listInterventions('pending').length, 1)
    assert.equal(events.filter((event) => event.kind === 'approval.request').length, 1)

    const resolved = hub.removeIntervention('ev-1', 'allowed-once', 'this')
    assert.equal(resolved.status, 'resolved')
    assert.equal(resolved.by, 'this')
    assert.equal(hub.listInterventions('pending').length, 0)
    const resolvedEvents = events.filter((event) => event.kind === 'intervention.resolved')
    assert.equal(resolvedEvents.length, 1)
    assert.equal(resolvedEvents[0].data.by, 'this')
    assert.equal(resolvedEvents[0].data.outcome, 'allowed-once')

    // Second resolve is a no-op (the HTTP layer turns this into 404/409).
    assert.equal(hub.removeIntervention('ev-1', 'rejected', 'other'), null)
    assert.equal(hub.getIntervention('ev-1')?.outcome, 'allowed-once')
    assert.equal(hub.getIntervention('missing'), null)
  })

  it('sweeps expired interventions', async () => {
    const { hub } = await makeHub({ config: { approvalTtlMs: 1_000 } })
    const record = hub.addIntervention({ eventId: 'ev-ttl', sessionId: 's-1', kind: 'approval.request', data: {} })
    assert.equal(record.status, 'pending')
    // Reach into the stored record to age it (TTL floor is 1s in config).
    const stored = hub._interventions.get('ev-ttl')
    stored.expiresAt = Date.now() - 1
    assert.equal(hub.listInterventions('pending').length, 0)
    assert.equal(hub.getIntervention('ev-ttl'), null)
  })

  it('tracks session state, status and messages', async () => {
    const { hub } = await makeHub()
    hub.applySessions([
      { sessionId: 's-1', updatedAt: 10, running: false, blank: false, agentAvailable: true, cwd: 'E:/a' },
      { sessionId: 's-2', updatedAt: 20, running: true, blank: false, agentAvailable: true },
    ])
    assert.equal(hub.getSessions().length, 2)
    assert.equal(hub.getSessionStatus('s-1')?.running, false)

    hub.applySessionStatus('s-1', true)
    assert.equal(hub.getSessionStatus('s-1')?.running, true)
    assert.equal(hub.isSessionRunning('s-1'), true)
    // No change -> no event.
    assert.equal(hub.applySessionStatus('s-1', true), null)

    hub.applySessionSummary({ sessionId: 's-3', updatedAt: 30, running: false, blank: false, agentAvailable: true })
    assert.equal(hub.getSessions().length, 3)
    assert.equal(hub.applySessionSummary({ sessionId: 's-3', title: 'Renamed' })?.kind, 'session.updated')
    assert.equal(hub.getSessions().find((session) => session.sessionId === 's-3')?.title, 'Renamed')

    hub.touchSession('s-1', 99)
    assert.equal(hub.getSessions().find((session) => session.sessionId === 's-1')?.updatedAt, 99)

    hub.removeSession('s-2')
    assert.equal(hub.getSessions().some((session) => session.sessionId === 's-2'), false)

    hub.publish('session:s-1', 'message.user', { sessionId: 's-1', seq: 2, time: 5, role: 'user', text: 'hi' })
    hub.publish('session:s-1', 'message.assistant', { sessionId: 's-1', seq: 3, time: 6, role: 'assistant', text: 'yo' })
    const messages = hub.getMessages('s-1')
    assert.deepEqual(messages.map((message) => message.seq), [2, 3])
    assert.deepEqual(messages.map((message) => message.text), ['hi', 'yo'])
  })

  it('tracks workspaces and their metadata', async () => {
    const { hub } = await makeHub()
    hub.applyWorkspaces({
      workspaces: [
        { workspaceId: 'w-1', title: 'one', sessionIds: ['s-1'] },
        { workspaceId: 'w-2', title: 'two', sessionIds: [] },
      ],
      archivedSessionIds: ['s-old'],
      pinnedSessionIds: ['s-1'],
    })
    assert.deepEqual(hub.getWorkspaces().map((workspace) => workspace.workspaceId), ['w-1', 'w-2'])
    assert.deepEqual(hub.getWorkspaceMeta().archivedSessionIds, ['s-old'])
    assert.equal(hub.getWorkspaceOfSession('s-1'), 'w-1')

    hub.upsertWorkspace({ workspaceId: 'w-3', title: 'three' })
    assert.equal(hub.getWorkspaces().length, 3)
    hub.upsertWorkspace({ workspaceId: 'w-3', removed: true })
    assert.equal(hub.getWorkspaces().length, 2)
    hub.upsertWorkspace({ order: ['w-2', 'w-1'] })
    assert.deepEqual(hub.getWorkspaces().map((workspace) => workspace.workspaceId), ['w-2', 'w-1'])
  })

  it('reports DSH status and a complete snapshot', async () => {
    const { hub } = await makeHub()
    hub.applySessions([{ sessionId: 's-1', updatedAt: 1, running: false, blank: false, agentAvailable: true }])
    hub.applyWorkspaces([{ workspaceId: 'w-1', title: 'one' }])
    hub.addIntervention({ eventId: 'ev-p', sessionId: 's-1', kind: 'approval.request', data: {} })
    hub.setEventsClientId('client-7')

    assert.equal(hub.getDshStatus().connected, false)
    assert.equal(hub.setDshStatus({ connected: true, clientId: 'client-7' })?.kind, 'gateway.status')
    assert.equal(hub.getDshStatus().connected, true)
    assert.equal(hub.getEventsClientId(), 'client-7')

    const snapshot = hub.snapshot()
    assert.equal(snapshot.seq, hub.seq)
    assert.equal(typeof snapshot.serverTime, 'number')
    assert.equal(snapshot.sessions.length, 1)
    assert.equal(snapshot.workspaces.length, 1)
    assert.equal(snapshot.pending.length, 1)
    assert.equal(snapshot.dsh.connected, true)
  })

  it('manages the device table', async () => {
    const { hub } = await makeHub()
    hub.upsertDevice({ deviceId: 'd1', name: 'Phone', tokenHash: 'abc', lastSeen: 1 })
    hub.upsertDevice({ deviceId: 'd2', name: 'Tablet', tokenHash: 'def', lastSeen: 2 })
    assert.equal(hub.listDevices().length, 2)
    assert.equal(hub.getDevice('d1')?.name, 'Phone')
    assert.equal(hub.getDeviceByTokenHash('def')?.deviceId, 'd2')
    hub.upsertDevice({ deviceId: 'd1', name: 'Phone 2', tokenHash: 'abc', lastSeen: 3 })
    assert.equal(hub.getDevice('d1')?.name, 'Phone 2')
    assert.equal(hub.removeDevice('d2'), true)
    assert.equal(hub.getDevice('d2'), null)
  })
})
