# OpenCode Android Client 开发代码审查

本文聚焦三个方面：明显的设计问题、体量过大的模块、以及测试覆盖空洞。结论先行：项目已经具备可工作的主干能力，但当前代码结构明显偏向“快速堆功能”，核心状态管理和 UI 逻辑耦合较重；如果继续在现有结构上叠加功能，后续维护成本和回归风险都会比较快地上升。

## 一、总体判断

- 优点：项目主路径已经打通，架构层次基本清晰（`data / ui / util / di`），关键网络访问集中在 repository，配置也统一走 `SettingsManager`。
- 风险：聊天、文件预览、设置、语音输入等功能都在持续增长，但状态与副作用还没有被进一步拆分，导致 ViewModel 和 Screen 文件已经承担了过多职责。
- 建议优先级：先拆状态与副作用边界，再补测试；否则每次加功能都会把回归验证压力继续堆到人工测试上。

## 二、明显的设计问题

### 1. `MainViewModel` 责任过多，已经成为事实上的“应用协调器”

文件：`app/src/main/java/com/yage/opencode_client/ui/MainViewModel.kt`

当前 `MainViewModel` 同时负责：

- 服务端连接与初始化
- Session 列表与消息加载
- SSE 事件消费与增量拼装
- 轮询兜底逻辑
- 语音录制与转写流程编排
- 设置读写
- 文件预览跳转状态
- 权限请求响应

这会带来几个直接问题：

- 任意一个功能修改都可能误伤其他状态字段，回归面过大。
- 很多状态更新只能靠 `_state.update { it.copy(...) }` 进行手工维护，长期容易出现字段遗漏或状态竞争。
- UI 层、领域逻辑层、异步副作用层混在同一个类里，测试粒度很难做细。

建议：

- 按功能拆出 `ChatViewModel` / `ConnectionViewModel` / `SpeechCoordinator` / `SessionSyncController` 一类的边界。
- 把 `loadMessagesWithRetry()`、`startBusyPolling()`、`handleSSEEvent()` 这类同步机制抽成独立协作者，避免主 ViewModel 继续膨胀。
- 将“持久化设置”和“运行时会话状态”分离，不要都压在同一个 `AppState` 中。

### 2. `ChatScreen` UI 代码和交互规则高度耦合，可读性与可维护性都在下降

文件：`app/src/main/java/com/yage/opencode_client/ui/chat/ChatScreen.kt`

`ChatScreen.kt` 已经超过 1100 行，且不仅是展示层，还内嵌了大量交互规则，例如：

- 自动滚动策略
- 流式消息的拼装展示
- 工具卡片 / patch 卡片布局策略
- 输入栏高度驱动的按钮布局切换
- 权限弹窗与 speech error 展示

问题不在于“代码长”，而在于“多个变化频率不同的逻辑被绑在一起”：消息渲染、滚动行为、输入栏状态机、工具结果展示，它们后续演进节奏一定不同。

建议：

- 至少拆成 `ChatTopBar`、`MessageList`、`MessagePartRenderer`、`InputBar`、`PermissionBanner` 等独立文件。
- 把自动滚动规则单独抽成状态对象或 helper，避免以后继续在 `MessageList` 内硬编码条件。
- 把 Tool / Patch / Reasoning 的渲染注册表化，而不是继续堆 `when` 和布尔条件。

### 3. `FilesScreen` 同时承担“文件浏览器”和“预览器”两套职责

文件：`app/src/main/java/com/yage/opencode_client/ui/files/FilesScreen.kt`

这个文件目前同时负责：

- 文件树加载
- git status 映射
- 文件内容请求
- 目录兜底预览
- Markdown 预览
- 图片 base64 解码
- 缩放/双击/平移交互
- 本地缓存文件生成与分享

这会导致后面任何一个预览能力增强（PDF、diff、更多二进制类型）都会继续加重同一个文件。现在已经能看到两个方向的逻辑混在一起：资源获取逻辑和 Compose 手势/展示逻辑。

