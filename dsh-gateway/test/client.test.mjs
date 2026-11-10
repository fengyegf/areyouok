/**
 * The browser half of the panel, driven without a browser.
 *
 * `lib/client.js` is a `window.__ModuleLoader__.load({id, factory})` bundle, so
 * a test can give it a stub `window`, a stub `React` and a stub plugin context
 * and then assert the parts that decide whether the page appears at all:
 *
 *   - the factory registers into `plugins.row.config` under the keys the
 *     Plugins page dispatches (both spellings of this package's name),
 *   - the dictionaries reach the locale service,
 *   - the `summary` view is a one-liner and the `page` view renders the loading
 *     state before its first snapshot arrives,
 *   - nothing throws when the Host is unreachable.
 *
 * This is a contract test, not a rendering test: it cannot tell whether the
 * page looks right, which stays a manual check in the running Web UI.
 *
 * @module dsh-gateway/test/client.test
 */

import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const CLIENT_PATH = fileURLToPath(new URL('../lib/client.js', import.meta.url))

/** Minimal element shape the assertions walk. */
function node(type, props, ...children) {
  return { type, props: props ?? {}, children: children.flat(Infinity).filter((child) => child !== undefined) }
}

/** A React stub good enough for a component that only builds elements. */
function makeReact() {
  const hooks = [];
  const effects = [];
  let cursor = 0;
  let dirty = false;
  return {
    Fragment: 'Fragment',
    createElement: node,
    useState(initial) {
      const index = cursor++;
      if (hooks[index] === undefined) hooks[index] = typeof initial === 'function' ? initial() : initial;
      return [hooks[index], (next) => {
        hooks[index] = typeof next === 'function' ? next(hooks[index]) : next;
        dirty = true;
      }];
    },
    useCallback(fn) {
      cursor += 1;
      return fn;
    },
    useMemo(fn) {
      cursor += 1;
      return fn();
    },
    useRef(initial) {
      const index = cursor++;
      if (hooks[index] === undefined) hooks[index] = { current: initial ?? null };
      return hooks[index];
    },
    useEffect(fn) {
      const index = cursor++;
      effects[index] = fn;
    },
    /* Test hook: drop all hook state, as if the component were mounting fresh. */
    __reset() {
      cursor = 0;
      hooks.length = 0;
      effects.length = 0;
    },
    /**
     * Render to a fixed point: run the effects, then re-render while a state
     * setter fired, awaiting a microtask between passes so a resolved `fetch`
     * can land.
     * @param {Function} component
     * @param {object} props
     * @returns {Promise<any>} the final element tree.
     */
    async __render(component, props) {
      let tree = null;
      const cleanups = [];
      for (let pass = 0; pass < 12; pass += 1) {
        cursor = 0;
        dirty = false;
        tree = expand(component(props));
        if (pass === 0) {
          for (const effect of effects) {
            if (typeof effect === 'function') {
              const cleanup = effect();
              if (typeof cleanup === 'function') cleanups.push(cleanup);
            }
          }
        }
        await new Promise((resolve) => setTimeout(resolve, 0));
        if (!dirty) break;
      }
      // A mounted page holds a poll timer: release it, like unmounting would.
      this.__cleanups = cleanups;
      return tree;
    },
    /** Run every effect cleanup collected by the last `__render`. */
    __dispose() {
      for (const cleanup of this.__cleanups ?? []) {
        try {
          cleanup();
        } catch {
          // ignore
        }
      }
      this.__cleanups = [];
    },
  };
}

/** Collect every string inside a rendered tree. */
function textOf(value) {
  if (value === null || value === undefined) return ''
  if (typeof value === 'string' || typeof value === 'number') return String(value)
  if (Array.isArray(value)) return value.map(textOf).join('')
  return textOf(value.children)
}

/**
 * Expand child function components into elements.
 *
 * Real React resolves `h(QrCode, …)` while rendering the parent; this stub only
 * records the element, so every component the page composes has to be invoked
 * here before the tree can be inspected. Hook slots are consumed in the same
 * order React would: after the parent's own hooks, depth first.
 */
