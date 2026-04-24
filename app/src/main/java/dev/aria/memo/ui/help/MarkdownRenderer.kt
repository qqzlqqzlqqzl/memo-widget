package dev.aria.memo.ui.help

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Minimal Markdown-to-Compose renderer built for [HelpScreen]. We deliberately avoid
 * third-party markdown libs to keep APK size down; the set of constructs supported
 * here was chosen to cover everything that appears in USER_GUIDE.md:
 *
 * - `#` / `##` / `###` headings (bolder, progressively smaller)
 * - `> ` block quotes (left-border + inset)
 * - ```lang fenced code blocks (monospace Surface; `mermaid` → placeholder text)
 * - `| col | col |` GFM tables with an optional `|---|---|` separator row
 * - `![alt](path)` images — we only render an italic placeholder because we don't
 *   ship the `screenshots/` directory as assets (it would bloat the APK).
 * - `[text](url)` links — underlined + primary color; clicking opens a browser.
 * - Plain paragraphs, bullet/numbered lists (rendered as plain text with the
 *   bullet glyph preserved since that's how the source reads).
 *
 * The parser is line-oriented and strictly single-pass. It's good enough for our
 * guide but not a full CommonMark implementation; keep that in mind before
 * reusing it elsewhere.
 */

/** Public-ish block representation — internal to this file, but parseBlocks is used by tests. */
internal sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class BlockQuote(val text: String) : MdBlock
    data class CodeBlock(val lang: String, val content: String) : MdBlock
    data class Table(val rows: List<List<String>>) : MdBlock
    data class Image(val alt: String, val path: String) : MdBlock
    data class BulletList(val items: List<String>) : MdBlock
    object HorizontalRule : MdBlock
}

/** Parse a full markdown document into a flat list of blocks. */
internal fun parseBlocks(source: String): List<MdBlock> {
    val lines = source.lines()
    val blocks = mutableListOf<MdBlock>()
    var i = 0
    val paragraphBuf = mutableListOf<String>()
    val quoteBuf = mutableListOf<String>()
    val bulletBuf = mutableListOf<String>()

    fun flushParagraph() {
        if (paragraphBuf.isNotEmpty()) {
            blocks += MdBlock.Paragraph(paragraphBuf.joinToString("\n").trimEnd())
            paragraphBuf.clear()
        }
    }
    fun flushQuote() {
        if (quoteBuf.isNotEmpty()) {
            blocks += MdBlock.BlockQuote(quoteBuf.joinToString("\n"))
            quoteBuf.clear()
        }
    }
    fun flushBullets() {
        if (bulletBuf.isNotEmpty()) {
            blocks += MdBlock.BulletList(bulletBuf.toList())
            bulletBuf.clear()
        }
    }
    fun flushAll() {
        flushParagraph()
        flushQuote()
        flushBullets()
    }

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()

        // Blank line — paragraph/quote/list terminator.
        if (line.isBlank()) {
            flushAll()
            i++
            continue
        }

        // Horizontal rule: --- on its own line.
        if (trimmed == "---" || trimmed == "***") {
            flushAll()
            blocks += MdBlock.HorizontalRule
            i++
            continue
        }

        // Fenced code block.
        if (trimmed.startsWith("```")) {
            flushAll()
            val lang = trimmed.removePrefix("```").trim()
            val bodyBuf = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                bodyBuf.append(lines[i])
                bodyBuf.append('\n')
                i++
            }
            // Drop the trailing newline so the surface doesn't show an empty last line.
            val body = bodyBuf.toString().trimEnd('\n')
            blocks += MdBlock.CodeBlock(lang = lang, content = body)
            // Skip closing fence if present.
            if (i < lines.size) i++
            continue
        }

        // Heading (## foo). Exactly 1-3 hashes — anything deeper is atypical; we
        // map 4+ to level 3 to avoid vanishing.
        val headingMatch = Regex("^(#{1,6})\\s+(.+)$").find(trimmed)
        if (headingMatch != null) {
            flushAll()
            val level = headingMatch.groupValues[1].length.coerceAtMost(3)
            blocks += MdBlock.Heading(level = level, text = headingMatch.groupValues[2].trim())
            i++
            continue
        }

        // Standalone image line: ![alt](path). Treated as its own block so it
        // doesn't inline-mangle with surrounding paragraphs.
        val imageMatch = Regex("^!\\[([^\\]]*)\\]\\(([^)]+)\\)\\s*$").find(trimmed)
        if (imageMatch != null) {
            flushAll()
            blocks += MdBlock.Image(
                alt = imageMatch.groupValues[1],
                path = imageMatch.groupValues[2],
            )
            i++
            continue
        }

        // Block quote.
        if (trimmed.startsWith("> ") || trimmed == ">") {
            flushParagraph()
            flushBullets()
            quoteBuf += trimmed.removePrefix(">").removePrefix(" ")
            i++
            continue
        } else if (quoteBuf.isNotEmpty()) {
            flushQuote()
        }

        // Table: a line with at least 2 pipes. Gobble all contiguous pipe lines.
        if (isTableLine(trimmed)) {
            flushAll()
            val rows = mutableListOf<List<String>>()
            while (i < lines.size && isTableLine(lines[i].trimStart())) {
                val cells = splitTableRow(lines[i].trimStart())
                // Skip the `|---|---|` separator row which only contains dashes/colons.
                val isSeparator = cells.all { it.matches(Regex("^:?-+:?$")) }
                if (!isSeparator) rows += cells
                i++
            }
            blocks += MdBlock.Table(rows)
            continue
        }

        // Bullet list.
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.matches(Regex("^\\d+\\.\\s.+"))) {
            flushParagraph()
            bulletBuf += trimmed
            i++
            continue
        } else if (bulletBuf.isNotEmpty()) {
            flushBullets()
        }

        // Plain paragraph line.
        paragraphBuf += line
        i++
    }

    flushAll()
    return blocks
}

