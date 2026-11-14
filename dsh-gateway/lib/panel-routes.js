/**
 * Management API for the in-app gateway panel.
 *
 * The gateway no longer serves an administrative HTML page: the panel is a
 * Client plugin (`lib/client.js`) that registers into the Plugins page's
 * `plugins.row.config` slot, so it must be able to call the Host from the Web
 * UI's own origin. Those calls therefore land on the **DSH host web server**
 * (`ctx.webServer.register`), not on the gateway's LAN/public listeners, and
 * they inherit the host's browser authentication instead of a bespoke
 * loopback-only rule:
 *
 *   - `GET    /plugins/dsh-gateway/state`            panel snapshot
 *   - `GET    /plugins/dsh-gateway/pairing`          current pairing code
 *   - `POST   /plugins/dsh-gateway/pairing`          mint a pairing code
 *   - `DELETE /plugins/dsh-gateway/devices/{id}`     revoke one device
 *   - `PATCH  /plugins/dsh-gateway/config`           switch lanRequirePairing / mdns
 *
 * Every route repeats the host's own trust gate (`connection.requestRejection`,
 * which combines the Host/Origin fence with the browser login) before touching
 * anything. Without that service the routes fall back to a loopback/Host
 * self-check rather than running unguarded, so a Host without `connection`
 * still cannot be driven from another machine on the network.
 *
 * The response envelope matches `/v1`: `{ok:true, …}` or
 * `{ok:false, error:{code,message}}`.
 *
 * @module dsh-gateway/lib/panel-routes
 */

import { HttpError, readJsonBody, safeJson } from './util.js'

/** Route prefix owned by this plugin. */
export const PANEL_PREFIX = '/plugins/dsh-gateway'

/** Body cap for the small JSON bodies the panel sends. */
const PANEL_BODY_LIMIT = 64 * 1024

/** Match one loopback authority (`127.0.0.0/8`, `::1`, `localhost`). */
function isLoopbackAuthority(value) {
  const host = String(value ?? '').trim().toLowerCase()
  if (host === '') return false
  if (host === 'localhost' || host.endsWith('.localhost')) return true
  if (host === '[::1]' || host === '::1') return true
  const match = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})(?::\d+)?$/.exec(host)
  if (match === null) return false
  return match[1] === '127' && [match[2], match[3], match[4]].every((part) => Number(part) <= 255)
}

/**
 * Decide whether one panel request may proceed.
 *
 * `connection.requestRejection(request)` is the host's own answer (403 for an
 * untrusted Host/Origin, 401 for an unauthenticated browser) and is preferred
 * whenever the service exists. Without it, only a loopback authority with a
 * same-origin `Origin` (or none) is accepted.
 * @param {object|null} connection - `ctx.get('connection')`, may be undefined.
 * @param {import('node:http').IncomingMessage} req
 * @returns {number|undefined} rejection status, or undefined to proceed.
 */
export function panelRejection(connection, req) {
  const headers = req?.headers ?? {}
  if (connection !== null && connection !== undefined && typeof connection.requestRejection === 'function') {
    try {
      const rejection = connection.requestRejection({ headers })
      if (rejection !== undefined && rejection !== null && rejection !== false) {
        return typeof rejection === 'number' ? rejection : 403
      }
      return undefined
    } catch {
      // A fence that throws must fail closed, never open.
      return 403
    }
  }
  const host = headers.host
  if (!isLoopbackAuthority(host)) return 403
  const site = String(headers['sec-fetch-site'] ?? '').toLowerCase()
  if (site === 'cross-site') return 403
  const origin = headers.origin
  if (typeof origin === 'string' && origin !== '' && origin !== 'null') {
    let originHost = ''
    try {
      originHost = new URL(origin).host
    } catch {
      return 403
    }
    if (originHost.toLowerCase() !== String(host).toLowerCase()) return 403
  }
  return undefined
}

/**
 * Write one JSON reply for the panel.
 *
 * Deliberately NOT `writeJson` from `util.js`: that helper advertises
 * `access-control-allow-origin: *` for the APK's player API. These routes serve
 * the Web UI on its own origin, where CORS headers are unnecessary — and a
 * wildcard would tell every other origin that it may read an authenticated
 * reply. The Host/Origin guard below is the real fence; this keeps the reply
 * from inviting a cross-origin read in the first place.
 * @param {import('node:http').ServerResponse} res
 * @param {number} status
 * @param {unknown} body
 * @param {Record<string,string>} [headers]
 */
function sendJson(res, status, body, headers = {}) {
  if (res.headersSent || res.destroyed) return
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store',
    'x-content-type-options': 'nosniff',
    'referrer-policy': 'no-referrer',
    ...headers,
  })
  res.end(safeJson(body ?? null))
}

/** Write one `{ok:true, …}` payload. */
function ok(res, body) {
  sendJson(res, 200, { ok: true, ...body })
}

