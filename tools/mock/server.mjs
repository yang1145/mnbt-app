// tools/mock/server.mjs
// MNBT 移动 APP 本地 mock server —— 严格按 docs/prd/mobile-app.md §4/§5/§6.2 契约实现。
// 纯 node 标准库，零 npm 依赖。仅监听 127.0.0.1，开发用途。
//
// 同文件还内置 ZJMF-CBAP（智简魔方V10业务系统 云站点）段落，按 docs/cloud-integration.md 契约实现，
// 路由前缀 /console/v1/，与 MNBT 的 index.php?_r= 路由互不干扰。
//
// 用法：
//   node tools/mock/server.mjs [--port=8787] [--host=127.0.0.1] [--state=running] [--tls]
//   node tools/mock/server.mjs --gen-code [port]
//
// 管理路由（开发辅助，不需要令牌）：
//   GET/POST /__mock__/gen_pair_code[?ttl=毫秒]   生成新配对码（默认 120s TTL）
//   GET      /__mock__/state                      查看主机状态开关
//   GET/POST /__mock__/state?set=expired|...      切换主机状态
//   POST     /__mock__/reset                      清空配对码/设备/限流/ZJMF 状态（测试用）
//   GET      /__mock__/help

import http from 'node:http';
import https from 'node:https';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

// ---------------------------------------------------------------- 常量（§4.3/§4.4）

// 8 位大写、去混淆字符集（排除 0 O 1 I）
const CODE_CHARSET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
const CODE_LEN = 8;
const CODE_TTL_MS = 120_000;
// IP 维度 10 分钟内最多 5 次配对失败
const PAIR_FAIL_WINDOW_MS = 600_000;
const PAIR_FAIL_MAX = 5;

const BRIDGE_VERSION = '1.0.0-mock';
const MNBT_VERSION = '1.88';

// 模拟 MN_zj 单主机档案（§6.3 上下文装配的数据源）
const HOST = {
  zj_id: 1,
  user: 'demo01',
  domain: 'demo01.example.com',
  site_name: '梦奈云主机',
  mnbt_version: MNBT_VERSION,
  expire: '2026-11-21',
  ssbt: 'node-a',
  php: '7.4',
  ymbds: 5, // 最大可绑域名数（domain.add 校验）
};

// 用量基线（host.overview 在此基线上随机小幅漂移，便于 APP 验证 diff 刷新）
const QUOTA_BASE = {
  web: { used: 620, max: 1024, unit: 'MB' },
  sql: { used: 12, max: 512, unit: 'MB' },
  traffic: { used: 1843, max: 10240, unit: 'MB' },
};

// 状态开关 → 错误码（§4.6）
const STATE_ERRORS = {
  running: null,
  expired: { code: 1003, msg: '主机已到期，仅可查看' },
  banned: { code: 1004, msg: '主机已被封禁（MN_zj.qk=false）' },
  deleted: { code: 1005, msg: '主机已删除，请移除该主机条目' },
  node_unreachable: { code: 1006, msg: '宝塔节点配置错误 / 节点不可达，请联系站点管理员' },
  low_plugin: { code: 1010, msg: 'bridge 插件版本过低，不支持该 action，请升级服务端插件' },
};

// ---------------------------------------------------------------- ZJMF-CBAP（云站点）常量
// 全部按 docs/cloud-integration.md 契约：包络 {status, msg, data}、Bearer mock-jwt-*、
// POST 必须带 _r（毫秒时间戳字符串）、电源操作校验 client_operate_password。

const ZJMF_SITE = {
  site_name: 'MockIDC 云',
  operate_password_required: true,
};
const ZJMF_OPERATE_PASSWORD = '123456';
const ZJMF_TOKEN_TTL = 2_592_000; // 30 天（秒）

// 三台样例机：
//   ① mf_cloud / on      / Active / 有 dedicate_ip，due_time 距今 5 天（ISO8601）
//   ② remf_cloud / off   / Active，due_time 距今 90 天（ISO8601）
//   ③ mf_cloud / operating / due_time 已过期 3 天（故意用秒级时间戳字符串，验证时间字段兼容）
const ZJMF_SERVERS = [
  {
    id: 1, name: '云服务器-01', product_name: '云服务器 ECS·标准型', status: 'Active',
    module: 'mf_cloud', dedicate_ip: '203.0.113.10',
    due_time: new Date(Date.now() + 5 * 86400_000).toISOString(),
    billing_cycle_name: '月付', active_time: new Date(Date.now() - 30 * 86400_000).toISOString(),
  },
  {
    id: 2, name: '云服务器-02', product_name: '云服务器 ECS·入门型', status: 'Active',
    module: 'remf_cloud', dedicate_ip: '',
    due_time: new Date(Date.now() + 90 * 86400_000).toISOString(),
    billing_cycle_name: '年付', active_time: new Date(Date.now() - 200 * 86400_000).toISOString(),
  },
  {
    id: 3, name: '云服务器-03', product_name: '轻量应用服务器', status: 'Active',
    module: 'mf_cloud', dedicate_ip: '198.51.100.77',
    due_time: String(Math.floor((Date.now() - 3 * 86400_000) / 1000)),
    billing_cycle_name: '月付', active_time: new Date(Date.now() - 10 * 86400_000).toISOString(),
  },
];

// 电源操作 func → [终态 status, 终态 desc]；on/off 立即生效，reboot/hard_* 先 operating 2 秒
const ZJMF_POWER_FUNCS = {
  on: ['on', '运行中'],
  off: ['off', '已关机'],
  reboot: ['on', '运行中'],
  hard_off: ['off', '已强制关机'],
  hard_reboot: ['on', '运行中'],
};
const ZJMF_INSTANT_FUNCS = new Set(['on', 'off']);

function zjmfInitialPower() {
  return new Map([
    [1, { status: 'on', desc: '运行中' }],
    [2, { status: 'off', desc: '已关机' }],
    [3, { status: 'operating', desc: '操作执行中' }],
  ]);
}

// ---------------------------------------------------------------- 动作注册表（§5 全量）

// action -> { gn, api }，mock 只用于生成 meta 回显与 device.info 支持清单
const ACTIONS = {};
const def = (action, gn, api) => { ACTIONS[action] = { gn, api }; };

