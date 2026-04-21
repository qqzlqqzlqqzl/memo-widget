# HANDOFF — 给下一个 session 的接手文档

> 最后更新：**2026-04-21**，接手时请先读完这份再动代码。

---

## 1. 项目现状速览

| 项 | 值 |
|---|---|
| 最新 release | **v0.6.0-p4.1** · event reminders（[链接](https://github.com/qqzlqqzlqqzl/memo-widget/releases/tag/v0.6.0-p4.1)） |
| 当前分支 | `feature/p3-polish`（HEAD 在此） |
| 默认分支 | `master`（**滞后**，停在 commit `98724d1`，即 P1 之前；release 从 feature 分支打 tag） |
| 工作树状态 | **dirty** — 有 4 个文件未提交：`AndroidManifest.xml`, `ServiceLocator.kt`, `AlarmScheduler.kt`, `BootReceiver.kt`（外加一堆 staged 过但未 commit 的修复，见下文 §5） |
| versionCode / versionName | `6` / `0.6.0-p4.1` |
| Room schema | **v6**（迁移链 v1→v2→v3→v4→v5→v6，全部 ALTER，不丢数据） |
| compileSdk / targetSdk / minSdk | 35 / 35 / 26 |
| Kotlin / AGP / JDK | 2.0.10 / 8.7.3 / 17 |
| 单元测试 | **24 passed**（`AppConfigTest x4` + `MemoResultTest x4` + `IcsCodecTest x7` + `EventExpanderTest x9`） |
| Issue | **18 closed · 13 open**（大半是 self-reported review finding，main path 能跑） |

### 功能清单（到 v0.6.0-p4.1 为止）

- 📝 笔记：按天分组的 Markdown 文件，追加 `## HH:MM\n<body>`
- 📅 日程：标准 iCalendar (`.ics`) 一事件一文件
- 🔁 循环日程：`FREQ=WEEKLY` / `FREQ=MONTHLY`（P4）
- 🔔 本地提醒：AlarmManager + POST_NOTIFICATIONS + 锁屏隐私 + 开机重排 + 循环自动续排（P4.1）
- 🏠 桌面小部件：2×2 MemoWidget（快速写）+ 4×2 TodayWidget（今日日程/笔记）
- 🔍 全文搜索笔记（NoteListScreen OutlinedTextField → ViewModel filter）
- ☁️ GitHub 双向同步：local-first，`appendToday` 立刻 PUT；`PullWorker` 每 30 min；`PushWorker` 重试 + CONFLICT 自愈（SHA 刷新）
- 🟦 `SyncStatusBus` + `SyncBanner`：同步状态进程内 StateFlow → 笔记页顶部红条
- 🔒 PAT 加密：`EncryptedSharedPreferences` + Android Keystore
- 🛡️ FLAG_SECURE：设置页 PAT 明文时自动加，防截屏
- 🔐 PathLocker：文件路径粒度的互斥，防并发写同 SHA
- ⏱️ Ktor HttpTimeout：req/connect/socket 15-30s，防 Worker 永久挂起（工作树已加，**未 commit**）

---

## 2. 代码结构速览（10 句）

```
app/src/main/java/dev/aria/memo/
├── MemoApplication.kt          ← Application；onCreate 调 ServiceLocator.init
├── MainActivity.kt              ← 单 Activity + Compose Nav；onCreate 请求 POST_NOTIFICATIONS
├── EditActivity.kt              ← 快速写一条（从 widget 启动）
├── data/                        ← Models + Repo + Ktor + Room + Sync + PAT 加密存储
│   ├── ics/                     ← 自研 iCalendar 编解码器（line folding + UID escape + RRULE）
│   ├── local/                   ← Room 实体 / Dao / Database / Converters
│   └── sync/                    ← WorkManager：PullWorker + PushWorker + PathLocker + SyncStatusBus
├── ui/                          ← Compose UI（notelist / calendar / nav / SettingsScreen）
├── widget/                      ← Glance：MemoWidget (2×2) + TodayWidget (4×2)
└── notify/                      ← AlarmScheduler / EventAlarmReceiver / BootReceiver / NotificationChannelSetup
```

**核心数据流**：UI → Repository → Room（SoT）+ Ktor API → GitHub。Room 永远是 UI 的数据源；同步是后台 reconcile。

---

## 3. 构建 / 测试 / 发版命令

```bash
# ---- 构建 ----
./gradlew :app:assembleDebug                # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease              # 当前无 keystore，只能算成 unsigned

# ---- 测试 ----
./gradlew :app:testDebugUnitTest            # 全部 24 项
./gradlew :app:testDebugUnitTest --tests "dev.aria.memo.data.ics.IcsCodecTest"

# ---- Lint ----
./gradlew :app:lintDebug                    # 报告在 app/build/reports/lint-results-debug.html

# ---- 清理 ----
./gradlew :app:clean

# ---- 发版（手工流程）----
# 1. 改 app/build.gradle.kts 里的 versionCode + versionName
# 2. 提交 + push 当前 feature 分支
# 3. gh release create vX.Y.Z-pN --target <branch> --title "..." --notes "..."
# 4. gh release upload vX.Y.Z-pN app/build/outputs/apk/debug/app-debug.apk
#    （当前 release 都只附 debug apk；正式签名 APK 要补 keystore 等到 P5）
```

### 镜像 / 代理说明

- `settings.gradle.kts` 已配阿里云 Maven 镜像，国内能直接下依赖
- `gradle/wrapper/gradle-wrapper.properties` 指向腾讯云 Gradle 镜像
- **ADB 镜像（如接 adb install 国产设备）**：这部分 host 侧设置未写入 repo，各自在 `~/.android/adb_usb.ini` 或 `adb shell` 层处理；app 本身不依赖

---

## 4. 当前仍 open 的 issue（13 个）

跑 `gh issue list --state open --limit 50` 确认最新状态。截至接手时：

| # | 严重度 | 标题概要 | 状态 |
|:---:|:---:|---|---|
| #31 | 🟡 perf | CalendarViewModel combine 里 EventExpander.expand 每次 selected 改变都重算 | ✅ **工作树已修** |
| #30 | 🟡 UX | AppNav Notes startDestination tab 切换丢 ViewModel 状态 | 未动 |
| #29 | 🟡 perf | note_files.date 没加索引（Room schema 优化） | ✅ **工作树已修**（schema v6 的 MIGRATION_5_6） |
| #28 | 🟠 corr | IcsCodec RRULE 不 escape + 无 line folding | ✅ **工作树已修** |
| #27 | 🟠 corr | PushWorker CONFLICT 分支无 SHA 刷新逃生口 | ✅ **工作树已修** |
| #26 | 🟡 UX | Rate-limit 403 与 auth 403 无法区分 | 未动 |
| #25 | 🟡 robust | listDir 对非目录 path 静默吞成 UNKNOWN | 未动 |
| #24 | 🟡 UX | POST_NOTIFICATIONS 永久拒绝后无引导去系统设置 | 未动 |
| #23 | 🟡 UX | EventEditDialog RecurrenceChip 不穷尽 | ✅ **工作树已修**（加了"自定义" chip） |
| #22 | 🟠 state | EventEditDialog rememberSaveable(null) 新建模式残留 | ✅ **工作树已修**（sessionKey） |
| #21 | 🟠 UX | SyncStatusBus 在 Worker 被杀时永久卡 Syncing | ✅ **工作树已修**（try/finally） |
| #20 | 🔴 robust | Ktor HttpClient 没配 HttpTimeout，可永久卡 Worker | ✅ **工作树已修** |
| #19 | 🟠 robust | bootstrapAllNotes 无速率保护 | ✅ **工作树已修**（MAX_BOOTSTRAP_PULLS_PER_CYCLE=50） |

**全部 closed 见** `gh issue list --state closed --limit 50`（涵盖 #1 ~ #18）。

---

## 5. 🔴 重要：工作树有未提交的 9 个 issue 修复

`git status` 目前显示 4 个文件 Modified；`git diff HEAD` 还会看到其它改动。**接手第一件事：跑 `git diff HEAD` 看清全部改动再决定是否 commit**。

### 已改文件（非彻底清点，基于 session 尾部 `git diff --stat`）

```
app/src/main/AndroidManifest.xml                          （去掉 LOCKED_BOOT_COMPLETED，配合 #17 的 closed fix）
app/src/main/java/dev/aria/memo/data/ServiceLocator.kt    （install HttpTimeout → #20）
app/src/main/java/dev/aria/memo/data/ics/IcsCodec.kt      （line folding + RRULE escape → #28）
app/src/main/java/dev/aria/memo/data/local/NoteFileEntity.kt（date index → #29）
app/src/main/java/dev/aria/memo/data/sync/PullWorker.kt   （MAX_BOOTSTRAP_PULLS_PER_CYCLE=50 → #19）
app/src/main/java/dev/aria/memo/data/sync/PushWorker.kt   （try/finally → #21 + CONFLICT SHA refresh retry → #27）
app/src/main/java/dev/aria/memo/notify/AlarmScheduler.kt  （windowEnd = Long.MAX_VALUE/2，RRULE 长远事件能排 alarm）
app/src/main/java/dev/aria/memo/notify/BootReceiver.kt    （只接 BOOT_COMPLETED，配合 #17 closed fix）
app/src/main/java/dev/aria/memo/ui/calendar/CalendarViewModel.kt（拆 Expanded+filter → #31）
app/src/main/java/dev/aria/memo/ui/calendar/EventEditDialog.kt （sessionKey + 自定义 chip → #22 #23）
```

### 🎯 下一个 session 建议优先级

1. **先跑一遍测试**：`./gradlew :app:testDebugUnitTest` 确保 24 项全绿
2. **看 `git diff HEAD`** 全量 review 工作树改动
3. **分 commit 提**（按 issue 组），不要一把 squash — 每个 issue 独立便于将来 revert
4. **Push + 标 P4.2 / P4.3 release**：加上 fix 量对得起小版本号
5. **关闭对应 issue**（`gh issue close <n> --comment "fixed in <commit-hash>"`）

---

## 6. 下一阶段（P5）候选

按优先级：

### 🎯 A. 先收工作树 + 关剩余 open issue

- #26 / #25 / #24 / #30 — UX 收尾工作，半天能搞完
- 做完可以打 **v0.7.0-p4.3** 收口 P4

### 🎯 B. Release 签名 APK

- 现在发的都是 debug apk
- P5 补 keystore → `:app:assembleRelease` 能出签名 APK
- 可以考虑走 Play Store（要去广告身份认证等）

### 🎯 C. iCalendar VALARM 支持（跨设备提醒）

- 当前 `reminderMinutesBefore` 是本地字段，不写入 `.ics`
- P5 可以加 `BEGIN:VALARM ... END:VALARM` 写进 ics，换设备后其它设备也能识别
- 但要处理"本地偏好 vs. 远端 VALARM"的优先级 — PullWorker 已有经验（已保留本地 reminder 不被 null 覆盖）

### 🎯 D. RRULE 高级特性

- 当前只支持 `FREQ=WEEKLY` / `FREQ=MONTHLY`，没有 UNTIL / COUNT / EXDATE / BYDAY
- 接下来可以把 `EventExpander` 扩充；UI 上"自定义" chip 就是给这个留的锚点

### 🎯 E. 笔记冲突解决 UI

- 同一天两台设备同时改，CONFLICT 自愈只能处理"SHA stale"，内容冲突（不同设备写了不同内容）目前 app 会覆盖
- CRDT 式 merge 或者 3-way merge diff UI 都是选项

### 🎯 F. 历史笔记懒加载

- bootstrap 一次拉全部，但 UI 只显示 14 天 — 可以做无限滚动加载更早天数

---

## 7. ⚠️ 注意事项（不踩坑）

### Room schema = v6（schema_location 在 `app/schemas/`）

- 新增字段走 `ALTER TABLE`（见 `AppDatabase.MIGRATION_*` 系列）
- `exportSchema = true`，schemas 目录随仓库走；改 schema 一定要相应加 Migration，不然老用户升级闪退
- **不要** bump schema 版本而不写 Migration

### Ktor HttpTimeout 已加（工作树）

- `ServiceLocator` 里 `HttpClient(CIO) { install(HttpTimeout) { ... } }`
- 30s request / 15s connect / 30s socket — 如果以后遇到"特别慢但要等"的端点，可以在单个 `HttpRequestBuilder` 里覆盖；全局兜底 30s

### ADB 镜像 / 国产设备联调

- gradle 镜像 (阿里/腾讯云) 已配好，代码仓库层不管 ADB
- 国内调试国产手机：先把厂商 ADB 驱动装上；开发者模式 + USB 调试；`adb devices` 看见再 `./gradlew :app:installDebug`

### Branch 策略

- `master` 落后 ≈ 5 个 major commit，**不是** release 源
- 每个 P 阶段自起一个 `feature/pN-xxx` 分支，直接在上面打 tag + release
- 当前 HEAD 在 `feature/p3-polish`，下一个阶段可以开 `feature/p5-*`（或者先合 p3-polish 回 master，推荐后者）

### PAT 加密的 Android Keystore 依赖

- `AndroidKeyStore` 从 API 23+ 可用，覆盖 minSdk 26 没问题
- 但 emulator 上某些 rev 有 bug，测试如果碰到 KeyStoreException 换机器试

### 国产 ROM 自启动

- 提醒功能在 Xiaomi/MIUI / Huawei/EMUI / OPPO/ColorOS / vivo/FuntouchOS 上都要用户手动加自启动白名单
- 这个在 USER_GUIDE 里单独列表说了，但**没写代码自动检测** —— 未来可以加一个"启动后自动重排 alarm + 检测系统是否允许"的 health check

### 循环事件 AlarmManager 策略

- 永远只占 **1 个 slot**（响完当前这次，EventAlarmReceiver 才排下一次）
- 这是为了不塞爆 AlarmManager（某些 ROM 有 500 个 alarm 硬限制）
- 副作用：app 被系统彻底冻结（不只是杀进程，而是 Disabled Force-stop 级）时下一个循环不会排 — 这种 case 只能靠 BootReceiver 重新启动

### FLAG_SECURE 作用域

- 当前**只在设置页 PAT 明文可见时**生效，其它 tab 正常截屏
- 如果以后加了"显示私密笔记"功能，记得扩展 FLAG_SECURE 生命周期

---

## 8. 快速 self-check

开一个新 session 前，跑这几条确认环境 OK：

```bash
cd /Users/aria-score-00/AndroidProjects/memo-widget
git status                                        # 应看到 feature/p3-polish，和上面 §5 列的 dirty files
git log --oneline -5                              # HEAD 应是 e56f0c2 "docs: user guide..."
./gradlew :app:testDebugUnitTest --rerun-tasks    # 24 passed
gh release list --limit 3                         # 最上面是 v0.6.0-p4.1
gh issue list --state open --limit 20             # 13 个 open
```

如果上面任一条不对 → 先同步环境再动手。

---

## 9. 联系方式 / 上下文

- Repo owner: `qqzlqqzlqqzl`
- Repo: https://github.com/qqzlqqzlqqzl/memo-widget
- 前一个 session 的产物：p3-polish 分支 + v0.6.0-p4.1 release + 这份 HANDOFF
- 本 session 没有改 `.kt` / `.xml` / `.gradle` 文件，只动了 `.md`

祝接手顺利 🚀
