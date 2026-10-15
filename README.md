<div align="center">

<img src="brand/clawdy_icon_512.png" width="128" alt="Clawdy" />

# Clawdy

**一台手机就是全部，在 Android 本机运行的个人 Agent。**

对话、命令、文件、操作其他 App，都在你的手机上完成。<br/>
模型用你自己的 API Key，手机直连服务商，没有中转服务器。

![version](https://img.shields.io/badge/version-0.3.0-E07F5F)
![android](https://img.shields.io/badge/Android-11%2B-4CAF7A)
![license](https://img.shields.io/badge/license-GPL--3.0-7B5CD6)

</div>

---

## 能做什么

| 能力 | 说明 |
|---|---|
| **对话 Agent** | 多步规划、调用工具、汇报结果；支持 DeepSeek、OpenAI、Claude、Gemini、通义、GLM、Kimi 等 |
| **Termux 工作区** | 真正的 Linux：Python、Node、Git、Pandoc、Chromium，处理 DOCX / PPTX / PDF |
| **Hands** | 通过无障碍读屏、点击、滑动、输入，替你操作微信等 App，每一步都看得见、随时能停 |
| **连接器（MCP）** | 内置飞书、GitHub、Figma、Google、B 站、网易云、小红书、高德等预设 |
| **人设与记忆** | SOUL.md 定义性格，MEMORY.md 每轮对话后自动更新，可选对话风格 |
| **密码箱** | Keystore AES-256 加密，代填需本机验证，内容永不发给模型 |

## 快速开始

**1. 安装 App**：下载 [`release/Clawdy-0.3.0-debug.apk`](release/) 安装。从 0.2.0 起可直接覆盖升级。

**2. 准备 Termux**：从 [F-Droid](https://f-droid.org/packages/com.termux/) 或 [GitHub](https://github.com/termux/termux-app/releases) 安装（不要用 Play 版），然后运行：

```bash
curl -fsSL https://raw.githubusercontent.com/QiGai77/clawdy/main/scripts/clawdy-setup.sh | bash
```

> 只体检：末尾加 `-s -- --diagnose`　·　不装 Chromium：加 `-s -- --minimal`

**3. 授权**：在 Clawdy「工具连接」页依次打开：

- Termux → 授权 → 点「测试连接」看到 ok
- Hands → 开启无障碍服务与悬浮窗
- Shizuku（可选）→ 授权

**4. 添加模型**：设置 → 配置 → 添加服务商，填 API Key。默认 DeepSeek V4.1 Flash。

<details>
<summary><b>无障碍 / 悬浮窗打不开？</b></summary>

Android 13+ 对浏览器安装的 APK 会限制敏感权限：

1. 设置 → 应用 → Clawdy → 右上角 `⋮` → **允许受限制的设置**
2. 回到无障碍 / 悬浮窗设置重新开启
3. 国产系统还需：电池优化设为「不限制」，允许自启动

菜单里没有这个选项时：`adb shell appops set io.github.qigai.clawdy ACCESS_RESTRICTED_SETTINGS allow`
</details>

<details>
<summary><b>Termux 连不上？</b></summary>

- 先装了 Clawdy 再装 Termux：权限不存在，重装一次 Clawdy
- Play 商店版 Termux：不支持外部调用，换 F-Droid / GitHub 版
- 测试连接超时：重跑一键脚本，并把 Termux 的电池优化设为「不限制」
</details>

## 隐私

- 默认只把屏幕**文字**发给模型，截图上传默认关闭
- 不在密码框上操作；发消息、支付、删除等敏感操作**每次**单独确认
- 文件、会话、日志只留在本机

## 构建

```bash
./gradlew :app:assembleDebug -x lint     # JDK 17 · compileSdk 36
```

完整设计、架构与决策记录见 [`docs/DESIGN.md`](docs/DESIGN.md)，开发交接见 [`HANDOFF.md`](HANDOFF.md)。

## 致谢与许可

基于 [nanoMuse](https://github.com/nano-muse/nanoMuse) 的 Android 工程，遵循 **GPL-3.0-or-later**。<br/>
字体 Noto Serif SC、Source Serif 4 遵循 SIL OFL 1.1。角色形象来自画师作品，已获授权，使用请注明画师。
