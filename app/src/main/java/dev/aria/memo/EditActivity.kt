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
 * button and by MainActivity's shortcut.
 *
 * Intent extras (both optional):
 *  - [EXTRA_PATH] — repo-relative path of the day-file to edit. When blank or
 *    missing the ViewModel falls back to today's path derived from the current
 *    AppConfig.pathTemplate.
 *  - [EXTRA_BODY] — initial body text. When null the ViewModel loads whatever
 *    Room currently has cached for the resolved path (empty if no cache).
 *
 * Wiring the path is what makes the checklist-toggle path actually persist:
 * without a primed path the ViewModel used to silently no-op every tap.
 *
 * After a successful save, EditScreen invokes [finish] via the onSaved callback.
 */
class EditActivity : ComponentActivity() {

    private val viewModel: EditViewModel by viewModels { EditViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val extraPath = intent?.getStringExtra(EXTRA_PATH)
        val extraBody = intent?.getStringExtra(EXTRA_BODY)
        viewModel.prime(extraPath, extraBody)

        setContent {
            MemoTheme {
                EditScreen(
                    viewModel = viewModel,
                    onSaved = { finish() },
                    onBack = { finish() },
                )
            }
        }
    }

    companion object {
        const val EXTRA_PATH = "dev.aria.memo.EditActivity.EXTRA_PATH"
        const val EXTRA_BODY = "dev.aria.memo.EditActivity.EXTRA_BODY"
    }
}
