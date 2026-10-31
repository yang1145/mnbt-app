// tools/mock/test.mjs —— mock server 自测：同进程随机端口启动，全部断言后关闭服务器。
// 运行: node tools/mock/test.mjs
import { createMockServer } from './server.mjs';
import crypto from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

let passed = 0;
let failed = 0;
function ok(cond, name, detail) {
  if (cond) { passed++; console.log(`  PASS  ${name}`); }
  else { failed++; console.error(`  FAIL  ${name}${detail ? ' :: ' + detail : ''}`); }
}

const app = createMockServer({ sandboxRoot: path.join(__dirname, '.sandbox') });
await app.start(0, '127.0.0.1');
const base = app.baseUrl;

async function post(action, params, { token, fmt, headers = {}, rawBody, contentType } = {}) {
  const h = { ...headers };
  let body;
  if (rawBody !== undefined) {
    body = rawBody;
    if (contentType) h['Content-Type'] = contentType;
  } else if (params && Object.keys(params).some((k) => Array.isArray(params[k]))) {
    const usp = new URLSearchParams();
    for (const [k, v] of Object.entries(params)) {
      if (Array.isArray(v)) for (const item of v) usp.append(`${k}[]`, item);
      else usp.append(k, v);
    }
    body = usp.toString();
    h['Content-Type'] = 'application/x-www-form-urlencoded';
  } else if (params) {
    body = new URLSearchParams(params).toString();
    h['Content-Type'] = 'application/x-www-form-urlencoded';
  }
  if (token) h['Authorization'] = 'Bearer ' + token;
  const qs = fmt && fmt !== 1 ? `_fmt=${fmt}&` : '';
  const r = await fetch(`${base}/index.php?${qs}_r=/app/v1/${action}`, { method: 'POST', headers: h, body });
  const text = await r.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* fmt4 */ }
  return { text, json };
}

const reset = async () => { await fetch(`${base}/__mock__/reset`, { method: 'POST' }); };

async function newCode(ttl) {
  const j = await (await fetch(`${base}/__mock__/gen_pair_code${ttl ? `?ttl=${ttl}` : ''}`)).json();
  return j.code;
}

let TOKEN = '';

console.log('== 1. 配对流程 ==');
{
  // 配对码格式检查（8 位、去混淆字符集）
  const code = await newCode();
  ok(/^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{8}$/.test(code), '配对码为 8 位去混淆大写字符', code);

  const { json } = await post('pair', { code, device_id: 'uuid-test', device_name: 'Pixel 8', app_version: '1.0' });
  ok(json && json.success === true && json.code === 0, '配对成功返回标准包络');
  ok(json?.data?.token && /^[0-9a-f]{64}$/.test(json.data.token), 'token 为 64 位 hex（bin2hex(random_bytes(32)) 形态）');
  ok(json?.data?.site?.base && json?.data?.host?.user === 'demo01' && json?.data?.host?.web_quota?.max === 1024, 'data 含 token+site+host 档案（§4.5 同构）');
  // $extra 平铺到顶层（§4.5 注 1）
  ok(json?.token === json?.data?.token && json?.host?.user === 'demo01', '标准包络把载荷同时平铺到顶层');
  TOKEN = json.data.token;

  // 单次使用
  const dup = await post('pair', { code });
  ok(dup.json?.code === 1002, '重复使用同一配对码 -> 1002', dup.text);

  // 错误码
  const bad = await post('pair', { code: 'ZZZZZZZZ' });
  ok(bad.json?.code === 1002, '错误配对码 -> 1002');

  // 过期码
  await reset();
  const shortCode = await newCode(30); // 30ms TTL
  await new Promise((r) => setTimeout(r, 60));
  const expired = await post('pair', { code: shortCode });
  ok(expired.json?.code === 1002, '过期配对码 -> 1002');

  // 限流：IP 10 分钟 5 次失败 -> 1008
  await reset();
  let last;
  for (let i = 0; i < 5; i++) last = await post('pair', { code: 'AAAAAAAA' });
  ok(last.json?.code === 1002, '前 5 次失败仍为 1002');
  const limited = await post('pair', { code: await newCode() }); // 即使码有效
  ok(limited.json?.code === 1008, '第 6 次失败 -> 1008 限流', limited.text);
}

