# textmate 资产来源说明

红线：本目录全部文件在**构建期**取用并随 APK 打包，**运行时零站外请求**。
编辑器语法高亮、主题配色一律从 `assets/textmate/` 读取，不得改为在线拉取。

## php.tmLanguage.json

- 来源：<https://raw.githubusercontent.com/microsoft/vscode/1.96.0/extensions/php/syntaxes/php.tmLanguage.json>
  （VS Code 官方内置 PHP 扩展的语法文件，锁定 tag `1.96.0`）
- 上游：文件内 `information_for_contributors` 标注，它由
  <https://github.com/KapitanOczywisty/language-php>（commit
  `5e8f000cb5a20f44f7a7a89d07ad0774031c53f3`）的 `grammars/php.cson` 转换而来。
- 许可：MIT（microsoft/vscode 与 KapitanOczywisty/language-php 均为 MIT）。
  正式许可声明页在 P6 统一整理，此处仅记录出处。
- 自包含性：单文件、`scopeName = source.php`，无外部 grammar include、无 injections。
  语法内嵌的 HTML 片段（`<?php ... ?>` 之外的部分）按纯文本着色，
  因为未附带 `text.html.basic` grammar —— 这是刻意取舍，避免多文件依赖。
- 覆盖度说明：PHP 8.x 语法高亮可用；Heredoc、命名参数等个别构造的着色可能不精确，
  属可接受范围（PRD 未要求精细还原 VS Code 配色）。

## mnbt-light.json / mnbt-dark.json

- 手写的最小 TextMate 主题（VS Code JSON 主题格式：`tokenColors` + `colors`），
  由 sora-editor `language-textmate` 模块（内含 tm4e 的 RawThemeReader）解析。
- 前景/背景/光标/选区/行号色取自 PRD §8.1 色板（`ui/theme/Color.kt` 的
  LightPalette / DarkPalette 近似值），token 色只做粗略区分，不追求还原。
- 许可：本项目自产，随仓库许可。

## 与 sora-editor 的对应关系（0.24.4，tag `0.24.4`）

- grammar 路径在 `TextmateSupport.kt` 中以 DSL 注册：`textmate/php.tmLanguage.json`
  → scope `source.php`。文件名必须以 `.json` 结尾（tm4e 按扩展名猜 ContentType）。
- 主题名按文件名注册（`ThemeModel` 取去扩展名的文件名）：`mnbt-light` / `mnbt-dark`，
  `ThemeRegistry.setTheme("mnbt-dark")` 切换。
