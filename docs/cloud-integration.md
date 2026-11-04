# MNBT 云开放协议 v2 对接规范（系统服务商向）

> 版本：v2（2026-11）
> 用途：本文是 MNBT APP（智能云管）「云服务器」能力的**开放对接规范**。任何云业务系统
> （智简魔方 ZJMF、WHMCS、魔方云、其他财务/云面板或自研系统）的服务商按本文实现一组
> HTTP 端点 + 一个配对二维码，即可被 APP 直接纳管，**无需 APP 侧任何改动**。
>
> `tools/mock/server.mjs` 的 `/console/v1/` 段落与 APP 侧 `CloudSystemClient` /
> `CloudSiteRepository` / `CloudServerRepository` 均按本文实现，可作参考实现。
>
> v1 时代本文是 ZJMF 专用契约；v2 起协议中的系统知识（模块路由等）已全部收归服务端，
> APP 只认协议本身。ZJMF 站点从 v1 升级到 v2 见附录 A。

---

## 1. 总则

| 项 | 约定 |
|---|---|
| 请求 URL | `{站点根}/console/v1/{路径}`。站点根由扫码/手输得到，规范化为无尾斜杠的 `http(s)://host[:port][/子目录]` |
| 鉴权 | 除 `mnbt/bind` 外一律带 `Authorization: Bearer <jwt>`（站点级设备令牌，见 §2） |
| POST 编码 | `application/x-www-form-urlencoded` |
| 防重放 | **所有 POST 请求体必须带 `_r` 字段**，值为当前毫秒时间戳字符串。服务端 3 秒内对同 IP+URL+body 的重复 POST 直接拒 400 |
| 超时 | 15 秒 |
| TLS | 系统默认 CA 信任（APP 不做证书 pinning）；生产环境强烈建议 HTTPS |
| 响应包络 | JSON `{"status": <int>, "msg": "<中文文案>", "data": <object>}` |

**包络判定规则**（APP 侧 `CloudSystemClient.unwrap` 与服务端实现必须完全一致）：

- `status === 200` → 成功，取 `data`（`data` 缺省时视为 `{}`）；
- **HTTP 401 或 body `status === 401` 都视为登录失效**（两种形态都真实存在，务必二选一并稳定）；
- 其他 → 失败：
  - 分支逻辑看 **`data.error_code`**（字符串，可能缺失）；
  - `status === 429` 时 **`data.retry_after`** 为可重试秒数；
  - `msg` 是服务端返回的中文文案，APP 直接展示。
- 防重放命中：`{status:400}`，**无 `error_code`**（`msg` 形如「重复请求被拒绝（防重放）」）。

**令牌模型**（APP 只接触一种令牌）：

- 站点级**设备令牌**：由服务端实现签发，JWT，有效期 **30 天，滑动续期**（APP 每次成功请求都会续期）；
  存于 APP 的 TokenVault（EncryptedSharedPreferences），Room 里只存引用键 `token_ref`。
- 服务商系统自己的登录态（核心 JWT、session、API key 等）只存在于服务端实现内部，自取自续，绝不下发到 APP。
- APP 收到 401 的唯一恢复手段是 `POST mnbt/refresh`（见 §5.2）。

---

## 2. 二维码格式（协议版本门槛）

云站点配对二维码内容为 UTF-8 JSON 字符串：

