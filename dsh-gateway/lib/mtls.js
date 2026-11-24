/**
 * 公网访问的客户端 PKI（双向 TLS 认证，mTLS）。
 *
 * 每个插件实例（= 每个 `dataDir`）拥有一套独立的自签 CA：
 *
 *   <dataDir>/mtls/ca-key.pem      CA 私钥（PKCS#8 PEM，0600）
 *   <dataDir>/mtls/ca-cert.pem     CA 证书（自签，basicConstraints CA:TRUE，0644）
 *   <dataDir>/mtls/clients.json    注册表（已签发 + 撤销表，原子写，0600）
 *   <dataDir>/mtls/keys/<serial>.pem  面板导出证书用私钥（明文 PEM 0600，仅管理员数据目录）
 *
 * 设计要点（契约 docs/dsh-gateway-mtls.md §3/§4.1）：
 *   - 私钥永不出网关的**明文**通道：局域网领证走 CSR（APK 生成密钥对），网关只签发；
 *   - 只有面板“导出证书文件”才由网关生成私钥，导出格式为 PKCS#12（`.p12`，PBE 3DES），
 *     密码由用户在面板输入；
 *   - 注册表坏文件自愈：备份成 `clients.json.bad-<ts>` 后重建，绝不让网关起不来；
 *   - 注册表写入串行化（同一实例内一次只有一个签发/撤销落盘），避免并发领证互相覆盖；
 *   - 撤销检查是 O(1) 的内存 `Set<serial>`，握手成功后立刻 `socket.destroy()`；
 *   - 续期语义：`issueFromCsr` 若带非空 `deviceId`，先撤销该设备名下“未撤销、未过期”的
 *     LAN 领证证书，再签发新证书 —— 一个设备同一时刻只有一张有效证书，续期/重装不会堆证书，
 *     “撤销后无法连接”的语义清晰。`issuedVia='panel-export'` 或 `deviceId=null` 的证书
 *     不参与这条自动撤销。
 *
 * 失败策略与 `tls.js` 一致：PKI 是可选基础设施，任何异常都由调用方（index.js）降级为
 * “公网边不启动、LAN 边照常”，绝不影响宿主进程。
 *
 * @module dsh-gateway/lib/mtls
 */

import {
  X509Certificate,
  createHash,
  createPublicKey,
  generateKeyPairSync,
  randomBytes,
} from 'node:crypto'
import { copyFileSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { createSecureContext } from 'node:tls'
import forge from 'node-forge'
import {
  HttpError,
  ensureDir,
  fileExists,
  sha256Bytes,
  writeFileAtomic,
} from './util.js'
import { fingerprintOfPem } from './tls.js'

/** CA 证书有效期（天）：与网关自签 TLS 证书一致，10 年。 */
const CA_CERT_DAYS = 3650

/** RSA 密钥长度（契约固定 RSA-2048 + SHA-256）。 */
const KEY_SIZE = 2048

/** `clientCertDays` 缺省值（契约 §6）。 */
const DEFAULT_CLIENT_CERT_DAYS = 3650

/** `clientCertDays` 上限（契约 §6：1..3650）。 */
const MAX_CLIENT_CERT_DAYS = 3650

/** 时钟偏差容忍：证书 notBefore 提前 5 分钟，避免设备与 PC 时间不同步导致握手失败。 */
const CLOCK_SKEW_MS = 5 * 60_000

/** 注册表格式版本。 */
const REGISTRY_VERSION = 1

/** deviceId 是客户端生成的，保持可打印且有界（与 auth.js 的同名规则一致）。 */
const DEVICE_ID_PATTERN = /^[A-Za-z0-9._:-]{1,128}$/

/** 序列号是十六进制大写（与 node X509Certificate.serialNumber 一致）。 */
const SERIAL_PATTERN = /^[0-9A-F]{2,64}$/

/** 允许的签发来源。 */
const ISSUED_VIA = new Set(['lan-enroll', 'panel-export'])

/** 去掉控制字符并截断展示名。 */
function cleanName(value, fallback = '') {
  if (typeof value !== 'string') return fallback
  // eslint-disable-next-line no-control-regex
  const cleaned = value.replace(/[\u0000-\u001f\u007f]/g, '').trim()
  return cleaned === '' ? fallback : cleaned.slice(0, 64)
}

/**
 * X.509 主体里的 CN 只能用可打印 ASCII。
 *
 * node-forge 收到非 ASCII 的 `commonName` 时会编出不合法的 DER（PEM 的 base64
 * 长度不是 4 的倍数、PKCS#12 的 MAC 也随之失效），Android/JDK 都无法解析 ——
 * 表现就是「导出 .p12 后在手机上导入失败（mac invalid）」。因此证书主体只用
 * ASCII 标识；用户输入的中文展示名保留在注册表与面板里，不进证书。
 * @param {unknown} value 原始展示名
 * @param {string} [fallback] 全被过滤掉时使用的 ASCII 兜底
 * @returns {string} 至少 1 个字符、至多 64 个字符的 ASCII 名
 */
function asciiCommonName(value, fallback = '') {
  const text = typeof value === 'string' ? value : ''
  const ascii = text
    // 非 ASCII（中文、emoji）换成空格，避免把两个字粘在一起
    .replace(/[^\x20-\x7e]/g, ' ')
    // 去掉 X.509 里需要转义或本身非法的字符
    .replace(/[^A-Za-z0-9 ._@:/-]/g, '')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 64)
  if (ascii !== '') return ascii
  const safeFallback = String(fallback ?? '')
    .replace(/[^\x20-\x7e]/g, '')
    .trim()
    .slice(0, 64)
  return safeFallback !== '' ? safeFallback : 'dsh-client'
}

