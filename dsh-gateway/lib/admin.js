/**
 * Loopback-only admin page for the gateway.
 *
 * The page is fully self-contained (inline CSS + inline JS, no external
 * requests, strict CSP) because it runs on `http://127.0.0.1:<lanPort>/admin`
 * where no network fetch is needed:
 *   - gateway status (DSH link, ports, mDNS, uptime, certificate fingerprint),
 *   - one-shot pairing QR code (`dshgw://pair?…`, generated with `qrcode`),
 *   - device list with revoke buttons,
 *   - memory-only switches for `lanRequirePairing` / `mdns` (restart hint).
 *
 * `renderAdminPage` is synchronous and embeds the first state snapshot;
 * `renderAdminState` is asynchronous because the QR code data URL is produced
 * by `qrcode`. Neither function performs I/O of its own.
 *
 * @module dsh-gateway/lib/admin
 */

import { networkInterfaces } from 'node:os'
import QRCode from 'qrcode'
import { classifyAddress } from './util.js'

/** Escape a value for HTML text/attribute context. */
function escapeHtml(value) {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

/** JSON safe to inline inside a `<script>` tag. */
function jsonForScript(value) {
  return JSON.stringify(value ?? null)
    .replace(/</g, '\\u003c')
    .replace(/>/g, '\\u003e')
    .replace(/\u2028/g, '\\u2028')
    .replace(/\u2029/g, '\\u2029')
}

/**
 * Enumerate this machine's addresses, split into LAN and public buckets.
 *
 * LAN means "private/link-local/ULA and therefore reachable over plain HTTP
 * without a certificate". Link-local IPv6 is skipped on purpose: a URL needs a
 * zone id to use it, so phones cannot reach it.
 * @returns {{lan:string[], public:string[], all:string[]}}
 */
export function enumerateAddresses() {
  const lan = []
  const publicList = []
  let interfaces = {}
  try {
    interfaces = networkInterfaces()
  } catch {
    interfaces = {}
  }
  const seenLan = new Set()
  const seenPublic = new Set()
  for (const addresses of Object.values(interfaces)) {
    for (const entry of addresses ?? []) {
      if (entry === null || typeof entry !== 'object' || entry.internal) continue
      const raw = String(entry.address ?? '')
      const address = raw.split('%')[0].toLowerCase()
      if (address === '' || address === '0.0.0.0' || address === '::') continue
      // fe80::/10 link-local needs an interface zone to form a usable URL.
      if (/^fe[89ab][0-9a-f]:/.test(address)) continue
      const kind = classifyAddress(address)
      if (kind === 'loopback') continue
      if (kind === 'lan') {
        if (!seenLan.has(address)) {
          seenLan.add(address)
          lan.push(address)
        }
      } else if (!seenPublic.has(address)) {
        seenPublic.add(address)
        publicList.push(address)
      }
    }
  }
  // Order candidates by usefulness for a phone on the same network. Home Wi-Fi
  // (192.168/16) almost always wins; 10/8 is often a real office LAN while
  // 172.16/12 is frequently a WSL/Hyper-V/Docker virtual switch; CGNAT
  // (100.64/10, Tailscale) and IPv6 last.
  const rank = (address) => {
    if (!address.includes(':')) {
      const [a, b] = address.split('.').map((part) => Number(part))
      if (a === 192 && b === 168) return 0
      if (a === 10) return 1
      if (a === 172 && b >= 16 && b <= 31) return 2
      if (a === 100 && b >= 64 && b <= 127) return 3
      return 4
    }
    if (/^f[cd][0-9a-f]{2}:/.test(address)) return 5
    return 6
  }
  lan.sort((left, right) => rank(left) - rank(right) || left.localeCompare(right))
  publicList.sort((left, right) => rank(left) - rank(right) || left.localeCompare(right))
  return { lan, public: publicList, all: [...lan, ...publicList] }
}

/** Format an address for use inside a URL (IPv6 wrapped in brackets). */
export function formatHost(address) {
  const value = String(address ?? '')
  if (value.includes(':')) return `[${value}]`
  return value
}

/**
 * Build the `dshgw://pair?…` deep link scanned by the APK.
 *
 * LAN candidates are plain `http://…` (no certificate, no token needed);
 * public candidates are `https://…` and need the pairing code + certificate
 * fingerprint. `url` stays as the primary/first choice for older clients.
 */
export function pairingUri({ url, lan, pub, code, fingerprint, name } = {}) {
  const params = new URLSearchParams()
  if (typeof url === 'string' && url !== '') params.set('url', url)
  if (typeof lan === 'string' && lan !== '') params.set('lan', lan)
  if (typeof pub === 'string' && pub !== '') params.set('pub', pub)
  if (typeof code === 'string' && code !== '') params.set('code', code)
  if (typeof fingerprint === 'string' && fingerprint !== '') params.set('fp', fingerprint)
  params.set('n', typeof name === 'string' && name !== '' ? name : 'dsh-gateway')
  return `dshgw://pair?${params.toString()}`
}

/**
 * Build the presentation object for one pairing code, including QR data URL.
 *
 * The QR prefers the LAN plain-HTTP URL: a phone on the same network then
 * connects without any TLS certificate, pairing code or device token.
 * Public HTTPS URLs remain in the payload as a fallback (`pub`).
 * @param {{pairing:object|null, fingerprint?:string, name?:string}} input
 * @returns {Promise<object|null>}
 */
export async function renderPairingView({ pairing, fingerprint = '', name = '' } = {}) {
  if (pairing === null || typeof pairing !== 'object' || typeof pairing.code !== 'string') return null
  const urls = (Array.isArray(pairing.urls) ? pairing.urls : [])
    .filter((url) => typeof url === 'string' && url.trim() !== '')
    .map((url) => url.trim())
  const lanUrls = urls.filter((url) => /^http:\/\//i.test(url))
  const publicUrls = urls.filter((url) => /^https:\/\//i.test(url))
  const lanUrl = typeof pairing.lanUrl === 'string' && pairing.lanUrl !== '' ? pairing.lanUrl : (lanUrls[0] ?? '')
  const publicUrl = typeof pairing.publicUrl === 'string' && pairing.publicUrl !== '' ? pairing.publicUrl : (publicUrls[0] ?? '')
  const url = lanUrl !== '' ? lanUrl : (publicUrl !== '' ? publicUrl : (urls[0] ?? ''))
  const fp = typeof pairing.fingerprint === 'string' && pairing.fingerprint !== '' ? pairing.fingerprint : fingerprint
  const uri = url === ''
    ? null
    : pairingUri({
      url,
      lan: lanUrl !== '' ? lanUrl : undefined,
      pub: publicUrl !== '' ? publicUrl : undefined,
      code: pairing.code,
      fingerprint: fp,
      name,
    })
  let qrDataUrl = null
  if (uri !== null) {
    try {
      qrDataUrl = await QRCode.toDataURL(uri, {
        errorCorrectionLevel: 'M',
        margin: 1,
        width: 320,
        color: { dark: '#0b1220ff', light: '#ffffffff' },
      })
    } catch {
      qrDataUrl = null
    }
  }
  return {
    code: pairing.code,
    createdAt: pairing.createdAt ?? null,
    expiresAt: pairing.expiresAt ?? null,
    ttlMs: pairing.ttlMs ?? null,
    urls,
    lanUrls,
    publicUrls,
    lanUrl: lanUrl === '' ? null : lanUrl,
    publicUrl: publicUrl === '' ? null : publicUrl,
    url,
    fingerprint: fp || null,
    uri,
    qrDataUrl,
  }
}

/**
 * Build the JSON payload served by `GET /admin/api/state`.
 * @param {object} input
 * @returns {Promise<object>}
 */
export async function renderAdminState({
  name = 'dsh-gateway',
  version = '0.1.0',
  fingerprint = '',
  ports = { lan: 0, public: 0 },
  mdns = { enabled: false, active: false },
  dsh = { connected: false, clientId: null },
  devices = [],
  pairing = null,
  config = {},
  publicUrls = [],
  lanUrls = [],
  uptimeMs = 0,
  restartRequired = false,
  lastError = '',
} = {}) {
  const pairingView = await renderPairingView({ pairing, fingerprint, name })
  return {
    ok: true,
    name,
    version,
    fingerprint,
    ports: { lan: Number(ports?.lan) || 0, public: Number(ports?.public) || 0 },
    mdns: { enabled: Boolean(mdns?.enabled), active: Boolean(mdns?.active) },
    dsh: { connected: Boolean(dsh?.connected), clientId: dsh?.clientId ?? null },
    devices: Array.isArray(devices) ? devices : [],
    pairing: pairingView,
    config: {
      httpPort: config.httpPort ?? 0,
      httpsPort: config.httpsPort ?? 0,
      lanRequirePairing: Boolean(config.lanRequirePairing),
      mdns: Boolean(config.mdns),
      maxDevices: config.maxDevices ?? 4,
      publicUrl: typeof config.publicUrl === 'string' ? config.publicUrl : '',
    },
    publicUrls: Array.isArray(publicUrls) ? publicUrls : [],
    lanUrls: Array.isArray(lanUrls) ? lanUrls : [],
    uptimeMs: Number(uptimeMs) || 0,
    restartRequired: Boolean(restartRequired),
    lastError: typeof lastError === 'string' ? lastError : '',
    serverTime: Date.now(),
  }
}

const PAGE_CSS = `
:root{color-scheme:dark}
*{box-sizing:border-box}
body{margin:0;font:14px/1.5 system-ui,"Segoe UI",Roboto,"PingFang SC","Microsoft YaHei",sans-serif;
  background:radial-gradient(1200px 600px at 10% -10%,#1d2b4f 0%,#0b1220 55%,#070a12 100%);
  color:#e8edf7;min-height:100vh}
main{max-width:920px;margin:0 auto;padding:28px 18px 60px}
h1{font-size:22px;margin:0 0 4px;font-weight:650;letter-spacing:.2px}
.sub{color:#93a4c3;font-size:13px}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:12px;margin:20px 0}
.card{background:rgba(23,32,52,.72);border:1px solid rgba(120,150,200,.18);border-radius:14px;padding:14px 16px;
  backdrop-filter:blur(10px);box-shadow:0 8px 24px rgba(0,0,0,.25)}
.card .k{color:#8fa3c5;font-size:12px;text-transform:uppercase;letter-spacing:.08em}
.card .v{font-size:17px;margin-top:4px;font-weight:600;word-break:break-all}
.ok{color:#4ade80}.bad{color:#f87171}.warn{color:#fbbf24}.muted{color:#93a4c3}
section{margin:26px 0}
h2{font-size:16px;margin:0 0 12px;font-weight:620;display:flex;align-items:center;gap:8px}
h2 .dot{width:8px;height:8px;border-radius:50%;background:#4f7cff;box-shadow:0 0 12px #4f7cff}
button{font:inherit;cursor:pointer;border-radius:10px;border:1px solid rgba(130,160,220,.35);
  background:linear-gradient(180deg,#2a3d63,#1d2a44);color:#e8edf7;padding:8px 14px;transition:.15s}
button:hover{filter:brightness(1.15)}
button.danger{border-color:rgba(248,113,113,.45);background:linear-gradient(180deg,#5a2430,#3b1821);color:#ffd7d7}
button:disabled{opacity:.5;cursor:not-allowed}
.row{display:flex;gap:12px;flex-wrap:wrap;align-items:flex-start}
.pair-box{display:flex;gap:18px;flex-wrap:wrap;align-items:center}
#persistent-qr{width:180px;height:180px;border-radius:12px;background:#fff;padding:8px;display:none}
#persistent-qr[src]{display:block}
code,.mono{font-family:ui-monospace,Consolas,"SF Mono",monospace;font-size:12px;word-break:break-all}
.code{background:rgba(10,16,30,.8);border:1px dashed rgba(120,150,220,.35);padding:8px 10px;border-radius:8px;display:inline-block;margin:6px 0}
table{width:100%;border-collapse:collapse;font-size:13px}
th,td{text-align:left;padding:8px 6px;border-bottom:1px solid rgba(120,150,220,.14);vertical-align:top}
th{color:#8fa3c5;font-weight:500;font-size:12px}
tr:last-child td{border-bottom:none}
.empty{color:#7d8fad;padding:10px 0}
label.switch{display:flex;align-items:center;gap:10px;margin:8px 0;cursor:pointer}
input[type=checkbox]{appearance:none;width:42px;height:24px;border-radius:999px;background:#26324a;position:relative;
  transition:.2s;outline:none;border:1px solid rgba(130,160,220,.3)}
input[type=checkbox]::after{content:"";position:absolute;left:3px;top:2px;width:18px;height:18px;border-radius:50%;
  background:#93a4c3;transition:.2s}
input[type=checkbox]:checked{background:#2f5bd3;border-color:#4f7cff}
input[type=checkbox]:checked::after{left:19px;background:#fff}
.hint{font-size:12px;color:#fbbf24;margin-left:6px;display:none}
.hint.show{display:inline}
#toast{position:fixed;left:50%;bottom:26px;transform:translateX(-50%);background:#111c33;border:1px solid rgba(130,160,220,.35);
  color:#e8edf7;padding:10px 18px;border-radius:12px;opacity:0;transition:.25s;pointer-events:none;z-index:9;
  box-shadow:0 10px 30px rgba(0,0,0,.4)}
#toast.show{opacity:1}
.footer{margin-top:34px;color:#7d8fad;font-size:12px;line-height:1.7}
.warnbox{margin-top:14px;border-left:3px solid #f59e0b;background:rgba(120,80,10,.18);padding:10px 14px;border-radius:0 10px 10px 0;
  color:#fcd9a0;font-size:12.5px}
`

const PAGE_JS = String.raw`
(function () {
  'use strict';
  var state = window.__DSHGW_STATE__ || {};
  var timer = null;

  function $(id) { return document.getElementById(id); }
  function el(tag, cls, text) {
    var node = document.createElement(tag);
    if (cls) node.className = cls;
    if (text !== undefined && text !== null) node.textContent = String(text);
    return node;
  }
  function toast(message) {
    var node = $('toast');
    node.textContent = message;
    node.classList.add('show');
    clearTimeout(timer);
    timer = setTimeout(function () { node.classList.remove('show'); }, 2600);
  }
  function fmtTime(ms) {
    if (typeof ms !== 'number' || ms <= 0) return '—';
    var date = new Date(ms);
    var pad = function (n) { return (n < 10 ? '0' : '') + n; };
    return date.getFullYear() + '-' + pad(date.getMonth() + 1) + '-' + pad(date.getDate()) + ' ' +
      pad(date.getHours()) + ':' + pad(date.getMinutes());
  }
  function fmtDuration(ms) {
    if (typeof ms !== 'number' || ms <= 0) return '—';
    var s = Math.floor(ms / 1000);
    var d = Math.floor(s / 86400); s -= d * 86400;
    var h = Math.floor(s / 3600); s -= h * 3600;
    var m = Math.floor(s / 60);
    if (d > 0) return d + ' 天 ' + h + ' 小时';
    if (h > 0) return h + ' 小时 ' + m + ' 分';
    return m + ' 分';
  }
  function api(path, options) {
    return fetch(path, Object.assign({ headers: { 'content-type': 'application/json' } }, options || {}))
      .then(function (response) {
        return response.json().catch(function () { return {}; }).then(function (body) {
          if (!response.ok || body.ok === false) {
            var message = body && body.error ? body.error.message : ('HTTP ' + response.status);
            throw new Error(message || 'request failed');
          }
          return body;
        });
      });
  }

  function renderStatus(next) {
    var dsh = next.dsh || {};
    var dshNode = $('v-dsh');
    dshNode.textContent = dsh.connected ? '已连接' : '未连接';
    dshNode.className = 'v ' + (dsh.connected ? 'ok' : 'bad');
    $('v-lan').textContent = next.ports && next.ports.lan ? ('0.0.0.0:' + next.ports.lan) : '已关闭';
    $('v-public').textContent = next.ports && next.ports.public ? ('0.0.0.0:' + next.ports.public) : '已关闭';
    var mdns = next.mdns || {};
    var mdnsNode = $('v-mdns');
    mdnsNode.textContent = mdns.enabled ? (mdns.active ? '广播中' : '已启用') : '已关闭';
    mdnsNode.className = 'v ' + (mdns.active ? 'ok' : (mdns.enabled ? 'warn' : 'muted'));
    $('v-uptime').textContent = fmtDuration(next.uptimeMs);
    $('v-devices').textContent = String((next.devices || []).length);
    $('v-fp').textContent = next.fingerprint || '—';
    $('v-name').textContent = next.name || 'dsh-gateway';
    $('v-version').textContent = 'v1 · gateway ' + (next.version || '0.1.0');
    var lanToggle = $('toggle-lan');
    lanToggle.checked = Boolean(next.config && next.config.lanRequirePairing);
    var mdnsToggle = $('toggle-mdns');
    mdnsToggle.checked = Boolean(next.config && next.config.mdns);
    var restart = $('restart-hint');
    if (next.restartRequired) restart.classList.add('show'); else restart.classList.remove('show');
  }

  function renderPairing(pairing) {
    var qr = $('persistent-qr');
    var box = $('pairing-meta');
    var urlsBox = $('pairing-urls');
    box.textContent = '';
    urlsBox.textContent = '';
    if (!pairing) {
      qr.removeAttribute('src');
      box.appendChild(el('div', 'empty', '尚未生成配对码。点击「生成配对二维码」创建一次性码（10 分钟有效）。'));
      return;
    }
    if (pairing.qrDataUrl) qr.src = pairing.qrDataUrl; else qr.removeAttribute('src');
    var lines = [
      ['配对码', pairing.code],
      ['有效期至', fmtTime(pairing.expiresAt)],
      ['局域网（HTTP，免证书）', pairing.lanUrl || '—'],
      ['公网（HTTPS）', pairing.publicUrl || '—'],
      ['证书指纹（仅公网）', (pairing.fingerprint || '').slice(0, 48) + ((pairing.fingerprint || '').length > 48 ? '…' : '')]
    ];
    lines.forEach(function (pair) {
      var row = el('div');
      row.appendChild(el('span', 'muted', pair[0] + '：'));
      row.appendChild(el('code', '', pair[1] || '—'));
      box.appendChild(row);
    });
    if (pairing.uri) box.appendChild(el('div', 'code mono', pairing.uri));
    (pairing.lanUrls || []).forEach(function (url) {
      urlsBox.appendChild(el('div', 'mono muted', '局域网（免证书）：' + url));
    });
    (pairing.publicUrls || (pairing.urls || []).filter(function (url) { return /^https:/i.test(url); })).forEach(function (url) {
      urlsBox.appendChild(el('div', 'mono muted', '公网（HTTPS）：' + url));
    });
  }

  function renderDevices(devices) {
    var tbody = $('device-rows');
    tbody.textContent = '';
    if (!devices || devices.length === 0) {
      var tr = el('tr');
      var td = el('td', 'empty', '暂无已配对设备');
      td.colSpan = 5;
      tr.appendChild(td);
      tbody.appendChild(tr);
      return;
    }
    devices.forEach(function (device) {
      var tr = el('tr');
      tr.appendChild(el('td', '', device.name || '(未命名)'));
      tr.appendChild(el('td', 'mono', device.deviceId || ''));
      tr.appendChild(el('td', 'mono', device.remoteAddress || '—'));
      tr.appendChild(el('td', '', fmtTime(device.lastSeen)));
      var actions = el('td');
      var button = el('button', 'danger', '撤销');
      button.addEventListener('click', function () {
        if (!window.confirm('撤销设备「' + (device.name || device.deviceId) + '」？其令牌立即失效。')) return;
        button.disabled = true;
        api('/admin/api/devices/' + encodeURIComponent(device.deviceId), { method: 'DELETE' })
          .then(function () { toast('已撤销'); return refresh(); })
          .catch(function (error) { button.disabled = false; toast(error.message); });
      });
      actions.appendChild(button);
      tr.appendChild(actions);
      tbody.appendChild(tr);
    });
  }

  function render(next) {
    state = next;
    renderStatus(next);
    renderPairing(next.pairing);
    renderDevices(next.devices);
  }

  function refresh() {
    return api('/admin/api/state').then(render).catch(function (error) { toast(error.message); });
  }

  function createPairing() {
    var button = $('btn-pair');
    button.disabled = true;
    api('/admin/api/pairing', { method: 'POST', body: JSON.stringify({}) })
      .then(function (body) { renderPairing(body.pairing); toast('配对码已生成'); return refresh(); })
      .catch(function (error) { toast(error.message); })
      .then(function () { button.disabled = false; });
  }

  function patchConfig(key, value) {
    api('/admin/api/config', { method: 'PATCH', body: JSON.stringify({ key: key, value: value }) })
      .then(function (body) {
        toast(body.restartRequired ? '已写入内存，重启 DSH 后生效' : '已更新');
        return refresh();
      })
      .catch(function (error) { toast(error.message); return refresh(); });
  }

  document.addEventListener('DOMContentLoaded', function () {
    render(state);
    $('btn-pair').addEventListener('click', createPairing);
    $('toggle-lan').addEventListener('change', function (event) {
      patchConfig('lanRequirePairing', event.target.checked);
    });
    $('toggle-mdns').addEventListener('change', function (event) {
      patchConfig('mdns', event.target.checked);
    });
    setInterval(refresh, 15000);
    refresh();
  });
})();
`

/**
 * Render the whole admin page.
 * @param {object} input - see `renderAdminState`; `state` may be a prebuilt snapshot.
 * @returns {string} HTML document.
 */
export function renderAdminPage(input = {}) {
  const state = input.state ?? {}
  const name = typeof input.name === 'string' && input.name !== '' ? input.name : (state.name ?? 'dsh-gateway')
  const fingerprint = typeof input.fingerprint === 'string' ? input.fingerprint : (state.fingerprint ?? '')
  const safeName = escapeHtml(name)
  const safeFingerprint = escapeHtml(fingerprint || '—')
  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="referrer" content="no-referrer">
<title>${safeName} · dsh-gateway 管理</title>
<style>${PAGE_CSS}</style>
</head>
<body>
<main>
  <header>
    <h1 id="v-name">${safeName}</h1>
    <div class="sub" id="v-version">v1 · gateway 0.1.0</div>
    <div class="sub">证书指纹 <code id="v-fp">${safeFingerprint}</code></div>
  </header>

  <div class="grid">
    <div class="card"><div class="k">DSH 连接</div><div class="v" id="v-dsh">—</div></div>
    <div class="card"><div class="k">局域网 HTTP</div><div class="v" id="v-lan">—</div></div>
    <div class="card"><div class="k">公网 HTTPS</div><div class="v" id="v-public">—</div></div>
    <div class="card"><div class="k">mDNS</div><div class="v" id="v-mdns">—</div></div>
    <div class="card"><div class="k">已配对设备</div><div class="v" id="v-devices">—</div></div>
    <div class="card"><div class="k">已运行</div><div class="v" id="v-uptime">—</div></div>
  </div>

  <section>
    <h2><span class="dot"></span>扫码配对（局域网 HTTP / 公网 HTTPS）</h2>
    <div class="pair-box">
      <img id="persistent-qr" alt="配对二维码">
      <div style="flex:1;min-width:260px">
        <button id="btn-pair" type="button">生成配对二维码</button>
        <span class="hint" id="restart-hint">重启后生效</span>
        <div id="pairing-meta"></div>
        <div id="pairing-urls" style="margin-top:8px"></div>
      </div>
    </div>
    <div class="warnbox">同一局域网优先走明文 HTTP（端口 7788），<b>不需要证书、也不需要设备令牌</b>；公网才走 HTTPS（7789），需要一次性配对码与证书指纹（APK 固定校验）。二维码含一次性配对码，请勿截图外传。</div>
  </section>

  <section>
    <h2><span class="dot"></span>已配对设备</h2>
    <table>
      <thead><tr><th>名称</th><th>设备 ID</th><th>最近来源</th><th>最近活跃</th><th>操作</th></tr></thead>
      <tbody id="device-rows"><tr><td class="empty" colspan="5">加载中…</td></tr></tbody>
    </table>
  </section>

  <section>
    <h2><span class="dot"></span>开关（仅内存，重启 DSH 后按配置生效）</h2>
    <label class="switch"><input type="checkbox" id="toggle-lan"><span>局域网也要求配对令牌（lanRequirePairing）</span></label>
    <label class="switch"><input type="checkbox" id="toggle-mdns"><span>启用 mDNS 广播（mdns，需重启）</span></label>
  </section>

  <div class="footer">
    本页仅限回环访问（127.0.0.1）。局域网默认免认证意味着同一局域网内任何人都能控制本机 DSH，
    请仅在可信网络使用；需要更严格策略时开启 lanRequirePairing 或关闭局域网端口。
  </div>
</main>
<div id="toast"></div>
<script>window.__DSHGW_STATE__=${jsonForScript(state)};</script>
<script>${PAGE_JS}</script>
</body>
</html>`
}

export default { renderAdminPage, renderAdminState, renderPairingView, pairingUri, enumerateAddresses }
