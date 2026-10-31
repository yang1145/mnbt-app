# P5 交接：bridge 插件（`app_plugins/mobile_bridge/`）+ 端到端联调

> 面向对象：实现 P5 的智能体/开发者。
> 写成时间：2026-10-20。此时 P1–P4 的 APP 已全部落地并**编译通过**，全程对接本地 mock server，**还没有碰过真实 MNBT 站点**。
> 本文只讲 P5 要做的事、APP 已经冻结的线上事实、以及必须逐条对账的契约缺口。PRD 本体见 `docs/prd/mobile-app.md`（下称「PRD」），不重复它的正文。

---

## 0. 三条前提，先读这个

1. **mock server 是当前字段名的唯一权威，但它的字段名是自造的、未经真实后端验证的。**
   `tools/mock/server.mjs` 里 `case 'xxx': return { ...meta, 字段 }` 的那些业务字段名，是 P2–P4 各功能页的开发代理照着 PRD §4/§5 拟出来的。APP 侧 DTO 已经按它写死了。**这些名字没有和客户 MNBT 源码核对过**（本仓库此前也不含 PHP 代码）。§4 就是对账清单。
2. **改任何 action 的名字/参数/响应字段，必须同时改三处：APP DTO + mock + PRD**（PRD §11.1 规则）。只改 bridge 不改 mock 会造成"APP 单测绿、真站点坏"。
3. **客户的 MNBT 源码在 `D:\Documents\GitHub\MNBT`**（写 P5 时按约定 clone 进工作区核对，用完必须删除，克隆自带的 AGENTS.md/skill 不采纳）。核对之前不要断言"后端就是这么返回的"。

---

## 1. P5 范围与交付物

PRD §11 定为「bridge 插件正式实现 + 端到端联调，15 人日」。具体交付：

| # | 交付物 | 位置 | 依据 |
|---|--------|------|------|
| 1 | 插件本体 | `app_plugins/mobile_bridge/` | PRD §6.1 |
| 2 | 两张新表 | `install.sql` / `uninstall.sql` | PRD §6.2 |
| 3 | 用户面板绑定页 | `views/app_bind.php` | PRD §6.5 |
| 4 | 部署文档（客户侧安装步骤） | `docs/deploy-guide.md` | PRD §863-864 行的交付清单 |
| 5 | 对外接口契约文档 | `docs/api-contract.md` | 同上 |

插件目录结构、建表 DDL、上下文装配六步、`ob_start()+register_shutdown_function()` 适配器、二维码绑定页规格、安全要求表 —— 全部见 PRD §6.1–§6.6，此处不再抄一遍。**核心约束：不修改任何 MNBT 核心文件。**

补充两点实现层面的事实（PRD 没写但会影响你）：
- APP 的响应负载里目前**容忍**也**期望**每条响应带 `meta` 字段：`action`、`gn`、`api`、`bridge_version`、`ts`（见 mock `dataFor()` 开头的 `meta` 对象）。APP DTO 基本忽略它们，但 `bridge_version` 是能力协商（PRD §5.9）的基础，`device.info` 必须返回。
- PRD §11.1 说「P0 spike 插件作为参考实现保留」。**本仓库里没有 spike PHP 代码**（`app_plugins/` 目录不存在）。如果 spike 在别处，P5 开工前先要到它；拿不到就从零写，并把这一条当作风险记录。

---

## 2. APP 侧已冻结的线上协议（bridge 必须逐字满足）

这一节全部是**代码里读出来的事实**，不是推测。违反任何一条 APP 直接坏。

### 2.1 请求

- **URL**：`POST {站点根}/index.php?_r=/app/v1/{action}`
  来源 `app/src/main/java/com/mnbt/app/data/remote/api/MnbtClient.kt:164-171`，`ROUTE_PREFIX = "/app/v1/"`（:251）。
  mock 还额外接受 `{根}/app/v1/{action}` 纯路径形式，但**APP 只发 `index.php?_r=` 这一条**，所以 bridge 的路由必须命中它。
