<!--
HANDOFF.md — memo-widget Android app 的 AI 接手文档（AI-facing handoff）。
受众：下一个接手此仓库的 AI 会话 / Agent。
优化目标：零歧义、低 token 扫描、命令可复制粘贴执行。
面向人类的文档在 README.md 和 USER_GUIDE.md — 不要在本文件里重复那些内容。
-->

# HANDOFF（AI 接手版）

> 给下一个会话的机器可读上下文。下文出现的所有绝对路径、命令、标识符都是字面值。
> 如果本文件与仓库实际状态冲突，以仓库为准 — 然后回来修这份文件。

上次重新生成：**2026-04-24**，基于 `feature/p3-polish` 分支 P8 Widget 重做 + 自动刷新。当前 release：v0.12.1-p8（P7 为 v0.11.0-p7；P6.1.1 为 v0.10.1-p6.1.1）。

## ✅ P8 已完成（Widget 重做 + 全链路自动刷新）

1. **MemoWidget 形态重做**：从"今天最多 3 条固定快照"改为"最近 20 条可滚动列表"。`observeRecent(limit=20)` / `recentEntries(limit=20)`，`LazyColumn` 渲染最多 20 行，用户把 widget resize 到 4×4 可多看。默认尺寸 3×3 cell。
2. **TitleBar actions 新增 🔄 手动刷新按钮**：Memo widget + Today widget 顶部都加了一个 Glance `SquareIconButton`，点击 → `RefreshActionCallback` → `MemoWidget().updateAll(context)` + `TodayWidget().updateAll(context)`。解决"自动刷新偶然失败时用户没法强制触发"。
3. **WidgetRefresher 新模块**（`data/widget/WidgetRefresher.kt`）：object + `SupervisorJob + Dispatchers.Default` 的 fire-and-forget 触发器，`refreshAll(context)` 非阻塞、`refreshAllNow(context)` 阻塞给测试用；两个分支都 `runCatching` 包 `MemoWidget().updateAll` / `TodayWidget().updateAll`，保证"widget 刷新失败永远不冒泡到写路径"。接口意图见 `/tmp/WIDGET_P8_SPEC.md` 第 29-79 行。
4. **全链路 hook 接入**（Agent 2 实施）：以下成功路径末尾全部追加 `WidgetRefresher.refreshAll(context)` 调用：
   - `MemoRepository.appendToday` / `updateTodayBody` / 其余写方法
   - `SingleNoteRepository.create` / `update` / `delete` / `togglePin` / `togglePinByPath`（含 NOT_CONFIGURED→Ok 路径，P6.1.1 #57 修复分支也会触发 refresh）
   - `PullWorker.doWork()` 在 `Result.success()` / `Result.retry()` 之前
   - `PushWorker.doWork()` 在 `Result.success()` 之前
   - `SettingsStore` 的 owner/repo/pat 变更路径（isConfigured 状态从未配置→已配置时 widget 要立刻重绘）
   - `AppBootObserver` / `MainActivity.onCreate`（冷启动兜底）
5. **`ServiceLocator.appContext` 字段**：Repository 本身不持有 Context（DI 洁癖），通过 `ServiceLocator.appContext`（在 `init(context)` 里赋值 `context.applicationContext`）拿；所有 hook 点统一这种方式取 Context。
6. **Debounce 250ms**：快速连续写入（比如批量 import / pull 大批新笔记）时，WidgetRefresher 的触发会被合并成一次 UI 更新，避免桌面闪烁。
7. **BDD 扩展**（Agent 4 实施）：场景 #54-#80 新增 27 条（widget 自动刷新 10 条 + 列表展示 10 条 + 交互 7 条），总计约 **90 条** scenario（原 53 + 新增 ~27-37，以 BDD_SCENARIOS.md 实际数为准）。
8. **版本**：versionCode 14 / versionName `0.12.1-p8`。测试 ≈240 项，详见 §6。



## ✅ P7 已完成（AI 问答集成）

1. **数据层**：`data/ai/AiConfig.kt` / `AiClient.kt` / `AiSettingsStore.kt` / `AiContextBuilder.kt` / `AiDto.kt`（OpenAI-compatible protocol；`stream=false` MVP；401/403 → UNAUTHORIZED、5xx → 通用 UNKNOWN 不回显 body）。API key 存 EncryptedSharedPreferences（同 PAT 策略），绝不 log。
2. **UI**：`ui/ai/AiChatScreen.kt`（LargeTopAppBar + FilterChip 三段 context mode + 消息气泡 + 未配置态 MemoEmptyState + Snackbar）+ `AiChatViewModel.kt`（sum type UiState、multi-turn transcript ephemeral 不持久化、firstOrNull 优雅降级、try/catch 包住所有 repo 调用）
3. **导航入口**：NoteListScreen 顶栏 `Icons.Filled.Psychology` AI 按钮（tab 级，noteUid=null）+ SingleNoteRow 长按 DropdownMenu "问 AI"（带 noteUid，CURRENT_NOTE mode）
4. **Settings 扩展**：3 个 OutlinedTextField（URL / API Key 掩码 / Model）+ "保存 AI 配置" + "测试连接"（发 `ping` 请求 snackbar 反馈）+ FLAG_SECURE 扩展到 `patVisible || aiKeyVisible`
5. **Repo 改造**：`MemoRepository` / `SingleNoteRepository` 改为 `open class` + nullable ctor 支持 test fake（tech-debt，P7.1 抽 interface façade 见下）
6. **Review 三路 + 16 issues 闭环**：#60-75（1 High + 10 Medium + 5 Low）全修，#64 interface façade 延期 P7.1
7. **版本**：versionCode 12 / versionName `0.11.0-p7`

### P7.0.1 Review fix wave（本轮随 v0.11.0-p7 一起发）

| # | 严重度 | 摘要 |
|---|---|---|
| #60 | **High** | `AiChatViewModel.send()` ALL_NOTES 下 `Flow.first()` 抛 NoSuchElementException 逃逸 → 改 `firstOrNull()` + 整 send 包 try/catch |
| #61 | Medium | Fake observe() override 统一（Flow live 语义，否则 isConfigured 门禁永失效）|
| #62 | Medium | AiSettingsStore.observe() null-ctx 路径改 `flow { emit; awaitCancellation }` |
| #63 | Medium | AiClient 不再把 4xx 响应 body 拼进 snackbar（防用户 prompt PII 泄漏）+ 5xx 独立分支 |
| #65 | Medium | versionCode/Name bump |
| #66 | Medium | AiClientTest 加 403 / 500 映射 case |
| #67 | Medium | AiClientTest 加 3 条 apiKey 不泄漏 regression（401/serialize/network）|
| #68 | Medium | AiChatViewModelTest 加多轮 transcript 积累断言 |
| #69 | Medium | BDD 补场景 51（多轮）/ 52（429）/ 53（长按入口）|
| #70 | Medium | HANDOFF drift 修（即本次）|
| #71 | Low | SingleNoteRow 长按 DropdownMenu "问 AI" 入口（+ AppNav 透传 noteUid）|
| #72 | Low | send() priorMessages 注释误导修 |
| #73 | Low | AiContextBuilderTest 加 "笔记" header 常量断言 |
| #74 | Low | hasCurrentNote 负向断言（initial state）|
| #75 | Low | BDD 场景 48 明确 "尾截断" 方向 + QA 避免构造末段 case |

## ✅ P6.1.1 已完成（deferred bug 清零 + CI/CD 首次引入）

