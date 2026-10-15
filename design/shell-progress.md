# Clawdy Compose shell — progress log

## 2026-10-15

### Build host
- aapt2 runs under qemu; the sandbox had lost `qemu-user` and the amd64 cross runtime. Reinstalled `qemu-user libc6-amd64-cross libgcc-s1-amd64-cross libstdc++6-amd64-cross`; `/workspace/tools/env.sh` now reinstalls them when missing.
- Swapfile: not possible (overlay root fs, `swapon` → EINVAL).
- `gradle.properties`: `-Xmx3584m -XX:MaxMetaspaceSize=768m -XX:+UseParallelGC -XX:ReservedCodeCacheSize=256m`, `workers.max=1`, `parallel=false`, `daemon=false`, `ksp.incremental=false`, Kotlin in-process, `suppressUnsupportedCompileSdk=36`. No OOM since. Full assembleDebug ≈ 9–10 min.
- Build outputs (`app/build`, `.gradle`) had been committed; removed from the index, `.gitignore` added.

### Done (commits on src/clawdy)
1. Design system — `ui/theme/ClawdyTokens.kt` (light/dark palette, radii, motion), serif everywhere via `MinisTheme` (Noto Serif SC GB2312 subset Regular/SemiBold + Source Serif 4 Regular/SemiBold, chained with `Typeface.CustomFallbackBuilder` on API 29+), OFL texts in `assets/licenses/`.
   `ui/clawdy/ClawdyIcons.kt` (58 line icons, 1.75 stroke, no emoji), `ClawdyComponents.kt` (avatar w/ glow + breathing, switch with 8dp track / 6dp thumb / 160ms, spinner, buttons, rows, cards, pills, progress).
2. Agent — `shell_execute` (PRoot) removed from the tool list (`ClawdyFlags.SANDBOX_IN_AGENT=false`), new `phone_hands` tool calling `HandsOffloadHandler` directly (Hands used to be reachable only through the sandbox shell), Termux-first system prompt, `termux_run` now goes through `RiskGate.checkShell` (so risky Termux commands raise the 06 sheet).
3. Shell (start destination, `Routes.SESSION_LIST` → `ClawdyShell`): 01 home, 02/03 task card, 04 step sheet, 05 answer + file card + action row + failed state, 06 approval sheet (RiskGate), 08 drawer, 09 model sheet, 10 add provider (presets 2026-10-15, test connection, /models), 11 tools (real checks + a11y guide + permission rows), 12 onboarding, 13 attach sheet, 14 workspace (Termux `find` listing), 15 settings, licenses page. Hands overlay re-tinted coral.

### Placeholders / not done
See the report returned to the dispatching agent (also summarised in the final commit messages).

## 2026-10-15 (v0.2.0) — settings rework from the user's device annotations

### Build host (from scratch, aarch64, 7 GB)
- `tools/setup-host-aarch64.sh` now also fetches cmdline-tools 11076708, imports the Hark gateway CA
  (`/opt/certs/gateway-ca.crt`) into the JDK trust store (Gradle failed with PKIX otherwise), and copies
  `tools/fwdproxy.py` (local unauthenticated forwarder → the authenticated egress proxy) and the
  `tools/aapt2` qemu wrapper into `/workspace/tools`.
- Clean `assembleDebug` 12m42s; `compileDebugKotlin` alone ≈5 min.

### Settings (ui/clawdy)
- Top level: profile row (no subtitle) · 模型/配置 · 安全/安全 · 对话 (对话与上下文, 记忆, 技能, 定时任务) ·
  工具 (工具连接, Hands 设置, 环境变量, 运行日志) · 系统 (网络代理, 通知与后台, 备份与恢复*) · 外观 (主题) ·
  关于 (版本, 开源许可, 反馈问题). The legacy-settings row is gone. *备份与恢复 still opens the upstream screen.
- 模型配置: add provider, default model (single-member group "默认模型" set as default), keys → key detail
  (label, base URL, key replace, enable, Responses API, refresh /models, add model id, delete) → model detail
  (ModelOverrides: name, context, max output, reasoning, vision, hidden, max thinking level), groups → group
  detail (members + order, fallback/loadBalance, fallback trigger, context cap, default, delete).