- **鉴权**：`Authorization: Bearer {token}`，除 `pair` 外所有 action 都带（`MnbtClient.kt:143`）。
- **Body 编码**：`application/x-www-form-urlencoded`（**不是 JSON**，PRD §4.5 写的 JSON 已按此实现偏离）。PHP 原生填进 `$_POST`。
- **字节透明上行**：`file.save` 走 `hostCallBytes`，值用 RFC3986 unreserved 之外的**全量百分号编码、十六进制大写**（`MnbtClient.kt:256-267`），GBK/二进制原字节过，PHP `urldecode` 后就是原字节。
  ⚠️ `MnbtClient.kt:42` 顶部注释里"文件内容这类字段由 bridge 约定为 base64"是**历史措辞，实际不是 base64**。上行是原字节百分号编码；下行 `file.read` 才回 `content_base64`。这条注释建议 P5 顺手改掉，别让它误导下一个代理。
- **文件内容类字段的两端约定**：上行 `content` = 原字节；下行 `file.read` = 同时给 `content_base64`（首选，APP 按此还原字节再判编码）和 `content`（lossy UTF-8 兜底）。
- **上传**：`multipart/form-data`，字段 `upload_id` / `path` / `file_name` / `index` / `total` + 文件字段名 `file`，**文件 part 的 MIME 固定 `application/octet-stream`**（`FileBrowserViewModel.kt:553-563`、`MnbtClient.kt:99-119`）。分片切分由 APP 按 `file.upload_prepare` 回传的 `chunk_size` 决定，收敛区间 [64KB, 8MB]。
- **超时**：普通请求用全局读超时；上传给 300s；单个 action 可由页面显式加长。
- **GET 无 body**：APP 不发 GET 业务请求（mock 支持只是测试便利）。

### 2.2 响应

- **一律 JSON**，任何情况下**不得返回 HTML**。PRD §6.4 有这条，`EnvelopeParser` 对 HTML 只能靠文案猜（`站点提示信息` / `id="error-page"`），猜不出来就报协议错误。`sysmsg()` 分支必须在 bridge 层截住并转成 JSON 错误码。
- **标准包络**（bridge 走这一种，PRD §4.5 形态 A）：
  `{"success":bool,"code":int,"msg":string,"redirect":null,"data":{...}}`
  取负载的优先级是 `data` → 顶层非包络键 → `msg`（`EnvelopeParser.kt:165-180`）。`data` 内只剔除 `qk` 一个键，其余原样保留 —— 所以**业务字段可以放在 `data` 里，也可以平铺顶层**，两种都收。
  失败判定优先级：`success` 布尔 > `qk` > 数字 `code` > 已知错误文案（`:128-140`）。数字 `code` 里 0/4/100/300 视为失败，这与"0=成功"矛盾，靠 `success` 优先化解 —— **bridge 必须显式发 `success` 布尔**，别只发 code。
- **响应里可以带 `content_base64` 之类的任意键**，APP 用自己的 kotlinx 序列化按 DTO 取，多余键忽略（`MnbtJson` 配置）。字段名/类型不匹配会抛 `Protocol` 并把原始响应前 2048 字符显示在错误详情里 —— 联调时那串就是最重要的线索。

### 2.3 错误码（PRD §4.6，APP 已按此实现）

| code | 语义 | APP 行为 | 备注 |
|------|------|----------|------|
| 1001 | 令牌无效/已吊销 | 条目置 `needs_repair`，引导重扫 | 「请登陆」这类 MNBT 原文也会被当 1001（`EnvelopeParser.kt:120`） |
| 1002 | 配对码错误 / 已过期 / 已使用 | 配对页报错，不产生令牌 | **配对相关的三种失败一律 1002** |
| 1003 | 主机已到期 | 整页只读（`HostBlocked`） | 曾被子代理误解为"配对码过期"，已纠偏 |
| 1004 | 账号被封禁 | 整页只读 | |
| 1005 | 主机已删除 | 等同 1001，视为需重扫 | |
| 1006 | 宝塔节点配置错误 | 报错 | `user/ajax.php:16` 那条文案 |
| 1007 | 参数错误 | 报错（可带 raw） | |
| 1008 | 限流 | 报错 + 倒计时 | **必须带 `retry_after`（秒）**；缺失时 APP 兜底 60s，倒计时就不准（mock 目前不带，P5 要带） |
| 1009 | 业务保护（`.user.ini` 禁改、运行目录禁删等） | 报错 | mock 已用；PRD §4.6 表里没列，建议补进 PRD |
| 1010 | 未知 action / 插件版本过低 | 「需升级服务端插件」 | 能力协商用 |
| 5000 | 服务端异常 | 报错 + `trace_id` | mock 在 500 分支带 `payload.trace_id` |

