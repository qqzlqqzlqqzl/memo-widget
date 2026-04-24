# P8.1 路线图

> 基于 12 份 review 报告与 Fix-1 ~ Fix-8 阶段遗留的 deferred 项汇总而成的迭代计划。P8 已完成关键缺陷修复与基础功能稳定化，P8.1 聚焦**可访问性、数据层深度重构、Widget 体验升级与架构/依赖现代化**四个方向，共 4 个 epic、21 条任务。

---

## Epic A · 可访问性与国际化（a11y + i18n）

### 目标
消除硬编码中文字符串，建立多语言资源体系，满足 Material Design 可访问性基线（对比度 AA、触摸目标 48dp），为海外发行（首批 en-US / ja-JP）扫清技术阻碍。

### 关键任务
1. **字符串抽取**：扫描 `app/` 与 `widget/` 模块下 Compose / Glance / Toast / contentDescription 中超过 50 处硬编码中文，统一迁入 `res/values/strings.xml`，命名规约 `screen_section_purpose`（例 `memo_list_empty_hint`）。
2. **多语言 locale 建立**：新增 `values-en/strings.xml`、`values-ja/strings.xml`，人工翻译 + 母语者复核；CI 加 `missing-translation` lint 严格模式，杜绝 fallback 漏洞。
3. **日期与数字本地化**：全部 `DateTimeFormatter.ofPattern("yyyy-MM-dd")` 替换为 `ofLocalizedDate(FormatStyle.MEDIUM).withLocale(...)`，时间相对格式用 `DateUtils.getRelativeTimeSpanString`；数字走 `NumberFormat.getInstance(locale)`。
4. **对比度修复**：review 报告 R-03 / R-07 指出的浅灰占位文字（#B0B0B0 on #FFFFFF，比例 2.8:1）全部提到 ≥ 4.5:1，同步更新 `Material3 ColorScheme` 的 `onSurfaceVariant` token。
5. **触摸目标扩大**：Widget 列表项右侧删除按钮、工具栏 IconButton 触摸区 < 48dp 的一律补 `Modifier.minimumInteractiveComponentSize()` 或 `padding`；TalkBack 语义补 `stateDescription` 与 `Role.Button`。
6. **RTL 预研**：虽然 P8.1 不做阿拉伯语 / 希伯来语发行，但在 `AndroidManifest` 开启 `supportsRtl=true`，并修正所有 `start/end`（原先用 `left/right` 的地方），为 P9 做铺垫。
7. **截图回归**：用 Compose UI Test + Paparazzi 为三语各抓 10 张关键页面快照，PR 门禁接入。

### 依赖与风险
- 依赖 Fix-3 已完成的 `LocalDate` 统一（不再有 `SimpleDateFormat` 残留），否则本地化会碎片化。
- 风险：日文字符串普遍比中文长 1.5×，Widget 固定高度易被截断，需提前在 `2x2` / `4x4` 上做文本测量 fallback（`TextOverflow.Ellipsis` + `maxLines`）。
- 风险：`values-ja` 与 Glance 资源合并存在已知 AGP 8.5 bug（IssueTracker #301234567），需在 epic 内先验证。

### 工作量
**L**（字符串量大 + 三语复核 + 截图基线建立，预计 6–8 人日）

---

## Epic B · 数据层深度修复

### 目标
把 Fix-1（Bug-1 C1 临时 patch）、Fix-5（EncryptedSharedPreferences 缓解方案）、Fix-7（Room migration 仅单测）留下的三块深层债务一次性偿还，确保多进程数据一致性与加密兼容性。

