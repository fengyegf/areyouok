# dsh-gateway

DeepSeek Harness（DSH）局域网/公网转发网关插件：把本机 DSH 的**会话、消息、审批/提问能力**以稳定的 `/v1` 协议暴露给手机 APK，并自带 mDNS 发现、扫码配对、设备令牌、事件补发，以及内置在「插件 → 已安装插件」里的配置页。

- 设计文档：`docs/dsh-gateway-handoff.md`
- 实现契约：`docs/dsh-gateway-impl-plan.md`
- 客户端：本仓库 `app/`（Kotlin + Compose，APK）

```
DSH 桌面 App (127.0.0.1:随机端口)
   ▲  进程内 launch token 换 Cookie → 回环 HTTP /api + WS /api/remote.mux
   │
dsh-gateway 插件（跑在 DSH 进程里）
   · 自建监听：LAN 7788(HTTP) / 公网 7789(HTTPS)
   · /v1 REST + /v1/stream WS，事件 ring/seq/补发，审批跨断线
   · mDNS 广播 _dshgw._tcp
   · 配置页（lib/client.js）→ 设置面板 → DSH 网关
   · 配置页后端（lib/panel-routes.js）→ 宿主 webServer: /plugins/dsh-gateway/*
   ▲
APK
```

## ⚠️ 安全警告（必读）

**默认配置下，同一局域网内任何设备都能完全控制本机 DSH，包括执行工具和读写文件。** 这是设计上的取舍（交接文档 D6）。请仅在可信网络中启用；需要更严格策略时把 `lanRequirePairing` 设为 `true`，或关闭 `httpPort`（设为 `0`）只保留公网 HTTPS 端口。

公网端口（`httpsPort`）始终要求扫码获得的设备令牌；插件只转发白名单 API，不暴露设置/凭据/终端/插件/文件。

## 安装

### 前置

- DSH `>= 0.2.2-rc.1 < 0.3.0-0`（桌面版）。
- Node.js 依赖安装（本包为 `link:` 安装时自带 `node_modules`）：

```bash
cd dsh-gateway
npm install
```

### 方式一：`dsh plugin` CLI（推荐，若可用）

```bash
dsh plugin --profile desktop add link:/absolute/path/to/areyouok/dsh-gateway
```

### 方式二：手工挂载 profile

1. 编辑 `%USERPROFILE%\.dsh\profiles\desktop\package.json`：
   - `dependencies` 增加 `"dsh-gateway": "link:/absolute/path/to/areyouok/dsh-gateway"`
   - `dsh.profile.bundles` 数组追加 `"dsh-gateway"`
2. 在 profile 目录执行（使用 DSH 自带 pnpm）：

```bash
cd %USERPROFILE%\.dsh\profiles\desktop
pnpm install
```

3. **完全退出并重启 DeepSeek Harness**（插件随进程加载；当前会话会结束）。

> 验证：DSH 日志出现 `[dsh-gateway] listening lan=7788 public=7789`；侧边栏底部「设置」→ 左侧「DSH 网关」能打开配置页并看到运行状态；`curl http://127.0.0.1:7788/v1/health` 返回 `{"ok":true,...}`。

### 卸载

从 profile `package.json` 的 `dependencies` 与 `dsh.profile.bundles` 中移除 `dsh-gateway`，`pnpm install` 后重启 DSH。

## 配置

在 profile 的 `cordis.patch.yml`（或插件配置层）里覆盖：

```yaml
- id: dsh-gateway
  name: dsh-gateway
  config:
    httpPort: 7788
    httpsPort: 7789
    mdns: true
    lanRequirePairing: false
    maxDevices: 4
    ringSize: 2000
```

