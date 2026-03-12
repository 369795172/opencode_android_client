# OpenCode Android 客户端工作日志

## 2026-02-23

- 项目初始化，Android Studio 创建项目，初始化 git，编写 PRD 和 RFC
- 添加全部依赖（OkHttp、Retrofit、Serialization、Hilt、Navigation、Security 等）
- 实现数据模型层：Session、Message、Part、AgentInfo、TodoItem、FileNode、Config、SSE、Permission
- 实现网络层：REST API 接口、OkHttp SSE 客户端、带认证的 Repository
- 搭建 Hilt DI 和 Application 类
- 实现 UI 主题（浅色/深色）、SettingsManager（EncryptedSharedPreferences）、ThemeMode
- 实现 AppState + MainViewModel（StateFlow + SSE）
- 实现三个主页面：ChatScreen、FilesScreen、SettingsScreen，MainActivity 添加底部导航
- 配置 network_security_config.xml 和 INTERNET 权限
- 创建 ModelTests 和 AppStateTest

---

## 2026-02-24

- 修复大量编译问题：kapt 迁移至 KSP、升级 KSP 版本、修复各文件导入和语法错误
- 创建 AGENTS.md（构建环境说明）
- 替换应用图标为 OpenCode logo（从 iOS 项目复制，脚本生成各尺寸 mipmap）
- 修复 Settings 页面：加载已保存设置、Test Connection 正常工作、Save 持久化
- 添加测试覆盖率（Kover）和集成测试，配置 .env 凭证加载
- 添加 Android Studio 运行配置

---

## 2026-03-02

- 代码审查发现 5 个 bug：Repository lazy re-init、network_security_config 过宽、主题切换未持久化、SSE 无重连、模型选择无 UI
- 修复全部 bug：Repository 改为 mutable + rebuildClients、Tailscale ts.net exception、主题全链路打通、SSE 指数退避重连
- 新增 Markdown 渲染（Chat 消息 + 文件预览）
- 新增模型选择下拉菜单
- 新增 Context Usage 环形进度条
- 新增平板三栏布局
- AppState 扩展：ModelOption、ContextUsage、availableModels、contextUsage
- AppStateTest 新增 14 个测试，更新 PRD/RFC 标记完成状态

---

## 2026-03-03

- 重新生成 Gradle wrapper，新增 gradle.properties 配置 JVM 内存，升级 AGP 和 Kotlin
- Chat TopBar 添加 Settings 齿轮入口，解决平板布局下底部导航不可见的问题
- 默认 Server URL 改为 Tailscale quantum 地址
- 平板布局多轮迭代：左栏 Session 列表 + Settings，中栏文件预览，右栏 Chat，比例 25/37.5/37.5
- App 启动时自动连接服务器，修复消息加载时机
- 修复消息解析：Part.files 兼容字符串数组和对象数组两种格式
- 修复 Files 后退按钮、Chat TopBar 下拉定位、手机导航返回
- 升级 Compose BOM 至 2025.12.00，compileSdk 升至 35，修复 markdown-renderer 兼容性
- Tool/Patch 卡片改进：类型标题、两列并排、Show in Files 跳转、蓝色背景、Todo 展示
- 全局字号缩小一号，Markdown 标题同步缩小
- 模型列表改为预设模式（与 iOS 一致），修复 ProvidersResponse default 字段解析
- 修复发送消息：错误处理、null 字段省略、type 序列化、消息更新及时性
- 修复状态栏 insets 重叠，修复 session 切换闪烁和消息顺序

---

## 2026-03-05

- InputBar 添加 imePadding，物理键盘 IME 栏不再遮挡输入框
- Session 列表左滑删除（AnchoredDraggable），SessionList 提取为共用组件
- 手机端用 ModalBottomSheet 展示 SessionList，左滑 reveal 删除
- Logo 缩小至 66dp 安全区
- 用户消息与 AI 回复均可长按选择复制
- Session 子 agent 树形折叠展开，新增 SessionTreeTest

---

## 2026-03-12