/** 一个 PEM 证书是否能被标准 X.509 解析器读过（forge 的编码缺陷会让它失败）。 */
function isParsableCertificate(pem) {
  if (typeof pem !== 'string' || pem.trim() === '') return false
  try {
    // eslint-disable-next-line no-new
    new X509Certificate(pem)
    return true
  } catch {
    return false
  }
}

/** 规范序列号：去空白、转大写。 */
function normalizeSerial(value) {
  return String(value ?? '').trim().toUpperCase()
}

/** 读一个 UTF-8 文本文件，失败返回 null。 */
function readText(file) {
  try {
    if (!fileExists(file)) return null
    return readFileSync(file, 'utf8')
  } catch {
    return null
  }
}

/**
 * 生成一个安全的证书序列号。
 *
 * 首字节强制 0x40..0x7f：DER INTEGER 为正数且不带前导 0x00，Node 的
 * `X509Certificate.serialNumber` / `peerCertificate.serialNumber` 与我们落库的
 * 十六进制大写字符串逐字符一致（契约 §3 要求）。
 */
function newSerialHex() {
  const bytes = randomBytes(16)
  bytes[0] = (bytes[0] & 0x7f) | 0x40
  return bytes.toString('hex').toUpperCase()
}

/** 把天数配置收敛到 1..3650。 */
function clientCertDaysOf(config) {
  const raw = Number(config?.clientCertDays)
  if (!Number.isFinite(raw)) return DEFAULT_CLIENT_CERT_DAYS
  return Math.min(MAX_CLIENT_CERT_DAYS, Math.max(1, Math.trunc(raw)))
}

/** 生成一对 RSA-2048 密钥（node:crypto 原生实现，比纯 JS 快一个数量级）。 */
function generateRsaKeyPair() {
  const pair = generateKeyPairSync('rsa', {
    modulusLength: KEY_SIZE,
    publicKeyEncoding: { type: 'pkcs1', format: 'pem' },
    privateKeyEncoding: { type: 'pkcs8', format: 'pem' },
  })
  return { publicKeyPem: pair.publicKey, privateKeyPem: pair.privateKey }
}

/** 证书 DER 字节（Buffer）。 */
function certificateDer(cert) {
  const bytes = forge.asn1.toDer(forge.pki.certificateToAsn1(cert)).getBytes()
  return Buffer.from(bytes, 'binary')
}

/** 一张 forge 证书的 SHA-256 指纹（小写 hex）。 */
function certificateFingerprint(cert) {
  return sha256Bytes(certificateDer(cert))
}

/**
 * 一个数据目录对应的客户端 PKI 实例。
 *
 * 公开方法名固定（契约 §4.1），其它模块与测试按此调用：
 * `load`、`tlsOptions`、`caCertificate`、`caFingerprint`、`issueFromCsr`、
 * `issueForExport`、`exportPkcs12`、`list`、`revoke`、`revokeByDevice`、
 * `isRevokedSerial`、`peerOf`、`close`。
 */