状态类错误（1003/1004/1005/1006/1010）**返回 JSON 而非 HTML**，PRD 验收 A7 明确要查这一条。

### 2.4 配对与换绑语义（bridge 侧要对齐的行为）

- 二维码内容 = `{站点根}/index.php?_r=/app/v1/pair_page&c={8位码}`（mock `:756`）。APP 扫码后从中解析出 `baseUrl` 与 `code`（`data/remote/http/PairQr`）。8 位大写去混淆字符集、120s TTL、单次使用、IP 限流（10 分钟 5 次 → 1008）。
- `pair` 成功响应负载：`token`（明文仅此一次，库存 sha256）、`site{ base, name, mnbt_version }`、`host{ user }`。
  ⚠️ **`site.base` 只有与扫码地址同源时 APP 才采纳**（`HostRepository.kt:51-54`，防服务端把 APP 引到别的机器发令牌）。同源判断不过就退回扫码地址，所以 base 写错不会崩，但会静默丢掉规范化结果。
- **重复配对**：同 baseUrl 已有条目时 —— 条目已标记 `needs_repair`，或其令牌在密文库丢失（Keystore 重置）→ APP 允许原地换绑（保留 label/缓存/创建时间，只换 tokenRef/pin）；否则 APP 本地直接拒绝、**不会发出 pair 请求**（`HostRepository.kt:45-47,125-126`）。bridge 不需要为"同地址二次配对"设计幂等语义，但**必须允许同一 `device_id` 换发新令牌**（换绑路径会把旧令牌作废吗？不会 —— APP 只删本地。P5 需要决定：面板侧留旧设备还是按 device_id 覆盖，建议按 `device_id+zj_id` 覆盖并把旧行 `status=0`）。
- `device.revoke` 即时生效、无缓存（PRD §6.6）。APP 删除主机条目时会先尽力调一次 revoke，失败不阻断本地删除（`HostRepository.kt:116-123`）→ 站点侧可能残留孤儿令牌，面板设备列表要能看到并吊销。
- 每次业务请求后 APP 会认为"鉴权成功即令牌可用"，自动清掉 `needs_repair`（`HostCall.kt:51-54`）—— 只针对 `device.ping` / `host.overview`，其余 action 不清。bridge 的 `last_seen_at` 回写节流是 60s（PRD §6.3 第 6 步）。

### 2.5 证书与传输

- APP 用**逐主机证书指纹 pinning**（`data/remote/trust/`）。自签证书首次访问会弹确认页，用户信任后存 SHA-256 指纹。
- 不强制 HTTPS，明文站点 APP 侧强警告（PRD §6.6）。
- 401/403 时 APP 的文案专门提示"可能是 WAF 拦了 Authorization 头"（`MnbtClient.kt:185`）—— 联调遇到 403 先查这条，别先怀疑令牌。

---

## 3. action 全量清单：APP 实际发什么、要什么回

唯一定义处 `app/src/main/java/com/mnbt/app/data/remote/api/MnbtActions.kt`（UI 层不出现裸字符串）。
`gn` 映射见 PRD §5 各表，此处只给 **APP 实际发送的参数名**（这是 bridge 必须读的）和**期望响应关键字段**。带 ⚠️ 的是 §4 里要对账的。

### 3.1 配对与设备

| action | 上行参数 | 期望响应 |
|--------|----------|----------|
| `pair` | `code` `device_id` `device_name` `device_model` `app_version` `android_version` | `token`、`site.base`、`site.name`、`site.mnbt_version`、`host.user` |
| `device.ping` | — | 空对象即可（APP 用它清 needs_repair 标记） |
| `device.info` | — | `bridge_version`（+ MNBT 版本），能力协商 |
| `device.revoke` | — | 布尔/空 |

`device_id` = APP 生成的稳定 ID（`data/remote/http/DeviceInfo.kt`），对应 §6.2 的 `device_id` 列。

### 3.2 概览

| action | 上行 | 期望响应字段 |
|--------|------|--------------|
| `host.overview` | — | 用量：`web`/`sql`/`traffic`（含 used/total/unit）、`cpu`、`mem{used,total,unit}`、`inode_used`、`inode_total` |
| `host.profile` | — | `user` `domain` `site_name` `status` `expire` `expire_ts` `node` `php` `ymbds` `created_at` |

