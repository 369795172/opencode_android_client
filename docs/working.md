# OpenCode Android 客户端工作日志

## 2026-02-23

- 使用 Android Studio 创建项目结构，初始化 git，创建 PRD.md 和 RFC.md
- 在 libs.versions.toml 添加 OkHttp、Retrofit、Kotlinx Serialization、Hilt、Navigation、Security、Lifecycle 等依赖
- 创建 Session、Message、AgentInfo、TodoItem、File、Config、SSE、Permission 等数据模型
- 实现 OpenCodeApi、SSEClient、OpenCodeRepository
- 创建 AppModule、OpenCodeApp
- Theme.kt 支持浅色/深色，SettingsManager 用 EncryptedSharedPreferences，ThemeMode 枚举
- AppState 与 MainViewModel
- ChatScreen、FilesScreen、SettingsScreen，MainActivity 底部导航
- network_security_config 支持 HTTP
- ModelTests、AppStateTest

## 2026-02-24

- 迁移 kapt → KSP，修复 Message/Session/Permission/OpenCodeRepository 等构建问题，添加 material-icons-extended
- 创建 AGENTS.md
- 从 opencode_ios_client 复制 logo，resize_icon.py 生成 mipmap
- Settings 从 SettingsManager 加载并持久化，Test Connection 实际调用 API
- 添加 Kover、OpenCodeRepositoryTest（MockWebServer）、.env 凭证、OpenCodeIntegrationTest
- 添加 runConfigurations/app.xml

## 2026-03-02

- 代码审查发现 Repository lazy re-init、network_security_config、主题切换未生效、SSE 无重连、模型选择无 UI
- Sprint：Markdown 渲染、主题修复、模型选择 UI、Context Usage 环形进度、平板三栏、Bug 修复
- Repository 改为 mutable + rebuildClients，network_security_config 添加 ts.net，SSE retryWhen 指数退避，主题全链路打通
- Markdown 用 markdown.m3，模型下拉从 providers API，ContextUsageRing 绿/橙/红，平板 calculateWindowSizeClass 三栏
- AppState 新增 ModelOption、ContextUsage、availableModels、contextUsage
- AppStateTest 新增 14 个测试，PRD/RFC 更新

## 2026-03-03

- Gradle wrapper 9.3.1，gradle.properties -Xmx16g，AGP 9.1.0，Kotlin 2.2.10
- Chat TopBar 添加 Settings 齿轮，Connect 增大触摸区域，TabletLayout hoist showSettings
- 默认 Server localhost，平板 TabRow Workspace/Settings，Session 列表 + New，Session.displayName 扩展属性
- 移除文件树，左栏 SessionList + Settings，中栏 FilesScreen，右栏 Chat，栏宽 25/37.5/37.5
- 自动 testConnection，loadMessages 修复，Part.files 兼容两种格式，Files 后退 parentPath 修复，DropdownMenu Box 包裹
- Compose BOM 2025.12.00，compileSdk 35，ReasoningCard 恢复 Markdown
- Session 交替背景、分页，Patch/Tool 添加 Show in Files，filePathToShowInFiles、showFileInFiles
- busy 时 2 秒轮询 loadMessages，filePathsForNavigationFiltered，sessionDirectory 转相对，popUpTo 修复手机导航
- Tool 卡片扳手图标，Tool/Patch 并排，Files 预览隐藏 TopAppBar
- 字号缩小，Model 列表 default 解析 Map，ModelPresets 筛选，文件名换行，write/patch 颜色，markdownTypographyCompact
- promptAsync 错误处理，model null 省略，encodeDefaults，sendMessage 后 loadMessagesWithRetry，status idle 时重载
- WindowInsets.statusBars，ToolCard todos，selectSession 清空，loadMessagesWithRetry 400ms，reversed 消息顺序
- 根背景 Surface，dynamicColorScheme，UiModeManager
- Tool 最多两列，Model/Agent 从最后消息同步，Message.agent，消息分页 30 条
- HTTP 限制 cleartextTrafficPermitted false，Log 清理，backup 排除 shared_prefs，Agent 下拉完整 name

## 2026-03-05

- InputBar 添加 imePadding，物理键盘 IME 栏不再遮挡输入框
- InputBarInsetsTest 验证 windowSoftInputMode=adjustResize
- Session 列表左滑显示红色删除按钮，点击删除，SwipeToDismissBox backgroundContent
- 修复部分 item 左滑无响应：gesturesEnabled = !listState.isScrollInProgress，避免 LazyColumn 滚动与 SwipeToDismissBox 手势冲突
- SessionList 提取到 ui/session/SessionList.kt，平板与手机共用
- 手机端 Session 删除：用 ModalBottomSheet 替代下拉菜单，展示与平板相同的 SessionList，左滑 reveal 删除
- 修复 SwipeToDismissBox 松手弹回：改用 AnchoredDraggable，左滑后松手可定住，删除按钮保持可见可点击
- 删除区域收窄至 56dp，行背景改为完全不透明（surfaceContainerLow / surface）解决半透明遮挡删除按钮问题
- Logo 缩小：resize_icon.py 按 Android 66dp 安全区生成 foreground（logo 居中 66dp/108dp）；mipmap 图标 logo 约 66% 尺寸
