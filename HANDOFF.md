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
| `app/src/main/java/com/openminis/app/ui/clawdy/` | 新前端壳：`ClawdyShell`、`ClawdyScreens`、`ClawdySheets`、`ClawdyTaskCard`、`ClawdyTurns`、`ClawdyComponents`、`ClawdyIcons`；设置：`ClawdySettings`（首页、模型配置、密钥、分组）、`ClawdySecurityScreens`（安全、密码箱、代填确认、敏感应用）、`ClawdyMoreSettings`（对话、记忆、技能、环境变量、网络、后台、Hands、定时任务） |
| `app/src/main/java/com/openminis/app/clawdy/` | `ClawdyFlags`（`SANDBOX_IN_AGENT=false`）、`ClawdyPresets`（模型目录）、`ToolStatus`、`HandsTool`（`phone_hands`）、`ClawdySecurity`（安全设置的读取与接线）、`vault/`（`Vault` 加密存储、`VaultTools` = `vault_list`/`vault_fill` + 审批队列） |
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

已构建的调试版 APK：`release/Clawdy-0.2.0-debug.apk`（v0.2.0，设置重做 + 密码箱 + 安全接线）；旧的 `release/Clawdy-0.1.0-debug.apk` 用户已在真机上装过，能运行。GitHub Releases 对应 `v0.2.0-debug` / `v0.1.0-debug`。

构建主机从零搭建：`bash tools/setup-host-aarch64.sh`（JDK 17、cmdline-tools、platform 36、build-tools 35.0.0、qemu；Hark 沙箱里会把网关 CA 导入 JDK、用 `tools/fwdproxy.py` 做本地代理），然后 `setsid bash tools/build.sh &`，看 `/workspace/tools/build.log`。只编 Kotlin 可以跑 `./gradlew :app:compileDebugKotlin`。

## 4. 已完成
### v0.1.0
- 15 屏 Compose 壳：首页、任务卡（折叠/展开/完成/失败）、步骤详情、审批单、侧边栏、模型选择、添加服务商（BYOK、测试连接、拉取 `/models`）、工具页（真实状态检测和无障碍引导）、首次引导、附件、工作区、设置、许可证页。
- 深色主题，衬线字体全局生效。
- Agent 工具列表里移除了 `shell_execute`（PRoot）。新增 `phone_hands`，直接调用 `HandsOffloadHandler`。系统提示词改为 Termux 优先。
- 模型预置目录截至 2026-10-15：DeepSeek、OpenAI GPT-6（走 `useResponsesAPI`，未测）、Anthropic、Gemini、Qwen、GLM、Kimi、OpenRouter。

### v0.2.0（2026-10-15，按用户在真机截图上的批注）
- **设置首页重做**（`ui/clawdy/ClawdySettings.kt`）：头像行去掉副标题；模型 → 一行「配置」；安全 → 一行「安全」；新增 对话 / 工具 / 系统 三组，全部是原生 Clawdy 页面；「更多设置（旧版界面）」已删除。
- **模型配置页**：添加服务商、默认模型（写入「默认模型」分组并设为 `defaultPrimaryGroupId`）、密钥列表 → 密钥详情（改名、改 Base URL、换 Key、启用、Responses API、刷新 /models、手动加模型、删除）→ 模型详情（显示名、上下文窗口、最大输出、推理、识图、隐藏、推理强度上限，写进 `ModelOverrides`）、分组管理（新建、成员排序、依次备用/轮流、何时切换、上下文上限、设为默认、删除）。
- **安全页**（`ClawdySecurityScreens.kt`）：
  - 工作模式 谨慎/标准/放手 → `RiskGate.mode`（`GateMode`），真正改变审批行为（见 `RiskGate.check`）。
  - 截图上传（默认关）→ Hands 改为发送无障碍文字树（`hands/ScreenText.kt`，坐标同 0–999 网格），任何聊天模型都能当 Hands 模型（`Hands.textModel`）。
  - 不在密码框上操作（默认开）→ `HandsOperator.typeText`。
  - 读取剪贴板前确认 → `termux_run` 里出现 `termux-clipboard-get` 时弹审批卡。
  - 敏感应用名单（默认银行/支付类）→ Hands 每一步检查前台应用，命中即停止；`open_app` 也拒绝。
  - 已记住的授权逐条撤销 + 全部撤销（含本次运行的对话级授权）。
  - 审批卡「将发送的数据」显示真实统计（命令行数/字节、目标域名、附带文件、Hands 看截图还是文字）。
