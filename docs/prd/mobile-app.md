---
title: MNBT 主机管理 APP PRD
description: 面向虚拟主机终端用户的 Android 管理 APP，扫码添加任意 MNBT 站点，纯直连零站外依赖
---

# MNBT 主机管理 APP PRD

> 版本：v1.0（待评审）
> 日期：2026-10-20
> 状态：待审批
> 适用 MNBT：1.88+
> 仓库：`mnbt-app`（Android）+ `MNBT/app_plugins/mobile_bridge`（对接插件，最后实现）

---

## 1. 背景与目标

### 1.1 背景

MNBT 是基于宝塔面板 API 的虚拟主机分销系统，由 ISV 交付给多个客户自建后端部署。终端主机用户目前只能通过网页控制面板（`/user`）管理自己的站点。移动端缺失导致两个真实场景无法覆盖：

- **救火**：网站出问题时用户往往不在电脑前，无法及时改文件、切 PHP 版本、看告警。
- **扫一眼**：到期时间、空间与流量用量这类状态查询，手机上比开电脑自然得多。

客户站点地址各不相同、且由客户自行运维，因此 APP 不能内嵌任何服务端地址或密钥。

### 1.2 目标

| # | 目标 |
|---|------|
| G1 | APP 内不预置任何 IP / 域名 / 密钥，主机条目全部通过扫码添加，一套 APP 适配所有客户站点 |
| G2 | MNBT 侧以插件形式提供对接层，客户只需在后台安装启用插件，不改核心代码、不配伪静态 |
| G3 | 覆盖网页用户面板的核心运维能力（文件、数据库、域名、SSL、站点设置、监控、部署） |
| G4 | 内置通用 SSH 终端，作为独立工具存在 |
| G5 | 预留 Docker 容器侧入口，首版不实现深层功能 |
| G6 | APK 体积 ≤ 50MB，冷启动首屏可交互 ≤ 1s（本地缓存命中时） |

### 1.3 非目标（明确不做）

| 不做 | 原因 |
|------|------|
| 管理员端 / 代理商端 | 首版只服务终端主机用户 |
| 任何售卖、支付、续费下单、优惠券、实名功能 | MNBT 的售卖能力全在选配插件里，且产品定位不含交易 |
| 依赖 MNBT 任何现有插件的功能 | `app_plugins/` 下插件全是客户选配，装了才有；APP 只能依赖核心能力 |
| 站点访问统计（PV/UV/IP 排行/蜘蛛分析等） | `user/api/site_stats.php:17-63` 十个类型全部走 `pluginRequest('mnbt_connector', ...)`，依赖宝塔节点上的选配 Python 插件 |
| 消息推送 / 后台轮询告警 | 已决策砍掉，底部 tab 位置让给 SSH 终端 |
| 中心服务器、云中控、账号体系 | 违背纯直连与零站外依赖原则 |
| 批量绑定主机 | 一次扫码绑一台，客户有 N 台就扫 N 次（已确认可接受） |
| 相册/相机直传、系统分享、生物识别锁 | 首版不做 |
| Docker 容器深层功能 | 只留 tab 与占位页 |
| 平板 / 横屏适配、多语言 | 首版竖屏锁定、仅简体中文 |

---

## 2. 术语

| 术语 | 含义 |
|------|------|
| 站点 | 一套 MNBT 部署，即一个客户的服务端 |
| 主机 | `MN_zj` 表的一行，一个虚拟主机账号 |
| 主机条目 | APP 本地保存的一条「站点 + 主机 + 令牌」记录 |
| bridge 插件 | MNBT 侧新增的 `app_plugins/mobile_bridge/`，APP 的唯一对接层 |
| 配对码 | 用户在网页面板生成的一次性短码，用于换取长期令牌 |
| 设备令牌 | 配对成功后下发的长期 Bearer Token，绑定到单台主机 |

---

## 3. 系统架构

```
┌─────────────────┐         HTTPS / HTTP          ┌──────────────────────┐
│  mnbt-app       │ ────────────────────────────> │  客户的 MNBT 站点     │
│  (Android)      │   Bearer Token                │                      │
│                 │ <──────────────────────────── │  app_plugins/        │
│  Room 本地库     │      统一 JSON 包络            │    mobile_bridge/    │
│  主机条目 / 令牌  │                               │        │             │
└─────────────────┘                               │        ▼             │
        │                                         │  include user/api/*  │
        │ 独立通道，与 MNBT 无关                     │        │             │
        ▼                                         │        ▼             │
┌─────────────────┐                               │  MPHX/bt_api.php     │
│  任意 SSH 服务器  │ <──── MINA SSHD ────────────  │        │             │
└─────────────────┘                               └────────┼─────────────┘
                                                           ▼
                                                    宝塔面板节点 API
```

### 3.1 关键架构决定

**APP 直连客户站点，无中间层。** 所有业务请求都是 APP ↔ 客户 MNBT 两方通信。SSH 终端是独立通道，用户自行填写连接信息，MNBT 不下发任何 SSH 凭据。

**bridge 插件通过 include 复用现有 handler，不重写业务逻辑。** MNBT 用户端的能力实现分散在 `user/api/*.php`，形态是过程式的 `if($egn=='xxx'){ ... exit(json_encode(...)); }`，依赖 `user/ajax.php:8-25` 预先装配的全局变量（`$yhc`、`$btipe`、`$btkeye`、`$os_xt`、`$ssbt`、`$DB`、`$conf`）。bridge 插件复刻这段引导后 include 目标文件，即可零重写地获得全部用户端能力，且 MNBT 后续升级会自动被 APP 继承。

**请求路径统一走 `index.php?_r=/app/v1/{action}`。** 插件路由引擎支持 `_r` 查询参数形式（`MPHX/plugin.php:1162`），客户站点无需配置 Nginx/Apache 伪静态即可工作。

---

## 4. 扫码配对与令牌协议

### 4.1 配对流程

```
1. 用户在网页控制面板打开「APP 绑定」页
   → /user/plugin.php?p=mobile_bridge&page=app_bind   （需 user_token 登录态）
2. 页面生成一次性配对码，渲染二维码（本地 JS，无 CDN）
3. APP 内点「扫码」→ 解析二维码得到站点地址 + 配对码
4. APP POST {base}/index.php?_r=/app/v1/pair
5. bridge 校验配对码 → 生成设备令牌 → 落库 → 返回令牌与主机档案
6. APP 加密存储为主机条目，配对码立即作废
```

### 4.2 二维码内容

采用 HTTPS URL 形式而非自定义 scheme，以便非本 APP 的扫码器（系统相机、微信）能降级到一个说明页：

