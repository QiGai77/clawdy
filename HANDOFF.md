# HANDOFF：给下一个 AI 的交接说明

> 2026-10-15 写于 Hark。请先读完本文件，再读 `docs/DESIGN.md`（完整设计文档，共 20 节）和 `design/shell-progress.md`。

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
| `app/src/main/java/com/openminis/app/ui/clawdy/` | 新前端壳：`ClawdyShell`、`ClawdyScreens`、`ClawdySheets`、`ClawdyTaskCard`、`ClawdyTurns`、`ClawdyComponents`、`ClawdyIcons`；设置：`ClawdySettings`（首页、模型配置、密钥、分组、`Segmented`/`SegmentedRow`/`Hint`/`Note`）、`ClawdySecurityScreens`（安全、密码箱、代填确认、敏感应用）、`ClawdyMoreSettings`（对话、记忆文件、技能、环境变量、网络、后台、Hands、定时任务）、`ClawdyPersonaScreens`（人设、外观、高级设置）、`ClawdyConnectors`（连接器列表与编辑） |
| `app/src/main/java/com/openminis/app/clawdy/` | `ClawdyFlags`（`SANDBOX_IN_AGENT=false`）、`ClawdyPresets`（模型目录）、`ToolStatus`、`HandsTool`（`phone_hands`）、`ClawdySecurity`（安全设置的读取与接线）、`vault/`（`Vault` 加密存储、`VaultTools` = `vault_list`/`vault_fill` + 审批队列） |
| `app/src/main/java/com/openminis/app/tools/TermuxTool.kt` | `termux_run`，经过 `RiskGate.checkShell` 审批 |
| `app/src/main/java/com/openminis/app/ui/theme/ClawdyTokens.kt` | 设计令牌 |
| `app/src/main/java/com/openminis/app/ui/theme/ClawdyLook.kt` | 外观设置（字体、字号、强调色、引用/代码块、密度、说明文字），`LocalClawdyLook` |
| `app/src/main/java/com/openminis/app/clawdy/{memory,mcp}/`、`ClawdyPersona.kt`、`TermuxDiag.kt` | 记忆、MCP 连接器、人设/对话方式、Termux 诊断 |
| `scripts/clawdy-setup.sh` | Termux 一键安装 + 体检（`--diagnose` 只体检，`--minimal` 不装 chromium/playwright）；App 里复制的就是 `curl -fsSL https://raw.githubusercontent.com/QiGai77/clawdy/main/scripts/clawdy-setup.sh \| bash`。`termux-bootstrap.sh` 只是转调它的壳 |
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

已构建的调试版 APK：`release/Clawdy-0.3.0-debug.apk`（v0.3.0，可直接覆盖安装 0.2.0）；`release/Clawdy-0.2.0-debug.apk`（v0.2.0，设置重做 + 密码箱 + 安全接线）；旧的 `release/Clawdy-0.1.0-debug.apk` 用户已在真机上装过，能运行。GitHub Releases 对应 `v0.3.0-debug` / `v0.2.0-debug` / `v0.1.0-debug`。

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