- **密码箱**（`clawdy/vault/`）：账号密码/银行卡/地址/备注四种表单；`vault.meta`（条目名，不需解锁）+ `vault.secret`（字段值，Keystore AES-256-GCM，有锁屏时密钥绑定锁屏、30 秒有效）；查看/编辑要指纹或锁屏密码；自动上锁时间可选。
  - Agent 工具 `vault_list`（只给名称/站点/字段名）和 `vault_fill`（审批单 + 锁屏验证后，本机用 ACTION_SET_TEXT 填入；工具结果只有 filled/denied/not_found 和字段名）。值不进模型请求、聊天记录、记忆、日志；Hands 文字模式会把代填过的值遮掉。
- **原生移植的旧版设置**：对话与上下文（自动压缩、长期记忆开关）、记忆（文件列表/编辑/删除）、技能（启用、查看 SKILL.md、GitHub 导入、删除）、定时任务（列表、启用、删除；编辑仍用上游编辑页）、环境变量（增删改、隐私模式；现在会在 `termux_run` 前 export，输出里的值会打码）、网络代理（只给模型服务商）、通知与后台（任务提醒、后台浮窗、电池优化、国产系统自启动）、Hands 设置（屏幕模型选择）。抽屉里的「定时任务」「记忆」也改成原生页。
- 调试签名固定：`app/clawdy-debug.keystore`（Android 默认 debug 口令）写进了 `signingConfigs.debug`。v0.1.0 用的是旧沙箱里丢失的临时 key，所以 **从 0.1.0 升到 0.2.0 必须先卸载**；之后的调试版都能覆盖安装。
- 构建脚本修好：`tools/setup-host-aarch64.sh` 会下载 cmdline-tools、导入网关 CA；新增 `tools/fwdproxy.py`、`tools/aapt2`。

## 5. 待办（建议按这个顺序做）
1. **真机测试 v0.2.0**（先卸载 0.1.0，签名不同）：设置各页能打开不崩；工作模式三档实际效果；密码箱增删改 + 锁屏验证；`vault_fill` 在一个真实登录页上（看用户名框定位是否准）；Hands 文字模式完整跑一个任务；敏感应用拦截。
2. 备份与恢复还是上游界面（`Routes.BACKUP`，设置 → 系统 → 备份与恢复）。远程备份依赖已 stub 的 rclone，只有本地导出/导入能用。要原生重做时复用 `BackupViewModel`。
3. MCP：上游的 MCP 调用靠沙箱里的 `minis-mcp-cli`，沙箱移除后不可用，所以没有放进新设置。需要先决定 MCP 在 Termux 里怎么跑（stdio 服务器用 Termux 起、HTTP 服务器 App 直连）。
4. 还没移植的上游设置：思考规则（`ThinkingRulesSection`，按服务商）、语音输入/输出分组、用量统计（`UsageStatsScreen`）、系统文件（SOUL/USER/HEARTBEAT 编辑，人设页已覆盖 SOUL）、外观里除主题以外的选项、Shizuku 详细页、关于页的更新检查。定时任务的编辑页仍是上游界面。
5. 已决定不移植（属于被移除的沙箱或 nanoMuse 云）：存储 / rootfs、共享文件夹、挂载外部文件夹、终端、nanoMuse 云账号与数据管理、电脑/编程（reach hub）、连接器、头像工作室、上游反馈渠道。
6. Hands 悬浮层加暂停/继续（现有的 operator 不支持暂停，需要改 `HandsOffloadHandler`）。
7. 附件面板：「当前屏幕」截图、最近照片条；把联网搜索和 Hands 开关接入 Agent。
8. 审批单：支持编辑待执行的动作（现在点「编辑」会取消动作，并把内容放回输入框）。
9. 工作区页：长按菜单、「+」按钮。
10. 移除沙箱残留：APK 里还带着 `assets/alpine-minirootfs.tar`（8 MB）和 `proot`，启动时仍会安装 rootfs 并启动 offload server。拆的时候要先把 Hands 和 offload 对它的依赖解开，**改完必须测**。
11. 点赞/点踩要持久化；任务卡的展开状态要按消息保存。
12. 旧页面和深链接还在用 Material 图标或旧的 ChatScreen，要逐步迁移（上游 `Routes.SETTINGS` 仍注册着，只是新设置里不再有入口）。

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
