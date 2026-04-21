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
 * button and by MainActivity's shortcut. No extras required — the repository
 * always writes to today's file. After a successful save, EditScreen invokes
 * [finish] via the onSaved callback.
 */
class EditActivity : ComponentActivity() {

    private val viewModel: EditViewModel by viewModels { EditViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
}