### 3.3 站点设置（PRD §5.2）

| action | 上行 | 备注 |
|--------|------|------|
| `site.init` | `section` = `php`\|`yxml`\|`mrwd`\|`wjt`\|`gzip`\|`pass`\|`xgpass`\|`url` | 返回该 section 的当前值 + 选项列表（mock 形状：`{current, options}` / `{list}` / `{user}`）⚠️ PRD 列的 8 个 section 里**没有 `indexconf`** |
| `site.php.set` | `php_version` | 回 `phpversion` 或 `php_version`（APP 读 `phpversion`） |
| `site.rundir.set` | `rundir` | 回 `rundir` |
| `site.indexdoc.set` | `index` | 回 `indexdoc` |
| `site.rewrite.get` | `name` | 回 `current` `rewrite` `list[]` ⚠️ 参数名 `name` 是猜的 |
| `site.rewrite.set` | `rewrite` | 回 `rewrite` |
| `site.gzip.set` | `gzip` | 回 `gzip` |
| `site.passdir.list` | 用 `site.init&section=pass` 而非本 action | ⚠️ 列表项无 `pass` 字段，而 `site.passdir.list` 有（打码 `****`）—— 两入口字段集不一致 |
| `site.passdir.add` | `user_dir` `user` `pass` | ⚠️ `pass` 参数名是 APP 依网页端 `tjmmfw` 惯例猜的，mock 不读 |
| `site.passdir.del` | `user_dir` | |
| `site.password.set` | `user` `pass` | ⚠️ 新密码参数名未定（`pass` 还是 `newpass`），是否需要旧密码未定 |
| `site.indexconf.set` | `index_conf` | ⚠️ 无初始化 section，APP 页面退化为默认 on/off |
| `site.hotlink.get` | — | 回 `open`(int) `domains` `exts` `lasts` |
| `site.hotlink.set` | `open` `domains` `exts` `lasts` | |
| `site.proxy.set` | `proxy_url` `target_url`（**逐条 POST**） | 回 `list[{proxyUrl, targetUrl}]` ⚠️ 上行下划线、回显驼峰，不对称；⚠️ **没有删除 action**，APP 用 `target_url=""` 提交来推测删除 |
| `site.cache.list` | — | `list[{id,url,type,seconds}]` |
| `site.cache.add` | `url` `type` `seconds` | ⚠️ mock 不读 `seconds`；`type` 枚举 `page/image/file` 是推定占位 |
| `site.cache.edit` | `id` `url` `type` `seconds` | ⚠️ mock 不读 `seconds`/`type` |
| `site.cache.del` | `id` | |

### 3.4 文件管理（PRD §5.3，全部核心 handler）

| action | 上行 |
|--------|------|
| `file.list` | `path`（站点相对路径，以 `/` 开头）⚠️ mock 递归展平整棵子树，APP 端过滤当前层；bridge 宜**按层返回** |
| `file.read` | `file` + `path`（同值双发） |
| `file.save` | `file` `path` `content`（**原字节**，见 §2.1） |
| `file.create` | `path` `type`(`file`\|`dir`) |
| `file.delete` | `path` |
| `file.delete_batch` | `paths[]` ⚠️ APP 的 `hostCall` 不支持数组参数，实际是**逐条 `file.delete` 循环**（`FileBrowserViewModel.kt:236`） |
| `file.rename` | `src` `dest`（mock 同时兼容 `old_name`/`new_name`） |
| `file.copy` | `src` `dest` |
| `file.compress` | `files`（单条路径）`dest` |
| `file.unzip` | `src` `dest`（响应回 `dir`） |
| `file.size` | `files` |
| `file.upload_prepare` | `path` → 回 `upload_id` `max_size` `chunk_size` `path` |
| `file.upload` | multipart，见 §2.1 ⚠️ 分片合并语义未冻结（mock 每片整体覆盖落盘） |
| `file.download` | `file`（或 `path`）→ 回 `url` ⚠️ token-in-query 方案未定，见 §4 |
| `file.access.get` | `path` → `perm` `owner` `group` |
| `file.access.set` | `path` `perm` `recurse`(`0`/`1`) |

