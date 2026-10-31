# scripts/tabler — Tabler SVG → Android VectorDrawable

一次性批量把 [Tabler Icons](https://tabler.io/icons)（MIT）的描边图标转成 Compose 可用的
`VectorDrawable`，产出直接落到 `app/src/main/res/drawable/`。规则对齐 `docs/prd/mobile-app.md`
§8.3/§8.4：单色描边、`strokeWidth=2`、24dp viewport；`strokeColor` 写死不透明黑
`#FF000000`，实际颜色由使用处 `Modifier`/`Icon` 的 `tint` 走语义色覆盖（XML 内不加 `android:tint`）。

## 加新图标

编辑同目录 `icons.json`，加一行 `"目标drawable名": "tabler图标名"`（tabler 名用中划线，
drawable 名必须等于 `ic_` + tabler 名把中划线换成下划线，脚本会校验并对不上就报警）。
若某个 tabler 名在该版本不存在，改用语义最近的真实图标，并把该条写成对象记录原因：

```json
"ic_foo_bar": "foo-bar"
```

```json
"ic_foo": { "icon": "some-real-tabler-name", "note": "foo 在该版本无此图标，用 xxx 代替" }
```

## 重跑

```bash
# 必须在 Git Bash 里跑（脚本用系统 GNU tar 解 npm tarball）；node 直接可用，无需 npm install
node scripts/tabler/convert.mjs
```

- 脚本从 npm registry 查 `@tabler/icons` 的 latest 版本，下载 tarball 并用系统 `tar` 解到
  系统临时目录（`$TMPDIR/tabler-convert/<version>`），**只下载一次**；临时目录不进仓库。
- 幂等：重复执行会覆盖同名 XML；已解包则复用缓存不再联网（重跑会打印 `reused from cache`）。
- 找不到 outlined 源目录时，会依次尝试 `package/icons/outline`（新版布局）与
  `package/svg/outlined/default`（旧版布局）。
- 每个 tabler SVG 首条 `<path stroke="none" d="M0 0h24v24H0z">` 是透明包围矩形，转换后保留为
  仅含 `pathData`、无 `strokeColor` 的不可见 path，以保证 path 数量与源 SVG 1:1，同时不会被误染成黑框。
- 若网络不可用，脚本会明确报错退出，不会手搓 SVG 路径。

## 校验

脚本转换时会逐条比对源/输出 path 数并打印 `OK/!!`；全部成功退出码 0，有问题退出码 1。
输出文件名即 drawable 名（`ic_<name>.xml`），MainActivity 导航按这些名字引用，请勿手改 XML。
