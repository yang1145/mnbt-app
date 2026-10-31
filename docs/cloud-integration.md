# MNBT APP × ZJMF-CBAP（云站点）对接契约

> 版本：v1（2026-10）
> 用途：本文是 MNBT APP「云服务器」能力与 ZJMF-CBAP（智简魔方V10业务系统，ThinkPHP 6）站点对接的**唯一契约依据**，
> 第三阶段自研 PHP addon（`mnbt` 插件）按本文实现；`tools/mock/server.mjs` 的 `/console/v1/` 段落与
> APP 侧 `ZjmfClient` / `CloudSiteRepository` / `CloudServerRepository` 均已按本文落地。
>
> 契约中的前台接口行为源自对 ZJMF-CBAP 官方微信小程序的逆向确认；addon 端点（`mnbt/*`）为本项目自定义。

---

## 1. 总则

| 项 | 约定 |
|---|---|
| 请求 URL | `{站点根}/console/v1/{路径}`。站点根由扫码/手输得到，规范化为无尾斜杠的 `http(s)://host[:port][/子目录]` |
| 鉴权 | 除 `mnbt/bind` 外一律带 `Authorization: Bearer <jwt>`（站点级设备令牌，见 §2） |
| POST 编码 | `application/x-www-form-urlencoded`（ThinkPHP `param()` / `$_POST` 原生可读） |
| 防重放 | **所有 POST 请求体必须带 `_r` 字段**，值为当前毫秒时间戳字符串。服务端 3 秒内对同 IP+URL+body 的重复 POST 直接拒 400 |
| 超时 | 15 秒 |
| TLS | 系统默认 CA 信任（第一版不做证书 pinning） |
| 响应包络 | JSON `{"status": <int>, "msg": "<中文文案>", "data": <object>}` |

**包络判定规则**（APP 侧 `ZjmfClient.unwrap` 与未来 addon 必须完全一致）：

- `status === 200` → 成功，取 `data`（`data` 缺省时视为 `{}`）；
- **HTTP 401 或 body `status === 401` 都视为登录失效**（两种形态都真实存在于 ZJMF 前台，见 §4）；
- 其他 → 失败：
  - 分支逻辑看 **`data.error_code`**（字符串，可能缺失）；
  - `status === 429` 时 **`data.retry_after`** 为可重试秒数；
  - `msg` 是服务端返回的中文文案，APP 直接展示。
- 防重放命中：`{status:400}`，**无 `error_code`**（`msg` 形如「重复请求被拒绝（防重放）」）。

**令牌模型**（APP 只接触一种令牌）：

- 站点级**设备令牌**：由 mnbt addon 签发，JWT，有效期 **30 天，滑动续期**（APP 每次成功请求都会续期）；
  存于 APP 的 TokenVault（EncryptedSharedPreferences），Room 里只存引用键 `token_ref`。
- ZJMF **核心 JWT**（约 1 天、无刷新机制）只存在于 addon 内部，addon 负责自取自续，绝不下发到 APP。
- APP 收到 401 的唯一恢复手段是 `POST mnbt/refresh`（见 §5.2）。

---

## 2. 二维码格式

云站点配对二维码内容为 UTF-8 JSON 字符串：

```json
{"app":"mnbt","type":"zjmf","url":"https://idc.example.com","code":"A1B2C3D4E5"}
```

| 字段 | 说明 |
|---|---|
| `app` | 固定 `"mnbt"`，用于与 MNBT 主机二维码（URL 形态）区分 |
| `type` | 固定 `"zjmf"`，表示云站点配对 |
| `url` | 站点根地址（含 scheme；允许带子目录，不允许尾斜杠） |
| `code` | 一次性配对码，由站长在 addon 后台生成，建议 5 分钟有效、单次使用（可与 addon 约定） |

手输兜底：用户填写站点地址 + 配对码，语义与扫码一致。

---

## 3. 通用行为约定（APP 侧已实现，addon 需对齐）

1. **401 恢复策略**：任意请求收到 401（双形态任一）→ APP 先调 `POST /console/v1/mnbt/refresh`
   换新令牌并**重试原请求一次**；refresh 也失败 → 站点标记 `NEEDS_REPAIR`，
   对外统一抛「登录已失效，请重新扫码绑定」。addon 必须保证：refresh 接口用**旧设备令牌**
   能换到新令牌（滑动续期），令牌吊销/过期后 refresh 也返回 401。