console.log('== 2. Bearer 鉴权 / device.info 免鉴权 ==');
{
  await reset();
  const noAuth = await post('host.overview', {});
  ok(noAuth.json?.code === 1001, '无 Authorization -> 1001');
  const wrongAuth = await post('host.overview', {}, { token: 'deadbeef'.repeat(8) });
  ok(wrongAuth.json?.code === 1001, '无效令牌 -> 1001');
  const info = await post('device.info', {});
  ok(info.json?.success === true && info.json.data.bridge_version && info.json.data.mnbt_version && Array.isArray(info.json.data.supported), 'device.info 免鉴权，返回 bridge_version/mnbt_version/action 清单');
  ok(info.json.data.supported.length >= 60 && info.json.data.supported.includes('file.upload') && info.json.data.supported.includes('ssl.force_https'), `device.info 支持清单覆盖 §5 全量 action（${info.json.data.supported.length} 个）`);
}

console.log('== 3. 重新配对 + device.revoke -> 再请求 1001 ==');
{
  await reset();
  const code = await newCode();
  const pair = await post('pair', { code, device_name: 'test' });
  TOKEN = pair.json.data.token;
  const ping = await post('device.ping', {}, { token: TOKEN });
  ok(ping.json?.success === true && ping.json.data.pong === true, '配对后 device.ping 正常');
  const rev = await post('device.revoke', {}, { token: TOKEN });
  ok(rev.json?.success === true && rev.json.data.revoked === true, 'device.revoke 成功');
  // 内存只存 sha256（§6.2 token_hash 语义）
  const devices = await (await fetch(`${base}/__mock__/devices`)).json();
  const rec = devices.devices.find((d) => d.status === 0);
  ok(!!rec, '吊销后设备记录 status=0（对齐 §6.2 MN_plugin_app_device）');
  const after = await post('host.overview', {}, { token: TOKEN });
  ok(after.json?.code === 1001, '吊销后再请求 -> 1001');
}

console.log('== 4. host.overview 用量随机漂移 ==');
{
  await reset();
  const code = await newCode();
  TOKEN = (await post('pair', { code })).json.data.token;
  const a = await post('host.overview', {}, { token: TOKEN });
  const b = await post('host.overview', {}, { token: TOKEN });
  const c = await post('host.overview', {}, { token: TOKEN });
  const pick = (j) => j.data.web_quota.used + ':' + j.data.traffic_quota.used;
  ok(a.json.success && typeof a.json.data.web_quota.used === 'number' && typeof a.json.data.sql_quota.max === 'number', 'host.overview 返回 web/sql/traffic 配额对象');
  ok(pick(a.json) !== pick(b.json) || pick(b.json) !== pick(c.json), '连续刷新用量数字有小幅变化（diff 刷新可验证）', `${pick(a.json)} | ${pick(b.json)} | ${pick(c.json)}`);
}