```
https://{客户域名}/index.php?_r=/app/v1/pair_page&c={配对码}
```

- APP 扫码器识别到路径含 `/app/v1/pair_page` → 提取 base 与 `c`，走配对流程。
- 其他扫码器 → bridge 的 `GET /app/v1/pair_page` 返回一个静态 HTML 页，提示「请使用 MNBT 主机管理 APP 扫码」。
- 二维码下方同时提供手输入口（站点地址 + 配对码），相机不可用时的兜底。

### 4.3 配对码规则

| 项 | 规则 |
|----|------|
| 格式 | 8 位大写字符，取自去混淆字符集（排除 `0 O 1 I`） |
| 生成 | `random_int()` 逐位取，不用时间戳 |
| 有效期 | 120 秒 |
| 使用次数 | 单次，校验成功即置 `used=1` |
| 归属 | 绑定生成它的 `MN_zj.id`，配对成功后令牌继承该归属 |
| 限流 | 复用核心 `mnbt_throttle_exceeded('app_pair', $ip, 5, 600)`，IP 维度 10 分钟内最多 5 次失败 |
| 并发 | MyISAM 无事务，靠 `code` 主键唯一性 + 更新后回读 `used` 字段确认抢占成功 |

### 4.4 设备令牌规则

| 项 | 规则 |
|----|------|
| 形态 | `bin2hex(random_bytes(32))`，64 位十六进制不透明串 |
| 存储 | 数据库只存 `hash('sha256', $token)`，明文仅在配对响应中出现一次 |
| **不使用 `authcode()`** | `MPHX/common.php:22,70` 的 `SYS_KEY='MNBT'` 与 `password_hash='!@#%!s!0'` 均为硬编码且仓库公开，用它签发的令牌可被伪造 |
| 有效期 | 默认永久，用户可在面板或 APP 内随时吊销 |
| 绑定 | 一台设备 × 一台主机 = 一条记录；同一主机可被多台设备绑定 |
| 失效联动 | 主机被删除（`MN_zj` 行消失）时令牌自动失效，接口返回 `host_gone` |
| APP 侧存储 | Room + `EncryptedSharedPreferences`（AndroidX Security），密钥走 Android Keystore |

### 4.5 请求约定

> **请求体用 `application/x-www-form-urlencoded`，不用 JSON。**
> 原因有两条，都是硬的：
> 1. **编码安全**。JSON 要求整个 body 是合法 UTF-8，而宝塔站点上大量 PHP 文件是 GBK 存的。
>    `file_save` 走 JSON 时，非 UTF-8 字节会让 `json_decode` 直接返回 null，
>    或者被静默替换成 U+FFFD —— 用户保存一次就把文件写坏了。
>    百分号编码是字节透明的，GBK / Latin-1 / 二进制都能原样过。
> 2. **bridge 侧零成本**。PHP 对 form-encoded 会自动填充 `$_POST`，
>    bridge 不需要读 `php://input` 再自行 `json_decode`，现有 handler 拿到的入参形态与网页端完全一致。

**配对**

```http
POST /index.php?_r=/app/v1/pair HTTP/1.1
Host: customer-domain.com
Content-Type: application/x-www-form-urlencoded

code=ABCD2345&device_id=<uuid>&device_name=Pixel%208&device_model=Pixel%208&app_version=1.0&android_version=15
```

响应：

```json
{
  "success": true,
  "code": 0,
  "msg": "ok",
  "data": {
    "token": "…64 hex…",
    "site": {"name":"梦奈云主机","base":"https://customer-domain.com","mnbt_version":"1.88"},
    "host": {
      "user":"demo01","domain":"demo01.example.com","status":"running",
      "expire":"2026-11-21","web_quota":{"used":620,"max":1024,"unit":"MB"},
      "sql_quota":{"used":12,"max":512,"unit":"MB"},
      "traffic_quota":{"used":1843,"max":10240,"unit":"MB"}
    }
  }
}
```

**业务请求**

```http
POST /index.php?_r=/app/v1/{action} HTTP/1.1
Authorization: Bearer <token>
X-MNBT-Device: <device_id>
Content-Type: application/x-www-form-urlencoded
```

请求体为该 action 的参数，直接落到 `$_POST` 交给现有 handler。
数组型参数用 PHP 的 `name[]=v1&name[]=v2` 形式；文件上传走 `multipart/form-data`，
文件字段的 MIME 一律填 `application/octet-stream`，避免服务端按 MIME 猜编码。

**统一响应包络**

```json
{"success": true, "code": 0, "msg": "ok", "data": { }}
```

`data` 内为现有 handler 的原始输出。

> APP 侧解析器（`EnvelopeParser`）实际要容忍 **四**种形态，比这里原先写的三种多一种：
> 1. `{"success":..,"code":..,"msg":..,"redirect":..,"data":{}}` —— `Response::build` 的标准包络。
>    注意 `MPHX/function.php:518-522` 会把 `$extra` **同时**平铺到顶层，所以 `data` 与顶层都要读。
> 2. `{"qk":1|4,"code":"文本消息","msg":{…真正的载荷…}}` —— 老 handler，载荷在 `msg` 里而不是 `data`。
> 3. `{"code":"修改成功","phpversion":"7.4"}` —— 完全没有成功标志位，只能靠字段名猜。
> 4. 非 JSON 的 `sysmsg()` HTML 页（`<title>站点提示信息</title>` + `<body id="error-page">`），
>    到期/封禁/未登录都会走到，需要剥标签后把原文当错误消息显示。
>
> 另有一个坑：`Response::build` 把数字 code **0/4/100/300** 判定为失败，
> 与本节「0 = 成功」直接冲突。解析顺序必须是**先看 `success` 布尔，再看 `qk`，最后才看数字 code**。


### 4.6 错误码

| code | 含义 | APP 行为 |
|------|------|---------|
| 0 | 成功 | — |
| 1001 | 令牌无效或已吊销 | 标记主机条目为「需重新配对」，不自动删除 |
| 1002 | 配对码错误或已过期 | 提示重新生成 |
| 1003 | 主机已到期 | 展示到期页，只读，禁写操作 |
| 1004 | 主机已被封禁（`MN_zj.qk='false'`） | 展示封禁页 |
| 1005 | 主机已删除 | 提示并引导移除该主机条目 |
| 1006 | 宝塔节点配置错误 / 节点不可达 | 提示联系站点管理员 |
| 1007 | 参数错误 | 表单校验提示 |
| 1008 | 限流触发 | 提示稍后重试 |
| 1009 | 上游 handler 返回失败 | 透传 `msg` 原文 |
| 1010 | bridge 插件版本过低，不支持该 action | 提示升级插件 |
| 5000 | 服务端异常 | 提示重试，附 trace id |

