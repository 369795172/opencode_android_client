# AGENTS.md - OpenCode Android Client

README 给人。Agent 先读本文件，再读 `docs/PRD.md`、`docs/RFC.md`、`docs/design.md`、`docs/test.md`、`docs/working.md`。

## Project Role

OpenCode Server 的原生 Android 远程控制客户端（包名 `ai.opencode.client`）。它是电脑上 OpenCode 实例的移动端延伸：阅读 Markdown 报告、审查决策、用语音纠偏（Steer）。它不是手机 IDE，不是本地 OpenCode server，也不是 Web UI 的套壳。

Public GitHub fork（`369795172/opencode_android_client`）；上游为 `grapeot/opencode_android_client`。隐私红线生效：已跟踪文件零真实凭证、私人邮箱、内网主机名。

## Structure

- `app/src/main/java/ai/opencode/client/` — 应用代码（`data/` 网络与仓储，`ui/` Compose，`di/` Hilt）
- `app/src/test/` — JVM 单测（每次 commit 必跑）
- `app/src/androidTest/` — component / integration-UI（emulator；勿装物理机）
- `docs/` — 产品与工程文档（PRD / RFC / design / test / working）
- `scripts/` — 设备 bootstrap 等辅助脚本
- `ui_driver/` — LLM-driven UI 测试 CLI
- `README.md` — 人读：快速开始、构建、远程访问

## Git Rules

- 个人 fork 日常集成分支：`path-b-grapeot`；上游默认分支：`master`
- 提交保持小而可逆；commit message 引用 issue 时用 `fix: #<n> — …` / `feat: #<n> — …`
- **每个 meaningful change 之后必须更新 `docs/working.md`**（日期键倒序 + 特性级 bullet）。决策变更同步回写 PRD/RFC/design，不留口径漂移。
- 不提交 `.env`、密钥、私钥、真实密码、build 产物
- 这是 public repo：已跟踪文件禁止私人邮箱、真实 API key、内部路径、1Password/`op://` 引用

## Build Environment

终端默认可能找不到 Java，导致 `./gradlew` 失败。使用 Android Studio 自带的 JDK：

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"

# For integration tests (adb)
export PATH="$PATH:$HOME/Library/Android/sdk/platform-tools"
```

**持久化**：在 `~/.zshrc` 中加入上述 `JAVA_HOME` 和 `PATH` 两行，然后 `source ~/.zshrc`。

## Run / Module not found

若 Run 报 "Module not found"：File → Sync Project with Gradle Files；若仍失败，File → Invalidate Caches / Restart。Run 配置使用 module `OpenCode.app`（对应 settings.gradle.kts 的 rootProject.name + `:app`）。

## Test Commands

Fail-closed 口径与命令见 `docs/test.md`。常用入口：

- Unit tests（每次 commit 必过）: `./gradlew testDebugUnitTest`
- Coverage report: `./gradlew koverHtmlReport` → `app/build/reports/kover/html/index.html`
- Integration tests: `./gradlew connectedDebugAndroidTest`（需要 .env 的 OPENCODE_*；未配置则 skip，不当 fail）

## Device Safety

- Do not run `connectedDebugAndroidTest`, install, or launch debug builds on a physical Android phone unless explicitly asked. Physical devices may contain the user's active app settings and credentials; installing test builds can overwrite them.
- For UI/instrumented tests, use an emulator only. If both emulator and physical devices are connected, target the emulator explicitly with `ANDROID_SERIAL=<emulator-id>` or an equivalent Gradle/adb device selection.

## Glasses bootstrap (ADB)

For IME-less glasses (or any device you name by serial), install the personal debug APK and inject a Direct Host Profile via debug Intent extras:

```bash
cd projects/opencode-android
./scripts/glasses_bootstrap.sh -s <glasses-serial> \
  --profile ./scripts/glasses_profile.example.json \
  --password-env OPENCODE_SERVER_PASSWORD