| 字段 | 默认 | 说明 |
|---|---|---|
| `httpPort` | 7788 | 局域网 HTTP；`0` 关闭 |
| `httpsPort` | 7789 | 公网 HTTPS；`0` 关闭 |
| `host` | `0.0.0.0` | 监听地址 |
| `mdns` | true | 广播 `_dshgw._tcp` |
| `lanRequirePairing` | false | 局域网是否也要求设备令牌 |
| `publicUrl` | 空 | 手动指定公网基址（默认自动枚举全局 IPv6；代理 TUN / 保留段的虚拟地址不会被广播） |
| `qrEmbedCertificate` | false | 公网配对二维码是否内嵌整张证书（默认只带 SHA-256 指纹，码更疏、更好扫） |
| `maxDevices` | 4 | 同时在线设备上限 |
| `approvalTtlMs` | 1800000 | 待审批在插件侧保留时长 |
| `ringSize` | 2000 | 事件 ring 条数 |
| `heartbeatMs` | 20000 | WS 心跳间隔 |
| `idleTimeoutMs` | 45000 | WS 判死阈值 |
| `name` | 主机名 | 网关显示名（mDNS/health 用） |
| `translateEnabled` | true | 思考链翻译（`/v1/translate`）总开关 |
| `translateProvider` | `bing` | 默认翻译提供方：`bing` / `google` |
| `translateTimeoutMs` | 10000 | 单次翻译超时（毫秒，含请求与读响应） |
| `translateMaxTextChars` | 4000 | 单次翻译文本上限（UTF-16 码元） |
| `translateMaxResponseBytes` | 1048576 | 翻译响应正文上限（字节） |
| `translateCacheMaxEntries` | 500 | 服务端翻译缓存条目上限（LRU 淘汰） |

> `lanRequirePairing` 与 `mdns` 也可以在配置页里直接切换，但那只改内存里的运行时值（`mdns` 还需重启 DSH 才换广告器）；要长期生效仍请写进上面的 `config`。

## `/v1` 接口

完整契约见 `docs/dsh-gateway-handoff.md` §6 与 `docs/dsh-gateway-impl-plan.md` §4。摘要：

```
GET  /v1/health                         免认证（仅私网/回环）
POST /v1/pair                           扫码配对换设备令牌
GET  /v1/workspaces                     项目列表
GET  /v1/sessions?workspaceId=          会话列表（默认过滤 subagent；?includeSubagents=1 看全部）
POST /v1/sessions                       新建会话
GET  /v1/sessions/{id}/state            运行/待输入状态
GET  /v1/sessions/{id}/messages         历史消息分页
POST /v1/sessions/{id}/messages         发送消息 {text?,mode:"queue"|"steer",files?:[receiptId | {receiptId}]}
POST /v1/sessions/{id}/files?name=&mediaType=
                                        上传一个文件（application/octet-stream），换回可被
                                        下一条消息引用的回执；带 mediaType=image/png 时按图片入库
GET  /v1/sessions/{id}/attachments/{attId}?mediaType=&width=&height=&bytes=&name=
                                        按需预览一张会话图片（宿主存储，不落盘）
GET  /v1/sessions/{id}/image?path=      按需预览 PC 上的一张图片文件（绝对路径或相对会话目录，仅图片扩展名）
POST /v1/sessions/{id}/interrupt        打断
POST /v1/sessions/{id}/model            切换模型
GET  /v1/models                         模型目录
GET  /v1/interventions?status=pending   待审批/提问
POST /v1/interventions/{eventId}/answer 回答
GET  /v1/events?sinceSeq=               补发兜底
GET  /v1/translate/providers            翻译提供方/目标语言/长度上限（纯配置，不联网）
POST /v1/translate                      翻译一段文本 {text,targetLanguage,sourceLanguage?,provider?}
WS   /v1/stream                         长连接（hello/sub/ack/ping）
```

鉴权：

- `7788`：回环全放行；私网放行（`lanRequirePairing=true` 时需 `Authorization: Bearer <deviceToken>`）；公网来源 403。
- `7789`：任何来源都需要设备令牌；HTTPS 自签证书。
- **配置页二维码可在「局域网 HTTP / 公网 HTTPS」两个版本间切换**：
  - 局域网版 `dshgw://pair?url=http://<ip>:7788&…` 不带证书；APK 在同一网段时不会触碰 TLS，也不需要配对码（`lanRequirePairing=true` 时仍可在 LAN 上走 HTTP 配对换令牌）。
  - 公网版 `dshgw://pair?mode=pub&pub=https://…&fp=<sha256>&…` 把公网地址**和自签证书的 SHA-256 指纹**写进二维码；APK 扫码后直接在 App 内固定这张证书（`GatewayTls`/`GatewayPins` + 自定义 `TrustManager`/`HostnameVerifier`），**不需要把证书装进 Android 系统信任库**。默认**不再**内嵌 1.4 KB 的证书本体（`cert=`）：那会把二维码从 ~57 模块撑到 ~141 模块，面板里每模块只剩约 2px，手机相机解析不出来；需要整张证书时把 `qrEmbedCertificate` 设为 `true`。
  - 公网地址只从「手机真的能拨通」的地址里挑：`198.18.0.0/15`（Clash/Mihomo 等代理 TUN 的 fake-ip 段，默认网关 `198.18.0.1`）、`240.0.0.0/4`、`169.254.0.0/16`、Teredo `2001:0::/32`、IPv6 链路本地与文档段一律不广播；全局 IPv6 优先于任何公网 IPv4；上一轮已经广播过的地址优先保留，避免网卡抖动时面板地址自己跳变。