export class ClientPki {
  /**
   * @param {object} options
   * @param {string} options.dir - 网关数据目录（`<dataDir>/mtls` 建在其下）。
   * @param {Record<string, any>} [options.config] - 归一化配置（读 `clientCertDays`）。
   * @param {{info?:Function,warn?:Function,error?:Function,debug?:Function}} [options.logger]
   */
  constructor({ dir, config, logger }) {
    this.dir = typeof dir === 'string' && dir !== '' ? dir : process.cwd()
    this.config = config ?? {}
    this.logger = logger ?? {}
    this.mtlsDir = join(this.dir, 'mtls')
    this.keysDir = join(this.mtlsDir, 'keys')
    this.caCertPath = join(this.mtlsDir, 'ca-cert.pem')
    this.caKeyPath = join(this.mtlsDir, 'ca-key.pem')
    this.registryPath = join(this.mtlsDir, 'clients.json')

    /** CA 证书 PEM。 */
    this.caPem = ''
    /** CA 指纹（sha256 hex 小写）。 */
    this.caSha256 = ''
    /** @type {import('node-forge').pki.Certificate|null} */
    this.caCert = null
    /** @type {import('node-forge').pki.rsa.PrivateKey|null} */
    this.caKey = null
    /** 已签发证书条目（注册表内存镜像；list() 负责倒序）。 */
    this.certs = []
    /** O(1) 撤销判定。 */
    this.revokedSerials = new Set()
    /** 串行化落盘的 promise 链。 */
    this.writeChain = Promise.resolve()
    /** 串行化签发/撤销的事件队列（同一时刻只允许一个签发流程改注册表）。 */
    this.mutationChain = Promise.resolve()
    this.closed = false
  }

  /**
   * 加载或生成 CA + 注册表；幂等。
   * 坏材料自愈重建并 warn（契约 §4.1）。
   * @returns {Promise<ClientPki>}
   */
  async load() {
    ensureDir(this.mtlsDir)
    ensureDir(this.keysDir)
    this.#loadOrCreateCa()
    this.#loadRegistry()
    this.logger.info?.(
      'client PKI ready: ca=' + this.caSha256.slice(0, 16) + '… certs=' + this.certs.length +
      ' revoked=' + this.revokedSerials.size,
    )
    return this
  }

  /** 公网 HTTPS 监听器需要透传给 `createHttpsServer` 的 mTLS 选项（契约 §4.2）。 */
  tlsOptions() {
    return {
      requestCert: true,
      rejectUnauthorized: true,
      ca: [this.caPem],
      minVersion: 'TLSv1.2',
    }
  }

  /** CA 证书 PEM（字符串）。 */
  caCertificate() {
    return this.caPem
  }

  /** CA 的 SHA-256 指纹（hex 小写）。 */
  caFingerprint() {
    return this.caSha256
  }

