/**
 * 图片预览：`/v1` 消息里的 `images`、按需附件字节、会话文件图片端点。
 *
 * 图片不复制、不落盘：设备拿到的永远是 PC 上那一份的按需预览。
 *
 * @module dsh-gateway/test/images.test
 */

import assert from 'node:assert/strict'
import { resolve } from 'node:path'
import { after, before, describe, it } from 'node:test'

import {
  createResources,
  libStatus,
  request,
  requestBinary,
  startGateway,
  waitForDsh,
  waitForHealth,
} from './helper.mjs'
import { MockDsh, defaultSessions } from './mock-dsh.mjs'

const REQUIRED = ['index.js', 'hub.js', 'dsh.js', 'http.js', 'ws.js']
const status = await libStatus()
const missing = REQUIRED.filter((name) => status.missing.includes(name))
const reason = missing.length === 0 ? undefined : `lib modules not implemented yet: ${missing.join(', ')}`

/** 1x1 red PNG. */
const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64',
)

const ATTACHMENT = {
  attachmentId: 'sha256:6b1d0f7b4a1c9e2d3f4a5b6c7d8e9f00112233445566778899aabbccddeeff00',
  mediaType: 'image/png',
  width: 1,
  height: 1,
  bytes: PNG.byteLength,
  name: 'shot.png',
}

describe('image previews', { skip: reason }, () => {
  /** @type {ReturnType<typeof createResources>} */
  let resources
  /** @type {MockDsh} */
  let mock
  /** @type {any} */
  let gateway
  /** @type {string} */
  let base

  /** Absolute path the gateway will resolve from `cwd + relative`. */
  const filePath = resolve('E:/work/alpha', 'docs/shot.png')

  before(async () => {
    resources = createResources()
    mock = new MockDsh({ sessions: defaultSessions() })
    await mock.start()
    resources.add(() => mock.stop())
    mock.attachments.set(ATTACHMENT.attachmentId, { bytes: PNG, mediaType: 'image/png' })
    mock.files.set(filePath, { bytes: PNG, mediaType: 'image/png' })
    const started = await startGateway({ mock, resources, config: { ringSize: 16 } })
    gateway = started.gateway
    base = started.lanBase
    await waitForHealth(base)
    await waitForDsh(gateway)
  })

  after(async () => {
    await resources.disposeAll()
  })

  /** 一条带图片的 user 消息 + 一条带图片的 tool 结果。 */
  function seedImageHistory() {
    mock.setHistory('s-alpha', [
      { type: 'event', event: { type: 'turn/start', seq: 1, time: 1, data: { turn: 1 } } },
      {
        type: 'event',
        event: {
          type: 'user/message',
          seq: 2,
          time: 2,
          data: {
            id: 'msg-image-1',
            role: 'user',
            content: [{ type: 'image', attachment: ATTACHMENT }],
            source: { kind: 'user' },
          },
        },
      },
      {
        type: 'event',
        event: {
          type: 'tool/result',
          seq: 3,
          time: 3,
          data: {
            message: { role: 'tool', content: [{ type: 'image', attachment: ATTACHMENT }], source: { kind: 'tool' } },
          },
        },
      },
    ])
  }

  it('history messages carry the stored image references', async () => {
    seedImageHistory()
    const response = await request(base, '/v1/sessions/s-alpha/messages')
    assert.equal(response.status, 200)
    const withImages = response.json.messages.filter((message) => Array.isArray(message.images))
    assert.equal(withImages.length, 2)
    for (const message of withImages) {
      assert.deepEqual(message.images, [{
        attachmentId: ATTACHMENT.attachmentId,
        mediaType: 'image/png',
        width: 1,
        height: 1,
        bytes: ATTACHMENT.bytes,
        name: 'shot.png',
      }])
    }
  })

  it('GET attachments streams the stored bytes without caching them', async () => {
    const query = `?mediaType=image%2Fpng&width=1&height=1&bytes=${ATTACHMENT.bytes}&name=shot.png`
    const response = await requestBinary(base, `/v1/sessions/s-alpha/attachments/${ATTACHMENT.attachmentId}${query}`)
    assert.equal(response.status, 200)
    assert.equal(response.headers.get('content-type'), 'image/png')
    assert.equal(response.headers.get('cache-control'), 'private, no-store')
    assert.equal(response.headers.get('content-length'), String(PNG.byteLength))
    assert.deepEqual(response.bytes, PNG)
  })

  it('unknown attachments answer 404', async () => {
    const response = await request(base, '/v1/sessions/s-alpha/attachments/att-missing?mediaType=image%2Fpng&width=1&height=1&bytes=1')
    assert.equal(response.status, 404)
    assert.equal(response.json.error.code, 'not_found')
  })

  it('an invalid attachment reference answers 400', async () => {
    const response = await request(base, '/v1/sessions/s-alpha/attachments/bad-ref?mediaType=image%2Fpng&width=1&height=1&bytes=1')
    assert.equal(response.status, 400)
    assert.equal(response.json.error.code, 'invalid_request')
  })

  it('an attachment request without mediaType is rejected', async () => {
    const response = await request(base, `/v1/sessions/s-alpha/attachments/${ATTACHMENT.attachmentId}?width=1&height=1&bytes=1`)
    assert.equal(response.status, 400)
    assert.equal(response.json.error.code, 'invalid_request')
  })

  it('GET image previews a cwd-relative file from the PC', async () => {
    const response = await requestBinary(base, '/v1/sessions/s-alpha/image?path=docs%2Fshot.png')
    assert.equal(response.status, 200)
    assert.equal(response.headers.get('content-type'), 'image/png')
    assert.deepEqual(response.bytes, PNG)
  })

  it('GET image refuses non-image files', async () => {
    const response = await request(base, '/v1/sessions/s-alpha/image?path=docs%2Fnotes.txt')
    assert.equal(response.status, 415)
    assert.equal(response.json.error.code, 'unsupported_type')
  })

  it('GET image answers 404 when the PC file is gone', async () => {
    const missingFile = resolve('E:/work/alpha', 'docs/missing.png')
    assert.equal(mock.files.has(missingFile), false)
    const response = await request(base, '/v1/sessions/s-alpha/image?path=docs%2Fmissing.png')
    assert.equal(response.status, 404)
  })
})