/** Write one error envelope (never leaks a stack or an internal message). */
function fail(res, status, code, message) {
  sendJson(res, status, { ok: false, error: { code, message } })
}

/** Map a thrown value onto the shared envelope. */
function failFrom(res, error) {
  if (error instanceof HttpError) {
    fail(res, error.status, error.code, error.message)
    return
  }
  fail(res, 500, 'internal_error', 'internal error')
}

/**
 * Register the panel routes on the host web server.
 *
 * A host `webServer` route is keyed by (kind, path) and rejects a second
 * registration of the same path, so the two `pairing` methods share one handler
 * with its own method table.
 * @param {object} deps
 * @param {object} deps.ctx - plugin context (needs `webServer`, optionally `connection`).
 * @param {() => import('./http.js').ApiServer|null} deps.api - late-bound gateway.
 * @param {{warn?:(message:string)=>void}} [deps.logger]
 * @returns {() => void} disposer removing every route.
 */
export function mountPanel({ ctx, api, logger }) {
  const disposers = []
  const connection = () => {
    try {
      return typeof ctx?.get === 'function' ? ctx.get('connection') ?? null : null
    } catch {
      return null
    }
  }

  /**
   * Wrap one path with the guard, method table and error envelope.
   * @param {string} path - exact path.
   * @param {Record<string, Function>} methods - handler per HTTP method.
   */
  const route = (path, methods) => {
    const allowed = Object.keys(methods)
    const dispose = ctx.webServer.register({
      kind: 'exact',
      path,
      handler: async (req, res) => {
        try {
          if (req.method === 'OPTIONS') {
            if (res.headersSent || res.destroyed) return
            res.writeHead(204, {
              'access-control-allow-methods': [...allowed, 'OPTIONS'].join(', '),
              'access-control-allow-headers': 'content-type',
              'cache-control': 'no-store',
            })
            res.end()
            return
          }
          const handler = methods[req.method]
          if (handler === undefined) {
            sendJson(res, 405, {
              ok: false,
              error: { code: 'invalid_request', message: `method ${req.method} is not allowed here` },
            }, { allow: allowed.join(', ') })
            return
          }
          const rejection = panelRejection(connection(), req)
          if (rejection !== undefined) {
            fail(res, rejection, rejection === 401 ? 'unauthenticated' : 'forbidden_source', 'this request is not authorized')
            return
          }
          const gateway = api()
          if (gateway === null) {
            fail(res, 503, 'dsh_unavailable', 'the gateway is not started yet')
            return
          }
          await handler(gateway, req, res)
        } catch (error) {
          if (!res.headersSent) {
            logger?.warn?.(`panel route ${path} failed: ${error?.message ?? error}`)
            failFrom(res, error)
            return
          }
          try {
            res.destroy()
          } catch {
            // ignore
          }
        }
      },
    })
    if (typeof dispose === 'function') disposers.push(dispose)
  }

  route(`${PANEL_PREFIX}/state`, {
    GET: async (gateway, req, res) => {
      ok(res, await gateway.panelState())
    },
  })

  route(`${PANEL_PREFIX}/pairing`, {
    GET: async (gateway, req, res) => {
      // `mode` picks which edge the QR should describe; the code itself is
      // untouched, so switching views never invalidates the other one.
      const url = new URL(String(req.url ?? '/'), 'http://localhost')
      ok(res, { pairing: await gateway.panelPairing(url.searchParams.get('mode') ?? '') })
    },
    POST: async (gateway, req, res) => {
      const body = await readJsonBody(req, PANEL_BODY_LIMIT)
      ok(res, { pairing: await gateway.panelPairingCreate(typeof body.mode === 'string' ? body.mode : '') })
    },
  })

  route(`${PANEL_PREFIX}/devices`, {
    DELETE: async (gateway, req, res) => {
      // The device id rides the query string: an exact route cannot hold a
      // path parameter, and a prefix route would have to re-implement parsing.
      const url = new URL(String(req.url ?? '/'), 'http://localhost')
      const deviceId = String(url.searchParams.get('deviceId') ?? '').trim()
      if (deviceId === '') throw new HttpError(400, 'invalid_request', 'deviceId is required')
      ok(res, { revoked: gateway.panelRevokeDevice(deviceId), deviceId })
    },
  })

  route(`${PANEL_PREFIX}/config`, {
    PATCH: async (gateway, req, res) => {
      const body = await readJsonBody(req, PANEL_BODY_LIMIT)
      ok(res, gateway.panelConfigPatch(body))
    },
  })

  return () => {
    while (disposers.length > 0) {
      const dispose = disposers.pop()
      try {
        dispose?.()
      } catch {
        // ignore
      }
    }
  }
}

export default { mountPanel, panelRejection, PANEL_PREFIX }
