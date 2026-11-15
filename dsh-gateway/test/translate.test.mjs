/**
 * Translation backend tests: provider wire shape, language mapping, durable
 * cache, the /v1 error envelope and the HTTP routes.
 *
 * The anonymous endpoints are never called: global fetch is replaced by a
 * router that only answers the two translate hosts (everything else, including
 * the test client itself, passes through to the real fetch).
 *
 * @module dsh-gateway/test/translate.test
 */

import assert from 'node:assert/strict'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { after, afterEach, before, beforeEach, describe, it } from 'node:test'

import {
  MAX_CONCURRENT,
  MAX_QUEUED,
  TranslationService,
  providerLanguage,
} from '../lib/translate.js'
import {
  createResources,
  makeLogger,
  request,
  startGateway,
  waitForHealth,
} from './helper.mjs'
import { MockDsh } from './mock-dsh.mjs'

const realFetch = globalThis.fetch

/** Whether one URL targets an anonymous translate endpoint. */
function isTranslateUrl(href) {
  return href.startsWith('https://edge.microsoft.com/') || href.startsWith('https://translate.googleapis.com/')
}

/** Install a translate-only fetch router; returns the restore function. */
function installFetch(handler) {
  globalThis.fetch = (input, init) => {
    const href = typeof input === 'string' ? input : String(input)
    if (isTranslateUrl(href)) return handler(href, init)
    return realFetch(input, init)
  }
  return () => {
    globalThis.fetch = realFetch
  }
}

/** One JSON Response. */
function jsonResponse(body, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
}

/** Bing translatetext payload. */
function bingPayload(text) {
  return [{ translations: [{ text, to: 'zh-Hans' }] }]
}

/** Google translate_a/single payload. */
function googlePayload(text) {
  return [[[text, 'hello', null, null]], null, 'en']
}

/** Full resolved-config shape for one service. */
function serviceConfig(overrides = {}) {
  return {
    translateEnabled: true,
    translateProvider: 'bing',
    translateTimeoutMs: 5_000,
    translateMaxTextChars: 4_000,
    translateMaxResponseBytes: 1_048_576,
    translateCacheMaxEntries: 500,
    ...overrides,
  }
}

/** Create a service in a fresh temp dir registered on the resources. */
async function makeService(resources, overrides = {}) {
  const dir = await mkdtemp(join(tmpdir(), 'dshgw-translate-'))
  resources.add(() => rm(dir, { recursive: true, force: true }))
  const logger = makeLogger()
  const config = serviceConfig(overrides)
  const service = new TranslationService({ config, logger, dir })
  resources.add(() => service.close())
  return { service, dir, logger, config }
}