- 安全: work mode cards; 密码箱; 截图上传 / 不在密码框上操作 / 读取剪贴板前确认 / 敏感应用; remembered grants
  with per-item revoke + revoke all.

### Wiring
- `RiskGate.mode: GateMode` (CAUTIOUS / STANDARD / RELAXED), set by `ClawdySecurity.apply` at startup and on change.
- Hands text mode (`ScreenText`) when screenshots are off; `Hands.textModel` lets any chat model drive it;
  `readiness` no longer requires Android 11 / a vision model in text mode.
- Hands: sensitive-app stop per step and on open_app; password-field guard follows the setting.
- `termux_run`: clipboard-read approval; env vars exported; output redacted by `EnvVarRedactor`.
- Approval sheet: real data preview (`dataPreview`), mode-aware remember checkbox, class-aware button label.
- Vault: `clawdy/vault/Vault.kt`, `VaultTools.kt`; sheet `VaultFillSheet` mounted once in `AppNavigation`.

## 2026-10-15 (v0.3.0) — device feedback on 0.2.0

### Fixes
- Hands typing (`hands/HandsTyping.kt`): service-level input focus → editable child/ancestor of a wrapper →
  editable under the last tap / only editable; SET_TEXT → clipboard+PASTE → clipboard+Shizuku keyevent 279,
  each verified by re-reading. Root cause of 「输入文本失败」 in WeChat: input focus sat on a non-editable
  container (or per-window findFocus returned null) and SET_TEXT was the only method.
- Identity: `ClawdyPersona.finalizePrompt` renames upstream "nanoMuse/OpenMinis" mentions in the assembled
  system prompt; `IDENTITY_FENCE` added to SoulStore's identity section. Hands default agentName "Clawdy".
- Layout: `Segmented` fills its line; `SegmentedRow` puts it under the title (vault lock, group strategy,
  appearance). `ClawdyRow(hint=…)` + `below=…`, trailing capped at 180dp. Step detail output wraps.

### New
- 外观 (`ClawdyLook` / `LocalClawdyLook`): font (serif/sans/system), scale 0.9–1.25 on the whole Typography and
  `serif()`, 7 accent presets (`ClawdyLookStore.palette` derives task card / bubble tints), quote style ×3,
  code block dark/light, density (line height × turn spacing), 显示说明文字 (default off; `Hint`/`hint` follow it).
- Settings top level: 人设 avatar row · 模型 · 安全 · 能力 (技能, 连接器, 工具连接, 定时任务) · 外观 ·
  系统 (通知与后台, 备份与恢复, 高级设置) · 关于. `AdvancedScreen` holds the expert items.
- 人设 (`PersonaScreen`): SOUL name + body, 对话方式 (5 presets + custom → `ClawdyPersona.stylePrompt`), MEMORY.md
  editor, 使用记忆 / 自动记忆 switches, 清空. Drawer 记忆 → 人设与记忆.
- Memory (`clawdy/memory/ClawdyMemory.kt`): single MEMORY.md, one-time migration, debounced post-turn update from
  `nmAfterTurn()` with the session's provider, `scrub()` secret filter; `MemoryRepository` global file = MEMORY.md.
- Termux (`clawdy/TermuxDiag.kt`): verdicts NOT_INSTALLED / PLAY_BUILD / NOT_DEFINED / INSTALLED_AFTER /
  NOT_GRANTED / READY, live `echo ok` test, one-liner `scripts/clawdy-setup.sh` (also in assets).
- Connectors (`clawdy/mcp/`): `McpClient` (Streamable HTTP + legacy SSE), `McpOAuth` (RFC 9728/8414/7591 +
  upstream PKCE + refresh), `McpManager` (stdio via Termux + supergateway on 127.0.0.1:38200–38899, tools
  `mcp_tools` / `mcp_call` with RiskGate on send/delete/pay-looking tools), `McpPresets` (13 presets),
  UI `ClawdyConnectors.kt`.
