package dev.aria.memo.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.aria.memo.ui.theme.MemoSpacing
import dev.aria.memo.ui.theme.MemoTheme

/**
 * One slide of the onboarding flow.
 */
private data class OnboardingSlide(
    val icon: ImageVector,
    val title: String,
    val body: String,
)

private val SLIDES = listOf(
    OnboardingSlide(
        icon = Icons.Filled.Cloud,
        title = "你的笔记，存在你的 GitHub",
        body = "memo-widget 不在自己的服务器上保存任何东西。每条笔记都会同步到你 GitHub 仓库的 Markdown 文件——你随时可以用浏览器、其他客户端打开它。",
    ),
    OnboardingSlide(
        icon = Icons.Filled.Lock,
        title = "用一枚 GitHub PAT 登录",
        body = "需要给 app 一枚 Personal Access Token，权限范围只要 repo 读写就够了。我们把它加密保存在本机；如果换设备，重新填一次即可。",
    ),
    OnboardingSlide(
        icon = Icons.Filled.Settings,
        title = "下一步：去「设置」配置 owner / repo",
        body = "点击下方按钮，会带你到「设置」页填 PAT、用户名、仓库名。配好之后再回来写第一条笔记——就不会保存失败了。",
    ),
)

/**
 * First-launch onboarding (#144 Bug-2 Critical#1).
 *
 * Shown over the [AppNav] root when the user has never finished it. We
 * use a non-dismissable [AlertDialog] (tapping outside doesn't close it)
 * so the user makes a deliberate choice — either go to Settings or
 * "稍后" (skip), both of which mark the flow completed.
 *
 * The body explains the GitHub-backed model in plain Chinese before the
 * user encounters the FAB-tap-then-PAT-error trap.
 */
@Composable
fun OnboardingDialog(
    onGoToSettings: () -> Unit,
    onSkip: () -> Unit,
) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    val slide = SLIDES[index]
    val isLast = index == SLIDES.lastIndex

    AlertDialog(
        onDismissRequest = { /* non-dismissable */ },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = CircleShape,
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = slide.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Spacer(Modifier.size(MemoSpacing.sm))
                Text(slide.title)
            }
        },
        text = {
            Column {
                Text(
                    text = slide.body,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(MemoSpacing.md))
                ProgressDots(total = SLIDES.size, current = index)
            }
        },
        confirmButton = {
            if (isLast) {
                Button(onClick = onGoToSettings) {
                    Text("去设置")
                }
            } else {
                Button(onClick = { index++ }) {
                    Text("下一步")
                }
            }
        },
        dismissButton = {
            // The "稍后" button always reads the same — letting the user
            // bail out at any step without burying them in a wall of text.
            TextButton(onClick = onSkip) {
                Text("稍后")
            }
        },
    )
}

@Composable
private fun ProgressDots(total: Int, current: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(total) { i ->
            val color = if (i == current) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
            Surface(
                color = color,
                shape = CircleShape,
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(8.dp),
            ) {}
        }
    }
}

@Preview(showBackground = true, name = "Onboarding · slide 1")
@Composable
private fun OnboardingPreview() {
    MemoTheme {
        OnboardingDialog(onGoToSettings = {}, onSkip = {})
    }
}