> **到期/封禁必须返回 JSON 而非 HTML**：核心 `MPHX/member.php:43-45` 对到期与封禁用户走 `sysmsg()` 输出 HTML。APP 不带 `user_token` cookie，因此不会触发该分支，但 bridge 必须自行实现 JSON 化的到期/封禁检查。

### 4.7 bridge 插件路由注册要点

```php
mnbt_register_route('POST', '/app/v1/{action}', $cb, 10, null, true);
```

- `$auth` 必须传 `null`，**不能用 callable**：`MPHX/plugin.php:703-711` 中 `mnbt_plugin_auth_fail()` 对 callable 鉴权失败会 `header('Location: ...')` 跳 HTML 登录页，APP 会收到 302 而不是 JSON。鉴权在回调内部自行完成。
- 第 6 个参数 `$csrfExempt` 必须为 `true`：APP 无 cookie 会话，无法通过 `mnbt_csrf_validate_request()`（`MPHX/function.php:116`）。

---

## 5. 能力映射表

APP 的 `action` 到 MNBT 现有 `gn` 的映射。**标注「核心」的可直接复用；标注「自实现」的因原入口在选配插件中、bridge 需自行调用底层 `bt_api`。**

### 5.1 概览与用量

| action | 映射 | 说明 |
|--------|------|------|
| `host.overview` | `gn=sxsyxx`（核心，`user/api/site.php`） | 刷新并返回空间/流量/数据库用量 |
| `host.profile` | bridge 自实现 | 读 `MN_zj` + `MN_bt`，返回主机档案与到期日 |

### 5.2 站点设置

| action | 映射 | 初始化数据 |
|--------|------|-----------|
| `site.init` | `gn=set_init`（section 参数透传） | **新增（2026-10-20，契约变更）**：统一承载各设置分区的初始化数据，`section=php\|yxml\|mrwd\|wjt\|gzip\|pass\|xgpass\|url`；下表「初始化数据」列指的就是它 |
| `site.php.set` | `gn=phpxg` | `section=php` |
| `site.rundir.set` | `gn=setyxml` | `section=yxml` |
| `site.indexdoc.set` | `gn=xgmrwd` | `section=mrwd` |
| `site.rewrite.get` | `gn=hqjt` | — |
| `site.rewrite.set` | `gn=setwjt` | `section=wjt` |
| `site.gzip.set` | `gn=setgzip` | `section=gzip` |
| `site.passdir.list` | `gn=set_init&section=pass` | — |
| `site.passdir.add` | `gn=tjmmfw` | — |
| `site.passdir.del` | `gn=scmmfw` | — |
| `site.password.set` | `gn=xgpass` | `section=xgpass` |
| `site.indexconf.set` | `gn=indexconf` | — |
| `site.hotlink.get` | `gn=getfdl` | — |
| `site.hotlink.set` | `gn=fdlkg` | — |
| `site.proxy.set` | `gn=fzjh` | `section=url` |
| `site.cache.list` | `gn=cache_list` | — |
| `site.cache.add` | `gn=cache_add` | — |
| `site.cache.edit` | `gn=cache_edit` | — |
| `site.cache.del` | `gn=cache_del` | — |

### 5.3 文件管理（全部核心，`user/api/file.php`）

| action | 映射 gn |
|--------|---------|
| `file.list` | `file_list` |
| `file.read` | `file_read` |
| `file.save` | `file_save` |
| `file.create` | `file_create` |
| `file.delete` | `file_delete` |
| `file.delete_batch` | `file_delete_batch` |
| `file.rename` | `file_rename` |
| `file.copy` | `file_copy` |
| `file.compress` | `file_compress` |
| `file.unzip` | `file_unzip` |
| `file.size` | `file_size` |
| `file.upload_prepare` | `file_upload_prepare` |
| `file.upload` | `file_upload`（multipart 分片，bridge 需透传 multipart 而非 JSON） |
| `file.download` | `file_download`（返回外链，APP 用 OkHttp 下载） |
| `file.access.get` | `file_access` |
| `file.access.set` | `file_access_set` |

> 路径一律为站点相对路径（以 `/` 开头），服务端已拒绝 `..` 穿越。`.user.ini` 禁改、运行目录禁删等保护由核心 handler 内置，bridge 不重复实现。
> 回收站在核心已下线，APP 不做。

### 5.4 数据库（核心，`user/api/database.php` + `user/api/panel.php`）

| action | 映射 |
|--------|------|
| `db.list` | `gn=backup_list` + `gn=set_init&section=mysqlcz` |
| `db.create` | `gn=databaseadd` |
| `db.delete` | `gn=databasedel` |
| `db.delete_all` | `gn=Delalldatabase`（需二次确认弹窗） |
| `db.backup.download` | `gn=databasedownload`（文件流） |
| `db.backup.restore` | `gn=databaserestore` |
| `db.access.set` | `gn=databaseaq1` |
| `db.sql.import` | `gn=sqldr` |

### 5.5 域名与 SSL

| action | 映射 | 说明 |
|--------|------|------|
| `domain.list` | `gn=listurl`（核心） | — |
| `domain.subdir.list` | `gn=hqzmlls`（核心） | — |
| `domain.add` | **自实现** → `bt_api::btapi_addym($siteid,$webname,$domain)`（`MPHX/bt_api.php:149`） | 核心 `user/api/domain.php` 的 CRUD 已迁至选配插件 `domain_shop`，但底层方法在核心 |
| `domain.del` | **自实现** → `bt_api::btapi_delym($siteid,$webname,$domain,$port)`（`MPHX/bt_api.php:163`） | 同上 |
| `ssl.get` | `gn=getssl`（核心） | — |
| `ssl.apply` | `gn=sqssl&type=false` | Let's Encrypt 申请 |
| `ssl.renew` | `gn=sqssl&type=true` | 续签 |
| `ssl.custom` | `gn=setssl` | 粘贴 key + pem |
| `ssl.close` | `gn=clossl` | — |
| `ssl.force_https` | `gn=httpsqz` | — |

> 域名增删需校验 `MN_zj.ymbds`（最大绑定数），超出返回 1007。

### 5.6 监控与通知（核心，`user/api/monitor.php` + `user/api/panel.php`）

| action | 映射 |
|--------|------|
| `monitor.list` | `gn=monitor_list` |
| `monitor.save` | `gn=monitor_save` |
| `monitor.delete` | `gn=monitor_del` |
| `monitor.toggle` | `gn=monitor_toggle` |
| `monitor.log` | `gn=monitor_log_list` |
| `notice.list` | `gn=notice_list` |
| `notice.read` | `gn=notice_read` |