### 关键任务
1. **PullWorker upsert 深层重构**：把 Fix-1 注入的 `synchronized(lockObj)` 临时锁替换为 `PathLocker`（按 memoId 哈希分桶的 `ConcurrentHashMap<String, ReentrantLock>`），支持跨 Worker / ContentProvider 写入串行化；覆盖先前 review 中 R-01、R-04 指出的「Widget 拉取 + 主进程编辑并发 upsert 丢失」场景。
2. **PathLocker 单元测试 + 压测**：Turbine + Espresso Device Testing 并发 200 × 100 次写入，断言 Room 最终条数与期望一致；加 benchmark 模块跟踪 P99 锁等待时间。
3. **security-crypto 正式版迁移**：依赖从 `androidx.security:security-crypto:1.1.0-alpha06` 升至 stable `1.1.0`；写 `EncryptedSharedPreferencesMigrator`，首次启动时读出旧 MasterKey (v1) 数据 → 用新 API 重写 → 校验成功后清旧文件；失败 fallback 到明文 + 上报 Crashlytics（禁止静默丢数据）。
4. **Room MigrationTestHelper instrumented 补齐**：目前 Fix-7 只对 `2 → 3` schema 做了 JVM 单测，补 `1 → 2`、`3 → 4` 以及 `1 → 4` 跳跃升级的真机测试，放 `androidTest/` 下，CI 跑在 API 26 / 30 / 34 三档。
5. **迁移可观测性**：迁移入口打点（耗时、失败原因、旧数据量），Crashlytics 自定义 key `db_migration_from_to`，便于灰度回滚决策。
6. **Repo Flow 背压治理**：review R-09 提到批量删除触发 `StateFlow` 风暴，改用 `distinctUntilChanged` + `conflate`，并在 DAO 层加 `@Transaction` 包装批处理。

### 依赖与风险
- 任务 3 与 Epic D 的 Compose BOM / Kotlin 升级需错峰执行，避免同时触发大范围编译错误。
- 风险：EncryptedSharedPreferences 1.1.0 在低端机（Android 6）首启 crypto op 耗时 ~300 ms，需提前放到 `Dispatchers.IO` 并加启动 loading。
- 风险：PathLocker 重构触碰 Bug-1 修复路径，必须先冻结 Bug-1 的回归测试用例再开工。

### 工作量
**L**（数据层重构 + 加密迁移 + 真机测试矩阵，预计 7–9 人日）

---

## Epic C · Widget 体验升级

### 目标
等待 Glance 1.2 稳定发布后，利用其新 API 解决 Fix-2 / Fix-6 遗留的「全量重绘浪费」「单一布局不分尺寸」「无加载态」三大痛点，把 Widget 体验拉到 Material You Widget Guidelines 2026 基线。

### 关键任务
1. **Glance 1.2 升级与 updateIf 接入**：`androidx.glance:glance-appwidget` 从 1.1.0 → 1.2.0-stable（以发布日为准），在 `GlanceAppWidget.provideGlance` 内使用 `updateIf { state.hash != lastHash }`，取代 Fix-2 手写的 `WorkManager` diff 比对。
2. **providePreview 快照**：为每种 Widget 尺寸提供 `providePreview(context)` 静态预览图，改善 Pixel Launcher 的 Widget picker 体验；同时在系统 Widget Picker 中显示真实 mock 数据而非占位。
3. **SizeMode.Responsive 分 2×2 / 4×4**：
   - `2×2`：仅显示 1 条最新 memo + 新建按钮；
   - `4×4`：显示 5 条列表 + 分组分隔；
   - 通过 `SizeMode.Responsive(setOf(smallSize, largeSize))` 声明，避免 Fix-6 手写的 `LocalSize` 分支嵌套。
4. **Widget 内 inline loading**：Glance 1.2 起支持 `CircularProgressIndicator`，替换目前「刷新时清空再填」的白屏体验；刷新期间显示骨架屏 + 保留旧数据。
5. **点击区热区优化**：列表项整行可点击跳转编辑，而非仅文字；用 `actionStartActivity` + deep link，保持与主 app 导航图一致。
6. **深色模式适配**：`GlanceTheme.colors` 绑定系统 dynamic color，验证 Android 12+ 壁纸取色路径，截图回归覆盖 light / dark / high-contrast 三档。
7. **性能埋点**：用 `Trace.beginSection("widget_render")` 度量重绘耗时，Firebase Performance 上报，P99 目标 < 150 ms。

### 依赖与风险
- **硬依赖 Glance 1.2 稳定版发布**。若至 P8.1 启动时仍为 beta，epic 降级为「预研 + 准备分支」，不合并 main。
- 风险：`updateIf` API 早期 beta 在 ColorOS / MIUI 上的 Worker 唤醒策略有差异，需要 OEM 白名单兼容层。
- 风险：Widget 内 loading indicator 会增加一次 `RemoteViews` 刷新，需和电量团队对齐能耗预算。

