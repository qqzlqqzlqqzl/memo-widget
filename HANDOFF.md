<!--
HANDOFF.md — memo-widget Android app 的 AI 接手文档（AI-facing handoff）。
受众：下一个接手此仓库的 AI 会话 / Agent。
优化目标：零歧义、低 token 扫描、命令可复制粘贴执行。
面向人类的文档在 README.md 和 USER_GUIDE.md — 不要在本文件里重复那些内容。
-->

# HANDOFF（AI 接手版）

> 给下一个会话的机器可读上下文。下文出现的所有绝对路径、命令、标识符都是字面值。
> 如果本文件与仓库实际状态冲突，以仓库为准 — 然后回来修这份文件。

上次重新生成：**2026-04-22**，基于 `feature/p3-polish` 分支 P6 版本。当前 release：v0.9.0-p6。

## 🔴 P6.1 下一轮必做（按优先级）

1. **UI 集成 SingleNoteRepository**（scaffold 已就绪但 UI 层未消费 — 用户点"+"仍走老 MemoRepository.appendToday）
   - `NoteListViewModel`：`combine(memoRepo.observeNotes(), singleNoteRepo.observeAll())` 合并两种来源成 `List<UiNoteItem>` sum type
   - `NoteListScreen`：展示合并列表，pinned 行可跨两种格式
   - `EditActivity` 的写入路径：改走 `singleNoteRepo.create(body)` 而非 `memoRepo.appendToday(body)`
   - Widget `MemoWidget`：替换成 `singleNoteRepo.observeRecent(limit=3)` + legacy fallback