建议：

- 将文件浏览（tree/list）和内容预览（preview/viewer）拆成两个层次。
- 图片预览相关的 `decodeImagePayload()`、`shareImage()`、`ImageViewer()` 可下沉到独立 preview 包。
- 如果后续预览类型继续增加，建议建立 `PreviewContent` sealed model，而不是在 `FileContentViewer()` 内继续扩展 `when` 分支。

### 4. 错误处理策略不统一，且存在静默吞错

代表位置：

- `app/src/main/java/com/yage/opencode_client/ui/MainViewModel.kt` 中的 provider / permission / session 相关异步分支
- `app/src/main/java/com/yage/opencode_client/ui/MainViewModelSessionActions.kt` 中的 session/message 协作分支
- `app/src/main/java/com/yage/opencode_client/ui/MainViewModelSyncActions.kt` 中的 SSE / polling 同步分支

项目里已经有多处 `onFailure { e -> }` 或 `catch (e: Exception) { }` 这种静默忽略的写法。短期看会让界面“不报错”，但长期会让调试和线上排障非常痛苦，因为异常既没有落日志，也没有进入统一状态通道。

建议：

- 至少统一成“记录日志 + 进入可观测错误状态”两步。
- 对 SSE 解析失败、provider 加载失败这类非阻塞错误，建议放到 debug 日志或轻量提示里，而不是完全吞掉。
- 建立简单的 error taxonomy：网络错误、解析错误、权限错误、用户输入错误。

### 5. 业务规则中仍有较多硬编码常量，后续调参成本高

代表位置：

- `MainViewModel.kt` 中消息加载延迟 `400ms`、补刷 `1200ms`
- 轮询间隔 `2000ms`
- `ChatScreen.kt` 中输入栏纵向布局阈值 `112.dp`
- `AIBuildersAudioClient.kt` 中 chunk size、超时、silence duration 等常量

这些值不是不能硬编码，而是目前缺少“配置归属”。一旦后续根据机型、网络、服务端行为做调优，维护者很难判断哪些值可以改、改完会影响什么。

建议：

- 至少把这些参数收敛到常量对象或配置类中。
- UI 阈值和网络/音频参数不要散落在 Screen 与 Client 内部。

## 三、体量过大的模块

### 1. `ChatScreen.kt` 过大，建议最高优先级拆分

文件大小约 1110 行，是当前最明显的维护瓶颈。这个文件未来还会继续长，因为聊天本身就是功能密度最高的页面。建议优先拆分，否则后续任何改动都很难做小范围 review。

建议拆分方向：

- `ChatScreen.kt` 只保留页面装配
- `ChatTopBar.kt`
- `MessageList.kt`
- `MessageCards.kt`
- `ChatInputBar.kt`
- `PermissionCard.kt`

### 2. `MainViewModel.kt` 过大，且比纯 UI 大文件更危险

文件大小约 737 行。相比大 Compose 文件，超大的 ViewModel 风险更高，因为它持有状态、网络、副作用和用户动作入口。一旦这里出现竞态或状态覆盖，问题往往不是“局部 UI 不对”，而是整条功能链出错。

建议拆分方向：

- 连接初始化
- 会话与消息同步
- 语音输入编排
- 设置持久化
- 文件跳转与辅助 UI 状态

### 3. `FilesScreen.kt` 与 `SettingsScreen.kt` 已接近下一阶段拆分阈值

- `FilesScreen.kt` 约 510 行
- `SettingsScreen.kt` 约 473 行

这两个文件还没有到必须立即重构的程度，但已经接近“再加 2~3 个功能就会失控”的边缘。特别是 `SettingsScreen.kt` 现在把服务端配置、外观设置、语音设置、关于页全部写在一个大滚动表单里，后续如果增加高级网络配置、调试开关、日志开关，会很快失去结构性。

