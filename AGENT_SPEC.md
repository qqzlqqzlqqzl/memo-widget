# Memo Widget — Agent Implementation Spec

**Project root**: `~/projects/memo-widget/`
**Package**: `dev.aria.memo`
**Min SDK**: 26 (Android 8) · **Target SDK**: 34 · **Kotlin**: 2.0 · **AGP**: 8.5

This spec is the single source of truth for all parallel implementation agents.
Each agent touches ONLY files in its ownership column. Do NOT modify files outside your range.

## 1. Architecture

```
┌──────────────────────────────────────────────┐
│  Widget (Glance)      EditActivity    Main    │
│  - button             (Compose)       (Settings)│
│  - recent 3 entries                            │
└──────┬────────────────────┬──────────────┬────┘
       │                    │              │
       └─────────┬──────────┴──────────────┘
                 │
         ┌───────▼─────────┐
         │ MemoRepository  │  ← business logic
         │  appendToday()  │
         │  recentEntries()│
         └───────┬─────────┘
                 │
         ┌───────┴─────────┐
         ▼                 ▼
  ┌──────────────┐   ┌────────────┐
  │GitHubApi     │   │SettingsStore│
  │  getFile()   │   │  DataStore  │
  │  putFile()   │   │  encrypted  │
  └──────────────┘   └────────────┘
```

## 2. Shared Data Types (canonical)

All agents use these exact type definitions. If you need a type not here, STOP and ask the coordinator.

### 2.1 AppConfig (SettingsStore)

```kotlin
data class AppConfig(
    val pat: String,          // GitHub Personal Access Token, empty string = unconfigured
    val owner: String,        // GitHub username/org, e.g. "qqzlqqzlqqzl"
    val repo: String,         // repo name, e.g. "memos"
    val branch: String = "main",
    val pathTemplate: String = "{yyyy}-{MM}-{dd}.md"  // one file per day
) {
    val isConfigured: Boolean get() = pat.isNotBlank() && owner.isNotBlank() && repo.isNotBlank()
    fun filePathFor(date: LocalDate): String = pathTemplate
        .replace("{yyyy}", date.year.toString())
        .replace("{MM}", date.monthValue.toString().padStart(2, '0'))
        .replace("{dd}", date.dayOfMonth.toString().padStart(2, '0'))
}
```

### 2.2 MemoEntry (parsed from file)

```kotlin
data class MemoEntry(
    val date: LocalDate,       // which day file
    val time: LocalTime,       // HH:MM header within file
    val body: String           // plain markdown body after the header
)
```

### 2.3 GitHub Contents API DTOs

```kotlin
@Serializable
data class GhContents(
    val sha: String,
    val content: String,       // base64-encoded
    val encoding: String       // "base64"
)

@Serializable
data class GhPutRequest(
    val message: String,
    val content: String,       // base64 encoded new content
    val branch: String,
    val sha: String? = null    // null when creating, required when updating
)

@Serializable
data class GhPutResponse(
    val content: GhFileInfo
)

@Serializable
data class GhFileInfo(
    val sha: String,
    val path: String
)
```

### 2.4 Result wrapper

```kotlin
sealed class MemoResult<out T> {
    data class Ok<T>(val value: T) : MemoResult<T>()
    data class Err(val code: ErrorCode, val message: String) : MemoResult<Nothing>()
}

enum class ErrorCode {
    NOT_CONFIGURED,   // no PAT/repo
    UNAUTHORIZED,     // 401 from GitHub
    NOT_FOUND,        // 404: file doesn't exist yet (usually benign, treated as empty)
    CONFLICT,         // 409: sha mismatch, retry once
    NETWORK,          // IO
    UNKNOWN
}
```

## 3. Memo File Format (canonical)

Each day's file looks like:
```markdown
# 2026-04-20

## 14:30
今天学了 Glance 的 widget API，比 RemoteViews 舒服多了。

## 15:12
- 买菜
- 跑步 30min

## 18:05
晚饭：凉面
```

**Append algorithm** (MemoRepository.appendToday):
1. If file exists: GET it, decode base64, append `\n## HH:MM\n{body}\n`
2. If 404: create with header `# {YYYY-MM-DD}\n\n## HH:MM\n{body}\n`
3. PUT with sha (null if creating)

## 4. Interfaces (MUST match exactly)

### 4.1 SettingsStore (Agent B)

```kotlin
class SettingsStore(private val context: Context) {
    val config: Flow<AppConfig>
    suspend fun update(transform: (AppConfig) -> AppConfig)
    suspend fun current(): AppConfig
}
```

Use `androidx.datastore:datastore-preferences:1.1.1`. No encryption library needed for V1 (Android DataStore is sandboxed to app).

### 4.2 GitHubApi (Agent B)

```kotlin
class GitHubApi(private val httpClient: HttpClient) {
    suspend fun getFile(config: AppConfig, path: String): MemoResult<GhContents>
    suspend fun putFile(config: AppConfig, path: String, request: GhPutRequest): MemoResult<GhPutResponse>
}
```

Use `io.ktor:ktor-client-*:2.3.12` with `cio` engine + `content-negotiation` + `kotlinx-json`.
Base URL: `https://api.github.com/repos/{owner}/{repo}/contents/{path}`
Auth header: `Authorization: Bearer {pat}`
Accept header: `application/vnd.github+json`

