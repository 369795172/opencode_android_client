# 语音识别功能：PRD + RFC

> 状态：Draft · 2026-03-12
> 目标：实现 Android 客户端与 iOS 客户端的语音识别 Feature Parity

---

## 第一部分：PRD（产品需求）

### 1.1 背景

iOS 客户端已实现基于 AI Builder Space WebSocket API 的语音转写功能。用户在聊天界面点击麦克风按钮录音，松手后自动转写为文字并填入输入框，支持实时流式显示 partial transcript。Android 客户端尚未实现此功能（PRD 中标记为"暂不实现"），需要补齐。

### 1.2 用户流程

录音和转写的完整交互：

1. 用户在 Settings 中配置 AI Builder 凭证（Base URL + Token），点击 Test Connection 验证
2. 在 Chat 界面，输入框右侧出现麦克风按钮（仅在 Token 已配置且连接测试通过时可用）
3. 点击麦克风按钮开始录音，按钮变为红色实心图标
4. 再次点击停止录音，按钮变为 loading spinner
5. 系统自动将录音转写为文字，partial transcript 实时显示在输入框中
6. 转写完成后，文字追加到输入框已有内容之后（以空格分隔）
7. 用户可编辑、继续录音追加，或直接发送

### 1.3 Settings 配置项

在 Settings 页面新增「Speech Recognition」区域，包含：

| 字段 | 类型 | 默认值 | 存储方式 |
|------|------|--------|----------|
| AI Builder Base URL | 文本 | `https://space.ai-builders.com/backend` | EncryptedSharedPreferences |
| AI Builder Token | 密码 | 空 | EncryptedSharedPreferences |
| Custom Prompt | 多行文本 | `All file and directory names should use snake_case (lowercase with underscores).` | EncryptedSharedPreferences |
| Terminology | 文本 | `adhoc_jobs, life_consulting, survey_sessions, thought_review` | EncryptedSharedPreferences |

Test Connection 按钮：调用 `/v1/embeddings` 验证凭证有效性，显示成功/失败状态。

### 1.4 录音按钮状态

| 状态 | 图标 | 可点击 | 条件 |
|------|------|--------|------|
| 空闲 | 麦克风轮廓（灰色） | 是 | Token 已配置且连接测试通过 |
| 录音中 | 麦克风实心（红色） | 是 | 点击停止录音 |
| 转写中 | CircularProgressIndicator | 否 | 等待转写完成 |
| 不可用 | 麦克风轮廓（灰色） | 否 | Token 未配置 / 连接未通过 / 正在发送消息 |

### 1.5 错误处理

| 场景 | 提示 |
|------|------|
| Token 为空 | "Speech recognition requires an AI Builder token. Configure it in Settings." |
| 连接测试未通过 | "AI Builder connection test has not passed. Please test in Settings first." |
| 麦克风权限被拒 | "Microphone permission denied. Enable it in system settings." |
| 转写失败（网络/服务器） | 显示具体错误信息 |

### 1.6 与 iOS 的功能对照

| 功能点 | iOS | Android（本次实现） |
|--------|-----|---------------------|
| 录音格式 | M4A → PCM 24kHz | PCM 44.1kHz → 降采样 24kHz |
| WebSocket 协议 | URLSessionWebSocketTask | OkHttp WebSocket |
| Partial transcript 实时显示 | ✓ | ✓ |
| 文本追加到已有输入 | ✓ | ✓ |
| Settings: Base URL / Token / Prompt / Terms | ✓ | ✓ |
| Test Connection | ✓（POST /v1/embeddings） | ✓ |
| 连接状态缓存（签名匹配跳过重测） | ✓ | ✓ |
| 录音权限请求 | ✓ | ✓ |

### 1.7 不做的事

本次不涉及：VAD 实时切分、多语言切换 UI、录音文件保存/重放、离线语音识别。

---

## 第二部分：RFC（技术方案）

### 2.1 架构总览