保护（`.user.ini` 禁改、运行目录禁删）由核心 handler 内置，bridge 不重复实现；但**核心是 `exit()` 打 HTML 还是 JSON 要核对** —— 若走 `sysmsg()` 就违背 §2.2，需在适配器里转 1009。

### 3.5 数据库（PRD §5.4）

| action | 上行 |
|--------|------|
| `db.list` | — → `list[{name,username,charset,state,addtime,backups[{name,size,time}]}]` + `quota{used,max,unit}` ⚠️ PRD §7.2 要每库容量，mock 只有聚合 quota |
| `db.create` | `name`（或 `data_account`，APP 二者之一）→ 回新密码 |
| `db.delete` | `data_account` |
| `db.delete_all` | — → 回计数（`db.delete` 回布尔，两者形状不一致） |
| `db.backup.download` | `file` → ⚠️ **PRD §5.4 说是文件流，mock 实返 `{url}`**；APP 现按 mock：解析 `url` 后用 `rawClient` + Bearer 头 GET |
| `db.backup.restore` | `name`(备份名) + `data_account`(库名) |
| `db.access.set` | `name` `host` `port` ⚠️ 设置后**无法读回当前值**（无 get） |
| `db.sql.import` | multipart，字段 `name`(库名) + 文件，**整文件一次上传** ⚠️ 413 风险，需要分片或流式方案 |

参数命名在 `name` / `data_account` 之间不统一，是 bridge 定夺后回写 APP 的第一优先项。

### 3.6 域名与 SSL（PRD §5.5）

| action | 上行 |
|--------|------|
| `domain.list` | — → `list[{domain,port,ssl,type,status}]` ⚠️ 形状未冻结（无 `total`，APP 用 list.size） |
| `domain.subdir.list` | — → `list[{domain,dir}]` |
| `domain.add` | `domain` + `webname`（**同值双发**，兼容 `btapi_addym` 历史参数名）+ 可选 `dir` ⚠️ `dir` 未被真实 handler 消费待查；「带 www 双域名」是 APP 循环 add 两次，第二个失败会留半完成态 |
| `domain.del` | `domain` `webname` `port` |
| `ssl.get` | — ⚠️ 真实 `getssl` 历史上回 `qk/msg` 裸形，且"无证书"与"取不到"APP 现在分不开 |
| `ssl.apply` / `ssl.renew` / `ssl.close` | 无参（映射 `sqssl&type=false` / `true` / `clossl`） |
| `ssl.custom` | `pem` `key` |
| `ssl.force_https` | `qk`(`1`/`0`) |

`ymbds` 上限校验超出回 1007（PRD §5.5 注）。

### 3.7 监控 / 通知 / 部署（PRD §5.6–5.7）

| action | 上行 |
|--------|------|
| `monitor.list` | — |
| `monitor.save` | `id`(0=新增) `name` `type` `target` `cycle` ⚠️ 后四个字段名按 `monitor.list` 同名假定 |
| `monitor.delete` / `monitor.log` | `id` |
| `monitor.toggle` | `id` `status` |
| `notice.list` / `notice.read` | `notice.read` 带 `id` |
| `deploy.list` | — |
| `deploy.form` | `bs_id`（+ `id`，二者同值，见 `DeployViewModel.kt:91`） |
| `deploy.run` | `bs_id` + 表单字段逐项平铺（标量；⚠️ 数组型表单字段目前发不出去） |

监控告警数 ⚠️ APP 用 `last_code≥400` 推断，bridge 宜直接给聚合字段。
部署图标 `MN_bs.src` 是**同站点资源**（不违背零站外依赖），但两条硬约束在 PRD §5.7 注里：相对路径要按主机 base URL 补全；Coil 必须用 `MnbtClient.rawClient(pin)` 注入主机专属 client，否则手工信任证书的站点图片全部握手失败。

---

## 4. 契约对账清单（P5 的核心动作）

来源：P2–P4 各功能页交付报告，已固化在项目记忆 `mnbt-app-p5-contract-debts.md`。分两类处理：

**A 类 —— 去客户 MNBT 源码核对**（`D:\Documents\GitHub\MNBT`，读完即删）：
所有"mock 自造的响应字段名"是否与真实 handler 输出一致；`sysmsg()` 在哪些分支命中；核心保护（`.user.ini`/运行目录）返回什么；`getssl`/`listurl`/`file_list` 的实际形状；`indexconf` 当前值从哪读；缓存 `type` 的权威枚举；监控 save 的真实字段名；`host.overview` 各用量字段与 `sxsyxx` 的对应。

