package dev.aria.memo

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import dev.aria.memo.ui.edit.EditScreen
import dev.aria.memo.ui.edit.EditViewModel
import dev.aria.memo.data.PreferencesStore
import dev.aria.memo.ui.theme.MemoThemeWithMode
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue

/**
 * Thin Compose host for EditScreen. Launched by the widget's "Add memo"
 * button, by MainActivity's shortcut, and by tapping a single-note row in the
 * note list.
 *
 * Intent extras (all optional):
 *  - [EXTRA_NOTE_UID] — UID of a single-note to edit (Obsidian-style file under
 *    `notes/`). When present the ViewModel loads that note and save writes
 *    through [dev.aria.memo.data.SingleNoteRepository.update].
 *  - [EXTRA_PATH] — repo-relative path of the legacy day-file to edit. Used by
 *    checklist-toggle flows that still persist via [MemoRepository]. Blank /
 *    missing → falls back to today's path derived from AppConfig.pathTemplate.
 *  - [EXTRA_BODY] — initial body text for the legacy flow. When null the VM
 *    loads whatever Room has cached for the resolved path (empty if no cache).
 *
 * When [EXTRA_NOTE_UID] is provided it takes precedence over the other two
 * extras — the single-note path is the new primary flow.
 *
 * After a successful save, EditScreen invokes [finish] via the onSaved callback.
 */
class EditActivity : ComponentActivity() {

    private val viewModel: EditViewModel by viewModels {
        // Use the SavedStateRegistryOwner-aware factory so the OS-backed
        // SavedStateHandle persists draft body + noteUid through process
        // death (B27 / issue: process death草稿丢失). The 1-arg overload
        // only survives configuration changes, not process death.
        EditViewModel.factoryFor(this, intent?.getStringExtra(EXTRA_NOTE_UID))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Sec-1 / M2 (FLAG_SECURE coverage): note bodies can contain
        // sensitive content (names, locations, credentials pasted by
        // mistake). FLAG_SECURE blocks screenshots and hides this window
        // from the recent-apps thumbnail, closing the multi-task-card leak
        // that SettingsScreen's PAT-only toggle doesn't cover.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )

        val uid = intent?.getStringExtra(EXTRA_NOTE_UID)
        if (uid.isNullOrBlank()) {
            // Legacy / new-note flow — keep priming the ViewModel with any
            // supplied path+body so checklist toggles can still persist on the
            // day-file cache until all callers migrate to the single-note flow.
            val rawPath = intent?.getStringExtra(EXTRA_PATH)
            val safePath = rawPath?.let { path ->
                if (isValidNotePath(path)) {
                    path
                } else {
                    Log.w(TAG, "EXTRA_PATH rejected by allowlist: \"$path\" — falling back to null")
                    null
                }
            }
            val extraBody = intent?.getStringExtra(EXTRA_BODY)
            viewModel.prime(safePath, extraBody)
        }

        // Fix-6 (Bug-1 C4): only expose the delete overflow when editing an
        // existing single-note. New-note mode (no uid) has nothing to delete
        // yet — back-press is the escape hatch for that flow.
        val editingUid = uid
        val prefs = PreferencesStore(applicationContext)
        setContent {
            val themeMode by prefs.themeMode.collectAsStateWithLifecycle(initialValue = "auto")
            MemoThemeWithMode(themeMode = themeMode) {
                EditScreen(
                    viewModel = viewModel,
                    // Guard against a second Success landing after we've
                    // already asked for finish() — Activity teardown is async,
                    // so the compose tree stays alive for a few frames, and we
                    // don't want a stray re-entry into finish() from a repeat
                    // save that's been swallowed by the ViewModel's dedup.
                    onSaved = { if (!isFinishing) finish() },
                    onBack = { if (!isFinishing) finish() },
                    onDelete = if (!editingUid.isNullOrBlank()) {
                        {
                            viewModel.delete(onDone = {
                                if (!isFinishing) finish()
                            })
                        }
                    } else null,
                )
            }
        }
    }

