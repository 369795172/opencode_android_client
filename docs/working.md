# OpenCode Android 客户端工作日志

## 2026-02-23

### 项目初始化
- 使用 Android Studio 创建项目结构
- 初始化 git 仓库
- 创建 docs/PRD.md 和 docs/RFC.md

### 依赖
- 在 libs.versions.toml 中添加 OkHttp、Retrofit、Kotlinx Serialization、Hilt、Navigation、Security、Lifecycle 等依赖
- 更新 app/build.gradle.kts 引入全部所需依赖

### 数据模型
- 创建 Session、SessionStatus 模型 (Session.kt)
- 创建 Message、MessageWithParts、Part、PartState 模型及自定义序列化器 (Message.kt)
- 创建带可见性逻辑的 AgentInfo 模型 (AgentInfo.kt)
- 创建 TodoItem 模型 (TodoItem.kt)
- 创建 FileNode、FileContent、FileStatusEntry、FileDiff 模型 (File.kt)
- 创建 HealthResponse、ProvidersResponse、ConfigProvider、ProviderModel 模型 (Config.kt)
- 创建 SSEEvent、SSEPayload 模型 (SSE.kt)
- 创建 PermissionRequest、PermissionResponse 模型 (Permission.kt)

### 网络层
- 实现 OpenCodeApi 接口及全部 REST 端点 (OpenCodeApi.kt)
- 实现基于 OkHttp SSE 的 SSEClient (SSEClient.kt)
- 实现带 Retrofit 与认证的 OpenCodeRepository (OpenCodeRepository.kt)

### DI 与应用配置
- 创建 Hilt 依赖注入的 AppModule (AppModule.kt)
- 创建 OpenCodeApp Application 类 (OpenCodeApp.kt)

### UI 主题
- 创建 Theme.kt，支持浅色/深色主题
- 更新 Color.kt 的消息与文件状态颜色
- 更新 Type.kt 的 Typography 配置

### 工具类
- 创建 SettingsManager，使用 EncryptedSharedPreferences 安全存储 (SettingsManager.kt)
- 创建 ThemeMode 枚举用于主题选择

### 状态管理
- 创建 AppState 数据类承载全部 UI 状态
- 创建 MainViewModel，使用 StateFlow 与 SSE 处理 (MainViewModel.kt)

### UI 实现
- 创建 ChatScreen：消息列表、输入栏、权限卡片 (ChatScreen.kt)
- 创建 FilesScreen：文件树与内容查看 (FilesScreen.kt)
- 创建 SettingsScreen：连接与外观设置 (SettingsScreen.kt)
- 更新 MainActivity：底部导航与页面路由

### 配置
- 添加 network_security_config.xml 支持 HTTP 明文流量
- 更新 AndroidManifest.xml：INTERNET 权限与网络安全配置

### 测试
- 创建 ModelTests：序列化与模型逻辑测试 (ModelTests.kt)
- 创建 AppStateTest：状态管理测试 (AppStateTest.kt)

---

## 2026-02-24

### 构建修复
- 迁移 kapt → KSP，适配 AGP 9 内置 Kotlin 兼容
- 升级 KSP 至 2.3.6 修复 kotlin.sourceSets DSL
- 修复 MainActivity 图标 (FolderOutlined/SettingsOutlined → Folder/Settings)
- 修复 Message.kt 的 SerialDescriptor 与 booleanOrNull
- 修复 Permission.kt 的 SerialName 导入
- 修复 Session.kt 的 isRetry getter 语法
- 修复 OpenCodeRepository 的 Retrofit converter 导入
- 添加 material-icons-extended 依赖
- 重命名 Theme.kt 中的 OpenCodeTheme

### AGENTS.md
- 创建 AGENTS.md，包含 Android Studio JDK 构建说明

### 应用图标 / Logo
- 从 opencode_ios_client 复制 logo (AppIcon.png)
- scripts/resize_icon.py：生成 Android mipmap 与自适应图标前景
- 用 OpenCode logo 替换默认 Android 图标

### Settings 页面修复
- 从 SettingsManager 加载已保存的 URL/用户名/密码
- Test Connection：实际调用 testConnection() 并显示结果（之前会卡住）
- Save 按钮将设置持久化到 EncryptedSharedPreferences

### 测试覆盖率与集成测试
- 添加 Kover 做单元测试覆盖率 (`./gradlew koverHtmlReport`)
- 添加 OpenCodeRepositoryTest，使用 MockWebServer (checkHealth、getSessions、getAgents)
- 添加 .env 用于集成测试凭证（复制 .env.example 为 .env 并填写）
- Gradle 动态加载凭证：构建时读取 .env，传入 instrumentation args
- 添加 OpenCodeIntegrationTest (checkHealth、getSessions、getAgents)
- 运行集成测试：`./gradlew connectedDebugAndroidTest`（需模拟器/真机 + .env）