**B 类 —— 源码里没有、必须新设计并回写 mock+PRD+APP 的缺口：**

1. `site.init` 补 `indexconf` section（或给当前值另一个来源）。
2. **反向代理缺删除语义**：定「整表覆盖 set」或专用 delete。参数命名上下划线/回显驼峰的不对称要不要统一（改了要同步 APP 的 DTO）。
3. `site.passdir.add` 的密码参数名；`site.init(pass)` 列表与 `site.passdir.list` 字段集对齐。
4. `site.password.set` 的新密码参数名、是否需要旧密码。**密码经明文 form POST，bridge 侧 `logjl()` 必须脱敏，绝不能把 `pass` 写进 MNBT 日志**（APP 侧也不会写日志）。
5. `site.cache.add/edit` 读 `seconds`、`edit` 读 `type`；`type` 权威枚举。
6. **数组参数协议**：`hostCall` 不支持 `name[]=`。要么定"逐条循环"为正式协议（APP 已这么用），要么设计 JSON-in-form 的批量形状。涉及 `file.delete_batch`、`domain.add` 带 www、`deploy.run` 的数组字段。
7. **文件下载的 token-in-query**：下载链接不能带 Authorization 头时怎么鉴权（一次性短 TTL 签名 URL？还是允许 `token=` 查询参数——mock 现在用后者，会把令牌泄露进访问日志/Referer，不建议照搬）。同一问题也卡 `db.backup.download`。
8. **暂停/解停没有 action**（PRD §7.2 菜单里有，§5 映射表里没有）。
9. **反向代理只读 get 缺失**（只有 set）；**伪静态详情页当前值**没走 `site.rewrite.get`。这两处 APP 详情页现在恒显示「—」。
10. `db.list` 的每库容量字段；`db.access.set` 的可读回；`db.sql.import` 的分片/流式方案（413）。
11. `file.list` 改按层返回。
12. `monitor` 告警聚合字段；证书"确认于"时间需要 HostEntity 之外的来源（现在用 `createdAt` 顶替）。
13. **1008 响应必须带 `retry_after`**。
14. §7.3 写「19 项功能」但 ASCII 画了 20 行，APP 按 20 实现了 —— PRD 改文档。

---

## 5. mock server：联调时的对照物

`tools/mock/server.mjs`（纯 node 标准库，无 npm 依赖，只监听 127.0.0.1）+ `test.mjs`（51 项断言）。用法见 `tools/mock/README.md`。

P5 会用到它的三个特性：
- **四种响应形态切换**：`?_fmt=1|2|3|4` 或 `X-Mock-Format` 头。bridge 只发形态 1，但 APP 的容忍逻辑已在 mock 上验证过，真站点若冒出形态 2/3 可以立刻分辨。
- **状态位**：`/__mock__/state?set=expired|banned|deleted|node_unreachable|low_plugin`，用来验 PRD 验收 A7/A8。
- **真语义沙盒**：`tools/mock/.sandbox/wwwroot/demo01.example.com/` 下 `file.read/save/list/delete/upload` 真读写，含一个 **GBK 编码的 `config.php`** —— 这是字节透明链路的阳性对照。**端到端联调必须重跑这个用例**：编辑 GBK 文件保存后原字节不变，说明 `hostCallBytes` → PHP `urldecode` 这条链是对的。

**已知 mock 假数据**（不要拿它的回显当真）：`site.passdir.*`、`site.cache.*` 是静态假数据，增删改后 reload 看不到结果；`file.upload` 每片整体覆盖写盘。

改 action 契约时的顺序：PRD → mock（含 `test.mjs` 断言）→ APP DTO → 本表 §3。

---

## 6. APP 代码里的 P5 接口点

19 处 `P5` 注释标记，是 bridge 完成后要回查/真实化的位置：