2. **"一键迁移"按钮**：SettingsScreen 加按钮，把现有 `YYYY-MM-DD.md` 的每个 `## HH:MM` 块拆成 `notes/YYYY-MM-DD-HHMM-slug.md`，原文件 tombstone
3. **Room MigrationTestHelper**：v1→v8 迁移链单元测试（现在只验证了前 6 段跳运行时，未 instrumented 测）
4. **recentEntriesAcrossDays SQL LIMIT 层优化**：当前 `dao.observeAll().first()` 全表读取，应下推到 SQL `ORDER BY date DESC LIMIT X`
5. **CalendarViewModel mutating 原子化**：改 `AtomicBoolean.compareAndSet` 或 `Mutex`
6. **PullWorker 统一 rate-limit budget**：notes bootstrap 50 + window 14 + events 50 + singlenotes 50 = 164 per cycle，集成一个全局预算
7. **FrontMatter codec 抽到独立类**：现在 `SingleNoteRepository.togglePin` 跨类调 `MemoRepository.applyPinFrontMatter`

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
| 最近发布的 release | `v0.7.0-p4.2`（tag，2026-04-21T10:00Z） |
| Release 来源分支 | `feature/p3-polish`（tag 从这里打，**不是** `master`） |
| 当前分支 | `feature/p3-polish` |
| HEAD SHA | `68c0f43` — *"feat(p4.2): close 13 review issues + 5-agent parallel fix wave"* |
| `master` 分支 | **陈旧**。停在 `98724d1`（P1 之前）。不要从它合并、不要从它 pull、不要从它打 release。 |
| `versionCode` / `versionName` | `7` / `0.7.0-p4.2`（`app/build.gradle.kts:17-18`） |
| 工具链 | Kotlin 2.0.10 · AGP 8.7.3 · JDK 17 · compileSdk/targetSdk 35 · minSdk 26 |
| Room schema | `v7` — 最近一次迁移 `MIGRATION_6_7` 给 `note_files` 加了 `isPinned`。schema 导出在 `app/schemas/dev.aria.memo.data.local.AppDatabase/{1..7}.json`。 |
| 单元测试 | **共 47 项**，分布在 7 个文件（详见 §6）。 |
| GitHub issues | **0 个 open · 31 个 closed**（#1–#31 全部在 P3/P4/P4.2 的修复浪潮里关掉）。 |
| 工作树状态 | **DIRTY** — P4.3 工作还没提交（tags 标签页 + checklist 渲染 + pin 置顶 + 快速添加通知）。详见 §10。 |

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
| `data/PreferencesStore.kt` | **新**（P4.3 WIP）。专门给 UI 级开关用的小 DataStore（目前只有 `quickAddEnabled`）。故意与 `SettingsStore` 分开。 |
| `data/ics/IcsCodec.kt` | RFC 5545 子集编解码器。处理 line folding（75 字节）、UID escape、RRULE escape。conformance 看 `IcsCodecTest`。 |
| `data/ics/EventExpander.kt` | 把一个 `EventEntity` + RRULE 展开成某时间窗口内的发生列。纯函数，单测覆盖。 |
| `data/tag/TagIndexer.kt` | **新**（P4.3 WIP）。从所有缓存日文件的 memo body 里解析 `#tag/nested`（含 CJK），返回一棵 `TagNode` 树。纯函数。 |
| `data/local/AppDatabase.kt` | Room DB。实体：`NoteFileEntity`、`EventEntity`。迁移 1→7。 |
| `data/local/NoteDao.kt` | `observeAll`、`get(path)`、`upsert`、`markClean`、`setPinned`（P4.3 WIP）。 |
| `data/local/EventDao.kt` | 事件 CRUD + `observeBetween(startMs, endMs)`。 |
| `data/local/NoteFileEntity.kt` | 主键 `path`。`date` 有索引（issue #29）。`isPinned` 列（P4.3 WIP，schema v7）。 |
| `data/local/EventEntity.kt` | 主键 `uid`。`filePath` 唯一索引。可空字段 `rrule`、`reminderMinutesBefore`、`tombstoned`。 |
| `data/local/Converters.kt` | `LocalDate` ⇄ epoch day converters。 |
| `data/sync/PullWorker.kt` | WorkManager 任务。`bootstrapAllNotes` 首次引导（封顶 `MAX_BOOTSTRAP_PULLS_PER_CYCLE=50`，issue #19），拉事件，硬删时取消 alarm。 |
| `data/sync/PushWorker.kt` | 冲刷 `dirty` 行。409 时刷新 SHA 重试一次（issue #27）。通过 `try/finally` 给 `SyncStatusBus` 发信号（issue #21）。 |
| `data/sync/PathLocker.kt` | 每路径一把 `Mutex`。用 `withLock(path) { ... }`。 |
| `data/sync/SyncScheduler.kt` | 入队周期 `PullWorker` + 一次性 `PushWorker`。 |
| `data/sync/SyncStatusBus.kt` / `SyncStatus.kt` | 进程内 `StateFlow<SyncStatus>`，笔记页的 `SyncBanner` 消费。 |
| `ui/nav/AppNav.kt` | 底部导航：`Notes` · `Tags` · `Calendar` · `Settings`（Tags 是 P4.3 WIP）。`NavController` + `saveState=true`。 |
| `ui/notelist/NoteListScreen.kt` + `NoteListViewModel.kt` | 日文件列表、搜索、同步横幅、pin 切换（P4.3 WIP）。 |
| `ui/calendar/CalendarScreen.kt` + `CalendarViewModel.kt` | 月视图 + 当日事件清单。`EventExpander` 跑在 `Dispatchers.Default`（issue #7），结果有缓存（issue #31）。 |
| `ui/calendar/EventEditDialog.kt` | 创建/编辑事件 UI。`rememberSaveable` 用 `sessionKey` 作用域（issue #22）。RecurrenceChip 穷尽含 "自定义" 兜底（issue #23）。 |
| `ui/edit/ChecklistRenderer.kt` | **新**（P4.3 WIP）。把 `- [ ]` / `- [x]` markdown 清单行渲染成可点击的 Material `Checkbox` 行。 |
| `ui/tags/TagListScreen.kt` + `TagListViewModel.kt` | **新**（P4.3 WIP）。展示 `TagIndexer` 输出的树。 |
| `ui/EditScreen.kt` + `EditViewModel.kt` | `MainActivity` / `EditActivity` 共用的编辑器。 |
| `ui/SettingsScreen.kt` + `SettingsViewModel.kt` | GitHub 配置、PAT 掩码、快速添加开关、通知权限引导、手动刷新。 |
| `widget/MemoWidget.kt` + `MemoWidgetContent.kt` + `MemoWidgetReceiver.kt` | Glance 2×2 快速写 widget。点一下拉起 `EditActivity`。 |
| `widget/TodayWidget.kt` + `TodayWidgetContent.kt` + `TodayWidgetReceiver.kt` | Glance 4×2 今日列表（事件 + 备忘）。`itemId` 里带一个序号，防止同一分钟的备忘碰撞（issue #3）。 |
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
| P4.2 | `v0.7.0-p4.2`（当前） | 5-agent review 浪潮：HttpTimeout、`bootstrapAllNotes` 限速、SyncStatusBus finally 块、`rememberSaveable(sessionKey)`、RecurrenceChip 穷尽、listDir 非目录处理、403 rate-limit 与 auth 区分、POST_NOTIFICATIONS 设置 deep-link、Notes tab 状态保存、CalendarViewModel 展开缓存、`note_files.date` 索引（schema v6）、IcsCodec line folding + RRULE escape、PushWorker CONFLICT SHA 刷新。 | #19–#31 |
| **P4.3 WIP** | *(未发布)* | **标签页（Tags tab）**（`TagIndexer` → `TagListScreen`）、**清单渲染**（`ChecklistRenderer`）、**笔记置顶**（`isPinned` 列，schema v7）、**快速添加常驻通知**（`QuickAddNotificationManager` + `PreferencesStore`）。 | — |