```json
{"app":"mnbt","type":"zjmf","proto":2,"url":"https://idc.example.com","code":"A1B2C3D4E5"}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `app` | 是 | 固定 `"mnbt"`，用于与 MNBT 主机二维码（URL 形态）区分 |
| `type` | 是 | **服务商自由填写的系统标识**（如 `zjmf`、`whmcs`、`mypanel`），APP 仅透传展示，不做业务判断 |
| `proto` | 是 | 协议版本，当前必须为 `2`。**缺失或 <2 的二维码 APP 拒绝绑定**，提示「服务商插件版本过旧」 |
| `url` | 是 | 站点根地址（含 scheme；允许带子目录，不允许尾斜杠） |
| `code` | 是 | 一次性配对码，由站长在服务端后台生成，建议 5 分钟有效、单次使用 |

手输兜底：用户填写站点地址 + 配对码，语义与扫码一致（此时无 `type`/`proto`，APP 以
`mnbt/config` 返回的 `proto` 做版本校验，见 §4）。

---

## 3. 通用行为约定

1. **401 恢复策略**：任意请求收到 401（双形态任一）→ APP 先调 `POST /console/v1/mnbt/refresh`
   换新令牌并**重试原请求一次**；refresh 也失败 → 站点标记 `NEEDS_REPAIR`，
   对外统一抛「登录已失效，请重新扫码绑定」。服务端必须保证：refresh 接口用**旧设备令牌**
   能换到新令牌（滑动续期），令牌吊销/过期后 refresh 也返回 401。
2. **协议版本校验**：`mnbt/bind` 与 `mnbt/config` 响应都带 `proto` 字段（v2 实现恒为 `2`）。
   APP 在绑定时发现 `proto < 2` 会回滚绑定并提示升级。
3. **操作密码**：`client_operate_password`，仅当 `mnbt/config` 返回
   `operate_password_required = true` 时由 UI 传入；为 null 时**不携带该字段**。
   服务端收到带密码的请求必须校验，错误返回 `{status:400, msg:"操作密码错误"}`。
4. **时间字段**：`due_time` / `active_time` 等，兼容 ISO8601 与秒/毫秒时间戳字符串，
   APP 统一转 epoch 毫秒（失败按「未知」渲染）。新实现建议直接返回 ISO8601。
5. **能力协商**：见 §6。APP 依据 `capabilities` 决定界面功能显隐，请如实声明，
   不要声明做不到的能力。

---

## 4. 错误语义汇总

| 形态 | 例子 | APP 行为 |
|---|---|---|
| 登录失效 A | HTTP 401（body 任意/空） | 触发 refresh-重试；仍失败 → 站点 `NEEDS_REPAIR` |
| 登录失效 B | HTTP 200，body `{status:401, msg:"未登录或登录已失效"}` | 同上 |
| 业务失败 | `{status:400, msg:"操作密码错误", data:{error_code:"password_error"}}` | 展示 `msg`；电源操作允许用户改密码重试 |
| 防重放 | `{status:400}`，无 `error_code` | 提示重试 |
| 限流 | `{status:429, msg:"请求过于频繁", data:{retry_after:30}}` | 展示 `msg` + 倒计时 |
| 端点缺失 | HTTP 404 或 `{status:404}` | 电源/VNC/状态统一端点 404 → APP 提示「服务商插件版本过旧，请升级到开放协议 v2」（APP 不再有任何回退路由，见附录 A） |

服务端注意：所有错误都用**中文可展示文案**填 `msg`；`error_code` 只做分支，不直接展示。

---

## 5. 端点定义（路径前缀 `/console/v1/mnbt/`）

### 5.1 POST `mnbt/bind` —— 扫码配对换设备令牌

请求（无 Bearer）：

```
POST {站点根}/console/v1/mnbt/bind
Content-Type: application/x-www-form-urlencoded

code=A1B2C3D4E5&device_name=Pixel+8&_r=1730000000000
```

| 字段 | 说明 |
|---|---|
| `code` | 二维码里的一次性配对码，必填，校验失败返回 `{status:400, msg:"配对码错误或已过期"}` |
| `device_name` | 设备显示名（服务端用于设备列表展示与吊销），可空 |
| `_r` | 防重放毫秒时间戳 |

成功 data：

```json
{
  "jwt": "eyJhbGciOi...",
  "expires_in": 2592000,
  "site_name": "某某云",
  "proto": 2,
  "capabilities": ["hard_power", "vnc", "flow", "credentials", "config_text"]
}
```

- `jwt`：设备令牌（30 天滑动续期），后续所有请求的 Bearer；
- `expires_in`：秒；
- `site_name`：站点显示名，APP 用它回填站点条目名称（缺省用 baseUrl host）；
- `proto`：协议版本（`2`）；
- `capabilities`：能力清单（§6），缺省视为全能力（兼容宽容，但新实现建议显式给出）。

配对成功即视为设备已登记（服务端记录 device_name / 首次 IP / 时间，供吊销）。

### 5.2 POST `mnbt/refresh` —— 换新设备令牌

请求（带**旧**设备令牌的 Bearer）：

```
POST {站点根}/console/v1/mnbt/refresh
Authorization: Bearer <旧 jwt>