console.log('== 5. 四种响应形态 ==');
{
  await reset();
  const code = await newCode();
  TOKEN = (await post('pair', { code })).json.data.token;
  // 默认必须走标准包络
  const dft = await post('site.rewrite.get', {}, { token: TOKEN });
  ok(dft.json.success === true && dft.json.code === 0 && dft.json.msg === 'ok' && dft.json.data.rewrite, '默认（无 _fmt）= 标准包络');
  ok(dft.json.data.current === dft.json.current, '默认包络 extra 平铺顶层');

  const f1 = await post('host.profile', {}, { token: TOKEN, fmt: 1 });
  ok(f1.json.success === true && typeof f1.json.code === 'number' && f1.json.data.user === 'demo01', '形态1: success/code/msg/data 标准包络 + 顶层平铺');

  const f2 = await post('host.profile', {}, { token: TOKEN, fmt: 2 });
  ok(f2.json.qk === 1 && typeof f2.json.code === 'string' && f2.json.msg && f2.json.msg.user === 'demo01', '形态2: qk=1 + code 文本 + 载荷在 msg');

  const f3 = await post('site.php.set', { php_version: '8.1' }, { token: TOKEN, fmt: 3 });
  ok(f3.json.success === undefined && f3.json.qk === undefined && typeof f3.json.code === 'string' && f3.json.phpversion === '8.1', '形态3: 无成功标志裸字段（{"code":"修改成功","phpversion":"7.4"} 同款）', JSON.stringify(f3.json));

  const f4 = await post('host.profile', {}, { token: TOKEN, fmt: 4 });
  ok(/<title>站点提示信息<\/title>/.test(f4.text) && /<body id="error-page">/.test(f4.text), '形态4: 非 JSON 的 sysmsg HTML 页');

  // 请求头覆盖开关
  const fh = await fetch(`${base}/index.php?_r=/app/v1/host.profile`, { method: 'POST', headers: { Authorization: 'Bearer ' + TOKEN, 'X-Mock-Format': '2' } });
  const hj = await fh.json();
  ok(hj.qk === 1 && hj.msg?.user === 'demo01', 'X-Mock-Format 头覆盖形态');

  // 全量默认形态抽检：所有 action 默认返回标准包络
  await post('file.create', { path: '/sweep_del.txt', type: 'file' }, { token: TOKEN });
  const actions = (await post('device.info', {})).json.data.supported;
  let badFmt = [];
  for (const a of actions) {
    if (a === 'file.upload' || a === 'file.download' || a === 'device.revoke') continue; // multipart / 外链 / 吊销会破坏 token
    const params = { path: '/', file: '/index.html', content: 'sweep', domain: 'sweep.example.com', webname: 'sweep.example.com', src: '/a', dest: '/b', id: '1' };
    if (a === 'file.delete') params.path = '/sweep_del.txt';
    const r = await post(a, params, { token: TOKEN });
    if (!(r.json && r.json.success === true && r.json.code === 0 && r.json.data)) badFmt.push(a + ':' + r.text.slice(0, 60));
  }
  ok(badFmt.length === 0, `默认全量 ${actions.length} 个 action 走标准包络`, badFmt.join(' | '));
}