1. **Fix #57 数据不丢**：`SingleNoteRepository.create` 在 PAT 未配置时先写 Room（dirty=true）+ SyncStatusBus emit"已存本地 · 待配置"，返 Ok。PushWorker 在配好 PAT 后自动推。修复首装用户写笔记直接丢的 pre-existing bug。
2. **Fix #51 LIMIT 下推**：`MemoRepository.recentEntriesAcrossDays` 改走 `dao.observeRecent(limit * 2 + 1)`，不再全表读。极端稀疏 fallback 到全表保证正确性。
3. **Fix #56 分层归位**：`MemoRepository.getContentForPath(path)` 薄方法；`EditViewModel.prime()` 和 `toggleChecklist` 的 `ServiceLocator.noteDao()` 直访全部换成 `ServiceLocator.repository.getContentForPath`。UI → Repository → DAO 分层恢复。
4. **CI/CD 首次配置**：`.github/workflows/ci.yml`，每次 push/PR 跑 `compileDebugKotlin + testDebugUnitTest`。之前项目**从未有过 CI**——所有发版都是本地 build+test。从 P6.1.1 起 GitHub 上每次 push 都有绿灯 gate。
5. **BDD 扩 3 条**：场景 41（首装写笔记不丢）/ 42（LIMIT 下推）/ 43（Repository 分层守卫）。

## ✅ P6.1 已完成（本轮闭环）

1. **UI 集成 SingleNoteRepository** ✅ — NoteListViewModel sum type `LegacyDay | SingleNote`；NoteListScreen when 分派 + MemoCard 双色 accent（绿 legacy / 紫 single）；EditActivity 读 `EXTRA_NOTE_UID` 路由 edit/create；MemoWidget 先 `observeRecent(3)` 再 legacy fallback。
2. **UI 视觉大改版**（新增）✅ — 全局 tertiary 蓝紫色盘、LargeTopAppBar + 滚动联动、Markdown 工具栏 10 按钮、字符数+行数统计、FilterChip 编辑/预览、ScrollAwareFab 收缩（笔记页 + 日历页联动 top bar）、MemoEmptyState 插画空态、AnimatedContent tab 切换。
3. **数据层债务 4/5/6/7** ✅ — `FrontMatterCodec` 抽离、`NoteDao.observeRecent(limit)` LIMIT 下推（widget 用）、`CalendarViewModel.mutating` 改 `AtomicBoolean.compareAndSet`、`PullWorker` 全局 `PullBudget(cap=150)` 四段共享。
4. **SingleNoteRepository 补 PathLocker** ✅ — 补 create/update/delete/togglePin 的写路径锁（复刻 MemoRepository.appendToday 在 issue #6 前缺的同一把锁）；togglePin 改直调 `FrontMatterCodec.applyPin`。
5. **Review 三路 + 20 issues 开闭环** ✅ — 本轮关闭 #40-59；179 测试全绿（旧 170 + 新 9）。

## 🟡 P6.2 下一轮（deferred）

1. **"一键迁移"按钮**：SettingsScreen 加按钮，把 `YYYY-MM-DD.md` 的每个 `## HH:MM` 块拆成 `notes/YYYY-MM-DD-HHMM-slug.md`，原文件 tombstone。
2. **Room MigrationTestHelper**：v1→v8 迁移链 instrumented 测试（目前只验证了前 8 段跳运行时）。
3. **EditViewModel 3-arg legacy test 构造器迁移**：`DoubleTapSaveTest` 挪到新 4-deps 构造（对齐 `EditViewModelSingleNoteTest`），然后删已标 `@Deprecated` 的 adapter。
4. **LegacyDay 排序精细化 OR 一键迁移**：LegacyDay 时间塌陷 MIDNIGHT，single-note 09:00 排在 legacy 23:00 上方。P6.2 要么按 `group.entries.maxOf { time }` 重算，要么推用户走一键迁移。见 BDD 场景 40 + issue #49。
5. **Markor fork 评估**：若要吸收 Markor Markdown 语法高亮等成熟度，评估最小化 fork 成本（Apache 2.0；XML View 栈不是 Compose，需桥接或逐步迁移）。
6. **instrumented UI test（CI 扩展）**：在 CI workflow 加 emulator-based 流程跑 BDD 核心 P0 场景；目前 CI 只覆盖 unit test。

> P6.1.1 移除条目（已修）：~~legacy `recentEntriesAcrossDays` LIMIT 下推~~ · ~~EditViewModel.prime() 直访 DAO~~ · ~~首装未配 PAT 写笔记 data-loss~~

## 🆕 P8.1 下一轮（deferred）

### 从 P7 / P7.1 继续 deferred

1. **Repo interface façade**（issue #64，三 reviewer 共识）：`MemoRepository` / `SingleNoteRepository` 当前是 `open class + nullable ctor + by lazy requireNotNull` 模式，仅为 test fake 友好。延迟 NPE 爆点 + 封装破坏。P8.1 抽 `MemoRepositoryReadApi` / `SingleNoteReadApi` interface，real repo 和 fake 分别实现。
2. **AI 流式响应（SSE）**：当前非流式，每次要等完整 response。P8.1 接 SSE `stream=true`，逐 token 追加 assistant 气泡。
3. **AI 会话持久化**：当前 ephemeral（杀进程就没）。P8.1 加 `chat_sessions` Room 表，支持跨启动恢复 + 多会话切换。
4. **429 rate-limit 映射**：见 BDD 场景 52 — 当前 429 走 `UNKNOWN` 默认分支。P8.1 加 `ErrorCode.RATE_LIMITED` + 指数退避建议文案。
5. **"AI 改写" action**：P7 只支持 AI 回答（只读）。P8.1 加 "把 AI 回答应用到当前笔记" diff 预览 + 保存按钮。
6. **i18n**：字符串全硬编码中文（与项目既有风格一致）。P8 没做字符串提取；下一个大版本统一提取 strings.xml。

### P8 新增 deferred