### Android Studio 运行配置
- 添加 .idea/runConfigurations/app.xml 作为 Android App 运行配置（解决 Run 按钮灰显、模块显示 \<no module\> 的问题）

---

## 2026-03-02

### 代码审查发现的 Bug

1. **OpenCodeRepository lazy re-init**: `okHttpClient` 和 `retrofit` 用 `by lazy` 初始化，但 `configure()` 只修改字段值，不会重建实例。用户在 Settings 修改 URL 后，实际请求仍然发到旧地址。
2. **network_security_config.xml**: `base-config cleartextTrafficPermitted="true"` 允许所有域名 HTTP 明文流量，domain-config 形同虚设。应该限制为仅私有 IP + Tailscale。
3. **主题切换未生效**: SettingsScreen 的 `selectedTheme` 是局部 remember 状态，未写入 SettingsManager.themeMode，也未传给 OpenCodeTheme。
4. **SSE 无重连**: 连接断开后不重试。
5. **模型选择无 UI**: selectedModelIndex 和 providers 数据已存储，但 ChatScreen 没有模型选择下拉框。

### Sprint 计划 (2026-03-02)

**目标功能**:
- Markdown 渲染 (Chat 消息 + 文件预览)
- 主题切换修复 (persist + apply)
- 模型选择 UI
- Context Usage 环形进度
- 平板三栏布局
- Tailscale *.ts.net HTTP exception
- Bug fixes (Repository re-init, network_security_config, SSE reconnect)
- 测试覆盖

**不做的功能**:
- 打字机效果 / delta 增量渲染
- 语音输入
- SSH Tunnel
- Session 变更文件列表

### Sprint 完成记录 (2026-03-02)

#### Bug 修复
1. **Repository lazy re-init** — `okHttpClient`/`retrofit` 从 `by lazy` 改为 mutable + `rebuildClients()`，`configure()` 后重建实例
2. **network_security_config** — 保留 `base-config cleartextTrafficPermitted="true"`（因 Android domain-config 不支持 IP 子网匹配），添加 Tailscale `ts.net` exception
3. **SSE 无重连** — 添加 `retryWhen` 指数退避重连（1s 初始，30s 上限，2x 倍率）
4. **主题切换未生效** — SettingsScreen → SettingsManager.themeMode → MainViewModel.themeMode → MainActivity.darkTheme → OpenCodeTheme 全链路打通

#### 新功能
1. **Markdown 渲染** — AI 消息使用 `com.mikepenz.markdown.m3.Markdown` composable；文件预览 `.md` 文件自动渲染
2. **模型选择 UI** — TopBar 添加 `Icons.Default.Tune` 下拉菜单，从 providers API 获取可用模型
3. **Context Usage 环形进度** — `ContextUsageRing` composable，绿 <70% / 橙 70-90% / 红 >90%
4. **平板三栏布局** — `calculateWindowSizeClass()` 检测 `WindowWidthSizeClass.Expanded`，三栏 Row 布局（左: Files/Settings，中: 文件预览，右: Chat）

#### AppState 扩展
- `ModelOption(displayName, providerId, modelId)` 内部类
- `ContextUsage(percentage, totalTokens, contextLimit)` 内部类
- `availableModels` 计算属性：从 providers 提取模型列表
- `contextUsage` 计算属性：从最后一条 assistant 消息的 tokens 与模型 context limit 计算

#### 测试覆盖
- `AppStateTest` 新增 14 个测试：availableModels（空/单/多 provider/null name）、contextUsage（空消息/无 tokens/无 model/provider 不匹配/limit 为 0/正常计算/clamp/阈值/最新消息优先）
- 全部 unit test 通过（`./gradlew testDebugUnitTest` BUILD SUCCESSFUL）

#### 文档更新
- PRD.md 标记所有已完成功能 ✅，更新实现规划表
- RFC.md 状态从 Draft → Accepted (Implemented)

---

## 2026-03-03

### 构建环境
- 重新生成 Gradle wrapper（gradle-wrapper.jar 缺失），Gradle 9.3.1
- 新增 gradle.properties：JVM 内存 `-Xmx16g`（适配 128GB+ 机器，避免 GC thrashing）
- .gitignore 添加 `!gradle.properties` 以提交构建配置

### 依赖升级
- AGP 9.0.1 → 9.1.0
- Kotlin 2.0.21 → 2.2.10

### UI 改进
- **Settings 入口**：Chat TopBar 右侧添加齿轮图标，点击可进入 Settings（解决 Fold 平板布局下底部导航栏不可见、Connect/Settings 点击无反应的问题）
- **Connect 按钮**：增大触摸区域（`minimumInteractiveComponentSize` + padding），提升大屏设备可点性
- **TabletLayout**：hoist `showSettings` 状态，支持从 Chat 面板直接打开 Settings

