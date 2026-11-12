/**
 * dsh-gateway plugin entry.
 *
 * Wires the moving parts together:
 *   - {@link DshLoopbackClient}: talks to this process' own DSH over loopback
 *     HTTP + `/api/remote.mux` (cookie minted from the in-process launch token),
 *   - {@link EventHub}: normalizes DSH frames into `/v1` events, keeps seq /
 *     ring / pending interventions / device table and persists them,
 *   - {@link AuthService} + {@link ApiServer} + {@link StreamServer}: the LAN
 *     HTTP (`:7788`) and public HTTPS (`:7789`) edges,
 *   - {@link mountPanel}: the management API the in-app configuration page
 *     calls, registered on the DSH host web server,
 *   - {@link MdnsAdvertiser}: zero-config discovery for the APK.
 *
 * There is no administrative web page: pairing, the device list and the two
 * runtime switches live in the Web UI, in the plugin's own configuration page
 * (`lib/client.js`), so they inherit the host's theme, locale and login.
 *
 * Everything is defensive: this code runs inside the DSH process, so a failure
 * here must degrade the gateway, never the host.
 *
 * `createGateway` is exported separately from `apply` so tests and scripts can
 * drive the whole stack against a mock DSH without a Cordis fiber.
 *
 * @module dsh-gateway
 */

import { Config, resolveConfig } from './config.js'
import { EventHub } from './hub.js'
import { DshLoopbackClient } from './dsh.js'
import { AuthService } from './auth.js'
import { ApiServer } from './http.js'
import { StreamServer } from './ws.js'
import { MdnsAdvertiser } from './mdns.js'
import { ensureTlsMaterial } from './tls.js'
import { mountPanel } from './panel-routes.js'
import { watchTurnEnds } from './turns.js'
import {
  normalizeWaterfall,
  normalizeSessionRecord,
  normalizeControlFrame,
  normalizeWorkspaceFrame,
} from './normalize.js'
import { dataDir, makeLogger } from './util.js'

export const name = 'dsh-gateway'

/** Only the two public DSH services this plugin needs. */
export const inject = ['webServer', 'connection']

export { Config }

/**
 * Build the gateway stack without starting listeners.
 * @param {object} options
 * @param {import('@deepseek-ai/cordis').Context} options.ctx - plugin context (needs webServer + connection).
 * @param {Record<string, unknown>} [options.config] - raw config (normalized here).
 * @param {{info:Function,warn:Function,error:Function,debug?:Function}} [options.logger]
 * @param {string} [options.dir] - data directory (defaults to `$DSH_HOME/dsh-gateway`).
 */