internal fun isTableLine(line: String): Boolean {
    // Minimal test: starts with `|`, ends with `|`, has at least 2 pipes.
    val trimmed = line.trim()
    if (!trimmed.startsWith("|") || !trimmed.endsWith("|")) return false
    return trimmed.count { it == '|' } >= 2
}

internal fun splitTableRow(line: String): List<String> {
    val trimmed = line.trim().removePrefix("|").removeSuffix("|")
    return trimmed.split("|").map { it.trim() }
}

/**
 * Render a parsed set of blocks as stacked composables. Each block handles its
 * own vertical spacing — callers provide the wrapping column.
 */
@Composable
fun RenderMarkdown(source: String, modifier: Modifier = Modifier) {
    // Medium fix: memoize the parsed block list so recompositions (e.g. theme
    // change, scroll state) don't re-run the ~500-line regex pipeline.
    val blocks = androidx.compose.runtime.remember(source) { parseBlocks(source) }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        blocks.forEach { block -> RenderBlock(block) }
    }
}

@Composable
private fun RenderBlock(block: MdBlock) {
    when (block) {
        is MdBlock.Heading -> HeadingText(block.level, block.text)
        is MdBlock.Paragraph -> InlineText(block.text)
        is MdBlock.BlockQuote -> BlockQuoteBox(block.text)
        is MdBlock.CodeBlock -> CodeBlockBox(block.lang, block.content)
        is MdBlock.Table -> TableBox(block.rows)
        is MdBlock.Image -> ImagePlaceholder(block.alt, block.path)
        is MdBlock.BulletList -> BulletListBox(block.items)
        MdBlock.HorizontalRule -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

@Composable
private fun HeadingText(level: Int, text: String) {
    val style = when (level) {
        1 -> MaterialTheme.typography.headlineMedium
        2 -> MaterialTheme.typography.titleLarge
        else -> MaterialTheme.typography.titleMedium
    }
    Text(
        text = text,
        style = style.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (level == 1) 8.dp else 4.dp, bottom = 2.dp),
    )
}

@Composable
private fun InlineText(text: String) {
    // Fix-7 #1: pipe theme-resolved link + code-bg colors into the
    // non-composable annotator so dark mode / dynamic color respond.
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBg = dev.aria.memo.ui.theme.MemoThemeColors.inlineCodeBg
    val annotated = buildInlineAnnotatedString(text, linkColor, codeBg)
    val ctx = LocalContext.current
    if (annotated.getStringAnnotations("URL", 0, annotated.length).isEmpty()) {
        Text(
            text = annotated,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        ClickableText(
            text = annotated,
            style = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            onClick = { offset ->
                annotated.getStringAnnotations("URL", offset, offset).firstOrNull()?.let { ann ->
                    runCatching {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(ann.item)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        ctx.startActivity(intent)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun BlockQuoteBox(text: String) {
    // Minor fix: `height(IntrinsicSize.Min)` makes the Row take the natural
    // height of its tallest child (the text column), which in turn lets the
    // left bar `fillMaxHeight` stretch correctly instead of collapsing to 1dp.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(androidx.compose.foundation.layout.IntrinsicSize.Min)
            .padding(vertical = 2.dp),
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(8.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                // Render each logical line of the blockquote through the inline
                // pipeline so links inside `> ...` still work.
                text.split("\n").forEach { line ->
                    if (line.isBlank()) {
                        Spacer(Modifier.height(4.dp))
                    } else {
                        InlineText(line)
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeBlockBox(lang: String, content: String) {
    if (lang.equals("mermaid", ignoreCase = true)) {
        // Mermaid rendering would require a webview + JS — out of scope.
        // Show a neutral placeholder so the user knows the figure exists.
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "流程图(Mermaid) — 在 GitHub 仓库查看原图。",
                style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                modifier = Modifier.padding(12.dp),
            )
        }
        return
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = content,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun TableBox(rows: List<List<String>>) {
    if (rows.isEmpty()) return
    val border = MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, border, RoundedCornerShape(4.dp)),
    ) {
        rows.forEachIndexed { index, row ->
            val isHeader = index == 0
            val background = if (isHeader) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                Color.Transparent
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(background),
            ) {
                row.forEachIndexed { cellIndex, cell ->
                    if (cellIndex > 0) {
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .background(border),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        // Fix-7 #1: themed link + code-bg colors.
                        val annotated = buildInlineAnnotatedString(
                            cell,
                            MaterialTheme.colorScheme.primary,
                            dev.aria.memo.ui.theme.MemoThemeColors.inlineCodeBg,
                        )
                        Text(
                            text = annotated,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            if (index < rows.lastIndex) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(border),
                )
            }
        }
    }
}

@Composable
private fun ImagePlaceholder(alt: String, path: String) {
    // Screenshots aren't bundled (would bloat the APK); show a compact placeholder
    // so the alt text still gives context.
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        val label = if (alt.isBlank()) path else alt
        Text(
            text = "(截图：$label — 见 GitHub 仓库 $path)",
            style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
            modifier = Modifier.padding(8.dp),
        )
    }
}

@Composable
private fun BulletListBox(items: List<String>) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEach { raw ->
            val (marker, body) = extractBullet(raw)
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "$marker ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(modifier = Modifier.weight(1f)) {
                    InlineText(body)
                }
            }
        }
    }
}

private fun extractBullet(raw: String): Pair<String, String> {
    val dashMatch = Regex("^([-*])\\s+(.+)$").matchEntire(raw)
    if (dashMatch != null) return "•" to dashMatch.groupValues[2]
    val numberedMatch = Regex("^(\\d+)\\.\\s+(.+)$").matchEntire(raw)
    if (numberedMatch != null) return "${numberedMatch.groupValues[1]}." to numberedMatch.groupValues[2]
    return "•" to raw
}

/**
 * Convert inline markdown (bold `**foo**`, italic `*foo*`/`_foo_`, inline code
 * `` `foo` ``, links `[txt](url)`, standalone URLs) into an [AnnotatedString].
 *
 * Only the set of constructs present in USER_GUIDE.md is handled — the goal is
 * "render the guide readably", not "parse arbitrary markdown".
 *
 * Fix-7 #1 (UI-A report): `linkColor` / `codeBgColor` are now parameters
 * instead of hardcoded `Color(0xFF1976D2)` / `Color(0x22808080)`. Callers in
 * `@Composable` code pass `MaterialTheme.colorScheme.primary` and
 * `MemoThemeColors.inlineCodeBg` so dark mode / dynamic color can respond.
 * Defaults preserve the original values for non-composable call sites (unit
 * tests) that don't care about palette.
 */
internal fun buildInlineAnnotatedString(
    raw: String,
    linkColor: Color = Color(0xFF1976D2),
    codeBgColor: Color = Color(0x22808080),
): AnnotatedString = buildAnnotatedString {
    // Strip zero-width characters that sometimes sneak into anchors.
    var remaining = raw
    while (remaining.isNotEmpty()) {
        // Explicit link: [text](url)
        val linkMatch = Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)").find(remaining)
        val codeMatch = Regex("`([^`]+)`").find(remaining)
        val boldMatch = Regex("\\*\\*([^*]+)\\*\\*").find(remaining)
        // Italic: match * or _ but avoid bold (`**` handled first via priority).
        val italicMatch = Regex("(?<![*_])[*_]([^*_\n]+)[*_](?![*_])").find(remaining)

        val candidates = listOfNotNull(
            linkMatch?.let { "link" to it.range.first },
            codeMatch?.let { "code" to it.range.first },
            boldMatch?.let { "bold" to it.range.first },
            italicMatch?.let { "italic" to it.range.first },
        )
        val next = candidates.minByOrNull { it.second }
        if (next == null) {
            append(remaining)
            break
        }
        // Text before the match.
        append(remaining.substring(0, next.second))
        when (next.first) {
            "link" -> {
                val m = linkMatch!!
                val text = m.groupValues[1]
                val url = m.groupValues[2]
                val start = length
                withStyle(
                    SpanStyle(
                        color = linkColor,
                        textDecoration = TextDecoration.Underline,
                    )
                ) {
                    append(text)
                }
                addStringAnnotation("URL", url, start, length)
                remaining = remaining.substring(m.range.last + 1)
            }
            "code" -> {
                val m = codeMatch!!
                withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = codeBgColor,
                    )
                ) {
                    append(m.groupValues[1])
                }
                remaining = remaining.substring(m.range.last + 1)
            }
            "bold" -> {
                val m = boldMatch!!
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(m.groupValues[1])
                }
                remaining = remaining.substring(m.range.last + 1)
            }
            "italic" -> {
                val m = italicMatch!!
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(m.groupValues[1])
                }
                remaining = remaining.substring(m.range.last + 1)
            }
        }
    }
}