- 设计文档：`docs/speech_recognition.md`（PRD + RFC，中文）
- 新增 AIBuildersAudioClient：通过 AI Builder WebSocket API 实现实时语音转写，支持部分结果回调和连接测试
- 新增 AudioRecorderManager：M4A 录音 + 解码 + 重采样至 24kHz PCM
- SettingsManager 新增 6 个 AI Builder 相关属性
- SettingsScreen 新增 Speech Recognition 设置区（Base URL、Token、Prompt、Terminology、连接测试）
- ChatScreen 输入栏添加麦克风按钮（录音中红色动画），speechError 弹窗提示
- MainViewModel 新增语音状态管理、录音/转写流程、连接测试逻辑
- AndroidManifest 添加 RECORD_AUDIO 权限
- 22 个单元测试 + 集成测试，全部通过
- 修复麦克风“点击无反应”：按钮在未配置时不再静默禁用，点击会进入 ViewModel 并给出明确错误提示；补充录音/转写关键日志
- 修复 AI Builder Token 隐藏字符问题：清洗零宽字符/BOM/空白后再组 Authorization header，解决 `unexpected char 0x200b`
- 修复录音启动失败 `setAudioSource failed`：点击麦克风前先检查/请求 `RECORD_AUDIO` 运行时权限，拒绝时给出明确提示
- 真机验证通过：AI Builder 连接成功后，首次点击麦克风会触发系统权限请求，授权后可正常开始录音
- Repo 公开到 GitHub（grapeot/opencode_android_client），添加 README
- 添加 GitHub Actions CI（unit test on push/PR）
- 版本号设为 0.1.20260312，首个 GitHub Release
- 将 `speech_recognition.md` 的核心设计合并进 `PRD.md` 与 `RFC.md`，删除单独文档
- 修复 Chat 输入栏：录音中允许继续发送已有文本；输入框变高时右侧操作按钮自动改为竖排
- 修复 CI：提交 `gradle-wrapper.jar`，避免 GitHub Actions 找不到 Gradle wrapper
- Files 新增图片预览：默认 fit-to-screen，支持双击缩放、拖动平移、系统分享
- Chat 自动跟随改为双模式：停留在底部时跟随新内容，离开底部时保持当前位置
- 修复手机端 Settings/Chat 状态错位：Settings 与 Chat 统一使用同一个 `MainViewModel`，避免 AI Builder 连接测试结果只留在 Settings 页面，导致麦克风仍提示未通过测试
- 修复手机端底部 Tab 导航：改为 top-level 导航写法，去掉对 `Screen.Chat.route` 的特殊 `inclusive popUpTo`，降低切换 Chat/Settings 后出现空白 Settings 页的风险
- 简化 Chat 顶栏：移除右上角重复的停止图标，busy 状态只保留输入栏右侧的停止按钮，减少重复入口
- 新建阶段一测试强化分支：`feature/test-hardening-phase1`
- 细化并落实“先补测试、再做拆分”的执行顺序：优先锁定 `MainViewModel` 状态迁移与 `OpenCodeRepository` 协议行为
- 新增 `MainDispatcherRule`，为 ViewModel 协程测试提供稳定的 Main dispatcher 控制
- 新增 `MainViewModelTest`，覆盖初始化模型索引钳制、AI Builder 连接状态恢复、发送消息成功/失败、消息加载后同步 agent/model、录音前置校验、停止录音失败、SSE streaming 增量、idle 状态补刷、权限请求刷新等关键状态机
- 扩充 `OpenCodeRepositoryTest`，覆盖 `sendMessage()` 请求体与 Basic Auth header、错误体透传、`getFileContent()` 查询参数、`getProviders()` 默认模型解析、`configure()` 重建 client 后切换 base URL/认证信息
- 根据 Oracle 审查补充 guard-rail：新增空输入/无 session 发送短路测试、`message.part.updated` 缺失 delta 的补刷测试、空 session SSE 防崩测试，并修复 `MainViewModel` 中该空 session 分支的 NPE
- 执行验证：`./gradlew testDebugUnitTest` 通过
- 生成覆盖率报告：`./gradlew koverHtmlReport`，报告位于 `app/build/reports/kover/html/index.html`
- 新建完整重构分支：`feature/full-refactor-phase2`
- 更新 `docs/dev_code_review.md`，将审查结论转换为本轮可执行 checklist
- 将 `SettingsScreen` 拆分为连接、外观、语音、About 四个 section 组件，主屏只保留状态编排
- 将 `FilesScreen` 拆分为浏览区、预览区与纯 helper（路径归一化、目录预览文案），并补充 `FileNavigationUtilsTest`
- 将 Chat UI 拆分为顶栏、消息内容、输入栏三个文件；同时把 `MessageSelectionTest` 从单文件断言改成 chat package 级行为断言，避免后续继续拆文件时产生伪失败
- 执行验证：`./gradlew testDebugUnitTest` 通过
- 继续收口 `MainViewModel`：新增 `MainViewModelConnectionActions.kt`、`MainViewModelSpeechActions.kt`、`MainViewModelSyncActions.kt`，把连接初始化、AI Builder 连接测试、录音转写编排、busy polling、SSE 收集与事件分发从主类中抽离出去
- 收紧 `MainViewModel` 相关错误处理：session 列表/状态、load more、create/update/delete session、abort session、agent/pending permission 加载都改为统一日志或状态可观测路径
- 根据 Oracle 复查补充收口：统一 AI Builder signature 的规范化计算，修复 saved session 丢失后的回退选择，避免 busy polling 与消息加载重叠，并把错误文案从 `null` 降级为明确 fallback
- 清理 `docs/dev_code_review.md` 中已经失效的 `MainViewModel.kt:<line>` 引用，改成按职责分组的文件级定位
- 执行验证：`./gradlew testDebugUnitTest assembleDebug koverHtmlReport` 通过
- 新建 phase 3 收口分支：`feature/phase3-constant-and-structure-followups`
- 新增 `ChatUiTuning.kt` 与 `AudioTranscriptionConfig.kt`，把 Chat 输入区 / top bar 阈值与音频转写参数从 UI、录音、WebSocket 实现中收口出来
- 将 `FilePreviewPane.kt` 的 preview 分流逻辑下沉到 `FilePreviewUtils.previewContentKind(...)`，减少 Composable 内部 `when` 分支复杂度
- 新增 `ChatUiTuningTest.kt`、扩充 `FilePreviewUtilsTest.kt` 与 `SpeechRecognitionTest.kt`，为阈值判断、preview 路由和音频参数对齐补充 JVM 护栏
- 新增 `ChatInputBarInstrumentedTest.kt` 与 `SettingsSectionsInstrumentedTest.kt`，覆盖 Chat 输入区动作按钮状态、Settings 语音区按钮可用性与成功态展示
- 调整 connected integration tests：仅在显式配置且服务可达时运行 OpenCode server smoke tests；未配置或服务不可达时自动 skip，避免把外部环境问题误报为回归
- 执行验证：`./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest koverHtmlReport connectedDebugAndroidTest` 通过
