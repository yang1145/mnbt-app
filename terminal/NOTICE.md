# terminal 模块来源与改动说明

## 来源

- 上游项目：[termux-app](https://github.com/termux/termux-app) `v0.118.3`
- 取用范围：`terminal-emulator` 与 `terminal-view` 两个子项目的 Java 源码、文本选择手柄
  drawable、以及三条字符串资源。上游的 20 个 JVM 单元测试一并内联，用来在改完之后验证模拟器
  行为没有走样。
- 许可：termux-app 整体是 GPLv3，但 `terminal-emulator` / `terminal-view` 在上游 LICENSE.md 中
  被单独列为 **Apache License 2.0**（它们派生自 jackpal/Android-Terminal-Emulator）。因此本模块
  不受 GPLv3 传染。完整许可证正文见同目录 `LICENSE-APACHE-2.0.txt`。

## 为什么内联而不是引依赖

上游发布的 AAR 无法驱动远程 shell：`TerminalSession` 是 `final`，`initializeEmulator()` 里直接
调 `JNI.createSubprocess()` fork 本机 `/system/bin/sh`，`write()` 在 `mShellPid > 0` 时才生效，
而 `TerminalView.attachSession()` 只认 `TerminalSession`。接 jitpack 依赖的结果是在手机上开一个
本地 shell，不是 SSH 会话。市面上也没有提供可插拔传输层的现成 Android 终端控件，所以只能改源码。

## 相对上游的改动

| 文件 | 改动 |
|------|------|
| `com/termux/terminal/TerminalSession.java` | **重写**。去掉 `final`；删除 JNI / 本地进程 / `/proc` 相关代码；新增 `TerminalSession.Channel` 接口作为传输层接缝；`mShellPid` 三态换成 `STATE_NEW/RUNNING/EXITED`；新增 `onRemoteOutput()` / `onRemoteClosed()` 供传输层回调；`cleanupResources()` 改为幂等；`MainThreadHandler` 显式 `super(Looper.getMainLooper())`（上游无参构造在后台线程建会话时会崩）；会话结束时打到屏幕上的那行英文提示换成中文，并去掉"press Enter"——我们在打印后立刻回调 `onSessionFinished`，不等回车；`writeCodePoint()` 与其余公开 API 逐字保留。 |
| `com/termux/terminal/JNI.java` | **未内联**（本地进程创建，远程会话用不到）。 |
| `src/main/jni/**` | **未内联**（C 代码与 ndk-build 配置，同上）。 |
| `res/values-zh/strings.xml` | **新增**，把上游三条英文菜单文案译成中文。上游 `res/values/strings.xml` 原样保留作 fallback。 |
| `build.gradle.kts` / `AndroidManifest.xml` | **新增**，替换上游两个 Groovy 构建脚本。上游 `terminal-emulator` 与 `terminal-view` 合并为本模块，namespace 取 `com.termux.view`，这样两个 textselection 源文件里的 `import com.termux.view.R;` 无需改动。 |
| 其余全部文件 | **逐字节照抄上游**，包括包名、注释与上游遗留的 `// NO_UCD` 标记。 |

## 升级上游的方式

重新从上游取对应版本的 `terminal-emulator/src/main/java` 与 `terminal-view/src/main/java` 覆盖
本模块，然后按上表重放 `TerminalSession.java` 的改动、重新排除 `JNI.java` 与 `jni/`。上游单元测试
不引用 `TerminalSession`（用的是自己的 `MockTerminalOutput`），所以覆盖后可以直接跑
`:terminal:testDebugUnitTest` 验证。
