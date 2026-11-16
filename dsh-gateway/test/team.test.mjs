/**
 * `/v1/sessions/{id}/team` end-to-end: the real HTTP listener + gateway stack
 * against the mock DSH.
 *
 * The endpoint is a thin boolean switch over `agentTeams/setEnabled`, so these
 * tests pin the contract the APK depends on: the echoed body, the 400/502 error
 * codes and the strict flat RPC args.
 *
 * @module dsh-gateway/test/team.test
 */

import assert from 'node:assert/strict'
import { after, before, beforeEach, describe, it } from 'node:test'

import {
  createResources,
  libStatus,
  request,
  startGateway,
  waitForDsh,
  waitForHealth,
} from './helper.mjs'
import { MockDsh } from './mock-dsh.mjs'

const REQUIRED = ['index.js', 'hub.js', 'dsh.js', 'http.js']
const status = await libStatus()
const missing = REQUIRED.filter((name) => status.missing.includes(name))
const reason = missing.length === 0 ? undefined : `lib modules not implemented yet: ${missing.join(', ')}`

describe('/v1 session team mode', { skip: reason }, () => {
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
    const started = await startGateway({ mock, resources, config: { ringSize: 16 } })
    gateway = started.gateway
    base = started.lanBase
    await waitForHealth(base)
    await waitForDsh(gateway)
  })

  after(async () => {
    await resources.disposeAll()
  })

  beforeEach(() => {
    mock.reset()
    // Programmed results/failures are sticky by design; this file owns the
    // mock instance, so isolate each test explicitly.
    mock.results.clear()
    mock.failures.clear()
  })

  /**
   * Switch one session's team mode.
   * @param {string} sessionId
   * @param {unknown} [body]
   */
  const setTeam = (sessionId, body) => request(base, `/v1/sessions/${sessionId}/team`, {
    method: 'POST',
    body,
  })

  it('enables team mode and echoes the accepted state', async () => {
    const response = await setTeam('s-alpha', { enabled: true })
    assert.equal(response.status, 200)
    assert.deepEqual(response.json, { ok: true, sessionId: 's-alpha', enabled: true })
    // Strict DSH codec: flat wire args, no `request` envelope.
    assert.equal(mock.callsFor('agentTeams/setEnabled').length, 1)
    assert.deepEqual(mock.lastCall('agentTeams/setEnabled').args, { agentId: 's-alpha', enabled: true })
  })

  it('disables team mode and decodes the path segment', async () => {
    const response = await setTeam('s%2Dalpha', { enabled: false })
    assert.equal(response.status, 200)
    assert.deepEqual(response.json, { ok: true, sessionId: 's-alpha', enabled: false })
    assert.deepEqual(mock.lastCall('agentTeams/setEnabled').args, { agentId: 's-alpha', enabled: false })
  })

  it('rejects a missing or non-boolean enabled with 400 invalid_request', async () => {
    for (const body of [{}, { enabled: null }, { enabled: 'true' }, { enabled: 1 }, { enabled: [] }]) {
      const response = await setTeam('s-alpha', body)
      assert.equal(response.status, 400, `body ${JSON.stringify(body)} should be rejected`)
      assert.equal(response.json.ok, false)
      assert.equal(response.json.error.code, 'invalid_request')
      assert.match(response.json.error.message, /enabled/)
      assert.match(response.json.error.message, /boolean/)
    }
    // An absent body reads as `{}` (readJsonBody) and is refused the same way.
    const noBody = await setTeam('s-alpha')
    assert.equal(noBody.status, 400)
    assert.equal(noBody.json.ok, false)
    assert.equal(noBody.json.error.code, 'invalid_request')
    // Validated before DSH runs: nothing reaches the host on a bad body.
    assert.equal(mock.callsFor('agentTeams/setEnabled').length, 0)
  })

  it('answers 502 dsh_unavailable when the DSH call fails', async () => {
    mock.failMethod('agentTeams/setEnabled', { code: 'gateway/internal', message: 'boom' })
    const response = await setTeam('s-alpha', { enabled: true })
    assert.equal(response.status, 502)
    assert.deepEqual(response.json, {
      ok: false,
      error: { code: 'dsh_unavailable', message: 'DSH could not save the team mode' },
    })
    assert.equal(mock.callsFor('agentTeams/setEnabled').length, 1)
  })
})