```
┌─────────────────────────────────────────────────────────────────────┐
│                      Android Client                                  │
├──────────────┬───────────────────┬──────────────────────────────────┤
│  UI Layer    │  ViewModel Layer  │  Data / Service Layer            │
│              │                   │                                  │
│  ChatScreen  │  MainViewModel    │  AIBuildersAudioClient           │
│   └ MicBtn   │   └ speech state  │   ├ createSession() [HTTP]      │
│   └ Input    │   └ toggleRec()   │   ├ streamPCM() [WebSocket]     │
│              │   └ testAIConn()  │   └ testConnection() [HTTP]     │
│  Settings    │                   │                                  │
│   └ Speech   │                   │  AudioRecorderManager            │
│     section  │                   │   ├ requestPermission()          │
│              │                   │   ├ start() → PCM capture        │
│              │                   │   └ stop() → PCM data            │
│              │                   │                                  │
│              │                   │  SettingsManager                  │
│              │                   │   └ aiBuilder* properties        │
└──────────────┴───────────────────┴──────────────────────────────────┘
                                    │
                        OkHttp (REST + WebSocket)
                                    ▼
┌─────────────────────────────────────────────────────────────────────┐
│            AI Builder Student Portal (Server)                        │
│  POST /v1/audio/realtime/sessions → session_id + ws_url             │
│  WS   /v1/audio/realtime/ws?ticket=xxx → transcript stream          │
│  POST /v1/embeddings → connection test                               │
└─────────────────────────────────────────────────────────────────────┘
```

### 2.2 WebSocket 协议

与服务端的交互分两步：先 HTTP 创建 session 获取 ticket，再用 ticket 建立 WebSocket。

**Step 1: 创建 Realtime Session**

```
POST /v1/audio/realtime/sessions
Authorization: Bearer <token>
Content-Type: application/json

{
  "language": null,
  "prompt": "<custom_prompt>",
  "terms": ["adhoc_jobs", "life_consulting", ...],
  "vad": false,
  "silence_duration_ms": 1200
}

Response:
{
  "session_id": "ws_sess_xxx",
  "ws_url": "/v1/audio/realtime/ws?ticket=ws_ticket_xxx",
  "expires_in": 300
}
```

**Step 2: WebSocket 流式传输**

```
Client                                  Server
  │                                       │
  │──── Connect ws_url ──────────────────>│
  │<──── {"type":"session_ready"} ────────│
  │                                       │
  │──── [binary PCM chunk 1] ────────────>│
  │──── [binary PCM chunk 2] ────────────>│
  │──── ... ─────────────────────────────>│
  │──── {"type":"commit"} ───────────────>│
  │                                       │
  │<──── {"type":"transcript_delta",      │
  │       "text":"你好"} ─────────────────│
  │<──── {"type":"transcript_delta",      │
  │       "text":"你好世界"} ─────────────│
  │<──── {"type":"transcript_completed",  │
  │       "text":"你好世界。"} ───────────│
  │<──── {"type":"usage", ...} ───────────│
  │                                       │
  │──── {"type":"stop"} ─────────────────>│
  │<──── {"type":"session_stopped"} ──────│
  │                                       │
```

**音频格式**：PCM16，24kHz，单声道，有符号 16 位小端。每个 WebSocket binary frame 不超过 240,000 bytes（约 5 秒）。

**WebSocket URL 构造**：服务端返回的 `ws_url` 是相对路径，需与 base URL 拼接后将 scheme 从 `https` 改为 `wss`（或 `http` 改为 `ws`）。

### 2.3 音频采集方案

iOS 的做法是先录制 M4A 文件，录音结束后用 `ExtAudioFile` API 一次性转换为 PCM 24kHz。Android 上等价方案有两条路径：

**方案 A（与 iOS 对齐）：录 M4A 后转换**
- 用 `MediaRecorder` 录制 M4A/AAC 文件
- 录音结束后用 `MediaExtractor` + `MediaCodec` 解码为 PCM
- 再降采样到 24kHz

**方案 B（直接 PCM）：用 AudioRecord 直接采集**
- 用 `AudioRecord` 直接采集 PCM（设备原生采样率，通常 44.1kHz 或 48kHz）
- 在内存中累积 PCM 数据
- 录音结束后降采样到 24kHz

**选择方案 A**。理由：

1. 与 iOS 实现一致性高，录音阶段都是录制压缩文件，转写阶段再转 PCM
2. `MediaRecorder` 录 M4A 简单可靠，一行代码就能配好，不需要处理 AudioRecord 的 buffer 管理和线程
3. M4A 文件可以在调试时保存下来回放检查，排错方便
4. 降采样用 Android 的 `AudioTrack.getNativeOutputSampleRate` 和 `AudioFormat` 就能搞定