function expand(value) {
  if (value === null || value === undefined || typeof value !== 'object') return value
  if (Array.isArray(value)) return value.map(expand)
  if (typeof value.type === 'function') return expand(value.type(value.props))
  return Object.assign({}, value, { children: value.children.map(expand) })
}

/** Collect every element of one tag inside a rendered tree. */
function findAll(value, tag) {
  if (value === null || value === undefined || typeof value === 'string') return []
  if (Array.isArray(value)) return value.flatMap((child) => findAll(child, tag))
  const own = value.type === tag ? [value] : []
  return own.concat(findAll(value.children, tag))
}

/** A 3×3 matrix with three dark runs: `111 / 001 / 100`. */
const QR_FIXTURE = {
  size: 3,
  data: Buffer.from([1, 1, 1, 0, 0, 1, 1, 0, 0]).toString('base64'),
};

/** The snapshot the fake Host serves. */
const STATE_FIXTURE = {
  ok: true,
  name: 'dev-box',
  version: '0.2.0',
  fingerprint: 'ab'.repeat(32),
  fingerprintPretty: 'AB:AB',
  ports: { lan: 7788, public: 7789 },
  mdns: { enabled: true, active: true },
  dsh: { connected: true, clientId: 'c1' },
  devices: [
    { deviceId: 'dev-1', name: 'Pixel', remoteAddress: '192.168.1.9', lastSeen: 1_700_000_000_000 },
  ],
  pairing: {
    code: 'code-123',
    expiresAt: 1_700_000_600_000,
    lanUrl: 'http://192.168.1.5:7788',
    publicUrl: null,
    urls: ['http://192.168.1.5:7788'],
    uri: 'dshgw://pair?url=http%3A%2F%2F192.168.1.5%3A7788&code=code-123&n=dev-box',
    qr: QR_FIXTURE,
  },
  config: { httpPort: 7788, httpsPort: 7789, lanRequirePairing: false, mdns: true, maxDevices: 4, publicUrl: '' },
  publicUrls: [],
  lanUrls: ['http://192.168.1.5:7788'],
  uptimeMs: 3_725_000,
  lastError: '',
  serverTime: Date.now(),
};

/** The same snapshot before any pairing code exists (the empty state). */
const STATE_NO_PAIRING = Object.assign({}, STATE_FIXTURE, { pairing: null });

/** Replace `fetch` for one test and record what the page asked for. */
function withFetch(handler) {
  const calls = [];
  const previous = globalThis.fetch;
  globalThis.fetch = async (url, options) => {
    calls.push({ url: String(url), options: options ?? {} });
    return handler(String(url), options ?? {});
  };
  return {
    calls,
    restore() {
      globalThis.fetch = previous;
    },
  };
}

/** A `{ok:true, …}` JSON response. */
function jsonResponse(body, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    async json() {
      return body;
    },
  };
}

/** Load the bundle with stubs and return its exported module plus the recorders. */
function loadClient() {
  /** @type {any} */
  let loaded = null
  const React = makeReact()
  const dictionaries = []
  const registrations = []
  const yielded = []
  const effects = []
  const injects = []
  const slots = {
    inject(slot, callback) {
      injects.push(slot)
      const generator = callback();
      for (const entry of generator) {
        if (entry !== undefined) yielded.push(entry)
      }
      return () => {};
    },
    register(options, component) {
      registrations.push({ options, component })
      // The real service returns a disposer; a `function*` injection yields it.
      return () => {};
    },
  }
  const locale = {
    register(namespace, dicts) {
      dictionaries.push({ namespace, dicts })
      return () => {};
    },
  }
  const window = {
    __ModuleLoader__: {
      load(definition) {
        loaded = definition
      },
    },
  }
  const source = readFileSync(CLIENT_PATH, 'utf8')
  // The bundle is an IIFE over `window`: evaluating it is exactly what the
  // Web module graph does, minus the network.
  // eslint-disable-next-line no-new-func
  new Function('window', 'console', source)(window, console)
  assert.ok(loaded !== null, 'the bundle must call __ModuleLoader__.load')
  const module = loaded.factory((id) => {
    assert.equal(id, 'react')
    return React
  })
  const ctx = {
    effect(factory, label) {
      effects.push({ label, dispose: factory() })
      return () => {};
    },
    locale: Object.assign(locale, {
      bind() {
        return (key, params) => {
          const dict = dictionaries.length > 0 ? dictionaries[dictionaries.length - 1].dicts.zh : {};
          const template = dict[key] ?? key;
          return params === undefined
            ? template
            : String(template).replace(/\{(\w+)\}/g, (whole, name) => (name in params ? String(params[name]) : whole));
        };
      },
    }),
    slots,
  }
  return { definition: loaded, module, ctx, registrations, yielded, dictionaries, effects, injects, React }
}