// 5.1 概览与用量
def('host.overview', 'sxsyxx', 'site');
def('host.profile', 'bridge_self', 'app');
// 5.2 站点设置
def('site.init', 'set_init', 'site');
def('site.php.set', 'phpxg', 'site');
def('site.rundir.set', 'setyxml', 'site');
def('site.indexdoc.set', 'xgmrwd', 'site');
def('site.rewrite.get', 'hqjt', 'site');
def('site.rewrite.set', 'setwjt', 'site');
def('site.gzip.set', 'setgzip', 'site');
def('site.passdir.list', 'set_init&section=pass', 'site');
def('site.passdir.add', 'tjmmfw', 'site');
def('site.passdir.del', 'scmmfw', 'site');
def('site.password.set', 'xgpass', 'site');
def('site.indexconf.set', 'indexconf', 'site');
def('site.hotlink.get', 'getfdl', 'site');
def('site.hotlink.set', 'fdlkg', 'site');
def('site.proxy.set', 'fzjh', 'site');
def('site.cache.list', 'cache_list', 'site');
def('site.cache.add', 'cache_add', 'site');
def('site.cache.edit', 'cache_edit', 'site');
def('site.cache.del', 'cache_del', 'site');
// 5.3 文件管理（§5.3 表格实际共 16 项）
def('file.list', 'file_list', 'file');
def('file.read', 'file_read', 'file');
def('file.save', 'file_save', 'file');
def('file.create', 'file_create', 'file');
def('file.delete', 'file_delete', 'file');
def('file.delete_batch', 'file_delete_batch', 'file');
def('file.rename', 'file_rename', 'file');
def('file.copy', 'file_copy', 'file');
def('file.compress', 'file_compress', 'file');
def('file.unzip', 'file_unzip', 'file');
def('file.size', 'file_size', 'file');
def('file.upload_prepare', 'file_upload_prepare', 'file');
def('file.upload', 'file_upload', 'file');
def('file.download', 'file_download', 'file');
def('file.access.get', 'file_access', 'file');
def('file.access.set', 'file_access_set', 'file');
// 5.4 数据库
def('db.list', 'backup_list+set_init&section=mysqlcz', 'database');
def('db.create', 'databaseadd', 'database');
def('db.delete', 'databasedel', 'database');
def('db.delete_all', 'Delalldatabase', 'database');
def('db.backup.download', 'databasedownload', 'database');
def('db.backup.restore', 'databaserestore', 'database');
def('db.access.set', 'databaseaq1', 'database');
def('db.sql.import', 'sqldr', 'database');
// 5.5 域名与 SSL
def('domain.list', 'listurl', 'domain');
def('domain.subdir.list', 'hqzmlls', 'domain');
def('domain.add', 'btapi_addym', 'app');
def('domain.del', 'btapi_delym', 'app');
def('ssl.get', 'getssl', 'domain');
def('ssl.apply', 'sqssl&type=false', 'domain');
def('ssl.renew', 'sqssl&type=true', 'domain');
def('ssl.custom', 'setssl', 'domain');
def('ssl.close', 'clossl', 'domain');
def('ssl.force_https', 'httpsqz', 'domain');
// 5.6 监控与通知
def('monitor.list', 'monitor_list', 'monitor');
def('monitor.save', 'monitor_save', 'monitor');
def('monitor.delete', 'monitor_del', 'monitor');
def('monitor.toggle', 'monitor_toggle', 'monitor');
def('monitor.log', 'monitor_log_list', 'monitor');
def('notice.list', 'notice_list', 'panel');
def('notice.read', 'notice_read', 'panel');
// 5.7 一键部署
def('deploy.list', 'deploy_list', 'deploy');
def('deploy.form', 'yjbsform', 'deploy');
def('deploy.run', 'yjbs', 'deploy');
// 5.8 设备管理
def('device.revoke', 'bridge_self', 'app');
def('device.ping', 'bridge_self', 'app');
def('device.info', 'bridge_self', 'app');

// 不需要 Bearer 令牌的路由（任务约定：pair / pair_page / device.info）
const NO_AUTH = new Set(['pair', 'pair_page', 'device.info']);

// ---------------------------------------------------------------- 小工具

const sha256hex = (s) => crypto.createHash('sha256').update(s).digest('hex');

function genPairCode() {
  let out = '';
  for (let i = 0; i < CODE_LEN; i++) {
    out += CODE_CHARSET[crypto.randomInt(CODE_CHARSET.length)]; // 对齐 random_int() 逐位取
  }
  return out;
}

function nowStr() {
  const d = new Date();
  const p = (n, w = 2) => String(n).padStart(w, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

function jitter(used, span = 8) {
  return Math.max(0, used + crypto.randomInt(-span, span + 1));
}

// 百分号解码到 Buffer（字节透明，GBK/二进制可原样还原；'+' 视为空格）
function decodePercent(buf) {
  const out = [];
  for (let i = 0; i < buf.length; i++) {
    const c = buf[i];
    if (c === 0x25 /* % */) {
      out.push(parseInt(buf.subarray(i + 1, i + 3).toString('latin1'), 16));
      i += 2;
    } else if (c === 0x2b /* + */) {
      out.push(0x20);
    } else {
      out.push(c);
    }
  }
  return Buffer.from(out);
}

// 解析 application/x-www-form-urlencoded：
// - 值保留原始 Buffer（raw），字符串视图按 UTF-8 解码（str）
// - name[]=v1&name[]=v2 归并为数组（PHP 约定）
function parseForm(buf) {
  const fields = new Map(); // name -> {str, raw, arr?}
  if (!buf || buf.length === 0) return fields;
  for (const seg of buf.subarray(0).toString('latin1').split('&')) {
    if (!seg) continue;
    const eq = seg.indexOf('=');
    const keyB = decodePercent(Buffer.from(eq < 0 ? seg : seg.slice(0, eq), 'latin1'));
    const valB = eq < 0 ? Buffer.alloc(0) : decodePercent(Buffer.from(seg.slice(eq + 1), 'latin1'));
    let key = keyB.toString('utf8');
    const isArray = key.endsWith('[]');
    if (isArray) key = key.slice(0, -2);
    if (isArray) {
      let e = fields.get(key);
      if (!e || !e.arr) {
        e = { arr: [], rawArr: [] };
        fields.set(key, e);
      }
      e.arr.push(valB.toString('utf8'));
      e.rawArr.push(valB);
    } else {
      fields.set(key, { str: valB.toString('utf8'), raw: valB });
    }
  }
  return fields;
}

function formStr(fields, name, dft = undefined) {
  const e = fields.get(name);
  if (!e) return dft;
  if (e.arr) return e.arr;
  return e.str;
}
function formRaw(fields, name) {
  const e = fields.get(name);
  return e ? e.raw : undefined;
}

// 解析 multipart/form-data（file.upload 透传校验用）
function parseMultipart(buf, boundary) {
  const delim = Buffer.from('--' + boundary);
  const parts = [];
  let pos = buf.indexOf(delim);
  while (pos >= 0) {
    const start = pos + delim.length;
    if (buf.subarray(start, start + 2).toString('latin1') === '--') break; // 结束标记
    const next = buf.indexOf(delim, start);
    if (next < 0) break;
    let bodyStart = start;
    // 跳过分隔符后的 \r\n
    if (buf[bodyStart] === 0x0d && buf[bodyStart + 1] === 0x0a) bodyStart += 2;
    const chunk = buf.subarray(bodyStart, next - 2 >= bodyStart ? next - 2 : next); // 去掉尾部 \r\n
    const sep = chunk.indexOf('\r\n\r\n');
    if (sep >= 0) {
      const headers = chunk.subarray(0, sep).toString('latin1');
      const data = chunk.subarray(sep + 4);
      const cdLine = headers.split('\r\n').find((h) => /^content-disposition:/i.test(h)) || '';
      const nm = /name="([^"]*)"/i.exec(cdLine);
      const fn = /filename="([^"]*)"/i.exec(cdLine);
      const ct = /content-type:\s*([^\r\n]+)/i.exec(headers);
      if (nm) parts.push({ name: nm[1], filename: fn ? fn[1] : undefined, contentType: ct ? ct[1].trim() : '', data });
    }
    pos = next;
  }
  return parts;
}

// sysmsg() HTML 页（§4.5 形态 4）
function sysmsgHtml(text) {
  const esc = String(text).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  return `<!DOCTYPE html><html><head><meta charset="utf-8"><title>站点提示信息</title>` +
    `<style>body{font-family:sans-serif;background:#f5f5f5}#error-page{max-width:640px;margin:80px auto;padding:24px;background:#fff;border-radius:6px}</style>` +
    `</head><body id="error-page"><h3>站点提示信息</h3><p>${esc}</p><p><a href="javascript:history.back()">返回上一页</a></p></body></html>`;
}

const PAIR_PAGE_HTML = (code) => `<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>MNBT 主机管理 - 扫码指引</title>
<style>
 body{margin:0;font-family:system-ui,-apple-system,"Segoe UI",sans-serif;background:#f6f7f9;color:#1f2329}
 .card{max-width:420px;margin:12vh auto 0;padding:32px 28px;background:#fff;border-radius:12px;box-shadow:0 2px 12px rgba(0,0,0,.08);text-align:center}
 h1{font-size:20px;margin:0 0 8px}.sub{color:#6b7280;font-size:14px;line-height:1.7;margin:12px 0}
 .code{display:inline-block;margin:18px 0;padding:10px 18px;font:700 26px/1 ui-monospace,Consolas,monospace;letter-spacing:6px;background:#f2f6ff;border:1px dashed #7aa5ff;border-radius:8px}
 .tip{font-size:12px;color:#9aa0a6}
</style></head>
<body><div class="card">
 <h1>MNBT 主机管理 APP</h1>
 <p class="sub">请使用「MNBT 主机管理」APP 扫描本页二维码完成配对。<br>请勿在浏览器中直接访问本页面。</p>
 <div class="code">${code || ''}</div>
 <p class="tip">配对码 120 秒内有效，仅可使用一次。<br>若相机不可用，可在 APP「添加主机」中手动输入站点地址与配对码。</p>
</div></body></html>`;

