# dsh-gateway

DeepSeek Harness（DSH）局域网/公网转发网关插件：把本机 DSH 的**会话、消息、审批/提问能力**以稳定的 `/v1` 协议暴露给手机 APK，并自带 mDNS 发现、扫码配对、设备令牌、事件补发与管理页。

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
   · mDNS 广播 _dshgw._tcp；/admin 管理页（仅回环）
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

> 验证：DSH 日志出现 `[dsh-gateway] listening lan=7788 public=7789`；浏览器打开 <http://127.0.0.1:7788/admin> 能看到管理页；`curl http://127.0.0.1:7788/v1/health` 返回 `{"ok":true,...}`。

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
| `publicUrl` | 空 | 手动指定公网基址（默认自动枚举全局 IPv6） |
| `maxDevices` | 4 | 同时在线设备上限 |
| `approvalTtlMs` | 1800000 | 待审批在插件侧保留时长 |
| `ringSize` | 2000 | 事件 ring 条数 |
| `heartbeatMs` | 20000 | WS 心跳间隔 |
| `idleTimeoutMs` | 45000 | WS 判死阈值 |
| `name` | 主机名 | 网关显示名（mDNS/health 用） |

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
POST /v1/sessions/{id}/messages         发送消息 {text,mode:"queue"|"steer"}
POST /v1/sessions/{id}/interrupt        打断
POST /v1/sessions/{id}/model            切换模型
GET  /v1/models                         模型目录
GET  /v1/interventions?status=pending   待审批/提问
POST /v1/interventions/{eventId}/answer 回答
GET  /v1/events?sinceSeq=               补发兜底
WS   /v1/stream                         长连接（hello/sub/ack/ping）
```

鉴权：

- `7788`：回环全放行；私网放行（`lanRequirePairing=true` 时需 `Authorization: Bearer <deviceToken>`）；公网来源 403。
- `7789`：任何来源都需要设备令牌；HTTPS 自签证书，配对二维码携带证书 SHA-256 指纹供 APK 固定校验。
- **局域网连接走明文 HTTP（7788），不需要证书**：mDNS 发现、管理页二维码、`dshgw://pair?lan=http://…` 都优先给局域网 HTTP 地址；APK 在同一网段时不会触碰 TLS，也不需要配对码（`lanRequirePairing=true` 时仍可在 LAN 上走 HTTP 配对换令牌）。公网 HTTPS 只在局域网不可达时使用。
- `/admin`：仅回环。

## 管理页

<http://127.0.0.1:7788/admin>（仅本机可访问）

- 网关状态：DSH 连接、监听端口、mDNS、在线设备数
- 生成配对二维码（**优先局域网 HTTP 地址** + 公网 HTTPS 回退 + 一次性 code + 证书指纹 + 主机名，10 分钟有效）
- 设备列表：名称、最近活跃、来源 IP、撤销
- 公网地址枚举结果
- 开关提示：`lanRequirePairing`、`mdns`（修改后需重启插件生效）

## 开发

```bash
cd dsh-gateway
node --check lib/*.js                    # 语法检查
npm test                                 # 不依赖真实 DSH 的全链路测试（150 用例，mock DSH）
node test/smoke.mjs                      # 端到端冒烟，成功打印 SMOKE OK
```

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
| 手机扫码打不开公网 | 检查 IPv6 前缀/防火墙放行 7789；管理页显示的公网地址是否可达 |

## 兼容性

- DSH `0.2.2-rc.1`（已针对该版本源码核实回环协议）。
- Node.js `>= 20`（DSH 桌面运行时自带）。
- 运行时依赖：`ws`、`bonjour-service`、`selfsigned`、`ipaddr.js`、`qrcode`、`@deepseek-ai/schemastery`。

## 许可

与上层项目一致（内部项目）。
