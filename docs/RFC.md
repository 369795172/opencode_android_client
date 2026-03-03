# RFC-001: OpenCode Android Client 技术方案

> Request for Comments · Accepted · Mar 2026

## 元数据

| 字段 | 值 |
|------|------|
| **RFC 编号** | RFC-001 |
| **标题** | OpenCode Android Client 技术方案 |
| **状态** | Accepted (Implemented) |
| **创建日期** | 2026-02 |
| **最后更新** | 2026-03-02 |
| **PRD 引用** | [PRD.md](PRD.md) |

---

## 摘要

本 RFC 提出 OpenCode Android Client 的技术实现方案。核心是：在 Android 8.0+ 上构建一个基于 Jetpack Compose 的原生客户端，通过 HTTP REST + SSE 与 OpenCode Server 通信，实现远程监控、消息发送、文档审查等能力。

---

## 1. 整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                    Android Client (Jetpack Compose)              │
├─────────────────────────────────────────────────────────────────┤
│  UI Layer              │  ViewModel Layer      │  Data Layer     │
│  ─────────             │  ────────────         │  ──────────     │
│  ChatScreen            │  ChatViewModel        │  APIClient      │
│  FilesScreen           │  FilesViewModel       │  SSEClient      │
│  SettingsScreen        │  SettingsViewModel    │  Repository     │
│  Components            │                       │                 │
└─────────────────────────────────────────────────────────────────┘
                              │
                              │ OkHttp (REST + SSE)
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                     OpenCode Server (Mac/Linux)                  │
│  GET /global/event  │  POST /session/:id/prompt_async  │  ...    │
└─────────────────────────────────────────────────────────────────┘
```

**分层说明**：
- **UI Layer**：Jetpack Compose 声明式 UI
- **ViewModel Layer**：持有 UI 状态，处理业务逻辑
- **Data Layer**：网络请求、数据持久化

---

## 2. 技术选型

| 层面 | 选择 | 理由 |
|------|------|------|
| 语言 | Kotlin | Android 官方推荐，协程支持好 |
| UI | Jetpack Compose | 声明式，与 SwiftUI 概念相似，未来方向 |
| 状态 | ViewModel + StateFlow | 官方推荐，生命周期感知 |
| 网络 | OkHttp + Retrofit | 业界标准，SSE 支持好 |
| 序列化 | Kotlinx Serialization | Kotlin 原生，性能好 |
| 依赖注入 | Hilt | 官方推荐，Dagger 封装 |
| Markdown | Markwon 或 Compose Markdown | 成熟稳定 |
| 代码高亮 | Prism4j 或 Syntax Highlighter | 轻量级 |
| SSH（可选） | Apache Mina SSHD 或 JSch | 成熟，支持端口转发 |
| 安全存储 | EncryptedSharedPreferences + Keystore | Android 官方方案 |

### 2.1 HTTP 连接配置

Android 9+ 默认禁止明文流量，需要配置 `network_security_config.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">localhost</domain>
        <domain includeSubdomains="true">127.0.0.1</domain>
        <domain includeSubdomains="true">10.0.2.2</domain>
    </domain-config>
</network-security-config>
```

对于局域网 IP，可以动态添加用户配置的服务器地址。

### 2.2 SSH 库选型（可选）

| 库 | 语言 | 维护状态 | 推荐度 |
|----|------|----------|--------|
| **Apache Mina SSHD** | Java | 活跃 | ★★★★★ |
| JSch | Java | 维护模式 | ★★★★ |
| sshj | Java | 活跃 | ★★★★ |

**推荐 Apache Mina SSHD**：功能完整，支持端口转发，文档齐全。

---

## 3. 网络层设计

### 3.1 REST API

```kotlin
interface OpenCodeApi {
    @GET("/health")
    suspend fun getHealth(): HealthResponse
    
    @GET("/session")
    suspend fun getSessions(): List<Session>
    
    @GET("/session/{id}/message")
    suspend fun getMessages(
        @Path("id") sessionId: String,
        @Query("limit") limit: Int = 6
    ): List<Message>
    
    @POST("/session/{id}/prompt_async")
    suspend fun sendMessage(
        @Path("id") sessionId: String,
        @Body request: PromptRequest
    )
    