```

- Requires debug APK (`ai.opencode.client`); refuses to auto-pick a device (must pass `-s` / `ANDROID_SERIAL`).
- Example profile: `scripts/glasses_profile.example.json` (leave `password` empty and pass `--password-env`).
- App side: `MainActivity` extras `test_server_url` / `test_username` / `test_password` / `test_profile_name` → `MainViewModel.configureServer`（syncs Host Profile + EncryptedSharedPreferences）.

## Key Decisions（摘要）

完整「Why X, not Y」见 `docs/RFC.md` Key Decisions。Agent 接手时至少能复述：

1. **原生 Jetpack Compose，不用 WebView 套 OpenCode Web UI**：Steer 需要 SSE、加密存储、NFC、PCM 语音；与 iOS SwiftUI 对等，不共享渲染引擎。
2. **文件预览无语法高亮**：审的是 Markdown 决策与产物，不是代码美学；Files Tab 是兜底入口。
3. **后台不保持 SSE，当前阶段不做 Foreground Service / 推送**：回前台 REST 全量同步 + 重建 SSE；通知是 Future，不进当前成功标准。
4. **SSH 用 mwiede/JSch 做 app 内 local forward**：不用系统 VPN、Termux/OpenSSH、Apache Mina SSHD（除非 JSch 在 key format / Android crypto 上阻塞）。
5. **语音：点击即录 PCM16 + 本地 cache replay**：不等 WebSocket session 建连；失败路径保留用户已看见的 partial。
6. **Markdown Web Preview 只加载 app assets 里的 JS**：DOMPurify allowlist；workspace 内容走 data URI，不走 `file://` 工作区路径。
7. **Deep link 只在当前 Host 验证 `opencode://session/<id>`**：不自动切 Host，不携带凭证，不执行 prompt/permission/tool。

## What NOT to do

- 不要把本 App 做成手机 IDE、本地 OpenCode server、或 Web UI 套壳。
- 不要引入代码语法高亮、本地 LLM / 本地 shell / 工作区写文件能力。
- 不要在后台保持 SSE 长连接；不要为了「连上」而关闭 SSH host key 校验。
- 不要从网络加载 Web Preview 的 JS；不要让 WebView 直接读 workspace 文件系统。
- 不要把 NFC `Intent` 处理放进 Compose recomposition（会制造 session storm）。
- 不要在物理 Android 手机上跑 `connectedDebugAndroidTest` / 安装 debug 包，除非用户点名该设备。
- 不要提交 `.env`、真实密码、私钥、私人邮箱；public fork 的隐私扫描必须零命中。
- 不要把 Files Tab 当主工作流做体验堆砌；主路径是 Chat 里的 tool/patch 卡片。
- 不要让 session deep link 自动切换 Host、携带 server URL/凭证、或执行写操作。
- 不要跳过 `docs/working.md`：meaningful change 未记 changelog / 未蒸馏 lesson，视为未完成。

## Maintenance

- 每个实现里程碑：跑 `./gradlew testDebugUnitTest`、更新 `docs/working.md`、按需回写 PRD/RFC/design。
- 里程碑后问一次：本轮能否蒸馏一条可迁移原则进 `docs/working.md` Lessons。
- 公开发布或 push 前做隐私扫描（命令见 `docs/test.md` Fail-closed），必须零命中。
- 可交付构建按 README 版本号规范 bump `versionName` / `versionCode`。本文件只约束文档与工程纪律，不替代发版 checklist。

## Feishu Inbound（需求流水线运行环境）

本仓的 feishu inbound（Pipeline C–F）不在本仓运行，由 rootgrove 托管：

- 运行环境：rootgrove venv（`/Users/marvi/CursorWorks/rootgrove/venv/bin/python`）；引擎 pin SSOT = rootgrove `tools/feishu_inbound/requirements.txt`
- Instance config：rootgrove `config/feishu_inbound_opencode_android.yaml`（surface 路由见 `feishu_inbound_opencode_android_surfaces.yaml`）
- 调度：launchd `com.personal.feishu-inbound-opencode-android-lead-tick` → `tools/feishu_inbound/run_opencode_android_lead_tick.sh`（wrapper 检测引擎缺失时按 pin 自动安装）
- 引擎仓：`369795172/feishu-inbound-skill`（只装 Release wheel，不跟踪其 main）

本仓的 Gradle/Kotlin 工具链只服务 APK 构建，与 inbound 无关。
