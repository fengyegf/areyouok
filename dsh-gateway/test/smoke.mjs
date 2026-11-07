#!/usr/bin/env node
/**
 * Full-chain smoke run for dsh-gateway (no real DSH, no network access).
 *
 * Boots the mock DSH host, starts the real gateway stack against it, then walks
 * the APK path end to end over real HTTP + WebSocket:
 *
 *   health → workspaces → sessions → WS ready/hello/sub → prompt
 *   → user/assistant events → assistant-stream delta
 *   → approval waterfall → GET /v1/interventions → answer → $events/result
 *   → intervention.resolved → desktop-first cancel → /v1/events catch-up
 *
 * Prints `SMOKE OK` and exits 0 on success; prints the failing step and exits 1.
 *
 * @module dsh-gateway/test/smoke
 */

import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

import { MockDsh } from './mock-dsh.mjs'
import {
  StreamClient,
  createResources,
  freePort,
  loadLib,
  makeCtx,
  makeLogger,
  request,
  waitFor,
  waitForDsh,
} from './helper.mjs'

const STEP_TIMEOUT_MS = 15_000

/** Log one passing step. */
function step(message) {
  console.log(`  ✔ ${message}`)
}

/** Fail loudly with the step that broke. */
function check(condition, message) {
  if (!condition) throw new Error(`assertion failed: ${message}`)
}

/**
 * Run the whole chain.
 * @returns {Promise<void>}
 */
