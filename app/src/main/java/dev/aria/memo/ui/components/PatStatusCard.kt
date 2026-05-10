package dev.aria.memo.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.aria.memo.ui.settings.PatStatus
import dev.aria.memo.ui.settings.SettingsUiState
import dev.aria.memo.ui.theme.MemoSpacing
import dev.aria.memo.ui.theme.MemoTheme
import dev.aria.memo.ui.theme.MemoThemeColors

/**
 * Status card surfacing both the *configured?* and *actually working?* views
 * of the GitHub PAT.
 *
 * Fix-X1: the original StatusCard only checked "PAT/owner/repo non-blank" so a
 * revoked token still rendered as "已就绪" while the SyncBanner shouted
 * "GitHub 拒绝访问". Now we look at [SettingsUiState.patStatus] and pick a
 * matching color + verb. The "重新验证" / "用 GitHub 重新登录" buttons make
 * the recovery path obvious instead of leaving the user to guess.
 */
@Composable
fun PatStatusCard(
    state: SettingsUiState,
    onTestConnection: () -> Unit,
    onReauth: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visual = state.toVisual()
    MemoCard(modifier = modifier, accentColor = visual.accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            visual.LeadingGlyph()
            Text(
                text = visual.headline,
                style = MaterialTheme.typography.titleMedium,
                color = visual.accent,
                modifier = Modifier
                    .padding(start = MemoSpacing.sm)
                    .weight(1f),
            )
        }
        Text(
            text = visual.detail,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = MemoSpacing.xs),
        )
        // 之前用 "<owner>" / "<repo>" 当 placeholder, 普通用户看到 < > 会以为
        // App 出 bug 了。改成"还没填"中文 placeholder; 三个字段都填齐时
        // 仍是清爽的 "owner/repo · branch" 形式让用户能 verify 配置对不对。
        val owner = state.owner.ifBlank { "（还没填用户名）" }
        val repo = state.repo.ifBlank { "（还没填仓库名）" }
        val branch = state.branch.ifBlank { "main" }
        Text(
            text = "目标仓库：$owner/$repo · $branch",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = MemoSpacing.xs),
        )
        if (visual.actions.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MemoSpacing.sm),
                horizontalArrangement = Arrangement.spacedBy(MemoSpacing.sm),
            ) {
                visual.actions.forEach { action ->
                    OutlinedButton(
                        onClick = when (action.kind) {
                            PatActionKind.Verify -> onTestConnection
                            PatActionKind.Reauth -> onReauth
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(action.label)
                    }
                }
            }
        }
    }
}

private enum class PatActionKind { Verify, Reauth }

private data class PatAction(val kind: PatActionKind, val label: String)

private data class PatStatusVisual(
    val headline: String,
    val detail: String,
    val accent: Color,
    val glyph: ImageVector?,
    val showSpinner: Boolean,
    val actions: List<PatAction>,
) {
    @Composable
    fun LeadingGlyph() {
        when {
            showSpinner -> CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.height(18.dp),
                color = accent,
            )
            glyph != null -> Icon(
                imageVector = glyph,
                contentDescription = null,
                tint = accent,
            )
        }
    }
}