- 网关的两个监听**不再提供任何管理页面或管理接口**：`/admin` 及其 `/admin/api/*` 已删除，只剩 `/v1`。

### 思考链翻译（`/v1/translate`）

安卓端把模型的思考链（`reasoning` / `thinking`）按段提交到这里翻译。网关调用微软 Edge 翻译或 Google 翻译的**匿名端点**，无需 API Key；同一段文本（含提供方、语言、原文）第二次请求直接命中服务端缓存，不再出网。

- 默认开启（`translateEnabled: true`），但客户端默认仍不展示译文；关掉开关后接口返回 `503 translation_unavailable`。
- 只做单段、有长度上限的翻译；长思考链由客户端自己切段。
- 原文不写日志、不落盘；缓存键是 `sha256(provider|recipe|source|target|text)`，缓存文件里只有译文。

#### `GET /v1/translate/providers`

```json
{
  "ok": true,
  "providers": [
    { "id": "bing", "label": "Bing", "free": true },
    { "id": "google", "label": "Google", "free": true }
  ],
  "defaultProvider": "bing",
  "defaultTargetLanguage": "zh-CN",
  "maxTextChars": 4000,
  "targets": ["zh-CN", "zh-TW", "en", "ja"]
}
```

该接口不联网、不依赖 DSH 连接，完全由配置生成。

#### `POST /v1/translate`

请求（`Content-Type: application/json`，字段都在 body 里）：

```json
{
  "text": "I should check the file first.",
  "targetLanguage": "zh-CN",
  "sourceLanguage": "auto",
  "provider": "bing"
}
```

- `text`：必填且非空；长度 ≤ `translateMaxTextChars`（UTF-16 码元，即 JS `string.length`）。
- `targetLanguage`：必填，必须是 `targets` 之一（大小写不敏感）。
- `sourceLanguage`：可选，缺省或 `auto` 表示自动识别；显式给值时按 BCP-47 透传。
- `provider`：可选，缺省用 `translateProvider`。

成功响应：

```json
{
  "ok": true,
  "text": "我应该先检查这个文件。",
  "provider": "bing",
  "targetLanguage": "zh-CN",
  "sourceLanguage": "auto",
  "cached": false,
  "chars": 12
}
```

- `cached`：本次是否命中服务端缓存（命中时未出网）。
- `chars`：**译文**的 UTF-16 长度（不是原文长度）。

语言映射：

| 请求语言 | Bing | Google |
|---|---|---|
| `zh` / `zh-CN` / `zh-SG` / `zh-Hans` | `zh-Hans` | `zh-CN` |
| `zh-TW` / `zh-HK` / `zh-MO` / `zh-Hant` | `zh-Hant` | `zh-TW` |
| 其它标签 | 原样透传 | 原样透传 |

错误沿用统一信封 `{"ok":false,"error":{"code","message"}}`：

| 状态 | code | 场景 |
|---|---|---|
| 400 | `invalid_request` | `text` 缺失/空白、`targetLanguage` 不在列表、`provider` 或 `sourceLanguage` 非法 |
| 413 | `text_too_long` | `text.length` 超过 `translateMaxTextChars` |
| 429 | `rate_limited` | 并发已满（4 路在跑）且排队已满（8 个等待） |
| 502 | `translation_failed` | 提供方非 2xx、响应无法解析、响应超过 `translateMaxResponseBytes`、网络失败 |
| 503 | `translation_unavailable` | `translateEnabled=false`（或服务已关闭） |
| 504 | `translation_timeout` | 超过 `translateTimeoutMs`（排队等待也算） |

安全与限制：