async function main() {
  const resources = createResources()
  const dir = await mkdtemp(join(tmpdir(), 'dshgw-smoke-'))
  resources.add(() => rm(dir, { recursive: true, force: true }))
  const logger = makeLogger()
  const mock = new MockDsh()
  await mock.start()
  resources.add(() => mock.stop())

  let gateway
  let base
  let phone
  try {
    const { createGateway } = await loadLib('index.js')
    const httpPort = await freePort()
    gateway = createGateway({
      ctx: makeCtx({
        origin: mock.origin,
        port: mock.port,
        host: mock.host,
        launchToken: mock.launchToken,
        logger,
      }),
      config: {
        httpPort,
        httpsPort: 0,
        host: '127.0.0.1',
        mdns: false,
        ringSize: 256,
        heartbeatMs: 5_000,
        idleTimeoutMs: 60_000,
      },
      logger,
      dir,
    })
    const ports = await gateway.start()
    resources.add(() => gateway.stop())
    base = `http://127.0.0.1:${ports.lanPort}`
    console.log(`dsh-gateway smoke: mock DSH at ${mock.origin}, gateway at ${base}`)

    // 1. Discovery
    const health = await waitFor(async () => {
      const response = await request(base, '/v1/health', { timeoutMs: 1_000 }).catch(() => null)
      return response?.status === 200 ? response : false
    }, { timeoutMs: STEP_TIMEOUT_MS, label: 'health' })
    check(health.json.ok === true, 'health.ok')
    check(health.json.v === 1, 'health.v === 1')
    check(health.json.lan === true, 'health.lan === true')
    step(`health ok (name=${health.json.name}, dsh=${health.json.dsh})`)

    await waitForDsh(gateway, { timeoutMs: STEP_TIMEOUT_MS })
    step(`loopback link ready (clientId=${gateway.dsh.eventsClientId?.slice(0, 8)}…)`)

    // 2. Workspaces + sessions
    await waitFor(() => gateway.hub.getWorkspaces().length >= 2, { timeoutMs: STEP_TIMEOUT_MS, label: 'workspaces' })
    const workspaces = await request(base, '/v1/workspaces')
    check(workspaces.json.ok === true, 'workspaces.ok')
    check(workspaces.json.workspaces.length === 2, 'workspaces length')
    step(`workspaces ok (${workspaces.json.workspaces.map((item) => item.workspaceId).join(', ')})`)

    await waitFor(() => gateway.hub.getSessions().length >= 2, { timeoutMs: STEP_TIMEOUT_MS, label: 'sessions' })
    const sessions = await request(base, '/v1/sessions')
    check(sessions.json.ok === true, 'sessions.ok')
    const alpha = sessions.json.sessions.find((session) => session.sessionId === 's-alpha')
    check(alpha !== undefined, 's-alpha listed')
    step(`sessions ok (${sessions.json.sessions.map((session) => session.sessionId).join(', ')})`)

    // 3. Phone connects: ready → hello → sub
    const wsUrl = `${base.replace('http', 'ws')}/v1/stream`
    phone = await StreamClient.connect(wsUrl)
    resources.add(() => phone.close())
    const ready = await phone.waitForType('ready', { timeoutMs: STEP_TIMEOUT_MS })
    check(typeof ready.seq === 'number', 'ready.seq')
    check(Array.isArray(ready.snapshot.sessions), 'ready.snapshot.sessions')
    step(`WS ready (seq=${ready.seq}, sessions=${ready.snapshot.sessions.length})`)

    phone.send({ t: 'hello', v: 1, deviceId: 'smoke-device', resume: { seq: ready.seq } })
    phone.send({ t: 'sub', id: 'smoke-sub', topic: 'session:s-alpha' })
    await waitFor(() => {
      const conns = [...(gateway.ws?.connections ?? [])]
      return conns.some((conn) => conn.deviceId === 'smoke-device')
    }, { timeoutMs: STEP_TIMEOUT_MS, label: 'hello handled' })
    step('WS hello + sub session:s-alpha')

    // 4. Prompt
    const sent = await request(base, '/v1/sessions/s-alpha/messages', {
      method: 'POST',
      body: { text: 'hello from the smoke test', mode: 'queue' },
    })
    check(sent.status === 200, 'prompt status 200')
    check(sent.json.ok === true && sent.json.accepted === true, 'prompt accepted')
    step('POST /v1/sessions/s-alpha/messages accepted (session/prompt)')

    // 5. DSH echo: user message, assistant message, streamed delta
    mock.sessionEvent('s-alpha', 'user/message', {
      id: 'smoke-user-1',
      role: 'user',
      content: [{ type: 'text', text: 'hello from the smoke test' }],
      source: { kind: 'user' },
    })
    mock.sessionEvent('s-alpha', 'assistant/message', {
      turn: 1,
      step: 1,
      message: { role: 'assistant', content: [{ type: 'text', text: 'hi there' }] },
      stream: [],
    })
    mock.assistantStream('s-alpha', {
      type: 'chunk',
      turn: 1,
      step: 1,
      chunk: { type: 'text-delta', text: 'streaming…' },
    })

    const userEvent = await phone.waitForFrame(
      (frame) => frame.t === 'event' && frame.kind === 'message.user' && frame.data?.text === 'hello from the smoke test',
      { timeoutMs: STEP_TIMEOUT_MS, label: 'message.user' },
    )
    check(userEvent.topic === 'session:s-alpha', 'user event topic')
    // The follow snapshot replays the baseline assistant message before the
    // live one, so match the text instead of just the kind.
    const assistantEvent = await phone.waitForFrame(
      (frame) => frame.t === 'event' && frame.kind === 'message.assistant' && frame.data?.text === 'hi there',
      { timeoutMs: STEP_TIMEOUT_MS, label: 'message.assistant' },
    )
    check(assistantEvent.data.text === 'hi there', 'assistant text')
    const delta = await phone.waitForFrame(
      (frame) => frame.t === 'event' && frame.kind === 'message.delta' && frame.data?.text === 'streaming…',
      { timeoutMs: STEP_TIMEOUT_MS, label: 'message.delta' },
    )
    check(delta.data.text === 'streaming…', 'delta text')
    step('streamed message.user / message.assistant / message.delta')

    // 6. Approval waterfall → pending → answer → $events/result
    const approval = mock.waterfall('approval/request', {
      agentId: 's-alpha',
      request: { toolName: 'pwsh', callId: 'smoke-call', reason: 'smoke test' },
    })
    const approvalEvent = await phone.waitForFrame(
      (frame) => frame.t === 'event' && frame.kind === 'approval.request' && frame.data?.eventId === approval.eventId,
      { timeoutMs: STEP_TIMEOUT_MS, label: 'approval.request' },
    )
    check(approvalEvent.data.sessionId === 's-alpha', 'approval sessionId')
    const pending = await request(base, '/v1/interventions?status=pending')
    check(pending.json.ok === true, 'interventions.ok')
    check(pending.json.interventions.some((item) => item.eventId === approval.eventId), 'approval pending listed')
    step(`approval.request pending (${approval.eventId.slice(0, 8)}…)`)

    const answered = await request(base, `/v1/interventions/${approval.eventId}/answer`, {
      method: 'POST',
      body: { value: 'allowed-once' },
    })
    check(answered.status === 200 && answered.json.ok === true, 'answer ok')
    await waitFor(() => mock.resultCalls.length >= 1, { timeoutMs: STEP_TIMEOUT_MS, label: '$events/result' })
    const result = mock.resultCalls[0]
    check(result.eventId === approval.eventId, '$events/result eventId')
    check(result.clientId === gateway.dsh.eventsClientId, '$events/result latest clientId')
    check(result.outcome?.kind === 'result', '$events/result outcome.kind')
    check(result.outcome?.value === 'allowed-once', '$events/result outcome.value')
    const resolved = await phone.waitForFrame(
      (frame) => frame.t === 'event' && frame.kind === 'intervention.resolved' && frame.data?.eventId === approval.eventId,
      { timeoutMs: STEP_TIMEOUT_MS, label: 'intervention.resolved' },
    )
    check(resolved.data.by === 'this', 'resolved.by === this')
    step('answer → POST /api/$events/result → intervention.resolved')

    // 7. Desktop answers first: mock cancel frame
    const second = mock.waterfall('user-questions/request', {
      agentId: 's-alpha',
      request: { questions: [{ id: 'q1', question: 'continue?', options: [{ label: 'Yes' }, { label: 'No' }] }] },
    })
    await phone.waitForFrame(
      (frame) => frame.t === 'event' && frame.kind === 'question.request' && frame.data?.eventId === second.eventId,
      { timeoutMs: STEP_TIMEOUT_MS, label: 'question.request' },
    )
    mock.cancel(second.eventId)
    const desktopResolved = await phone.waitForFrame(
      (frame) => frame.t === 'event' && frame.kind === 'intervention.resolved' && frame.data?.eventId === second.eventId,
      { timeoutMs: STEP_TIMEOUT_MS, label: 'resolved by other' },
    )
    check(desktopResolved.data.by === 'other', 'resolved.by === other')
    const empty = await request(base, '/v1/interventions?status=pending')
    check(empty.json.interventions.length === 0, 'no pending interventions left')
    step('desktop-first cancel → intervention.resolved (by other)')

    // 8. REST catch-up path (/v1/events?sinceSeq=) — session + global topics.
    const catchup = await request(base, `/v1/events?sinceSeq=${ready.seq}&topics=session:s-alpha,global`)
    check(catchup.json.ok === true, 'events.ok')
    check(catchup.json.gap === false, 'events.gap === false')
    check(catchup.json.events.length > 0, 'events replayed')
    step(`GET /v1/events?sinceSeq=${ready.seq} → ${catchup.json.events.length} events`)

    // 9. Interrupt + models (cheap final REST checks)
    const interrupt = await request(base, '/v1/sessions/s-alpha/interrupt', { method: 'POST' })
    check(interrupt.json.ok === true, 'interrupt ok')
    const models = await request(base, '/v1/models')
    check(models.json.ok === true && models.json.catalog !== null, 'models ok')
    step('POST /v1/sessions/:id/interrupt + GET /v1/models')

    const warnings = logger.entries.filter((entry) => entry.level === 'error')
    check(warnings.length === 0, `no error logs (saw ${warnings.map((entry) => entry.message).join(' | ')})`)

    console.log('SMOKE OK')
    process.exitCode = 0
  } finally {
    try {
      await phone?.close()
    } catch {
      // ignore
    }
    await resources.disposeAll().catch(() => {})
  }
}

main().catch((error) => {
  console.error(`SMOKE FAILED: ${error?.stack ?? error}`)
  process.exitCode = 1
})