### 工作量
**M**（依赖外部库时间表，核心工作量约 4–5 人日，但留 buffer）

---

## Epic D · 架构清理与依赖升级

### 目标
偿还 P6.2 后积累的架构债务（Hilt 模块边界模糊、Repo 直接暴露 DAO），完成一年期依赖大版本升级，保持代码库在 2026 年下半年仍具备可演进性。

### 关键任务
1. **Repository interface 门面（#64）**：为 `MemoRepository` / `SyncRepository` 抽出 `interface` + `Impl`，DI 绑 `@Binds`；UseCase 只依赖 interface，便于 Fake 替换与未来模块拆分（`:data-api` / `:data-impl`）。
2. **Compose BOM 大跨度升级**：`2024.09.00` → `2026.03.00`，附带 Material3 1.4 / Foundation 1.8 的 API 变更（`LazyListState.scrollBy` 签名、`TextField` 新 decorator 等），逐 PR 迁移，避免一把梭。
3. **Kotlin / AGP / Gradle 三件套升级**：Kotlin 2.0.21 → 2.1.20（K2 稳定），AGP 8.5.x → 8.9.x，Gradle 8.9 → 8.12；同步迁移 `kotlin.compose` plugin 取代旧的 `composeOptions` 配置块。
4. **Ktor 2.3.12 → 3.x**：HTTP client 大版本升级，`HttpClientEngine` DSL 有 break change，需改写 `AuthInterceptor` 与 `LoggingPlugin`；同时评估切到 OkHttp engine 的尾延迟收益。
5. **删 @Deprecated P6.2 遗产**：
   - `LegacyMemoMapper`（P6.2 为兼容旧 JSON 引入，Fix-4 后已无调用）；
   - `OldWidgetConfigStore`（SharedPreferences 遗留，DataStore 接管后沉睡 2 个版本）；
   - `MemoListViewModel.loadMemosLegacy()`。
6. **KSP 迁移**：Room / Hilt 从 kapt → ksp，编译时间预计 -35%；验证 `RoomPaging` 与 Hilt 测试 runner 兼容性。
7. **模块边界清理**：`:core-ui` 与 `:feature-*` 间循环依赖（`ViewModel` 反向引用 `core-ui`）打破，引入 `:core-navigation` 单独放 NavGraph 声明。
8. **基线性能快照**：升级前后各跑一次 Macrobenchmark，startup / frame timing 纳入 release-notes，作为下次回归基线。

### 依赖与风险
- Kotlin 2.1 与 Compose Compiler 对齐关系严格，必须同步升级，不能拆两个 PR。
- Ktor 3 对 `CIO` engine 的 TLS 实现有调整，若后端证书为自签，需在升级前完成 truststore 迁移。
- 风险：AGP 8.9 放弃对 Java 11 build host 的支持，CI 基础镜像需先升到 JDK 21。
- 风险：删除 Deprecated 代码前需 grep 下游反射调用（Widget host、测试 stub），避免运行时 NSME。

### 工作量
**L**（跨库升级 + 架构抽象 + 性能回归，预计 8–10 人日，可与 Epic B 错峰以共享 CI 窗口）

---

## 汇总

| Epic | 任务数 | 工作量 | 关键门禁 |
|------|-------|--------|---------|
| A · a11y + i18n | 7 | L | 三语截图 CI / 对比度检查 |
| B · 数据层深度修复 | 6 | L | 并发压测 / 真机迁移矩阵 |
| C · Widget 体验升级 | 7 | M | Glance 1.2 稳定 / 功耗预算 |
| D · 架构清理 + 依赖升级 | 8 | L | Macrobenchmark 基线 |
| **合计** | **28** | — | — |

> 注：Epic C 强依赖外部 Glance 1.2 稳定时间表；若延期，按 Epic A → B → D → C 顺序推进，并在 Epic D 完成后重新评估 C 的启动时机。所有 Epic 均应拆为 ≤ 400 行 diff 的 PR，单 PR 覆盖单一任务，遵守 P8 阶段确立的「small-batch + feature-flag」节奏。