---

## 6. 测试清单

全部跑：`./gradlew :app:testDebugUnitTest`

| 文件 | 测试数 | 主题 |
|---|---:|---|
| `app/src/test/java/dev/aria/memo/data/AppConfigTest.kt` | 4 | 按 day/week/month 策略的 `filePathFor()` |
| `app/src/test/java/dev/aria/memo/data/MemoResultTest.kt` | 4 | `Ok`/`Err` 的 map/flatMap/getOrDefault |
| `app/src/test/java/dev/aria/memo/data/ics/IcsCodecTest.kt` | 9 | 往返、UID escape、line folding、RRULE |
| `app/src/test/java/dev/aria/memo/data/ics/EventExpanderTest.kt` | 9 | WEEKLY/MONTHLY 发生、窗口过滤 |
| `app/src/test/java/dev/aria/memo/data/tag/TagIndexerTest.kt` | 6 | 平铺/嵌套/CJK 标签、去重、按日聚合 |
| `app/src/test/java/dev/aria/memo/data/MemoRepositoryPinTest.kt` | 7 | 置顶 front-matter 解析 / 往返、排序 |
| `app/src/test/java/dev/aria/memo/ui/edit/ChecklistLineParserTest.kt` | 8 | `- [ ]` / `- [x]` 检测、嵌套、正文抽取 |
| **合计** | **47** | |

还没有 instrumented（`androidTest`）用例 — 加一个需要模拟器，见 §9。

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
./gradlew :app:testDebugUnitTest                                                    # 全部 47
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

HEAD `68c0f43` 时 `git status --porcelain` 显示的是**一波 P4.3 特性 WIP**：

### Modified（已跟踪）