// ---------------------------------------------------------------- 响应包络（§4.5 四形态）

function renderEnvelope(fmt, { success, code = 0, msg = 'ok', payload = {} }) {
  if (fmt === 4) {
    return { contentType: 'text/html; charset=utf-8', body: sysmsgHtml(success ? msg : `错误 ${code}：${msg}`) };
  }
  if (fmt === 2) {
    // 老 handler：qk=1 成功 / 4 失败，code 是文本，载荷在 msg
    return { contentType: 'application/json; charset=utf-8', body: JSON.stringify({ qk: success ? 1 : 4, code: msg, msg: payload }) };
  }
  if (fmt === 3) {
    // 无成功标志的裸字段形态，只能靠字段名猜
    const o = { ...payload, code: msg };
    if (!success) o.mock_error_code = code; // 形态 3 历史上无法表达数字错误码（PRD 歧义点）
    return { contentType: 'application/json; charset=utf-8', body: JSON.stringify(o) };
  }
  // 形态 1：Response::build 标准包络，$extra 同时平铺到顶层
  const o = { success, code: success ? 0 : code, msg };
  for (const [k, v] of Object.entries(payload)) {
    if (!(k in o) && k !== 'data') o[k] = v;
  }
  o.data = payload;
  return { contentType: 'application/json; charset=utf-8', body: JSON.stringify(o) };
}

// ---------------------------------------------------------------- 沙箱虚拟站点目录

const SANDBOX_ROOT = path.join(__dirname, '.sandbox', 'wwwroot', HOST.domain);

// GBK 种子内容（非 UTF-8 字节，用于验证 file.save/file.read 字节原样往返）
const GBK_SEED = Buffer.from(
  '2f2f20c3cec4ced4c6d6f7bbfac5e4d6c3cec4bcfea3a847424b20b1e0c2ebcabec0fda3a90a5c24636667203d2061727261792822d5beb5e3c3fbb3c622203d3e2022c3cec4ced4c6d6f7bbfa22293b0a',
  'hex'
);

function ensureSandbox() {
  const mk = (rel, buf) => {
    const p = path.join(SANDBOX_ROOT, rel);
    if (!fs.existsSync(p)) {
      fs.mkdirSync(path.dirname(p), { recursive: true });
      fs.writeFileSync(p, buf);
    }
  };
  mk('index.html', Buffer.from('<!DOCTYPE html><html><body><h1>MNBT mock site</h1><p>demo01.example.com</p></body></html>\n', 'utf8'));
  mk('config.php', GBK_SEED);
  mk('path/style.css', Buffer.from('body{margin:0}\n', 'utf8'));
  mk('upload/readme.txt', Buffer.from('upload dir placeholder\n', 'utf8'));
}

function sandboxResolve(relPath) {
  if (!relPath || !relPath.startsWith('/')) return null;
  const abs = path.normalize(path.join(SANDBOX_ROOT, relPath));
  if (abs !== SANDBOX_ROOT && !abs.startsWith(SANDBOX_ROOT + path.sep)) return null; // 拒绝 .. 穿越
  return abs;
}

function walkDir(dir, rel) {
  const out = [];
  const entries = fs.readdirSync(dir, { withFileTypes: true }).sort((a, b) => (a.isDirectory() === b.isDirectory() ? a.name.localeCompare(b.name) : a.isDirectory() ? -1 : 1));
  for (const e of entries) {
    const abs = path.join(dir, e.name);
    const r = rel + '/' + e.name;
    const st = fs.statSync(abs);
    out.push({
      name: e.name,
      path: r,
      type: e.isDirectory() ? 'dir' : 'file',
      size: e.isDirectory() ? 0 : st.size,
      mtime: Math.floor(st.mtimeMs / 1000),
      perms: (e.isDirectory() ? '0755' : '0644'),
      owner: HOST.user,
      group: HOST.user,
    });
    if (e.isDirectory()) out.push(...walkDir(abs, r));
  }
  return out;
}

function quotaSnapshot() {
  return {
    web_quota: { ...QUOTA_BASE.web, used: jitter(QUOTA_BASE.web.used) },
    sql_quota: { ...QUOTA_BASE.sql, used: jitter(QUOTA_BASE.sql.used, 2) },
    traffic_quota: { ...QUOTA_BASE.traffic, used: jitter(QUOTA_BASE.traffic.used, 30) },
  };
}

// ---------------------------------------------------------------- 业务 action 数据