  /**
   * 校验 CSR 签名 → 签发客户端证书 → 落库。
   *
   * 续期语义：deviceId 非空时，先撤销该设备名下“未撤销、未过期”的 LAN 领证证书
   * （issuedVia='lan-enroll'），再签发新证书；issuedVia='panel-export' 或
   * deviceId=null 的证书不参与自动撤销。
   *
   * @param {{csr?:string, deviceId?:string|null, deviceName?:string}} [input]
   * @returns {Promise<{certificate:string, serial:string, notAfter:number, deviceId:string|null,
   *   name:string, fingerprint:string, caCertificate:string, caFingerprint:string}>}
   * @throws {HttpError} 400 invalid_request（CSR 缺失/非法/签名不符）。
   */
  async issueFromCsr({ csr, deviceId, deviceName } = {}) {
    const publicKey = this.#verifyCsr(csr)
    const id = this.#deviceIdOf(deviceId)
    const name = cleanName(deviceName, id ?? 'dsh-client')
    return this.#enqueue(async () => {
      const now = Date.now()
      const autoRevoked = this.#revokeCertificatesOfDevice(id, now)
      const signed = this.#signCertificate({ publicKey, name, deviceId: id, issuedVia: 'lan-enroll', now })
      this.certs.push(signed.entry)
      this.#reindex()
      await this.#persist()
      if (autoRevoked > 0) {
        this.logger.info?.('device ' + id + ' renewed: ' + autoRevoked + ' previous certificate(s) revoked')
      }
      this.logger.info?.('issued client certificate serial=' + signed.entry.serial + ' name=' + name + ' via=lan-enroll')
      return this.#publicIssued(signed.entry)
    })
  }

  /**
   * 生成 RSA-2048 密钥对 + 证书 + PKCS#12（面板“导出证书文件”用）。
   *
   * 私钥以明文 PEM 0600 落在 <dataDir>/mtls/keys/<serial>.pem，以便重复导出；
   * p12 才用用户密码加密（PBE 3DES，JVM/Android 实测可读）。
   *
   * @param {{name?:string, deviceId?:string|null, password?:string}} [input]
   * @returns {Promise<{certificate:string, serial:string, notAfter:number, deviceId:string|null,
   *   name:string, fingerprint:string, p12:Buffer, caCertificate:string, caFingerprint:string}>}
   * @throws {HttpError} 400 invalid_request（password 为空）。
   */
  async issueForExport({ name, deviceId, password } = {}) {
    this.#assertPassword(password)
    const id = this.#deviceIdOf(deviceId)
    const display = cleanName(name, id ?? 'dsh-client')
    const pair = generateRsaKeyPair()
    const publicKey = forge.pki.publicKeyFromPem(pair.publicKeyPem)
    const privateKey = forge.pki.privateKeyFromPem(pair.privateKeyPem)
    return this.#enqueue(async () => {
      const now = Date.now()
      const signed = this.#signCertificate({ publicKey, name: display, deviceId: id, issuedVia: 'panel-export', now })
      const p12 = this.#buildPkcs12({ privateKey, cert: signed.forgeCertificate, password })
      writeFileAtomic(this.#keyPathOf(signed.entry.serial), pair.privateKeyPem, 0o600)
      this.certs.push(signed.entry)
      this.#reindex()
      await this.#persist()
      this.logger.info?.('exported client certificate serial=' + signed.entry.serial + ' name=' + display)
      return { ...this.#publicIssued(signed.entry), p12 }
    })
  }

  /**
   * 已存在且未撤销的证书 + 已持久化的私钥 → PKCS#12。
   * @param {{serial?:string, password?:string}} [input]
   * @returns {Promise<{serial:string, name:string, deviceId:string|null, notAfter:number,
   *   fingerprint:string, p12:Buffer, caCertificate:string, caFingerprint:string}>}
   * @throws {HttpError} 400（password 为空）/ 404（证书不存在、已撤销或私钥缺失）。
   */
  async exportPkcs12({ serial, password } = {}) {
    this.#assertPassword(password)
    const key = normalizeSerial(serial)
    const entry = this.#find(key)
    if (entry === undefined || entry.revokedAt !== null) {
      throw new HttpError(404, 'not_found', 'the client certificate is not available for export')
    }
    const privateKeyPem = readText(this.#keyPathOf(entry.serial))
    if (privateKeyPem === null) {
      throw new HttpError(404, 'not_found', 'the private key of this client certificate is not available')
    }
    const privateKey = forge.pki.privateKeyFromPem(privateKeyPem)
    const forgeCertificate = forge.pki.certificateFromPem(entry.certificate)
    const p12 = this.#buildPkcs12({ privateKey, cert: forgeCertificate, password })
    return {
      serial: entry.serial,
      name: entry.name,
      deviceId: entry.deviceId,
      notAfter: entry.notAfter,
      fingerprint: entry.fingerprint,
      p12,
      caCertificate: this.caPem,
      caFingerprint: this.caSha256,
    }
  }

  /**
   * 注册表列表（按 createdAt 倒序，契约 §4.1）。
   *
   * 只暴露契约 §5.2 的字段：证书 PEM 属于签发响应/导出材料，不在列表里重复下发。
   */
  list() {
    return this.certs
      .slice()
      .sort((left, right) => (right.createdAt ?? 0) - (left.createdAt ?? 0))
      .map((entry) => ({
        serial: entry.serial,
        deviceId: entry.deviceId,
        name: entry.name,
        createdAt: entry.createdAt,
        notAfter: entry.notAfter,
        revokedAt: entry.revokedAt,
        fingerprint: entry.fingerprint,
        issuedVia: entry.issuedVia,
      }))
  }

  /**
   * 撤销一个 serial（内存立即生效，落盘串行化）。
   * @param {string} serial
   * @returns {boolean} 不存在/已撤销 → false。
   */
  revoke(serial) {
    const entry = this.#find(serial)
    if (entry === undefined || entry.revokedAt !== null) return false
    entry.revokedAt = Date.now()
    this.revokedSerials.add(entry.serial)
    void this.#persist()
    this.logger.info?.('revoked client certificate serial=' + entry.serial)
    return true
  }

  /**
   * 撤销某设备名下全部证书。
   * @param {string} deviceId
   * @returns {number} 撤销条数。
   */
  revokeByDevice(deviceId) {
    if (typeof deviceId !== 'string' || deviceId === '') return 0
    const now = Date.now()
    let count = 0
    for (const entry of this.certs) {
      if (entry.deviceId !== deviceId || entry.revokedAt !== null) continue
      entry.revokedAt = now
      this.revokedSerials.add(entry.serial)
      count += 1
    }
    if (count > 0) {
      void this.#persist()
      this.logger.info?.('revoked ' + count + ' client certificate(s) of device ' + deviceId)
    }
    return count
  }

  /** 撤销表查询（O(1)）。 */
  isRevokedSerial(serial) {
    const key = normalizeSerial(serial)
    return key !== '' && this.revokedSerials.has(key)
  }

  /**
   * 从 TLSSocket 读 peer 证书。
   * @param {import('node:tls').TLSSocket|any} socket
   * @returns {{serial:string, deviceId:string|null, name:string, fingerprint:string,
   *   notAfter:number, revoked:boolean}|null}
   */
  peerOf(socket) {
    if (socket === null || typeof socket !== 'object') return null
    if (typeof socket.getPeerCertificate !== 'function') return null
    let peer = null
    try {
      peer = socket.getPeerCertificate()
    } catch {
      return null
    }
    if (peer === null || typeof peer !== 'object') return null
    if (peer.raw === undefined || peer.raw === null || peer.raw.length === 0) return null
    const serial = normalizeSerial(peer.serialNumber)
    const entry = serial === '' ? undefined : this.#find(serial)
    const name = entry?.name ?? (typeof peer.subject?.CN === 'string' ? peer.subject.CN : '')
    const parsedNotAfter = typeof peer.valid_to === 'string' ? Date.parse(peer.valid_to) : Number.NaN
    return {
      serial,
      deviceId: entry?.deviceId ?? null,
      name,
      fingerprint: sha256Bytes(Buffer.from(peer.raw)),
      notAfter: Number.isFinite(parsedNotAfter) ? parsedNotAfter : (entry?.notAfter ?? 0),
      revoked: this.isRevokedSerial(serial),
    }
  }

  /** 清理（flush 注册表）。永不抛。 */
  async close() {
    if (this.closed) return
    this.closed = true
    try {
      await this.#persist()
    } catch {
      // 落盘失败不影响停机
    }
  }

  // ---------------------------------------------------------------- internals

  /** 串行执行一个会改注册表的任务。 */
  #enqueue(task) {
    const run = this.mutationChain.then(task)
    this.mutationChain = run.then(() => undefined, () => undefined)
    return run
  }

  /**
   * 安排一次注册表落盘（与其它落盘串行）。
   *
   * 落盘失败只 warn、不抛：撤销/加载路径是 `void this.#persist()`，一个磁盘错误
   * 不能变成 unhandled rejection 把宿主进程带崩（注册表仍以内存为准）。
   */
  #persist() {
    const write = this.writeChain
      .then(() => this.#writeRegistry())
      .catch((error) => {
        this.logger.warn?.('client registry write failed: ' + (error?.message ?? error))
      })
    this.writeChain = write.then(() => undefined, () => undefined)
    return write
  }

  /** 原子写注册表。 */
  async #writeRegistry() {
    const payload = {
      version: REGISTRY_VERSION,
      caFingerprint: this.caSha256,
      certs: this.certs.map((entry) => ({
        serial: entry.serial,
        deviceId: entry.deviceId,
        name: entry.name,
        fingerprint: entry.fingerprint,
        createdAt: entry.createdAt,
        notAfter: entry.notAfter,
        revokedAt: entry.revokedAt,
        issuedVia: entry.issuedVia,
        // 证书 PEM 是公开材料；持久化它是为了重启后仍能 exportPkcs12()（私钥在 keys/ 下）。
        certificate: entry.certificate,
      })),
    }
    writeFileAtomic(this.registryPath, JSON.stringify(payload, null, 2) + '\n', 0o600)
  }

  /** 加载/生成 CA。返回是否重建了 CA。 */
  #loadOrCreateCa() {
    const certPem = readText(this.caCertPath)
    const keyPem = readText(this.caKeyPath)
    if (certPem !== null && keyPem !== null && this.#caUsable(certPem, keyPem)) {
      this.#adoptCa(certPem, keyPem)
      return false
    }
    if (certPem !== null || keyPem !== null) {
      this.logger.warn?.('client CA material is incomplete or invalid; regenerating a new CA')
    }
    const commonName = 'DSH Gateway CA ' + createHash('sha256').update(this.dir).digest('hex').slice(0, 8)
    const pair = generateRsaKeyPair()
    const cert = forge.pki.createCertificate()
    cert.publicKey = forge.pki.publicKeyFromPem(pair.publicKeyPem)
    cert.serialNumber = newSerialHex()
    cert.validity.notBefore = new Date(Date.now() - CLOCK_SKEW_MS)
    cert.validity.notAfter = new Date(Date.now() + CA_CERT_DAYS * 86_400_000)
    const attributes = [{ name: 'commonName', value: commonName }]
    cert.setSubject(attributes)
    cert.setIssuer(attributes)
    cert.setExtensions([
      { name: 'basicConstraints', cA: true, critical: true },
      { name: 'keyUsage', keyCertSign: true, cRLSign: true, digitalSignature: true, critical: true },
      { name: 'subjectKeyIdentifier' },
    ])
    cert.sign(forge.pki.privateKeyFromPem(pair.privateKeyPem), forge.md.sha256.create())
    const pem = forge.pki.certificateToPem(cert)
    writeFileAtomic(this.caKeyPath, pair.privateKeyPem, 0o600)
    writeFileAtomic(this.caCertPath, pem, 0o644)
    this.#adoptCa(pem, pair.privateKeyPem)
    this.logger.info?.('generated client CA ' + commonName + ' fp=' + this.caSha256.slice(0, 16) + '…')
    return true
  }

  /** CA 私钥与证书是否配对可用。 */
  #caUsable(certPem, keyPem) {
    try {
      const parsed = new X509Certificate(certPem)
      if (parsed.ca !== true) return false
      const certPublicKey = parsed.publicKey.export({ type: 'spki', format: 'pem' })
      const privateKeyPublicKey = createPublicKey(keyPem).export({ type: 'spki', format: 'pem' })
      return certPublicKey === privateKeyPublicKey
    } catch {
      return false
    }
  }

  /** 让当前实例持有 CA 材料。 */
  #adoptCa(certPem, keyPem) {
    this.caPem = certPem
    this.caSha256 = fingerprintOfPem(certPem) ?? ''
    this.caCert = forge.pki.certificateFromPem(certPem)
    this.caKey = forge.pki.privateKeyFromPem(keyPem)
  }

  /** 加载注册表；坏文件备份重建（契约 §3）。 */
  #loadRegistry() {
    let parsed
    let present = false
    try {
      const text = readText(this.registryPath)
      present = text !== null
      parsed = text === null ? undefined : JSON.parse(text)
    } catch {
      // 文件存在但内容不是 JSON → 备份重建（不是“首次启动”）。
      present = true
      parsed = undefined
    }
    if (parsed !== undefined && this.#registryValid(parsed)) {
      const entries = this.#normalizeEntries(parsed.certs)
      if (parsed.caFingerprint === this.caSha256) {
        this.certs = entries
      } else {
        this.logger.warn?.(
          'client registry was issued by CA ' + String(parsed.caFingerprint).slice(0, 16) +
          '… but the current CA is ' + this.caSha256.slice(0, 16) + '…; dropping ' + entries.length + ' stale certificate(s)',
        )
        this.certs = []
      }
      this.#reindex()
      if (this.certs.length !== parsed.certs.length) void this.#persist()
      return
    }
    if (parsed !== undefined || present) {
      this.logger.warn?.('client registry is malformed; backing it up and rebuilding an empty one')
      if (present) this.#backupRegistry()
    }
    this.certs = []
    this.revokedSerials.clear()
    void this.#persist()
  }

  /** 粗校验注册表结构。 */
  #registryValid(value) {
    if (value === null || typeof value !== 'object' || Array.isArray(value)) return false
    if (value.version !== REGISTRY_VERSION) return false
    if (typeof value.caFingerprint !== 'string' || value.caFingerprint === '') return false
    return Array.isArray(value.certs)
  }

  /** 注册表条目归一化；丢弃无法识别的条目。 */
  #normalizeEntries(rawEntries) {
    const entries = []
    const seen = new Set()
    for (const raw of Array.isArray(rawEntries) ? rawEntries : []) {
      if (raw === null || typeof raw !== 'object') continue
      const serial = normalizeSerial(raw.serial)
      if (!SERIAL_PATTERN.test(serial) || seen.has(serial)) continue
      if (typeof raw.fingerprint !== 'string' || raw.fingerprint === '') continue
      // 重启后 exportPkcs12() 需要证书 PEM；指纹对不上说明文件被改坏，丢弃该条目。
      if (typeof raw.certificate !== 'string' || raw.certificate.trim() === '') continue
      if (fingerprintOfPem(raw.certificate) !== raw.fingerprint.toLowerCase()) continue
      // 旧版本可能留下解析不了的证书（非 ASCII 主体编码缺陷）：既不能导出也不能
      // 用于认证，丢弃它，避免面板继续导出坏掉的 .p12。
      if (!isParsableCertificate(raw.certificate)) continue
      const createdAt = Number.isFinite(raw.createdAt) ? Math.trunc(raw.createdAt) : 0
      const notAfter = Number.isFinite(raw.notAfter) ? Math.trunc(raw.notAfter) : 0
      if (notAfter <= 0) continue
      seen.add(serial)
      entries.push({
        serial,
        deviceId: typeof raw.deviceId === 'string' && raw.deviceId !== '' ? raw.deviceId : null,
        name: cleanName(raw.name, serial.slice(0, 8)),
        fingerprint: raw.fingerprint,
        createdAt,
        notAfter,
        revokedAt: Number.isFinite(raw.revokedAt) ? Math.trunc(raw.revokedAt) : null,
        issuedVia: ISSUED_VIA.has(raw.issuedVia) ? raw.issuedVia : 'lan-enroll',
        certificate: raw.certificate,
      })
    }
    return entries
  }

  /** 把坏注册表备份成 clients.json.bad-<ts>（保留证据，绝不阻塞启动）。 */
  #backupRegistry() {
    try {
      copyFileSync(this.registryPath, this.registryPath + '.bad-' + Date.now())
    } catch (error) {
      this.logger.warn?.('client registry backup failed: ' + (error?.message ?? error))
    }
  }

  /** 重建撤销 Set。 */
  #reindex() {
    const revoked = new Set()
    for (const entry of this.certs) {
      if (entry.revokedAt !== null) revoked.add(entry.serial)
    }
    this.revokedSerials = revoked
  }

  /** 按序列号查条目（大小写不敏感）。 */
  #find(serial) {
    const key = normalizeSerial(serial)
    if (key === '') return undefined
    return this.certs.find((entry) => entry.serial === key)
  }

  /** 撤销某设备名下未撤销、未过期的 LAN 领证证书（续期语义）。 */
  #revokeCertificatesOfDevice(deviceId, now) {
    if (deviceId === null) return 0
    let count = 0
    for (const entry of this.certs) {
      if (entry.deviceId !== deviceId) continue
      if (entry.issuedVia !== 'lan-enroll') continue
      if (entry.revokedAt !== null) continue
      if (entry.notAfter <= now) continue
      entry.revokedAt = now
      count += 1
    }
    if (count > 0) this.#reindex()
    return count
  }

  /** CSR 校验 → forge 公钥。 */
  #verifyCsr(csr) {
    if (typeof csr !== 'string' || csr.trim() === '') {
      throw new HttpError(400, 'invalid_request', 'csr is required')
    }
    let request
    try {
      request = forge.pki.certificationRequestFromPem(csr)
    } catch {
      throw new HttpError(400, 'invalid_request', 'csr is not a valid PKCS#10 request')
    }
    let verified = false
    try {
      verified = request.verify()
    } catch {
      verified = false
    }
    if (verified !== true) {
      throw new HttpError(400, 'invalid_request', 'csr signature verification failed')
    }
    const publicKey = request.publicKey
    if (publicKey === null || publicKey === undefined || publicKey.n === undefined || publicKey.e === undefined) {
      throw new HttpError(400, 'invalid_request', 'csr must carry an RSA public key')
    }
    return publicKey
  }

  /** 归一化可选的 deviceId（非法值按 null 处理，不签发绑定关系）。 */
  #deviceIdOf(value) {
    if (typeof value !== 'string') return null
    const trimmed = value.trim()
    return DEVICE_ID_PATTERN.test(trimmed) ? trimmed : null
  }

  /** 密码校验（契约 §4.1：password 为空 → 400）。 */
  #assertPassword(password) {
    if (typeof password !== 'string' || password === '') {
      throw new HttpError(400, 'invalid_request', 'password is required')
    }
  }

  /** 生成一个未被占用的序列号。 */
  #uniqueSerial() {
    for (let attempt = 0; attempt < 8; attempt += 1) {
      const serial = newSerialHex()
      if (this.#find(serial) === undefined) return serial
    }
    throw new HttpError(500, 'internal_error', 'could not allocate a certificate serial number')
  }

  /** CA 公钥的 subjectKeyIdentifier（SHA-1，RFC3280 §4.2.1.2）。 */
  #caKeyIdentifier() {
    return forge.pki.getPublicKeyFingerprint(this.caCert.publicKey, { type: 'RSAPublicKey' }).getBytes()
  }

  /** 用 CA 私钥签发一张客户端证书。 */
  #signCertificate({ publicKey, name, deviceId, issuedVia, now }) {
    const cert = forge.pki.createCertificate()
    cert.publicKey = publicKey
    const serial = this.#uniqueSerial()
    cert.serialNumber = serial
    // X.509 的时间精度是秒：先取整，让注册表里的 notAfter 与 X509Certificate.validTo 完全一致。
    const notBefore = Math.floor((now - CLOCK_SKEW_MS) / 1000) * 1000
    const notAfter = Math.floor((now + clientCertDaysOf(this.config) * 86_400_000) / 1000) * 1000
    cert.validity.notBefore = new Date(notBefore)
    cert.validity.notAfter = new Date(notAfter)
    // 证书主体必须是 ASCII（见 asciiCommonName）：中文展示名只留在注册表/面板。
    const commonName = asciiCommonName(
      name,
      asciiCommonName(deviceId, 'dsh-client-' + serial.slice(0, 8)),
    )
    cert.setSubject([{ name: 'commonName', value: commonName }])
    cert.setIssuer(this.caCert.subject.attributes)
    cert.setExtensions([
      { name: 'basicConstraints', cA: false, critical: true },
      { name: 'keyUsage', digitalSignature: true, keyEncipherment: true, critical: true },
      { name: 'extKeyUsage', clientAuth: true },
      { name: 'subjectKeyIdentifier' },
      // forge 的 keyIdentifier:true 会误用**本证书**的公钥；这里显式给 CA 的 SKI。
      { name: 'authorityKeyIdentifier', keyIdentifier: this.#caKeyIdentifier() },
    ])
    cert.sign(this.caKey, forge.md.sha256.create())
    const certificate = forge.pki.certificateToPem(cert)
    // 不变量：落库、下发给手机、装进 PKCS#12 的证书必须能被标准 X.509 解析器读过。
    // forge 对非法主体编码有缺陷，这里直接拒绝，绝不把坏证书发出去。
    if (!isParsableCertificate(certificate)) {
      this.logger.error?.('signed client certificate is not a parsable X.509 (cn=' + commonName + ')')
      throw new HttpError(500, 'internal_error', 'the client certificate could not be encoded')
    }
    const entry = {
      serial,
      deviceId: deviceId ?? null,
      name,
      fingerprint: certificateFingerprint(cert),
      createdAt: now,
      notAfter,
      revokedAt: null,
      issuedVia,
      certificate,
    }
    return { forgeCertificate: cert, entry }
  }

  /** 注册表条目 → 对外签发响应。 */
  #publicIssued(entry) {
    return {
      certificate: entry.certificate,
      serial: entry.serial,
      notAfter: entry.notAfter,
      deviceId: entry.deviceId,
      name: entry.name,
      fingerprint: entry.fingerprint,
      caCertificate: this.caPem,
      caFingerprint: this.caSha256,
    }
  }

  /** 导出私钥的落盘路径。 */
  #keyPathOf(serial) {
    return join(this.keysDir, serial + '.pem')
  }

  /** 组装 PKCS#12（PBE 3DES，契约 §2）。 */
  #buildPkcs12({ privateKey, cert, password }) {
    const asn1 = forge.pkcs12.toPkcs12Asn1(privateKey, [cert, this.caCert], password, {
      algorithm: '3des',
    })
    const p12 = Buffer.from(forge.asn1.toDer(asn1).getBytes(), 'binary')
    // 导出前用 OpenSSL（node:tls）读一遍：MAC/包装参数不对时这里就会失败，
    // 把问题留在网关日志里，而不是等用户在手机上「导入失败」。
    try {
      createSecureContext({ pfx: p12, passphrase: password })
    } catch (error) {
      this.logger.error?.('exported PKCS#12 failed verification: ' + (error?.message ?? error))
      throw new HttpError(500, 'internal_error', 'the exported certificate bundle could not be verified')
    }
    return p12
  }
}

/**
 * 加载或创建某个数据目录的客户端 PKI。
 * @param {object} options
 * @param {string} options.dir - 网关数据目录。
 * @param {Record<string, any>} [options.config]
 * @param {{info?:Function,warn?:Function,error?:Function,debug?:Function}} [options.logger]
 * @returns {Promise<ClientPki>}
 */
export async function ensureClientPki({ dir, config, logger } = {}) {
  const pki = new ClientPki({ dir, config, logger })
  await pki.load()
  return pki
}

export default { ensureClientPki, ClientPki }