> 核心限制照搬：每用户最多 5 个监控任务，URL 监控最小间隔 60s，资源监控固定 180s，URL 禁指内网。

### 5.7 一键部署（核心，`user/api/deploy.php` + `user/api/panel.php`）

| action | 映射 |
|--------|------|
| `deploy.list` | `gn=deploy_list` |
| `deploy.form` | `gn=yjbsform` |
| `deploy.run` | `gn=yjbs` |

> 部署程序图标（`MN_bs.src`）由客户站点自身提供，属同站点资源，不违背零站外依赖。
> 两个实现约束：`MN_bs.src` 若是相对路径，必须先按该主机的 base URL 补全；Coil 不能用它
> 默认自建的 `OkHttpClient`，要通过 `ImageLoader.Builder(okHttpClient = MnbtClient.rawClient(pin))`
> 注入该主机专属的 client，否则用户手动信任过证书的站点上图片会全部握手失败。

### 5.8 设备管理（bridge 自实现）

| action | 说明 |
|--------|------|
| `device.revoke` | 吊销当前令牌 |
| `device.ping` | 心跳，用于验证令牌有效性与刷新用量 |
| `device.info` | 返回 bridge 插件版本 + MNBT 版本，供 APP 做能力协商 |

### 5.9 能力协商

APP 启动后对每个主机条目调 `device.info`，取得 bridge 插件版本。若某 action 需要的最低插件版本高于站点实际版本，返回 1010，APP 在对应入口显示「需升级服务端插件」。这样插件与 APP 可独立发版。

---

## 6. bridge 插件设计（MNBT 侧）

> **实现顺序上排在最后**（见 §11）。本章先定契约，APP 开发期间用本地 mock server 按本章协议实现。

### 6.1 目录结构

```
app_plugins/mobile_bridge/
├── plugin.json          # id: mobile_bridge, requires_mnbt: 1.88, type: ["tool"]
├── install.sql          # 建表
├── uninstall.sql        # 删表
├── bootstrap.php        # 注册路由 / 页面 / 菜单
├── lib/
│   ├── token.php        # 令牌生成、校验、吊销
│   ├── pair.php         # 配对码生成与校验
│   ├── context.php      # 复刻 user/ajax.php 的全局上下文装配
│   ├── adapter.php      # include handler + 输出捕获 + 包络
│   ├── actions.php      # action → gn / 自实现 的映射与分发
│   └── qrcode/          # 本地二维码渲染资源（无 CDN）
└── views/
    └── app_bind.php     # 用户面板绑定页（二维码 + 设备列表）
```

**不修改任何 MNBT 核心文件。**

### 6.2 新增数据表

```sql
CREATE TABLE `MN_plugin_app_device` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `zj_id` int(11) NOT NULL,                 -- 绑定的 MN_zj.id
  `token_hash` char(64) NOT NULL,           -- sha256(token) hex
  `device_id` varchar(64) NOT NULL DEFAULT '',
  `device_name` varchar(120) NOT NULL DEFAULT '',
  `app_version` varchar(20) NOT NULL DEFAULT '',
  `created_at` varchar(50) NOT NULL,
  `last_seen_at` varchar(50) NOT NULL DEFAULT '',
  `last_ip` varchar(45) NOT NULL DEFAULT '',
  `status` tinyint(1) NOT NULL DEFAULT 1,   -- 1 启用 / 0 已吊销
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_token` (`token_hash`),
  KEY `idx_zj` (`zj_id`)
) ENGINE=MyISAM DEFAULT CHARSET=utf8;

