/**
 * dsh-gateway configuration.
 *
 * The Cordis `Config` export is a schemastery schema so the DSH settings UI can
 * render the knobs; `resolveConfig` additionally normalizes a raw config object
 * for standalone use (tests, CLI smoke runs) where schemastery resolution is not
 * performed by the loader.
 *
 * @module dsh-gateway/lib/config
 */

import z from '@deepseek-ai/schemastery'
import { hostname } from 'node:os'

/** Defaults documented in docs/dsh-gateway-impl-plan.md §5. */
export const DEFAULTS = Object.freeze({
  httpPort: 7788,
  httpsPort: 7789,
  host: '0.0.0.0',
  mdns: true,
  lanRequirePairing: false,
  publicUrl: '',
  maxDevices: 4,
  approvalTtlMs: 1_800_000,
  ringSize: 2000,
  heartbeatMs: 20_000,
  idleTimeoutMs: 45_000,
  name: '',
  logFile: '',
  deviceTokenTtlDays: 0,
  pairingCodeTtlMs: 600_000,
})

/** Schemastery schema consumed by the DSH loader / settings UI. */
export const Config = z.object({
  httpPort: z.natural().default(DEFAULTS.httpPort).description('局域网 HTTP 端口，0 关闭'),
  httpsPort: z.natural().default(DEFAULTS.httpsPort).description('公网 HTTPS 端口，0 关闭'),
  host: z.string().default(DEFAULTS.host).description('监听地址（默认所有接口）'),
  mdns: z.boolean().default(DEFAULTS.mdns).description('是否广播 _dshgw._tcp'),
  lanRequirePairing: z.boolean().default(DEFAULTS.lanRequirePairing).description('局域网也要求设备令牌'),
  publicUrl: z.string().default(DEFAULTS.publicUrl).description('手动指定公网基址，如 https://[2408::1]:7789'),
  maxDevices: z.natural().default(DEFAULTS.maxDevices).description('同时在线设备上限'),
  approvalTtlMs: z.natural().default(DEFAULTS.approvalTtlMs).description('待审批在插件侧保留时长'),
  ringSize: z.natural().default(DEFAULTS.ringSize).description('事件 ring 保留条数'),
  heartbeatMs: z.natural().default(DEFAULTS.heartbeatMs).description('WS 服务端心跳间隔'),
  idleTimeoutMs: z.natural().default(DEFAULTS.idleTimeoutMs).description('WS 空闲判死阈值'),
  name: z.string().default(DEFAULTS.name).description('网关显示名（默认主机名）'),
  logFile: z.string().default(DEFAULTS.logFile).description('日志文件路径，空表示 <dataDir>/gateway.log'),
})

/** Clamp one numeric config value into a sane range. */
function intOf(value, fallback, min, max) {
  const parsed = typeof value === 'number' ? value : Number.parseInt(String(value ?? ''), 10)
  if (!Number.isFinite(parsed)) return fallback
  return Math.min(max, Math.max(min, Math.trunc(parsed)))
}

/**
 * Resolve a raw plugin config object into a fully populated, validated config.
 * @param {Record<string, unknown>|undefined|null} raw - config as handed by the loader or tests.
 * @returns {typeof DEFAULTS & {name: string, logFile: string}} normalized config.
 */
export function resolveConfig(raw) {
  const input = raw !== null && typeof raw === 'object' ? raw : {}
  const config = { ...DEFAULTS }
  config.httpPort = intOf(input.httpPort, DEFAULTS.httpPort, 0, 65_535)
  config.httpsPort = intOf(input.httpsPort, DEFAULTS.httpsPort, 0, 65_535)
  config.host = typeof input.host === 'string' && input.host.trim() !== '' ? input.host.trim() : DEFAULTS.host
  config.mdns = input.mdns === undefined ? DEFAULTS.mdns : Boolean(input.mdns)
  config.lanRequirePairing = input.lanRequirePairing === undefined
    ? DEFAULTS.lanRequirePairing
    : Boolean(input.lanRequirePairing)
  config.publicUrl = typeof input.publicUrl === 'string' ? input.publicUrl.trim() : ''
  config.maxDevices = intOf(input.maxDevices, DEFAULTS.maxDevices, 1, 64)
  config.approvalTtlMs = intOf(input.approvalTtlMs, DEFAULTS.approvalTtlMs, 1_000, 86_400_000)
  config.ringSize = intOf(input.ringSize, DEFAULTS.ringSize, 16, 100_000)
  config.heartbeatMs = intOf(input.heartbeatMs, DEFAULTS.heartbeatMs, 1_000, 120_000)
  config.idleTimeoutMs = intOf(input.idleTimeoutMs, DEFAULTS.idleTimeoutMs, 5_000, 600_000)
  config.name = typeof input.name === 'string' && input.name.trim() !== '' ? input.name.trim() : hostname()
  config.logFile = typeof input.logFile === 'string' ? input.logFile.trim() : ''
  config.deviceTokenTtlDays = intOf(input.deviceTokenTtlDays, DEFAULTS.deviceTokenTtlDays, 0, 3650)
  config.pairingCodeTtlMs = intOf(input.pairingCodeTtlMs, DEFAULTS.pairingCodeTtlMs, 30_000, 3_600_000)
  if (config.httpPort !== 0 && config.httpPort === config.httpsPort) config.httpsPort = 0
  return config
}

export default Config