**降采样实现**：录音结束后，将 AAC 文件通过 `MediaExtractor` + `MediaCodec` 解码为原始 PCM，然后用线性插值降采样到 24kHz 16-bit mono。这和 iOS 端 `ExtAudioFileSetProperty + ExtAudioFileRead` 的逻辑等价。

### 2.4 新增文件清单

```
app/src/main/java/com/yage/opencode_client/
├── data/
│   └── audio/
│       ├── AudioRecorderManager.kt    # 录音管理（MediaRecorder + M4A）
│       └── AIBuildersAudioClient.kt   # WebSocket 转写客户端
└── (已有文件修改)
    ├── util/SettingsManager.kt        # +4 个 aiBuilder 属性
    ├── ui/MainViewModel.kt            # +speech state & methods
    ├── ui/chat/ChatScreen.kt          # +麦克风按钮
    └── ui/settings/SettingsScreen.kt  # +Speech Recognition section
```

### 2.5 AudioRecorderManager

封装 Android 的 `MediaRecorder` API，负责录音生命周期和权限请求。

```kotlin
class AudioRecorderManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null

    val isRecording: Boolean get() = recorder != null

    /** 请求麦克风权限，返回是否授予 */
    suspend fun requestPermission(activity: Activity): Boolean

    /** 开始录音，保存到临时 M4A 文件 */
    fun start(): File

    /** 停止录音，返回 M4A 文件路径 */
    fun stop(): File?

    /** 将 M4A 文件转换为 PCM 24kHz 16-bit mono ByteArray */
    suspend fun convertToPCM(m4aFile: File): ByteArray
}
```

录音参数对齐 iOS：
- 输出格式：MPEG_4 容器 + AAC 编码
- 采样率：44100 Hz
- 声道数：1（mono）
- 编码码率：64000 bps

### 2.6 AIBuildersAudioClient

封装与 AI Builder Space 服务端的所有交互，包括 HTTP session 创建和 WebSocket 流式传输。

```kotlin
object AIBuildersAudioClient {
    private const val TARGET_SAMPLE_RATE = 24000
    private const val SEND_CHUNK_SIZE = 240_000  // bytes

    /** 连接测试：POST /v1/embeddings */
    suspend fun testConnection(baseURL: String, token: String): Result<Unit>

    /** 完整转写流程：创建 session → WebSocket 流式传输 → 返回最终文本 */
    suspend fun transcribe(
        baseURL: String,
        token: String,
        pcmAudio: ByteArray,
        language: String? = null,
        prompt: String? = null,
        terms: String? = null,
        onPartialTranscript: ((String) -> Unit)? = null
    ): Result<TranscriptionResponse>
}

data class TranscriptionResponse(
    val requestId: String,
    val text: String
)
```

内部实现：
1. `createRealtimeSession()` — POST 创建 session，解析 `session_id` 和 `ws_url`
2. `buildWebSocketURL()` — 将 HTTP base URL + 相对 ws_url 拼接，scheme 转 ws/wss
3. `streamPCMOverWebSocket()` — OkHttp WebSocket 连接，发送 PCM chunks + commit，接收 transcript 事件

WebSocket 使用 OkHttp 而非第三方库，与项目现有网络层一致。OkHttp 的 `WebSocketListener` 提供 `onMessage(webSocket, text)` 和 `onMessage(webSocket, bytes)` 两个回调，分别处理 JSON 控制消息和二进制音频。

### 2.7 SettingsManager 扩展

```kotlin
// 新增属性
var aiBuilderBaseURL: String        // default: "https://space.ai-builders.com/backend"
var aiBuilderToken: String          // default: ""
var aiBuilderCustomPrompt: String   // default: "All file and directory names should use snake_case..."
var aiBuilderTerminology: String    // default: "adhoc_jobs, life_consulting, survey_sessions, thought_review"

// 新增 companion object keys
private const val KEY_AI_BUILDER_BASE_URL = "ai_builder_base_url"
private const val KEY_AI_BUILDER_TOKEN = "ai_builder_token"
private const val KEY_AI_BUILDER_CUSTOM_PROMPT = "ai_builder_custom_prompt"
private const val KEY_AI_BUILDER_TERMINOLOGY = "ai_builder_terminology"
```