### 4.3 MemoRepository (Agent B)

```kotlin
class MemoRepository(
    private val settings: SettingsStore,
    private val api: GitHubApi
) {
    suspend fun appendToday(body: String, now: LocalDateTime = LocalDateTime.now()): MemoResult<Unit>
    suspend fun recentEntries(limit: Int = 3): MemoResult<List<MemoEntry>>
}
```

`recentEntries` reads today's file only in V1 (simpler). If today's file has < limit, returns what's there.

### 4.4 UI contracts (Agent C)

```kotlin
// MainActivity hosts SettingsScreen
// EditActivity hosts EditScreen; started via Intent from widget
// Both use ViewModel with MemoRepository

class SettingsViewModel(repository, settings): ViewModel { ... }
class EditViewModel(repository): ViewModel {
    fun save(body: String, onDone: (MemoResult<Unit>) -> Unit)
}
```

### 4.5 Widget contract (Agent D)

```kotlin
// MemoWidget is a GlanceAppWidget
// MemoWidgetReceiver is GlanceAppWidgetReceiver
// Widget pulls recentEntries() from repository in Glance content
// "Add memo" button → Intent to EditActivity
```

## 5. File Ownership

### Agent A — Build & Manifest
```
build.gradle.kts                           (root)
settings.gradle.kts
gradle.properties
gradle/libs.versions.toml
app/build.gradle.kts
app/proguard-rules.pro
app/src/main/AndroidManifest.xml
app/src/main/res/values/strings.xml
app/src/main/res/values/themes.xml
app/src/main/res/values/colors.xml
app/src/main/res/xml/memo_widget_info.xml
app/src/main/res/drawable/ic_launcher_*.xml (simple vector)
```

### Agent B — Data Layer
```
app/src/main/java/dev/aria/memo/data/Models.kt       (AppConfig, MemoEntry, enums)
app/src/main/java/dev/aria/memo/data/GitHubDto.kt
app/src/main/java/dev/aria/memo/data/SettingsStore.kt
app/src/main/java/dev/aria/memo/data/GitHubApi.kt
app/src/main/java/dev/aria/memo/data/MemoRepository.kt
app/src/main/java/dev/aria/memo/data/ServiceLocator.kt   (simple DI)
```

### Agent C — UI Layer
```
app/src/main/java/dev/aria/memo/MainActivity.kt
app/src/main/java/dev/aria/memo/EditActivity.kt
app/src/main/java/dev/aria/memo/ui/SettingsScreen.kt
app/src/main/java/dev/aria/memo/ui/EditScreen.kt
app/src/main/java/dev/aria/memo/ui/SettingsViewModel.kt
app/src/main/java/dev/aria/memo/ui/EditViewModel.kt
app/src/main/java/dev/aria/memo/ui/theme/Color.kt
app/src/main/java/dev/aria/memo/ui/theme/Theme.kt
app/src/main/java/dev/aria/memo/ui/theme/Type.kt
```

### Agent D — Widget
```
app/src/main/java/dev/aria/memo/widget/MemoWidget.kt
app/src/main/java/dev/aria/memo/widget/MemoWidgetReceiver.kt
app/src/main/java/dev/aria/memo/widget/MemoWidgetContent.kt
```

## 6. Gradle Dependency Requirements (for Agent A)

```toml
[versions]
agp = "8.5.2"
kotlin = "2.0.10"
compose-bom = "2024.09.00"
glance = "1.1.0"
ktor = "2.3.12"
datastore = "1.1.1"
activity-compose = "1.9.2"
lifecycle = "2.8.5"

[libraries]
androidx-activity-compose = { ... }
androidx-datastore-preferences = { ... }
androidx-glance-appwidget = { ... }
androidx-glance-material3 = { ... }
androidx-lifecycle-viewmodel-compose = { ... }
compose-bom = { ... }
compose-material3 = { group = "androidx.compose.material3", name = "material3" }
compose-ui = { ... }
compose-ui-tooling-preview = { ... }
compose-ui-tooling = { ... }
ktor-client-cio = { ... }
ktor-client-content-negotiation = { ... }
ktor-client-core = { ... }
ktor-serialization-kotlinx-json = { ... }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version = "1.7.2" }
kotlinx-datetime = { module = "org.jetbrains.kotlinx:kotlinx-datetime", version = "0.6.1" }
```

## 7. Non-negotiable rules for agents

1. **Don't touch files outside your ownership row** — zero exceptions.
2. **Match the interface signatures exactly** — other agents depend on them.
3. **No TODO stubs** — if you need something, implement it fully or ask the coordinator.
4. **Error handling**: use MemoResult sealed class, don't throw from repo/api layer.
5. **No logging PAT** — ever. Not in `Log.d`, not in toast, not in exception messages.
6. **Add `@Preview` to every Composable** where Compose Preview makes sense.
7. **Use kotlinx-datetime** not java.time for anything cross-platform; java.time.LocalDate is OK on API 26+.
8. **Material 3 theme**, support dark mode.

## 8. Deliverable per agent

Each agent on completion must:
1. List files created/modified
2. Show key code snippets for integration points
3. Report any deviation from the spec (must justify why)
4. Confirm that files outside ownership were NOT touched