console.log('== 6. file.save / file.read GBK 字节原样往返（沙箱真读写） ==');
{
  await reset();
  const code = await newCode();
  TOKEN = (await post('pair', { code })).json.data.token;

  // 手工构造 GBK 内容的百分号编码 body（模拟 APP 用 GBK 字节 %XX 编码后提交）
  const gbkText = '<?php // 梦奈云主机：GBK 编码测试「全角符号」 end';
  const iconv = new TextDecoder('gbk'); // 仅用于校验解码
  // 直接构造 GBK 字节：node TextEncoder 只出 UTF-8，这里用 Buffer 查表——改用已知 GBK 序列
  const gbkBytes = Buffer.from([
    0x3c, 0x3f, 0x70, 0x68, 0x70, 0x20, // <?php
    0x2f, 0x2f, 0x20, // // 
    0xc3, 0xce, 0xc4, 0xce, 0xd4, 0xc6, 0xd6, 0xf7, 0xbb, 0xfa, // 梦奈云主机 (GBK)
    0xa3, 0xba, // ：(GBK 全角冒号)
    0x47, 0x42, 0x4b, // GBK
    0x20, 0xb1, 0xe0, 0xc2, 0xeb, 0xb2, 0xe2, 0xca, 0xd4, // 编码测试 (GBK)
    0xa1, 0xb0, 0xc8, 0xab, 0xbd, 0xc7, 0xb7, 0xfb, 0xba, 0xc5, 0xa1, 0xb1, // 「全角符号」
    0x20, 0x65, 0x6e, 0x64, // end
  ]);
  let encoded = '';
  for (const b of gbkBytes) encoded += '%' + b.toString(16).padStart(2, '0').toUpperCase();
  const body = 'path=' + encodeURIComponent('/sub/gbk_test.php') + '&content=' + encoded + '&charset=GBK';
  const saveResp = await fetch(`${base}/index.php?_r=/app/v1/file.save`, {
    method: 'POST',
    headers: { Authorization: 'Bearer ' + TOKEN, 'Content-Type': 'application/x-www-form-urlencoded' },
    body,
  });
  const saved = await saveResp.json();
  ok(saved.success === true && saved.data.size === gbkBytes.length, 'file.save 按原始字节数落盘（非 UTF-8 字节不被破坏）', JSON.stringify(saved.data));

  // 磁盘上的字节必须与提交的 GBK 字节完全一致
  const fs = await import('node:fs');
  const onDisk = fs.readFileSync(path.join(__dirname, '.sandbox', 'wwwroot', 'demo01.example.com', 'sub', 'gbk_test.php'));
  ok(onDisk.equals(gbkBytes), '磁盘字节与提交字节逐字节一致');

  const readResp = await post('file.read', { path: '/sub/gbk_test.php' }, { token: TOKEN });
  const back = Buffer.from(readResp.json.data.content_base64, 'base64');
  ok(back.equals(gbkBytes), 'file.read content_base64 还原为相同 GBK 字节（原样往返）');
  try {
    ok(iconv.decode(back).includes('梦奈云主机'), 'GBK 字节可解码回中文（往返无损）');
  } catch { ok(false, 'TextDecoder gbk 可用'); }

  const listResp = await post('file.list', { path: '/' }, { token: TOKEN });
  const paths = listResp.json.data.list.map((e) => e.path);
  ok(listResp.json.success && paths.includes('/sub/gbk_test.php') && paths.includes('/config.php'), 'file.list 返回沙箱目录树（含新建文件与 GBK 种子文件）');

  const traversal = await post('file.read', { path: '/../../../Windows/win.ini' }, { token: TOKEN });
  ok(traversal.json?.code === 1007, '路径穿越被拒绝 -> 1007');
}

console.log('== 7. file.delete_batch 数组参数 + multipart 上传 ==');
{
  await reset();
  const code = await newCode();
  TOKEN = (await post('pair', { code })).json.data.token;
  const batch = await post('file.delete_batch', { paths: ['/upload/readme.txt', '/nonexistent'] }, { token: TOKEN });
  ok(batch.json.success && batch.json.data.count === 1 && batch.json.data.deleted.includes('/upload/readme.txt'), 'name[]=v 数组参数正确解析（删除 1 个存在文件）', JSON.stringify(batch.json.data));

  const b = '----mocktest';
  const mp = Buffer.concat([
    Buffer.from(`--${b}\r\nContent-Disposition: form-data; name="upload_id"\r\n\r\nabc123\r\n`),
    Buffer.from(`--${b}\r\nContent-Disposition: form-data; name="path"\r\n\r\n/upload\r\n`),
    Buffer.from(`--${b}\r\nContent-Disposition: form-data; name="file"; filename="bin.dat"\r\nContent-Type: application/octet-stream\r\n\r\n`),
    Buffer.from([0x00, 0x01, 0xc3, 0xce, 0xff, 0xfe]),
    Buffer.from(`\r\n--${b}--\r\n`),
  ]);
  const up = await post('file.upload', {}, { token: TOKEN, rawBody: mp, contentType: `multipart/form-data; boundary=${b}` });
  ok(up.json.success && up.json.data.size === 6 && up.json.data.name === 'bin.dat', 'multipart file.upload 透传校验通过，二进制 6 字节落盘', JSON.stringify(up.json.data));
  const upNoFile = await post('file.upload', {}, { token: TOKEN, rawBody: Buffer.from(`--${b}\r\nContent-Disposition: form-data; name="upload_id"\r\n\r\nx\r\n--${b}--\r\n`), contentType: `multipart/form-data; boundary=${b}` });
  ok(upNoFile.json?.code === 1007, 'multipart 缺文件字段 -> 1007');

  const dl = await post('file.download', { path: '/sub/gbk_test.php' }, { token: TOKEN });
  ok(dl.json.success && dl.json.data.url.includes('file_download_raw'), 'file.download 返回外链');
  if (dl.json.success) {
    const raw = await fetch(dl.json.data.url);
    ok(raw.status === 200 && (await raw.arrayBuffer()).byteLength > 0, 'file.download 外链可真实下载字节');
  }
}