### v0.3.0（2026-10-15，按用户 0.2.0 真机测试反馈）
- **Hands 输入修好**（`io/github/nanomuse/hands/HandsTyping.kt`）：0.2.0 在微信搜索框里每次 `type into 搜索框` 都失败。原因是 0.2.0 只在各窗口根节点上 `findFocus(FOCUS_INPUT)` 并直接 `ACTION_SET_TEXT`：微信的输入焦点经常落在包着 EditText 的容器上（不可编辑，SET_TEXT 被拒），有些 ROM 上按窗口找焦点直接返回空。现在：先用服务级 `findFocus`，再在焦点节点的子孙/祖先里找可编辑的，再找上一次点击位置下的输入框、唯一的输入框；写入依次尝试 `ACTION_SET_TEXT` → 剪贴板 + `ACTION_PASTE` → 剪贴板 + Shizuku `input keyevent 279`（`input text` 打不了中文），每一步都重新读节点文字确认；失败时把每种方法的失败原因交给屏幕模型。
- **身份**：系统提示词里上游残留的 “nanoMuse” 全部替换成 Clawdy，并加了一句身份声明（`ClawdyPersona.IDENTITY_FENCE`），不再说“nanoMuse 里的 AI 助手”。
- **人设页**（设置首页头像行，`ClawdyPersonaScreens.kt` `PersonaScreen`）：名字 + 性格（SOUL.md）、对话方式（默认/简洁/温柔陪伴/专业严谨/活泼/自定义，注入系统提示词）、记忆（MEMORY.md 查看编辑、使用记忆、自动记忆、清空）。设置和抽屉里的「记忆」入口都改到这里。
- **记忆重做**（`clawdy/memory/ClawdyMemory.kt`）：只有一个 `MEMORY.md`（`minis-global/memory/`），启动时把 GLOBAL.md、USER.md、每日记录合并进去，原文件移到 `_archive-0.2/`。每轮回答结束 6 秒后（防抖）用当前对话的模型把新信息合并进 MEMORY.md（基本信息/习惯与偏好/爱好/人际关系/正在做的事/随手记）。`memory_write` 现在写进 MEMORY.md 的「随手记」。密钥类内容三道防线：提示词禁止、对话里代填过的密码箱值先遮盖、写入前逐行过滤（Key、Token、长数字、“密码：xx”）。
- **外观**（`ui/theme/ClawdyLook.kt` + 外观页）：主题、字体（衬线/无衬线/跟随系统）、字号（小/标准/大/特大，作用于全部 Typography 和 Clawdy 组件）、强调色（7 个预设，自动派生任务卡/气泡浅色）、引用块（竖条+浅底/只有竖条/描边卡片）、代码块（深色/浅色）、对话密度（行距 + 消息间距）、显示说明文字（默认关）。
- **说明文字默认隐藏**：`ClawdyRow` 新增 `hint`（说明，只在「显示说明文字」开时出现）与 `subtitle`（数据，始终显示）分开；`Hint()` 跟随开关，`Note()` 用于空状态/结果等必须显示的文字。
- **布局**：`Segmented` 改为占满整行、等分，新增 `SegmentedRow`（标题在上、选项在下）；密码箱「离开后多久上锁」、分组「使用方式 / 何时换下一个」、外观各项都改用它。`ClawdyRow` 的 trailing 最多 180dp，文字列不会再被挤成一字一行。步骤详情的输出改为自动换行（0.2.0 横向滚动、左侧被截）。Termux 卡片描述不再截断。
- **高级设置**（`AdvancedScreen`）：自动压缩、上下文与记忆细节、模型分组与故障切换、Hands 屏幕模型、环境变量、网络代理、运行日志、系统文件、上游设置页。设置首页只剩：人设 · 模型 · 安全 · 能力（技能/连接器/工具连接/定时任务）· 外观 · 系统（通知与后台/备份与恢复/高级设置）· 关于。
- **Termux 诊断**（`clawdy/TermuxDiag.kt`）：区分「Google Play 版（没有 RUN_COMMAND）」「权限未定义（旧版）」「Termux 比 Clawdy 后装（需覆盖安装一次 Clawdy；仍不行再卸载重装）」「未授权（弹窗或 应用详情 → 其他权限）」，显示版本和来源（F-Droid/GitHub/Play，按签名和安装来源判断）；「测试连接」真正发一次 RUN_COMMAND `echo ok`，超时会提示 allow-external-apps 或电池优化。卡片里只保留一行可复制的一键脚本命令。
- **连接器 = MCP**（`clawdy/mcp/`，设置 → 能力 → 连接器）：原生 Kotlin MCP 客户端（Streamable HTTP，自动回退旧版 HTTP+SSE；`Mcp-Session-Id`；分页 tools/list），不再依赖沙箱里的 `minis-mcp-cli`。远程服务 App 直连；OAuth 走 RFC 9728/8414 发现 + RFC 7591 动态注册 + 上游 PKCE（回调 127.0.0.1:54546）+ 自动刷新。stdio 服务在 Termux 里用 `npx -y supergateway --stdio … --outputTransport streamableHttp --port <由名字算出的端口> --host 127.0.0.1` 启动，日志 `~/clawdy/mcp/<名字>.log`，然后按 HTTP 连接。Agent 工具：`mcp_tools`、`mcp_call`（发送/删除/付款类工具名先弹审批卡）。密钥类字段存进加密环境变量，配置里写 `$$变量名`。13 个预设（全部未在真机测试）：高德、百度地图、GitHub（PAT）、飞书（官方 lark-mcp）、12306、哔哩哔哩、中国天气、网易云音乐、小红书（需电脑跑 xiaohongshu-mcp，局域网连接）、Google 全家桶（workspace-mcp）、Canva（OAuth）、Canvas LMS、Figma（官方只允许白名单客户端，标为目前不可用）。