@Composable
private fun SettingsUiState.toVisual(): PatStatusVisual {
    // Distinct theme colors so the four states are unmistakable at a glance.
    // Match the palette already used elsewhere — primary for healthy,
    // error for revoked, warning for inconclusive, onSurfaceVariant for "not
    // yet checked" so it doesn't shout while the page first loads.
    val ok = MaterialTheme.colorScheme.primary
    val bad = MaterialTheme.colorScheme.error
    val warn = MemoThemeColors.warning
    val mute = MaterialTheme.colorScheme.onSurfaceVariant

    if (!isConfigured) {
        return PatStatusVisual(
            headline = "还缺：${missingFields.joinToString("、")}",
            // PAT/Owner/Repo 术语去掉, 跟新的中文标签对齐
            detail = "把上面几个字段填齐就会自动验证一次。",
            accent = bad,
            glyph = Icons.Filled.ErrorOutline,
            showSpinner = false,
            actions = emptyList(),
        )
    }
    return when (val s = patStatus) {
        is PatStatus.Unknown -> PatStatusVisual(
            // Fixes #247 (UI-A #17): the ✓ / 🔄 unicode glyphs were
            // dropping to ⬜ on devices whose system font (notably some
            // OPPO/Vivo skins) lacked U+2713 / U+1F504 in their fallback
            // chain. The status now leans on the [glyph] Icon slot
            // alone — Material Icons ship inside the app so they
            // render identically across vendors.
            headline = "配置已就绪",
            detail = "字段都填了。点「重新验证」可以确认登录信息是否仍然有效。",
            accent = mute,
            glyph = Icons.AutoMirrored.Filled.HelpOutline,
            showSpinner = false,
            actions = listOf(PatAction(PatActionKind.Verify, "重新验证")),
        )
        is PatStatus.Verifying -> PatStatusVisual(
            headline = "正在验证…",
            // "向 GitHub 发了一次 contents 请求" 是后端实现细节, 用户不需要知道
            detail = "正在跟 GitHub 打招呼，几秒就回来。",
            accent = mute,
            glyph = null,
            showSpinner = true,
            actions = emptyList(),
        )
        is PatStatus.Valid -> PatStatusVisual(
            headline = "登录信息有效",
            // "sync 通道" 是 dev 行话, 用户语言是"同步"
            detail = "GitHub 接受了这枚登录信息，同步可以正常进行。",
            accent = ok,
            glyph = Icons.Filled.CheckCircle,
            showSpinner = false,
            actions = listOf(PatAction(PatActionKind.Verify, "重新验证")),
        )
        is PatStatus.Invalid -> PatStatusVisual(
            // ⚠️ emoji 在某些字体里会 fallback 成 ⬜, 跟 Fix #247 同样的问题;
            // 状态色 + 左侧 ErrorOutline 图标已经够强提示, 文案不必再重复警示符。
            headline = "登录信息已失效",
            detail = "${s.message}。建议「用 GitHub 重新登录」换一枚新的；老的会自动替换。",
            accent = bad,
            glyph = Icons.Filled.ErrorOutline,
            showSpinner = false,
            actions = listOf(
                PatAction(PatActionKind.Reauth, "用 GitHub 重新登录"),
                PatAction(PatActionKind.Verify, "重新验证"),
            ),
        )
        is PatStatus.CheckFailed -> PatStatusVisual(
            headline = "暂时验证不上",
            detail = "${s.message}。不一定是登录信息的问题，再试一次看看。",
            accent = warn,
            glyph = Icons.Filled.WarningAmber,
            showSpinner = false,
            actions = listOf(PatAction(PatActionKind.Verify, "重试")),
        )
    }
}

@Preview(showBackground = true, name = "PatStatus · valid")
@Composable
private fun PatStatusCardValidPreview() {
    MemoTheme {
        PatStatusCard(
            state = SettingsUiState(
                pat = "ghp_x",
                owner = "qqzlqqzlqqzl",
                repo = "memos",
                branch = "main",
                loaded = true,
                patStatus = PatStatus.Valid(checkedAt = 0L),
            ),
            onTestConnection = {},
            onReauth = {},
        )
    }
}

@Preview(showBackground = true, name = "PatStatus · invalid")
@Composable
private fun PatStatusCardInvalidPreview() {
    MemoTheme {
        PatStatusCard(
            state = SettingsUiState(
                pat = "ghp_revoked",
                owner = "qqzlqqzlqqzl",
                repo = "memos",
                branch = "main",
                loaded = true,
                patStatus = PatStatus.Invalid("GitHub 拒绝了这枚 PAT", checkedAt = 0L),
            ),
            onTestConnection = {},
            onReauth = {},
        )
    }
}

@Preview(showBackground = true, name = "PatStatus · verifying")
@Composable
private fun PatStatusCardVerifyingPreview() {
    MemoTheme {
        PatStatusCard(
            state = SettingsUiState(
                pat = "ghp_x",
                owner = "qqzlqqzlqqzl",
                repo = "memos",
                branch = "main",
                loaded = true,
                patStatus = PatStatus.Verifying,
            ),
            onTestConnection = {},
            onReauth = {},
        )
    }
}