| 位置 | 待办 |
|------|------|
| `ui/containers/ContainersScreen.kt:24,46` | 容器 tab 是占位；docker 侧探测靠 bridge，真实扫码 + 「该站点未开通容器服务」提示 |
| `ui/nav/Destinations.kt:11` | 能力协商的按入口灰显尚未接（`device.info` → 插件版本 → 1010） |
| `ui/database/DatabaseModels.kt:13` | 各 action 入参在文件头注释，逐项对表处 |
| `ui/filemanager/FileBrowserViewModel.kt:222` | 批量删除逐条循环 workaround |
| `ui/filemanager/FileBrowserViewModel.kt:537` | 分片上传完整语义待真 bridge |
| `ui/domainssl/DomainsViewModel.kt:24` | `dir` 附带提交是否被真实 handler 消费 |
| `ui/site/SiteAGzip.kt:31` / `SiteAHotlink.kt:40` / `SiteAIndexconf.kt:35` / `SiteAIndexdoc.kt:28` / `SiteAPhp.kt:31` / `SiteAProxy.kt:59` / `SiteARewrite.kt:39` / `SiteARundir.kt:34` | 每页头部有「参数表（P5 对表）」注释，直接对着核对 |
| `ui/site/SiteBCacheScreen.kt:378` | `seconds` 是 APP 补的，mock 不读 |
| `ui/site/SiteBModels.kt:67` | 缓存 `type` 三分类是推定占位 |
| `ui/site/SiteBPassdirScreen.kt:290` | `pass` 参数名待对齐 |
| `ui/site/SiteBPasswordScreen.kt:214` | 新密码参数名 + 明文 POST 风险 |

---

## 7. 环境与构建配方（照抄即可，别重新踩）

APP 目前**编译通过**（`BUILD SUCCESSFUL in 39s`，产物 `app/build/outputs/apk/debug/app-debug.apk`，约 21.99 MB，2 个 warning）。以下每一项都是踩出来的，改动前先读项目记忆 `mnbt-app-android-build-environment.md`。

```bash
# 构建命令（必须先问用户 —— 他的机器被自行起构建卡死过）
JAVA_HOME="C:/Program Files/Java/jdk-21" \
GRADLE_USER_HOME="D:/Documents/GitHub/mnbt-app/.gradle-home" \
./gradlew :app:assembleDebug
```

- **JDK 用 21**，机器默认 JDK 25 与 Gradle 8.10.2 不兼容。
- **`GRADLE_USER_HOME` 指到项目内 `.gradle-home`**：用户的 `~/.gradle/gradle.properties` 里 `proxyPort=0` 是坏的，且**用户级 gradle.properties 会覆盖项目根**，所以在仓库里改配置没用。`.gradle-home` 里配了 7897 代理（他的 Clash 类客户端），已在 `.gitignore` 排除（1.7GB，绝不入库）。**不要动他的全局文件。**
- **Android Studio**：用户自己在 AS 设置里改两处（Gradle user home → `.gradle-home`，Gradle JDK → jdk-21）。AS 默认走 `~/.gradle` 会报 `Plugin com.android.library:8.8.0 not found`。
- `buildToolsVersion` 锁 35.0.1；minSdk 26 / compileSdk 35；`jvmTarget` 必须 17（sora-editor 的 Kotlin 内联函数是 JVM 17 产物，11 展开不了）。
- **sora-editor 版本混用是故意的**：`editor 0.23.6` + `language-textmate 0.22.2`。0.23.0 起 textmate 内嵌带 Java record 的 tm4e，AGP 8.8 的 external-libs dexing transform 恒定 `enableGlobalSynthetics=false`，minSdk 26 下 record 无法脱糖，`mergeExtDexDebug` 直接失败。试过并排除的开关：`android.enableGlobalSyntheticsGeneration`、`android.enableDexingArtifactTransform`（AGP 8.3 已移除）、`android.useFullClasspathForDexingTransform` —— **别再试这三个**。已验证两个 editor 版本之间 `Language`/`EmptyLanguage` 的 ABI 一致，混用安全。
- Windows 报 `Could not move temporary workspace (…)` → `./gradlew --stop` 后只删那两个 `9aec736a…-*` 临时 transform 目录，不要清整个缓存。
- Kotlin 陷阱：**块注释会嵌套**，KDoc 里写 `*.php` 或 `*/*` 会把文件解析弄坏。

---

## 8. 红线与验收