    /**
     * Review-T fix: launchMode="singleTop" 让系统复用同一 Activity 实例（避免栈里堆笔记编辑器），
     * 但默认 onNewIntent 不更新 ViewModel —— 用户在 NoteA 编辑时点 widget 打开 NoteB，
     * Activity 复用，ViewModel 仍持 noteUid=A，编辑 NoteB 内容点保存会写到 NoteA 文件。
     * 这是跨笔记数据覆盖致命 bug。
     *
     * 修法：检测到新 intent 的"笔记身份"和当前持有的不同时，finish() + 重启 Activity，
     * 让 ViewModel 用新 uid 重建。**身份**包含三个维度（Review-Fix4 复核补漏）：
     *  1. EXTRA_NOTE_UID 不同 → single-note 模式切笔记
     *  2. EXTRA_NOTE_UID 都为 null 但 EXTRA_PATH 不同 → legacy day-file 切 path
     *  3. uid null → 非 null（或反向）→ 模式切换
     *
     * **已知妥协（草稿丢失，待 P8.2 补完整 discard dialog）**：用户在 NoteA 有未保存修改
     * 时切到 NoteB 会丢稿——viewModelScope 在 finish() 时被 cancel，_body MutableStateFlow
     * 是纯内存。BackHandler 的"是否保存草稿"对话框只覆盖系统返回键，不覆盖此路径。
     * 我们接受这个 lesser evil:
     *   - 跨笔记覆盖 = silent corruption（用户永远不知道写错文件）
     *   - 草稿丢失 = 用户能感知（"我刚刚没保存就切了 widget"）
     * P8.2 issue 留 discard dialog 流程接管。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val newUid = intent.getStringExtra(EXTRA_NOTE_UID)
        val oldUid = getIntent()?.getStringExtra(EXTRA_NOTE_UID)
        // Validate the incoming path before using it for identity comparison
        // so a malformed path cannot trigger a spurious Activity restart.
        val newPath = intent.getStringExtra(EXTRA_PATH)?.let { path ->
            if (isValidNotePath(path)) path
            else { Log.w(TAG, "onNewIntent: EXTRA_PATH rejected by allowlist: \"$path\""); null }
        }
        val oldPath = getIntent()?.getStringExtra(EXTRA_PATH)
        // Identity change = uid changed, OR both uids null but path changed.
        val identityChanged = (newUid != oldUid) ||
            (newUid == null && oldUid == null && newPath != oldPath)
        if (identityChanged) {
            setIntent(intent)
            finish()
            startActivity(intent)
        }
    }

    companion object {
        private const val TAG = "EditActivity"

        const val EXTRA_PATH = "dev.aria.memo.EditActivity.EXTRA_PATH"
        const val EXTRA_BODY = "dev.aria.memo.EditActivity.EXTRA_BODY"
        const val EXTRA_NOTE_UID = "note_uid"

        /** Regex for a bare day-entry filename, e.g. "2024-01-31.md". */
        private val DAY_ENTRY_REGEX = Regex("""\d{4}-\d{2}-\d{2}\.md""")

        /**
         * Allowlist guard for repo-relative note paths received via [EXTRA_PATH].
         *
         * Accepted forms:
         *  - starts with "notes/" (Obsidian single-note layout)
         *  - matches [DAY_ENTRY_REGEX] (bare legacy day-file, e.g. "2024-01-31.md")
         *
         * Rejected regardless of the above:
         *  - contains ".." (directory traversal)
         *  - contains "\" (Windows-style separator)
         *  - starts with "/" (absolute path)
         *  - contains any ASCII control character (0x00–0x1F)
         */
        private fun isValidNotePath(path: String): Boolean {
            // Reject structural attacks first — these are never valid in our schema.
            if (path.contains("..")) return false
            if (path.contains('\\')) return false
            if (path.startsWith("/")) return false
            if (path.any { it.code in 0..0x1F }) return false

            // Accept only the two known safe shapes.
            return path.startsWith("notes/") || DAY_ENTRY_REGEX.matches(path)
        }
    }
}