describe('panel client bundle', () => {
  it('registers under the plugin id the Loader rows use', () => {
    const { definition } = loadClient()
    assert.equal(definition.id, 'dsh-gateway')
  })

  it('injects only the slots and locale services', () => {
    const { module } = loadClient()
    assert.deepEqual(module.inject, ['slots', 'locale'])
  })

  it('registers a Settings page plus its nav glyph and the plugin row page', () => {
    const { module, ctx, registrations, yielded, dictionaries, effects, injects } = loadClient()
    module.apply(ctx)
    // The Settings dialog is the destination; the plugin row keeps its page.
    assert.deepEqual(injects, ['settings.section', 'plugins.row.config'])
    const section = registrations.filter((entry) => entry.options.name === 'settings.section')
    assert.equal(section.length, 1)
    assert.equal(section[0].options.id, 'dsh-gateway')
    assert.equal(section[0].options.order, 35)
    // `label` is a thunk so the nav row follows a locale change without
    // re-registering; the shell re-reads it on every projection.
    assert.equal(typeof section[0].options.label, 'function')
    assert.equal(typeof section[0].options.label(), 'string')
    const icon = registrations.filter((entry) => entry.options.name === 'settings.section.icon')
    assert.equal(icon.length, 1)
    // The shell renders the glyph with `{ only: row.id }`, so the two ids must
    // match or the nav row ships with an empty cell.
    assert.equal(icon[0].options.id, section[0].options.id)
    const rowKeys = registrations
      .filter((entry) => entry.options.name === 'plugins.row.config')
      .map((entry) => entry.options.key)
      .sort()
    assert.deepEqual(rowKeys, ['dsh-gateway#dsh-gateway', 'include:dsh-gateway#include:dsh-gateway'])
    assert.equal(registrations.length, 4)
    for (const entry of registrations) {
      assert.equal(entry.options.locale, 'dshGateway')
      assert.equal(typeof entry.component, 'function')
    }
    // `function*` injection: the disposer is yielded, one per registration.
    assert.equal(yielded.length, 4)
    assert.ok(yielded.every((entry) => typeof entry === 'function'))
    assert.equal(dictionaries.length, 1)
    assert.equal(dictionaries[0].namespace, 'dshGateway')
    assert.equal(typeof dictionaries[0].dicts.zh['meta.title'], 'string')
    assert.equal(typeof dictionaries[0].dicts.en['meta.title'], 'string')
    // The stylesheet is an effect so a reload replaces it instead of stacking.
    assert.equal(effects.length, 2)
    assert.ok(effects.every((entry) => typeof entry.dispose === 'function'))
  })

  it('renders a one-liner for the row summary and the loading state on the page', async () => {
    const { module, ctx, registrations, React } = loadClient()
    module.apply(ctx)
    const component = registrations[0].component
    const t = (key) => key

    React.__reset()
    const summary = component({ view: 'summary', t })
    assert.match(textOf(summary), /meta\.description/)

    /* Before the first snapshot lands the page shows its loading copy; the
     * first poll is an effect, so it fires on mount and is asserted after. */
    const fetchStub = withFetch(() => new Promise(() => {}))
    try {
      React.__reset()
      const page = component({ view: 'page', t })
      assert.match(textOf(page), /loading/)
      assert.equal(fetchStub.calls.length, 0)

      await React.__render(component, { view: 'page', t })
      assert.equal(fetchStub.calls.length, 1)
      assert.equal(fetchStub.calls[0].url, '/plugins/dsh-gateway/state')
      assert.equal(fetchStub.calls[0].options.credentials, 'same-origin')
    } finally {
      fetchStub.restore()
      React.__dispose()
    }
  })

  it('renders the snapshot: status cards, QR runs, device row and switches', async () => {
    const { module, ctx, registrations, React } = loadClient()
    module.apply(ctx)
    const component = registrations[0].component
    const fetchStub = withFetch((url) => {
      if (url === '/plugins/dsh-gateway/state') return jsonResponse(STATE_FIXTURE)
      return jsonResponse({ ok: false, error: { code: 'not_found', message: 'no route' } }, 404)
    })
    try {
      React.__reset()
      const tree = await React.__render(component, { view: 'page', t: (key, params) => (params === undefined ? key : key + JSON.stringify(params)) })
      const text = textOf(tree)
      // Status cards.
      assert.match(text, /status\.dsh/)
      assert.match(text, /status\.on/)
      assert.match(text, /0\.0\.0\.0:7788/)
      assert.match(text, /0\.0\.0\.0:7789/)
      assert.match(text, /status\.advertising/)
      // Device row.
      assert.match(text, /Pixel/)
      assert.match(text, /dev-1/)
      // Pairing payload.
      assert.match(text, /code-123/)
      assert.match(text, /dshgw:\/\/pair/)
      // The switch reflects the snapshot, not a local guess.
      const switches = findAll(tree, 'input').filter((input) => input.props.type === 'checkbox')
      assert.equal(switches.length, 2)
      assert.equal(switches[0].props.checked, false)
      assert.equal(switches[1].props.checked, true)
      // The QR is drawn from the matrix: three dark runs, no <img>.
      const qrSvg = findAll(tree, 'svg').filter((svg) => svg.props.viewBox === '0 0 3 3')[0]
      assert.ok(qrSvg !== undefined, 'the QR svg should carry the matrix viewBox')
      const rects = findAll(qrSvg, 'rect')
      assert.equal(rects.length, 3)
      assert.deepEqual(rects.map((rect) => [rect.props.x, rect.props.y, rect.props.width]), [[0, 0, 3], [2, 1, 1], [0, 2, 1]])
    } finally {
      fetchStub.restore()
      React.__dispose()
    }
  })

  it('renders the snapshot as a collapsible card holding every section', async () => {
    const { module, ctx, registrations, React } = loadClient()
    module.apply(ctx)
    const component = registrations[0].component
    const fetchStub = withFetch((url) => {
      if (url === '/plugins/dsh-gateway/state') return jsonResponse(STATE_FIXTURE)
      return jsonResponse({ ok: false, error: { code: 'not_found', message: 'no route' } }, 404)
    })
    try {
      React.__reset()
      const tree = await React.__render(component, { view: 'page', t: (key) => key })
      // The header is a real button announcing its state, not a clickable div.
      const header = findAll(tree, 'button').filter((button) => button.props['aria-expanded'] !== undefined)[0]
      assert.ok(header !== undefined, 'the card header should be a button with aria-expanded')
      assert.equal(header.props['aria-expanded'], true)
      // One scroll, four sections, nothing hidden behind a tab strip.
      assert.equal(findAll(tree, 'div').filter((div) => div.props.role === 'tablist').length, 0)
      assert.equal(findAll(tree, 'div').filter((div) => div.props.role === 'tabpanel').length, 0)
      const headings = findAll(tree, 'h4').map((heading) => textOf(heading))
      assert.deepEqual(headings, ['section.status', 'section.pairing', 'section.devices1', 'section.config'])
      // Every section's content is present at once: the device row and the
      // switches are on screen without a click.
      assert.match(textOf(tree), /Pixel/)
      const switches = findAll(tree, 'input').filter((input) => input.props.type === 'checkbox')
      assert.equal(switches.length, 2)
      // The icon is inline SVG: an <img> would be at the mercy of `img-src`.
      assert.equal(findAll(tree, 'img').length, 0)
    } finally {
      fetchStub.restore()
      React.__dispose()
    }
  })

  it('marks the QR area before a code exists, on the theme surface', async () => {
    const { module, ctx, registrations, React } = loadClient()
    module.apply(ctx)
    const component = registrations[0].component
    const fetchStub = withFetch((url) => {
      if (url === '/plugins/dsh-gateway/state') return jsonResponse(STATE_NO_PAIRING)
      return jsonResponse({ ok: false, error: { code: 'not_found', message: 'no route' } }, 404)
    })
    try {
      React.__reset()
      const tree = await React.__render(component, { surface: 'settings', t: (key) => key })
      // The empty slot keeps the 180px footprint so generating a code does not
      // shift the layout, and it is marked `-qrEmpty` so the stylesheet can
      // paint the panel surface + dashed edge instead of the QR's white plate
      // (a white square on the light theme is invisible — the reported bug).
      const slot = findAll(tree, 'div').filter((div) => String(div.props.className).includes('-qrEmpty'))[0]
      assert.ok(slot !== undefined, 'the empty QR area needs its own marker class')
      assert.equal(String(slot.props.className).includes('-qr '), true, 'the slot keeps the -qr footprint class')
      // It says what goes there, and the label is localized rather than a bare box.
      assert.match(textOf(slot), /pairing\.placeholder/)
      // A decorative ghost only: nothing that could be mistaken for a real code.
      assert.equal(findAll(slot, 'svg').length, 1)
      assert.equal(slot.props.role, 'img')
      assert.equal(slot.props['aria-label'], 'pairing.placeholder')
      // No matrix runs, because there is no code.
      assert.equal(findAll(slot, 'rect').length > 0, true, 'the ghost is drawn from rects')
    } finally {
      fetchStub.restore()
      React.__dispose()
    }
  })

  it('renders the Settings surface without a card header, all sections inline', async () => {
    const { module, ctx, registrations, React } = loadClient()
    module.apply(ctx)
    const component = registrations[0].component
    const fetchStub = withFetch((url) => {
      if (url === '/plugins/dsh-gateway/state') return jsonResponse(STATE_FIXTURE)
      return jsonResponse({ ok: false, error: { code: 'not_found', message: 'no route' } }, 404)
    })
    try {
      React.__reset()
      const tree = await React.__render(component, { surface: 'settings', t: (key) => key })
      // The shell draws the dialog title and close control, so this page must
      // not repeat them: no card header button, and therefore no aria-expanded.
      assert.equal(findAll(tree, 'button').filter((b) => b.props['aria-expanded'] !== undefined).length, 0)
      // The body is up immediately — the reader opened Settings on purpose.
      assert.match(textOf(tree), /status\.dsh/)
      // Same single scroll: no tab strip here either.
      assert.equal(findAll(tree, 'div').filter((div) => div.props.role === 'tablist').length, 0)
      const headings = findAll(tree, 'h4').map((heading) => textOf(heading))
      assert.deepEqual(headings, ['section.status', 'section.pairing', 'section.devices1', 'section.config'])
    } finally {
      fetchStub.restore()
      React.__dispose()
    }
  })

  it('shows the host error sentence when the snapshot cannot be read', async () => {
    const { module, ctx, registrations, React } = loadClient()
    module.apply(ctx)
    const component = registrations[0].component
    const fetchStub = withFetch(() => jsonResponse({ ok: false, error: { code: 'forbidden_source', message: 'this request is not authorized' } }, 403))
    try {
      React.__reset()
      const tree = await React.__render(component, { view: 'page', t: (key, params) => (params === undefined ? key : key + ' ' + params.message) })
      assert.match(textOf(tree), /this request is not authorized/)
    } finally {
      fetchStub.restore()
      React.__dispose()
    }
  })

  it('never throws when fetch is missing entirely', async () => {
    const { module, ctx, registrations, React } = loadClient()
    module.apply(ctx)
    const component = registrations[0].component
    const previous = globalThis.fetch
    delete globalThis.fetch
    try {
      React.__reset()
      // The very first render is the loading state; the failed poll that
      // follows must degrade to an error line, not to a crashed slot entry.
      const first = component({ view: 'page', t: (key) => key })
      assert.match(textOf(first), /loading/)
      const settled = await React.__render(component, { view: 'page', t: (key) => key })
      assert.match(textOf(settled), /error\.state/)
    } finally {
      globalThis.fetch = previous
      React.__dispose()
    }
  })
})