建议：

- `SettingsScreen` 至少拆成 `ConnectionSettingsSection`、`AppearanceSection`、`SpeechSettingsSection`、`AboutSection`。
- `FilesScreen` 至少拆成 `FileBrowserPane` 和 `FilePreviewPane`。

## 四、测试覆盖缺口

当前测试的优点是：已经覆盖了一些纯函数和基础模型，包括：

- URL / token / websocket URL 处理
- 音频重采样
- 一部分 repository 基础请求
- AppState / SessionTree / FilePreviewUtils 等 helper

但从风险暴露角度看，最该测的地方还没被真正覆盖。

### 1. `MainViewModel` 几乎没有针对性测试

这是当前最大的测试空洞。高风险但缺测的行为包括：

- `sendMessage()` 成功/失败后的状态迁移
- `selectSession()` 切换时消息重置与重新加载
- `handleSSEEvent()` 对不同事件的处理
- `message.part.updated` 的增量拼装逻辑
- `session.status` 从 busy 到 idle 时的清理与补刷逻辑
- `toggleRecording()` 在录音 / 转写 / token 缺失 / connection 未通过等分支下的行为

建议优先补 ViewModel 单元测试，并对 repository / recorder 做 fake 或 mock 注入。

### 2. Compose UI 测试明显不足

虽然有少量测试文件，但没有看到针对核心交互页面的系统性 Compose UI 测试。高价值缺口包括：

- `ChatScreen` 输入、发送、停止、录音按钮状态
- 长文本输入时横排/竖排按钮切换
- 自动滚动 / 非自动滚动行为
- `FilesScreen` 文件点击、Markdown 预览、图片预览入口
- `SettingsScreen` 保存与测试连接流程

这些地方目前主要依赖人工回归，随着功能增多会越来越脆弱。

### 3. `OpenCodeRepositoryTest` 覆盖面偏窄

文件：`app/src/test/java/com/yage/opencode_client/OpenCodeRepositoryTest.kt`

当前只覆盖了：

- 默认地址
- health
- sessions
- agents

但 repository 实际承担的 API 远不止这些。至少还缺：

- `sendMessage()` 的请求体与错误信息拼装
- `getMessages()`、`getProviders()`、`getFileTree()`、`getFileContent()`
- Basic Auth header 是否正确带上
- `configure()` 后 client 重建是否生效

### 4. 图片预览链路只有工具函数测试，没有行为测试

当前新增了 `FilePreviewUtilsTest.kt`，这是好的开始，但仍不足以覆盖真正复杂的地方。高风险缺口包括：

- 图片 base64 解码失败时的回退行为
- 分享流程是否生成正确 MIME type
- 非图片文件是否误走图片分支
- 超大图片或非法 payload 的处理

### 5. 集成测试存在，但更像连通性验证，不足以承担回归防线

项目里已有 `androidTest`，但从当前结构看，它更偏向端到端连通性验证，而不是稳定的功能回归网。依赖真实环境凭证的测试天然更难频繁运行，因此不能替代可本地稳定执行的单元测试和 UI 测试。

建议将测试策略分成三层：

- 纯函数 / 状态机：本地单元测试
- ViewModel / repository 协作：mock/fake 驱动的 JVM 测试
- 关键主路径：少量稳定的 instrumentation 测试

## 五、落地策略：先补测试，再做拆分

这次工作不建议直接进入重构，而应该分成两个大阶段推进：

- 第一阶段先建立回归防线，让后续重构具备可验证性。
- 第二阶段再拆模块，并且每拆一块就跑一次测试，避免一次性大手术。

这个顺序的核心原因很简单：当前代码的主要风险不只是“结构不优雅”，而是“状态同步、异步副作用、UI 交互规则混在一起”。如果没有足够稳定的测试，后续任何拆分都可能在很远的地方引入回归，而且很难第一时间定位。