export function createGateway(options) {
  const { ctx } = options
  const config = resolveConfig(options.config)
  const logger = options.logger ?? makeLogger(ctx?.logger, 'dsh-gateway')
  const dir = options.dir ?? dataDir()

  const hub = new EventHub({ config, logger, dir })
  const auth = new AuthService({ hub, config, logger, dir })

  /**
   * 每轮结束的事实源：订阅宿主 `session/event · turn/end`，广播 `turn.completed`。
   *
   * 注册得比监听器更早（`start()` 之前），这样插件加载完成到端口就绪之间的
   * 轮次结束也不会漏；`ctx.on` 在测试上下文里可能不存在，此时是一个空操作。
   */
  let stopTurnWatch = () => {}
  try {
    stopTurnWatch = watchTurnEnds({ ctx, hub, logger })
  } catch (error) {
    logger.warn(`turn watcher unavailable: ${error?.message ?? error}`)
  }

  /** @type {StreamServer|null} */
  let ws = null
  /** @type {ApiServer|null} */
  let api = null
  /** @type {MdnsAdvertiser|null} */
  let mdns = null
  /** @type {DshLoopbackClient|null} */
  let dsh = null
  /** @type {(() => void)|null} */
  let unmountPanel = null
  let disposed = false
  let started = null
  let ports = { lanPort: 0, publicPort: 0 }

  /**
   * Publish the panel's JSON routes on the DSH host web server.
   *
   * Failure is contained: a profile whose host has no `webServer.register`
   * (a bare test context, or a host without the Web carrier) keeps the gateway
   * data plane, and only the in-app page loses its backend.
   */
  function mountPanelRoutes() {
    if (unmountPanel !== null) return
    if (typeof ctx?.webServer?.register !== 'function') {
      logger.debug?.('host web server unavailable; the in-app plugin page has no backend')
      return
    }
    try {
      unmountPanel = mountPanel({ ctx, api: () => api, logger })
    } catch (error) {
      logger.warn(`in-app plugin panel did not mount: ${error?.message ?? error}`)
      unmountPanel = null
    }
  }

  /** Route one `$events` frame into hub state. */
  function handleEventsFrame(frame) {
    if (frame === null || typeof frame !== 'object') return
    switch (frame.type) {
      case 'ready':
        hub.setEventsClientId(frame.clientId)
        hub.setDshStatus({ connected: true, clientId: frame.clientId })
        break
      case 'emit':
        handleEmit(frame.event, Array.isArray(frame.args) ? frame.args : [])
        break
      case 'waterfall': {
        const next = normalizeWaterfall(frame)
        if (next !== null) {
          hub.addIntervention({
            eventId: next.eventId,
            sessionId: next.sessionId,
            kind: next.kind,
            data: next.data,
          })
        }
        break
      }
      case 'cancel':
        hub.removeIntervention(frame.eventId, 'cancelled', 'other')
        break
      default:
        break
    }
  }

  /** Map one host `emit` event (args array) into hub state. */
  function handleEmit(event, args) {
    if (typeof event !== 'string') return
    const [first, second] = args
    switch (event) {
      case 'api-session/status':
        hub.applySessionStatus(first === undefined ? undefined : String(first), Boolean(second))
        break
      case 'api-session/added':
        if (first !== null && typeof first === 'object') hub.applySessionSummary(first)
        break
      case 'api-session/removed':
        if (first !== undefined) hub.removeSession(String(first))
        break
      case 'api-session/activity':
        if (first !== undefined) hub.touchSession(String(first), Number(second) || Date.now())
        break
      case 'api-session/error':
        hub.publish('global', 'session.error', {
          sessionId: first === undefined ? '' : String(first),
          message: String(second ?? ''),
        })
        break
      default:
        hub.publish('global', 'event', { event, args: args.length === 1 ? args[0] : args })
        break
    }
  }

  /** Frame sink shared by every `session/follow` stream. */
  function handleSessionFrame(sessionId, frame) {
    try {
      const events = normalizeSessionRecord(sessionId, frame)
      for (const event of events) {
        hub.publish(event.topic ?? `session:${sessionId}`, event.kind, event.data, { id: event.id })
      }
    } catch (error) {
      logger.debug?.(`session frame dropped: ${error?.message ?? error}`)
    }
  }

  const dshHandlers = {
    onEvent: (frame) => {
      try {
        handleEventsFrame(frame)
      } catch (error) {
        logger.warn(`event frame dropped: ${error?.message ?? error}`)
      }
    },
    onControl: (frame) => {
      try {
        const next = normalizeControlFrame(frame)
        if (next === null) return
        if (next.kind === 'sessions.snapshot') hub.applySessions(next.data)
        else if (next.kind === 'session.summary') hub.applySessionSummary(next.data)
        else if (next.kind === 'session.removed') hub.removeSession(next.data?.sessionId)
        else if (next.kind === 'session.status') hub.applySessionStatus(next.data?.sessionId, next.data?.running)
      } catch (error) {
        logger.warn(`control frame dropped: ${error?.message ?? error}`)
      }
    },
    onWorkspace: (frame) => {
      try {
        const next = normalizeWorkspaceFrame(frame)
        if (next === null) return
        if (next.kind === 'workspaces') hub.applyWorkspaces(next.data ?? [])
        else if (next.kind === 'workspace.updated') hub.upsertWorkspace(next.data)
      } catch (error) {
        logger.warn(`workspace frame dropped: ${error?.message ?? error}`)
      }
    },
    onStatus: (status) => {
      hub.setDshStatus(status)
      if (status?.connected === false && status?.error) logger.warn(`DSH link down: ${status.error}`)
    },
    onSessionFrame: handleSessionFrame,
  }

  /**
   * Start listeners, TLS, mDNS and the DSH loopback link.
   * @returns {Promise<{lanPort:number,publicPort:number}>}
   */
  async function start() {
    if (started !== null) return started
    started = (async () => {
      await hub.load()
      await auth.load()
      if (disposed) return ports
      const tls = config.httpsPort === 0 ? null : await ensureTlsMaterial(dir, logger)
      if (disposed) return ports

      ws = new StreamServer({ config, logger, hub, auth })
      api = new ApiServer({
        ctx,
        config,
        logger,
        hub,
        auth,
        tls,
        dir,
        onUpgrade: (req, socket, head) => ws?.handleUpgrade(req, socket, head),
      })
      ports = await api.start()
      if (disposed) return ports

      dsh = new DshLoopbackClient({ ctx, config, logger, handlers: dshHandlers })
      ws.dsh = dsh
      api.dsh = dsh

      if (config.mdns) {
        mdns = new MdnsAdvertiser({
          name: config.name,
          port: ports.lanPort,
          pairPort: ports.publicPort,
          logger,
        })
        try {
          await mdns.start()
        } catch (error) {
          logger.warn(`mDNS disabled after failure: ${error?.message ?? error}`)
          mdns = null
        }
      }

      logger.info(`listening lan=${ports.lanPort} public=${ports.publicPort} mdns=${config.mdns}`)
      mountPanelRoutes()
      try {
        await dsh.start()
      } catch (error) {
        logger.warn(`DSH link unavailable at boot: ${error?.message ?? error}`)
      }
      return ports
    })()
    return started
  }

  /** Stop everything; safe to call multiple times. */
  async function stop() {
    if (disposed) return
    disposed = true
    try {
      stopTurnWatch?.()
    } catch {
      // ignore
    }
    stopTurnWatch = () => {}
    try {
      unmountPanel?.()
    } catch {
      // ignore
    }
    unmountPanel = null
    try {
      await mdns?.stop()
    } catch {
      // ignore
    }
    try {
      await api?.close()
    } catch {
      // ignore
    }
    try {
      await dsh?.stop()
    } catch {
      // ignore
    }
    try {
      await hub.flush()
    } catch {
      // ignore
    }
  }

  return {
    hub,
    auth,
    config,
    dir,
    logger,
    start,
    stop,
    get api() { return api },
    get ws() { return ws },
    get dsh() { return dsh },
    get mdns() { return mdns },
    get ports() { return ports },
    get disposed() { return disposed },
    /** Mount the host-side panel routes now (idempotent; `start` calls it too). */
    mountPanel: mountPanelRoutes,
  }
}

/**
 * Mount the gateway for one plugin fiber.
 * @param {import('@deepseek-ai/cordis').Context} ctx - plugin context.
 * @param {Record<string, unknown>} [rawConfig] - validated or raw config.
 */
export function apply(ctx, rawConfig) {
  const gateway = createGateway({ ctx, config: rawConfig ?? {} })
  if (typeof ctx?.effect === 'function') {
    ctx.effect(() => () => gateway.stop(), 'dsh-gateway: listeners')
  }
  gateway.start().catch((error) => gateway.logger.error(`gateway failed to start: ${error?.message ?? error}`))
  return undefined
}

export default { name, inject, Config, apply, createGateway }