- `redirect: error`，绝不跟随重定向；`credentials: omit`，不带 Cookie/凭据。
- 不重试、不自动换提供方：一次请求最多一次出网。
- 单次响应正文上限 `translateMaxResponseBytes`（默认 1 MiB），超限按 `502` 处理。
- 缓存：启动时从 `<dataDir>/translate-cache.json` 加载，LRU 淘汰，写入用临时文件 + rename 原子替换；`translateCacheMaxEntries` 控制条数。
- 鉴权与其它 `/v1` 路由一致：公网口必须带设备 Bearer 令牌。
- 匿名端点是浏览器接口，可能限流或改协议；失败只会返回上面的错误码，客户端应回退到显示原文。

示例（body.json 里放上面的请求 JSON）：

```bash
curl -s http://127.0.0.1:7788/v1/translate/providers
curl -s -X POST http://127.0.0.1:7788/v1/translate -H "content-type: application/json" --data-binary @body.json
```

## 配置页（内置在设置面板里）

不再是独立网页。管理界面现在是 DSH **设置面板**里的一页，位置：

**侧边栏底部 → 设置 → 左侧导航「DSH 网关」**

（`settings.section` 槽，带 `settings.section.icon` 导航图标；同一份实现也注册到插件的行配置页 `plugins.row.config`，所以「插件 → 已安装插件 → dsh-gateway」照样能打开，两个入口看到的是同一个页面。）

页面内容（一页纵向排列，不分标签）：

- **运行状态**：DSH 连接、局域网 HTTP / 公网 HTTPS 端口、mDNS、已配对设备数、已运行时长
- **扫码配对**：生成一次性 `dshgw://pair?…` 配对码，页内直接画出二维码；顶部可在**局域网 HTTP / 公网 HTTPS**两个版本间切换（公网版把自签证书的 SHA-256 指纹写进码里，App 扫码时直接固定这张证书），10 分钟有效
- **已配对设备**：名称、设备 ID、最近来源、最近活跃、撤销
- **开关**：`lanRequirePairing`、`mdns`（内存生效；`mdns` 需重启）

四个分区在同一屏里从上到下排开，中间用一条分隔线断开，没有标签栏——内容本来就短，藏起来反而多一层要点的东西。

三种渲染面（同一个 `lib/client.js` 组件）：

| 面 | 槽位 | 表现 |
|---|---|---|
| 设置面板 | `settings.section`（`surface: 'settings'`） | 只有页面主体：标题/关闭/滚动都由设置面板提供，不再套卡片头和折叠 |
| 插件行配置页 | `plugins.row.config` | 折叠卡片（图标 + 标题 + 说明 + 展开箭头），默认展开 |
| 行摘要 | `plugins.row.config` 的 `view: 'summary'` | 只有一句话 |

实现分两半：

| 文件 | 运行位置 | 作用 |
|---|---|---|
| `lib/client.js` | 浏览器（`dsh.client`，`settings.section` + `plugins.row.config` 槽） | 页面本身：状态卡片、二维码 SVG、设备表、开关；只用 `--dsw-alias-*` 主题令牌与 `ctx.locale` 文案 |
| `lib/panel-routes.js` | Host（`ctx.webServer.register`） | 页面调用的 JSON 接口，挂在宿主服务器上，复用宿主的浏览器登录与 Host/Origin 栅栏 |
| `lib/panel.js` | Host | 纯数据：地址枚举、`dshgw://` 深链、二维码矩阵、状态快照 |

接口（全部 `{ok:true,…}` / `{ok:false,error:{code,message}}`）：

```
GET    /plugins/dsh-gateway/state           面板快照
GET    /plugins/dsh-gateway/pairing?mode=   当前配对码（无则 null；mode 选 lan/public 视图）
POST   /plugins/dsh-gateway/pairing         生成新配对码（body `{mode}`）
DELETE /plugins/dsh-gateway/devices?deviceId=  撤销设备
PATCH  /plugins/dsh-gateway/config          切换 lanRequirePairing / mdns
```

这些路由挂在**宿主的** webServer 上（而不是网关自己的 7788/7789），因此从 Web UI 的同源页面就能直接调用，且默认继承宿主的浏览器会话校验；`connection.requestRejection` 缺失时回退到回环 Host + 同源 Origin 自检，栅栏抛错一律按拒绝处理（fail-closed）。

## 开发