CREATE TABLE `MN_plugin_app_pair` (
  `code` char(8) NOT NULL,
  `zj_id` int(11) NOT NULL,
  `created_at` int(11) NOT NULL,
  `expires_at` int(11) NOT NULL,
  `used` tinyint(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (`code`),
  KEY `idx_zj` (`zj_id`)
) ENGINE=MyISAM DEFAULT CHARSET=utf8;
```

引擎与字符集沿用 MNBT 现有约定（MyISAM / utf8，见 `install/install.sql`）。

### 6.3 上下文装配（`lib/context.php`）

复刻 `user/ajax.php:8-25` 的引导，但数据源从 `user_token` cookie 换成设备令牌：

1. 从 `Authorization: Bearer` 取令牌 → `sha256` → 查 `MN_plugin_app_device`，校验 `status=1`。
2. 用 `zj_id` 查 `MN_zj` 得 `$yhc`；行不存在 → 1005。
3. 检查 `$yhc['datae']`（到期）→ 1003；`$yhc['qk']=='false'`（封禁）→ 1004。
4. 用 `$yhc['ssbt']` 查 `MN_bt` 得 `$cert`；不存在 → 1006。
5. 装配 `$btipe`、`$btkeye`、`$os_xt`、`$l_ler_a`、`$ssbt`、`$zjid`、`$yhid`、`$islogins=1`。
6. 回写 `last_seen_at` 与 `last_ip`（节流：距上次回写 <60s 则跳过，避免每请求写库）。

### 6.4 Handler 适配器（`lib/adapter.php`）

现有 handler 以 `exit()` 结束，无法在返回后包装。方案：

```
ob_start();
register_shutdown_function(function () {
    $raw = ob_get_clean();
    echo wrap_envelope($raw);   // 解析 raw JSON，套统一包络
});
$egn = $mappedGn;
$_POST = $params;
include ROOT . 'user/api/' . $file . '.php';
```

- handler 内 `include("../class.php")` 等相对路径按**包含文件所在目录**解析，从插件 include 过去仍然正确。
- `wrap_envelope()` 需容忍三种历史响应格式（见 §4.5）。
- 若捕获到的输出不是合法 JSON（说明命中了 `sysmsg()` 等 HTML 分支），返回 5000 并记录日志，绝不把 HTML 透传给 APP。

### 6.5 用户面板绑定页

- 注册：`mnbt_register_page('user', 'app_bind', ...)` + `mnbt_register_menu('user', ...)`
- 访问：`/user/plugin.php?p=mobile_bridge&page=app_bind`
- 内容：
  - 「生成绑定码」按钮 → AJAX（`mnbt_register_ajax('user', 'p_mobile_bridge_gen_code', ..., 'user')`，走正常 cookie + CSRF）
  - 二维码（本地 JS 渲染 canvas，无外部请求）+ 120 秒倒计时
  - 已绑定设备列表：设备名、绑定时间、最后活跃、最后 IP、吊销按钮
  - 手输配对信息（站点地址 + 码）的文字说明
- 视觉遵循 MNBT 现有 TDesign 用户面板风格（这是网页侧，与 APP 视觉规范无关）。

### 6.6 安全要求

| 要求 | 实现 |
|------|------|
| 配对码爆破防护 | IP 限流（10 分钟 5 次）+ 120s TTL + 单次使用 |
| 令牌不可逆推 | 库存 sha256，明文只下发一次 |
| 越权防护 | 令牌绑定 `zj_id`，所有操作强制以该主机的 `$yhc` 为上下文，handler 自身的路径规范化与站点目录限制照旧生效 |
| 写操作审计 | 沿用核心 `logjl()` / `MN_log`，操作者记为 `{主机用户名}(APP)` |
| 吊销 | 面板与 APP 双向可吊销，吊销即时生效（无缓存） |
| 传输 | 不强制 HTTPS（客户站点可能只有 HTTP），但明文连接在 APP 侧强警告 |

---

## 7. APP 信息架构

### 7.1 底部导航

四个 tab：**主机 / 终端 / 容器 / 我的**。

### 7.2 页面清单

**主机 tab**

| 页面 | 要点 |
|------|------|
| 主机列表 | 顶部健康总览条（N 台 · M 台异常 · K 台 30 天内到期）；紧凑列表，一屏 4~5 台；每行 = 状态点 + 域名 + 空间/流量百分比 + 到期天数；下拉刷新；右上扫码按钮；空态为大按钮引导 |
| 扫码页 | ZXing 取景 + 手输入口 |
| 证书信任页 | 见 §9.2 |
| 手动添加页 | 站点地址 + 配对码两个输入框 |
| 主机详情 | 见 §7.3 |
| 各功能页 | 见 §5 映射表逐项对应 |

**主机详情页结构**

```
┌ sticky 头：主机域名 + 状态点 + ⋮（暂停/解停/解绑/刷新用量）
├ 用量区：空间 / 流量 / 数据库 三条细进度 + 到期倒计时（随滚动折叠进 sticky 头）
├ 搜索框：19 项功能的即时筛选
└ 分组列表：
   站点运行   PHP 版本 → 8.2 | 运行目录 | 伪静态 | 默认文档 | 索引配置
   文件数据   文件管理 | 数据库 | 导入 SQL
   域名证书   绑定域名 → 3 个 | SSL 证书 → 剩 58 天 | 强制 HTTPS → 开
   性能缓存   Gzip | 缓存规则 → 3 条 | 防盗链 → 关 | 反向代理
   监控部署   监控任务 → 2 个告警 | 通知 | 一键部署
   账号安全   密码访问目录 | 修改密码
```

每行右侧**必须显示当前值**（版本号、剩余天数、条数、开关状态），这是选分组列表而非九宫格的核心理由：不进二级页就能看到状态。

**终端 tab**

| 页面 | 要点 |
|------|------|
| 会话列表 | 已保存的 SSH 连接，显示主机别名/地址/上次连接时间 |
| 新建会话 | 地址、端口、用户名、认证方式（密码 / 私钥 / 私钥+口令）、私钥导入 |
| 终端页 | PTY 全屏终端 + 辅助键栏（Esc、Tab、Ctrl、方向键、`|`、`-`、`/`、键盘）+ 字号调节 + 复制粘贴。`键盘` 是显式唤起/收起软键盘的键位：`TerminalView` 不是 `EditText`，框架不会替它弹输入法，上游 Termux 靠自己的 extra-keys 键盘键，砍掉它就没有任何输入入口 |

**容器 tab**

占位页，但入口是活的：说明文案 + 一个真实可用的「扫码添加容器服务」按钮。扫到未支持的站点时明确提示「该站点未开通容器服务」。不做任何容器功能。

**我的 tab**

| 项 | 说明 |
|----|------|
| 账号卡 | 头像框 + 账号名 + 站点地址 + 绑定日期/插件版本 + 概览一行（主机 N / SSH 配置 N / 待修复 N）。APP 没有独立账号体系，账号取最近使用那台主机配对时回传的 MNBT 用户名；头像框是纯装饰（白底 + hairline 蓝环 + 四角直角括号，无蓝底色块），首字母只在 ASCII 用户名时生成，中文用户名回落到人形图标——不允许联网取头像图 |
| 主机管理 | 全部主机条目、重命名、解绑、证书信任管理 |
| 外观 | 深色模式：跟随系统 / 浅色 / 深色 |
| 存储 | 清除本地缓存（不删令牌） |
| 关于 | 版本号、开源许可声明（含 sora-editor LGPL-2.1、Tabler MIT、MINA SSHD Apache-2.0 等） |

---

## 8. 视觉与交互规范

### 8.1 设计基线

**不从 MNBT 网页主题推导**，按移动端自身使用姿势定：单手操作、碎片时间、救火场景、夜间查看。

| Token | 浅色 | 深色 |
|-------|------|------|
| primary（交互蓝） | `#0b57d0` | `#a8c7fa` |
| onPrimary | `#ffffff` | `#062e6f` |
| background | `#f5f6f8` | `#111318` |
| surface（卡片） | `#ffffff` | `#1e2025` |
| outline（分隔线/边框） | `#e7e7e7` | `#3a3d42` |
| textPrimary | `#181818` | `#e6e6e6` |
| textSecondary | `#595959` | `#a0a4ab` |
| textPlaceholder | `#8c8c8c` | `#6f7379` |
| success | `#2ba471` | `#4cc38a` |
| warning | `#e37318` | `#f0954a` |
| error | `#d54941` | `#f2726b` |

交互蓝用 `#0b57d0` 而非网页侧的 `#0052d9`：后者为桌面大面积设计，在手机上做 14sp 文字与 20dp 描边图标时发闷。

所有色值必须走语义层（`AppColors`），禁止在组件里直接写色值——这是深色模式不返工的前提。

### 8.2 形状与密度

| 项 | 值 |
|----|-----|
| 卡片圆角 | 12dp |
| 按钮 / 输入框圆角 | 8dp |
| 悬浮对话框圆角 | 16dp（四角都收） |
| BottomSheet 圆角 | 16dp（仅顶部，底边贴屏幕不收圆角） |
| tab 切换动效 | 120ms 交叉淡入，无位移 |
| 进/出功能页动效 | 180ms，新页从右侧推入 1/4 屏宽、旧页原地淡出；返回方向对称 |
| 最小触控目标 | 48dp |
| 列表行高 | 56dp（带副标题 72dp） |
| 页面左右边距 | 16dp |
| 卡片内边距 | 16dp |
| 卡片间距 | 8dp |
| 正文字号 | 14sp，标题 16sp，页面标题 20sp |

**分层不用阴影**，用 1px `outline` 边框 + 白卡压在 `background` 灰底上。

### 8.3 硬性视觉红线

以下为长期稳定偏好，不接受偏离：

- 不出现蓝底色块、蓝底小标签（chip）、标题左侧竖条。
- 选中态 = 蓝边 + 蓝字 + 白底，不铺蓝底；切换选中态不改字重（避免宽度跳动）。
- 状态色只用在状态点、文字、细进度条上，不铺大面积色块。
- 列表不用斑马纹，用 1px 分隔线 + 按压浅蓝反馈。
- 行内操作只常显 2 个高频，其余收进「更多」。
- 限高区域必须可滚动并显示细滚动条，禁止静默截断；路径等长文本给细横向滚动条。
- 同行控件必须等高对齐。
- 图标一律单色描边，禁止彩色底、禁止填充色块。

### 8.4 图标

**Tabler Icons**（MIT，6238 个，2px 描边）。按需取用到的约 70 个 SVG，转 VectorDrawable 放入 `res/drawable/`，总体积约 70KB。

- 统一 `strokeWidth=2`、`24dp` viewport、`tint` 走语义色（默认 `textSecondary`，激活 `primary`）。
- 转换用一次性脚本批量完成，脚本入库以便后续补图标。
- 需要覆盖的图标集：4 个 tab、19 项功能、文件操作（新建/上传/下载/删除/重命名/压缩/解压/权限/复制）、状态（运行/暂停/到期/异常/告警）、通用（返回/更多/搜索/刷新/扫码/复制/分享/设置）。

### 8.5 组件来源

优先用现成件，不自研：`NavigationBar`、`TopAppBar`、`SearchBar`、`ListItem`、`Card`、`PullToRefreshBox`、`SwipeToDismissBox`、`ModalBottomSheet`、`AlertDialog`、`LinearProgressIndicator`、`Snackbar`。Accompanist 只取 `permissions`。

Android/Compose 生态没有成品业务 UI 库，业务屏的组合必须自己写。

---

## 9. 安全设计

### 9.1 令牌与本地存储

- 令牌存 `EncryptedSharedPreferences`（AndroidX Security，密钥托管 Android Keystore），不进 Room 明文字段。
- 主机条目元数据（地址、站点名、主机用户名、最后同步时间）存 Room。
- SSH 私钥与密码存 `EncryptedSharedPreferences`，私钥支持导入时设置口令。
- 应用退出登录 = 吊销令牌（调 `device.revoke`）+ 清本地。

### 9.2 证书信任流程

首次连接一个站点时，若 TLS 握手失败（自签 / 过期 / 域名不匹配）：

1. APP 取出服务器证书链，计算叶子证书 SHA-256 指纹。
2. 弹出全屏信任页，展示：**颁发者、主体、有效期起止、SHA-256 指纹、失败原因**。
3. 明确风险提示：「无法验证该证书的真实性。如果你不在该站点管理员所在的网络环境中，继续连接可能导致凭据被中间人截获。请与站点管理员核对指纹后再继续。」
4. 用户点「信任并继续」→ 指纹存入 Room（该主机条目专属）→ 后续连接做指纹 pinning，指纹变化则重新走信任流程。
5. 点「取消」→ 返回，不保存主机条目。

指纹可在「我的 → 主机管理 → 证书信任管理」中查看与撤销。

### 9.3 明文 HTTP

`AndroidManifest.xml` 设 `android:usesCleartextTraffic="true"`。

**理由**：Android 的明文策略由构建期 XML 决定，运行时无法为单个域名开白名单。而本 APP 不预置任何地址，所有明文请求都是用户自己扫码添加、且在信任页看过警告的，风险由用户显式承担。

信任页在站点为 HTTP 时追加红字强警告：**「此站点使用未加密连接，你的密码与文件内容可被同网络下的任何人截获。」** 主机列表中该主机条目常驻一个灰色「未加密」标记。

### 9.4 服务端侧

见 §6.6。补充：bridge 插件不新增任何绕过核心权限检查的路径，全部操作复用现有 handler 的校验（路径规范化、配额限制、`.user.ini` 保护等）。

---

## 10. 性能与体积

### 10.1 启动

| 措施 | 说明 |
|------|------|
| 首屏不等网络 | 主机列表与用量先读 Room 缓存立即渲染，网络数据后台静默刷新并 diff 更新。目标：本地缓存命中时冷启动到首屏可交互 ≤ 1s |
| Baseline Profile | `androidx.baselineprofile` + macrobenchmark 生成，随 release 打包 |
| 懒初始化 | sora-editor、SSH 客户端、扫码器均不在 `Application.onCreate` 初始化 |
| R8 full mode | release 开 `isMinifyEnabled` + `isShrinkResources` |
| 串行请求收敛 | 多主机用量刷新并发执行（协程），失败的主机单独标记，不阻塞其他 |

### 10.2 体积预算（上限 50MB）

| 模块 | 库 | 估算 |
|------|-----|------|
| 基座 | Kotlin stdlib + Compose BOM + Material3 + activity/core-ktx | ~4.5MB |
| 网络 | OkHttp | ~0.8MB |
| 存储 | Room + AndroidX Security | ~1.5MB |
| 扫码 | zxing-android-embedded | ~1.5MB |
| 图片加载 | Coil `coil-compose` | ~1MB |
| 代码编辑器 | sora-editor `editor` + `language-textmate` + PHP grammar | ~1.9MB |
| SSH | Apache MINA SSHD `sshd-core` + `sshd-common` + slf4j | ~3MB |
| SSH 加密 | BouncyCastle `bcprov-jdk18on` + `bcpkix-jdk18on` | ~6MB |
| 终端渲染 | 内联的 Termux 源码（`:terminal` 模块，10.6k 行 Java） | ~0.3MB |
| 图标 | Tabler 按需 ~70 个 VectorDrawable | ~0.07MB |
| 其他 | desugaring、导航、协程等 | ~1MB |
| **合计** | | **~21.6MB** |

预算内余量充足。所有库均为纯 Java/Kotlin，**零 native .so**，无需 ABI 分包，universal APK 即最终体积。

与初版的四处变化：

- **Coil 保留，但用途收窄到一处**：只有 §5.7 部署程序图标（`MN_bs.src`）需要加载网络图片，
  且图片来自客户站点自身，属同站资源，不违背零站外依赖。曾考虑为了省这 1MB 改用
  OkHttp + `BitmapFactory` 手写加载，放弃原因是自己写就要自己处理降采样、内存/磁盘缓存与
  列表复用时的错位，成本和出错面都大于 1MB 的收益。
- **BouncyCastle 是体积预算里最大的一块，而且压不下去**。Provider 用字符串类名注册算法实现，
  几乎所有 `jcajce` 类都是反射进去的，没有可靠的精简 keep 集合，只能 `-keep class org.bouncycastle.** { *; }`
  全留。但它是必需的：Android 平台自带的裁剪版 BC 没有 ed25519 与 `chacha20-poly1305@openssh.com`，
  而现代 OpenSSH 服务端默认就只开这两类。
- **终端渲染不再是 Termux 的 AAR**，改为内联源码（见 §10.3）。少了 jitpack 依赖与上游的 jni `.so`，
  体积反而更小，但仓库里多了 10.6k 行需要自己维护的 Java。
- 原表里的 `sshd-sftp` 已删除：文件管理全部走 bridge 接口，终端 tab 只做 shell。

每个 release 在交付说明中列出体积构成，涨幅超过 10% 需说明原因。

### 10.3 SSH 与终端的技术前提

#### 已按此实现的三项

1. **`minSdk` 从 24 提到 26**。MINA SSHD 使用 `java.nio.file`，Android 8.0（API 26）起才提供，
   且 library desugaring 不覆盖该包。已落到 `app/build.gradle.kts`。
2. **开启 core library desugaring**（`isCoreLibraryDesugaringEnabled = true` + `desugar_jdk_libs`），
   MINA SSHD 用到 `java.time` 等 API。已落地。
3. **BouncyCastle 类冲突**。原方案写的 `Security.insertProviderAt(BouncyCastleProvider(), 0)`
   **是错的**：Android 平台自带的裁剪版 BC 会以 "BC" 之名占位，MINA 的 `SecurityUtils` 只按
   provider 名字探测，探测到就认为可用，然后在真正取算法时失败。
   正确做法是先摘再装（MINA 官方 `docs/android.md` 也是这么写的）：

   ```kotlin
   Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
   Security.addProvider(BouncyCastleProvider())
   ```

   已落到 `SshPlatform`。

> **注意**：以上三项是「代码已按此写」，不是「已在真机验证通过」。
> 在跑通 P0 之前不得声称 SSH 可用。

另外 MINA 官方明确不为 Android 提供支持，只留了钩子。除上面的 provider 处理外，
还必须显式喂 `user.home` / `user.dir` / `user.name` 三个系统属性（Android 上都不存在），
否则加载私钥、写 known_hosts 时会炸。`SshPlatform` 已统一指向 app 的 `filesDir`。

回退方案 `com.github.mwiede:jsch` 已否决：体积优势（~600KB）换不来 ed25519 之外算法的完整度，
且用户已明确选定 MINA。

#### 终端渲染：内联 Termux 源码（已决策）

原计划通过 jitpack 引 `com.github.termux.termux-app:terminal-view` + `terminal-emulator`。
**这条路走不通**，原因是硬性的：

- `TerminalSession` 是 `final`，无法继承替换；
- 它的 `initializeEmulator()` 内部直接调 `JNI.createSubprocess()` fork 本机 `/system/bin/sh`；
- `write()` 只在 `mShellPid > 0` 时才生效；
- `TerminalView.attachSession()` 只接受 `TerminalSession`。

也就是说，引依赖的结果是在手机上开一个**本地** shell，而不是远程 SSH 会话。
市面上也不存在提供可插拔传输层的现成 Android 终端控件，「用现成的库」在这一项上无法满足。

决策：**把上游源码内联到仓库的 `:terminal` 模块，只重写 `TerminalSession.java` 一个文件**。

- 取用范围：`terminal-emulator` + `terminal-view` 的全部 Java 源码、两个文本选择手柄 drawable、
  三条字符串资源、以及上游 20 个 JVM 单元测试。
- **许可无风险**：termux-app 整体是 GPLv3，但 `terminal-emulator` / `terminal-view` 在上游
  `LICENSE.md` 中被单独列为 **Apache-2.0**（派生自 jackpal/Android-Terminal-Emulator），
  copyleft 不落到本 APP 上。
- 改动清单与升级方法见 `terminal/NOTICE.md`。核心是引入
  `TerminalSession.Channel { write / resize / close }` 作为传输层接缝，
  `SSH 通道 → Channel → TerminalSession → TerminalEmulator → TerminalView`。
- 内联的另一个好处：`:terminal:testDebugUnitTest` 能在纯 JVM 上跑上游那 20 个测试，
  这是「改动没把模拟器改坏」的唯一可自动验证的证据（上游测试用自建的 `MockTerminalOutput`，
  完全不碰 `TerminalSession` 与 JNI，所以覆盖后可以直接跑）。


---

## 11. 分期计划

> 工作量为单人开发估算，待校准。**bridge 插件正式实现按决策排在最后**（P5），P1–P4 期间 APP 对接本地 mock server，mock 严格按 §4/§5 契约实现。

| 期 | 内容 | 估算 |
|----|------|------|
| P0 | 技术验证：MINA SSHD 在 Android 跑通（§10.3 三项）；bridge 最小 spike 验证路由注册 + include 适配器 + 输出捕获可行；sora-editor 加载 PHP grammar 验证 | 5 人日 |
| P1 | 设计系统与骨架：语义色（浅/深）、Tabler 图标转换脚本、Typography、Shapes、导航框架、Room schema、OkHttp 层、证书信任与明文处理、mock server | 10 人日 |
| P2 | 主机主流程：扫码配对、主机列表（含总览条）、主机详情（搜索 + 分组列表）、概览与用量、站点设置类全部功能页 | 15 人日 |
| P3 | 文件管理 + 代码编辑器：文件浏览、分片上传、下载、压缩解压、权限、批量操作、sora-editor 集成 | 15 人日 |
| P4 | SSH 终端：MINA SSHD 会话管理、内联 `:terminal` 模块接入（`TerminalView` + `attachSession`）、辅助键栏、会话持久化 | 12 人日 |
| P5 | bridge 插件正式实现 + 端到端联调：令牌/配对/上下文装配/适配器/action 全量映射/用户面板绑定页 | 15 人日 |
| P6 | 收尾：深色模式全页回归、Baseline Profile、体积报告、真机适配、文档 | 6 人日 |

**合计约 78 人日。**

### 11.1 「插件最后写」的风险与缓解

P1–P4 全程用 mock，协议实现偏差可能到 P5 才暴露。缓解措施：

- P0 的 spike 插件不丢弃，作为协议的**参考实现**保留，P5 在其基础上补全与加固，而非从零开始。
- §4/§5 的契约在 P0 结束时冻结，之后修改需同步改 mock 与本文档。
- P2 结束时安排一次**真站点对接预演**（用 spike 插件 + 已完成的主机主流程），提前暴露上下文装配、响应格式容忍、CSRF/限流等问题。

---

## 12. 验收标准

### 12.1 功能

| # | 标准 |
|---|------|
| A1 | 全新安装的 APP 不含任何硬编码站点地址；抓包确认除用户扫码添加的域名外无任何外部请求 |
| A2 | 在两个不同域名的 MNBT 站点各扫一次码，两台主机共存且互不干扰 |
| A3 | 配对码过期（>120s）、重复使用、错误三种情况均给出明确提示且不产生令牌 |
| A4 | §5 映射表中每个 action 均有对应可用页面，且与网页面板操作结果一致 |
| A5 | 域名增删在未安装 `domain_shop` 插件的站点上正常工作 |
| A6 | 站点未安装 `mnbt_connector` 时，APP 内不出现任何站点统计入口 |
| A7 | 主机到期（1003）、封禁（1004）、删除（1005）三种状态分别展示对应页面，且服务端返回的是 JSON 而非 HTML |
| A8 | 面板侧吊销设备后，APP 下次请求即收到 1001 并标记为需重新配对 |
| A9 | SSH 终端可完成：密码登录、私钥登录、执行命令、Ctrl+C、vim 操作、窗口 resize、断线重连提示 |
| A10 | 文件管理可完成：浏览、在线编辑 PHP 文件并有语法高亮、保存、上传、下载、压缩、解压、改权限、批量删除 |

### 12.2 安全

| # | 标准 |
|---|------|
| B1 | 数据库中不存在令牌明文，只有 sha256 |
| B2 | 自签证书站点首次连接必弹信任页，指纹不符时二次拦截 |
| B3 | HTTP 站点在信任页与主机列表均有明确未加密警示 |
| B4 | bridge 全部路由 `csrfExempt=true` 且自鉴权，未带令牌或令牌错误一律返回 JSON 401 类错误码，无 302、无 HTML |
| B5 | APP 无法通过任何 action 访问令牌绑定主机以外的数据 |
| B6 | 卸载重装 APP 后旧令牌仍有效但需重新扫码；APP 内解绑会真实吊销服务端令牌 |

### 12.3 性能与体积

| # | 标准 |
|---|------|
| C1 | 本地缓存命中时，冷启动到主机列表可交互 ≤ 1s（中端机，Pixel 6a 级） |
| C2 | universal APK ≤ 50MB，且交付说明含体积构成 |
| C3 | 主机列表滚动无明显掉帧（Macrobenchmark 帧率 P50 ≥ 55fps） |
| C4 | 5 台主机的用量并发刷新总耗时 ≤ 3s（网络正常） |
| C5 | 深色模式下全部页面无硬编码色值残留（人工逐页走查） |

---

## 13. 风险与未决事项

### 13.1 技术风险

| 风险 | 影响 | 缓解 |
|------|------|------|
| MINA SSHD 在 Android 上跑不通（`java.nio.file` / BouncyCastle / `stty` 三个坑） | 终端 tab 无法交付 | P0 首项验证；代码已按 §10.3 的规避写法落地，但**尚未真机验证**。jsch 回退已否决（见 §10.3），真跑不通就只能砍终端 tab，不影响其余三个 tab |
| include 现有 handler 时 `exit()` 与输出缓冲交互异常 | 部分 action 返回空或 HTML | P0 spike 覆盖全部 12 个 `user/api/*.php` 文件；异常输出统一转 5000 |
| `MPHX/360safe/360webscan.php` 拦截 `file_save` 中带 PHP 代码的正常写入 | 文件编辑保存失败 | P0 实测；必要时在 bridge 文档中要求客户对 `/index.php?_r=/app/v1/*` 加白名单 |
| 360 网站卫士或其他 WAF 拦截 Bearer 头 | 全部请求失败 | 同上，交付文档写明放行规则 |
| 客户站点部署在子目录 | 路径拼接错误 | 统一使用 `mnbt_home_base()`（`MPHX/plugin.php` / `function.php:561`）拼接，配对响应回传 base |
| MyISAM 无事务，配对码并发抢占 | 极端情况下同一码被用两次 | 主键唯一 + 更新后回读 `used` 确认；令牌重复无实际危害 |
| MNBT 升级改动 `user/api/*.php` 的 `gn` 名或全局变量契约 | APP 对应功能失效 | 能力协商（§5.9）+ 每次 MNBT 升级跑一遍 action 全量回归 |

### 13.2 未决事项

| # | 事项 | 需要谁定 |
|---|------|---------|
| U1 | APP 显示名称与启动图标设计 | 待定 |
| U2 | bridge 插件的正式 `plugin.json` 名称与文案（面向客户后台展示） | 待定 |
| U3 | 是否需要在 MNBT 后台（管理员侧）提供「全站已绑定设备」总览 | 待定，首版未纳入范围 |
| U4 | 网站日志查看（`bt_api::GetLogsy` 在核心已存在，成本极低） | 待定，可作为 P2 的顺带增量 |
| U5 | iOS 版本 | 待定，本协议与视觉规范可直接复用，UI 需重写 |

---

## 14. 交付物清单

| 交付物 | 位置 |
|--------|------|
| Android APP 源码 | `mnbt-app/` |
| 内联的终端渲染模块（Termux Apache-2.0 源码，含改动清单与升级流程） | `mnbt-app/terminal/`，见 `terminal/NOTICE.md` |
| bridge 插件 | `MNBT/app_plugins/mobile_bridge/` |
| 本 PRD | `mnbt-app/docs/prd/mobile-app.md` |
| 客户部署说明（安装插件 + WAF 放行 + 绑定流程） | `mnbt-app/docs/deploy-guide.md`（P5 产出） |
| 协议契约文档（供第三方客户端实现） | `mnbt-app/docs/api-contract.md`（P5 产出） |

### 14.1 客户侧需要执行的动作

1. 将 `mobile_bridge` 目录上传到 MNBT 的 `app_plugins/`。
2. 后台 → 插件管理 → 安装并启用。
3. 若有 WAF（360 网站卫士等），放行 `/index.php?_r=/app/v1/*` 的 POST 请求与 `Authorization` 请求头。
4. 终端用户自行在控制面板「APP 绑定」页生成二维码。

**不需要**：修改核心文件、配置伪静态、开放额外端口、部署任何常驻服务。
