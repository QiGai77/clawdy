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