```bash
cd dsh-gateway
node --check lib/*.js                    # 语法检查
npm test                                 # 不依赖真实 DSH 的全链路测试（mock DSH）
node test/smoke.mjs                      # 端到端冒烟，成功打印 SMOKE OK
```

测试分层：

| 文件 | 覆盖 |
|---|---|
| `test/unit.test.mjs` | normalize / hub 纯逻辑 |
| `test/auth.test.mjs` | 来源策略、设备令牌、配对码 |
| `test/rest.test.mjs` | `/v1` REST 全链路（含「LAN 监听不再有 /admin」） |
| `test/stream.test.mjs` | WS 协议、补发、心跳 |
| `test/panel.test.mjs` | 配置页后端：路由表、状态、配对、撤销、开关、405/403、fail-closed |
| `test/client.test.mjs` | 浏览器半：模块加载契约、槽位注册、语言字典、渲染不崩 |
| `test/tls.test.mjs` | 自签证书与指纹 |
| `test/addresses.test.mjs` | 地址枚举：代理 TUN / 保留段过滤、IPv6 优先、稳定排序 |

测试体系说明见 `docs/dsh-gateway-impl-plan.md` §6；DSH 内部协议的事实以 `docs/dsh-internal-api-notes.md` 为准（含运行实例实测，wire args 与交接文档示例不同）。修改代码后需**完全重启 DSH** 生效（HMR 不可靠）。

安装脚本（默认 dry-run，不写 profile）：

```bash
node tools/install-gateway.mjs                 # 预览将写入 profile 的变更
node tools/install-gateway.mjs --apply         # 写入 profile（package.json 备份后）
node tools/install-gateway.mjs --remove --apply  # 卸载
```

## 排错

| 现象 | 排查 |
|---|---|
| `/v1/health` 不通 | 端口占用、Windows 防火墙、插件未加载（看 DSH 日志） |
| 插件里 401 | `authenticatedUrl` 换 Cookie 失败（DSH 版本变化） |
| WS 秒断 | 帧 JSON 非法 / 未回 pong / 事件循环阻塞 |
| 审批不回 | `clientId` 是否最新、`$events/result` 是否 200、eventId 是否已结算 |
| mDNS 发现不到 | 路由器多播/AP 隔离；用 APK 手输地址兜底 |
| 手机扫码打不开公网 | 检查 IPv6 前缀/防火墙放行 7789；配置页显示的公网地址是否可达 |
| 公网地址显示成 `198.18.x.x` / `198.19.x.x` | 这是代理（Clash/Mihomo）TUN 网卡的 fake-ip 段，不是公网地址：旧版把它排在 IPv6 前面写进了二维码。现在这类地址已被过滤，重启 DSH 后应只剩全局 IPv6。手机侧若也开着代理，连 `198.18.*` 会被自己的 TUN 劫持，表现成「TLS 握手失败（证书指纹不匹配？）」——那不是证书问题 |
| 公网二维码扫不出来 | 旧版公网码内嵌整张证书（~1.4 KB / 141 模块），面板里每模块约 2px，手机很难解析；现在默认只带指纹（~57 模块）。仍扫不动时先确认面板没有被缩得很窄、屏幕亮度足够 |
| 插件页一直「正在读取…」 | 宿主 `webServer` 未注册到 `/plugins/dsh-gateway/*`：看日志有无 `in-app plugin panel did not mount`；旧版 DSH 缺少 `ctx.webServer.register` 时配置页无后端 |
| 设置面板里没有「DSH 网关」 | 宿主未声明 `settings.section` 槽（旧版 DSH）；插件仍可从「插件 → 已安装插件」的行配置页进入 |
| 插件页 403 | 宿主 `connection.requestRejection` 拒绝了请求（Host/Origin 不被信任，或浏览器会话未登录）；同源打开 Web UI 再试 |

## 兼容性

- DSH `0.2.2-rc.1`（已针对该版本源码核实回环协议与配置页槽位）。
- Node.js `>= 20`（DSH 桌面运行时自带）。
- 运行时依赖：`ws`、`bonjour-service`、`selfsigned`、`ipaddr.js`、`qrcode`、`@deepseek-ai/schemastery`。
- 浏览器半不引入任何 `@deepseek-ai/dsh-client-*` 模块，只依赖 `slots` / `locale` 两个公开客户端服务与 `fetch`。

## 许可

与上层项目一致（内部项目）。