describe('TranslationService', () => {
  /** @type {ReturnType<typeof createResources>} */
  let resources
  let restoreFetch = () => {}

  beforeEach(() => {
    resources = createResources()
  })

  afterEach(async () => {
    restoreFetch()
    restoreFetch = () => {}
    await resources.disposeAll()
  })

  /** Route translate fetches through one mock handler. */
  function mockFetch(handler) {
    restoreFetch()
    restoreFetch = installFetch(handler)
  }

  it('advertises the two free providers and the accepted target languages', async () => {
    const { service } = await makeService(resources)
    const info = service.providers()
    assert.deepEqual(info.providers, [
      { id: 'bing', label: 'Bing', free: true },
      { id: 'google', label: 'Google', free: true },
    ])
    assert.equal(info.defaultProvider, 'bing')
    assert.equal(info.defaultTargetLanguage, 'zh-CN')
    assert.equal(info.maxTextChars, 4000)
    assert.deepEqual(info.targets, ['zh-CN', 'zh-TW', 'en', 'ja'])
  })

  it('sends the documented Bing request: URL params, JSON array body, headers, no redirects', async () => {
    const calls = []
    mockFetch(async (href, init) => {
      calls.push({ href, init })
      return jsonResponse(bingPayload('ni hao'))
    })
    const { service } = await makeService(resources)
    const result = await service.translate({ text: 'hello', targetLanguage: 'zh-CN' })

    assert.equal(calls.length, 1)
    const url = new URL(calls[0].href)
    assert.equal(url.origin + url.pathname, 'https://edge.microsoft.com/translate/translatetext')
    assert.equal(url.searchParams.get('to'), 'zh-Hans')
    assert.equal(url.searchParams.get('isEnterpriseClient'), 'false')
    assert.equal(url.searchParams.has('from'), false, 'auto source must not send from')
    assert.equal(calls[0].init.method, 'POST')
    assert.deepEqual(JSON.parse(calls[0].init.body), ['hello'])
    assert.equal(calls[0].init.headers['content-type'], 'application/json')
    assert.equal(calls[0].init.redirect, 'error')
    assert.equal(calls[0].init.credentials, 'omit')
    assert.ok(calls[0].init.signal instanceof AbortSignal)
    assert.deepEqual(result, {
      text: 'ni hao',
      provider: 'bing',
      targetLanguage: 'zh-CN',
      sourceLanguage: 'auto',
      cached: false,
      chars: 6,
    })
  })

  it('sends the documented Google form request with sl=auto', async () => {
    const calls = []
    mockFetch(async (href, init) => {
      calls.push({ href, init })
      return jsonResponse(googlePayload('你好'))
    })
    const { service } = await makeService(resources)
    const result = await service.translate({
      text: 'hello',
      targetLanguage: 'zh-CN',
      provider: 'google',
    })

    assert.equal(calls.length, 1)
    const url = new URL(calls[0].href)
    assert.equal(url.origin + url.pathname, 'https://translate.googleapis.com/translate_a/single')
    assert.equal(url.search, '', 'google keeps the query empty and posts a form')
    const form = new URLSearchParams(calls[0].init.body)
    assert.equal(form.get('client'), 'gtx')
    assert.equal(form.get('sl'), 'auto')
    assert.equal(form.get('tl'), 'zh-CN')
    assert.equal(form.get('dt'), 't')
    assert.equal(form.get('q'), 'hello')
    assert.equal(calls[0].init.method, 'POST')
    assert.equal(calls[0].init.headers['content-type'], 'application/x-www-form-urlencoded')
    assert.equal(calls[0].init.redirect, 'error')
    assert.equal(calls[0].init.credentials, 'omit')
    assert.equal(result.text, '你好')
    assert.equal(result.provider, 'google')
    assert.equal(result.chars, 2)
  })

  it('maps simplified and traditional Chinese per provider and passes other tags through', () => {
    for (const tag of ['zh-CN', 'zh', 'zh-SG', 'zh-Hans']) {
      assert.equal(providerLanguage(tag, 'bing'), 'zh-Hans')
      assert.equal(providerLanguage(tag, 'google'), 'zh-CN')
    }
    for (const tag of ['zh-TW', 'zh-HK', 'zh-Hant']) {
      assert.equal(providerLanguage(tag, 'bing'), 'zh-Hant')
      assert.equal(providerLanguage(tag, 'google'), 'zh-TW')
    }
    assert.equal(providerLanguage('en', 'bing'), 'en')
    assert.equal(providerLanguage('ja', 'google'), 'ja')
    assert.equal(providerLanguage('auto', 'bing'), 'auto')
  })

  it('sends from/sl only when the source language is explicit', async () => {
    const urls = []
    mockFetch(async (href) => {
      urls.push(href)
      return jsonResponse(bingPayload('r'))
    })
    const { service } = await makeService(resources)
    await service.translate({ text: 'a', targetLanguage: 'zh-TW', sourceLanguage: 'en' })
    await service.translate({ text: 'b', targetLanguage: 'zh-TW', sourceLanguage: 'zh-CN' })
    await service.translate({ text: 'c', targetLanguage: 'zh-TW', sourceLanguage: 'auto' })

    const explicit = new URL(urls[0])
    assert.equal(explicit.searchParams.get('from'), 'en')
    assert.equal(explicit.searchParams.get('to'), 'zh-Hant')
    const chinese = new URL(urls[1])
    assert.equal(chinese.searchParams.get('from'), 'zh-Hans')
    const auto = new URL(urls[2])
    assert.equal(auto.searchParams.has('from'), false)
  })

  it('serves the second identical request from the cache without network', async () => {
    let count = 0
    mockFetch(async () => {
      count += 1
      return jsonResponse(bingPayload('你好'))
    })
    const { service } = await makeService(resources)
    const first = await service.translate({ text: 'hello', targetLanguage: 'zh-CN' })
    const second = await service.translate({ text: 'hello', targetLanguage: 'zh-CN' })
    assert.equal(count, 1)
    assert.equal(first.cached, false)
    assert.equal(second.cached, true)
    assert.equal(second.text, '你好')
    assert.equal(service.cacheSize, 1)

    await service.translate({ text: 'hello again', targetLanguage: 'zh-CN' })
    assert.equal(count, 2, 'a different text is a cache miss')
  })

  it('persists the cache atomically and reuses it after a restart', async () => {
    let count = 0
    mockFetch(async () => {
      count += 1
      return jsonResponse(bingPayload('译文'))
    })
    const { service, dir, logger, config } = await makeService(resources)
    await service.translate({ text: 'source text', targetLanguage: 'zh-CN' })
    await service.close()

    const file = join(dir, 'translate-cache.json')
    const raw = await readFile(file, 'utf8')
    assert.equal(raw.includes('source text'), false, 'the source text is never stored verbatim')
    const parsed = JSON.parse(raw)
    assert.equal(parsed.version, 1)
    assert.equal(parsed.entries.length, 1)

    const restarted = new TranslationService({ config, logger, dir })
    resources.add(() => restarted.close())
    const second = await restarted.translate({ text: 'source text', targetLanguage: 'zh-CN' })
    assert.equal(second.cached, true)
    assert.equal(second.text, '译文')
    assert.equal(count, 1)
  })

  it('evicts the least recently used entry when the cache is full', async () => {
    mockFetch(async (href, init) => {
      const bing = JSON.parse(init.body)
      return jsonResponse(bingPayload('T:' + bing[0]))
    })
    const { service, dir } = await makeService(resources, { translateCacheMaxEntries: 2 })
    await service.translate({ text: 'one', targetLanguage: 'zh-CN' })
    await service.translate({ text: 'two', targetLanguage: 'zh-CN' })
    await service.translate({ text: 'three', targetLanguage: 'zh-CN' })
    assert.equal(service.cacheSize, 2)
    await service.flush()
    const parsed = JSON.parse(await readFile(join(dir, 'translate-cache.json'), 'utf8'))
    assert.equal(parsed.entries.length, 2)
  })

  it('rejects invalid input with 400 invalid_request and never calls the provider', async () => {
    let count = 0
    mockFetch(async () => {
      count += 1
      return jsonResponse(bingPayload('x'))
    })
    const { service } = await makeService(resources)
    await assert.rejects(
      () => service.translate({ text: '   ', targetLanguage: 'zh-CN' }),
      (error) => error.status === 400 && error.code === 'invalid_request',
    )
    await assert.rejects(
      () => service.translate({ text: 'hello', targetLanguage: 'fr' }),
      (error) => error.status === 400 && error.code === 'invalid_request',
    )
    await assert.rejects(
      () => service.translate({ text: 'hello', targetLanguage: 'zh-CN', provider: 'deepl' }),
      (error) => error.status === 400 && error.code === 'invalid_request',
    )
    await assert.rejects(
      () => service.translate({ text: 'hello', targetLanguage: 'zh-CN', sourceLanguage: 'not a tag!' }),
      (error) => error.status === 400 && error.code === 'invalid_request',
    )
    assert.equal(count, 0)
  })

  it('rejects text over maxTextChars with 413 text_too_long before any network call', async () => {
    let count = 0
    mockFetch(async () => {
      count += 1
      return jsonResponse(bingPayload('x'))
    })
    const { service } = await makeService(resources, { translateMaxTextChars: 20 })
    await assert.rejects(
      () => service.translate({ text: 'x'.repeat(21), targetLanguage: 'zh-CN' }),
      (error) => error.status === 413 && error.code === 'text_too_long',
    )
    assert.equal(count, 0)
  })

  it('maps a stalled provider to 504 translation_timeout', async () => {
    mockFetch((_href, init) => new Promise((_resolve, reject) => {
      init.signal?.addEventListener('abort', () => reject(new Error('aborted')), { once: true })
    }))
    const { service } = await makeService(resources, { translateTimeoutMs: 40 })
    await assert.rejects(
      () => service.translate({ text: 'slow', targetLanguage: 'zh-CN' }),
      (error) => error.status === 504 && error.code === 'translation_timeout',
    )
  })

  it('maps provider HTTP errors and unparseable bodies to 502 translation_failed', async () => {
    let mode = 'http'
    let count = 0
    mockFetch(async () => {
      count += 1
      if (mode === 'http') return jsonResponse({ error: 'boom' }, 500)
      if (mode === 'json') return new Response('<html>not json</html>', { status: 200 })
      return jsonResponse([{ translations: [] }])
    })
    const { service } = await makeService(resources)
    await assert.rejects(
      () => service.translate({ text: 'a', targetLanguage: 'zh-CN' }),
      (error) => error.status === 502 && error.code === 'translation_failed',
    )
    mode = 'json'
    await assert.rejects(
      () => service.translate({ text: 'b', targetLanguage: 'zh-CN' }),
      (error) => error.status === 502 && error.code === 'translation_failed',
    )
    mode = 'shape'
    await assert.rejects(
      () => service.translate({ text: 'c', targetLanguage: 'zh-CN' }),
      (error) => error.status === 502 && error.code === 'translation_failed',
    )
    assert.equal(count, 3, 'one attempt per request: no retry, no provider fallback')
  })

  it('rejects a response bigger than maxResponseBytes with 502', async () => {
    mockFetch(async () => jsonResponse(bingPayload('x'.repeat(500))))
    const { service } = await makeService(resources, { translateMaxResponseBytes: 64 })
    await assert.rejects(
      () => service.translate({ text: 'big', targetLanguage: 'zh-CN' }),
      (error) => error.status === 502 && error.code === 'translation_failed',
    )
  })

  it('rejects a transport failure with 502 translation_failed', async () => {
    mockFetch(async () => {
      throw new TypeError('network down')
    })
    const { service } = await makeService(resources)
    await assert.rejects(
      () => service.translate({ text: 'down', targetLanguage: 'zh-CN' }),
      (error) => error.status === 502 && error.code === 'translation_failed',
    )
  })

  it('caps concurrency at four running requests and answers 429 when the queue is full', async () => {
    mockFetch((_href, init) => new Promise((_resolve, reject) => {
      init.signal?.addEventListener('abort', () => reject(new Error('aborted')), { once: true })
    }))
    const { service } = await makeService(resources, { translateTimeoutMs: 5_000 })
    const pending = []
    for (let index = 0; index < MAX_CONCURRENT + MAX_QUEUED; index += 1) {
      pending.push(service.translate({ text: 'text-' + index, targetLanguage: 'zh-CN' }).catch((error) => error))
    }
    await new Promise((resolve) => setTimeout(resolve, 20))
    await assert.rejects(
      () => service.translate({ text: 'overflow', targetLanguage: 'zh-CN' }),
      (error) => error.status === 429 && error.code === 'rate_limited',
    )
    await service.close()
    const settled = await Promise.all(pending)
    assert.equal(settled.length, MAX_CONCURRENT + MAX_QUEUED)
    for (const value of settled) assert.ok(value instanceof Error)
  })

  it('answers 503 translation_unavailable when disabled', async () => {
    let count = 0
    mockFetch(async () => {
      count += 1
      return jsonResponse(bingPayload('x'))
    })
    const { service } = await makeService(resources, { translateEnabled: false })
    await assert.rejects(
      () => service.translate({ text: 'hello', targetLanguage: 'zh-CN' }),
      (error) => error.status === 503 && error.code === 'translation_unavailable',
    )
    assert.equal(count, 0)
  })

  it('never logs the source text', async () => {
    mockFetch(async () => jsonResponse(bingPayload('译文')))
    const { service, logger } = await makeService(resources)
    await service.translate({ text: 'SECRET_CHAIN_TEXT', targetLanguage: 'zh-CN' })
    await service.close()
    const blob = logger.entries.map((entry) => entry.message).join('\n')
    assert.equal(blob.includes('SECRET_CHAIN_TEXT'), false)
  })
})

