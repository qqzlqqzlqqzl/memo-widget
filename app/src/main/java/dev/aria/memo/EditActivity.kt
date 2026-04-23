package dev.aria.memo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import dev.aria.memo.ui.EditScreen
import dev.aria.memo.ui.EditViewModel
import dev.aria.memo.ui.theme.MemoTheme

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
        EditViewModel.factoryFor(intent?.getStringExtra(EXTRA_NOTE_UID))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uid = intent?.getStringExtra(EXTRA_NOTE_UID)
        if (uid.isNullOrBlank()) {
            // Legacy / new-note flow — keep priming the ViewModel with any
            // supplied path+body so checklist toggles can still persist on the
            // day-file cache until all callers migrate to the single-note flow.
            val extraPath = intent?.getStringExtra(EXTRA_PATH)
            val extraBody = intent?.getStringExtra(EXTRA_BODY)
            viewModel.prime(extraPath, extraBody)
        }

        setContent {
            MemoTheme {
                EditScreen(
                    viewModel = viewModel,
                    // Guard against a second Success landing after we've
                    // already asked for finish() — Activity teardown is async,
                    // so the compose tree stays alive for a few frames, and we
                    // don't want a stray re-entry into finish() from a repeat
                    // save that's been swallowed by the ViewModel's dedup.
                    onSaved = { if (!isFinishing) finish() },
                    onBack = { if (!isFinishing) finish() },
                )
            }
        }
    }

    companion object {
        const val EXTRA_PATH = "dev.aria.memo.EditActivity.EXTRA_PATH"
        const val EXTRA_BODY = "dev.aria.memo.EditActivity.EXTRA_BODY"
        const val EXTRA_NOTE_UID = "note_uid"
    }
}
