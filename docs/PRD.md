# PRD: OpenCode Android Client

> Product Requirements Document · v1.0 · Mar 2026

## 元数据

| 字段 | 值 |
|------|------|
| **产品名称** | OpenCode Android Client |
| **状态** | v1.0 (Feature Complete) |
| **创建日期** | 2026-02 |
| **最后更新** | 2026-03-12 |
| **参考** | iOS Client PRD |

---

## 摘要

OpenCode Android Client 是 OpenCode AI 编程助手的原生 Android 客户端，让开发者可以在手机/平板上远程监控 AI 工作进度、发送指令、审查代码变更。

---

## 背景

### 问题

开发者使用 OpenCode 时，常需在电脑前等待 AI 完成耗时任务。离开工位后无法及时了解进度、无法快速纠偏。现有 Web 客户端移动端体验不佳，TUI 无法在手机上使用。

### 目标

提供原生 Android 客户端，让用户可在手机/平板上：
- 监控 AI 工作进度
- 发送消息、切换模型、选择 Agent
- 查看 Markdown diff、代码变更
- 必要时中止或排队新指令

### 目标用户

- 使用 OpenCode 进行日常开发的程序员
- 需要远程监控长时间运行任务的场景
- Android 手机/平板用户

---

## 功能需求

### 3.1 核心功能

#### Chat Tab

- **Session 管理**：列出所有 Session，创建/切换/重命名 ✅
- **消息发送**：支持多行输入，busy 时服务端排队 ✅
- **消息渲染** ✅：
  - 用户消息：灰色背景 ✅
  - AI 消息：Markdown 渲染（multiplatform-markdown-renderer-m3） ✅
  - 思考过程（reasoning）：折叠展示 ✅
  - 工具调用（tool）：卡片形式，running 时展开、completed 时收起 ✅
  - Patch：显示文件路径，点击可跳转预览 ✅
  - Todo：在 tool 卡片内展示任务列表 ✅
- **流式显示**：SSE 事件触发消息刷新（不做打字机效果） ✅
- **自动跟随**：当用户停留在底部时，新的消息 / tool call / 流式更新自动跟随；离开底部时保持当前位置 ✅
- **模型选择**：从 `/provider` API 动态获取，TopBar 下拉菜单 ✅
- **Agent 选择**：从 `/agent` API 动态获取 ✅
- **Context Usage**：环形进度显示上下文占用（绿/橙/红三色） ✅
- **权限审批**：手动批准/拒绝 permission 请求 ✅
- **Abort**：中止当前任务 ✅
- **语音输入**：通过 AI Builder WebSocket API 录音转写，partial transcript 实时回填输入框，可在录音中继续发送已有文本 ✅

#### Files Tab

- **文件树**：递归展示工作目录，支持 git 状态颜色标记 ✅
- **文件预览**：文本文件等宽字体显示，Markdown 文件渲染 ✅
- **图片预览**：图片默认 fit-to-screen，支持双击放大、拖动平移、系统分享 ✅
- **Session 变更**：🔲 暂不实现

#### Settings Tab

- **服务器连接**：配置 URL（HTTP/HTTPS）、Basic Auth ✅
- **连接测试**：验证服务器可达性 ✅
- **SSH Tunnel**：🔲 暂不实现
- **主题**：Light / Dark / System ✅
- **语音识别配置**：AI Builder Base URL、Token、Prompt、Terminology、连接测试 ✅

### 3.2 平板适配

- **手机**：底部 Tab 导航（Chat / Files / Settings） ✅
- **平板**：三栏布局（WindowSizeClass.Expanded） ✅
  - 左：Workspace（Files / Settings 切换）
  - 中：文件预览
  - 右：Chat

---

## 技术约束

| 约束 | 说明 |
|------|------|
| 最低版本 | Android 8.0 (API 26) |
| 网络协议 | HTTP REST + SSE（Server-Sent Events） |
| 安全 | HTTPS 默认；HTTP 仅允许 localhost 与 Tailscale (*.ts.net)，局域网 IP 需 HTTPS |
| 无本地 AI | 不引入本地推理、文件系统操作、shell 能力 |

---

## 非功能需求

| 指标 | 要求 |
|------|------|
| 首屏加载 | < 3 秒（弱网） |
| 消息延迟 | SSE 事件 < 500ms 渲染 |
| 电池消耗 | 后台不保持连接，前台正常耗电 |
| 离线支持 | 断线时显示缓存内容，不崩溃 |

---

## 与 iOS 版本的差异

| 功能 | iOS | Android | 说明 |
|------|-----|---------|------|
| HTTP 连接 | 需配置 ATS | 需配置 network_security_config | 两者都允许 HTTP |
| SSH Tunnel | Citadel (SwiftNIO) | Apache Mina SSHD / JSch | 库不同，功能一致 |
| UI 框架 | SwiftUI | Jetpack Compose | 声明式，概念相似 |
| 状态管理 | @Observable | ViewModel + StateFlow | 架构相似 |
| 安全存储 | Keychain | Keystore + EncryptedSharedPreferences | 功能等价 |
| 语音输入 | AI Builder WebSocket 实时转写 | AI Builder WebSocket 实时转写 | 功能已对齐 |
| 图片预览 | fit / zoom / pan / share | fit / zoom / pan / Android share sheet | 功能已对齐 |

---

## 实现规划

| Phase | 范围 | 状态 |
|-------|------|------|
| 1 | 项目搭建、网络层、SSE、Session、消息发送 | ✅ 完成 (2026-02-23) |
| 2 | Part 渲染、权限审批、构建修复、集成测试 | ✅ 完成 (2026-02-24) |
| 3 | Bug 修复、Markdown 渲染、模型选择、Context Usage、主题、平板布局 | ✅ 完成 (2026-03-02) |
| 4 | SSH Tunnel、Session 变更文件列表 | 🔲 未来可选 |

---

## 成功指标

1. 能够稳定连接 OpenCode Server（局域网/公网）
2. 消息发送、接收、流式显示正常
3. 权限审批流程完整
4. 文件预览、Markdown / 图片渲染可用
5. 平板三栏布局体验流畅
6. Chat 在监控模式下自动跟随，在历史查看模式下不强制跳到底部

---

## 参考

- [OpenCode Web API](../../../adhoc_jobs/opencode_ios_client/docs/OpenCode_Web_API.md)
- [iOS Client RFC](../../../adhoc_jobs/opencode_ios_client/docs/OpenCode_iOS_Client_RFC.md)