### 阶段一：先把测试补到能作为回归防线

这一阶段的目标不是追求覆盖率数字本身，而是建立一组能保护后续重构的高价值测试。

#### 5.1 阶段目标

- 覆盖最容易在重构中被破坏的状态机与交互链路。
- 把“纯函数测试为主”的现状，提升到“状态协作测试 + 关键 UI 测试 + 少量稳定集成测试”的组合。
- 为后续每一轮拆分定义明确的通过门槛。

#### 5.2 测试优先级排序

建议按下面顺序补，而不是平均用力：

1. `MainViewModel` 单元测试
2. `OpenCodeRepository` 扩展测试
3. `ChatScreen` / `FilesScreen` / `SettingsScreen` 的 Compose UI 测试
4. 图片预览链路行为测试
5. 少量稳定的 instrumentation smoke tests

原因是：`MainViewModel` 和 `ChatScreen` 是后续重构风险最高的地方，优先级必须高于其他模块。

#### 5.3 阶段一建议拆成四个子阶段

##### 子阶段 A：补 `MainViewModel` 的行为测试

目标：先把最大风险点变成可验证状态。

建议覆盖的用例集合：

- `sendMessage()` 成功后：输入框清空、错误清理、消息刷新触发。
- `sendMessage()` 失败后：错误态写入，不清空输入。
- `selectSession()` 后：`currentSessionId`、`messages`、`messageLimit` 的变化。
- `handleSSEEvent()` 对 `session.created`、`session.status`、`message.created`、`message.part.updated`、`permission.asked` 的处理。
- `session.status` 从 busy -> idle 时是否清掉 streaming 状态并触发补刷。
- `toggleRecording()` 在以下分支下的行为：开始录音、停止录音、token 缺失、connection 未通过、转写中禁止重复触发、转写成功、转写失败。

落地前提：

- `OpenCodeRepository` 和 `AudioRecorderManager` 需要更容易 fake / mock。
- 必要时把时间相关逻辑（`delay(400)`、`delay(1200)`、轮询）抽成可注入 dispatcher 或 timing policy，避免测试不稳定。

##### 子阶段 B：补 repository 协作测试

目标：保证网络层改动不会悄悄改坏请求形状和认证逻辑。

建议补的内容：

- `sendMessage()` 的请求体结构。
- 非 2xx 响应时错误信息是否保留状态码和错误体。
- `configure()` 后 base URL 和 Basic Auth header 是否生效。
- `getMessages()`、`getProviders()`、`getFileTree()`、`getFileContent()` 的基本解析路径。

这里的价值在于：后续如果 ViewModel 和 UI 拆分时顺手调整 repository 接口，测试会第一时间告诉我们 contract 有没有漂移。

##### 子阶段 C：补高价值 Compose UI 测试

目标：把最脆弱、最容易回归的交互规则固化下来。

建议优先测：

- `ChatScreen`：发送按钮、停止按钮、录音按钮在不同 state 下的可用性。
- `ChatScreen`：输入框达到四行后按钮横排/竖排切换。
- `ChatScreen`：自动跟随滚动与非自动跟随滚动的行为边界。
- `FilesScreen`：点击文件、展示 Markdown、图片文件进入图片预览分支。
- `SettingsScreen`：保存设置、测试连接按钮状态、token 显示/隐藏。

这里不要求一开始就做到很细，但至少要把“已经被产品明确讨论过的交互规则”先锁住。

##### 子阶段 D：补少量稳定集成测试

目标：不是扩大数量，而是保留少量端到端烟雾验证，证明主路径还通。

建议只保留少数几个关键路径：

- 连接服务端并拉取 session 列表
- 发送一条消息并收到响应
- 打开文件并看到预览

不要把大量业务回归压在 instrumentation 上，否则运行成本会太高，团队很容易逐渐不跑。

#### 5.4 阶段一的完成标准

