package dev.aria.memo.ui.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Parsed representation of a single markdown checkbox line.
 *
 * `indent` is the number of leading space columns (tabs counted as one column
 * each — matches how humans write nested todos). `checked` reflects the state
 * inside the brackets (`[ ]` vs `[x]`/`[X]`). `text` is the body after the
 * closing bracket with its leading space consumed.
 */
data class ChecklistLine(
    val indent: Int,
    val checked: Boolean,
    val text: String,
)

/**
 * Parse one line of text. Returns a [ChecklistLine] if the line matches the
 * Markdown checkbox syntax (`- [ ] foo` / `- [x] foo`, optionally indented),
 * otherwise returns null.
 *
 * Accepts both lowercase `x` and uppercase `X` for checked state so tests like
 * `  - [X] baz` round-trip correctly. Leading indentation may be spaces (each
 * counted as one column); tabs are counted as one column too — good enough for
 * a memo app where nobody is writing 4-space-tabs mixed Markdown.
 */
fun parseChecklistLine(line: String): ChecklistLine? {
    val match = CHECKLIST_REGEX.matchEntire(line) ?: return null
    val indentStr = match.groupValues[1]
    val mark = match.groupValues[2]
    val text = match.groupValues[3]
    val checked = mark == "x" || mark == "X"
    return ChecklistLine(indent = indentStr.length, checked = checked, text = text)
}

private val CHECKLIST_REGEX = Regex("""^([ \t]*)- \[([ xX])] (.*)$""")
private val TIME_HEADER_REGEX = Regex("""^## \d{2}:\d{2}\s*$""")

/**
 * Read-mode renderer for a markdown note body.
 *
 * Splits the body on `\n`, rendering each line as:
 *  - Checkbox + text when the line matches `- [ ]` / `- [x]` syntax
 *  - Bold text when the line matches a `## HH:MM` time header
 *  - Plain text otherwise
 *
 * Tapping a checkbox invokes [onToggle] with the 0-based line index, the raw
 * line text the user saw (for concurrency guard against a mid-tap pull), and
 * the new checked state. The caller owns the body state and re-feeds the
 * updated body on the next recomposition.
 */
@Composable
fun ReadModeNote(
    body: String,
    onToggle: (lineIndex: Int, rawLine: String, newChecked: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lines = body.split("\n")
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(lines.size, key = { idx -> "line-$idx" }) { idx ->
            val line = lines[idx]
            val parsed = parseChecklistLine(line)
            when {
                parsed != null -> ChecklistRow(
                    line = parsed,
                    onToggle = { newChecked -> onToggle(idx, line, newChecked) },
                )
                TIME_HEADER_REGEX.matches(line) -> Text(
                    text = line.removePrefix("## ").trim(),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                line.isBlank() -> Spacer(modifier = Modifier.fillMaxWidth())
                else -> Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun ChecklistRow(
    line: ChecklistLine,
    onToggle: (Boolean) -> Unit,
) {
    // Bug-2 #162 fix: 用户连续快速点 checkbox 会触发多个 onToggle → 多次 toggleTodoLine
    // → race / dirty cycle / Push 多次。lastToggleAt 维护 200ms cooldown,在 cool 内
    // 忽略后续 toggle (lossy 但 visual checkbox state 由 line.checked 反映,UX 不受影响)。
    val lastToggleAt = remember { mutableLongStateOf(0L) }
    val debouncedToggle: (Boolean) -> Unit = { newChecked ->
        val now = System.currentTimeMillis()
        if (now - lastToggleAt.longValue >= 200L) {
            lastToggleAt.longValue = now
            onToggle(newChecked)
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (line.indent > 0) {
            Spacer(modifier = Modifier.width((line.indent * 8).dp))
        }
        Checkbox(
            checked = line.checked,
            onCheckedChange = debouncedToggle,
        )
        Text(
            text = line.text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (line.checked) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}
