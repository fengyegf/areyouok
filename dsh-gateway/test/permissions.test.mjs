/**
 * `/v1/permissions` and `/v1/sessions/{id}/permission` end-to-end: the real
 * HTTP listener + gateway stack against the mock DSH.
 *
 * @module dsh-gateway/test/permissions.test
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
import { MockDsh, defaultPermissionCatalog } from './mock-dsh.mjs'

const REQUIRED = ['index.js', 'hub.js', 'dsh.js', 'http.js']
const status = await libStatus()
const missing = REQUIRED.filter((name) => status.missing.includes(name))
const reason = missing.length === 0 ? undefined : `lib modules not implemented yet: ${missing.join(', ')}`

describe('/v1 permissions', { skip: reason }, () => {
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
   * Switch one session's preset.
   * @param {string} sessionId
   * @param {unknown} body
   */
  const switchPreset = (sessionId, body) => request(base, `/v1/sessions/${sessionId}/permission`, {
    method: 'POST',
    body,
  })

  it('GET /v1/permissions passes permissionPresets/catalog through', async () => {
    const response = await request(base, '/v1/permissions')
    assert.equal(response.status, 200)
    assert.deepEqual(response.json, { ok: true, catalog: defaultPermissionCatalog() })
    const call = mock.lastCall('permissionPresets/catalog')
    assert.ok(call, 'permissionPresets/catalog should be called')
    assert.deepEqual(call.args, {})
  })

  it('POST /v1/sessions/{id}/permission switches the preset', async () => {
    const response = await switchPreset('s-alpha', { preset: 'read-only' })
    assert.equal(response.status, 200)
    assert.deepEqual(response.json, {
      ok: true,
      sessionId: 's-alpha',
      preset: 'read-only',
      result: { kind: 'success', text: 'preset read-only' },
    })
    // The catalog is read first, then the slash command runs with the strict
    // flat wire args (`submittedAttachments` must always be present).
    assert.equal(mock.callsFor('permissionPresets/catalog').length, 1)
    assert.deepEqual(mock.lastCall('commands/execute').args, {
      agentId: 's-alpha',
      line: '/permission read-only',
      submittedAttachments: [],
    })

    // The path segment is decoded before it reaches DSH.
    const encoded = await switchPreset('s%2Dalpha', { preset: 'danger-full-access' })
    assert.equal(encoded.status, 200)
    assert.equal(encoded.json.sessionId, 's-alpha')
    assert.deepEqual(mock.lastCall('commands/execute').args, {
      agentId: 's-alpha',
      line: '/permission danger-full-access',
      submittedAttachments: [],
    })
  })

  it('rejects a preset that is not in the catalog with 400 invalid_request', async () => {
    const response = await switchPreset('s-alpha', { preset: 'turbo-mode' })
    assert.equal(response.status, 400)
    assert.equal(response.json.ok, false)
    assert.equal(response.json.error.code, 'invalid_request')
    assert.match(response.json.error.message, /unknown preset "turbo-mode"/)
    assert.match(response.json.error.message, /read-only/)
    assert.match(response.json.error.message, /workspace-write/)
    assert.match(response.json.error.message, /danger-full-access/)
    // Validated before the command runs: DSH never sees the typo.
    assert.equal(mock.callsFor('commands/execute').length, 0)
  })

  it('rejects a missing or malformed preset with 400 invalid_request', async () => {
    for (const body of [{}, { preset: '' }, { preset: 42 }, { preset: 'Read Only' }, { preset: 'read_only' }]) {
      const response = await switchPreset('s-alpha', body)
      assert.equal(response.status, 400, `body ${JSON.stringify(body)} should be rejected`)
      assert.equal(response.json.ok, false)
      assert.equal(response.json.error.code, 'invalid_request')
    }
    // A malformed preset is refused before the catalog is even read.
    assert.equal(mock.callsFor('permissionPresets/catalog').length, 0)
    assert.equal(mock.callsFor('commands/execute').length, 0)
  })

  it('maps a DSH command error result to 400 permission_rejected', async () => {
    mock.setResult('commands/execute', {
      commandId: 'cmd-fixed',
      result: {
        kind: 'error',
        text: 'unknown preset "read-only" (available: read-only, workspace-write, danger-full-access)',
      },
    })
    const response = await switchPreset('s-alpha', { preset: 'read-only' })
    assert.equal(response.status, 400)
    assert.deepEqual(response.json, {
      ok: false,
      error: {
        code: 'permission_rejected',
        message: 'unknown preset "read-only" (available: read-only, workspace-write, danger-full-access)',
      },
    })
  })

  it('answers 502 dsh_unavailable when DSH fails', async () => {
    // Catalog unreadable: both the read route and the switch route report it.
    mock.failMethod('permissionPresets/catalog', { code: 'gateway/internal', message: 'boom' })
    const read = await request(base, '/v1/permissions')
    assert.equal(read.status, 502)
    assert.equal(read.json.ok, false)
    assert.equal(read.json.error.code, 'dsh_unavailable')

    mock.failMethod('permissionPresets/catalog', { code: 'gateway/internal', message: 'boom' })
    const catalogDown = await switchPreset('s-alpha', { preset: 'read-only' })
    assert.equal(catalogDown.status, 502)
    assert.equal(catalogDown.json.error.code, 'dsh_unavailable')

    // Catalog is fine but the slash command itself fails.
    mock.failMethod('commands/execute', { code: 'gateway/internal', message: 'boom' })
    const executeDown = await switchPreset('s-alpha', { preset: 'read-only' })
    assert.equal(executeDown.status, 502)
    assert.equal(executeDown.json.error.code, 'dsh_unavailable')
  })
})