    @POST("/session/{id}/permissions/{permissionId}")
    suspend fun handlePermission(
        @Path("id") sessionId: String,
        @Path("permissionId") permissionId: String,
        @Body request: PermissionRequest
    )
    
    @POST("/session/{id}/abort")
    suspend fun abortSession(@Path("id") sessionId: String)
    
    @GET("/file")
    suspend fun getFileTree(@Query("path") path: String?): FileNode
    
    @GET("/file/content")
    suspend fun getFileContent(@Query("path") path: String): FileContent
    
    @GET("/agent")
    suspend fun getAgents(): List<AgentInfo>
}
```

### 3.2 SSE 连接

使用 OkHttp 的 `EventSource`：

```kotlin
class SSEClient(
    private val okHttpClient: OkHttpClient,
    private val baseUrl: String
) {
    private var eventSource: EventSource? = null
    
    fun connect(onEvent: (SSEEvent) -> Unit) {
        val request = Request.Builder()
            .url("$baseUrl/global/event")
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .build()
            
        eventSource = EventSources.createFactory(okHttpClient)
            .newEventSource(request, object : EventSourceListener() {
                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    val event = Json.decodeFromString<SSEEvent>(data)
                    onEvent(event)
                }
            })
    }
    
    fun disconnect() {
        eventSource?.cancel()
    }
}
```

### 3.3 错误处理与重连

- 网络错误：Toast 提示，不 crash
- SSE 断开：指数退避重连，上限 30s
- 服务器不可达：显示 Disconnected 状态

---

## 4. 状态管理

### 4.1 AppState

```kotlin
data class AppState(
    val serverUrl: String = "",
    val isConnected: Boolean = false,
    val sessions: List<Session> = emptyList(),
    val currentSessionId: String? = null,
    val messages: List<Message> = emptyList(),
    val selectedModelIndex: Int = 0,
    val selectedAgentIndex: Int = 0,
    val agents: List<AgentInfo> = emptyList()
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val apiClient: OpenCodeApi,
    private val sseClient: SSEClient
) : ViewModel() {
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()
    
    fun handleSSEEvent(event: SSEEvent) {
        when (event.payload.type) {
            "session.created" -> { /* 更新 sessions */ }
            "message.created" -> { /* 追加消息 */ }
            "message.part.updated" -> { /* 流式更新 */ }
            // ...
        }
    }
}
```

### 4.2 数据模型

```kotlin
@Serializable
data class Session(
    val id: String,
    val directory: String,
    val model: String? = null,
    val createdAt: String? = null
)

@Serializable
data class Message(
    val id: String,
    val role: String,
    val parts: List<Part>
)

@Serializable
data class Part(
    val id: String,
    val type: String,
    val text: String? = null,
    val toolName: String? = null,
    val toolInput: JsonObject? = null,
    val toolOutput: String? = null,
    val state: PartState? = null
)

@Serializable
data class AgentInfo(
    val name: String,
    val description: String? = null,
    val mode: String? = null,
    val hidden: Boolean? = null
)
```

---

## 5. UI 设计

### 5.1 导航结构

```kotlin
@Composable
fun MainNavigation() {
    val navController = rememberNavController()
    
    NavHost(navController, startDestination = "chat") {
        composable("chat") { ChatScreen() }
        composable("files") { FilesScreen() }
        composable("settings") { SettingsScreen() }
    }
}

// 手机：底部 Tab
@Composable
fun PhoneLayout() {
    Scaffold(
        bottomBar = { BottomNavigationBar() }
    ) { padding ->
        NavHost(/* ... */, modifier = Modifier.padding(padding))
    }
}