只有满足下面这些条件，才建议进入模块拆分：

- `MainViewModel` 的关键行为已经有稳定单元测试覆盖。
- `OpenCodeRepository` 的主要请求路径已经覆盖。
- Chat / Files / Settings 至少各有一组高价值 Compose UI 测试。
- 关键 smoke test 可以稳定在 CI 里通过。
- 当前测试不依赖大量人工等待或脆弱的时间窗口。

换句话说，阶段一的产出应该是一张“可以放心重构”的安全网，而不是简单的 coverage 数字上涨。

### 阶段二：在测试护栏下拆分模块

阶段二的原则不是“一口气重写”，而是“小步重构、每步可回退、每步可验证”。

#### 5.5 拆分原则

- 一次只拆一个高耦合热点，不做并行大改。
- 每次拆分都优先做“边界抽离”，而不是顺手改一堆行为。
- 每一步拆分后都跑完整的本地测试和 CI 测试。
- 如果某一步拆分让测试大面积变脆，先修测试稳定性，再继续拆。

#### 5.6 建议的拆分顺序

建议顺序如下：

1. 先拆 `MainViewModel`
2. 再拆 `ChatScreen`
3. 然后拆 `FilesScreen`
4. 最后整理 `SettingsScreen`、错误处理、硬编码常量

这个顺序不是按文件长度排，而是按“对全局状态与回归风险的影响面”排序。

##### Step 1：拆 `MainViewModel`

优先把以下能力抽出去：

- SSE 事件处理
- busy polling
- speech orchestration
- connection bootstrap / initial loading

目标不是立刻拆成很多 ViewModel，而是先把副作用和同步机制从主状态容器里挪出去。只要这一层清了，后续 `ChatScreen` 的改造难度会明显下降。

##### Step 2：拆 `ChatScreen`

建议先按 UI 结构切，再考虑更深的渲染模型抽象：

- 顶栏
- 消息列表
- 消息卡片渲染
- 输入栏
- 权限/错误相关展示

先拆文件边界，确保行为不变；等边界稳定以后，再考虑把自动滚动规则、part 渲染策略进一步抽象。

##### Step 3：拆 `FilesScreen`

建议先分成：

- 文件浏览层
- 文件内容预览层
- 图片预览能力层

这样后续如果加 PDF、diff、更多文件类型，新增复杂度就不会继续压回一个文件。

##### Step 4：收口 `SettingsScreen`、错误处理与常量管理

这是最后做的原因：它们重要，但回归风险没有前面两个核心模块高。

建议这一轮顺手完成：

- `SettingsScreen` 分 section
- 静默吞错改成统一可观测策略
- timing / audio / UI 阈值常量收敛到统一配置位置

#### 5.7 阶段二的每步执行模板

每次实际拆分都建议遵循同一个模板：

1. 选定一个目标模块
2. 先确认相关测试已经存在并通过
3. 只做一类结构性移动，不混入行为改动
4. 跑单元测试
5. 跑 UI 测试
6. 跑 CI
7. 再进入下一块

这个模板的价值在于：把重构从“大项目”降维成一串小步操作，每一步都能定位收益和风险。

### 阶段一与阶段二之间的交接物

为了避免阶段切换时目标变模糊，建议在阶段一结束时输出一份更短的实施清单，至少包含：

- 已经具备测试保护的模块清单
- 仍然缺测试的高风险点
- 推荐的首个拆分目标
- 每个拆分目标对应的验证命令

这样阶段二就不需要再重新讨论“从哪里下手”，可以直接进入执行。

### 阶段切换门槛与回退原则

为了避免计划在执行中失焦，建议提前约定阶段门槛和回退规则。

#### 进入阶段一之前

- 先确认当前 `main` 或 feature branch 上的测试命令、CI 命令、构建命令是可重复执行的。
- 先记录一份基线：哪些测试当前已通过，哪些测试本来就不稳定，哪些问题是已知债务。
- 如果基线本身不稳定，先做“测试基建修复”，不要直接补新测试。