2. **模块直连路由**：云服务器的电源/状态/VNC 走 `POST|GET /console/v1/{module}/{id}/{func}`，
   `module` 为 `mf_cloud`（直连）或 `remf_cloud`（转销商）。**不要用**
   `/idcsmart_common/host/:id/provision/:func`——对云主机是空操作。
   模块名需探测（见 §7），命中后缓存。
3. **操作密码**：`client_operate_password`，仅当 `mnbt/config` 返回
   `operate_password_required = true` 时由 UI 传入；为 null 时**不携带该字段**。
   addon 收到带密码的请求必须校验，错误返回 `{status:400, msg:"操作密码错误"}`。
4. **时间字段**：`due_time` / `active_time` 等，兼容 ISO8601 与秒/毫秒时间戳字符串，
   APP 统一转 epoch 毫秒（失败按「未知」渲染）。addon 新实现建议直接返回 ISO8601。

---

## 4. 错误语义汇总

| 形态 | 例子 | APP 行为 |
|---|---|---|
| 登录失效 A | HTTP 401（body 任意/空） | 触发 refresh-重试；仍失败 → 站点 `NEEDS_REPAIR` |
| 登录失效 B | HTTP 200，body `{status:401, msg:"未登录或登录已失效"}` | 同上 |
| 业务失败 | `{status:400, msg:"操作密码错误", data:{error_code:"password_error"}}` | 展示 `msg`；电源操作允许用户改密码重试 |
| 防重放 | `{status:400}`，无 `error_code` | 提示重试 |
| 限流 | `{status:429, msg:"请求过于频繁", data:{retry_after:30}}` | 展示 `msg` + 倒计时 |
| 端点缺失 | HTTP 404 或 `{status:404}` | 流量查询触发回退逻辑（§6.4）；其余直接展示 |

addon 注意：所有错误都用**中文可展示文案**填 `msg`；`error_code` 只做分支，不直接展示。

---

## 5. mnbt addon 端点（自研，路径前缀 `/console/v1/mnbt/`）

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
| `device_name` | 设备显示名（addon 用于设备列表展示与吊销），可空 |
| `_r` | 防重放毫秒时间戳 |

成功 data：

```json
{"jwt": "eyJhbGciOi...", "expires_in": 2592000, "site_name": "某某云"}
```

- `jwt`：设备令牌（30 天滑动续期），后续所有请求的 Bearer；
- `expires_in`：秒；
- `site_name`：站点显示名，APP 用它回填站点条目名称（缺省用 baseUrl host）。

配对成功即视为设备已登记（addon 侧记录 device_name / 首次 IP / 时间，供吊销）。

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

### 5.4 GET `mnbt/config` —— 站点配置

```
GET {站点根}/console/v1/mnbt/config
Authorization: Bearer <jwt>
```

成功 data：

```json
{"site_name": "某某云", "operate_password_required": true}
```

- `operate_password_required = true`：电源操作必须带 `client_operate_password`（§6.2）；
  APP 据此显隐密码输入框。
- **语义（addon v1 实现口径）**：系统核心 `CheckClientOperatePassword` 对 `is_api`
  请求（含本插件的设备令牌）直接放行，即设备令牌调用模块直连路由时操作密码**永不会被
  服务端校验**。因此 addon 对设备令牌恒返回 `operate_password_required = false`（实际
  生效值）；APP 的密码输入 UI 作为预留保留，仅当服务端未来策略变化（返回 true）时启用。

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
      "module": "mf_cloud",
      "power_status": "on",
      "power_desc": "运行中",
      "dedicate_ip": "203.0.113.10",
      "due_time": "2026-11-04T23:53:32.988Z",
      "billing_cycle_name": "月付",
      "active_time": "2026-10-01T23:53:32.990Z"
    },
    {
      "id": 2, "name": "云服务器-02", "product_name": "云服务器 ECS·入门型",
      "status": "Active", "module": "remf_cloud", "power_status": "off", "power_desc": "已关机",
      "due_time": "1793145212", "billing_cycle_name": "年付", "active_time": null
    }
  ]
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `id` | 是 | ZJMF host id，后续所有按 id 的端点都用它（APP 侧叫 hostId） |
| `name` | 是 | 实例名 |
| `product_name` | 是 | 商品名 |
| `status` | 是 | host 业务状态：`Active` / `Suspended` / `Deleted` / `Deleted` 等 ZJMF 原值 |
| `module` | 是 | `mf_cloud` 或 `remf_cloud`，决定模块直连路由前缀 |
| `power_status` | 是 | `on` / `off` / `operating` / `pending` / `suspend` / `fault` / `unknown` |
| `power_desc` | 是 | 电源状态中文描述 |
| `dedicate_ip` | 否 | 独立 IP，缺失不携带该键 |
| `due_time` | 否 | 到期时间，ISO8601 或秒/毫秒时间戳字符串 |
| `billing_cycle_name` | 否 | 计费周期名 |
| `active_time` | 否 | 开通时间，格式同 `due_time` |