function dataFor(action, p /* Getters */, ctx) {
  const meta = { action, gn: ACTIONS[action]?.gn, api: ACTIONS[action]?.api, bridge_version: BRIDGE_VERSION, ts: Math.floor(Date.now() / 1000) };
  const str = (n, d) => p.formStr(n, d);
  const raw = (n) => p.formRaw(n);

  switch (action) {
    // ---- 概览
    case 'host.overview': {
      const q = quotaSnapshot();
      return { ...meta, ...q, cpu: (Math.random() * 9 + 1).toFixed(1) + '%', mem: { used: jitter(412, 20), total: 2048, unit: 'MB' }, inode_used: jitter(18200, 50), inode_total: 100000 };
    }
    case 'host.profile':
      return { ...meta, user: HOST.user, domain: HOST.domain, site_name: HOST.site_name, status: ctx.status(), expire: HOST.expire, expire_ts: Math.floor(new Date(HOST.expire + 'T23:59:59').getTime() / 1000), node: HOST.ssbt, php: HOST.php, ymbds: HOST.ymbds, created_at: '2024-11-21 10:00:00' };

    // ---- 站点设置
    case 'site.init': {
      const section = str('section', 'php');
      const init = {
        php: { current: HOST.php, options: ['5.6', '7.0', '7.1', '7.2', '7.3', '7.4', '8.0', '8.1', '8.2', '8.3'] },
        yxml: { current: '/', options: ['/', '/public', '/admin', '/web'] },
        mrwd: { current: 'index.php,index.html,index.htm' },
        wjt: { current: 'thinkphp', list: ['laravel', 'thinkphp', 'wordpress', 'tp2', 'tp5'] },
        gzip: { current: 'on' },
        pass: { list: [{ dir: '/private', user: 'guard' }] },
        xgpass: { user: HOST.user },
        url: { list: [{ proxy_url: '/api', target_url: 'http://127.0.0.1:8080' }] },
      }[section];
      return init ? { ...meta, section, ...init } : { ...meta, error: 1007, msg: `未知 section: ${section}` };
    }
    case 'site.php.set': return { ...meta, phpversion: str('php_version', '7.4'), msg_extra: 'PHP 版本已切换' };
    case 'site.rundir.set': return { ...meta, rundir: str('rundir', '/') };
    case 'site.indexdoc.set': return { ...meta, indexdoc: str('index', 'index.php,index.html') };
    case 'site.rewrite.get': return { ...meta, current: 'thinkphp', rewrite: 'location / {\n if (!-e $request_filename) {\n rewrite ^(.*)$ /index.php?s=$1 last;\n }\n}', list: ['laravel', 'thinkphp', 'wordpress', 'tp2', 'tp5'] };
    case 'site.rewrite.set': return { ...meta, rewrite: str('rewrite', 'thinkphp') };
    case 'site.gzip.set': return { ...meta, gzip: str('gzip', 'on') };
    case 'site.passdir.list': return { ...meta, list: [{ dir: '/private', user: 'guard', pass: '****' }] };
    case 'site.passdir.add': return { ...meta, dir: str('user_dir'), user: str('user'), added: true };
    case 'site.passdir.del': return { ...meta, dir: str('user_dir'), deleted: true };
    case 'site.password.set': return { ...meta, protected: true, user: str('user', HOST.user) };
    case 'site.indexconf.set': return { ...meta, index_conf: str('index_conf', 'on') };
    case 'site.hotlink.get': return { ...meta, open: 1, domains: str('domains', '*.example.com'), exts: str('exts', 'jpg|png|gif'), lasts: 30 };
    case 'site.hotlink.set': return { ...meta, open: Number(str('open', '1')), domains: str('domains', ''), exts: str('exts', 'jpg|png|gif'), lasts: Number(str('lasts', '30')) };
    case 'site.proxy.set': return { ...meta, list: [{ proxyUrl: str('proxy_url', '/api'), targetUrl: str('target_url', 'http://127.0.0.1:8080') }] };
    case 'site.cache.list': return { ...meta, list: [{ id: 1, url: '/index.php', type: 'page', seconds: 3600 }] };
    case 'site.cache.add': return { ...meta, id: 2, url: str('url'), type: str('type', 'page') };
    case 'site.cache.edit': return { ...meta, id: Number(str('id', '1')), url: str('url') };
    case 'site.cache.del': return { ...meta, id: Number(str('id', '1')), deleted: true };

    // ---- 文件管理
    case 'file.list': {
      const dir = str('path', '/');
      const abs = sandboxResolve(dir);
      if (!abs || !fs.existsSync(abs) || !fs.statSync(abs).isDirectory()) return { ...meta, error: 1007, path: dir };
      return { ...meta, cwd: dir, list: walkDir(abs, dir === '/' ? '' : dir) };
    }
    case 'file.read': {
      const f = str('file', str('path'));
      const abs = sandboxResolve(f);
      if (!abs || !fs.existsSync(abs)) return { ...meta, error: 1007, file: f };
      const buf = fs.readFileSync(abs);
      const st = fs.statSync(abs);
      return { ...meta, file: f, path: f, size: buf.length, mtime: Math.floor(st.mtimeMs / 1000), encoding: 'auto', content_base64: buf.toString('base64'), content: utf8OrLossy(buf) };
    }
    case 'file.save': {
      const f = str('file', str('path'));
      const abs = sandboxResolve(f);
      const body = raw('content');
      if (!abs) return { ...meta, error: 1007, file: f };
      if (body === undefined) return { ...meta, error: 1007, msg: '缺少 content 参数' };
      if (path.basename(abs) === '.user.ini') return { ...meta, error: 1009, msg: '.user.ini 禁止修改（核心保护）' };
      fs.mkdirSync(path.dirname(abs), { recursive: true });
      fs.writeFileSync(abs, body); // 字节原样落盘
      const st = fs.statSync(abs);
      return { ...meta, file: f, path: f, size: st.size, mtime: Math.floor(st.mtimeMs / 1000), saved: true };
    }
    case 'file.create': {
      const f = str('path'); const abs = sandboxResolve(f);
      if (!abs) return { ...meta, error: 1007 };
      if (str('type', 'file') === 'dir') fs.mkdirSync(abs, { recursive: true });
      else { fs.mkdirSync(path.dirname(abs), { recursive: true }); if (!fs.existsSync(abs)) fs.writeFileSync(abs, ''); }
      return { ...meta, path: f, type: str('type', 'file'), created: true };
    }
    case 'file.delete': {
      const f = str('path'); const abs = sandboxResolve(f);
      if (!abs || !fs.existsSync(abs)) return { ...meta, error: 1007 };
      if (f === '/' ) return { ...meta, error: 1009, msg: '运行目录禁删' };
      fs.rmSync(abs, { recursive: true, force: true });
      return { ...meta, path: f, deleted: true };
    }
    case 'file.delete_batch': {
      const paths = str('paths', []); const arr = Array.isArray(paths) ? paths : [paths];
      const deleted = [];
      for (const f of arr) { const abs = sandboxResolve(f); if (abs && fs.existsSync(abs)) { fs.rmSync(abs, { recursive: true, force: true }); deleted.push(f); } }
      return { ...meta, requested: arr.length, deleted, count: deleted.length };
    }
    case 'file.rename': return { ...meta, src: str('src', str('old_name')), dest: str('dest', str('new_name')), renamed: true };
    case 'file.copy': return { ...meta, src: str('src'), dest: str('dest'), copied: true };
    case 'file.compress': return { ...meta, files: Array.isArray(str('files', [])) ? str('files', []).length : 1, dest: str('dest', '/archive.zip') };
    case 'file.unzip': return { ...meta, src: str('src', '/archive.zip'), dir: str('dest', '/'), extracted: true };
    case 'file.size': return { ...meta, files: str('files', []), size: jitter(36, 6), unit: 'KB' };
    case 'file.upload_prepare': return { ...meta, upload_id: crypto.randomBytes(8).toString('hex'), max_size: 50 * 1024 * 1024, chunk_size: 2 * 1024 * 1024, path: str('path', '/') };
    case 'file.upload': {
      if (!ctx.multipart) return { ...meta, error: 1007, msg: 'file.upload 需要 multipart/form-data' };
      const filePart = ctx.multipart.find((x) => x.filename !== undefined);
      if (!filePart) return { ...meta, error: 1007, msg: '缺少文件字段' };
      if (!ctx.multipart.some((x) => x.name === 'upload_id' || x.name === 'path')) return { ...meta, error: 1007, msg: '缺少 upload_id/path 字段' };
      if (filePart.contentType && filePart.contentType !== 'application/octet-stream') return { ...meta, error: 1009, msg: `文件字段 MIME 应为 application/octet-stream，收到 ${filePart.contentType}` };
      const rel = str('path', '/');
      const baseDir = rel.startsWith('/') ? rel : '/' + rel;
      const target = baseDir.endsWith('/') ? baseDir + filePart.filename : baseDir + '/' + filePart.filename;
      const abs = sandboxResolve(target);
      if (!abs) return { ...meta, error: 1007, msg: '非法路径' };
      fs.mkdirSync(path.dirname(abs), { recursive: true });
      fs.writeFileSync(abs, filePart.data);
      return { ...meta, path: '/' + path.relative(SANDBOX_ROOT, abs).replace(/\\/g, '/'), name: filePart.filename, size: filePart.data.length, uploaded: true };
    }
    case 'file.download': {
      const f = str('file', str('path'));
      const abs = sandboxResolve(f);
      if (!abs || !fs.existsSync(abs)) return { ...meta, error: 1007 };
      return { ...meta, file: f, filename: path.basename(f), size: fs.statSync(abs).size, url: `${ctx.baseUrl()}/index.php?_r=/app/v1/file_download_raw&token=${ctx.token}&path=${encodeURIComponent(f)}` };
    }
    case 'file.access.get': return { ...meta, path: str('path', '/'), perm: '0644', owner: HOST.user, group: HOST.user };
    case 'file.access.set': return { ...meta, path: str('path', '/'), perm: str('perm', '0644'), recurse: str('recurse', '0') === '1', applied: true };

    // ---- 数据库
    case 'db.list': return { ...meta, list: [{ name: 'demo01_db', username: 'demo01_db', charset: 'utf8mb4', state: 'RUNNING', addtime: '2024-11-21 12:00:00', backups: [{ name: 'demo01_db_20260928.sql.gz', size: 12048, time: '2026-09-28 03:00:00' }] }], quota: { used: jitter(12, 2), max: 512, unit: 'MB' } };
    case 'db.create': { const name = str('data_account', str('name', 'demo01_new')); return { ...meta, name, username: name, password: crypto.randomBytes(5).toString('hex') }; }
    case 'db.delete': return { ...meta, name: str('data_account', str('name')), deleted: true };
    case 'db.delete_all': return { ...meta, deleted: 1, remaining: 0 };
    case 'db.backup.download': return { ...meta, file: str('file', 'demo01_db_20260928.sql.gz'), url: `${ctx.baseUrl()}/index.php?_r=/app/v1/file_download_raw&token=${ctx.token}&path=/db/${encodeURIComponent(str('file', 'demo01_db_20260928.sql.gz'))}`, size: 12048 };
    case 'db.backup.restore': return { ...meta, name: str('name'), db: str('data_account', str('name')), restored: true };
    case 'db.access.set': return { ...meta, db: str('name'), access: str('host', 'localhost'), port: Number(str('port', '3306')), applied: true };
    case 'db.sql.import': return { ...meta, file: str('file', '/sql/dump.sql'), db: str('name', 'demo01_db'), imported: true };

    // ---- 域名与 SSL
    case 'domain.list': return { ...meta, list: [{ domain: HOST.domain, port: 80, ssl: false, type: 'top', status: 'ok' }, { domain: 'www.' + HOST.domain, port: 80, ssl: false, type: 'top', status: 'ok' }] };
    case 'domain.subdir.list': return { ...meta, list: [{ domain: 'blog.' + HOST.domain, dir: '/blog' }] };
    case 'domain.add': {
      const d = str('domain', str('webname'));
      if (!d) return { ...meta, error: 1007, msg: '缺少 domain 参数' };
      if (ctx.domains.size >= HOST.ymbds) return { ...meta, error: 1007, msg: `超出最大绑定域名数 ${HOST.ymbds}` };
      ctx.domains.add(d);
      return { ...meta, domain: d, port: Number(str('port', '80')), added: true, bound: ctx.domains.size, limit: HOST.ymbds };
    }
    case 'domain.del': ctx.domains.delete(str('domain', str('webname'))); return { ...meta, domain: str('domain', str('webname')), port: Number(str('port', '80')), deleted: true };
    case 'ssl.get': return { ...meta, ssl: { expire: '2027-01-15 00:00:00', issuer: "Let's Encrypt", domains: [HOST.domain, 'www.' + HOST.domain], status: 'valid' }, force_https: ctx.forceHttps, pem: '', key: '' };
    case 'ssl.apply': case 'ssl.renew': return { ...meta, order_id: 'mock-order-' + crypto.randomBytes(4).toString('hex'), domains: Array.isArray(str('domains', [])) ? str('domains', []) : [HOST.domain], result: 'success', expire: '2027-01-15' };
    case 'ssl.custom': return { ...meta, saved: true, has_key: !!str('key'), has_pem: !!str('pem') };
    case 'ssl.close': return { ...meta, closed: true };
    case 'ssl.force_https': ctx.forceHttps = str('qk', str('on', '1')) !== '0'; return { ...meta, force_https: ctx.forceHttps };

    // ---- 监控与通知
    case 'monitor.list': return { ...meta, list: [{ id: 1, name: '首页可用性', type: 'url', target: 'https://' + HOST.domain + '/', cycle: 60, status: 'on', last_code: 200, last_time: nowStr() }, { id: 2, name: '资源监控', type: 'resource', cycle: 180, status: 'on' }], limit: { max_tasks: 5, url_min_cycle: 60, resource_cycle: 180 } };
    case 'monitor.save': return { ...meta, id: Number(str('id', '2')), name: str('name', '首页可用性'), type: str('type', 'url'), saved: true };
    case 'monitor.delete': return { ...meta, id: Number(str('id', '1')), deleted: true };
    case 'monitor.toggle': return { ...meta, id: Number(str('id', '1')), status: str('status', 'off') };
    case 'monitor.log': return { ...meta, list: [{ time: nowStr(), target: 'https://' + HOST.domain + '/', code: 200, used_ms: jitter(123, 20), status: 'ok' }, { time: nowStr(), target: 'https://' + HOST.domain + '/', code: 502, used_ms: jitter(3000, 200), status: 'fail' }], total: 2 };
    case 'notice.list': return { ...meta, list: [{ id: 1, title: '空间超额警告', content: '已使用空间超过配额 80%', time: '2026-10-01 08:00:00', read: 0 }, { id: 2, title: 'PHP 版本维护通知', content: '10 月 3 日 02:00-04:00 节点维护', time: '2026-09-30 18:00:00', read: 1 }], unread: 1, total: 2 };
    case 'notice.read': return { ...meta, id: Number(str('id', '1')), read: 1 };

    // ---- 一键部署
    case 'deploy.list': return { ...meta, list: [{ id: 1, name: 'WordPress', ver: '6.6', src: ctx.baseUrl() + '/upload/deploy/wp.png', dir: '/' }, { id: 2, name: 'Typecho', ver: '1.8', src: ctx.baseUrl() + '/upload/deploy/typecho.png', dir: '/blog' }] };
    case 'deploy.form': return { ...meta, id: Number(str('bs_id', str('id', '1'))), name: 'WordPress', need_db: true, create_dir: '/', fields: [{ key: 'webname', label: '站点名称', type: 'input', default: HOST.domain }, { key: 'admin_user', label: '管理员账号', type: 'input', default: 'admin' }, { key: 'db', label: '数据库', type: 'select', options: ['demo01_db', '自动创建'] }] };
    case 'deploy.run': return { ...meta, task_id: 'mock-task-' + crypto.randomBytes(4).toString('hex'), status: 'queued' };

    // ---- 设备管理
    case 'device.ping': return { ...meta, pong: true, server_time: nowStr(), ...quotaSnapshot() };
    case 'device.info': return { ...meta, mock: true, bridge_version: BRIDGE_VERSION, mnbt_version: MNBT_VERSION, supported: Object.keys(ACTIONS).sort(), actions_min_version: Object.fromEntries(Object.keys(ACTIONS).map((a) => [a, '1.0.0'])) };
    case 'device.revoke': if (ctx.device) ctx.device.status = 0; return { ...meta, revoked: true, device_id: ctx.device?.device_id ?? '' };

    default: return { ...meta, note: 'no mock data for this action' };
  }
}