#### 从阶段一进入阶段二的门槛

- 新增测试已经进入 CI，而不是只在本地偶尔跑。
- 关键测试失败时，能够较快定位到模块级别，而不是只能知道“App 挂了”。
- 至少第一批拆分目标（`MainViewModel`、`ChatScreen`）已经有对应保护测试。

#### 阶段二中的回退原则

- 每次重构只做一个原子目标，保持单个 PR / 单组提交可回退。
- 如果某次拆分同时引入了行为改动和结构改动，应立即拆开处理；不要混在一次提交里继续推进。
- 如果某一步导致测试大量失稳，不继续向前拆，先恢复测试稳定性。
- 如果某次拆分后问题难以定位，优先回退该步，再缩小拆分粒度重新做。

#### 每一步的验证门槛

建议每一步至少执行并记录：

- 相关 JVM 单元测试
- 相关 Compose UI 测试
- `./gradlew testDebugUnitTest`
- CI 工作流通过

如果某一步只是文档或计划更新，可以不跑全量测试；但一旦进入真实重构阶段，验证门槛就不应该再放松。

## 六、建议的近期执行顺序

如果只看接下来最实际的推进方式，我建议按下面的顺序开展：

1. 先设计阶段一的测试补强范围与测试基建策略。
2. 先补 `MainViewModel` 测试，再补 repository 测试。
3. 然后补 `ChatScreen` / `FilesScreen` / `SettingsScreen` 的高价值 UI 测试。
4. 等回归防线稳定后，再开始拆 `MainViewModel`。
5. 随后拆 `ChatScreen`，再拆 `FilesScreen`。
6. 最后一轮统一处理 `SettingsScreen`、错误处理和常量收口。

## 七、结论

这个项目当前的主要问题不是“代码质量很差”，而是“核心模块正在变成多功能聚合点”，如果不尽快做边界拆分，后续开发速度会先快后慢，并且 bug 会越来越偏向状态同步和交互回归这类难排查问题。

换句话说，现在是一个很适合做第一次工程化收口的时间点：功能已经证明方向可行，但结构还没完全固化，重构成本仍然可控。越往后拖，代价越高。

## 八、本轮执行清单（Refactor Phase 2）

以下清单用于把前面的审查结论真正落地。原则是：每完成一个大项就跑测试，并在 `docs/working.md` 记录一次进度。

### A. `MainViewModel` 拆分与收口

- [x] 抽离连接初始化与首屏加载逻辑
- [x] 抽离 session/message 同步逻辑（含 retry / polling / SSE fallback）
- [x] 抽离 speech orchestration，缩小 `MainViewModel` 对录音/转写细节的直接掌控
- [x] 收口 `MainViewModel` 中的硬编码 timing 常量
- [x] 统一非阻塞错误处理，替换静默吞错

### B. `ChatScreen` 文件拆分

- [x] 抽出顶栏相关组件
- [x] 抽出消息列表与自动跟随滚动逻辑
- [x] 抽出消息卡片 / part renderer
- [x] 抽出输入栏与操作按钮布局逻辑
- [x] 抽出权限/错误展示组件

### C. `FilesScreen` 文件拆分

- [x] 抽出文件浏览区
- [x] 抽出文件内容预览区
- [x] 抽出图片预览与分享能力
- [x] 收口 preview 分支，避免继续堆叠 `when` 逻辑

### D. `SettingsScreen` 与常量/错误治理

- [x] 按 section 拆分 Settings 页面
- [x] 抽出复用表单行/设置块，降低主文件密度
- [ ] 将 UI / timing / audio 常量收敛到统一配置位置
- [x] 清理剩余静默错误处理，统一日志与状态策略

### E. 验证与交付