describe('/v1/translate routes', () => {
  /** @type {ReturnType<typeof createResources>} */
  let resources
  /** @type {MockDsh} */
  let mock
  /** @type {any} */
  let gateway
  let base = ''
  let mode = 'ok'
  let calls = []
  let restoreFetch = () => {}

  before(async () => {
    resources = createResources()
    mock = new MockDsh()
    await mock.start()
    resources.add(() => mock.stop())
    const started = await startGateway({
      mock,
      resources,
      config: { ringSize: 16, translateTimeoutMs: 150 },
    })
    gateway = started.gateway
    base = started.lanBase
    await waitForHealth(base)
    restoreFetch = installFetch((href, init) => {
      calls.push({ href, init })
      const google = href.includes('translate.googleapis.com')
      if (mode === 'http500') return Promise.resolve(jsonResponse({ error: 'boom' }, 500))
      if (mode === 'hang') {
        return new Promise((_resolve, reject) => {
          init?.signal?.addEventListener('abort', () => reject(new Error('aborted')), { once: true })
        })
      }
      if (google) {
        const form = new URLSearchParams(String(init?.body ?? ''))
        return Promise.resolve(jsonResponse(googlePayload('G:' + (form.get('q') ?? ''))))
      }
      const body = JSON.parse(String(init?.body ?? '[]'))
      return Promise.resolve(jsonResponse(bingPayload('B:' + body[0])))
    })
  })

  after(async () => {
    restoreFetch()
    await resources.disposeAll()
  })

  beforeEach(() => {
    mode = 'ok'
    calls = []
    mock.reset()
  })

  it('GET /v1/translate/providers returns the discovery envelope', async () => {
    const response = await request(base, '/v1/translate/providers')
    assert.equal(response.status, 200)
    assert.equal(response.json.ok, true)
    assert.deepEqual(response.json.providers, [
      { id: 'bing', label: 'Bing', free: true },
      { id: 'google', label: 'Google', free: true },
    ])
    assert.equal(response.json.defaultProvider, 'bing')
    assert.equal(response.json.defaultTargetLanguage, 'zh-CN')
    assert.equal(response.json.maxTextChars, 4000)
    assert.deepEqual(response.json.targets, ['zh-CN', 'zh-TW', 'en', 'ja'])
  })

  it('POST /v1/translate translates through Bing and caches the result', async () => {
    const body = { text: 'integration hello', targetLanguage: 'zh-CN' }
    const first = await request(base, '/v1/translate', { method: 'POST', body })
    assert.equal(first.status, 200)
    assert.equal(first.json.ok, true)
    assert.equal(first.json.text, 'B:integration hello')
    assert.equal(first.json.provider, 'bing')
    assert.equal(first.json.targetLanguage, 'zh-CN')
    assert.equal(first.json.sourceLanguage, 'auto')
    assert.equal(first.json.cached, false)
    assert.equal(first.json.chars, 'B:integration hello'.length)
    assert.deepEqual(
      Object.keys(first.json).sort(),
      ['cached', 'chars', 'ok', 'provider', 'sourceLanguage', 'targetLanguage', 'text'],
    )

    const second = await request(base, '/v1/translate', { method: 'POST', body })
    assert.equal(second.status, 200)
    assert.equal(second.json.cached, true)
    assert.equal(calls.length, 1, 'the cache hit must not hit the network')
  })

  it('POST /v1/translate honours an explicit google provider', async () => {
    const response = await request(base, '/v1/translate', {
      method: 'POST',
      body: { text: 'google hello', targetLanguage: 'zh-TW', provider: 'google' },
    })
    assert.equal(response.status, 200)
    assert.equal(response.json.provider, 'google')
    assert.equal(response.json.text, 'G:google hello')
    assert.equal(response.json.targetLanguage, 'zh-TW')
    const form = new URLSearchParams(String(calls[0].init.body))
    assert.equal(form.get('tl'), 'zh-TW')
    assert.equal(form.get('sl'), 'auto')
  })

  it('rejects a missing/blank text, an unknown target or an unknown provider with 400', async () => {
    for (const body of [
      { targetLanguage: 'zh-CN' },
      { text: '   ', targetLanguage: 'zh-CN' },
      { text: 'hello', targetLanguage: 'fr' },
      { text: 'hello', targetLanguage: 'zh-CN', provider: 'deepl' },
    ]) {
      const response = await request(base, '/v1/translate', { method: 'POST', body })
      assert.equal(response.status, 400, JSON.stringify(body))
      assert.equal(response.json.ok, false)
      assert.equal(response.json.error.code, 'invalid_request')
    }
    assert.equal(calls.length, 0)
  })

  it('rejects text longer than maxTextChars with 413 text_too_long', async () => {
    const response = await request(base, '/v1/translate', {
      method: 'POST',
      body: { text: 'x'.repeat(4001), targetLanguage: 'zh-CN' },
    })
    assert.equal(response.status, 413)
    assert.equal(response.json.error.code, 'text_too_long')
    assert.equal(calls.length, 0)
  })

  it('maps a provider HTTP failure to 502 translation_failed', async () => {
    mode = 'http500'
    const response = await request(base, '/v1/translate', {
      method: 'POST',
      body: { text: 'http failure', targetLanguage: 'zh-CN' },
    })
    assert.equal(response.status, 502)
    assert.equal(response.json.ok, false)
    assert.equal(response.json.error.code, 'translation_failed')
  })

  it('maps a stalled provider to 504 translation_timeout', async () => {
    mode = 'hang'
    const response = await request(base, '/v1/translate', {
      method: 'POST',
      body: { text: 'stall', targetLanguage: 'zh-CN' },
    })
    assert.equal(response.status, 504)
    assert.equal(response.json.error.code, 'translation_timeout')
  })

  it('answers 503 translation_unavailable when translation is disabled', async () => {
    const translate = gateway.api?.translate
    assert.ok(translate, 'the gateway must expose the translation service')
    translate.config.translateEnabled = false
    try {
      const response = await request(base, '/v1/translate', {
        method: 'POST',
        body: { text: 'disabled', targetLanguage: 'zh-CN' },
      })
      assert.equal(response.status, 503)
      assert.equal(response.json.error.code, 'translation_unavailable')
      assert.equal(calls.length, 0)
    } finally {
      translate.config.translateEnabled = true
    }
  })

  it('keeps the method contract: GET /v1/translate and POST /v1/translate/providers are 405', async () => {
    const get = await request(base, '/v1/translate')
    assert.equal(get.status, 405)
    assert.equal(get.json.error.code, 'invalid_request')
    const post = await request(base, '/v1/translate/providers', { method: 'POST', body: {} })
    assert.equal(post.status, 405)
  })
})