addon 实现要点：`power_status` 建议实时向模块查询（或短 TTL 缓存），不要只读库里旧值；
无权限/未开通云功能的设备返回 401 或空 `list`。

### 5.6 GET `mnbt/cloud/server/{id}` —— 云服务器详情

```
GET {站点根}/console/v1/mnbt/cloud/server/1
Authorization: Bearer <jwt>
```

成功 data（单条 `server`，字段为列表条目超集）：

```json
{
  "server": {
    "id": 1, "name": "云服务器-01", "product_name": "云服务器 ECS·标准型",
    "status": "Active", "module": "mf_cloud",
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
- `addition`：登录凭据，三子字段 `username` / `password` / `port` 均可缺省（addon 按模块回传多少给多少）；
- `client_notes`：客户备注原文；
- `config_text`：**服务端预格式化**的配置文本（含换行 `\n`），APP 原样展示、不再二次组装。

`id` 不存在 → `{status:404, msg:"云服务器不存在"}`。

### 5.7 GET `mnbt/cloud/server/{id}/flow` —— 归一化流量（addon 端点）

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

- addon 把各模块口径不一的流量数据**归一化**后再下发：`used`/`total` 数值同单位，`total` 可为
  `null`（不限量），`unit` 随条目给出；
- 该端点是 APP 查流量的**首选**；404（老版本 addon 未部署）时 APP 回退到模块直连
  `GET {module}/{id}/flow` 的尽力解析（§6.4）。

---

## 6. 模块直连路由（`/console/v1/{module}/{id}/...`）

`module` ∈ `mf_cloud`（直连）| `remf_cloud`（转销商），`id` 为 host id。
以下均为 ZJMF 前台真实存在的接口形态（小程序逆向确认），addon 只需保证鉴权放行与代理转发。

### 6.1 GET `{module}/{id}/status` —— 电源/状态查询

成功 data：

```json
{"status": "on", "desc": "运行中"}
```

`status` 取值与 `power_status` 同口径（on/off/operating/pending/suspend/fault/unknown）。
**APP 用它做模块探测**：包络 200 且 `data` 里有 `status` 字段即认为该 module 命中（§7）。

### 6.2 POST `{module}/{id}/{func}` —— 电源操作

`func` ∈ `on` | `off` | `reboot` | `hard_off` | `hard_reboot`。

```
POST {站点根}/console/v1/mf_cloud/1/reboot
Authorization: Bearer <jwt>
Content-Type: application/x-www-form-urlencoded

client_operate_password=123456&_r=1730000000300
```

- `client_operate_password`：仅当站点 `operate_password_required=true` 时携带；为 null 不带该字段。
- 成功 data：`{}`（HTTP 200 即成功，异步执行，APP 轮询 §6.1 观察状态变化）。
- 密码错误：`{status:400, msg:"操作密码错误"}`。
- 电源操作**不走** `/idcsmart_common/host/:id/provision/:func`（对云主机是空操作）。

### 6.3 POST `{module}/{id}/vnc` —— 获取 VNC 控制台地址

```
POST {站点根}/console/v1/mf_cloud/1/vnc
Authorization: Bearer <jwt>

_r=1730000000400
```

成功 data：

```json
{"url": "https://mock.local/vnc/session-1"}
```

- `url` **可能是相对路径**（如 `/vnc/session-1` 或 `vnc/session-1`），APP 归一化规则：
  不以 `http://`/`https://` 开头时拼接站点根（补齐必要 `/`）。addon 建议直接下发绝对地址。

