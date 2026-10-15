# HANDOFF：给下一个 AI 的交接说明

> 2026-10-15 写于 Hark。请先读完本文件，再读 `README.md`（完整设计文档，共 20 节）和 `design/shell-progress.md`。

## 0. 一句话
**Clawdy** 是装在手机上本地运行的个人 Android Agent，fork 自 [nanoMuse](https://github.com/nano-muse/nanoMuse)（上游 HEAD `a0d727fc04eafa74694a05af8aeac3d793efbafd`），遵循 GPL-3.0-or-later。
- 对话界面：Jetpack Compose，暖白底 + 珊瑚色强调 + 衬线字体。
- 模型：用户自带 API Key，直连模型服务商。
- 命令执行：只走 **Termux**（RUN_COMMAND intent）。
- 操作手机：**Android 无障碍服务**（Hands）；Shizuku 是可选能力。
- **不用**云电脑、云手机或中继服务器。PRoot/Alpine 沙箱已决定移除。

## 1. 硬性约定（用户已经定下的，不要推翻）
- applicationId：`io.github.qigai.clawdy`。Kotlin 内部包名保持 `com.openminis.app` 不改，方便和上游同步。
- 默认模型：DeepSeek V4.1 Flash，API ID `deepseek-flash`，Base URL `https://api.deepseek.com`。自定义 OpenAI 兼容服务商的示例用 `gpt-6-astra`。
- 图标一律用 SVG/ImageVector，**不用 emoji**。开关用小圆角矩形，**不用胶囊形**。
- 字体只用开源衬线体：Noto Serif SC、Source Serif 4。**不得**使用 Anthropic 的专有字体和商标。
- 首页不放「建议」按钮。输入框上方只显示「已连接工具 n/N」。工具调用的步骤收进对话流里的浅橙色任务卡，点击展开。
- 引用块：左侧强调色竖条，浅色底，14dp 圆角。
- 第 11 页（工具）必须带无障碍权限引导。第 05 页打开文件时调用外部应用，比如 WPS。
- 和用户沟通用中文。

## 2. 仓库结构
| 路径 | 内容 |
|---|---|
| `app/` | Android 工程（nanoMuse fork） |
| `app/src/main/java/com/openminis/app/ui/clawdy/` | 新前端壳：`ClawdyShell`、`ClawdyScreens`、`ClawdySheets`、`ClawdyTaskCard`、`ClawdyTurns`、`ClawdyComponents`、`ClawdyIcons` |
| `app/src/main/java/com/openminis/app/clawdy/` | `ClawdyFlags`（`SANDBOX_IN_AGENT=false`）、`ClawdyPresets`（模型目录）、`ToolStatus`、`HandsTool`（`phone_hands`） |
| `app/src/main/java/com/openminis/app/tools/TermuxTool.kt` | `termux_run`，经过 `RiskGate.checkShell` 审批 |
| `app/src/main/java/com/openminis/app/ui/theme/ClawdyTokens.kt` | 设计令牌 |
| `scripts/termux-bootstrap.sh` | 在手机的 Termux 里运行一次，开启 `allow-external-apps` |
| `design/` | 15 张界面概念图和逐页交互说明 |
| `brand/` | 图标和头像的源文件（授权见第 6 节） |
| `docs/Clawdy-README.pdf` | README 的 PDF 版 |
| `tools/` | 构建主机脚本：`setup-host-aarch64.sh`、`env.sh`、`build.sh` |

导航入口：`Routes.SESSION_LIST` 指向 `ClawdyShell`，路由定义在 `AppNavigation.kt`。

## 3. 构建
标准环境：JDK 17 + Android SDK（platform 36、build-tools 35.0.0）。
```bash
./gradlew :app:assembleDebug -x lint
# 产物：app/build/outputs/apk/debug/app-debug.apk（约 64 MB）
```
- 原生库（`jniLibs`）取自 nanoMuse v1.0.5 的 release APK，rclone 已经 stub 掉。
- `gradle.properties` 按 7 GB 内存的主机调过：`-Xmx3584m`、`workers.max=1`、不开并行、KSP 非增量。机器更强时可以调大。
- 如果在 aarch64 Linux 上构建，aapt2 需要通过 qemu-x86_64 运行。参考 `tools/env.sh`（其中的代理配置是 Hark 沙箱专用，用不上就删掉）。完整构建约 10 分钟。
- 已知问题：原始工作区里 `jniLibs/arm64-v8a/libc++_shared.so` 曾显示为被删除，本仓库保留了它。如果出现重复库冲突，可以删掉它，因为依赖库也会提供同一个文件。

已构建的调试版 APK 发在 GitHub Releases：`v0.1.0-debug`，对应提交是最后一个 "Gate termux_run…"。**还没在真机上测过**。

## 4. 已完成
- 15 屏 Compose 壳：首页、任务卡（折叠/展开/完成/失败）、步骤详情、审批单、侧边栏、模型选择、添加服务商（BYOK、测试连接、拉取 `/models`）、工具页（真实状态检测和无障碍引导）、首次引导、附件、工作区、设置、许可证页。
- 深色主题，衬线字体全局生效。
- Agent 工具列表里移除了 `shell_execute`（PRoot）。新增 `phone_hands`，直接调用 `HandsOffloadHandler`。系统提示词改为 Termux 优先。
- 模型预置目录截至 2026-10-15：DeepSeek、OpenAI GPT-6（走 `useResponsesAPI`，未测）、Anthropic、Gemini、Qwen、GLM、Kimi、OpenRouter。

## 5. 待办（建议按这个顺序做）
1. **真机冒烟测试**：首次引导 → 填入 DeepSeek Key → 跑一次 `termux_run` → 开启无障碍 → 跑一次 `phone_hands`。先修崩溃。
2. 把「截图上传」和「不在密码框上操作」两个设置真正接入 Hands 和 Agent（现在只保存了设置值）。
3. Hands 悬浮层加暂停/继续（现有的 operator 不支持暂停，需要改 `HandsOffloadHandler`）。
4. 附件面板：「当前屏幕」截图、最近照片条；把联网搜索和 Hands 开关接入 Agent。
5. 审批单：支持编辑待执行的动作（现在点「编辑」会取消动作，并把内容放回输入框）；「将发送的数据」一行要显示真实统计。
6. 工作区页：长按菜单、「+」按钮。
7. 移除沙箱残留：APK 里还带着 `assets/alpine-minirootfs.tar`（8 MB）和 `proot`，启动时仍会安装 rootfs 并启动 offload server。拆的时候要先把 Hands 和 offload 对它的依赖解开，**改完必须测**。「旧版界面」设置里还能看到沙箱页面。
8. 点赞/点踩要持久化；任务卡的展开状态要按消息保存。
9. 旧页面和深链接还在用 Material 图标或旧的 ChatScreen，要逐步迁移。

## 6. 授权注意
- `brand/` 和 `app/src/main/res/drawable-nodpi/nm_avatar_*.webp` 里的二次元角色来自小红书画师。用户于 2026-10-15 向画师确认过，可以公开使用（public）；转载时请注明画师。
- `design/s*.png` 是 AI 生成的概念图，只用作参考。
- 字体使用 OFL 许可，许可文本在 `app/src/main/assets/licenses/`。

## 7. 和上游同步
```bash
git remote add upstream https://github.com/nano-muse/nanoMuse.git
git fetch upstream
# 上游的 Android 工程在 android/src/android，用 subtree 或 cherry-pick 合并
```

## 8. 网页评审原型
Hark 里有个私有 Page「Clawdy 评审原型」，带检查器，可以导出 TXT 反馈。用户贴回反馈时，每条都落实到 `design/` 和 Compose 实现里。