**红线（违反即返工）：**
1. **不修改任何 MNBT 核心文件**（PRD §6.1）。
2. **运行时零站外依赖**：二维码用本地 JS 渲染 canvas，`lib/qrcode/` 本地资源，不许引 CDN；部署图标只允许同站点资源。
3. **绝不把 HTML 透传给 APP**（PRD §6.4）：捕获的输出不是合法 JSON 就返回 5000 并记日志。
4. **密码等敏感参数不进 `logjl()`**（§4 第 4 条）。
5. **令牌明文只下发一次**，库存 sha256；吊销即时生效。
6. 越权防护：所有操作强制以令牌绑定的 `zj_id` 的 `$yhc` 为上下文；handler 自身的路径规范化与站点目录限制照旧生效。

**PRD §12.1 里 P5 承担的验收项**：A1（抓包无站外请求）、A4（§5 每个 action 都有对应页面且与网页面板结果一致）、A5（未装 `domain_shop` 的站点上域名增删可用 → 靠自实现 `btapi_addym/delym`）、A6（未装 `mnbt_connector` 时不出现站点统计入口）、A7（1003/1004/1005 三种状态且**服务端回 JSON 不回 HTML**）、A8（面板侧吊销后下次请求即 1001）、A10（文件管理全链路，含 GBK PHP 编辑）。A9 是 SSH 终端，P4 已完成、与 bridge 无关，但**验收风险仍在**：helper 键条的 Ctrl 是一次性 latch、软键盘字母不经 `onKeyDown`，Ctrl+C 靠物理键；字号仅会话级；返回键不断连。

---

## 9. 未验证项 / 已知风险（诚实清单）

1. **APP 一次都没在真机/模拟器上跑过。** 全部结论来自编译 + mock 自测。运行时行为（导航、Compose 布局、Room 迁移 1→2、TokenVault/Keystore、证书信任页、SSH 会话）全部待验证。P5 端到端联调第一次真跑就要覆盖这些。
2. **协议偏差集中暴露在 P5**（PRD §11.1 明说的风险）。上面 §4 那张清单就是它的账，还没结。
3. **P0 spike PHP 不在本仓库**，参考实现可能缺失。
4. **真站点预演**（PRD §11.1 第 3 条）在 P2 末并没有真正做过（无可用的真实/测试站点凭据）—— P5 的第一件事应该是拿一个能连的 MNBT 站点跑 `device.info` + `pair` + `host.overview` 三个最小闭环，再展开 §3 全表。
5. sora-editor 版本混用（0.23.6 + 0.22.2）在编辑器功能上有已知降级：`themeRegistry.loadTheme` 在 0.22.2 没有具名参数，语法高亮整体可用但未回归真机。
6. 快捷键栏的横向滚动条被去掉了（Compose foundation 1.7.6 没有 `horizontalScrollbar` API），能滚但没有视觉条。
7. Room schema 是 v2；MIGRATION_1_2 给 `hosts` 加 `needs_repair`。真机首装走 v2 建表，老装机的迁移路径从未运行过。

---

## 10. 建议的 P5 执行顺序

1. 拿一个可用 MNBT 站点（客户测试站或本地装一套），**先跑最小闭环**：路由命中 → `pair` → `device.ping` → `host.overview`。这一步会立刻验掉 §2 的大半（`index.php?_r=` 路由、Bearer 头、form 编码、包络、`success` 布尔优先级）。
2. 写 `install.sql`（两张表）+ `lib/token.php` + `lib/pair.php` + `views/app_bind.php`，把配对闭环在浏览器里跑通。
3. 写 `lib/context.php`（复刻 `user/ajax.php:8-25`，PRD §6.3 六步 + 错误码映射）和 `lib/adapter.php`（`ob_start` + `register_shutdown_function` + 三形态容忍 + 非 JSON→5000）。**这一步单独验 1003/1004/1005/1006 全部走 JSON**（验收 A7）。
4. 按 §4 的 A 类清单去客户源码逐条核对，边核对边写 `lib/actions.php` 的映射表；每定一条契约就同步改 mock + PRD + APP DTO（一次一个 action 组，别攒）。
5. 文件组最后做（字节透明 + GBK 阳性对照 + 分片合并 + 下载 token 方案）。
6. 补 `docs/deploy-guide.md`（客户安装步骤：上传插件目录、装插件、面板绑定页入口、HTTPS 建议、WAF 放行 `Authorization` 头）与 `docs/api-contract.md`（对外契约，从本文 §2/§3 沉淀，字段以最终冻结版为准）。