### 6.4 GET `{module}/{id}/flow` —— 原始流量（回退端点）

```
GET {站点根}/console/v1/mf_cloud/1/flow
Authorization: Bearer <jwt>
```

- **形状不定**（各模块/版本返回结构差异大），APP 做**尽力解析**：识别裸数组或
  `list`/`items`/`flows` 等容器键内的对象数组，字段名识别 `used`（必备）、
  `total`、`unit`、`name`（及 `used_amount`/`total_amount`/`unit_name`/`title`/`label` 等常见变体）；
- 解析不出 → 返回空列表，**不报错**；
- 端点 404 → 仅在 addon 归一化端点（§5.7）也 404 时才会被触达；解析优先级永远是 §5.7 → §6.4。

示例（裸数组形态，APP 可解析）：

```json
{"status": 200, "msg": "ok", "data": [
  {"name": "流量", "used": 34.2, "total": 100, "unit": "GB"},
  {"name": "带宽", "used": 5, "total": 10, "unit": "Mbps"}
]}
```

---

## 7. 模块名探测规则

模块直连路由的 `{module}` 前缀 APP 无法预知，探测顺序：

1. 候选列表：`[站点级 moduleHint（上次命中的缓存）, "mf_cloud", "remf_cloud"]`（去重）；
2. 依次尝试 `GET /console/v1/{module}/{id}/status`；
3. **命中条件：包络 `status === 200` 且 `data` 里有 `status` 字段**；
4. 胜者缓存：站点级 `moduleHint`（持久化，Room `cloud_sites.module_hint`）+ 内存 per-host 缓存；
5. 探测中若返回 401，按 §3.1 走令牌恢复，不算「模块未命中」。

APP 侧同时会把 `mnbt/cloud/servers` 列表里每台的 `module` 字段直接回填缓存——
探测只在缓存全空（如冷启动后未刷新列表）时发生。

---

## 8. Mock 与实现对照

- Mock：`tools/mock/server.mjs` 的 `/console/v1/` 段落（`node tools/mock/server.mjs --port=8787`）。
  Bearer 必须以 `mock-jwt-` 开头；POST 校验 `_r`；电源操作密码 `123456`；
  3 台样例机覆盖 mf_cloud/remf_cloud × on/off/operating × ISO8601/秒级时间戳 due_time；
  `POST /__mock__/reset` 会重置云站点运行时状态。
- APP 侧：`com.mnbt.app.data.remote.api.zjmf.ZjmfClient`（传输与包络）、
  `ZjmfModels`（DTO 与时间解析）、`com.mnbt.app.domain.repo.CloudSiteRepository` /
  `CloudServerRepository` / `CloudAuth`（401 恢复、模块探测、回退逻辑）、
  `com.mnbt.app.domain.model.CloudModels`（UI 消费的领域类型）。
- UI 只允许通过 `AppContainer.cloudSiteRepository` / `cloudServerRepository` 与
  `com.mnbt.app.domain.model.Cloud*` 类型消费数据层。

## 9. 第三阶段 addon 实现清单（PHP）

1. 配对码签发与校验（一次性、短 TTL），`mnbt/bind` 换设备 JWT（30d，含 `device_id`/`device_name` 声明）；
2. `mnbt/refresh` 滑动续期 + 旧令牌失效；`mnbt/unbind` 吊销当前设备；
3. `mnbt/config`（site_name、operate_password_required 读 ZJMF 站点设置）；
4. `mnbt/cloud/servers` / `mnbt/cloud/server/{id}`：聚合 host 表 + 模块实时状态，字段严格按 §5.5/§5.6；
5. `mnbt/cloud/server/{id}/flow`：归一化各模块流量，字段严格按 §5.7；
6. 转发模块直连路由：`{module}/{id}/status|{func}|vnc|flow`，校验 `client_operate_password`；
7. 全局中间件：Bearer 鉴权（401 双形态之一即可，建议核心未登录时回 HTTP 401）、
   `_r` 防重放（3 秒窗口）、429 + `data.retry_after`；
8. 所有 `msg` 中文可展示；错误分支一律放 `data.error_code`。
