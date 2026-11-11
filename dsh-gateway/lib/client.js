/**
 * Browser half of dsh-gateway: the gateway's own configuration page.
 *
 * This replaces the standalone `/admin` HTML page the plugin used to serve from
 * its LAN listener. The page registers into the Plugins page's
 * `plugins.row.config` slot, so it renders where every other plugin's settings
 * live — under the installed plugin's row, inside the host's theme, locale and
 * signed-in session:
 *
 *   - status: DSH link, LAN/public ports, mDNS, paired-device count, uptime,
 *   - pairing: a one-shot `dshgw://pair?…` code drawn as an inline SVG QR,
 *   - devices: the paired table with a revoke action per row,
 *   - switches: `lanRequirePairing` and `mdns`, applied in memory with an
 *     explicit "restart DSH" notice where the listener only re-reads them then.
 *
 * Everything here is plain JavaScript against the public Client API: `slots`
 * for the seat, `locale` for copy, and `fetch` for the Host half's JSON routes
 * mounted by `lib/panel-routes.js`. No Harness Client package is imported, and
 * no DOM outside the component is touched.
 */
window.__ModuleLoader__.load({
  id: 'dsh-gateway',
  factory(require) {
    const React = require('react');
    const h = React.createElement;

    /** Locale namespace for this page's copy. */
    const NS = 'dshGateway';
    /** Stylesheet marker so a reload replaces the rules instead of stacking them. */
    const STYLE_ID = 'dsh-gateway/panel.css';
    /** Class prefix: a patch must never style another plugin's markup. */
    const CLS = 'dshgw';
    /** Host routes (see lib/panel-routes.js). */
    const API = '/plugins/dsh-gateway';
    /** How often the status snapshot is refreshed, in milliseconds. */
    const POLL_MS = 12000;

    /* ------------------------------------------------------------------ copy */

    const zh = {
      'meta.title': 'DSH 网关',
      'meta.description': '把本机 DSH 的会话与消息转发给手机，支持扫码配对、设备管理与局域网/公网开关。',
      'card.expand': '展开 {title}',
      'card.collapse': '收起 {title}',
      'section.status': '运行状态',
      'section.pairing': '扫码配对',
      'section.devices': '已配对设备',
      'section.config': '开关',
      'status.dsh': 'DSH 连接',
      'status.lan': '局域网 HTTP',
      'status.public': '公网 HTTPS',
      'status.mdns': 'mDNS 广播',
      'status.devices': '已配对设备',
      'status.uptime': '已运行',
      'status.on': '已连接',
      'status.off': '未连接',
      'status.disabled': '已关闭',
      'status.advertising': '广播中',
      'status.enabled': '已启用',
      'status.muted': '已停用',
      'pairing.create': '生成配对二维码',
      'pairing.renew': '重新生成',
      'pairing.empty': '尚未生成配对码。点击上方按钮创建一次性码（10 分钟有效）。',
      'pairing.placeholder': '二维码将显示在这里',
      'pairing.code': '配对码',
      'pairing.expires': '有效期至',
      'pairing.lan': '局域网（HTTP，免证书）',
      'pairing.public': '公网（HTTPS）',
      'pairing.fingerprint': '证书指纹（仅公网）',
      'pairing.note': '同一局域网优先走明文 HTTP，不需要证书也不需要设备令牌；公网才走 HTTPS，需要一次性配对码与证书指纹。二维码含一次性配对码，请勿截图外传。',
      'pairing.creating': '正在生成…',
      'devices.remote': '最近来源',
      'devices.seen': '最近活跃',
      'devices.revoke': '撤销',
      'devices.empty': '暂无已配对设备',
      'devices.unnamed': '未命名设备',
      'switches.lanRequirePairing': '局域网也要求配对令牌',
      'switches.mdns': '启用 mDNS 广播',
      'switches.restart': '该项需要重启 DSH 才会按新值生效。',
      'footnote': '局域网默认免认证意味着同一局域网内任何人都能控制本机 DSH，请仅在可信网络使用。',
      'name': 'dsh-gateway',
      'loading': '正在读取…',
      'unavailable': '网关尚未启动。',
      'error.state': '读取状态失败：{message}',
      'error.pairing': '生成配对码失败：{message}',
      'error.revoke': '撤销失败：{message}',
      'error.config': '保存失败：{message}',
      'toast.revoked': '已撤销 {name}',
      'toast.paired': '配对码已生成',
      'toast.saved': '已更新',
      'toast.restart': '已写入内存，重启 DSH 后生效',
      'duration.days': '{d} 天 {h} 小时',
      'duration.hours': '{h} 小时 {m} 分',
      'duration.minutes': '{m} 分',
    };

    const en = {
      'meta.title': 'DSH Gateway',
      'meta.description': 'Forwards this machine\'s DSH sessions to a phone, with QR pairing, device management and LAN/public switches.',
      'card.expand': 'Expand {title}',
      'card.collapse': 'Collapse {title}',
      'section.status': 'Status',
      'section.pairing': 'Pairing',
      'section.devices': 'Devices',
      'section.config': 'Switches',
      'status.dsh': 'DSH link',
      'status.lan': 'LAN HTTP',
      'status.public': 'Public HTTPS',
      'status.mdns': 'mDNS',
      'status.devices': 'Paired devices',
      'status.uptime': 'Uptime',
      'status.on': 'Connected',
      'status.off': 'Disconnected',
      'status.disabled': 'Off',
      'status.advertising': 'Advertising',
      'status.enabled': 'Enabled',
      'status.muted': 'Disabled',
      'pairing.create': 'Generate pairing code',
      'pairing.renew': 'Generate again',
      'pairing.empty': 'No pairing code yet. Generate a one-shot code that stays valid for 10 minutes.',
      'pairing.placeholder': 'The QR code will appear here',
      'pairing.code': 'Code',
      'pairing.expires': 'Valid until',
      'pairing.lan': 'LAN (plain HTTP, no certificate)',
      'pairing.public': 'Public (HTTPS)',
      'pairing.fingerprint': 'Certificate fingerprint (public only)',
      'pairing.note': 'On the same network the phone uses plain HTTP: no certificate and no device token. Only the public edge needs a one-shot code and the certificate fingerprint. The QR contains that code — do not share a screenshot.',
      'pairing.creating': 'Generating…',
      'devices.remote': 'Last source',
      'devices.seen': 'Last seen',
      'devices.revoke': 'Revoke',
      'devices.empty': 'No paired devices',
      'devices.unnamed': 'Unnamed device',
      'switches.lanRequirePairing': 'Require a device token on the LAN too',
      'switches.mdns': 'Advertise over mDNS',
      'switches.restart': 'This switch only takes effect after DSH restarts.',
      'footnote': 'The LAN edge is unauthenticated by default: anyone on this network can drive this DSH. Use it on trusted networks only.',
      'name': 'dsh-gateway',
      'loading': 'Loading…',
      'unavailable': 'The gateway is not running yet.',
      'error.state': 'Could not read the state: {message}',
      'error.pairing': 'Could not create a pairing code: {message}',
      'error.revoke': 'Could not revoke: {message}',
      'error.config': 'Could not save: {message}',
      'toast.revoked': 'Revoked {name}',
      'toast.paired': 'Pairing code created',
      'toast.saved': 'Updated',
      'toast.restart': 'Saved in memory; restart DSH to apply',
      'duration.days': '{d}d {h}h',
      'duration.hours': '{h}h {m}m',
      'duration.minutes': '{m}m',
    };

    /* ----------------------------------------------------------------- styles */

    const CSS = [
      /* ---------------------------------------------------------------- card
       * The plugin row's page is a CARD with a collapsible body.
       *
       * The row's configure control opens a detail page whose body is this
       * card; that page already carries the plugin's title, icon and one-liner
       * in its own header. The card header repeats them so the body can be
       * folded away — the shape `dsh-connect-workbuddy` settled on for the same
       * seat. The header is a real button with `aria-expanded`, so the card is
       * keyboard-operable and announces its state.
       *
       * The Settings surface does not use this card at all: it renders the
       * sections directly (see the `-page` rule below). */
      '.' + CLS + '-card{border:0.5px solid var(--dsw-alias-border-l2);border-radius:var(--dsw-radius-lg,10px);',
      'background:var(--dsw-alias-bg-layer-1);list-style:none;transition:border-color .16s,background .16s}',
      '.' + CLS + '-card:hover{border-color:var(--dsw-alias-label-tertiary)}',
      '.' + CLS + '-cardOpen{background:var(--dsw-alias-bg-layer-2)}',
      '.' + CLS + '-cardHeader{appearance:none;width:100%;font:inherit;color:inherit;text-align:left;cursor:pointer;',
      'background:transparent;border:0;border-radius:var(--dsw-radius-lg,10px);align-items:center;gap:12px;',
      'padding:14px 16px;display:flex}',
      '.' + CLS + '-cardHeader:focus-visible{outline:var(--dsw-focus-ring-width,2px) solid var(--dsw-alias-brand-primary);',
      'outline-offset:-2px}',
      '.' + CLS + '-cardIcon{width:32px;height:32px;flex:none;border-radius:7px}',
      '.' + CLS + '-cardHead{flex-direction:column;flex:1;gap:4px;min-width:0;display:flex}',
      '.' + CLS + '-cardTitle{color:var(--dsw-alias-label-primary);font-size:15px;font-weight:600;line-height:1.4}',
      '.' + CLS + '-cardDescription{color:var(--dsw-alias-label-tertiary);font-size:13px;line-height:1.5}',
      /* A pure-CSS caret: the host primitives' chevron icon names differ between
       * DSH lines, so no static import can serve every build. A border caret in
       * the plugin's own stylesheet is version-proof. */
      '.' + CLS + '-chevron{color:var(--dsw-alias-label-tertiary);flex:none;width:16px;height:16px;position:relative;',
      'transition:transform .16s}',
      '.' + CLS + '-chevron::before{content:"";display:block;position:absolute;left:4px;top:5px;width:7px;height:7px;',
      'border-right:1.6px solid currentColor;border-bottom:1.6px solid currentColor;transform:rotate(45deg)}',
      '.' + CLS + '-chevronOpen{transform:rotate(180deg)}',
      '.' + CLS + '-cardBody{border-top:0.5px solid var(--dsw-alias-border-l2);margin:0 16px;padding:0 0 12px}',
      /* ------------------------------------------------------------- sections
       * The page's four jobs, stacked in one scroll.
       *
       * There was a tab bar here. It cost more than it saved on a page this
       * short: three quarters of the content was hidden at any moment, the
       * pairing button sat behind a click, and the only hint that a device list
       * existed was a count inside a tab label. A single column shows all four
       * with no navigation to learn; the section headings keep it scannable, and
       * the rule above each one (except the first) is what separates them now
       * that no tab underline does. */
      '.' + CLS + '-sections{display:flex;flex-direction:column;gap:20px}',
      '.' + CLS + '-section{display:flex;flex-direction:column;gap:12px;min-width:0}',
      '.' + CLS + '-section+' + '.' + CLS + '-section{padding-top:20px;',
      'border-top:0.5px solid var(--dsw-alias-border-l2)}',
      '.' + CLS + '-sectionTitle{margin:0;font-size:13px;font-weight:600;line-height:20px;',
      'color:var(--dsw-alias-label-primary);display:flex;align-items:baseline;gap:6px}',
      '.' + CLS + '-sectionCount{font-weight:400;font-size:12px;line-height:18px;',
      'color:var(--dsw-alias-label-tertiary)}',
      '.' + CLS + '-panel{display:flex;flex-direction:column;gap:12px;min-width:0}',
      '.' + CLS + '-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:8px}',
      '.' + CLS + '-card{border:0.5px solid var(--dsw-alias-border-l2);border-radius:var(--dsw-radius-lg,10px);',
      'background:var(--dsw-alias-bg-layer-1);padding:10px 12px;display:flex;flex-direction:column;gap:2px}',
      '.' + CLS + '-key{color:var(--dsw-alias-label-secondary);font-size:12px;line-height:18px}',
      '.' + CLS + '-value{font-size:13px;font-weight:500;overflow-wrap:anywhere}',
      '.' + CLS + '-ok{color:var(--dsw-alias-state-success-primary)}',
      '.' + CLS + '-bad{color:var(--dsw-alias-state-error-primary)}',
      '.' + CLS + '-idle{color:var(--dsw-alias-state-idle-primary)}',
      '.' + CLS + '-pair{display:flex;gap:16px;flex-wrap:wrap;align-items:flex-start}',
      '.' + CLS + '-pairBody{flex:1;min-width:240px;display:flex;flex-direction:column;gap:8px;align-items:flex-start}',
      '.' + CLS + '-qr{flex:none;background:#fff;border-radius:var(--dsw-radius-lg,10px);padding:8px;',
      'box-sizing:border-box;width:180px;height:180px;display:flex;align-items:center;justify-content:center}',
      '.' + CLS + '-qr svg{display:block;width:100%;height:100%}',
      /* The empty state. Same 180px footprint as a real code so generating one
       * does not shift the page, but painted on the panel's own surface with a
       * dashed edge instead of the QR's white plate — a white square is
       * invisible on the light theme, which is exactly the bug this fixes. */
      '.' + CLS + '-qrEmpty{flex-direction:column;gap:6px;background:var(--dsw-alias-bg-layer-2);',
      'border:1px dashed var(--dsw-alias-border-l2);color:var(--dsw-alias-label-tertiary);padding:12px;',
      'text-align:center}',
      '.' + CLS + '-qrGhost{width:64px;height:64px;flex:none;opacity:.55}',
      '.' + CLS + '-qrHint{font-size:12px;line-height:16px;color:var(--dsw-alias-label-tertiary);',
      'overflow-wrap:anywhere}',
      '.' + CLS + '-rows{display:flex;flex-direction:column;gap:0;margin:0;padding:0;list-style:none}',
      '.' + CLS + '-row{display:flex;flex-direction:column;gap:2px;padding:8px 0;',
      'border-bottom:0.5px solid var(--dsw-alias-border-l2)}',
      '.' + CLS + '-row:last-child{border-bottom:0}',
      '.' + CLS + '-rowLine{display:flex;align-items:center;gap:12px;min-width:0}',
      '.' + CLS + '-rowMain{display:flex;flex-direction:column;flex:1;gap:2px;min-width:0}',
      '.' + CLS + '-rowName{font-weight:500;overflow-wrap:anywhere}',
      '.' + CLS + '-rowMeta{color:var(--dsw-alias-label-tertiary,var(--dsw-alias-label-secondary));font-size:12px;line-height:18px;overflow-wrap:anywhere}',
      '.' + CLS + '-mono{font-family:var(--dsw-font-mono,ui-monospace,SFMono-Regular,Menlo,monospace);font-size:12px}',
      '.' + CLS + '-kv{display:flex;flex-direction:column;gap:2px;margin:0}',
      '.' + CLS + '-kvRow{display:flex;gap:8px;align-items:baseline;flex-wrap:wrap}',
      '.' + CLS + '-kvKey{color:var(--dsw-alias-label-secondary);font-size:12px;line-height:18px;flex:none;min-width:96px}',
      '.' + CLS + '-kvValue{overflow-wrap:anywhere;min-width:0}',
      '.' + CLS + '-uri{border:0.5px dashed var(--dsw-alias-border-l2);border-radius:var(--dsw-radius-md,8px);',
      'background:var(--dsw-alias-bg-layer-2);padding:6px 8px;overflow-wrap:anywhere;max-width:100%}',
      '.' + CLS + '-button{border:0.5px solid var(--dsw-alias-border-l2);border-radius:var(--dsw-radius-md,8px);',
      'background:var(--dsw-alias-bg-layer-2);color:var(--dsw-alias-label-primary);font:inherit;font-size:13px;',
      'line-height:20px;padding:5px 12px;cursor:pointer}',
      '.' + CLS + '-button:hover:not(:disabled){background:var(--dsw-alias-bg-layer-3,var(--dsw-alias-bg-layer-2))}',
      '.' + CLS + '-button:disabled{opacity:.55;cursor:default}',
      '.' + CLS + '-button:focus-visible{outline:var(--dsw-focus-ring-width,2px) solid var(--dsw-alias-state-business-primary);outline-offset:1px}',
      '.' + CLS + '-danger{color:var(--dsw-alias-state-error-primary);border-color:var(--dsw-alias-state-error-primary)}',
      '.' + CLS + '-switch{display:flex;align-items:flex-start;gap:10px;cursor:pointer;padding:4px 0}',
      '.' + CLS + '-switch input{width:16px;height:16px;margin:2px 0 0;flex:none;accent-color:var(--dsw-alias-brand-primary)}',
      '.' + CLS + '-switchText{display:flex;flex-direction:column;gap:2px;min-width:0}',
      '.' + CLS + '-hint{color:var(--dsw-alias-label-secondary);font-size:12px;line-height:18px;margin:0}',
      '.' + CLS + '-warn{border-left:2px solid var(--dsw-alias-state-warn-primary);border-radius:0 var(--dsw-radius-md,8px) var(--dsw-radius-md,8px) 0;',
      'background:color-mix(in srgb,var(--dsw-alias-state-warn-primary) 10%,transparent);padding:8px 10px;margin:0;',
      'color:var(--dsw-alias-label-secondary);font-size:12px;line-height:18px}',
      '.' + CLS + '-status{margin:0;color:var(--dsw-alias-label-secondary);font-size:12px;line-height:18px}',
      '.' + CLS + '-statusError{margin:0;color:var(--dsw-alias-state-error-primary);font-size:12px;line-height:18px}',
      /* The Settings-dialog surface.
       *
       * The shell hands a section a scrollable content column and draws the
       * dialog title itself, so this page supplies only its own body — no card
       * border, no header, no collapse — and starts with the first section
       * heading.
       *
       * `min-width:0` matters here: the column is a flex child, and a long
       * device id or `dshgw://` string would otherwise stretch it past the
       * dialog instead of wrapping. */
      '.' + CLS + '-page{display:flex;flex-direction:column;gap:12px;min-width:0;',
      'color:var(--dsw-alias-label-primary);font-size:13px;line-height:20px}',
      /* The nav glyph the shell renders through `settings.section.icon`. It is
       * an inline <svg>, so it inherits the nav row's currentColor for its
       * frame and only needs the size and radius the shell's other icons use. */
      '.' + CLS + '-navIcon{display:block;border-radius:4px;flex:none}',
    ].join('');

    /** Insert (or refresh) the page stylesheet. */
    function installStyle() {
      if (typeof document === 'undefined' || document.head === null) return () => {};
      const existing = document.querySelector('style[data-plugin-css="' + STYLE_ID + '"]');
      if (existing !== null) {
        if (existing.textContent !== CSS) existing.textContent = CSS;
        document.head.appendChild(existing);
        return () => {
          existing.remove();
        };
      }
      const tag = document.createElement('style');
      tag.dataset.plugin = 'dsh-gateway';
      tag.dataset.pluginCss = STYLE_ID;
      tag.textContent = CSS;
      document.head.appendChild(tag);
      return () => {
        tag.remove();
      };
    }

    /* ------------------------------------------------------------------ view */

    /**
     * One `{key, value}` card.
     * @param {string} key - localized label.
     * @param {string} value - display value.
     * @param {string} [tone] - `ok` | `bad` | `idle`.
     */
    function Card(key, value, tone) {
      return h('div', { className: CLS + '-card', key },
        h('span', { className: CLS + '-key' }, key),
        h('span', { className: CLS + '-value' + (tone === undefined ? '' : ' ' + CLS + '-' + tone) }, value));
    }

    /**
     * Draw one QR matrix as SVG: run-length merged rects, one per dark run, so a
     * version-10 code costs a few hundred nodes instead of a few thousand.
     *
     * The matrix arrives base64 encoded from the Host — one byte per module
     * (`1` dark, `0` light) in row-major order — so no QR library reaches the
     * browser and no CSP `img-src` rule can blank the code.
     *
     * With no code yet it still renders a box of the SAME size. That empty
     * state used to be a bare white `<div>`: on the light theme a white square
     * on a white panel is invisible, so nothing told the reader where the code
     * would appear or that the area was theirs to fill. The placeholder keeps
     * the footprint (so generating a code does not shift the layout under the
     * cursor) and wears a dashed edge on the panel's own surface, which reads as
     * an empty slot in both themes.
     */
    function QrCode({ qr, label, placeholder }) {
      const matrix = React.useMemo(() => {
        if (qr === null || qr === undefined) return { size: 0, runs: [] };
        const size = Number(qr.size);
        if (!Number.isFinite(size) || size <= 0) return { size: 0, runs: [] };
        let modules;
        try {
          modules = atob(String(qr.data));
        } catch {
          return { size: 0, runs: [] };
        }
        if (modules.length < size * size) return { size: 0, runs: [] };
        const dark = (index) => modules.charCodeAt(index) !== 0;
        const runs = [];
        for (let y = 0; y < size; y += 1) {
          let x = 0;
          while (x < size) {
            if (!dark(y * size + x)) {
              x += 1;
              continue;
            }
            const start = x;
            while (x < size && dark(y * size + x)) x += 1;
            runs.push({ x: start, y, width: x - start });
          }
        }
        return { size, runs };
      }, [qr]);

      if (matrix.size === 0) {
        return h('div', {
          className: CLS + '-qr ' + CLS + '-qrEmpty',
          role: 'img',
          'aria-label': placeholder === undefined ? label : placeholder,
        },
        /* A faint QR-ish glyph: three finder squares and a few dots. Decorative
         * only — it says "a code goes here" without pretending to be scannable,
         * and it is drawn on the theme's own colours so it reads in both. */
        h('svg', {
          viewBox: '0 0 32 32',
          'aria-hidden': 'true',
          focusable: 'false',
          className: CLS + '-qrGhost',
        },
        h('rect', { x: 2, y: 2, width: 8, height: 8, rx: 1, fill: 'none', stroke: 'currentColor', strokeWidth: 1.6 }),
        h('rect', { x: 22, y: 2, width: 8, height: 8, rx: 1, fill: 'none', stroke: 'currentColor', strokeWidth: 1.6 }),
        h('rect', { x: 2, y: 22, width: 8, height: 8, rx: 1, fill: 'none', stroke: 'currentColor', strokeWidth: 1.6 }),
        h('rect', { x: 13, y: 13, width: 3, height: 3, fill: 'currentColor' }),
        h('rect', { x: 19, y: 13, width: 3, height: 3, fill: 'currentColor' }),
        h('rect', { x: 13, y: 19, width: 3, height: 3, fill: 'currentColor' }),
        h('rect', { x: 22, y: 22, width: 8, height: 8, rx: 1, fill: 'none', stroke: 'currentColor', strokeWidth: 1.6 })),
        h('span', { className: CLS + '-qrHint' }, placeholder === undefined ? label : placeholder));
      }
      return h('div', { className: CLS + '-qr', role: 'img', 'aria-label': label },
        h('svg', {
          viewBox: '0 0 ' + matrix.size + ' ' + matrix.size,
          shapeRendering: 'crispEdges',
          focusable: 'false',
          'aria-hidden': 'true',
        }, matrix.runs.map((run, index) => h('rect', {
          key: index,
          x: run.x,
          y: run.y,
          width: run.width,
          height: 1,
          fill: '#0b1220',
        }))));
    }

    /** Format one timestamp as `YYYY-MM-DD HH:MM` in the browser's zone. */
    function formatTime(ms) {
      if (typeof ms !== 'number' || ms <= 0) return '—';
      const date = new Date(ms);
      const pad = (value) => (value < 10 ? '0' : '') + value;
      return date.getFullYear() + '-' + pad(date.getMonth() + 1) + '-' + pad(date.getDate())
        + ' ' + pad(date.getHours()) + ':' + pad(date.getMinutes());
    }

    /** Format one duration in milliseconds using the page's own units. */
    function formatDuration(ms, t) {
      if (typeof ms !== 'number' || ms <= 0) return '—';
      const total = Math.floor(ms / 1000);
      const days = Math.floor(total / 86400);
      const hours = Math.floor((total % 86400) / 3600);
      const minutes = Math.floor((total % 3600) / 60);
      if (days > 0) return t('duration.days', { d: String(days), h: String(hours) });
      if (hours > 0) return t('duration.hours', { h: String(hours), m: String(minutes) });
      return t('duration.minutes', { m: String(minutes) });
    }

    /** Read one JSON envelope from the panel API, throwing the Host's message. */
    async function call(path, options) {
      const response = await fetch(API + path, Object.assign({
        credentials: 'same-origin',
        headers: { 'content-type': 'application/json' },
      }, options ?? {}));
      let body = null;
      try {
        body = await response.json();
      } catch {
        body = null;
      }
      if (!response.ok || body === null || body.ok === false) {
        const message = body !== null && body.error !== undefined && typeof body.error.message === 'string'
          ? body.error.message
          : 'HTTP ' + response.status;
        throw new Error(message);
      }
      return body;
    }

    /**
     * The plugin's own glyph, drawn inline.
     *
     * Inline rather than an `<img src=…>` of the packaged `icon.svg`: the card
     * is a control surface, and a host whose `img-src` policy excludes the
     * plugin's resource route would blank the artwork out of the header. The
     * shapes are the ones `icon.svg` ships, so the two stay one mark.
     */
    function GatewayIcon({ className, size }) {
      return h('svg', {
        className,
        viewBox: '0 0 32 32',
        width: size ?? 32,
        height: size ?? 32,
        role: 'img',
        'aria-hidden': 'true',
        focusable: 'false',
      },
      h('rect', { x: 1, y: 1, width: 30, height: 30, rx: 8, fill: '#0f766e' }),
      h('rect', { x: 9, y: 4.5, width: 14, height: 23, rx: 3.5, fill: '#ffffff', opacity: 0.96 }),
      h('rect', { x: 11.5, y: 8, width: 9, height: 8, rx: 1.6, fill: '#0f766e' }),
      h('rect', { x: 12.6, y: 9.1, width: 2, height: 2, fill: '#ffffff' }),
      h('rect', { x: 16.2, y: 9.1, width: 2, height: 2, fill: '#ffffff' }),
      h('rect', { x: 16.2, y: 12.4, width: 2, height: 2, fill: '#ffffff' }),
      h('rect', { x: 12.6, y: 12.4, width: 2, height: 2, fill: '#ffffff', opacity: 0.55 }),
      h('rect', { x: 12.5, y: 19, width: 9, height: 1.9, rx: 0.95, fill: '#0f766e', opacity: 0.85 }),
      h('rect', { x: 12.5, y: 22.2, width: 5.5, height: 1.9, rx: 0.95, fill: '#0f766e', opacity: 0.5 }));
    }

    /**
     * The gateway settings page.
     *
     * Three surfaces ask this one component for its content, and they differ
     * only in what the host already drew around it:
     *
     *   - `surface: 'settings'` — a page of the Settings dialog (`settings.section`).
     *     The shell owns the dialog chrome (title, close, scroll container), so
     *     the page contributes no header and no collapse: the reader already
     *     arrived here on purpose.
     *   - the plugin row's page (`plugins.row.config`, `view: 'page'`) — a card
     *     with its own header, open by default.
     *   - the row's one-liner (`view: 'summary'`) — just the sentence.
     *
     * @param {{view?: 'summary'|'page', surface?: string, t: Function}} props - slot props.
     */
    function GatewayPanel(props) {
      const t = props.t;
      const settingsSurface = props.surface === 'settings';
      const page = settingsSurface || props.view !== 'summary';
      /* Collapsed card, open body — the shape the app's own plugin list uses.
       * Both full-page seats start OPEN: a Settings page has no card header to
       * click (the shell's own title sits above it) and the plugin row's page
       * has just been opened on purpose, so an extra click would only bury the
       * content behind a control that repeats what the reader already knows. */
      const [open, setOpen] = React.useState(page);
      const [state, setState] = React.useState(null);
      const [error, setError] = React.useState('');
      const [busy, setBusy] = React.useState('');
      const [notice, setNotice] = React.useState('');

      /* The snapshot also lives in a ref: `refresh` must keep one identity for
       * the polling effect, so it cannot close over `state`, and it still has
       * to know whether a snapshot is already on screen when a poll fails. */
      const latest = React.useRef(null);
      const noticeTimer = React.useRef(null);

      React.useEffect(() => () => {
        if (noticeTimer.current !== null) clearTimeout(noticeTimer.current);
      }, []);

      /** Adopt one snapshot from either the poller or a write response. */
      const adopt = React.useCallback((next) => {
        latest.current = next;
        setState(next);
        setError('');
      }, []);

      /** Refresh the snapshot; silent on failure once one is on screen. */
      const refresh = React.useCallback(async (loud) => {
        try {
          adopt(await call('/state'));
        } catch (cause) {
          if (loud === true || latest.current === null) {
            setError(t('error.state', { message: cause.message }));
          }
        }
      }, [adopt, t]);

      React.useEffect(() => {
        if (!open) return undefined;
        let live = true;
        void refresh(true);
        const timer = setInterval(() => {
          if (live) void refresh(false);
        }, POLL_MS);
        return () => {
          live = false;
          clearInterval(timer);
        };
      }, [open, refresh]);

      const config = (state === null ? null : state.config) ?? {};
      const mdns = (state === null ? null : state.mdns) ?? {};
      const dsh = (state === null ? null : state.dsh) ?? {};
      const devices = state !== null && Array.isArray(state.devices) ? state.devices : [];
      const pairing = state === null ? null : state.pairing ?? null;

      const announce = (text) => {
        setNotice(text);
        if (noticeTimer.current !== null) clearTimeout(noticeTimer.current);
        noticeTimer.current = setTimeout(() => {
          noticeTimer.current = null;
          setNotice('');
        }, 2600);
      };

      const createPairing = async () => {
        if (state === null) return;
        setBusy('pairing');
        try {
          const body = await call('/pairing', { method: 'POST', body: '{}' });
          adopt(Object.assign({}, latest.current ?? state, { pairing: body.pairing }));
          announce(t('toast.paired'));
        } catch (cause) {
          announce(t('error.pairing', { message: cause.message }));
        } finally {
          setBusy('');
        }
      };

      const revoke = async (device) => {
        const name = typeof device.name === 'string' && device.name !== '' ? device.name : t('devices.unnamed');
        setBusy(device.deviceId);
        try {
          await call('/devices?deviceId=' + encodeURIComponent(device.deviceId), { method: 'DELETE' });
          announce(t('toast.revoked', { name }));
          await refresh(false);
        } catch (cause) {
          announce(t('error.revoke', { message: cause.message }));
        } finally {
          setBusy('');
        }
      };

      const patchConfig = async (key, value) => {
        setBusy('config');
        try {
          const body = await call('/config', {
            method: 'PATCH',
            body: JSON.stringify({ key, value }),
          });
          announce(body.restartRequired === true ? t('toast.restart') : t('toast.saved'));
          await refresh(false);
        } catch (cause) {
          announce(t('error.config', { message: cause.message }));
          await refresh(false);
        } finally {
          setBusy('');
        }
      };

      /* The row's one-liner in the plugin list. Deliberately plain text and not
       * the card: the host already drew the package's icon, title and this very
       * sentence a line above, so a card here would be a header repeating a
       * header — and it would nest a control inside a description paragraph. */
      if (!page) {
        return h(React.Fragment, null, t('meta.description'));
      }

      const title = t('meta.title');
      const header = h('button', {
        type: 'button',
        className: CLS + '-cardHeader',
        'aria-expanded': open,
        'aria-label': t(open ? 'card.collapse' : 'card.expand', { title }),
        onClick: () => setOpen(!open),
      },
      h(GatewayIcon, { className: CLS + '-cardIcon' }),
      h('span', { className: CLS + '-cardHead' },
        h('span', { className: CLS + '-cardTitle' }, title),
        h('span', { className: CLS + '-cardDescription' }, t('meta.description'))),
      h('span', {
        'aria-hidden': 'true',
        className: CLS + '-chevron' + (open ? ' ' + CLS + '-chevronOpen' : ''),
      }));

      /* ------------------------------------------------------------ body */

      const bodyOf = () => {
        if (!open) return null;
        if (state === null) {
          return h('div', { className: CLS + '-panel' },
            h('p', { className: error === '' ? CLS + '-status' : CLS + '-statusError', role: 'status' },
              error === '' ? t('loading') : error));
        }

        const pairingRows = pairing === null ? [] : [
          [t('pairing.code'), h('span', { className: CLS + '-mono' }, pairing.code)],
          [t('pairing.expires'), formatTime(pairing.expiresAt)],
          [t('pairing.lan'), pairing.lanUrl === null ? '—' : pairing.lanUrl],
          [t('pairing.public'), pairing.publicUrl === null ? '—' : pairing.publicUrl],
          [t('pairing.fingerprint'), state.fingerprintPretty === '' || state.fingerprintPretty === undefined
            ? '—'
            : h('span', { className: CLS + '-mono' }, state.fingerprintPretty)],
        ];

        const statusPanel = h('div', { className: CLS + '-panel' },
          h('div', { className: CLS + '-grid' },
            Card(t('status.dsh'), dsh.connected === true ? t('status.on') : t('status.off'),
              dsh.connected === true ? 'ok' : 'bad'),
            Card(t('status.lan'), state.ports.lan > 0 ? '0.0.0.0:' + state.ports.lan : t('status.disabled'),
              state.ports.lan > 0 ? 'ok' : 'idle'),
            Card(t('status.public'), state.ports.public > 0 ? '0.0.0.0:' + state.ports.public : t('status.disabled'),
              state.ports.public > 0 ? 'ok' : 'idle'),
            Card(t('status.mdns'),
              mdns.enabled === true ? (mdns.active === true ? t('status.advertising') : t('status.enabled')) : t('status.muted'),
              mdns.active === true ? 'ok' : 'idle'),
            Card(t('status.devices'), String(devices.length)),
            Card(t('status.uptime'), formatDuration(state.uptimeMs, t))),
          h('p', { className: CLS + '-hint' }, state.name || t('name')));

        const pairingDetails = pairing === null
          ? h('p', { className: CLS + '-status' }, t('pairing.empty'))
          : h('div', { className: CLS + '-kv' }, pairingRows.map((row, index) => h('div', {
            className: CLS + '-kvRow',
            key: 'pair-' + index,
          },
          h('span', { className: CLS + '-kvKey' }, row[0]),
          h('span', { className: CLS + '-kvValue' }, row[1]))));

        const pairingActions = h('div', { className: CLS + '-pairBody' },
          h('button', {
            type: 'button',
            className: CLS + '-button',
            disabled: busy === 'pairing',
            onClick: () => void createPairing(),
          }, busy === 'pairing' ? t('pairing.creating') : (pairing === null ? t('pairing.create') : t('pairing.renew'))),
          pairingDetails,
          pairing === null ? null : h('span', { className: CLS + '-uri ' + CLS + '-mono' }, pairing.uri));

        const pairingPanel = h('div', { className: CLS + '-panel' },
          h('div', { className: CLS + '-pair' },
            h(QrCode, {
              qr: pairing === null ? null : pairing.qr,
              label: pairing === null ? t('pairing.empty') : t('pairing.code'),
              placeholder: t('pairing.placeholder'),
            }),
            pairingActions),
          h('p', { className: CLS + '-hint' }, t('pairing.note')));

        const devicesPanel = h('div', { className: CLS + '-panel' },
          devices.length === 0
            ? h('p', { className: CLS + '-status' }, t('devices.empty'))
            : h('ul', { className: CLS + '-rows' }, devices.map((device) => h('li', {
              className: CLS + '-row',
              key: device.deviceId,
            },
            h('div', { className: CLS + '-rowLine' },
              h('div', { className: CLS + '-rowMain' },
                h('span', { className: CLS + '-rowName' },
                  typeof device.name === 'string' && device.name !== '' ? device.name : t('devices.unnamed')),
                h('span', { className: CLS + '-rowMeta ' + CLS + '-mono' }, device.deviceId),
                h('span', { className: CLS + '-rowMeta' },
                  t('devices.remote') + ' ' + (typeof device.remoteAddress === 'string' && device.remoteAddress !== '' ? device.remoteAddress : '—')
                  + ' · ' + t('devices.seen') + ' ' + formatTime(device.lastSeen))),
              h('button', {
                type: 'button',
                className: CLS + '-button ' + CLS + '-danger',
                disabled: busy === device.deviceId,
                onClick: () => void revoke(device),
              }, t('devices.revoke')))))));

        const configPanel = h('div', { className: CLS + '-panel' },
          h('label', { className: CLS + '-switch' },
            h('input', {
              type: 'checkbox',
              checked: config.lanRequirePairing === true,
              disabled: busy === 'config',
              onChange: (event) => void patchConfig('lanRequirePairing', event.target.checked),
            }),
            h('span', { className: CLS + '-switchText' },
              h('span', null, t('switches.lanRequirePairing')),
              h('span', { className: CLS + '-hint' }, 'lanRequirePairing'))),
          h('label', { className: CLS + '-switch' },
            h('input', {
              type: 'checkbox',
              checked: config.mdns === true,
              disabled: busy === 'config',
              onChange: (event) => void patchConfig('mdns', event.target.checked),
            }),
            h('span', { className: CLS + '-switchText' },
              h('span', null, t('switches.mdns')),
              h('span', { className: CLS + '-hint' }, 'mdns · ' + t('switches.restart')))),
          h('p', { className: CLS + '-warn' }, t('footnote')));

        /* Every section, top to bottom, in one scroll.
         *
         * There used to be a tab bar here. Four tabs for a page this short cost
         * more than they saved: three of the four were hidden at any moment, the
         * pairing button sat behind a click, and the device count in a tab label
         * was the only thing telling a reader that a list existed at all. A
         * single column shows the same content with no navigation to learn, and
         * the section headings keep it scannable. */
        return h('div', { className: CLS + '-sections' },
          h('section', { className: CLS + '-section' },
            h('h4', { className: CLS + '-sectionTitle' }, t('section.status')),
            statusPanel),
          h('section', { className: CLS + '-section' },
            h('h4', { className: CLS + '-sectionTitle' }, t('section.pairing')),
            pairingPanel),
          h('section', { className: CLS + '-section' },
            h('h4', { className: CLS + '-sectionTitle' },
              t('section.devices'),
              h('span', { className: CLS + '-sectionCount' }, String(devices.length))),
            devicesPanel),
          h('section', { className: CLS + '-section' },
            h('h4', { className: CLS + '-sectionTitle' }, t('section.config')),
            configPanel),
          notice === '' ? null : h('p', { className: CLS + '-status', role: 'status' }, notice),
          /* A failed poll keeps the last good snapshot on screen and says so
           * once, instead of replacing everything with an error page. */
          error === '' ? null : h('p', { className: CLS + '-statusError', role: 'alert' }, error));
      };

      /* The Settings dialog already supplies a page title, a close control and
       * the scroll container; a card header here would repeat the title and
       * draw a second frame inside the shell's own. So that surface renders the
       * body alone. */
      if (settingsSurface) {
        return h('div', { className: CLS + '-page' }, bodyOf());
      }

      return h('div', { className: CLS + '-card' + (open ? ' ' + CLS + '-cardOpen' : '') },
        header,
        h('div', { className: CLS + '-cardBody', hidden: !open }, bodyOf()));
    }

    /* ------------------------------------------------------------------ apply */

    /** Services this entry needs before it applies. */
    const inject = ['slots', 'locale'];

    /** Nav id this plugin's page claims inside the Settings dialog. */
    const SECTION_ID = 'dsh-gateway';

    /**
     * Register the dictionaries, the stylesheet and the settings page.
     * @param ctx - browser plugin context.
     */
    function apply(ctx) {
      try {
        ctx.effect(installStyle, 'dsh-gateway: panel stylesheet');
        ctx.effect(() => ctx.locale.register(NS, { zh, en }), 'dsh-gateway: dictionaries');
        const t = ctx.locale.bind(NS);
        /* The page lives in the Settings dialog (sidebar foot → Settings), as
         * one nav row beside General / Models / Plugins — the destination the
         * operator opened this repo's request for.
         *
         * `ctx.slots.inject(name, …)` IS the capability probe: it resolves when
         * the slot is declared and otherwise stays queued, so a host without
         * `settings.section` costs one no-op instead of a registration that
         * throws. No version check: a version string turns "not supported here"
         * into "never registers anywhere".
         *
         * The page and its nav glyph register from the same callback because the
         * shell renders `settings.section.icon` with `{ only: row.id }` — the
         * glyph's cell only exists while this page's id does, and one fiber
         * disposes both.
         *
         * `surface: 'settings'` is load-bearing: `GatewayPanel` drops its own
         * card header on that surface (the shell draws the title and close
         * control) and renders its four sections as the page body. */
        ctx.slots.inject('settings.section', function* registerSection() {
          yield ctx.slots.register({
            name: 'settings.section',
            id: SECTION_ID,
            order: 35,
            label: () => t('meta.title'),
            locale: NS,
            inject: () => ({ t, surface: 'settings' }),
          }, GatewayPanel);
          yield ctx.slots.register({
            name: 'settings.section.icon',
            id: SECTION_ID,
            locale: NS,
          }, (iconProps) => h(GatewayIcon, {
            className: CLS + '-navIcon',
            size: typeof iconProps?.size === 'number' ? iconProps.size : 16,
          }));
        });
        /* The plugin row's own page keeps the same component: the Plugins list
         * is where a reader who does not know about Settings will look, and
         * dropping the seat there would send them hunting. That surface passes
         * `view: 'page'` and no `surface`, so the card header comes back. */
        ctx.slots.inject('plugins.row.config', function* registerRows() {
          for (const key of ['dsh-gateway', 'include:dsh-gateway']) {
            yield ctx.slots.register({
              name: 'plugins.row.config',
              key: key + '#' + key,
              priority: 30,
              locale: NS,
            }, GatewayPanel);
          }
        });
      } catch (error) {
        /* A browser entry that throws is fatal at boot: never fail the page. */
        console.warn('[dsh-gateway] panel did not apply:', error);
      }
    }

    return { inject, apply };
  },
});