function utf8OrLossy(buf) {
  // content 字段仅供展示：非 UTF-8 时用替换字符，APP 应以 content_base64 为准
  const s = buf.toString('utf8');
  return s;
}

// ---------------------------------------------------------------- 服务器

export function createMockServer(options = {}) {
  ensureSandbox();
  const st = {
    hostStatus: options.state && STATE_ERRORS[options.state] !== undefined ? options.state : 'running',
    pairCodes: new Map(), // code -> {code, zj_id, created_at, expires_at, used}
    devices: new Map(), // tokenHash -> {token_hash, zj_id, device_id, device_name, app_version, created_at, last_seen_at, last_ip, status}
    pairFail: new Map(), // ip -> {count, window_start}
    domains: new Set([HOST.domain, 'www.' + HOST.domain]),
    forceHttps: false,
    // ---- ZJMF-CBAP（云站点）运行时状态 ----
    zjmfPower: zjmfInitialPower(), // 服务器 id -> {status, desc}（电源状态内存源）
    zjmfReplay: new Map(), // ip+url+body -> ts，3 秒防重放窗口
  };

  function newPairCode(ttl = CODE_TTL_MS) {
    const code = genPairCode();
    st.pairCodes.set(code, { code, zj_id: HOST.zj_id, created_at: Date.now(), expires_at: Date.now() + ttl, used: 0 });
    return st.pairCodes.get(code);
  }

  function status() {
    // MN_zj.qk / datae 的模拟视图
    return st.hostStatus === 'running' ? 'running' : st.hostStatus;
  }

  let server = null;
  let baseUrlCache = null;
  const baseUrl = () => baseUrlCache;

  function send(res, statusNum, rendered) {
    res.writeHead(statusNum, {
      'Content-Type': rendered.contentType,
      'Content-Length': Buffer.byteLength(rendered.body),
      'Access-Control-Allow-Origin': '*',
    });
    res.end(rendered.body);
  }

  function readBody(req) {
    return new Promise((resolve, reject) => {
      const chunks = [];
      let size = 0;
      req.on('data', (c) => {
        size += c.length;
        if (size > 64 * 1024 * 1024) { reject(new Error('body too large')); req.destroy(); return; }
        chunks.push(c);
      });
      req.on('end', () => resolve(Buffer.concat(chunks)));
      req.on('error', reject);
    });
  }

  function fmtOf(url, req) {
    const v = Number(url.searchParams.get('_fmt') ?? req.headers['x-mock-format'] ?? 1);
    return [1, 2, 3, 4].includes(v) ? v : 1; // 默认标准包络
  }

  function clientIp(req) {
    return req.socket.remoteAddress || 'unknown';
  }

  async function handle(req, res) {
    const url = new URL(req.url, 'http://' + (req.headers.host || '127.0.0.1'));
    // 路由：_r 优先，同时容忍直接路径（§4.1）
    let route = (url.searchParams.get('_r') || url.pathname || '').trim();
    if (route.endsWith('/index.php')) route = '/';
    route = route.replace(/^\/index\.php/, '') || route;
    route = route.replace(/^\//, '').replace(/^app\/v1\//, '');
    const action = route;

    // ---- 开发管理路由 /__mock__/...
    if (url.pathname.startsWith('/__mock__/') || action.startsWith('__mock__/')) {
      const sub = action.replace('__mock__/', '');
      return handleAdmin(sub, req, res, url);
    }

    const fmt = fmtOf(url, req);
    const bodyBuf = req.method === 'POST' || req.method === 'PUT' || req.body ? await readBody(req) : Buffer.alloc(0);
    const ct = (req.headers['content-type'] || '').toLowerCase();

    let fields = new Map();
    let multipartParts = null;
    if (ct.includes('multipart/form-data')) {
      const m = /boundary=(?:"([^"]+)"|([^;]+))/i.exec(req.headers['content-type'] || '');
      const boundary = m ? (m[1] || m[2].trim()) : null;
      if (!boundary) return send(res, 200, renderEnvelope(1, { success: false, code: 1007, msg: 'multipart 缺少 boundary' }));
      multipartParts = parseMultipart(bodyBuf, boundary);
      for (const part of multipartParts) if (part.filename === undefined) fields.set(part.name, { str: part.data.toString('utf8'), raw: part.data });
    } else {
      fields = parseForm(bodyBuf);
    }
    // GET /?_r=/app/v1/xxx&k=v：无 body 时从 query string 取参，不覆盖 body 参数
    for (const [k, v] of url.searchParams) {
      if (k === '_r' || k === '_fmt') continue;
      if (!fields.has(k)) fields.set(k, { str: v, raw: Buffer.from(v, 'utf8') });
    }
    const p = {
      formStr: (n, d) => { const v = formStr(fields, n); return v === undefined ? d : v; },
      formRaw: (n) => formRaw(fields, n),
    };

    // ---- pair_page：静态 HTML 提示页（§4.2）
    if (action === 'pair_page') {
      return send(res, 200, { contentType: 'text/html; charset=utf-8', body: PAIR_PAGE_HTML(String(p.formStr('c', url.searchParams.get('c') || '')).slice(0, 8)) });
    }

    // ---- pair（§4.3/§4.4/§4.5）
    if (action === 'pair') return handlePair(req, res, p);

    // ---- device.info 免鉴权（能力协商，§5.9）
    // 其余业务 action：Bearer 鉴权
    const authz = req.headers['authorization'] || '';
    const token = authz.startsWith('Bearer ') ? authz.slice(7).trim() : '';
    const tokenHash = token ? sha256hex(token) : '';
    const device = st.devices.get(tokenHash);
    if (!NO_AUTH.has(action) && !(device && device.status === 1)) {
      return send(res, 200, renderEnvelope(fmt, { success: false, code: 1001, msg: '令牌无效或已吊销，需重新配对' }));
    }
    if (device && Date.now() - device._last_seen_ms > 60_000) { // §6.3 步骤 6：60s 节流回写
      device.last_seen_at = nowStr();
      device.last_ip = clientIp(req);
      device._last_seen_ms = Date.now();
    }
    // 未知 action
    if (!ACTIONS[action]) {
      return send(res, 200, renderEnvelope(fmt, { success: false, code: 1010, msg: `未知 action: ${action}` }));
    }
    // 主机状态开关（§4.6 / §6.3：到期/封禁必须是 JSON）
    const gate = STATE_ERRORS[st.hostStatus];
    if (gate && action !== 'device.info' && action !== 'device.ping') {
      return send(res, 200, renderEnvelope(fmt, { success: false, code: gate.code, msg: st.hostStatus === 'low_plugin' ? `${gate.msg}（${action}）` : gate.msg }));
    }

    const ctx = { baseUrl, token, device, multipart: multipartParts, domains: st.domains, forceHttps: st.forceHttps, status, statusName: st.hostStatus };
    const getterProxy = {
      formStr: p.formStr,
      formRaw: p.formRaw,
    };
    let payload;
    try {
      payload = dataFor(action, getterProxy, ctx);
    } catch (e) {
      return send(res, 200, renderEnvelope(fmt, { success: false, code: 5000, msg: '服务端异常（mock）：' + e.message, payload: { trace_id: crypto.randomBytes(6).toString('hex') } }));
    }
    st.forceHttps = ctx.forceHttps;

    if (payload.error) {
      const msg = payload.msg || `handler 返回失败（mock, gn=${ACTIONS[action].gn}）`;
      delete payload.msg;
      return send(res, 200, renderEnvelope(fmt, { success: false, code: payload.error, msg, payload: {} }));
    }
    return send(res, 200, renderEnvelope(fmt, { success: true, code: 0, msg: fmt === 3 ? '修改成功' : fmt === 2 ? '操作成功' : 'ok', payload }));
  }

  function handlePair(req, res, p) {
    const fmt = 1; // 配对响应固定标准包络
    const ip = clientIp(req);
    const fail = st.pairFail.get(ip);
    if (fail && Date.now() - fail.window_start > PAIR_FAIL_WINDOW_MS) st.pairFail.delete(ip);
    if (fail && fail.count >= PAIR_FAIL_MAX) {
      return send(res, 200, renderEnvelope(fmt, { success: false, code: 1008, msg: '配对失败次数过多，请 10 分钟后再试' }));
    }
    const markFail = () => { const f = st.pairFail.get(ip) || { count: 0, window_start: Date.now() }; f.count++; st.pairFail.set(ip, f); };

    const code = String(p.formStr('code', '') || '').toUpperCase().trim();
    const rec = st.pairCodes.get(code);
    if (!rec) { markFail(); return send(res, 200, renderEnvelope(fmt, { success: false, code: 1002, msg: '配对码错误或已过期' })); }
    if (rec.used) { markFail(); return send(res, 200, renderEnvelope(fmt, { success: false, code: 1002, msg: '配对码已被使用，请重新生成' })); }
    if (Date.now() > rec.expires_at) { st.pairCodes.delete(code); markFail(); return send(res, 200, renderEnvelope(fmt, { success: false, code: 1002, msg: '配对码已过期（120 秒有效）' })); }

    rec.used = 1; // 单次使用（§4.3）
    st.pairFail.delete(ip);
    // §4.4：bin2hex(random_bytes(32))，内存只存 sha256 hex
    const token = crypto.randomBytes(32).toString('hex');
    const tokenHash = sha256hex(token);
    st.devices.set(tokenHash, {
      token_hash: tokenHash,
      zj_id: rec.zj_id,
      device_id: String(p.formStr('device_id', '') || ''),
      device_name: String(p.formStr('device_name', p.formStr('device_model', 'unknown')) || ''),
      app_version: String(p.formStr('app_version', '') || ''),
      created_at: nowStr(),
      last_seen_at: nowStr(),
      last_ip: clientIp(req),
      status: 1,
      _last_seen_ms: Date.now(),
    });
    const q = quotaSnapshot();
    const payload = {
      token, // 明文仅此一次下发
      site: { name: HOST.site_name, base: baseUrl(), mnbt_version: MNBT_VERSION },
      host: { user: HOST.user, domain: HOST.domain, status: status(), expire: HOST.expire, web_quota: q.web_quota, sql_quota: q.sql_quota, traffic_quota: q.traffic_quota },
    };
    return send(res, 200, renderEnvelope(fmt, { success: true, code: 0, msg: 'ok', payload }));
  }

  function handleAdmin(sub, req, res, url) {
    const json = (obj) => send(res, 200, { contentType: 'application/json; charset=utf-8', body: JSON.stringify(obj) });
    if (sub === 'gen_pair_code') {
      const ttl = Number(url.searchParams.get('ttl') || CODE_TTL_MS);
      const rec = newPairCode(ttl);
      return json({ ok: true, code: rec.code, expires_in_ms: ttl - 0, qr_url: `${baseUrl()}/index.php?_r=/app/v1/pair_page&c=${rec.code}`, base: baseUrl() });
    }
    if (sub === 'state') {
      const target = url.searchParams.get('set') || url.searchParams.get('value');
      if (target !== null) {
        if (!(target in STATE_ERRORS)) return json({ ok: false, error: `未知状态 ${target}，可选: ${Object.keys(STATE_ERRORS).join('|')}` });
        st.hostStatus = target;
      }
      return json({ ok: true, state: st.hostStatus, error_code: STATE_ERRORS[st.hostStatus]?.code ?? 0, states: Object.keys(STATE_ERRORS) });
    }
    if (sub === 'reset') {
      st.pairCodes.clear(); st.devices.clear(); st.pairFail.clear(); st.domains = new Set([HOST.domain, 'www.' + HOST.domain]); st.hostStatus = 'running';
      st.zjmfPower = zjmfInitialPower(); st.zjmfReplay.clear();
      return json({ ok: true });
    }
    if (sub === 'codes') return json({ ok: true, codes: [...st.pairCodes.values()] });
    if (sub === 'devices') return json({ ok: true, devices: [...st.devices.values()].map(({ token_hash, ...d }) => ({ token_hash: token_hash.slice(0, 8) + '…', ...d })) });
    if (sub === 'help') return json({ ok: true, routes: ['/index.php?_r=/app/v1/{action}', '/app/v1/{action}', '/console/v1/…（ZJMF 云站点，见 docs/cloud-integration.md）', '/__mock__/gen_pair_code', '/__mock__/state?set=…', '/__mock__/reset', '/__mock__/codes', '/__mock__/devices'], states: Object.keys(STATE_ERRORS), fmt_override: '?_fmt=1|2|3|4 或 X-Mock-Format 头' });
    return json({ ok: false, error: 'unknown admin route' });
  }

  // ---------------------------------------------------------------- ZJMF-CBAP（云站点）路由
  // 独立分节：{站点根}/console/v1/...，与 MNBT 的 index.php?_r= 路由互不干扰。
  // 包络恒为 {status, msg, data}；除 bind 外全部要求 Bearer（mock-jwt- 前缀）。

  const zjmfJson = (res, httpCode, payload) =>
    send(res, httpCode, { contentType: 'application/json; charset=utf-8', body: JSON.stringify(payload) });
  const zjmfOk = (res, data, msg = 'ok') => zjmfJson(res, 200, { status: 200, msg, data });
  const zjmfErr = (res, status, msg, data = {}) => zjmfJson(res, 200, { status, msg, data });

  function zjmfServerDto(s) {
    const power = st.zjmfPower.get(s.id) || { status: 'unknown', desc: '未知' };
    return {
      id: s.id,
      name: s.name,
      product_name: s.product_name,
      status: s.status,
      module: s.module,
      power_status: power.status,
      power_desc: power.desc,
      ...(s.dedicate_ip ? { dedicate_ip: s.dedicate_ip } : {}),
      due_time: s.due_time,
      billing_cycle_name: s.billing_cycle_name,
      active_time: s.active_time,
    };
  }

  function zjmfSetPower(id, status, desc) {
    st.zjmfPower.set(id, { status, desc });
  }

  async function handleZjmf(req, res, url) {
    const bodyBuf = req.method === 'POST' ? await readBody(req) : Buffer.alloc(0);
    const fields = parseForm(bodyBuf);
    const str = (n, d = undefined) => (fields.has(n) ? fields.get(n).str : d);
    const path = url.pathname.replace(/^\/console\/v1\//, '').replace(/\/+$/, '');
    const seg = path.split('/').filter(Boolean);
    const authz = req.headers['authorization'] || '';
    const token = authz.startsWith('Bearer ') ? authz.slice(7).trim() : '';
    const isBind = seg[0] === 'mnbt' && seg[1] === 'bind';

    // 鉴权：除 bind 外全部要求 Bearer 且令牌必须 mock-jwt- 开头（HTTP 401 + 包络 401 双形态）
    if (!isBind && !token.startsWith('mock-jwt-')) {
      return zjmfJson(res, 401, { status: 401, msg: '未登录或登录已失效', data: {} });
    }

    // 防重放：所有 POST 必须带 _r；3 秒内同 IP+URL+body 直接拒 400（无 error_code）
    if (req.method === 'POST') {
      if (!str('_r')) return zjmfErr(res, 400, '缺少 _r 参数（防重放校验失败）');
      const key = clientIp(req) + ' ' + req.url + ' ' + bodyBuf.toString('latin1');
      const now = Date.now();
      if (st.zjmfReplay.has(key) && now - st.zjmfReplay.get(key) < 3000) {
        return zjmfErr(res, 400, '重复请求被拒绝（防重放）');
      }
      st.zjmfReplay.set(key, now);
      for (const [k, t] of st.zjmfReplay) if (now - t > 10_000) st.zjmfReplay.delete(k);
    }

    // ---- addon 管理端：mnbt/* ----
    if (seg[0] === 'mnbt') {
      const action = seg[1] || '';
      if (action === 'bind') {
        if (req.method !== 'POST') return zjmfErr(res, 404, '接口不存在');
        const code = String(str('code', '') || '').trim();
        if (!code) return zjmfErr(res, 400, '配对码不能为空');
        return zjmfOk(res, {
          jwt: 'mock-jwt-' + crypto.randomBytes(16).toString('hex'),
          expires_in: ZJMF_TOKEN_TTL,
          site_name: ZJMF_SITE.site_name,
        });
      }
      if (action === 'refresh') {
        if (req.method !== 'POST') return zjmfErr(res, 404, '接口不存在');
        return zjmfOk(res, { jwt: 'mock-jwt-' + crypto.randomBytes(16).toString('hex'), expires_in: ZJMF_TOKEN_TTL });
      }
      if (action === 'unbind') {
        if (req.method !== 'POST') return zjmfErr(res, 404, '接口不存在');
        return zjmfOk(res, {}, '已解绑');
      }
      if (action === 'config') {
        if (req.method !== 'GET') return zjmfErr(res, 404, '接口不存在');
        return zjmfOk(res, { ...ZJMF_SITE });
      }
      if (action === 'cloud' && seg[2] === 'servers') {
        if (req.method !== 'GET') return zjmfErr(res, 404, '接口不存在');
        return zjmfOk(res, { list: ZJMF_SERVERS.map(zjmfServerDto) });
      }
      if (action === 'cloud' && seg[2] === 'server' && seg[3]) {
        if (req.method !== 'GET') return zjmfErr(res, 404, '接口不存在');
        const server = ZJMF_SERVERS.find((s) => s.id === Number(seg[3]));
        if (!server) return zjmfErr(res, 404, '云服务器不存在');
        if (seg[4] === 'flow') {
          // addon 归一化流量（契约 6）
          return zjmfOk(res, {
            list: [
              { name: '流量', used: 34.2, total: 100, unit: 'GB' },
              { name: '带宽', used: 5, total: 10, unit: 'Mbps' },
            ],
          });
        }
        return zjmfOk(res, {
          server: {
            ...zjmfServerDto(server),
            assign_ip: server.dedicate_ip || '198.51.100.1',
            os: 'Ubuntu 22.04 LTS',
            addition: { username: 'root', password: 'mock-pass-' + server.id, port: 22 },
            client_notes: 'mock 备注：生产库所在机器，重启前先通知值班。',
            config_text: 'CPU: 2 vCPU\n内存: 4 GB\n磁盘: 80 GB SSD\n机房: mock-zone-a',
          },
        });
      }
      return zjmfErr(res, 404, `接口不存在: /console/v1/${path}`);
    }

    // ---- 模块直连路由：{module}/{id}/...（module ∈ mf_cloud | remf_cloud）----
    if ((seg[0] === 'mf_cloud' || seg[0] === 'remf_cloud') && seg[1] && seg[2]) {
      const id = Number(seg[1]);
      const server = ZJMF_SERVERS.find((s) => s.id === id);
      if (!server) return zjmfErr(res, 404, '云服务器不存在');
      const func = seg[2];

      // 真实系统里模块不匹配会 404；mock 两个模块都返回当前电源状态，方便探测路径测试
      if (func === 'status') {
        if (req.method !== 'GET') return zjmfErr(res, 404, '接口不存在');
        const power = st.zjmfPower.get(id) || { status: 'unknown', desc: '未知' };
        return zjmfOk(res, { status: power.status, desc: power.desc });
      }
      if (func === 'flow') {
        // 回退流量端点（契约 10）：形状不定，这里故意给「裸数组 + 直接字段」形态
        if (req.method !== 'GET') return zjmfErr(res, 404, '接口不存在');
        return zjmfOk(res, [
          { name: '流量', used: 34.2, total: 100, unit: 'GB' },
          { name: '带宽', used: 5, total: 10, unit: 'Mbps' },
        ]);
      }
      if (func === 'vnc') {
        if (req.method !== 'POST') return zjmfErr(res, 404, '接口不存在');
        return zjmfOk(res, { url: `https://mock.local/vnc/session-${id}` });
      }
      if (ZJMF_POWER_FUNCS[func]) {
        if (req.method !== 'POST') return zjmfErr(res, 404, '接口不存在');
        if (str('client_operate_password') !== ZJMF_OPERATE_PASSWORD) {
          return zjmfErr(res, 400, '操作密码错误');
        }
        const [finalStatus, finalDesc] = ZJMF_POWER_FUNCS[func];
        if (ZJMF_INSTANT_FUNCS.has(func)) {
          zjmfSetPower(id, finalStatus, finalDesc); // on/off 立即生效
        } else {
          zjmfSetPower(id, 'operating', '操作执行中'); // reboot/hard_*：operating 2 秒再落终态
          setTimeout(() => zjmfSetPower(id, finalStatus, finalDesc), 2000);
        }
        return zjmfOk(res, {});
      }
      return zjmfErr(res, 404, `接口不存在: /console/v1/${path}`);
    }

    return zjmfErr(res, 404, `接口不存在: /console/v1/${path}`);
  }

  // file.download 外链的真实下载路由（APP 用 OkHttp 直接 GET，带 token 查询参数）
  async function maybeHandleDownloadRaw(req, res, url) {
    const r = url.searchParams.get('_r') || url.pathname;
    if (!r.endsWith('file_download_raw')) return false;
    const token = url.searchParams.get('token') || '';
    if (!token || !st.devices.has(sha256hex(token))) { send(res, 200, renderEnvelope(1, { success: false, code: 1001, msg: '令牌无效' })); return true; }
    const gate = STATE_ERRORS[st.hostStatus];
    if (gate) { send(res, 200, renderEnvelope(1, { success: false, code: gate.code, msg: gate.msg })); return true; }
    const abs = sandboxResolve(url.searchParams.get('path') || '');
    if (!abs || !fs.existsSync(abs) || fs.statSync(abs).isDirectory()) { send(res, 200, renderEnvelope(1, { success: false, code: 1007, msg: '文件不存在' })); return true; }
    res.writeHead(200, { 'Content-Type': 'application/octet-stream', 'Content-Length': fs.statSync(abs).size });
    fs.createReadStream(abs).pipe(res);
    return true;
  }

  const requestListener = (req, res) => {
    const url = new URL(req.url, 'http://' + (req.headers.host || '127.0.0.1'));
    if (req.method === 'OPTIONS') { res.writeHead(204, { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': 'Authorization, Content-Type, X-MNBT-Device, X-Mock-Format', 'Access-Control-Allow-Methods': 'GET,POST,PUT,OPTIONS' }); res.end(); return; }
    // ZJMF-CBAP（云站点）路由：/console/v1/... 独立分节，优先于 MNBT 路由
    if (url.pathname.startsWith('/console/v1/')) {
      handleZjmf(req, res, url).catch((e) => {
        try { zjmfJson(res, 200, { status: 500, msg: 'mock internal error: ' + e.message, data: {} }); } catch { /* ignore */ }
      });
      return;
    }
    maybeHandleDownloadRaw(req, res, url).then((done) => { if (!done) handle(req, res).catch((e) => { try { send(res, 500, renderEnvelope(1, { success: false, code: 5000, msg: 'mock internal error: ' + e.message })); } catch { /* ignore */ } }); });
  };

  if (options.tls) {
    const key = fs.readFileSync(path.join(__dirname, 'certs', 'dev-key.pem'));
    const cert = fs.readFileSync(path.join(__dirname, 'certs', 'dev-cert.pem'));
    server = https.createServer({ key, cert }, requestListener);
    server._fingerprint256 = new crypto.X509Certificate(cert).fingerprint256;
  } else {
    server = http.createServer(requestListener);
  }

  server.on('listening', () => {
    const a = server.address();
    const scheme = options.tls ? 'https' : 'http';
    baseUrlCache = `${scheme}://127.0.0.1:${a.port}`;
  });

  const api = {
    server,
    state: st,
    newPairCode,
    fingerprint256: server._fingerprint256 || null,
    start(port = 0, host = '127.0.0.1') {
      return new Promise((resolve, reject) => {
        server.once('error', reject);
        server.listen(port, host, () => resolve(api.port));
      });
    },
    close() {
      return new Promise((resolve) => {
        if (!server.listening) return resolve();
        server.closeAllConnections?.();
        server.close(() => resolve());
      });
    },
    get port() { return server.address()?.port ?? null; },
    get baseUrl() { return baseUrl() || `http://127.0.0.1:${api.port}`; },
  };
  return api;
}

// ---------------------------------------------------------------- CLI

function main() {
  const args = process.argv.slice(2);
  const get = (name, dft) => { const a = args.find((x) => x.startsWith(`--${name}=`)); return a ? a.split('=')[1] : dft; };
  const has = (name) => args.includes(`--${name}`);
  const helpText = `MNBT mock server\n` +
    `  node tools/mock/server.mjs [--port=8787] [--state=running|expired|banned|deleted|node_unreachable|low_plugin] [--tls]\n` +
    `  node tools/mock/server.mjs --gen-code [port]   为运行中的 server 生成配对码并打印二维码 URL`;
  if (has('help')) { console.log(helpText); return; }

  if (has('gen-code')) {
    const portArg = args.find((a, i) => i === args.indexOf('--gen-code') + 1 && /^\d+$/.test(a)) || get('gen-code-port', null) || get('port', '8787');
    const target = `http://127.0.0.1:${portArg}/__mock__/gen_pair_code`;
    fetch(target)
      .then((r) => { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
      .then((j) => {
        console.log('配对码:      ' + j.code);
        console.log(`二维码 URL: ${j.qr_url}`);
        console.log('手输 - 站点地址(base): ' + j.base);
        console.log('手输 - 配对码: ' + j.code);
        console.log('有效期 120 秒，单次使用。');
      })
      .catch((e) => { console.error(`无法连接 mock server（${target}）：${e.message}\n请先启动：node tools/mock/server.mjs --port=${portArg}`); process.exit(1); });
    return;
  }

  const port = Number(get('port', '8787'));
  const host = get('host', '127.0.0.1');
  const tls = has('tls');
  const app = createMockServer({ state: get('state', 'running'), tls });
  app.start(port, host).then(() => {
    const rec = app.newPairCode();
    console.log(`MNBT mock server (${tls ? 'https/tls' : 'http'}) 监听 ${app.baseUrl}`);
    if (tls && app.fingerprint256) console.log(`dev 证书 SHA-256 指纹（APP 信任屏核对用）: ${app.fingerprint256}`);
    console.log(`初始配对码: ${rec.code}  (120s, 单次)`);
    console.log(`二维码 URL: ${app.baseUrl}/index.php?_r=/app/v1/pair_page&c=${rec.code}`);
    console.log(`状态开关:   GET ${app.baseUrl}/__mock__/state?set=expired|banned|deleted|node_unreachable|low_plugin|running`);
    console.log(`新配对码:   运行中可执行 node tools/mock/server.mjs --gen-code ${port}，或 GET /__mock__/gen_pair_code`);
    console.log('Ctrl+C 退出。');
  }).catch((e) => { console.error('启动失败:', e.message); process.exit(1); });
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();