## 5. 待办（建议按这个顺序做）
1. **真机测试 v0.3.0**（可直接覆盖安装 0.2.0）：
   - Hands：微信搜索框输入中文（看工具输出里写的是哪种方法成功：SET_TEXT / paste / Shizuku paste）；再试 QQ、淘宝搜索框。
   - Termux：工具连接页的诊断结论是否和实际一致；按提示覆盖安装后「授权」能否弹窗；「测试连接」；在 Termux 里跑一键脚本，看体检表。
   - 人设：改名字/性格/对话方式后新对话是否生效；聊几轮后 MEMORY.md 是否自动出现关于用户的条目（约 6 秒后台更新）；旧的 GLOBAL.md/每日记录是否已合并。
   - 外观：字体/字号/强调色/引用块/代码块/密度/说明文字开关是否全局生效（含上游页面的 Typography）。
   - 连接器：先试不需要 Key 的 12306（本地，第一次 npx 下载较慢）和高德（远程，需 Key）；再试 GitHub PAT、飞书。
2. MCP：supergateway 的 `--host` 参数在较老版本里可能不存在（新版有）；如果启动日志报未知参数，去掉 `--host`（会监听所有网卡，需要提醒用户）。stdio 服务器的会话级开关（`setSessionOverride`）还没有 UI。
3. 记忆：自动记忆目前每轮都会调用一次模型（只发新增的对话片段）；如果费用敏感，可以改成「对话结束 / 切换对话时」再更新。
4. 备份与恢复还是上游界面（`Routes.BACKUP`）。远程备份依赖已 stub 的 rclone，只有本地导出/导入能用。要原生重做时复用 `BackupViewModel`。
5. 还没移植的上游设置：思考规则（`ThinkingRulesSection`，按服务商）、语音输入/输出分组、用量统计（`UsageStatsScreen`）、Shizuku 详细页、关于页的更新检查。定时任务的编辑页仍是上游界面。高级设置里有「上游设置页」入口仅供排查。
6. 已决定不移植（属于被移除的沙箱或 nanoMuse 云）：存储 / rootfs、共享文件夹、挂载外部文件夹、终端、nanoMuse 云账号与数据管理、电脑/编程（reach hub）、头像工作室、上游反馈渠道。（「连接器」已由 0.3.0 的 MCP 页替代。）
7. Hands 悬浮层加暂停/继续（现有的 operator 不支持暂停，需要改 `HandsOffloadHandler`）。
8. 附件面板：「当前屏幕」截图、最近照片条；把联网搜索和 Hands 开关接入 Agent。
9. 审批单：支持编辑待执行的动作（现在点「编辑」会取消动作，并把内容放回输入框）。
10. 工作区页：长按菜单、「+」按钮。
11. 移除沙箱残留：APK 里还带着 `assets/alpine-minirootfs.tar`（8 MB）和 `proot`，启动时仍会安装 rootfs 并启动 offload server。拆的时候要先把 Hands 和 offload 对它的依赖解开，**改完必须测**。
12. 点赞/点踩要持久化；任务卡的展开状态要按消息保存。
13. 旧页面和深链接还在用 Material 图标或旧的 ChatScreen，要逐步迁移（上游 `Routes.SETTINGS` 仍注册着）。

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
