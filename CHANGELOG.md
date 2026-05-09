# 更新日志

本项目所有显著变更都会记录在此文件中。

格式基于 [Keep a Changelog 1.1.0](https://keepachangelog.com/zh-CN/1.1.0/)，并遵循 [语义化版本 2.0.0](https://semver.org/lang/zh-CN/)。

本项目使用 `vMAJOR.MINOR.PATCH-pN` 版本号格式，其中 `-pN` 表示迭代阶段（Phase）。Release 分支为 `feature/p3-polish`（**不是** `master`）。

---

## [v0.12.22-p8.4] - 2026-05-09

P8.4 — 30-bucket parallel review-driven fix。先用 30 个 review subagent 对整库做了 30 个独立维度的并行审查（安全 / Manifest 暴露面 / OAuth / AI 客户端 / Path traversal / 网络配置 / R8 ProGuard / Glance 生命周期 / AlarmManager / WorkManager / Receiver / Compose 状态 / Room 迁移 / 备份 / 数据一致性 / Sync 冲突 / FrontMatter / Pin/Tag/Calendar / Compose 性能 / 主题 / a11y / i18n / 错误提示 / 测试质量 / 依赖 CVE / CI / Crash / 启动 / 文档 / BDD），然后用 30 个 fix subagent 在独立 git worktree 并行修改互不重叠的文件，最后合并到 master 并跑完 unit + R8 release + lint + assembleDebug + androidTest 编译 + emulator BDD instrumented 一整套测试。

### Added
- **`res/xml/network_security_config.xml`**：Android 9+ 默认禁止明文 HTTP（防 PAT 在内网 MITM 泄漏）；仅对 `localhost` / `127.0.0.1` / `::1` 三个 loopback host 放行 cleartext，让本机 Ollama 仍可用。Manifest `<application>` 加 `android:networkSecurityConfig="@xml/network_security_config"`。
- **`notify/NotifyUtils.kt`**：把 AlarmScheduler 的 `stableRequestCode(uid)` (FNV-1a 32bit) 提取到包级 `internal fun`，让 `EventAlarmReceiver` 的通知 PendingIntent requestCode 与 AlarmScheduler 一致，消除两个不同 uid hashCode 碰撞时 PendingIntent 错位（用户点错通知打开错笔记）的隐患。
- **OAuth URL-encode 回归测试** (`GitHubOAuthClientTest::requestDeviceCode url-encodes special characters in clientId`)：用 MockEngine 捕获 raw form body，断言 `&` `=` `+` ` ` `%` 等危险字符被 URLEncoder 正确 escape，关闭 issue [#116](https://github.com/qqzlqqzlqqzl/memo-widget/issues/116)。
- **`StrictMode` debug 启用** (`MemoApplication.onCreate`)：penaltyLog only，BDD #1184 要求的主线程 IO 探测；不 penaltyDeath 避免开发期意外崩。
- **`MemoApplication.ioScope` 提为成员属性** + `onTerminate { ioScope.cancel() }`，避免 Robolectric 多次 onCreate 时 collector 泄漏。

### Changed (Security)
- **删除 `USE_EXACT_ALARM`**：与 `SCHEDULE_EXACT_ALARM` 同时声明会被 Google Play 政策拒审（备忘录非日历类 app 不该持有 USE_EXACT_ALARM 受限权限），保留可被用户撤销的 SCHEDULE_EXACT_ALARM 走现有的 `setExactOrInexact` 降级路径。
- **`BootReceiver` `exported=false`** 并删除虚假 `android:permission`：四个系统 action（BOOT_COMPLETED / MY_PACKAGE_REPLACED / TIMEZONE_CHANGED / SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED）都由系统进程发送，protected broadcast 不受 exported 影响仍能投递；同时消除 attempt 假冒 BootReceiver 的攻击面。
- **`EditActivity` `EXTRA_PATH` 白名单校验**：必须 `notes/` 前缀或 `\d{4}-\d{2}-\d{2}\.md` 日条目格式；含 `..` / `\\` / 绝对前导 `/` / 控制字符即视为非法，记录 `Log.w` 并降级为 null。
- **`AppConfig.filePathFor` 拒绝 path traversal**：用户填入的 `pathTemplate` 替换占位符后再校验，含 `..` 段 / `//` / `\\` / 控制字符 / 绝对前导 `/` 即抛 `IllegalArgumentException`，下游 `runCatchingHttp` 转 `MemoResult.Err`。
- **`GitHubApi.buildUrl` 拒绝 `..` / `.` segment**：双重防御，即使前置校验绕过也无法构造出会被 GitHub 服务端 normalize 到仓库内任意路径的 URL。
- **`AiClient` URL 严格化**：用 `java.net.URI` 解析（不再手撕字符串），host 严格相等比较 loopback 白名单；拒绝 RFC1918 私网（10/8、172.16/12、192.168/16、169.254/16 link-local）+ IPv6 ULA/link-local 上的 `http://`（防内网 SSRF + PAT 通过明文泄漏）；拒绝 URL 含 userInfo（`@` 注入绕过 host 检查）。
- **`SettingsStore.migrateLegacyPat` 立即清明文**：迁移到 `SecurePatStore` 后立刻 `settingsDataStore.edit { remove(PAT_LEGACY) }`，不依赖下次 `update()` 触发的懒删（用户迁移后从不再点设置则永不触发）。
- **`SettingsStore.switchAccount` 清 AI 凭据**：通过 `ServiceLocator.aiSettings.save("","","")` 清空 provider URL / API key / model，避免共享设备时旧账号 AI key 泄给新用户。
- **`AiSettingsStore.clear()`**：新增方法，硬删 EncryptedSharedPreferences 的 `apiKey` 条目 + DataStore 的 `provider_url` / `model` 键。
- **`LogExporter` token redact**：写每行前用 regex 过滤 `Bearer xxx` / `ghp_*` / `gho_*` / `Authorization: ...` / `sk-*` AI key 前缀，避免用户分享日志给开发者排查时泄漏凭据；crash 文件单文件 512KB 上限，超出截断 + 标 `(truncated)`。

### Changed (Correctness)
- **`proguard-rules.pro` 加 Room TypeConverter keep**：`-keep @androidx.room.TypeConverters class * { *; }` + `-keepclassmembers class * { @androidx.room.TypeConverter <methods>; }`。原规则只覆盖 `_Impl` / `@Entity`，没 keep `Converters.kt` 类与方法名，R8 release 会改名导致 `LocalDate` / `LocalTime` 字段反射查找失败 → `ColumnTypeAdaptException`。
- **Glance widget `provideGlance` IO 切到 IO dispatcher**（`MemoWidget` / `TodayWidget`）：`settingsStore.current()` / `singleNoteRepo.observeRecent.first()` / `repository.recentEntriesAcrossDays` 全部 `withContext(Dispatchers.IO)`，保留 `withTimeoutOrNull` 兜底；遗留 TODO(P9) 标记下一步把 IO 真正提到 `update()` override + `GlanceState` 写入。
- **`TodayWidget` zone 注入贯通到 `EventLine`**：之前 `TodayWidgetContent.EventLine` 内部硬编码 `ZoneId.systemDefault()`，与 `TodayWidget` 注入的 `clock.zone` 不一致；现在 `TodayWidget` 把 `zone.id` 写入 `PreferencesGlanceStateDefinition`（`PREF_KEY_ZONE_ID`）+ `TodayWidgetContent(zone)` 函数参数双链路，`EventLine` 用 `zone` 做 epoch→本地时间换算。
- **`DateChangedReceiver` 改 `goAsync()` + IO 协程做 `ServiceLocator.init`**：冷进程启动时 init 包含 Room build + HttpClient 构造可能耗时数百 ms，主线程同步会触发 ANR；`refreshAll` 是 SharedFlow tryEmit 留在主线程让 testScheduler 可观察。`goAsync()` 在 Robolectric 下 null-safe。
- **`BootReceiver.rescheduleAll` 加 `withTimeoutOrNull(8_000)`**：goAsync 的 10s ANR 窗口预算保护，超时仍 finally pending.finish()。
- **`EventAlarmReceiver.postNotification` 移入 goAsync 协程**：避免主线程跨进程 NotificationManager.notify Binder IPC 占满 onReceive 的 10s 主线程预算。
- **`SingleNoteRepository.restoreFromTombstone` 加 `PathLocker.withLock` + 锁内复查**：消除「PushWorker 持锁执行 DELETE 的同时 restore 并发写 tombstoned=0 → PushWorker.hardDelete 清掉刚恢复的行」TOCTOU 竞态。
- **`PullWorker` tombstone 复活防御**：events / single_notes 段 upsert 前加 `if (localByPath?.tombstoned == true) continue`，避免「用户已删但 PushWorker DELETE 还没发出，PullWorker 拉到 remote 仍存在该文件，upsert tombstoned=false 复活笔记」典型 sync 雷。
- **`PullWorker` network error retry 前 emit `SyncStatus.Error(NETWORK)`**：与 PushWorker 行为对齐，UI 现在能看到「网络错误，稍后重试」。
- **`PushWorker` 异常 emit Status 后再 rethrow**：避免 doWorkInner 抛非 Cancellation 异常被 WorkManager 静默归 failure 而 UI 不知情。
- **`SyncScheduler.enqueuePullNow` 加 `setBackoffCriteria(EXPONENTIAL, 30s)`**：之前缺 backoff 走默认 LINEAR 30s，网络抖动时高频重试加速消耗 GitHub rate limit；与 PushWorker 对齐。
- **`EditViewModel` 接 `SavedStateHandle`** 并提供 2-arg `factoryFor(owner: SavedStateRegistryOwner, noteUid: String?)`；`EditActivity` 切到该 factory，让 `_body` / `noteUid` 跨 process death 持久化（之前是纯内存 MutableStateFlow，系统 kill 后用户回到 EditActivity 草稿丢失）。
- **`EditScreen` LaunchedEffect 不再强制把 selection 重置到末尾**：原行为 checklist toggle 时打断用户中间光标位置；现在仅在「内容真变了 + (已在末尾 OR 初始空)」才重置，否则 clamp 到不超过新长度的原位置。
- **`SettingsScreen` 7 个 dialog/draft state 改 `rememberSaveable`**：`patVisible` / `aiKeyVisible` / `showSwitchAccountDialog` / `showClientIdDialog` / `showOAuthDialog` / `pendingClientId` / `clientIdDraft`，旋转后不再丢；尤其 `showOAuthDialog` 影响 device-flow 等待期旋转手机时不丢对话框。
- **`NoteListScreen` SyncBanner 按 `ErrorCode` 映射友好文案**：`UNAUTHORIZED→"GitHub 认证失败，请检查 PAT"` / `NETWORK→"网络错误，稍后会自动重试"` / `CONFLICT→"并发冲突，已自动重试"` / `NOT_FOUND→"远程文件不存在"` / `NOT_CONFIGURED→"尚未配置 GitHub 同步"` / `UNKNOWN→"同步失败"`；原始 `err.message` 仅 BuildConfig.DEBUG 拼在括号内供排查（避免 GitHub raw JSON 片段直接给最终用户看）。
- **`AiChatViewModel` 错误兜底**：catch 用 `humanMessage(t)` 映射网络/超时/401-403/429/5xx/CancellationException 不同类型，避免把 `t.javaClass.simpleName`（如 `NullPointerException`）当 Snackbar 文字给用户看；原始 message 仅 `Log.e` 到 logcat 供排查。
- **Widget 刷新 Toast 改 `"正在刷新…"`**（`MemoWidgetContent` / `TodayWidgetContent`）：原文案 `"已刷新"` 在 `updateAll` 还在异步执行时就先弹（white-lie），失败时也欺骗用户；现在 `runCatching {}.onFailure` 主线程 post `"刷新失败，请检查网络"`。

### Changed (CI / Build)
- **`.github/workflows/ci.yml` `dependency-graph` job 加 `permissions: contents: write`**：原状态返回 `403 Resource not accessible by integration`，每次 master push 这个 job 都 fail；现在 dependency-submission action 能向 GitHub Dependabot 写依赖快照。同时加 `continue-on-error: true` 避免 repo 层 Dependency graph 未启用时把整个 workflow 染红。
- **Glance `1.1.0 → 1.1.1`**（`gradle/libs.versions.toml`）：CVE-2024-7254 protobuf-java DoS（CVSS 7.5–8.7）缓解，glance-appwidget-proto 的 transitive 依赖。
- **依赖大升级二期 (post-p8.4 drain)**：dependabot 触发的 minor/patch 浪潮全部并入 master——AGP 8.7.3→8.10.1（Compose BOM 2026.05 的 AAR metadata 7 要求）+ Gradle 8.9→8.11.1（AGP 8.10 要求）+ WorkManager 2.9.1→2.11.2 + navigation-compose 2.8.4→2.9.8 + datastore 1.1.1→1.2.1 + Material 1.12.0→1.13.0 + Robolectric 4.14.1→4.16.1（实测 JDK 17 仍能跑，旧注释里"4.16+ 要 JDK 21"作废）+ androidx.test:runner/core-ktx/rules 1.6.x→1.7.0 + ext:junit 1.2.1→1.3.0。Room 2.8.4 升级路径触发 kotlinx-serialization `AbstractMethodError typeParametersSerializers`，已回退到 2.6.1 稳态；KSP 2.3.7 / Kotlin 3.x 跨大版本闭锁，关了 PR 留待 Kotlin major bump 时一起做。
- **`.github/dependabot.yml`**：加 weekly 调度（Mon 09:30 Asia/Shanghai）+ compose/room/lifecycle/kotlinx/ktor 分组，让升级波动有节律不堵 PR 列表。
- **`.github/workflows/ci.yml` `dependency-graph` job 加 `DEPENDENCY_GRAPH_INCLUDE_CONFIGURATIONS=".*RuntimeClasspath"`**：原默认提交了整棵 buildscript 依赖，把 gradle 插件传递依赖里的 netty/bouncycastle/jose4j/jdom2/protobuf-java/commons-compress/guava 全部当成 APK 依赖给 Dependabot 报警 (30 条 high/medium，全部从 `settings.gradle.kts` 来)；filter 后只提交 `*RuntimeClasspath` 配置，避免误伤。配套手动 dismiss 旧 30 条 alert (reason: `not_used`)。

### Methodology / Process
- **30 个 review subagent 并行审查**：每个 agent 聚焦一个独立维度，置信度 ≥ 80% 才输出问题，避免水分。
- **30 个 fix subagent 在独立 worktree 并行修复**：30 桶按文件归属切分，零文件重叠，git apply 时零冲突；3 处 B17/B21 zone wiring + B18/B19 onDeleted（Glance 默认已自带 cleanup，spurious override 还原）+ B24 refreshAll 测试可观察性 在合并阶段手动协调修复。
- **6 路并行 test 验证**：`testDebugUnitTest`（3223 全绿）+ `minifyReleaseWithR8`（0 missing-class）+ `lintDebug`（0 ERROR）+ `assembleDebug`（24.8MB APK）+ `compileDebugAndroidTestKotlin`（编译过）+ CI emulator BDD instrumented（9 scenario 全绿）。

### Known Limits / Deferred to Next Wave
- **i18n 二期**：50+ 处 Compose / Glance / Toast 硬编码中文未抽到 `strings.xml`、`values-en/strings.xml` 未建、`DateTimeFormatter.ofPattern("yyyy 年 MM 月 dd 日")` 等硬模板未改 `Locale.getDefault()`、`CalendarScreen` 一处硬编码 `Locale.SIMPLIFIED_CHINESE`。
- **a11y 二期**：`MemoCard` / `DayCell` 缺 `Role.Button`、`ChecklistRow` 未合并 `semantics(mergeDescendants)`、`ScrollAwareFab` 折叠态 `contentDescription = null`、`CloudOff` 装饰图标可聚焦但无操作。
- **依赖大升级二期（部分关闭）**：post-p8.4 drain 把 AGP / Gradle / WorkManager / navigation-compose / datastore / Material / Robolectric / test 系列全部刷到当前。剩余落后项：Ktor 2.3.13（→ 3.0 大版本，API 改动较多）、Room 2.6.1（→ 2.8.x 因 kotlinx-serialization `AbstractMethodError typeParametersSerializers` 回滚，等 serialization 1.8+ 兼容矩阵稳定）、kotlinx-coroutines 1.8.1（→ 2.0 兼容性待评估）、serialization 1.7.2（与 Room 2.8 互斥锁住）、Kotlin 2.0.21（→ 2.3 触发 KSP 跨大版本，留 P9 主升级波）。
- **`PullBudget.tightenFromHeader` 接入**：当前是死代码；`MemoResult.Ok` 不携带 HTTP 响应头 → PullWorker 无法把 `X-RateLimit-Remaining` 透出给 `PullBudget`。需要 GitHubApi + MemoResult 跨文件改造。
- **Glance `provideGlance` 真正移到 `update()`**：当前只用 `withContext(Dispatchers.IO)` 兜底，IO 仍绑在 render pass 上；正确做法是 override `update()` + `updateAppWidgetState` 写入 GlanceState，`provideGlance` 只 `currentState()` 读。
- **CI 优化**：`concurrency.cancel-in-progress` / dependabot config / instrumented job 的 PR 触发条件收紧。

---

## [v0.12.18-p8 → v0.12.21-p8] - 2026-04-29

R8 install-failure 修复浪潮 + 体验补强。从用户反馈「6M 的 release APK 装不上、25M 的旧版能装」开始，定位到 R8 把 Glance widget reflectively-instantiated 的子类剥光导致 PackageManager 拒绝合并 manifest receivers，连带补齐 release 签名链与几项「方便用户帮我们排查 bug」的诊断能力。

### Added

- **设置 → 外观主题**: 三选一 FilterChip（跟随系统 / 亮 / 暗）。`PreferencesStore.themeMode` (DataStore) 持久化，`MemoThemeWithMode` 在 MainActivity / EditActivity 顶层 `collectAsStateWithLifecycle` 订阅，切换无需重启 Activity。
- **设置 → 日志导出**: `LogExporter.captureToFile` 把本进程最近 2000 行 logcat（threadtime 格式）+ 持久化崩溃栈写到 `cacheDir/logs/memo-log-{ts}.txt`，文件头自带 `versionName/versionCode` + 设备型号 + Android 版本 + PID + 抓取时间。`shareIntent` 通过 FileProvider (`${applicationId}.fileprovider`) 弹系统分享面板，用户可发给开发者排查（Telegram / 邮件 / 微信均可）。文件不含 PAT/AI key。
- **持久化崩溃日志**: `CrashLogger.install(ctx)` 在 `MemoApplication.onCreate` 第一行装的 `UncaughtExceptionHandler`，把崩溃栈写到 `filesDir/crash/crash-{ts}.txt` 后链给系统默认 handler。`LogExporter` 导出时自动 tail 这些文件，logcat 缓冲区被冲掉后开发者还能看到历史崩溃。最多保留 5 份，安装时清理旧文件防止崩溃循环灌爆磁盘。
- **AndroidManifest FileProvider**: 新增 `androidx.core.content.FileProvider` `<provider>`，`exported=false`、`grantUriPermissions=true`，仅暴露 `cache-path` 下的 `logs/` 子目录（`res/xml/file_paths.xml`）。
- **设置 → 崩溃指示卡**: 当 `filesDir/crash/` 有持久化崩溃文件时自动浮现的 warning 卡（`CrashIndicatorCard`），主动告知用户「检测到 N 条崩溃记录 · 最近 [时间]」，避免崩溃被忽略。两个独立按钮：「导出日志」（含历史崩溃做附录）、「清空记录」（`CrashLogger.clearAll`）。`LaunchedEffect` 重读 crash 目录，清空后卡片自动消失。
- **设置 → 同步状态卡**: `SyncStatusCard` 区分两条用户问的不同问题：「我刚改的笔记上传了吗」（push）和「其他设备的改动来了吗」（pull）。两条独立持久化时间戳 `PreferencesStore.lastPushEpochMs` + `lastPullEpochMs`（`longPreferencesKey`），分别由 `PushWorker.doWork()` 在 `roomChanged && !retry` 时写、`PullWorker.doWork()` 在 `!anyNetwork` 干净 success 分支写。卡片渲染两行：「已上传到 GitHub：刚刚」+「已检查 GitHub 更新：12 分钟前  ·  默认 30 分钟自动检查」。`SyncStatusFormatter.formatRelative(now, epochMs)` 抽为纯函数，5 个时间分支（刚刚 / N 分钟前 / N 小时前 / yyyy-MM-dd / null=未发生）。仅在 `state.isConfigured=true` 时显示——未配置 GitHub 时由 PatStatusCard 直接告知缺什么，避免 SyncStatusCard 的「尚未上传」让用户困惑。
- **设置 → 同步状态卡 → 「立即同步」按钮**: `FilledTonalButton` 一键调 `SyncScheduler.enqueuePullNow(ctx)` + `SyncScheduler.enqueuePush(ctx)`，立刻入队 PullWorker + PushWorker（KEEP 策略防重复）。snackbar 提示「已请求立即同步」，结果通过现有 SyncBanner 反馈。补全此前用户没有强制触发同步入口的缺口（之前必须等 30 分钟周期或用一次写笔记触发 push）。
- **`SyncScheduler.PULL_NOW_POLICY` 锁定**: 提取 `enqueuePullNow` 的 `ExistingWorkPolicy.KEEP` 为 `internal val`（对称于既有 `PUSH_POLICY`），新加 3 个 `SyncSchedulerPolicyTest` 用例锁住「不可改成 REPLACE / APPEND_OR_REPLACE」——前者会取消进行中的 PullWorker 致 Room 部分提交漂移，后者会让「立即同步」连续点击落在 backoff 链尾互相阻塞。两个 policy 现在对称防 Fix-WP 类回归。
- **JVM 单元测试**: `CrashLoggerTest`（7 用例：crashDir 自动创建 / 空目录 summary / 计数+时间戳 / clearAll / install 时按 mtime 裁剪到 5 份 / 不超阈值不动 / **install handler 端到端：模拟未捕获异常，验证 crash 文件落盘 + 链给 previous handler**）+ `LogExporterTest`（5 用例：路径正确 / 标准 header 齐全 / 空 crash 标 (none) / 历史崩溃文件名+内容串接 / 连续两次文件名唯一）+ `SyncStatusFormatterTest`（11 用例：5 个分支 + 时钟回拨/负值/0L 边缘）+ `PreferencesStoreSyncKeysTest`（3 用例：push/pull 用独立 DataStore key、默认值都是 0L、覆盖一个不影响另一个——锁住 key 撞车导致"上传时间被检查时间覆盖"的隐性 bug）。共 26 个新测试，全套 3219 tests 全绿，0 回归。

### Changed — bug fixes

- **release APK 装不上修复**: R8 默认认不出 Glance `glanceAppWidget = MemoWidget()` 字段初始化的反射依赖，导致 `MemoWidget` / `TodayWidget` 被剥；PackageManager 在 install 阶段查 manifest receiver 引用类时找不到，整个 APK install 被拒绝（用户看到的就是「解析失败 / 安装失败」）。`proguard-rules.pro` 补齐：`-keep class * extends GlanceAppWidget { *; }`、`-keep class * extends GlanceAppWidgetReceiver { *; }`、`-keep class * implements ActionCallback { *; }`、`-keep class dev.aria.memo.widget.** { *; }`、`-keep class dev.aria.memo.{MemoApplication,MainActivity,EditActivity}`、`-keep class * extends BroadcastReceiver`、`androidx.work.**` / `androidx.profileinstaller.**` / `androidx.startup.**`。dexdump 验证 release classes.dex 内 8 个关键类 + FileProvider 全部保留。
- **release APK 改用 debug keystore 签名（临时）**: 之前发的是 `app-release-unsigned.apk`，PackageManager 直接拒。`app/build.gradle.kts` 的 release buildType 改 `signingConfig = signingConfigs.getByName("debug")`，证书 SHA-256 `c64af4ec…` 与之前用户能装的 debug APK 一致，**无需卸载即可覆盖安装**。正式 Play Store 签名串密码 + GitHub Actions secret 流见 #274 / #288。
- **dataExtractionRules + fullBackupContent**: 新增两个 XML 文件 + manifest 同步引用，关闭 cloud backup / device transfer 全部 domain — PAT 在 EncryptedSharedPreferences 里走 AndroidKeystore master key，跨设备恢复必失败，索性禁掉。修 lint `[DataExtractionRules]` warning。

### Dep upgrades (patch only, P9-revisit 保守批次)

- Kotlin 2.0.10 → 2.0.21；KSP 2.0.10-1.0.24 → 2.0.21-1.0.28（与 Kotlin 锁版本）。
- Ktor 2.3.12 → 2.3.13；androidx.lifecycle 2.8.5 → 2.8.7。
- 全部为补丁版本无 API 变化；测试套件全绿。

---

## [v0.12.2-p8 → v0.12.17-p8] - 2026-04-27 → 2026-04-29

P8.1 punch-list closeout + P9-revisit campaign. ~16 incremental tagged
releases (v0.12.2-p8 through v0.12.17-p8, versionCode 14 through 30)
that closed the 10 originally-open P8.1 issues plus ~150 closed-but-
deferred items the previous "won't fix → P9" wave shouldn't have left
behind. Each tag has its own GitHub Release with its own per-fix
breakdown; this CHANGELOG entry rolls them up by theme.

### Added

- **First-launch onboarding sheet** (#144): 3-slide AlertDialog overlay
  on first install — explains the GitHub-backed model + routes to
  Settings.
- **Persistent offline banner** (#158): `ConnectivityObserver` wraps
  `ConnectivityManager.NetworkCallback`; `OfflineBanner` shows
  "离线中 · N 条待同步" above the SyncBanner when the network is down.
- **TagIndexer.Cache**: per-VM memo for tag extraction (#124).
- **Composite indices**: `note_files (isPinned, date)`,
  `events (startEpochMs, endEpochMs)`, `single_notes (isPinned, date,
  time)`. Schema 8 → 9 migration `MIGRATION_8_9` (#303).
- **MigrationDefaultsTest**: tripwire that reads `AppDatabase.kt` and
  asserts every historical `DEFAULT 0` survives (#301).
- **CI**: `dependency-graph` job on master pushes (Dependabot vuln
  scanner, #272).

### Changed — bug fixes

- IcsCodec accepts UTC `Z`, TZID-anchored, floating, and date-only
  DTSTART forms (#106). Previously TZID `DTSTART;TZID=…:20260427T140000`
  silently dropped the entire VEVENT.
- `reminderMinutesBefore` round-trips via `X-MEMO-REMINDER-MINUTES`
  X-property in the .ics body — survives cross-device sync (#308).
- Pin parse unified on `FrontMatterCodec.parse` — removed the
  `MemoRepository.readPinnedFromFrontMatter` parallel reader that
  tolerated quoted values while the codec rejected them (#105).
- `FrontMatterCodec.parse` strips UTF-8 BOM + leading blank lines
  before the YAML fence gate (#139).
- `PushWorker` retries with fresh credentials on PAT change via new
  `SyncScheduler.enqueuePushAfterCredentialChange` (REPLACE policy,
  #113).
- `EventEditDialog` opens an AlertDialog confirm before deleting
  — misclick is no longer a 1-second permanent delete (#182).
- Pull / Push workers track `roomChanged` and only fire
  `WidgetRefresher` when at least one mutation landed (#297).
- `TodayWidget` short-circuits on `isConfigured` before any Room read
  (#299) and includes today's single-notes alongside legacy day-files
  (#331).
- `EditViewModel.save` rolls back the optimistic user turn AND restores
  the input field on Err for retry without re-typing (#108).
- `AiChat` auto-scroll only when user is already at the bottom — no
  more force-scroll yanking the user out of history (#169).
- `ServiceLocator.init` synchronized double-checked locking (#330).

### Changed — perf

- `NoteListViewModel`: split combine pipeline so `_query` /
  `_refreshing` no longer re-trigger `parseEntries`; per-VM memo caches
  for `parseEntries` and `buildPreview` (#122).
- `TagIndexer`: per-path / per-uid cache; only modified bodies re-run
  `TAG_REGEX` (#124).
- `CalendarViewModel`: split combine into `eventBlock` (allEvents only)
  + `noteMarkers` (allNotes-derived); note writes don't re-expand RRULE
  occurrences (#130).
- `MemoRepository.recentEntriesAcrossDays` pool floor bumped to 100;
  `observeAll()` fallback essentially dead code (#309).
- `PullBudget` switched to `AtomicInteger`; `consume()` is a CAS loop;
  `tightenFromHeader()` ready for `X-RateLimit-Remaining` (#314).
- Widget `itemId` via FNV-1a 64-bit instead of 32-bit `String.hashCode`
  — removes row-recycle flicker risk (#319).
- `MarkdownRenderer` regexes hoisted to top-level vals (#311).
- `HelpScreen` uses `LazyRenderMarkdown` for ~20 KB user guide (#176).
- Three list VMs use `SharingStarted.WhileSubscribed(5_000L)` (#322).
- `BootReceiver` / `EventAlarmReceiver` use `Dispatchers.IO` (#320).
- `WidgetRefresher` per-target accumulation: `refreshMemo` /
  `refreshToday` / `refreshAll`; EventRepository writes only refresh
  Today (#300).
- Save paths use `refreshAllNow` (inline) so screen-off mid-debounce
  can't lose the updateAll (#305).
- `GlanceWidgetUpdater` hoists MemoWidget / TodayWidget to singleton
  vals (#133).

### Changed — UX / a11y

- `Settings`: inline validation for PAT, provider URL (loopback http
  allowed for local Ollama, #137), model, API key (#140).
- Search field shape switched to pill — reads as M3 SearchBar (#254).
- `#`-prefix search queries filter by tag (#199).
- `MemoEmptyState` switched to `secondaryContainer` + 56/28dp (#237).
- `OAuthSignInDialog`: 复制 → `OutlinedButton`, 打开浏览器 stays
  filled (#248).
- `MarkdownToolbar` split 10 IconButtons into three groups (#249).
- `SyncBanner` leading icon + bodyMedium (#229).
- `Calendar` marker dot 4dp → 6dp (#243).
- `TagList` row counts wrapped in pill (#244).
- `SingleNoteRow` trailing `MoreVert` for the long-press menu (#196).
- `TagListScreen` `LargeTopAppBar` only on tree view (#235).
- `Tag` expand state survives tab swaps (#167).
- Splash screen on Android 12+ uses launcher foreground icon (#253).
- `DayCell` semantics contentDescription / 48dp tap slot / alpha 0.6
  for out-of-month (#226 / #228 / #230).
- AppNav respects `Settings.Global.ANIMATOR_DURATION_SCALE` (#232).
- TodayWidget time/body use `defaultWeight + maxLines = 2` (#234).

### Changed — security

- `androidx.security:security-crypto` 1.1.0-alpha06 → 1.1.0 (#135).
- `AiClient` allows loopback http for local Ollama / vLLM (#137).
- AndroidManifest uses `dataExtractionRules` + `fullBackupContent`
  instead of bare deprecated `allowBackup`.

### Removed

- `MemoRepository.readPinnedFromFrontMatter` (#105).
- `EditViewModel`'s deprecated 3-arg legacy constructor (#321).
- Hard-coded `widthIn(max = 320.dp)` on AiChat bubbles (#227).
- Hard-coded 88dp FAB gutter constant (#241).

### Architecture

- `EditViewModel` accepts injected `currentConfig` + `loadBodyForPath`
  helpers — tests now reach the same codepath as production (#323).
- `ui/EditViewModel.kt` + `EditScreen.kt` → `ui/edit/`;
  `ui/SettingsViewModel.kt` + `SettingsScreen.kt` → `ui/settings/`
  (#326).

### Documentation

- `HANDOFF.md` now carries "数据模型设计选择" explaining why
  `note_files` has no `tombstoned` column (#306).

### Build / lint

- `versionCode` 13 → 30, `versionName` 0.12.1 → 0.12.17.
- Lint: 0 errors, 0 actionable warnings (only deferred dep-update
  notices — Kotlin / Compose BOM / AGP / Ktor / Glance all coupled and
  tracked as a single coordinated upgrade wave).
- CI gained `dependency-graph` job for Dependabot vuln scanning.

---

## [v0.12.1-p8] - 2026-04-24

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
- 快速连续写入时 400ms debounce（MutableSharedFlow + Flow.debounce）合并触发，避免桌面闪烁和 Glance session-lock 期间的"丢更新"。
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

[v0.12.1-p8]: https://github.com/qqzlqqzlqqzl/memo-widget/releases/tag/v0.12.1-p8
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