```
M  app/src/main/java/dev/aria/memo/MemoApplication.kt                    (从 PreferencesStore 重建快速添加通知)
M  app/src/main/java/dev/aria/memo/data/MemoRepository.kt                 (+159 行：pin 切换、front-matter 往返)
M  app/src/main/java/dev/aria/memo/data/local/AppDatabase.kt              (schema v6 → v7，MIGRATION_6_7)
M  app/src/main/java/dev/aria/memo/data/local/NoteDao.kt                  (setPinned + pinned-first ORDER BY)
M  app/src/main/java/dev/aria/memo/data/local/NoteFileEntity.kt           (isPinned 列)
M  app/src/main/java/dev/aria/memo/notify/NotificationChannelSetup.kt     (ensureQuickAddChannel)
M  app/src/main/java/dev/aria/memo/ui/EditScreen.kt                       (ChecklistRenderer 集成)
M  app/src/main/java/dev/aria/memo/ui/EditViewModel.kt                    (toggle checklist 行)
M  app/src/main/java/dev/aria/memo/ui/SettingsScreen.kt                   (快速添加开关)
M  app/src/main/java/dev/aria/memo/ui/nav/AppNav.kt                       (Tags tab + route)
M  app/src/main/java/dev/aria/memo/ui/notelist/NoteListScreen.kt          (pin 星号按钮)
M  app/src/main/java/dev/aria/memo/ui/notelist/NoteListViewModel.kt       (onTogglePin)
```

### 新文件（untracked）

```
A  app/schemas/dev.aria.memo.data.local.AppDatabase/7.json
A  app/src/main/java/dev/aria/memo/data/PreferencesStore.kt
A  app/src/main/java/dev/aria/memo/data/tag/TagIndexer.kt
A  app/src/main/java/dev/aria/memo/notify/QuickAddNotificationManager.kt
A  app/src/main/java/dev/aria/memo/ui/edit/ChecklistRenderer.kt
A  app/src/main/java/dev/aria/memo/ui/tags/TagListScreen.kt
A  app/src/main/java/dev/aria/memo/ui/tags/TagListViewModel.kt
A  app/src/test/java/dev/aria/memo/data/MemoRepositoryPinTest.kt
A  app/src/test/java/dev/aria/memo/data/tag/TagIndexerTest.kt
A  app/src/test/java/dev/aria/memo/ui/edit/ChecklistLineParserTest.kt
```

**Diff 统计**：16 个文件变更，**+1125 / −25** 行。

### 建议的下一个会话入口

1. `./gradlew :app:testDebugUnitTest` → 确认工作树下 **47** 项测试全部过。
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

## 11. 推荐的 P5.x 方向（ROI 排序）

1. **P5.0 — Release hardening**（keystore + 签名 APK + tag 推送触发 `assembleRelease` 的 GitHub Actions）。打通 Play Store，约 1 个会话。
2. **P5.1 — `.ics` VALARM 跨设备提醒**（把 `BEGIN:VALARM`/`END:VALARM` 写进 ics；pull 时只有本地为 null 才 fold 进 `reminderMinutesBefore`）。遵守不变量 #5。约 1 个会话。
3. **P5.2 — RRULE 扩展**（给 `EventExpander` + `IcsCodec` 加 `UNTIL`、`COUNT`、`BYDAY`、`EXDATE`；UI chip 已经有 "自定义" 做挂钩）。`IcsCodecTest` 加往返测试。约 2 个会话。
4. **P5.3 — Notes 懒加载历史**（滚过 14 天后继续回拉；Room paging3）。只要不动 Room schema 就便宜。
5. **P5.4 — ROM 限制健康检查**（MIUI/EMUI/ColorOS/Funtouch 上检测 autostart 关闭 / 电池白名单缺失，设置页挂一个卡片指向对应系统 intent）。用户可见的收益，没架构风险。

明确**不**在 P5.x 范围内：CRDT 合并、多用户共享、服务器组件、附件 / 二进制 blob、repo 内容端到端加密。

---

## 12. 会话自检（开工前先贴一遍）

```bash
cd /Users/aria-score-00/AndroidProjects/memo-widget
git rev-parse HEAD                                         # 期望 68c0f43（或更新的 commit）
git status --porcelain                                     # 期望 DIRTY — 与 §10 所列一致
./gradlew :app:testDebugUnitTest --rerun-tasks             # 期望：47 过，0 失败
gh release list --limit 3                                  # 榜首：v0.7.0-p4.2
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