全部使用 EncryptedSharedPreferences（token 和 base URL 都涉及凭证信息）。

### 2.8 AppState 扩展

```kotlin
data class AppState(
    // ... 现有字段 ...

    // Speech recognition state
    val isRecording: Boolean = false,
    val isTranscribing: Boolean = false,
    val speechError: String? = null,
    val aiBuilderConnectionOK: Boolean = false,
    val aiBuilderConnectionError: String? = null,
    val isTestingAIBuilderConnection: Boolean = false,
)
```

### 2.9 MainViewModel 扩展

```kotlin
// 新增方法
fun toggleRecording()              // 开始/停止录音 + 转写
fun testAIBuilderConnection()      // 测试 AI Builder 凭证
fun clearSpeechError()             // 清除语音错误

// Settings 相关
fun getAIBuilderSettings(): AIBuilderSettings
fun saveAIBuilderSettings(settings: AIBuilderSettings)

data class AIBuilderSettings(
    val baseURL: String,
    val token: String,
    val customPrompt: String,
    val terminology: String
)
```

`toggleRecording()` 的完整逻辑（对齐 iOS ChatTabView.toggleRecording）：

```
if isRecording:
    1. stop recorder → get M4A file
    2. set isRecording = false, isTranscribing = true
    3. convertToPCM(m4a)
    4. transcribe(pcm, onPartialTranscript = { partial ->
           inputText = mergedSpeechInput(prefix, partial)
       })
    5. inputText = mergedSpeechInput(prefix, finalTranscript)
    6. set isTranscribing = false
else:
    1. 检查 token 非空
    2. 检查 aiBuilderConnectionOK
    3. 请求麦克风权限
    4. start recorder
    5. set isRecording = true
```

### 2.10 UI 修改

**ChatScreen — 输入栏**

在 send 按钮上方（或左侧）添加麦克风按钮，布局与 iOS 一致：`[TextField] [MicButton / SendButton / AbortButton]`

```kotlin
// 麦克风按钮
IconButton(
    onClick = { viewModel.toggleRecording() },
    enabled = !state.isTranscribing && !isSending
              && state.aiBuilderConnectionOK
) {
    when {
        state.isTranscribing -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
        state.isRecording -> Icon(Icons.Default.Mic, tint = Color.Red)
        else -> Icon(Icons.Default.Mic, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
```

**SettingsScreen — Speech Recognition section**

在 Appearance section 之后、About section 之前，新增 Speech Recognition section：

```
[Section Title: Speech Recognition]
  AI Builder Base URL: [OutlinedTextField]
  AI Builder Token:    [OutlinedTextField, password]
  Custom Prompt:       [OutlinedTextField, multiline 3-6行]
  Terminology:         [OutlinedTextField]
  [Test Connection button] [状态指示器: ✓ / ✗ / spinner]
```

### 2.11 权限声明

`AndroidManifest.xml` 新增：

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

运行时权限请求使用 `ActivityResultContracts.RequestPermission()`。

### 2.12 mergedSpeechInput 逻辑

与 iOS 完全一致的文本合并逻辑：

```kotlin
fun mergedSpeechInput(prefix: String, transcript: String): String {
    val cleaned = transcript.trim()
    if (cleaned.isEmpty()) return prefix
    if (prefix.isEmpty()) return cleaned
    return "$prefix $cleaned"
}
```

当 prefix 为空时不补空格（修复 iOS 之前的句首空格 bug）。

### 2.13 连接测试与状态缓存

iOS 实现了基于签名的连接状态缓存：拼接 baseURL + token 的 hash，如果签名未变且之前测试通过，则跳过重测。Android 端同样实现此逻辑：

```kotlin
// SettingsManager 新增
var aiBuilderLastOKSignature: String?    // 上次成功的 baseURL+token hash
var aiBuilderLastOKTestedAt: Long        // 上次成功测试的时间戳

// 签名计算
fun aiBuilderSignature(baseURL: String, token: String): String {
    val input = "$baseURL|$token"
    return MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
```

### 2.14 错误处理

所有错误通过 `AppState.speechError` 传递到 UI，在 ChatScreen 通过 AlertDialog 显示。