- [x] 视需要补测试，确保拆分过程中新增边界有回归保护
- [x] `./gradlew testDebugUnitTest`
- [x] `./gradlew koverHtmlReport`
- [x] 更新 `docs/working.md`
- [x] 提交、推送并创建新的 refactor PR

## 九、Post Phase 2 执行结果（Phase 3 完成）

这一步的目标不是再做一轮大规模拆文件，而是把 refactor 的最后几个工程化遗留项收掉：常量归位、关键 UI 护栏测试、以及对剩余热点文件做最小但有价值的结构整理。现在这几个目标已经完成。

### 1. 常量归位已完成

当前 `SettingsScreen` / `FilesScreen` / `ChatScreen` / `MainViewModel` 的结构性拆分已经完成，最明显的遗留项是常量仍然分散在 UI 层和音频层：

- `ChatInputBar.kt` 中的输入栏布局阈值
- `AIBuildersAudioClient.kt` 中的 chunk size / timeout / silence duration
- `AudioRecorderManager.kt` 中的采样率 / 编码参数

实际落地：

- 在 `ui/chat/ChatUiTuning.kt` 中集中 Chat 输入区与 top bar 相关阈值
- 在 `data/audio/AudioTranscriptionConfig.kt` 中集中录音与转写参数
- 保持“UI 常量靠近 UI、音频常量靠近 audio”的边界，没有做全局杂物箱

### 2. 剩余热点文件只做了有回报的结构整理

Phase 2 之后，最大的两个剩余热点不再是顶层 screen，而是：

- `ChatMessageContent.kt`
- `FilePreviewPane.kt`

这两个文件现在已经比原来清晰很多，但仍然承担了较多变化频率不同的逻辑。本轮没有为了“文件更短”继续硬拆，而是只做了一个有测试价值的整理点：

- 将 `FilePreviewPane.kt` 的 preview 分流判断下沉到 `FilePreviewUtils.previewContentKind(...)`
- 这样把 preview 路由逻辑从 Composable 中拿出来，后续继续拆 image / markdown / text preview 时更容易加测试和 review

判断标准保持不变：只有当新的边界能降低 review 面积、减少回归面时才拆；如果只是为了让文件更短，可以先不做。

### 3. UI 层护栏已经补上第一批高价值测试

当前 JVM 层的回归护栏已经足够支撑这轮 refactor，本轮新增的 UI / instrumentation 测试把最值得固化的交互规则补上了：

- Chat 输入区在 busy / ready 状态下的 send / stop / mic 可用性
- 输入区横排/竖排切换阈值（转成纯函数 + JVM 测试）
- Settings 语音识别 section 的按钮可用性与成功态展示
- connected Android tests 在未配置或不可达环境下自动 skip，避免把“机器状态”误当成代码回归

这一步的意义不是“补测试债”，而是给后续 UI 微调提供稳定护栏，避免每次改布局都要靠人工回归。

### 4. 本轮验证结果

- `./gradlew testDebugUnitTest` 通过
- `./gradlew assembleDebug` 通过
- `./gradlew assembleDebugAndroidTest` 通过
- `./gradlew koverHtmlReport` 通过
- `./gradlew connectedDebugAndroidTest` 通过（外部服务不可达的 integration case 自动 skip）

### 5. 结论

到这一步为止，这轮 refactor 可以视为基本结束：

- [x] 收口 UI 常量（输入栏阈值、菜单尺寸、预览相关阈值）
- [x] 收口音频/转写参数（chunk size、timeout、sample rate、silence duration）
- [x] 为 Chat 输入区补一组 Compose UI / instrumentation 测试
- [x] 为 Settings 补一组高价值 Compose UI / instrumentation 测试
- [x] 评估剩余热点文件，并只对 `FilePreviewPane.kt` 做了值得保留的结构整理

后续如果还继续做，不应再按“大型重构项目”来推进，而应转回普通迭代：哪里新增功能或发现热点，哪里局部优化、局部补测试。
