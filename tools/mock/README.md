# MNBT APP Mock Server（P1–P4 开发对接用）

纯 node 标准库（v20+，无 npm 依赖）实现的本地 mock，严格按 `docs/prd/mobile-app.md` §4/§5/§6.2 契约模拟 bridge 插件协议。只监听 127.0.0.1。

## 起服务

```bash
node tools/mock/server.mjs                 # 默认 http://127.0.0.1:8787，启动即打印一个初始配对码+二维码URL
node tools/mock/server.mjs --port=9000     # 换端口
node tools/mock/server.mjs --tls            # https 自签模式（certs/ 下 openssl 预生成的 dev 证书，启动时打印 SHA-256 指纹，用于验证 APP 证书信任屏与指纹 pinning）
node tools/mock/server.mjs --state=expired  # 启动即处于「主机已到期」状态
```

## 配对

- 服务运行中随时生成新码：`node tools/mock/server.mjs --gen-code [port]`（或 `GET /__mock__/gen_pair_code`）。输出配对码、完整二维码 URL（`http://127.0.0.1:{port}/index.php?_r=/app/v1/pair_page&c=...`）与手输 base+码。
- 配对码规则照 §4.3：8 位大写去混淆字符集、120s TTL、单次使用；`?ttl=毫秒` 可缩短用于测试过期。
- 令牌 64 位 hex，仅配对响应明文下发一次，内存只存 sha256（§6.2 `token_hash` 语义）；`device.revoke` 即时生效。

## 切主机状态（错误码模拟，§4.6）

```bash
curl "http://127.0.0.1:8787/__mock__/state?set=expired"           # 业务 action -> 1003
# 可选: running | expired(1003) | banned(1004) | deleted(1005) | node_unreachable(1006) | low_plugin(1010)
curl "http://127.0.0.1:8787/__mock__/state"                        # 查看当前状态
curl -X POST "http://127.0.0.1:8787/__mock__/reset"                # 清空配对码/设备/限流计数（回到 running）
```

状态错误一律返回 JSON 包络（§4.6 注：不得是 HTML）；`device.info`/`device.ping` 不受 low_plugin 拦截，便于能力协商验证。

## 测四种响应形态（§4.5）

任意业务 action 加 `&_fmt=1|2|3|4`（放在 `_r` 之前，如 `/index.php?_fmt=2&_r=/app/v1/host.profile`）或请求头 `X-Mock-Format: 2`。默认全部形态 1（标准包络，载荷同时平铺顶层 + `data`）。形态 2 = `qk/code文本/msg载荷`；形态 3 = 无成功标志裸字段；形态 4 = `sysmsg()` HTML 页。

## 真语义部分

- `file.read` / `file.save` / `file.list` / `file.delete*` / `file.upload` 在 `tools/mock/.sandbox/wwwroot/demo01.example.com/` 下真读写（首启自动播种，含一个 GBK 编码的 `config.php`）。保存走 `application/x-www-form-urlencoded` 的百分号编码原始字节（GBK 可原样往返）；读取载荷含 `content_base64`。`file.upload` 校验 multipart、要求文件字段 MIME 为 `application/octet-stream`。
- `host.overview` 的 web/sql/traffic 用量每次刷新随机小幅漂移，便于验证 diff 刷新。
- `domain.add` 按 `ymbds=5` 校验最大绑定数，超出返回 1007。

## 自测

```bash
node tools/mock/test.mjs    # 同进程随机端口起服务，51 项断言，结束自动关闭
```