console.log('== 8. 错误码状态开关 ==');
{
  await reset();
  const code = await newCode();
  TOKEN = (await post('pair', { code })).json.data.token;
  const stateCases = [
    ['expired', 1003], ['banned', 1004], ['deleted', 1005], ['node_unreachable', 1006],
  ];
  for (const [s, ec] of stateCases) {
    await fetch(`${base}/__mock__/state?set=${s}`);
    const r = await post('host.overview', {}, { token: TOKEN });
    ok(r.json?.code === ec && r.json.success === false, `状态 ${s} -> ${ec}（JSON 而非 HTML，§4.6 注）`, r.text.slice(0, 80));
  }
  await fetch(`${base}/__mock__/state?set=low_plugin`);
  const lp = await post('file.list', { path: '/' }, { token: TOKEN });
  ok(lp.json?.code === 1010, '状态 low_plugin -> 非基础 action 返回 1010');
  const info = await post('device.info', {});
  ok(info.json.success === true, 'low_plugin 下 device.info 仍可用（能力协商）');
  await fetch(`${base}/__mock__/state?set=running`);
  const back = await post('host.overview', {}, { token: TOKEN });
  ok(back.json.success === true, '切回 running 后恢复正常');
}

console.log('== 9. pair_page 静态页 + GET 路由容忍 ==');
{
  const pp = await fetch(`${base}/index.php?_r=/app/v1/pair_page&c=ABCD2345`);
  const html = await pp.text();
  ok(pp.headers.get('content-type').includes('text/html') && /请使用「MNBT 主机管理」APP/.test(html) && !/https?:\/\/(?!127)/.test(html.replace(base, '')), 'pair_page 返回纯静态 HTML 提示页，零外部资源');
  const direct = await fetch(`${base}/app/v1/pair_page?c=XYZ`, { headers: { Authorization: 'Bearer ' + TOKEN } });
  ok(direct.status === 200 && (await direct.text()).includes('MNBT'), '容忍直接 /app/v1/{action} 路径');
  const getAct = await fetch(`${base}/index.php?_r=/app/v1/file.list&path=/`, { headers: { Authorization: 'Bearer ' + TOKEN } });
  const gj = await getAct.json();
  ok(gj.success === true && Array.isArray(gj.data.list), 'GET 形式的业务 action + query 参数可用');
}

console.log('== 10. 域名配额校验（§5.5 -> 1007） ==');
{
  await reset();
  const code = await newCode();
  TOKEN = (await post('pair', { code })).json.data.token;
  // reset 后域名集合含初始 2 个，ymbds=5，还能再加 3 个
  for (const d of ['a.example.com', 'b.example.com', 'c.example.com']) {
    const r = await post('domain.add', { domain: d }, { token: TOKEN });
    if (!r.json.success) { ok(false, 'domain.add 前 3 次应成功: ' + d + ' ' + r.text); break; }
  }
  const over = await post('domain.add', { domain: 'over.example.com' }, { token: TOKEN });
  ok(over.json?.code === 1007, '超出 ymbds 最大绑定数 -> 1007', over.text);
}

await reset();
await app.close();
ok(!app.server.listening, '测试结束服务器已关闭');

console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed === 0 ? 0 : 1);