// 平板：三栏布局
@Composable
fun TabletLayout() {
    Row {
        WorkspacePanel(Modifier.weight(1f))
        PreviewPanel(Modifier.weight(1.5f))
        ChatPanel(Modifier.weight(1.5f))
    }
}
```

### 5.2 消息渲染

```kotlin
@Composable
fun MessageRow(message: Message) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (message.role == "user") 
                    MaterialTheme.colors.surface.copy(alpha = 0.5f)
                else 
                    MaterialTheme.colors.background
            )
            .padding(16.dp)
    ) {
        message.parts.forEach { part ->
            when (part.type) {
                "text" -> MarkdownText(part.text ?: "")
                "reasoning" -> ReasoningCard(part)
                "tool" -> ToolCard(part)
                "patch" -> PatchCard(part)
            }
        }
    }
}
```

### 5.3 流式显示

```kotlin
@Composable
fun StreamingText(text: String, isStreaming: Boolean) {
    var displayedText by remember(text) { mutableStateOf("") }
    
    LaunchedEffect(text) {
        if (isStreaming) {
            text.forEach { char ->
                delay(10) // 打字机效果
                displayedText += char
            }
        } else {
            displayedText = text
        }
    }
    
    Text(displayedText)
}
```

---

## 6. 安全设计

### 6.1 凭证存储

```kotlin
class CredentialManager(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    
    private val sharedPreferences = EncryptedSharedPreferences.create(
        context,
        "credentials",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    
    fun saveServerCredentials(url: String, username: String, password: String) {
        sharedPreferences.edit {
            putString("server_url", url)
            putString("auth_username", username)
            putString("auth_password", password)
        }
    }
}
```

### 6.2 SSH 密钥管理（可选）

```kotlin
class SSHKeyManager(context: Context) {
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    
    fun generateKeyPair(): KeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore"
        )
        keyPairGenerator.initialize(
            KeyGenParameterSpec.Builder("ssh_key", KeyProperties.PURPOSE_SIGN)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
        )
        return keyPairGenerator.generateKeyPair()
    }
}
```

---

## 7. 项目结构

```
app/
├── src/main/
│   ├── java/com/opencode/android/
│   │   ├── App.kt                    # Application 类
│   │   ├── MainActivity.kt           # 入口 Activity
│   │   ├── data/
│   │   │   ├── api/
│   │   │   │   ├── OpenCodeApi.kt    # Retrofit 接口
│   │   │   │   └── SSEClient.kt      # SSE 客户端
│   │   │   ├── model/                # 数据模型
│   │   │   └── repository/           # 数据仓库
│   │   ├── ui/
│   │   │   ├── chat/
│   │   │   │   ├── ChatScreen.kt
│   │   │   │   ├── MessageRow.kt
│   │   │   │   └── ChatViewModel.kt
│   │   │   ├── files/
│   │   │   ├── settings/
│   │   │   └── components/           # 共享组件
│   │   ├── di/                       # Hilt 模块
│   │   └── util/                     # 工具类
│   ├── res/
│   │   ├── xml/network_security_config.xml
│   │   └── ...
│   └── AndroidManifest.xml
├── build.gradle.kts
└── proguard-rules.pro
```

---

## 8. 依赖配置

```kotlin
// build.gradle.kts (app)
dependencies {
    // Compose
    implementation("androidx.compose.ui:ui:1.6.0")
    implementation("androidx.compose.material3:material3:1.2.0")
    implementation("androidx.compose.ui:ui-tooling-preview:1.6.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.navigation:navigation-compose:2.7.6")
    
    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    
    // Network
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")
    
    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    
    // DI
    implementation("com.google.dagger:hilt-android:2.50")
    kapt("com.google.dagger:hilt-compiler:2.50")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")
    
    // Security
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    
    // Markdown
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:syntax-highlight:4.6.2")
    
    // SSH (optional)
    implementation("org.apache.sshd:sshd-core:2.12.0")
}
```

---

## 9. 实现规划

| Phase | 范围 | 预计周期 |
|-------|------|----------|
| 1 | 项目搭建、网络层、SSE、Session、消息发送、流式渲染 | 2-3 周 |
| 2 | Part 渲染、权限审批、主题、语音输入 | 1-2 周 |
| 3 | 文件树、Markdown 预览、Diff、平板布局 | 2 周 |
| 4 | SSH Tunnel（可选） | 1 周 |

---

## 10. 风险与缓解

| 风险 | 缓解措施 |
|------|----------|
| Compose 学习曲线 | 团队培训，参考 iOS SwiftUI 经验 |
| SSE 兼容性 | 使用成熟的 OkHttp SSE 库 |
| 平板适配复杂度 | 先完成手机版，平板作为 Phase 3 |
| SSH 库稳定性 | 充分测试，提供降级方案（公网 HTTPS） |

---

## 参考

- [OpenCode Web API](../../../adhoc_jobs/opencode_ios_client/docs/OpenCode_Web_API.md)
- [Android Network Security Config](https://developer.android.com/training/articles/security-config)
- [Jetpack Compose Documentation](https://developer.android.com/jetpack/compose)