### 默认服务器
- 默认 Server URL 改为 `http://quantum.tail63c3c5.ts.net:4096`（Tailscale MagicDNS）
- 更新 SettingsManager、OpenCodeRepository、SettingsScreen placeholder
- 新增单元测试：`default server URL is Tailscale quantum`

### 平板布局改进
- **Tab 切换**：左侧面板用 TabRow [Workspace] [Settings] 替代图标，便于在 Files 与 Settings 间切换
- **Session 列表**：Workspace Tab 顶部显示 Session 列表（含 New 按钮），下方为文件树
- **消息空状态**：Chat 无消息时显示 "No messages yet. Send a message to start."
- **Session.displayName**：提取 session 显示名逻辑为扩展属性，优先 title → directory 末段 → id

### 平板布局简化 (2026-03-03 续)
- **移除文件树**：左侧面板仅保留 Workspace（Session 列表）和 Settings，移除左下角 file tree
- **恢复中间栏**：三栏布局保留，中栏为 FilesScreen（文件预览），右栏为 Chat
- **栏宽比例**：左 25%、中 37.5%、右 37.5%
- **消息加载错误**：loadMessages 失败时在 UI 显示具体错误信息，便于排查 API 解析问题
- **/file path 参数**：getFileTree 传空字符串替代 null，部分服务端要求 path 参数存在

### 自动连接与消息加载 (2026-03-03 续)
- **自动连接**：App 启动及回到前台时自动调用 testConnection()，无需手动点 Settings → Test Connection
- **消息加载修复**：loadSessions 成功且已有 currentSessionId 时，同时调用 loadMessages(currentId)，修复「No messages yet」问题
- **MessageWithParts 解析测试**：ModelTests 新增 `MessageWithParts parses real API format`，验证 info/parts 与真实 API 响应格式一致

### 消息解析与 UI 修复 (2026-03-03 续)
- **Part.files 解析**：API 可能返回 `["path1","path2"]` 或 `[{path,...}]`，新增 PartFilesSerializer 兼容两种格式
- **loadMessages 错误日志**：失败时 Log.e 输出完整堆栈，便于 logcat 排查
- **Files 后退按钮**：修复路径无 `/` 时（如 `.github`）无法返回根目录，parentPath 正确计算
- **Chat TopBar 下拉**：Model/Agent/Session 的 IconButton+DropdownMenu 用 Box 包裹，修复 iPad 上定位
- **平板布局**：移除 TabRow，左栏默认 SessionList，header 增加 Settings 图标；SettingsScreen 支持 onBack 显示返回按钮
- **Chat 标题**：titleSmall + maxLines=1 + Ellipsis，长标题省略；平板隐藏 Settings 按钮（左栏已有）

### Markdown 与依赖 (2026-03-03 续)
- **Compose BOM**：升级至 2025.12.00（含 runtime 1.10.0），修复 markdown-renderer 0.39.0 的 NoSuchMethodError
- **compileSdk**：34 → 35（runtime 1.10.0 要求）
- **ReasoningCard**：恢复 Markdown 渲染

### Session 列表与 Patch 改进 (2026-03-03 续)
- **Session 列表**：交替背景色（一明一暗）、分割线、分页显示（首屏 20 条，滚动到底部动态加载更多）
- **Patch/Tool 文件**：每个文件路径右侧添加「Show in Files」按钮，点击后在 Files 面板直接显示该文件内容
- **AppState**：新增 `filePathToShowInFiles`，MainViewModel 新增 `showFileInFiles()` / `clearFileToShow()`
- **FilesScreen**：支持 `pathToShow` 与 `onCloseFile` 参数，可从 Chat 面板跳转并展示指定文件
- **测试**：AppStateTest 新增 filePathToShowInFiles 默认值与读写；ModelTests 新增 Part.filePathsForNavigation 测试

### 修复 (2026-03-03)
- **工具/补丁更新加速**：session 为 busy 时每 2 秒轮询 loadMessages，弥补 SSE 延迟
- **Patch 过滤目录**：API 返回 session 级目录列表，新增 `filePathsForNavigationFiltered` 仅含带扩展名路径，Patch/Tool 卡片仅展示文件
- **Files 面板**：传入 `sessionDirectory`，绝对路径转相对；getFileContent 为空时 fallback 到 getFileTree 展示目录
- **手机导航**：修复 popUpTo(Screen.Chat.route) { inclusive = true }，从 Files 点 Chat Tab 可正确返回