_r=1730000000100
```

成功 data：

```json
{"jwt": "eyJhbGciOi...", "expires_in": 2592000}
```

- 旧令牌自新令牌签发起应尽快失效（滑动续期）；
- 旧令牌无效/被吊销/过期 → 返回 401（双形态任一），APP 据此把站点标记 `NEEDS_REPAIR`。

### 5.3 POST `mnbt/unbind` —— 吊销设备令牌

```
POST {站点根}/console/v1/mnbt/unbind
Authorization: Bearer <jwt>

_r=1730000000200
```

成功 data：`{}`。语义：吊销**当前 Bearer 对应的设备**令牌，之后该令牌所有请求 401。
APP 侧「解绑站点」时尽力调用（失败不阻塞本地清理），随后本地删除令牌与条目。

### 5.4 GET `mnbt/config` —— 站点配置与能力协商

```
GET {站点根}/console/v1/mnbt/config
Authorization: Bearer <jwt>
```

成功 data：

```json
{
  "site_name": "某某云",
  "operate_password_required": true,
  "proto": 2,
  "capabilities": ["hard_power", "vnc", "flow", "credentials", "config_text"]
}
```

- `operate_password_required = true`：电源操作必须带 `client_operate_password`（§5.8）；
  APP 据此显隐密码输入框。
- `proto` / `capabilities`：语义同 §5.1。APP 绑定后会拉一次 config 缓存能力，
  之后的界面显隐以此为准。

### 5.5 GET `mnbt/cloud/servers` —— 云服务器列表

```
GET {站点根}/console/v1/mnbt/cloud/servers
Authorization: Bearer <jwt>
```

成功 data：

```json
{
  "list": [
    {
      "id": 1,
      "name": "云服务器-01",
      "product_name": "云服务器 ECS·标准型",
      "status": "Active",
      "power_status": "on",
      "power_desc": "运行中",
      "dedicate_ip": "203.0.113.10",
      "due_time": "2026-11-04T23:53:32.988Z",
      "billing_cycle_name": "月付",
      "active_time": "2026-10-01T23:53:32.990Z"
    },
    {
      "id": 2, "name": "云服务器-02", "product_name": "云服务器 ECS·入门型",
      "status": "Active", "power_status": "off", "power_desc": "已关机",
      "due_time": "1793145212", "billing_cycle_name": "年付", "active_time": null
    }
  ]
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `id` | 是 | 服务器 id（服务商系统内部主键），后续所有按 id 的端点都用它（APP 侧叫 hostId） |
| `name` | 是 | 实例名 |
| `product_name` | 是 | 商品名 |
| `status` | 是 | 业务状态：`Active` / `Suspended` / `Deleted` 等原值，APP 原样展示 |
| `power_status` | 是 | `on` / `off` / `operating` / `pending` / `suspend` / `fault` / `unknown` |
| `power_desc` | 是 | 电源状态中文描述 |
| `dedicate_ip` | 否 | 独立 IP，缺失不携带该键 |
| `due_time` | 否 | 到期时间，ISO8601 或秒/毫秒时间戳字符串 |
| `billing_cycle_name` | 否 | 计费周期名 |
| `active_time` | 否 | 开通时间，格式同 `due_time` |

> v2 起列表条目**不再要求 `module` 字段**（v1 的模块直连路由已废除）。多给了 APP 也不读。

服务端实现要点：`power_status` 建议实时向底层模块/节点查询（或短 TTL 缓存），不要只读库里旧值；
无权限/未开通云功能的设备返回 401 或空 `list`。

### 5.6 GET `mnbt/cloud/server/{id}` —— 云服务器详情（兼作状态轮询源）

```
GET {站点根}/console/v1/mnbt/cloud/server/1
Authorization: Bearer <jwt>
```

成功 data（单条 `server`，字段为列表条目超集）：

```json
{
  "server": {
    "id": 1, "name": "云服务器-01", "product_name": "云服务器 ECS·标准型",
    "status": "Active",
    "power_status": "on", "power_desc": "运行中",
    "dedicate_ip": "203.0.113.10",
    "due_time": "2026-11-04T23:53:32.988Z",
    "billing_cycle_name": "月付", "active_time": "2026-10-01T23:53:32.990Z",
    "assign_ip": "203.0.113.10",
    "os": "Ubuntu 22.04 LTS",
    "addition": {"username": "root", "password": "secret", "port": 22},
    "client_notes": "备注：生产库所在机器，重启前先通知值班。",
    "config_text": "CPU: 2 vCPU\n内存: 4 GB\n磁盘: 80 GB SSD\n机房: zone-a"
  }
}
```

- `assign_ip`：分配的 IP（可与 dedicate_ip 不同）；
- `os`：操作系统名；
- `addition`：登录凭据，三子字段 `username` / `password` / `port` 均可缺省（能力 `credentials`）；
- `client_notes`：客户备注原文；
- `config_text`：**服务端预格式化**的配置文本（含换行 `\n`），APP 原样展示、不再二次组装（能力 `config_text`）。

`id` 不存在 → `{status:404, msg:"云服务器不存在"}`。

**状态轮询**：v2 不设独立 status 端点。APP 下发电源指令后轮询本端点，
以 `power_status` / `power_desc` 的变化观察执行结果。请保证本端点返回**实时**电源状态。

### 5.7 GET `mnbt/cloud/server/{id}/flow` —— 归一化流量（能力 `flow`）

```
GET {站点根}/console/v1/mnbt/cloud/server/1/flow
Authorization: Bearer <jwt>
```

成功 data：

```json
{
  "list": [
    {"name": "流量", "used": 34.2, "total": 100, "unit": "GB"},
    {"name": "带宽", "used": 5, "total": 10, "unit": "Mbps"}
  ]
}
```

- 服务端把底层口径不一的流量数据**归一化**后再下发：`used`/`total` 数值同单位，`total` 可为
  `null`（不限量），`unit` 随条目给出；
- 不支持流量的系统在 `capabilities` 里不声明 `flow`，APP 不再请求本端点。

### 5.8 POST `mnbt/cloud/server/{id}/power` —— 电源操作（必选能力）

`func` ∈ `on` | `off` | `reboot` | `hard_off` | `hard_reboot`
（`hard_off` / `hard_reboot` 为可选能力 `hard_power`；`on` / `off` / `reboot` 必须支持。）

```
POST {站点根}/console/v1/mnbt/cloud/server/1/power
Authorization: Bearer <jwt>
Content-Type: application/x-www-form-urlencoded

func=reboot&client_operate_password=123456&_r=1730000000300
```

- `client_operate_password`：仅当站点 `operate_password_required=true` 时携带；为 null 不带该字段。
- 成功 data：`{}`（HTTP 200 即成功，异步执行，APP 轮询 §5.6 观察状态变化）。
- 密码错误：`{status:400, msg:"操作密码错误"}`。
- `func` 不在支持范围（如未声明 `hard_power` 却收到 `hard_off`）：`{status:400, data:{error_code:"unsupported_func"}}`。

### 5.9 POST `mnbt/cloud/server/{id}/vnc` —— 获取 VNC 控制台地址（能力 `vnc`）

```
POST {站点根}/console/v1/mnbt/cloud/server/1/vnc
Authorization: Bearer <jwt>

_r=1730000000400
```

成功 data：

```json
{"url": "https://mock.local/vnc/session-1"}
```

- `url` **可能是相对路径**（如 `/vnc/session-1` 或 `vnc/session-1`），APP 归一化规则：
  不以 `http://`/`https://` 开头时拼接站点根（补齐必要 `/`）。建议直接下发绝对地址。
- 不支持 VNC 的系统在 `capabilities` 里不声明 `vnc`，APP 不再请求本端点。

---

## 6. 能力协商（capabilities）

`mnbt/bind` 与 `mnbt/config` 的 `data.capabilities` 是**字符串数组**，缺省（字段不存在或
`null`）时 APP 按全能力处理。声明了却做不到的，APP 不兜底。

| 能力 | 控制的 APP 功能 | 缺省（未声明数组时） |
|---|---|---|
| `hard_power` | 详情页「强制关机 / 强制重启」按钮 | 显示 |
| `vnc` | 详情页「VNC 控制台」按钮 | 显示 |
| `flow` | 详情页「流量用量」卡片与流量请求 | 显示 |
| `credentials` | 详情页「登录凭据」卡片（`addition` 字段） | 显示 |
| `config_text` | 详情页「配置信息」卡片 | 显示 |

基础电源操作（`on` / `off` / `reboot`）与列表/详情必选，不设能力位。

---

## 7. Mock 与实现对照

- Mock：`tools/mock/server.mjs` 的 `/console/v1/` 段落（`node tools/mock/server.mjs --port=8787`）。
  Bearer 必须以 `mock-jwt-` 开头；POST 校验 `_r`；电源操作密码 `123456`；
  3 台样例机覆盖 on/off/operating × ISO8601/秒级时间戳 due_time；
  `POST /__mock__/reset` 会重置云站点运行时状态。
- APP 侧：`com.mnbt.app.data.remote.api.cloud.CloudSystemClient`（传输与包络）、
  `CloudSystemModels`（DTO 与时间解析）、`com.mnbt.app.domain.repo.CloudSiteRepository` /
  `CloudServerRepository` / `CloudAuth`（401 恢复、能力缓存）、
  `com.mnbt.app.domain.model.CloudModels`（UI 消费的领域类型）。
- UI 只允许通过 `AppContainer.cloudSiteRepository` / `cloudServerRepository` 与
  `com.mnbt.app.domain.model.Cloud*` 类型消费数据层。

## 8. 服务端实现清单（任何云业务系统）

1. 配对码签发与校验（一次性、短 TTL），`mnbt/bind` 换设备 JWT（30d，含 `device_id`/`device_name` 声明）；
2. `mnbt/refresh` 滑动续期 + 旧令牌失效；`mnbt/unbind` 吊销当前设备；
3. `mnbt/config`（site_name、operate_password_required、proto=2、capabilities）；
4. `mnbt/cloud/servers` / `mnbt/cloud/server/{id}`：聚合服务器表 + 实时电源状态，字段严格按 §5.5/§5.6；
5. `mnbt/cloud/server/{id}/flow`：归一化流量，字段严格按 §5.7（能力 `flow`）；
6. `mnbt/cloud/server/{id}/power`：分发到底层电源能力，校验 `client_operate_password`；
7. `mnbt/cloud/server/{id}/vnc`：返回控制台地址（能力 `vnc`）；
8. 全局中间件：Bearer 鉴权（401 双形态之一即可，建议统一回 HTTP 401）、
   `_r` 防重放（3 秒窗口）、429 + `data.retry_after`；
9. 所有 `msg` 中文可展示；错误分支一律放 `data.error_code`。

## 附录 A：ZJMF addon v1 → v2 迁移对照

v1 契约（本文 v1 版）的 `mnbt/*` 端点在 v2 中**路径与语义全部不变**，升级工作量集中在
「废除模块直连路由」与「补协议元数据」：

| v1 | v2 | 说明 |
|---|---|---|
| 二维码无 `proto` | 二维码必须带 `"proto":2` | 无 `proto` 的码 APP 直接拒绝 |
| bind 响应 `{jwt, expires_in, site_name}` | 增加 `proto: 2` 与 `capabilities` | §5.1 |
| config 响应 `{site_name, operate_password_required}` | 增加 `proto: 2` 与 `capabilities` | §5.4 |
| `GET {module}/{id}/status`（模块探测） | **删除**，APP 轮询 `GET mnbt/cloud/server/{id}` | §5.6 |
| `POST {module}/{id}/{func}`（电源） | 改为 `POST mnbt/cloud/server/{id}/power`，func 放 form | §5.8 |
| `POST {module}/{id}/vnc` | 改为 `POST mnbt/cloud/server/{id}/vnc` | §5.9 |
| `GET mnbt/cloud/server/{id}/flow` + `GET {module}/{id}/flow` 回退 | 只保留 `mnbt/cloud/server/{id}/flow`，回退删除 | §5.7 |
| 列表/详情 `module` 字段必填 | 不再要求（可留作展示用元数据） | §5.5 |

升级期间建议：v2 实现同时保留旧 `{module}/...` 路由一段时间，让未升级的 v1 APP 平滑过渡；
APP 自 v2 版本起不再调用任何 `{module}/...` 路由。
