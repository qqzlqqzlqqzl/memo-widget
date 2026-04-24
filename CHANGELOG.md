# 更新日志

本项目所有显著变更都会记录在此文件中。

格式基于 [Keep a Changelog 1.1.0](https://keepachangelog.com/zh-CN/1.1.0/)，并遵循 [语义化版本 2.0.0](https://semver.org/lang/zh-CN/)。

本项目使用 `vMAJOR.MINOR.PATCH-pN` 版本号格式，其中 `-pN` 表示迭代阶段（Phase）。Release 分支为 `feature/p3-polish`（**不是** `master`）。

---

## [v0.12.0-p8] - 2026-04-24

Widget 重做 + 全链路自动刷新。versionCode 13。

### Added

- `MemoWidget` 形态重做：从"今天最多 3 条固定快照"改为"最近 20 条可滚动列表"，默认 3×3 cell，可 resize 到 4×4。
- Memo widget + Today widget 顶部新增 🔄 手动刷新按钮（`SquareIconButton` → `RefreshActionCallback` → 两个 widget 同时 `updateAll`）。
- `data/widget/WidgetRefresher.kt` 新模块：object + `SupervisorJob + Dispatchers.Default` 的 fire-and-forget 触发器，提供 `refreshAll(context)` 非阻塞和 `refreshAllNow(context)` 阻塞版。
- `widget/RefreshActionCallback.kt` 新建：Glance `ActionCallback` 实现，绑定 🔄 按钮。
- `ServiceLocator.appContext` 字段：`init(context)` 里赋值 `context.applicationContext`，Repository 通过它拿 Context 而不自己持有（DI 洁癖）。
- Today widget memos 上限从 6 提到 20，`LazyColumn` 自己滚。
- BDD 扩场景 54-80（widget 自动刷新 10 条 + 列表展示 10 条 + 交互 7 条），总计约 90 条。
- Widget 相关单元测试约 20 项（`WidgetRefresherTest` / `MemoWidgetDataSource` limit=20 边界 / hook 点 verify）。

### Changed

- 全链路 hook 接入 `WidgetRefresher.refreshAll(context)`：
  - `MemoRepository.appendToday` / `updateTodayBody` 等所有写方法成功路径末尾。
  - `SingleNoteRepository.create` / `update` / `delete` / `togglePin` / `togglePinByPath`（含 NOT_CONFIGURED→Ok 路径）。
  - `PullWorker.doWork()` 在 `Result.success()` / `Result.retry()` 之前。
  - `PushWorker.doWork()` 在 `Result.success()` 之前。
  - `SettingsStore` owner/repo/pat 变更路径（未配置→已配置时立刻重绘）。
  - `AppBootObserver` / `MainActivity.onCreate`（冷启动兜底）。
- 快速连续写入时 250ms debounce 合并触发，避免桌面闪烁。
- 所有 widget 刷新调用 `runCatching` 包裹，保证 widget 异常永不冒泡到写路径。
- 单元测试基线从 220 提升至约 240 项，0 失败。

### Fixed

- 自动刷新偶然失败时用户无法强制触发的问题（通过 🔄 按钮解决）。

---

## [v0.11.0-p7] - 2026-03 AI 问答集成

AI 问答数据层 + UI + Settings 扩展 + 16 issues 闭环。versionCode 12。

### Added

- `data/ai/` 数据层：`AiConfig.kt` / `AiClient.kt` / `AiSettingsStore.kt` / `AiContextBuilder.kt` / `AiDto.kt`，OpenAI-compatible protocol（`stream=false` MVP）。
- `ui/ai/AiChatScreen.kt` + `AiChatViewModel.kt`：LargeTopAppBar + FilterChip 三段 context mode（无/当前笔记/全部笔记）+ 消息气泡 + 未配置态 MemoEmptyState + Snackbar。
- NoteListScreen 顶栏 `Icons.Filled.Psychology` AI 按钮入口（tab 级，noteUid=null）。
- SingleNoteRow 长按 DropdownMenu "问 AI"（带 noteUid，CURRENT_NOTE mode，深链）。
- Settings 扩展：3 个 OutlinedTextField（URL / API Key 掩码 / Model）+ "保存 AI 配置" + "测试连接"（发 `ping` 请求 snackbar 反馈）。
- AI API key 存 EncryptedSharedPreferences（AES256-GCM，同 PAT 策略），绝不 log。
- BDD 扩场景 44-53（含多轮 / 429 / 长按入口）。
- AI 相关单元测试：`AiClientTest` / `AiSettingsStoreTest` / `AiContextBuilderTest` / `AiChatViewModelTest`。

### Changed

- FLAG_SECURE 扩展到 `patVisible || aiKeyVisible`，AI key 明文可见时同样启用。
- `MemoRepository` / `SingleNoteRepository` 改为 `open class` + nullable ctor 支持 test fake（tech-debt，P8.1 抽 interface façade）。
- `AiChatViewModel.send()` 用 `firstOrNull() ?: emptyList()` 防 Flow 不 emit 崩溃；整 send 包 try/catch。
- `AiSettingsStore.observe()` null-context 路径改 `flow { emit; awaitCancellation }` 保持 live 语义。
- 单元测试基线达到 220 项（23 测试文件）。

### Fixed

- #60 High：`AiChatViewModel.send()` ALL_NOTES 下 `Flow.first()` 抛 `NoSuchElementException` 逃逸。
- #61 Medium：Fake observe() override 统一（Flow live 语义，否则 isConfigured 门禁永失效）。
- #62 Medium：AiSettingsStore.observe() null-ctx 路径 Flow 终止。
- #63 Medium：AiClient 不再把 4xx/5xx 响应 body 拼进 snackbar（防用户 prompt PII 泄漏）。
- #66 Medium：AiClientTest 补 403 / 500 映射 case。
- #67 Medium：AiClientTest 补 3 条 apiKey 不泄漏 regression（401/serialize/network）。
- #68 Medium：AiChatViewModelTest 补多轮 transcript 积累断言。
- #69 Medium：BDD 补场景 51（多轮）/ 52（429）/ 53（长按入口）。
- #71 Low：SingleNoteRow 长按 DropdownMenu "问 AI" 入口（+ AppNav 透传 noteUid）。
- #72 Low：send() priorMessages 注释误导。
- #73 Low：AiContextBuilderTest 加 "笔记" header 常量断言。
- #74 Low：hasCurrentNote 负向断言（initial state）。
- #75 Low：BDD 场景 48 明确 "尾截断" 方向。

### Security

- AI API key 走 EncryptedSharedPreferences（AES256-GCM，Keystore 支撑），与 PAT 一致。
- 错误响应 body 不回显给 UI，防 prompt PII 泄漏。

---

## [v0.10.1-p6.1.1] - 2026-02 deferred bug 清零 + CI/CD 首次引入

补 P6.1 遗留的 deferred bug + 首次引入 GitHub Actions CI。

### Added

- `.github/workflows/ci.yml` CI/CD workflow：每次 push/PR 自动跑 `compileDebugKotlin + testDebugUnitTest`，GitHub 侧绿灯 gate。
- BDD 扩场景 41（首装写笔记不丢）/ 42（LIMIT 下推）/ 43（Repository 分层守卫）。
- `MemoRepository.getContentForPath(path)` 薄方法。

### Changed

- `MemoRepository.recentEntriesAcrossDays` 改走 `dao.observeRecent(limit * 2 + 1)`，不再全表读；极端稀疏 fallback 到全表保证正确性。
- CI 配置里 strip 阿里云 Maven 镜像（GitHub runner 无法通过其解析 KSP plugin）。

### Fixed

- #57（修复首装未配置 PAT 写笔记直接丢的 pre-existing bug）：`SingleNoteRepository.create` 在 PAT 未配置时先写 Room（dirty=true）+ `SyncStatusBus` emit "已存本地 · 待配置"，返 Ok。PushWorker 在配好 PAT 后自动推。
- #51（LIMIT 下推）：`recentEntriesAcrossDays` 不再拉全表。
- #56（分层归位）：`EditViewModel.prime()` 和 `toggleChecklist` 的 `ServiceLocator.noteDao()` 直访全部换成 `ServiceLocator.repository.getContentForPath`，UI → Repository → DAO 分层恢复。

---

## [v0.10.0-p6.1] - 2026-02 UI 视觉大改版 + SingleNote UI 集成

UI 视觉升级 + `SingleNoteRepository` 从 scaffold 接入 UI 主流程 + 数据层债务清理 + 20 issues 闭环。

### Added

- 全局 tertiary 蓝紫色盘（Light `0xFF5B6CC9` / Dark `0xFFBBC4F4`）。
- 公共 Composable：`MemoCard` / `MemoEmptyState` / `MemoSectionHeader` / `ScrollAwareFab`。
- `MemoShapes` 统一 card 16dp / button 12dp；`MemoSpacing` xs=4dp…xxxl=32dp 的 4-pt 体系。
- `data/notes/FrontMatterCodec.kt`：YAML front-matter 纯函数 codec（parse / strip / applyPin / looksLikePinOnly），抽离自 `MemoRepository`。
- `data/sync/PullBudget.kt`：`PullWorker` 全局 API 预算（cap=150，四段共享）。
- `NoteDao.observeRecent(limit)`：LIMIT 下推（widget 用）。
- `NoteListUiItem` sum type（`LegacyDay` 绿 accent / `SingleNote` 紫 accent）。
- `EditActivity` 读 `EXTRA_NOTE_UID` 路由 edit/create。
- `LargeTopAppBar` + 滚动联动（笔记页 + 日历页）。
- Markdown 工具栏 10 按钮 + 字符数/行数统计 + FilterChip 编辑/预览。
- `AnimatedContent(150ms)` tab 切换过渡。
- 29 条 `FrontMatterCodecTest`（含嵌套 `---` / 缺闭合符 / 非 ASCII 键 3 条边界）。
- 6 条 `PullBudgetTest`（consume/remaining/exhausted/cap=0）。
- 14 条 `EditViewModelSingleNoteTest`（create / update / 双击去重 / init race / CONFLICT / 僵尸 uid）。
- 14 条 `NoteListViewModelCombineTest`（sum type combine / pin 优先 / 排序 / 搜索）。

### Changed

- `NoteListViewModel` 改 sum type：`LegacyDay | SingleNote`；`NoteListScreen` when 分派 + MemoCard 双色 accent。
- `MemoWidget` 先 `observeRecent(3)` 再 legacy fallback。
- `SingleNoteRepository` 的 `create` / `update` / `delete` / `togglePin` 全部穿 `PathLocker.withLock(filePath)`。
- `togglePin` 改直调 `FrontMatterCodec.applyPin`。
- `CalendarViewModel.mutating` 改 `AtomicBoolean.compareAndSet`。
- 新 Material3 `DatePickerDialog` + Switch/AssistChip。
- 单元测试基线达到 179 项（旧 170 + 新 9）。

### Fixed

- #40–#59（20 个 issue，P6.1 review 产出）。

---

## [v0.9.0-p6] - 2026-01 保存 dup race + 跨天 widget + BDD

修复 P5 留下的双击保存重复 race bug；widget 跨天；中文化 HANDOFF。

### Added

- `EditViewModel.lastCommittedBody` + 2s 去重窗口，防止双击保存产生重复。
- `MemoRepository.recentEntriesAcrossDays`：widget 跨天显示最近条目。
- BDD 套件扩到 28 场景。
- `RecentEntriesAcrossDaysTest`（11 条）跨天合并排序。
- `DoubleTapSaveTest`（6 条）双击保存去重窗口验证。

### Changed

- HANDOFF.md 中文化。

### Fixed

- #32–#39（8 个 issue）：包括 Widget 跨天零显示 bug、保存 dup race、P5 接入遗留的小问题。

---

## [v0.8.0-p5] - 2025-12 Obsidian 单笔记 scaffold + OAuth + 应用内手册

4 个同行复制的特性 + OAuth + 应用内用户手册 + 启动崩溃修复。

### Added

- **Obsidian-style 单笔记架构 scaffold**（仅数据层，UI 未消费）：
  - `notes/YYYY-MM-DD-HHMM-slug.md` 文件布局。
  - `data/local/SingleNoteEntity.kt`（主键 `uid`；`filePath` UNIQUE；字段：title/body/date/time/isPinned/githubSha/localUpdatedAt/remoteUpdatedAt/dirty/tombstoned）。
  - `data/local/SingleNoteDao.kt`：`observeAll`/`observeRecent`/`get`/`getByPath`/`pending`/`upsert`/`markClean`/`tombstone`/`hardDelete`/`togglePin`。
  - `data/SingleNoteRepository.kt`：CRUD + `togglePin`。
  - `data/notes/NoteSlugger.kt`：`slugOf(body)` 首行去 markdown → 文件系统安全 → 截断 30 字符（支持中文/emoji/Windows 保留字）。
  - Room schema v8 + `MIGRATION_7_8`。
  - `PullWorker` + `PushWorker` 单笔记段。
- **GitHub OAuth Device Flow** 登录：
  - `data/oauth/GitHubOAuthClient.kt` + `GitHubOAuthDto.kt`：POST `/login/device/code` + 轮询 `/login/oauth/access_token`，处理 `slow_down` / `authorization_pending`。
  - `ui/oauth/OAuthSignInDialog.kt` + `OAuthSignInViewModel.kt`：设备码 + 浏览器跳转对话框。
- **应用内用户手册**：`ui/help/HelpScreen.kt` + `MarkdownRenderer.kt`，渲染 `docs/help/*.md`。
- 10 条 `NoteSluggerTest`（中文/emoji/Windows 保留字）。
- 7 条 `SingleNoteRepositoryTest`（entity 构建、`extractTitle`）。
- `GitHubOAuthClientTest`（Device Flow 轮询 / slow_down / authorization_pending）。
- `MarkdownRendererTest`（heading/bold/italic/list/link）。

### Fixed

- 启动崩溃 bug。

---

## [v0.8.0-p4.3] - 2025-11 标签 + 清单 + 置顶 + 快速添加 + 手册

Tags tab / 清单渲染 / 笔记置顶 / 快速添加常驻通知 / OAuth / 应用内手册。

### Added

- **Tags tab**：`data/tag/TagIndexer.kt`（纯函数，解析 `#tag/nested` 含 CJK，产出 `TagNode` 树）+ `ui/tags/TagListScreen.kt` + `TagListViewModel.kt`。
- **清单渲染**：`ui/edit/ChecklistRenderer.kt`，把 `- [ ]` / `- [x]` 渲染成可点击的 Material `Checkbox` 行。
- **笔记置顶**：`isPinned` 列（Room schema v7），`MemoRepository` front-matter 往返。
- **快速添加常驻通知**：`notify/QuickAddNotificationManager.kt` 常驻静音通知 → 点击进 `EditActivity`；`quick_add` channel（IMPORTANCE_LOW，静音，不上 badge）。
- `data/PreferencesStore.kt`：UI 级开关 DataStore（目前只有 `quickAddEnabled`），故意与 `SettingsStore` 分开。
- 6 条 `TagIndexerTest`（平铺/嵌套/CJK/去重/按日聚合）。
- 8 条 `ChecklistLineParserTest`（检测/嵌套/正文抽取）。
- 7 条 `MemoRepositoryPinTest`（置顶 front-matter 解析/往返/排序）。
- `MemoRepositoryFrontMatterTest`（front-matter 往返行为）。

### Changed

- `MemoApplication.onCreate` 从 `PreferencesStore` 重建快速添加通知。

---

## [v0.7.0-p4.2] - 2025-11 5-agent 并行 review 浪潮

关闭 13 个 review issue（issues #19–#31），大量稳定性与正确性修补。

### Added

- HTTP client `HttpTimeout` 超时配置。
- `bootstrapAllNotes` 限速（50 请求/轮，避免一次性烧光 GitHub rate limit）。
- Notes tab 状态保存（`saveState=true`）。
- `CalendarViewModel` 展开结果缓存。
- `note_files.date` 索引（Room schema v6）。
- IcsCodec line folding（75 字节）+ RRULE escape。
- POST_NOTIFICATIONS 设置 deep-link。

### Changed

- `SyncStatusBus` 信号从 try 改 finally 块（确保无论成功失败都 emit）。
- `EventEditDialog.rememberSaveable` 用 `sessionKey` 作用域。
- `RecurrenceChip` 穷尽含 "自定义" 兜底。
- `listDir` 非目录情况安全处理。
- 403 rate-limit 与 auth 错误区分提示。
- `CalendarViewModel.EventExpander` 跑在 `Dispatchers.Default`。
- `NoteFileEntity.date` 加索引。

### Fixed

- #19 HttpTimeout 缺失。
- #20 `bootstrapAllNotes` 无限速烧 quota。
- #21 `SyncStatusBus` finally 块。
- #22 `rememberSaveable` sessionKey 作用域。
- #23 `RecurrenceChip` 穷尽。
- #24 `listDir` 非目录处理。
- #25 403 vs auth 区分。
- #26 POST_NOTIFICATIONS 设置 deep-link。
- #27 `PushWorker` 409 CONFLICT SHA 刷新重试。
- #28 Notes tab 状态保存。
- #29 `note_files.date` 索引。
- #30 `CalendarViewModel` 展开缓存。
- #31 IcsCodec line folding + RRULE escape。

---

## [v0.6.0-p4.1] - 2025-10 事件提醒

事件提醒：AlarmManager + 通知 + 运行时权限。

### Added

- `notify/AlarmScheduler.kt`：`AlarmManager.setExactAndAllowWhileIdle`；RRULE 事件只排下一次发生。
- `notify/EventAlarmReceiver.kt`：触发时发通知 + 计算下次发生 + 再排一次。
- `notify/BootReceiver.kt`：只处理 `BOOT_COMPLETED`，重排所有未来 alarm。
- `notify/NotificationChannelSetup.kt`：建 `event_reminders` channel（IMPORTANCE_DEFAULT，VISIBILITY_PRIVATE）。
- `notify/NotificationPermissionBus.kt`：`StateFlow<Boolean>` 把运行时权限状态告诉 Settings UI。
- POST_NOTIFICATIONS 运行时请求（Android 13+）。
- `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` / `RECEIVE_BOOT_COMPLETED` 权限。
- 用户说明书 USER_GUIDE.md + README 交叉链接。

### Fixed

- #15（事件提醒基础设施）。
- #16（提醒本地偏好保护）。
- #17 `BootReceiver` 只处理 `BOOT_COMPLETED`（不吞其他广播）。
- #18 `EventRepository.AlarmScheduler` 调用失败不致命。

---

## [v0.5.0-p4] - 2025-10 循环事件 + Push UI + 锁屏隐私 + 提醒保留

循环事件 + PullWorker 限速 + NPE 安全 AlarmScheduler + 通知权限请求。

### Added

- 循环事件：`FREQ=WEEKLY` / `FREQ=MONTHLY`（`EventExpander` + `IcsCodec`）。
- Push 操作 UI 反馈。
- 运行时请求 `POST_NOTIFICATIONS`（Android 13+）。

### Changed

- `PullWorker` 限速防烧 quota。
- 事件通知 `VISIBILITY_PRIVATE`（锁屏不泄隐私）。
- `AlarmScheduler` NPE 安全（缺字段/null 参数不崩）。
- `PullWorker` 永不把本地非 null 的提醒被远程 null 覆盖（`reminderMinutesBefore` 是本地偏好，不写进 `.ics` 的 VALARM）。

### Fixed

- #10 循环事件（WEEKLY/MONTHLY）支持。
- #11 PullWorker 限速。
- #12 PullWorker 本地提醒保留（本地非 null 不被远程 null 覆盖）。
- #13 锁屏 VISIBILITY_PRIVATE。
- #14 AlarmScheduler NPE 安全。

### Security

- 事件通知 `VISIBILITY_PRIVATE` 避免锁屏泄露事件标题。

---

## [v0.4.0-p3] - 2025-10 第 1 轮 review 批量修复

P3 Triage：`IcsCodec` 往返修复、全历史备忘拉取、Today widget itemId、日历 marker 移出主线程、中文文案、事件路径身份。

### Added

- 全历史备忘拉取（`bootstrapAllNotes`）。
- IcsCodec 往返测试覆盖。
- 中文 UI 文案。
- `.ics` round-trip 测试。

### Changed

- Today widget `itemId` 加序号（防同一分钟的备忘碰撞）。
- 日历 marker 计算移出主线程。
- 事件路径作为身份（`filePath` 唯一）。

### Fixed

- #1 IcsCodec 往返。
- #2 全历史备忘拉取。
- #3 Today widget `itemId` 同分钟碰撞。
- #4 日历 marker 主线程阻塞。
- #5 中文文案。
- #6 事件路径身份。
- #7 日历 `EventExpander` 跑 `Dispatchers.Default`。
- #8 事件 filePath 唯一。
- #9 IcsCodec RRULE 边界。

---

## [v0.3.0-p2] - 2025-09 日历 + 事件 + 今日 widget

日历月视图 + `.ics` 事件（单次发生）+ 今日 widget + 中文 README。

### Added

- `ui/calendar/CalendarScreen.kt` + `CalendarViewModel.kt` 月视图。
- `data/EventRepository.kt` + `data/local/EventEntity.kt` + `EventDao.kt`：`.ics` 事件存储（单次发生，RFC 5545 子集）。
- `data/ics/IcsCodec.kt`：iCalendar 编解码。
- `data/ics/EventExpander.kt`：把 `EventEntity` + RRULE 展开成时间窗口内的发生列（纯函数）。
- `ui/calendar/EventEditDialog.kt`：事件创建/编辑 UI。
- Today widget（Glance 4×2）：事件 + 今日备忘。
- 中文 README。
- 9 条 `IcsCodecTest`（往返 / UID escape / line folding / RRULE）。
- 9 条 `EventExpanderTest`（WEEKLY/MONTHLY 发生 / 窗口过滤）。

---

## [v0.2.0-p1] - 2025-09 离线优先备忘 + 同步

按天文件备忘 + 离线优先 + PAT 加密 + 底部导航 + 设置 + 首个 widget + 后台同步。

### Added

- **核心架构**：
  - `MemoApplication.kt`：`Application` 子类，`onCreate` → `ServiceLocator.init` + 建通知 channel + 周期 pull 排程 + 一次 push。
  - `MainActivity.kt`：单 Activity，持有 `AppNav`。
  - `EditActivity.kt`：快速添加入口（由 MemoWidget 拉起）。
  - `data/ServiceLocator.kt`：手写 DI，单例托管 `HttpClient(CIO)` / `GitHubApi` / `MemoRepository` / `EventRepository`。
  - `data/Models.kt`：`AppConfig` / `MemoResult<T>` / `ErrorCode` 线上契约。
- **GitHub Contents API 集成**：
  - `data/GitHubApi.kt` + `data/GitHubDto.kt`：`GET/PUT/DELETE /repos/{owner}/{repo}/contents/{path}` 的 Ktor 薄包装。
  - `data/MemoRepository.kt`：`appendToday` / `observeNotes` / `refreshNow`。
  - 按天文件格式 `YYYY-MM-DD.md`，每事件一份 `.ics`。
- **本地持久化**：
  - `data/local/AppDatabase.kt` Room DB。
  - `data/local/NoteFileEntity.kt`：主键 `path`，Room schema v1–v5。
  - `data/local/NoteDao.kt`：`observeAll` / `get(path)` / `upsert` / `markClean`。
  - `data/local/Converters.kt`：`LocalDate` ⇄ epoch day / `LocalTime` ⇄ seconds-of-day。
- **安全存储**：
  - `data/SecurePatStore.kt`：`EncryptedSharedPreferences` + `AndroidKeyStore` 封装 PAT。
  - `data/SettingsStore.kt`：DataStore 存 GitHub 配置（owner/repo/branch/fileStrategy），首次读时把 PAT 从明文 prefs 迁到 Keystore 加密存储。
- **后台同步**：
  - `data/sync/PullWorker.kt`：WorkManager 周期任务，拉 day-files + events 对账回写 Room。
  - `data/sync/PushWorker.kt`：冲刷 `dirty` 行。
  - `data/sync/PathLocker.kt`：每路径一把 `Mutex`，串行化 `appendToday` 与 `PushWorker` 避免 409 SHA race。
  - `data/sync/SyncScheduler.kt`：入队周期 PullWorker + 一次性 PushWorker。
  - `data/sync/SyncStatusBus.kt` / `SyncStatus.kt`：进程内 `StateFlow<SyncStatus>`。
- **UI**：
  - 单 Activity + Compose + `ui/nav/AppNav.kt` 底部导航（Notes / Tags / Calendar / Settings）。
  - `ui/notelist/NoteListScreen.kt`：笔记列表。
  - `ui/EditScreen.kt` + `EditViewModel.kt`：编辑器。
  - `ui/SettingsScreen.kt` + `SettingsViewModel.kt`：GitHub 配置、PAT 掩码、手动刷新。
  - `SyncBanner`：消费 `SyncStatusBus`。
  - 主题 `ui/theme/{Color,Theme,Type}.kt`（Material3 Dynamic Color）。
- **MemoWidget**（2×2）：Glance widget，点击拉起 EditActivity。
- **AndroidManifest** 权限：`INTERNET`。
- `.gradle` 配置：Kotlin 2.0 · AGP 8.7 · JDK 17 · compileSdk 35 · minSdk 26。
- 阿里云 Maven / 腾讯云 Gradle 镜像（`settings.gradle.kts` + `gradle-wrapper.properties`）。
- 单元测试：`AppConfigTest`（4 条 day/week/month 策略的 `filePathFor`）、`MemoResultTest`（4 条 Ok/Err map/flatMap/getOrDefault）。
- Lint baseline。
- E2E 验证 against real GitHub（手工）。
- README：架构图 / 截图 / setup guide。

### Security

- PAT 走 `EncryptedSharedPreferences` + `AndroidKeyStore`，明文永不进 log。
- FLAG_SECURE 在设置页 PAT 明文可见时启用。

### Fixed

- Manifest 里 wire `MemoApplication` 为 `Application` 子类（初始 scaffold 遗漏）。

---

## [v0.1.0] - 2025-09 初始 scaffold

首次可编译的项目骨架（P1 前置）。

### Added

- 初始 memo widget scaffold（后续 P1–P6 架构的胚胎）。
- Gradle 构建（Kotlin 2.0 · AGP 8.7 · compileSdk 35 · minSdk 26）。
- 空 Compose UI + 首个 Widget provider。
- `settings.gradle.kts` + `build.gradle.kts`。

---

## 约定

- **Release 分支**：`feature/p3-polish`（**不是** `master`，`master` 停在 `98724d1`）。
- **版本号**：`vMAJOR.MINOR.PATCH-pN`，`-pN` 表示 Phase 迭代号。
- **versionCode 严格递增**：每次 release 必须 +1；每次 `ALTER TABLE` 必须新加 Room migration 并登记。
- **tag 从 `feature/p3-polish` 打**：不是从 `master`。
- **未发布变更**：进行中的功能记录在 `HANDOFF.md` 的 P6.2 / P8.1 deferred 列表里，不在此 CHANGELOG 中。

## 相关链接

- [README.md](./README.md) — 面向开发者的说明
- [USER_GUIDE.md](./USER_GUIDE.md) — 面向普通用户的说明书
- [HANDOFF.md](./HANDOFF.md) — 面向 AI 接手会话的上下文
- [BDD_SCENARIOS.md](./BDD_SCENARIOS.md) — BDD 场景合集

[v0.12.0-p8]: https://github.com/qqzlqqzlqqzl/memo-widget/releases/tag/v0.12.0-p8
[v0.11.0-p7]: https://github.com/qqzlqqzlqqzl/memo-widget/releases/tag/v0.11.0-p7
[v0.10.1-p6.1.1]: https://github.com/qqzlqqzlqqzl/memo-widget/releases/tag/v0.10.1-p6.1.1
[v0.10.0-p6.1]: https://github.com/qqzlqqzlqqzl/memo-widget/releases/tag/v0.10.0-p6.1
[v0.9.0-p6]: https://github.com/qqzlqqzlqqzl/memo-widget/releases/tag/v0.9.0-p6
[v0.8.0-p5]: https://github.com/qqzlqqzlqqzl/memo-widget/releases/tag/v0.8.0-p5
[v0.8.0-p4.3]: https://github.com/qqzlqqzlqqzl/memo-widget/releases/tag/v0.8.0-p4.3
[v0.7.0-p4.2]: https://github.com/qqzlqqzlqqzl/memo-widget/releases/tag/v0.7.0-p4.2
[v0.6.0-p4.1]: https://github.com/qqzlqqzlqqzl/memo-widget/releases/tag/v0.6.0-p4.1
[v0.5.0-p4]: https://github.com/qqzlqqzlqqzl/memo-widget/releases/tag/v0.5.0-p4
[v0.4.0-p3]: https://github.com/qqzlqqzlqqzl/memo-widget/releases/tag/v0.4.0-p3
[v0.3.0-p2]: https://github.com/qqzlqqzlqqzl/memo-widget/releases/tag/v0.3.0-p2
[v0.2.0-p1]: https://github.com/qqzlqqzlqqzl/memo-widget/releases/tag/v0.2.0-p1
[v0.1.0]: https://github.com/qqzlqqzlqqzl/memo-widget/releases/tag/v0.1.0