| 错误类型 | 处理方式 |
|----------|----------|
| Token 为空 | 直接设置 speechError，不发起请求 |
| 连接未测试/失败 | 直接设置 speechError |
| 麦克风权限拒绝 | 设置 speechError |
| 录音启动失败 | 捕获异常，设置 speechError |
| HTTP session 创建失败 | 捕获异常，恢复 inputText，设置 speechError |
| WebSocket 连接/传输失败 | 捕获异常，恢复 inputText，设置 speechError |
| 转写超时 | OkHttp WebSocket 自带超时机制 |

### 2.15 依赖变更

本次实现不引入新的第三方依赖。OkHttp 已有 WebSocket 支持，`MediaRecorder` / `MediaExtractor` / `MediaCodec` 均为 Android SDK 内置 API。

### 2.16 测试策略

**Unit Tests（`./gradlew testDebugUnitTest`）：**

1. `AIBuildersAudioClientTest`
   - URL 构建：base URL + 相对路径拼接
   - WebSocket URL scheme 转换（https→wss, http→ws）
   - `mergedSpeechInput` 各种 edge cases（空 prefix、空 transcript、两者都空、两者都有）
   - Transcript delta 累积逻辑
   - Session response JSON 解析

2. `AudioRecorderManagerTest`
   - PCM 降采样：44.1kHz → 24kHz 验证采样点数正确
   - PCM 降采样：空数据、极短数据的边界处理

3. `AppStateTest` 扩展
   - speech state 默认值
   - aiBuilderConnectionOK 状态转换

4. `SettingsManagerTest` 扩展
   - aiBuilder 属性读写正确性

**Integration Tests（`./gradlew connectedDebugAndroidTest`，需要 .env 中的 AI_BUILDER_TOKEN）：**

1. `AIBuildersIntegrationTest`
   - testConnection 成功（需有效 token）
   - testConnection 失败（无效 token）
   - 完整转写流程：录制静音 PCM → 发送 → 收到空/短 transcript

### 2.17 实现计划

实现顺序考虑依赖关系和可测试性：

| 步骤 | 文件 | 依赖 |
|------|------|------|
| 1 | SettingsManager 扩展 | 无 |
| 2 | AudioRecorderManager | 无 |
| 3 | AIBuildersAudioClient | OkHttp（已有） |
| 4 | AppState + MainViewModel 扩展 | 步骤 1-3 |
| 5 | SettingsScreen Speech section | 步骤 1, 4 |
| 6 | ChatScreen 麦克风按钮 | 步骤 4 |
| 7 | AndroidManifest 权限 | 无 |
| 8 | Unit Tests | 步骤 1-3 |
| 9 | Integration Tests | 步骤 1-3 |

步骤 1-3 互相独立，可以并行实现。步骤 5-6 也可并行。

---

## 附录

### A. iOS 源码参考

| Android 模块 | 对应 iOS 文件 |
|-------------|--------------|
| AudioRecorderManager | `Services/AudioRecorder.swift` |
| AIBuildersAudioClient | `Services/AIBuildersAudioClient.swift` |
| SettingsScreen Speech section | `Views/SettingsTabView.swift` (lines 293-335) |
| ChatScreen 麦克风按钮 | `Views/Chat/ChatTabView.swift` (lines 427-468, 570-632) |
| AppState speech fields | `AppState.swift` (lines 161-323, 1064-1132) |

### B. 服务端参考

| 文件 | 作用 |
|------|------|
| `ai_builder_student_portal/backend/app/routers/audio_realtime.py` | HTTP session 创建 + WebSocket 路由 |
| `ai_builder_student_portal/backend/app/services/speech/realtime_gateway.py` | WebSocket 双向代理核心逻辑 |
| `ai_builder_student_portal/backend/app/services/speech/openai_realtime_service.py` | OpenAI Realtime API 客户端 |
| `ai_builder_student_portal/scripts/replay_realtime_ws.py` | Python 参考客户端实现 |

### C. Brainwave 参考

| 文件 | 参考价值 |
|------|----------|
| `brainwave_mobile/brainwave_android/.../AudioRecorder.kt` | AudioRecord API 用法（我们用 MediaRecorder，但可参考权限处理） |
| `brainwave_mobile/brainwave_android/.../WebSocketManager.kt` | OkHttp WebSocket 生命周期管理 |
| `brainwave_mobile/brainwave_android/.../RecordingManager.kt` | 录音 + WebSocket 流式传输的编排模式 |