### UI 改进 (2026-03-03 续)
- **Tool 卡片**：标题改为扳手图标 + tool 类型（read/patch/bash）；文件路径两列并排，不展开也显示「Show in Files」图标；展开后显示 Input/Tasks/Output
- **Tool/Patch 布局**：一行显示两个 tool（或两个 patch），并排；Tool 收起时仅显示类型 + 打开图标，展开后仅显示文件名列表（不显示 Input/Output）
- **Files 文件预览**：查看文件时隐藏 Files TopAppBar，仅保留 FileContentViewer 的 TopAppBar（叉号+文件名），去除中间大块空白
- **文件跳转调试**：FilesScreen 与 MainViewModel 添加 Log.d 便于 logcat 排查 pathToShow/relPath；路径规范化（trimStart('/')）兼容有无前导斜杠；空目录显示 "Directory (empty or path not found)"

### 字号与布局 (2026-03-03 续)
- **字号缩小**：全局 Typography 缩小一号（bodyLarge 16→14sp，titleLarge 22→20sp 等）；平板 compactTypography 同步缩小
- **Tablet 隐藏 Session 按钮**：平板布局左侧已有 Session 列表，TopBar 不再显示 Session 下拉按钮（showSessionListInTopBar=false）
- **Model 列表修复**：API `/config/providers` 的 `default` 字段实际为 `Map<providerId, modelId>` 而非单对象；ProvidersResponse 改为解析 Map，default 取首项；新增 ModelTests 验证解析
- **Model 筛选**：仿照 iOS，仅显示预设模型列表（ModelPresets），不展示 API 返回的全部模型；预设与 iOS 一致：GLM-5、Opus 4.6、Sonnet 4.6、GPT-5.3 Codex、GPT-5.2、Gemini 3.1 Pro、Gemini 3 Flash

### Tool 卡片样式 (2026-03-03 续)
- **文件名换行**：Tool/Patch 展开后文件名支持多行换行（移除 maxLines=1），便于查看长路径
- **write/patch 颜色**：浅色模式用灰底（surfaceContainerHighest）+ 蓝色前景，避免深蓝底黑字看不清；深色模式保持深蓝底（ToolWritePatchBackgroundDark）
- **Tool 并排布局**：连续 tool 与 patch 并排同一行（如 todoWrite 与 write 左右并排），不再分两行

### Markdown 标题字号 (2026-03-03 续)
- **标题缩小**：Markdown 渲染使用 `markdownTypographyCompact()`，h1-h6 各缩小一号（h1 用 headlineLarge、h2 用 headlineMedium 等）；Chat 消息、ReasoningCard、Files 预览均生效
- **可定制**：`com.mikepenz.markdown.m3.markdownTypography` 支持 h1-h6、text、code、quote 等参数，可在 Type.kt 的 `markdownTypographyCompact()` 中调整

### 发送消息修复 (2026-03-03 续)
- **promptAsync 错误处理**：Retrofit 4xx/5xx 不抛异常，需检查 `response.isSuccessful`；失败时抛出异常并显示给用户
- **model: null 序列化**：服务器不接受 `model: null`，Json 配置 `explicitNulls = false` 省略 null 字段
- **parts type discriminator**：服务器需要 `type` 字段区分 Part 类型，Json 配置 `encodeDefaults = true` 确保序列化默认值
- **消息更新及时性**：sendMessage 成功后立即 loadMessagesWithRetry；session.status 变为 idle 时 loadMessagesWithRetry；session busy 时每 2 秒轮询 loadMessages 作为 SSE 补充

### 状态栏 insets (2026-03-03 续)
- **Scaffold contentWindowInsets**：PhoneLayout 添加 `WindowInsets.statusBars`，避免内容与状态栏重叠
- **TabletLayout**：Row 添加 `windowInsetsPadding(WindowInsets.statusBars)`，Sessions 标题不再与状态栏重叠

### Tool 卡片 Todo 展示 (2026-03-03 续)
- **Todo 内容显示**：ToolCard 新增 todos 参数，展开时显示具体任务（content、status、priority）；已完成用勾选图标+删除线，未完成用空心圆；有 todos 时默认展开

### 状态更新与消息顺序 (2026-03-03 续)
- **selectSession 清空**：切换 session 时立即清空 messages，避免短暂显示上一 session 内容
- **loadMessagesWithRetry**：延迟 150ms→400ms；发送后增加 1.2s 二次加载
- **消息显示顺序**：API 返回 [旧→新]，LazyColumn reverseLayout 需 reversed() 使最新消息在底部；ModelTests 新增 `message display order reverses API chronological order`

### 夜间模式与主题 (2026-03-03 续)
- **根背景**：Theme 根布局用 `Surface(color = colorScheme.background)` 包裹，确保整屏背景随主题正确切换
- **动态颜色**：API 31+ 使用 dynamicLight/darkColorScheme，API 30 及以下用 light/darkColorScheme
- **主题模式**：从 UiModeManager 读取，替代 LocalConfiguration.uiMode