7. **widget 底部"最后更新 HH:mm"标签**（spec §MemoWidget 新形态 表格最后一行）：P8 先做了核心的 🔄 按钮 + 自动刷新，"最后更新时间戳" 延后到 P8.1（需要一个 Preferences key 存上次 refresh 的 epoch 并在 UI 渲染时比对）。
8. **widget inline 编辑**：当前点条目必须进 app 才能改。P8.1 评估是否做桌面直接编辑（Glance 对 TextField 支持有限，可能得做 RemoteViews hack 或延后到 Compose Widget 更成熟）。
9. **WidgetRefresher backoff / coalescing**：P8 做了 250ms debounce 防闪烁，但极端场景（bootstrap 一次性 pull 200 条笔记）仍会触发 200 次 `refreshAll` 堆积在 SupervisorJob scope 里。P8.1 加 `Mutex` 或 `conflate()` 合并。
10. **widget 交互扩展 (#80)**：BDD 场景 80 预留了 widget 长按行为但未实现。可选：长按 → 弹菜单（置顶 / 删除 / 复制）。
11. **widget instrumented test**：GlanceAppWidgetManager 仅能在 instrumented test 跑真实 widget；P8 的 widget UI 逻辑在 unit test 里只能测 data source。P8.1 加一个 emulator-based instrumented test 验证 refresh 路径端到端。

---

## 🧾 P8 已知 issue

<!--
锚点：Agent 6（review agent）review 完成后，把 High / Medium / Low 的 issue 汇总填到这里。
格式参考 §P7.0.1 Review fix wave：# / 严重度 / 摘要 三列表格。
未修的条目链接到 GitHub issue；当场修的条目标注 "已在 P8.0.1 fix wave 修复"。
-->

_待补（Agent 6 review 产出后填入）_

---

## 1. 一句话摘要（TL;DR）

- **是什么**：单 Activity 的 Android app（Compose + Glance）— 个人备忘 + 日程。
- **后端**：GitHub Contents API（REST，单用户单仓库）。无服务器，无云端，除 PAT 之外无鉴权。
- **GitHub 上的存储格式**：按天文件 `YYYY-MM-DD.md`（备忘，markdown）+ 每事件一份 `.ics`（iCalendar，RFC 5545 子集）。
- **UX 模型**：offline-first（离线优先）。UI 只读 Room，同步是后台对账。
- **单用户、无协作、无冲突 UI** — last-write-wins，409 时刷新 SHA。

---

## 2. 当前状态（ground truth，以此为准）

| 字段 | 值 |
|---|---|
| 最近发布的 release | `v0.12.1-p8`（tag，2026-04-24） |
| Release 来源分支 | `feature/p3-polish`（tag 从这里打，**不是** `master`） |
| 当前分支 | `feature/p3-polish` |
| HEAD SHA | 见 `git rev-parse HEAD`（P8 Widget 重做收口提交） |
| `master` 分支 | **陈旧**。停在 `98724d1`（P1 之前）。不要从它合并、不要从它 pull、不要从它打 release。 |
| `versionCode` / `versionName` | `14` / `0.12.1-p8`（`app/build.gradle.kts:17-18`） |
| 工具链 | Kotlin 2.0.10 · AGP 8.7.3 · JDK 17 · compileSdk/targetSdk 35 · minSdk 26 |
| Room schema | `v8` — P8 未新增表（widget 刷新不触表结构；ephemeral 对话仍不持久化，持久化见 P7.1 TODO #3）。schema 导出在 `app/schemas/dev.aria.memo.data.local.AppDatabase/{1..8}.json`。 |
| 单元测试 | **≈240 项** passed · 0 failed（P7 基线 220 + P8 新增 WidgetRefresher / MemoWidgetDataSource limit=20 / SingleNoteRepo/PullWorker/PushWorker hook 点测试约 20 项；以 `./gradlew :app:testDebugUnitTest` 实际输出为准）。 |
| **CI/CD** | `.github/workflows/ci.yml`（P6.1.1 首次引入）— push/PR 自动跑 compile + unit test，gate 在 GitHub 侧。 |
| GitHub issues | **0 个 open** · 75 个 closed（P8 review 产出的 issue 编号从 #76 起，见 Agent 6 review 结果）。 |
| 工作树状态 | 见 `git status --porcelain`。P8 release 后若 DIRTY 则说明 P8.1 已经开工。 |

用这段命令重新核对：

```bash
git -C /Users/aria-score-00/AndroidProjects/memo-widget status --porcelain
git -C /Users/aria-score-00/AndroidProjects/memo-widget rev-parse HEAD
gh release list --limit 5
gh issue list --state open --limit 50 --json number,title
```

---

## 3. 架构不变量（DO NOT VIOLATE）

未经用户明确指令不要改这些，即使用户要求，也应先强烈回推（push back hard）。

1. **后端就是 GitHub Contents API，句号。** 只有两类文件会进仓库：`*.md`（按天备忘）和 `*.ics`（每事件一份 iCalendar）。不要 JSON blob、不要二进制、不要数据库转储、不要 Gist、不要滥用 Issues API。
2. **Room 是 UI 的唯一 source of truth。** 每个 Compose 屏幕都读自 `NoteDao`/`EventDao` 支撑的 `Flow`。网络结果由 Worker 对账回写 Room；UI 绝不为了渲染去 await 网络。
3. **所有写操作（本地 + 远程 PUT/DELETE）必须穿过 `PathLocker.withLock(path) { … }`。** 它把同一文件路径的 `appendToday` 与 `PushWorker` 串行化，避免 409 SHA race。见 `app/src/main/java/dev/aria/memo/data/sync/PathLocker.kt`。
4. **单用户、无协作。** 没有 merge UI、没有 CRDT、没有 OT。冲突策略 = 刷新 SHA 再重试一次。如果远程 body 与本地不一致，本地赢。
5. **提醒（`reminderMinutesBefore`）是本地偏好。** 它**不**写进 `.ics` 的 VALARM。`PullWorker` 永不把本地非 null 的提醒被远程 null 覆盖（这是 issue #12，已修）。
6. **FLAG_SECURE 只在设置页 PAT 明文可见时启用。** 不要全局覆盖。
7. **versionCode 每次 release 必须严格递增。** 当前是 7，永不递减。每次 `ALTER TABLE` 都要新加一个 Room migration，并在 `AppDatabase.build()` 的 `addMigrations(...)` 链里登记。
8. **AlarmManager 每个事件只占 1 个 slot**（下一次发生）。`EventAlarmReceiver` 在当前响完后再排下一次。不要为一个"每年一次"的 RRULE 一次性排几百个 alarm。
9. **`master` 不是 release 分支。** 在有人明确合回去之前，release 和 tag 都从 `feature/p3-polish`（或其后继 feature 分支）打。

---

## 4. 代码地图（code map）

Package 根：`app/src/main/java/dev/aria/memo/`

| 路径（相对 package 根） | 作用 |
|---|---|
| `MemoApplication.kt` | `Application` 子类。`onCreate` → `ServiceLocator.init` + 建通知 channel + 周期 pull 排程 + 一次 push + 从 `PreferencesStore` 重建快速添加通知。 |
| `MainActivity.kt` | 单 Activity。持有 `AppNav`。Android 13+ 运行时请求 `POST_NOTIFICATIONS`。 |
| `EditActivity.kt` | 快速添加入口，由 `MemoWidget`（2×2）以及（新增的）`QuickAddNotificationManager` 拉起。 |
| `data/Models.kt` | `AppConfig`、`MemoResult<T>`、`ErrorCode`。稳定的线上契约。 |
| `data/ServiceLocator.kt` | 手写 DI。持有单例：装了 `HttpTimeout` 的 `HttpClient(CIO)`、`GitHubApi`、`MemoRepository`、`EventRepository`、`AlarmScheduler` 等。 |
| `data/GitHubApi.kt` + `data/GitHubDto.kt` | Contents API 的薄 Ktor 包装（`GET/PUT/DELETE /repos/{owner}/{repo}/contents/{path}`）。 |
| `data/MemoRepository.kt` | 编排 Settings + GitHubApi + NoteDao。`appendToday` / `observeNotes` / `refreshNow` / `bootstrapAllNotes`（有限速）。 |
| `data/EventRepository.kt` | 事件（`.ics`）版的 MemoRepository。同时持有 `AlarmScheduler` 调用（失败不致命 — issue #18）。 |
| `data/SettingsStore.kt` | DataStore，存 GitHub 配置（owner/repo/branch/fileStrategy）。首次读时把 PAT 从明文 prefs 迁到 Keystore 加密存储。 |
| `data/SecurePatStore.kt` | `EncryptedSharedPreferences` + `AndroidKeyStore` 的 PAT 封装。永远不要 log 这个东西。 |
| `data/PreferencesStore.kt` | 专门给 UI 级开关用的小 DataStore（目前只有 `quickAddEnabled`）。故意与 `SettingsStore` 分开。 |
| `data/SingleNoteRepository.kt` | **P5 引入，P6.1 接入 UI**。Obsidian-style 一笔记一文件（`notes/YYYY-MM-DD-HHMM-slug.md`）。CRUD + `togglePin`。P6.1 起 4 个写方法均穿 `PathLocker.withLock(filePath)`（issue #40）、`togglePin` 直调 `FrontMatterCodec.applyPin`。 |
| `data/notes/NoteSlugger.kt` | **P5**。`slugOf(body)`：首行去 markdown → 文件系统安全 → 截断 30 字符。处理中文/emoji/Windows 保留字。 |
| `data/notes/FrontMatterCodec.kt` | **P6.1**。YAML front-matter parse / strip / applyPin / looksLikePinOnly，纯函数无 Android 依赖。抽离自 `MemoRepository` companion object（保留向后兼容 shim）。 |
| `data/oauth/GitHubOAuthClient.kt` + `GitHubOAuthDto.kt` | **P5**。GitHub OAuth Device Flow：POST `/login/device/code`、轮询 `/login/oauth/access_token`，`slow_down` 与 `authorization_pending` 响应码处理。 |
| `data/ics/IcsCodec.kt` | RFC 5545 子集编解码器。处理 line folding（75 字节）、UID escape、RRULE escape。conformance 看 `IcsCodecTest`。 |
| `data/ics/EventExpander.kt` | 把一个 `EventEntity` + RRULE 展开成某时间窗口内的发生列。纯函数，单测覆盖。 |
| `data/tag/TagIndexer.kt` | 从所有缓存日文件的 memo body 里解析 `#tag/nested`（含 CJK），返回一棵 `TagNode` 树。纯函数。 |
| `data/local/AppDatabase.kt` | Room DB。实体：`NoteFileEntity`、`EventEntity`、`SingleNoteEntity`（P5）。迁移 1→8。 |
| `data/local/NoteDao.kt` | `observeAll`、`observeRecent(limit)`（**P6.1** LIMIT 下推）、`get(path)`、`upsert`、`markClean`、`togglePin`。 |
| `data/local/SingleNoteDao.kt` | **P5**。单笔记表 CRUD：`observeAll`/`observeRecent(limit)`/`get(uid)`/`getByPath(path)`/`pending()`/`upsert`/`markClean`/`tombstone`/`hardDelete`/`togglePin`。 |
| `data/local/EventDao.kt` | 事件 CRUD + `observeBetween(startMs, endMs)`。 |
| `data/local/NoteFileEntity.kt` | 主键 `path`。`date` 有索引（issue #29）。`isPinned` 列（schema v7）。 |
| `data/local/SingleNoteEntity.kt` | **P5**。主键 `uid`；`filePath` UNIQUE。字段：title/body/date/time/isPinned/githubSha/localUpdatedAt/remoteUpdatedAt/dirty/tombstoned。 |
| `data/local/EventEntity.kt` | 主键 `uid`。`filePath` 唯一索引。可空字段 `rrule`、`reminderMinutesBefore`、`tombstoned`。 |
| `data/local/Converters.kt` | `LocalDate` ⇄ epoch day、`LocalTime` ⇄ seconds-of-day converters。 |
| `data/sync/PullWorker.kt` | WorkManager 任务。**P6.1** 起四段共享 `PullBudget(cap=150)`。拉 day-files bootstrap + 14-day window + events + single notes；硬删取消 alarm 保持本地 pin/reminder。 |
| `data/sync/PullBudget.kt` | **P6.1**。非线程安全的单轮 API 预算。`consume()` 前置于每次 `getFile`/`listDir`；`exhausted` 后段 break 给 retry 机会。 |
| `data/ai/AiConfig.kt` | **P7**。data class（providerUrl / model / apiKey） + `isConfigured` 属性。 |
| `data/ai/AiDto.kt` | **P7**。OpenAI-compatible DTOs：`AiMessage` / `ChatRequest` / `ChatResponse` / `ChatChoice`，`stream=false` MVP。 |
| `data/ai/AiClient.kt` | **P7**。Ktor POST `/v1/chat/completions`，复用 ServiceLocator 的 HttpClient（CIO + HttpTimeout）。错误映射：401/403 → UNAUTHORIZED；5xx → 通用 UNKNOWN（不回显 body，issue #63）；IOException → NETWORK；SerializationException → UNKNOWN。apiKey 永不进 log/message/exception。 |
| `data/ai/AiSettingsStore.kt` | **P7**。open class。url/model 存 Preferences DataStore (`memo_ai_settings`)，apiKey 存 EncryptedSharedPreferences (`memo_ai_secure_prefs`, AES256-GCM)。`observe()` 在 null-context 路径 `flow { emit; awaitCancellation() }` 保持 live 语义（issue #62）。 |
| `data/ai/AiContextBuilder.kt` | **P7**。纯 object。`AiContextMode { NONE, CURRENT_NOTE, ALL_NOTES }`；`buildSystemPrompt(mode, currentNoteBody, allNoteBodies, charBudget=15_000)`。截断方向：CURRENT_NOTE 从尾截 + `(truncated)` 标记；ALL_NOTES 按顺序保留能塞下的完整笔记。 |
| `data/widget/WidgetRefresher.kt` | **P8 新建**。Widget 刷新触发器。object + `SupervisorJob + Dispatchers.Default` 的 fire-and-forget scope；`refreshAll(context)` 非阻塞、`refreshAllNow(context)` 阻塞版给 test 用；两种都 `runCatching` 调 `MemoWidget().updateAll` / `TodayWidget().updateAll`，保证 widget 异常不冒泡到写路径（repo.create / worker.doWork 调用它时永远不会被拖垮）。所有笔记 CRUD、sync worker 成功路径、SettingsStore PAT 变更 hook 都会调 `refreshAll`。 |
| `data/sync/PushWorker.kt` | 冲刷 `dirty` 行。409 时刷新 SHA 重试一次（issue #27）。通过 `try/finally` 给 `SyncStatusBus` 发信号（issue #21）。 |
| `data/sync/PathLocker.kt` | 每路径一把 `Mutex`。用 `withLock(path) { ... }`。 |
| `data/sync/SyncScheduler.kt` | 入队周期 `PullWorker` + 一次性 `PushWorker`。 |
| `data/sync/SyncStatusBus.kt` / `SyncStatus.kt` | 进程内 `StateFlow<SyncStatus>`，笔记页的 `SyncBanner` 消费。 |
| `ui/nav/AppNav.kt` | 底部导航：`Notes` · `Tags` · `Calendar` · `Settings`。`NavController` + `saveState=true`。**P6.1** 加 `AnimatedContent(150ms)` tab 切换过渡。 |
| `ui/notelist/NoteListScreen.kt` + `NoteListViewModel.kt` | **P6.1 大改**：合并 `NoteListUiItem.LegacyDay`（绿 accent）+ `SingleNote`（紫 accent）sum type；LargeTopAppBar + ScrollAwareFab + MemoCard + MemoSectionHeader + MemoEmptyState。`togglePin` 按 path 前缀分派，`notes/` 开头调 `singleNoteRepo.togglePin` 或 `togglePinByPath` fallback。 |
| `ui/calendar/CalendarScreen.kt` + `CalendarViewModel.kt` | 月视图 + 当日事件清单。`EventExpander` 跑在 `Dispatchers.Default`（issue #7），结果有缓存（issue #31）。**P6.1** 视觉：LargeTopAppBar + 滚动联动 ScrollAwareFab + tertiary 色 marker。ViewModel `mutating` 改 `AtomicBoolean.compareAndSet`（issue #44）。 |
| `ui/calendar/EventEditDialog.kt` | 创建/编辑事件 UI。`rememberSaveable` 用 `sessionKey` 作用域（issue #22）。RecurrenceChip 穷尽含 "自定义" 兜底（issue #23）。**P6.1** 新加 Material3 DatePickerDialog + Switch/AssistChip。 |
| `ui/edit/ChecklistRenderer.kt` | 把 `- [ ]` / `- [x]` markdown 清单行渲染成可点击的 Material `Checkbox` 行。 |
| `ui/tags/TagListScreen.kt` + `TagListViewModel.kt` | 展示 `TagIndexer` 输出的树。 |
| `ui/EditScreen.kt` + `EditViewModel.kt` | `MainActivity` / `EditActivity` 共用的编辑器。**P6.1 大改**：VM 双路径（`noteUid==null`→`create`；否则→`update`），加 init race 守卫（只在 `_body.isEmpty()` 时种）。Screen 加 Markdown 工具栏 10 按钮（`TextFieldValue` 选区感知）、字符数+行数底部栏、保存按钮移到 TopAppBar action、FilterChip 切换编辑/预览。 |
| `ui/SettingsScreen.kt` + `SettingsViewModel.kt` | GitHub 配置、PAT 掩码、快速添加开关、通知权限引导、手动刷新。**P6.1** 换 LargeTopAppBar + MemoCard。 |
| `ui/help/HelpScreen.kt` + `ui/help/MarkdownRenderer.kt` | **P5**。应用内用户手册（查看 `docs/help/*.md`）。**P6.1** 换 LargeTopAppBar + MemoEmptyState 错误态。 |
| `ui/oauth/OAuthSignInDialog.kt` + `OAuthSignInViewModel.kt` | **P5**。GitHub Device Flow 登录对话框（设备码 + 浏览器跳转）。 |
| `ui/components/MemoCard.kt` + `MemoEmptyState.kt` + `MemoSectionHeader.kt` + `ScrollAwareFab.kt` | **P6.1**。全局公共 Composable。MemoCard 可选 `accentColor` 左侧 4dp 色条；MemoEmptyState 圆形 tertiaryContainer 图标 + 标题/副标题；ScrollAwareFab = ExtendedFAB 的 `expanded` 包装。 |
| `ui/ai/AiChatScreen.kt` + `AiChatViewModel.kt` | **P7**。聊天页：LargeTopAppBar + FilterChip 上下文 toggle（无/当前笔记/全部笔记）+ 消息气泡 LazyColumn + 底部输入栏 + 未配置 `MemoEmptyState`。VM `send(): Job` 返回 Job 便于 test `.join()`；multi-turn transcript ephemeral；`send()` 包 try/catch + `firstOrNull() ?: emptyList()` 防 Flow 不 emit 崩溃（issue #60）。 |
| `ui/theme/Color.kt` + `Theme.kt` + `Type.kt` + `Shapes.kt` + `Spacing.kt` | **P6.1 扩展**：tertiary 蓝紫色盘（Light `0xFF5B6CC9` / Dark `0xFFBBC4F4`）、`MemoShapes` 统一 card 16dp / button 12dp、`MemoSpacing` xs=4dp…xxxl=32dp 的 4-pt 体系。 |
| `widget/MemoWidget.kt` + `MemoWidgetContent.kt` + `MemoWidgetReceiver.kt` | **P8 重做**：从 2×2 快速写 → 3×3 默认（可 resize 4×4）的**最近 20 条可滚动列表**。`LazyColumn` 渲染 `observeRecent(limit=20)` 结果；顶部 `TitleBar.actions` 带 ➕ 新建（启 `EditActivity`）+ 🔄 手动刷新（走 `RefreshActionCallback`）。未配置 PAT 态显示提示 + 点任意位置跳 `MainActivity`。置顶笔记前有 📌 图标，每行 `MM/DD HH:mm` 前缀。 |
| `widget/TodayWidget.kt` + `TodayWidgetContent.kt` + `TodayWidgetReceiver.kt` | **P8 扩容**：Glance 4×2 今日列表（事件 + 备忘）。memos 上限从 6 提到 20（`LazyColumn` 自己滚）。语义保持"今天"，跨日自动切。顶部新增 🔄 手动刷新按钮。`itemId` 里带序号防同一分钟的备忘碰撞（issue #3）。 |
| `widget/RefreshActionCallback.kt` | **P8 新建**。Glance `ActionCallback` 实现，绑定在 🔄 按钮上。在 IO 线程里调 `WidgetRefresher.refreshAllNow(context)` 强制两个 widget 重拉数据并 `updateAll`。失败 `runCatching` 吞掉不破坏 widget 状态。实际文件名以 Agent 3 实现为准（可能叫 `MemoRefreshAction.kt` 之类）。 |
| `notify/AlarmScheduler.kt` | `AlarmManager.setExactAndAllowWhileIdle`。RRULE 事件只排下一次发生；`windowEnd = Long.MAX_VALUE/2`。 |
| `notify/EventAlarmReceiver.kt` | 触发时：发通知、计算下次发生、再排一次。 |
| `notify/BootReceiver.kt` | 只处理 `BOOT_COMPLETED`（issue #17）。重排所有未来 alarm。 |
| `notify/NotificationChannelSetup.kt` | 建两个 channel：`event_reminders`（IMPORTANCE_DEFAULT、VISIBILITY_PRIVATE — issue #13）与 `quick_add`（IMPORTANCE_LOW、静音、不上 badge）。 |
| `notify/NotificationPermissionBus.kt` | `StateFlow<Boolean>`，把运行时权限状态告诉 Settings UI。 |
| `notify/QuickAddNotificationManager.kt` | **新**（P4.3 WIP）。常驻静音通知 → 点击进 `EditActivity`。 |

### 资源 / 配置文件

- `app/src/main/AndroidManifest.xml` — 权限：`INTERNET`、`POST_NOTIFICATIONS`、`RECEIVE_BOOT_COMPLETED`、`SCHEDULE_EXACT_ALARM`、`USE_EXACT_ALARM`。Receiver：`MemoWidget`、`TodayWidget`、`EventAlarmReceiver`（不导出）、`BootReceiver`。
- `app/src/main/res/xml/memo_widget_info.xml`、`today_widget_info.xml` — AppWidget provider metadata。
- `app/build.gradle.kts` — versionCode / versionName 在这里。
- `gradle/libs.versions.toml` — 所有依赖版本 pin 在这里。
- `settings.gradle.kts` — 配了阿里云 Maven 镜像。
- `gradle/wrapper/gradle-wrapper.properties` — 配了腾讯云 Gradle 镜像。

---

## 5. 按阶段交付的功能

| 阶段 | Tag | 关键功能 | 关联关闭的 issue |
|---|---|---|---|
| P1 | `v0.2.0-p1` | 按天文件备忘、离线优先、`MemoRepository`、PAT 加密、底部导航、设置页、widget、后台 `PullWorker`/`PushWorker`、`SyncStatusBus`/`SyncBanner`。 | — |
| P2 | `v0.3.0-p2` | 日历月视图、`.ics` 事件（单次发生）、今日 widget（4×2）、中文 README、`EventExpander`、`EventEditDialog`。 | — |
| P3 | `v0.4.0-p3` | 第 1 轮 review：`IcsCodec` 往返修复、全历史备忘拉取、Today widget itemId、日历 marker 移出主线程、中文文案、事件路径身份。 | #1 #2 #3 #4 #5 #6 #7 #8 #9 |
| P4 | `v0.5.0-p4` | 循环事件（`FREQ=WEEKLY`/`MONTHLY`）、`PullWorker` 限速、Push UI 反馈、锁屏 VISIBILITY_PRIVATE、NPE 安全的 AlarmScheduler、POST_NOTIFICATIONS 请求、PullWorker 本地提醒保留。 | #10 #11 #12 #13 #14 |
| P4.1 | `v0.6.0-p4.1` | 事件提醒：`AlarmScheduler`、`EventAlarmReceiver`、`BootReceiver`、通知 channel、POST_NOTIFICATIONS 运行时处理。 | #15 #16 #17 #18 |
| P4.2 | `v0.7.0-p4.2` | 5-agent review 浪潮：HttpTimeout、`bootstrapAllNotes` 限速、SyncStatusBus finally 块、`rememberSaveable(sessionKey)`、RecurrenceChip 穷尽、listDir 非目录处理、403 rate-limit 与 auth 区分、POST_NOTIFICATIONS 设置 deep-link、Notes tab 状态保存、CalendarViewModel 展开缓存、`note_files.date` 索引（schema v6）、IcsCodec line folding + RRULE escape、PushWorker CONFLICT SHA 刷新。 | #19–#31 |
| P4.3 | `v0.8.0-p4.3` | 标签页（Tags tab，`TagIndexer` → `TagListScreen`）、清单渲染（`ChecklistRenderer`）、笔记置顶（`isPinned` 列，schema v7）、快速添加常驻通知（`QuickAddNotificationManager` + `PreferencesStore`）、OAuth Device Flow 登录、应用内用户手册（HelpScreen）。 | — |
| P5 | `v0.8.0-p5` | Obsidian-style 单笔记架构 scaffold（`notes/YYYY-MM-DD-HHMM-slug.md`）：`SingleNoteEntity`/`SingleNoteDao`/`SingleNoteRepository`、`NoteSlugger`、schema v8 + `MIGRATION_7_8`、`PullWorker` + `PushWorker` 单笔记段。UI 层尚未消费（仅 scaffold）。 | — |
| P6 | `v0.9.0-p6` | P5 之后修保存 dup race bug（`EditViewModel.lastCommittedBody` + 2s 去重窗口）、widget 跨天显示（`recentEntriesAcrossDays`）、HANDOFF 中文化、BDD 套件扩到 28 场景。 | #32–#39 |
| P6.1 | `v0.10.0-p6.1` | **UI 大改版 + SingleNote UI 集成**：视觉升级（tertiary 色、LargeTopAppBar、Markdown 工具栏、MemoCard/MemoEmptyState/ScrollAwareFab 公共组件）、NoteListViewModel sum type 合并 legacy + single、EditViewModel 双路径、MemoWidget 先 single 后 legacy；数据层 `FrontMatterCodec` 抽离 + `PullBudget` 全局预算 + `NoteDao.observeRecent` LIMIT 下推 + `CalendarViewModel.mutating` 原子化 + `SingleNoteRepository` 补 PathLocker。 | #40–#59 |
| P6.1.1 | `v0.10.1-p6.1.1` | deferred bug 清零：首装未配 PAT 写笔记不丢、`recentEntriesAcrossDays` LIMIT 下推、`EditViewModel` 分层归位。**CI/CD 首次引入**（`.github/workflows/ci.yml`）。BDD 扩场景 41-43。 | — |
| P7 | `v0.11.0-p7` | **AI 问答集成**：data/ai（AiClient/AiSettingsStore/AiContextBuilder/AiDto，OpenAI-compatible，key 存 EncryptedSharedPreferences、FLAG_SECURE 扩展）+ ui/ai（AiChatScreen 含三段上下文切换 + AiChatViewModel 多轮 transcript ephemeral） + NoteListScreen `Psychology` 图标入口 + SingleNoteRow 长按 "问 AI" DropdownMenu 带 noteUid 深链。**Review 闭环**：1 High + 10 Medium + 5 Low（issue #60–#75），修到零 open。**220 unit test** / 0 failed（23 test 文件）。BDD 扩场景 44-53。 | #60–#75 |
| **P8** | `v0.12.1-p8`（当前） | **Widget 重做 + 自动刷新 + 12 reviewer 对抗审计 + 8 fix wave**：MemoWidget 3×3 默认 + **最近 20 条可滚动列表** + 🔄 刷新按钮 + Toast 反馈；`WidgetRefresher` 用 MutableSharedFlow + Flow.debounce(400ms) 接通 10 hook；Data-1 R4/R8/R11/R12/R13 数据完整性；Sec-1 toString redact + FLAG_SECURE + exported=false；Perf-1 SettingsStore flowOn(IO) + Application 异步 enqueue + MemoWidget withTimeoutOrNull 保护；UX: 单笔记删除入口 + BackHandler 草稿确认 + rememberSaveable listState + PushWorker retry 保留 lastError；Arch: ServiceLocator 双 API 统一（@Deprecated）+ MarkdownPreview helper 抽取 + 删未用依赖；Dep: compileSdk/targetSdk 35→36；BDD 1090 条；≈240 unit test 全绿；CI 加 android-lint / android-release-smoke 两个独立 job。**127 P8.1 延后 issue 已登记**（#87-#333 区间）。 | #333（PR）/ W-1~W-5 |

---

## 6. 测试清单

全部跑：`./gradlew :app:testDebugUnitTest`

| 文件 | ≈ 测试数 | 主题 |
|---|---:|---|
| `data/AppConfigTest.kt` | 4 | 按 day/week/month 策略的 `filePathFor()` |
| `data/MemoResultTest.kt` | 4 | `Ok`/`Err` 的 map/flatMap/getOrDefault |
| `data/ics/IcsCodecTest.kt` | 9 | 往返、UID escape、line folding、RRULE |
| `data/ics/EventExpanderTest.kt` | 9 | WEEKLY/MONTHLY 发生、窗口过滤 |
| `data/tag/TagIndexerTest.kt` | 6 | 平铺/嵌套/CJK 标签、去重、按日聚合 |
| `data/MemoRepositoryPinTest.kt` | 7 | 置顶 front-matter 解析 / 往返、排序 |
| `data/MemoRepositoryFrontMatterTest.kt` | — | FrontMatter 往返行为（delegate 版本，P6.1 后走 codec） |
| `data/notes/FrontMatterCodecTest.kt` | 29 | **P6.1**：parse / strip / applyPin / looksLikePinOnly + 3 条边界（嵌套 `---` / 缺闭合符 / 非 ASCII 键） |
| `data/notes/NoteSluggerTest.kt` | 10 | **P5**：中文/emoji/Windows 保留字的 slug 生成 |
| `data/sync/PullBudgetTest.kt` | 6 | **P6.1**：consume/remaining/exhausted/cap=0 |
| `data/SingleNoteRepositoryTest.kt` | 7 | **P5**：entity 构建（filePath/title/slug）、`extractTitle` |
| `data/RecentEntriesAcrossDaysTest.kt` | 11 | **P6**：跨天 widget 合并排序 |
| `data/oauth/GitHubOAuthClientTest.kt` | — | **P5**：Device Flow 轮询/slow_down/authorization_pending |
| `ui/edit/ChecklistLineParserTest.kt` | 8 | `- [ ]` / `- [x]` 检测、嵌套、正文抽取 |
| `ui/DoubleTapSaveTest.kt` | 6 | **P6**：双击保存去重窗口（走 legacy 3-arg 构造，P6.2 迁到新构造） |
| `ui/EditViewModelSingleNoteTest.kt` | 14 | **P6.1**：新建 create / 编辑 update / 双击去重 / init race / CONFLICT / 僵尸 uid |
| `ui/notelist/NoteListViewModelCombineTest.kt` | 14 | **P6.1**：sum type combine / pin 优先 / 日期时间降序 / 搜索 / togglePin 分派 |
| `widget/MemoWidgetDataSourceTest.kt` | 10+ | **P6.1** → **P8 扩 limit=20** 相关边界（19/20/21 条、正好 20 条、空态、未配置态、pinned first） |
| `ui/help/MarkdownRendererTest.kt` | — | **P5**：markdown 内部 render（heading/bold/italic/list/link）|
| `data/ai/AiClientTest.kt` | — | **P7**：ping / 401 → UNAUTHORIZED / 5xx → UNKNOWN 不回显 body / apiKey 不进 log/msg / NETWORK 映射 |
| `data/ai/AiSettingsStoreTest.kt` | — | **P7**：observe() flow live 语义 / null-ctx 回退 `awaitCancellation` 保持 Flow 不终止 |
| `data/ai/AiContextBuilderTest.kt` | — | **P7**：NONE / CURRENT_NOTE 尾截断 + `(truncated)` 标记 / ALL_NOTES 完整笔记保留 / `笔记` header 常量 |
| `ui/ai/AiChatViewModelTest.kt` | — | **P7**：send() 单轮 / 多轮 transcript 积累 / firstOrNull Flow 不崩 / hasCurrentNote 负向断言 |
| `data/widget/WidgetRefresherTest.kt` | — | **P8 新增**：`refreshAllNow` 调 MemoWidget.updateAll + TodayWidget.updateAll / runCatching 吃 GlanceId not found / 异常不传播 / SupervisorJob 子 job 失败不杀 scope |
| `data/SingleNoteRepositoryHookTest.kt` | — | **P8 新增**（或归并进 `SingleNoteRepositoryTest.kt`）：create / update / delete / togglePin 成功路径末尾调 WidgetRefresher（verify fake recorder）|
| `data/sync/PullWorkerRefreshTest.kt` / `PushWorkerRefreshTest.kt` | — | **P8 新增**（如添加）：doWork `Result.success` 前调 WidgetRefresher |
| **合计** | **≈240** | （以 gradle 实际跑结果为准：`./gradlew :app:testDebugUnitTest`；P7 基线 220 + P8 新增约 20）|

还没有 instrumented（`androidTest`）用例 — 加一个需要模拟器，见 §9。P6.2 TODO 里第 2 项是 Room MigrationTestHelper 的 instrumented 迁移链测试。

---

## 7. 命令速查（cheat sheet）

所有命令默认 `cwd = /Users/aria-score-00/AndroidProjects/memo-widget`。

### 构建

```bash
./gradlew :app:assembleDebug                   # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease                 # 未签名；仓库里还没 signing config
./gradlew :app:clean
./gradlew :app:dependencies --configuration releaseRuntimeClasspath | head -80
```

### 测试 + lint

```bash
./gradlew :app:testDebugUnitTest                                                    # 全部 ≈240
./gradlew :app:testDebugUnitTest --tests "dev.aria.memo.data.ics.IcsCodecTest"      # 单个 class
./gradlew :app:testDebugUnitTest --tests "dev.aria.memo.data.tag.*"                 # 单个 package
./gradlew :app:lintDebug                                                            # 报告在 app/build/reports/lint-results-debug.html
```

### 模拟器 / 设备

```bash
adb devices                                                            # 确认有设备
adb install -r app/build/outputs/apk/debug/app-debug.apk               # 装 debug
adb shell am start -n dev.aria.memo/.MainActivity                      # 启动
adb logcat -s "MemoApplication:*" "PullWorker:*" "PushWorker:*" "AlarmScheduler:*"   # 跟踪相关 tag
adb shell dumpsys alarm | grep dev.aria.memo                           # 看已排的 alarm
adb exec-out screencap -p > /tmp/memo_screen.png                       # 截屏（不是 launcher）
```

### GitHub

```bash
gh api rate_limit --jq .resources.core                                 # 看限速（5000/hr 共享）
gh issue list --state open --limit 50 --json number,title,labels
gh issue list --state closed --limit 60 --json number,title,closedAt
gh release list --limit 10
gh release view v0.7.0-p4.2
gh release create vX.Y.Z-pN --target feature/p3-polish \
    --title "vX.Y.Z-pN · <summary>" --notes "<body>"
gh release upload vX.Y.Z-pN app/build/outputs/apk/debug/app-debug.apk
```

> **限速警告**：在循环里反复 `gh issue view N` 会迅速烧光 quota（每次 1 次调用）。优先用 `gh issue list --json number,title,state,body --limit 60` 一次拉回来。

### Git 卫生

```bash
git status --porcelain
git diff HEAD --stat
git log --oneline -20
git log feature/p3-polish --not master --oneline      # 只看 release 分支独有的 commit
```

---

## 8. 文件归属约束（保持在你自己的分层里）

编辑时请遵守以下分层边界：

| 层 | 路径前缀 | 允许依赖 | 禁止依赖 |
|---|---|---|---|
| UI / Compose | `ui/` + `widget/` | `data/`（仅 repository、model），Android framework | Room DAO 直接访问、Ktor `HttpClient`、WorkManager |
| Repository / 编排 | `data/Memo*`、`data/Event*`、`data/Settings*`、`data/ServiceLocator` | `data/local/`、`data/sync/`、`data/ics/`、`data/tag/`、Ktor API | UI/Compose、widget |
| 同步（Workers） | `data/sync/` | `data/local/`、`data/GitHubApi`、`notify/AlarmScheduler` | UI/Compose、Activity |
| 本地持久化 | `data/local/` | 只能用 Room + kotlinx-datetime | 其他全部 |
| 编码 | `data/ics/`、`data/tag/` | 只能用纯 Kotlin / stdlib | Android context、Room、Ktor |
| 通知 | `notify/` | `EditActivity`（intent target）、`data/local/` | UI Composable |
| 应用引导 | `MemoApplication.kt`、`ServiceLocator.kt` | 随便 | — |

测试镜像 `main` 的结构。`data/tag/` 的测试不能拉 Android framework 类（保持 `TagIndexerTest` 为纯 JVM）。

---

## 9. 在本仓库上跑 AI 会话

### 非平凡改动的推荐流程

按用户的 memory 规则（max-agents-plus-review），优先这样跑：

1. **调研（并行 2–3 个 agent）** — 每个从不同视角读同一功能区域（数据流、UI、测试面）。产出：汇总笔记，不改代码。预算：每个 ≤15k token。
2. **设计（1 个 agent 或同步步骤）** — 选定方案，写一份简短计划（≤200 行），覆盖：涉及文件、新类型、迁移步骤、测试增量。
3. **实现（3 个并行 agent，不同策略）** — 如 agent A 最小 diff、agent B 做 refactor、agent C test-first。选最好的。预算：每个 ≤40k token。
4. **Review（强制，2–3 个 subagent）** — 一个抓正确性 bug、一个检查 §3 不变量、一个审测试覆盖和 Room 迁移。哪怕是"小改动"也要跑。
5. **验证** — `./gradlew :app:testDebugUnitTest` **必须**过；`git diff --stat` 理性检查；手动验证 §3 不变量没破坏。
6. **交接** — 更新本文 §2 发版状态、§5 阶段表、§10 open TODO；如果用户可见有变化，同步 USER_GUIDE。
7. **Commit / release 必须等用户明确同意**（用户偏好：未获确认不提交）。

### 反模式（不要做的事）

- 小改动上 review 步骤开 >3 个 agent — 并行等于重复 review，不是拆 workload。
- 手动 clear context / 主动 compact。
- 用 amend 代替新 commit。
- `git add .` / `git add -A`（会扫进敏感文件）。
- 用 `Bash` 跑 `find` / `grep` / `cat` — 用 `Glob`、`Grep`、`Read` 工具。
- 需要等 rate-limit reset 时还在 loop 里跑 `gh api` — 该用 `ScheduleWakeup`。

---

## 10. Open TODO（工作树现在装的东西）

P6.1 release 之后，工作树应为 **CLEAN**（`git status --porcelain` 应输出空）。如果非空，说明 P6.2 已经开工——按头部 P6.2 TODO 清单对照查验。

### P6.1 合并的最终改动摘要

- **新建文件**（11）：`data/notes/FrontMatterCodec.kt`、`data/sync/PullBudget.kt`、`ui/theme/{Shapes,Spacing}.kt`、`ui/components/{MemoCard,MemoEmptyState,MemoSectionHeader,ScrollAwareFab}.kt`、`app/src/test/java/dev/aria/memo/data/notes/FrontMatterCodecTest.kt`、`app/src/test/java/dev/aria/memo/data/sync/PullBudgetTest.kt`、以及 3 个新 VM/widget test 文件。
- **修改文件**（20）：`data/MemoRepository.kt`（FrontMatter delegate）、`data/SingleNoteRepository.kt`（+PathLocker + FrontMatterCodec 直调）、`data/local/NoteDao.kt`（+observeRecent）、`data/sync/PullWorker.kt`（PullBudget 化）、`ui/calendar/CalendarViewModel.kt`（AtomicBoolean）、`ui/theme/{Color,Theme}.kt`（tertiary）、`ui/nav/AppNav.kt`（AnimatedContent）、`ui/EditScreen.kt`（Markdown 工具栏）、`ui/EditViewModel.kt`（双路径 + init race 守卫）、`EditActivity.kt`（EXTRA_NOTE_UID）、`ui/notelist/{NoteListScreen,NoteListViewModel}.kt`（sum type + MemoCard）、`widget/{MemoWidget,MemoWidgetContent}.kt`（single-note 优先）、`ui/{Settings,help/Help,tags/TagList,calendar/Calendar,calendar/EventEditDialog}Screen.kt`（LargeTopAppBar + MemoEmptyState）。
- **diff 体量**：≈ +2000 / −800 行。
- **Closed issues**：#40–#59（20 个，P6.1 review 产出）。

### P6.1 release 后的 deferred TODO

见文首 "🟡 P6.2 下一轮（deferred）" 8 条。

### 建议的下一个会话入口

1. `./gradlew :app:testDebugUnitTest` → 确认工作树下 **≈240** 项测试全部过。
2. `git diff HEAD` → 每个 hunk 对照 §3 不变量复核（尤其 migration、索引、通知生命周期）。
3. 按特性拆 commit（一特性一个，不要一个大 squash）：
   - `feat(p4.3): pin notes with front-matter round-trip + schema v7`
   - `feat(p4.3): checklist rendering in editor`
   - `feat(p4.3): nested-tag tab`
   - `feat(p4.3): quick-add ongoing notification`
4. 在 `app/build.gradle.kts` 把 `versionCode` 升到 8，`versionName` 升到 `0.8.0-p4.3`。
5. 从 `feature/p3-polish` 打 release `v0.8.0-p4.3`。
6. 回来更新本文 §2 / §5 / §10。

### 明确不修的

- `master` 分支陈旧 — release 分支运转正常，在有人主动合回去之前维持现状。（把 6+ 个 feature 分支 rebase 到 master 是高风险低回报。）
- 没有签名 release APK — 只发 `app-debug.apk`。需要 keystore 配置；等 Play Store 决定再做。
- 没有 instrumented UI 测试 — Compose UI test + CI 里的模拟器是 P5+ 范畴。
- `bootstrapAllNotes` 限速 50 是 magic number — 单用户下没事，行为变了再回来调。

---

## 11. 推荐的 P6.2+ 方向（ROI 排序）

1. **P6.2 TODO 清单（头部列）** — 7 项优先级上面已列，按用户决定选做。
2. **P6.3 — `.ics` VALARM 跨设备提醒**（把 `BEGIN:VALARM`/`END:VALARM` 写进 ics；pull 时只有本地为 null 才 fold 进 `reminderMinutesBefore`）。遵守不变量 #5。约 1 个会话。
3. **P6.4 — RRULE 扩展**（给 `EventExpander` + `IcsCodec` 加 `UNTIL`、`COUNT`、`BYDAY`、`EXDATE`；UI chip 已经有 "自定义" 做挂钩）。`IcsCodecTest` 加往返测试。约 2 个会话。
4. **P6.5 — Notes 懒加载历史**（滚过 14 天后继续回拉；Room paging3）。只要不动 Room schema 就便宜。
5. **P6.6 — ROM 限制健康检查**（MIUI/EMUI/ColorOS/Funtouch 上检测 autostart 关闭 / 电池白名单缺失，设置页挂卡片指向系统 intent）。用户可见的收益，没架构风险。
6. **Release hardening**（keystore + 签名 APK + tag 推送触发 `assembleRelease` 的 GitHub Actions）。打通 Play Store。
7. **Markor fork 评估**（见 P6.2 TODO 第 8 项）。看 Markdown grammar / 语法助手的成熟度是否值得桥接。

明确**不**在 P6.x 范围内：CRDT 合并、多用户共享、服务器组件、附件 / 二进制 blob、repo 内容端到端加密。

---

## 12. 会话自检（开工前先贴一遍）

```bash
cd /Users/aria-score-00/AndroidProjects/memo-widget
git rev-parse HEAD                                         # 期望 P8 release commit 或更新
git status --porcelain                                     # 期望 CLEAN（P8 release 后）
./gradlew :app:testDebugUnitTest --rerun-tasks             # 期望：≈240 过，0 失败
gh release list --limit 3                                  # 榜首：v0.12.1-p8
gh issue list --state open --limit 20                      # 期望：0 open
gh api rate_limit --jq .resources.core                     # 跑 gh 循环前先看余量
```

任何一项对不上 — 去排查根因，不要遮盖。

---

## 13. 仓库指针

- Owner / repo：`qqzlqqzlqqzl/memo-widget`
- URL：https://github.com/qqzlqqzlqqzl/memo-widget
- 面向人类的终端用户说明：`/Users/aria-score-00/AndroidProjects/memo-widget/USER_GUIDE.md`
- 面向人类的开发者 README：`/Users/aria-score-00/AndroidProjects/memo-widget/README.md`
- 本文：`/Users/aria-score-00/AndroidProjects/memo-widget/HANDOFF.md`（AI-facing，每次 release 后重新生成）
