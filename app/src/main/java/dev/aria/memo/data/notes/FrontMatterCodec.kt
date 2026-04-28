package dev.aria.memo.data.notes

/**
 * YAML front-matter 解析 & 写回（pin 标志 round-trip）。
 * 纯函数，无状态，无 Android 依赖。
 *
 * HANDOFF.md P6.1 第 7 项：从 MemoRepository companion object 抽离。
 */
object FrontMatterCodec {

    /**
     * 解析结果。[frontMatter] 为空表示没有有效的 YAML 块；
     * [body] 是去掉开/合 `---` 之后的正文（尾部一条或多条空行也会吃掉）。
     */
    data class Parsed(
        val frontMatter: Map<String, String>,
        val body: String,
    )

    /**
     * Parse: 首行 `---`，找到配对的结束 `---`，提取中间的 `key: value` 对。
     * 不要求有 `pinned` 键——任何合法 `key: value` 块都可解析。
     * 任一非空行不是合法 `key: value` 时退回 Parsed(emptyMap, fullBody)。
     */
    fun parse(fullBody: String): Parsed {
        // Fixes #139 (Data-1 R5): iOS / Obsidian editors sometimes write
        // a UTF-8 BOM (U+FEFF) or a leading blank line in front of the
        // YAML fence, which made the previous strict startsWith("---\n")
        // check return the body verbatim — the front matter (including
        // pinned) was lost. Strip a BOM and any leading blank lines
        // before the gate; the body returned to callers retains the
        // original fullBody untouched on the no-front-matter path.
        // Use the escape sequence rather than a literal U+FEFF in the
        // source so Android Lint's ByteOrderMark check stays happy.
        val stripped = if (fullBody.isNotEmpty() && fullBody[0].code == 0xFEFF) fullBody.substring(1) else fullBody
        val normalized = stripped
            .replace("\r\n", "\n")
            .trimStart('\n')
        if (!normalized.startsWith("---\n") && normalized != "---") {
            return Parsed(emptyMap(), fullBody)
        }
        val afterOpen = normalized.indexOf('\n') + 1
        val closeMarker = normalized.indexOf("\n---", afterOpen)
        if (closeMarker < 0) return Parsed(emptyMap(), fullBody)
        val block = normalized.substring(afterOpen, closeMarker)
        val map = mutableMapOf<String, String>()
        for (raw in block.split('\n')) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val idx = line.indexOf(':')
            if (idx <= 0) return Parsed(emptyMap(), fullBody)
            val key = line.substring(0, idx).trim()
            if (key.isEmpty() || !key.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
                return Parsed(emptyMap(), fullBody)
            }
            val value = line.substring(idx + 1).trim()
            map[key] = value
        }
        if (map.isEmpty()) return Parsed(emptyMap(), fullBody)
        var cut = closeMarker + "\n---".length
        while (cut < normalized.length && normalized[cut] == '\n') cut++
        return Parsed(map, normalized.substring(cut))
    }

    /**
     * 去除 `pinned: true|false` 标志（严格 bool 值），**保留**用户的其他 YAML 键。
     *
     * Data-1 R4 修复：旧行为是看到一个合法 `pinned:` 键就把整块 frontmatter
     * 全部吃掉（含 `author: alice` 等用户自定义键），导致关闭 pin 后用户手写的
     * Obsidian frontmatter 丢失。新行为：
     *
     *  - 非我们管的块（没有 `pinned` 严格 bool 键）：返回原文，一字不动。
     *  - 只有 `pinned:` 的块：整块（含 `---` 开/合 fence）被吃掉，保持旧行为。
     *  - `pinned:` + 其他键：**只移除 `pinned:` 行**，其他键留在原位；
     *    fence 保持不动；分隔空行去噪。
     *
     * 用户手写的 YAML（无 pinned 键）或非 bool 值（如 `pinned: 1`）不会被吃。
     * 容忍 CRLF。
     */
    fun strip(fullBody: String): String {
        val normalized = fullBody.replace("\r\n", "\n")
        if (!normalized.startsWith("---\n") && normalized != "---") return fullBody
        val afterOpen = normalized.indexOf('\n') + 1
        val closeMarker = normalized.indexOf("\n---", afterOpen)
        if (closeMarker < 0) return fullBody
        val block = normalized.substring(afterOpen, closeMarker)
        if (!hasPinnedKeyWithStrictBool(block)) return fullBody

        // Data-1 R4: compute whether the block is pin-only. If so, drop the
        // whole fenced block (legacy behavior). If it carries additional user
        // keys, rewrite the block with only those keys preserved.
        val linesAll = block.split('\n')
        val nonPinLines = mutableListOf<String>()
        var sawPin = false
        for (raw in linesAll) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val idx = line.indexOf(':')
            // Already validated by hasPinnedKeyWithStrictBool — we know every
            // non-blank line is `key: value`.
            val key = line.substring(0, idx).trim()
            if (key == "pinned") {
                sawPin = true
                continue
            }
            nonPinLines += raw
        }
        // Shouldn't happen because hasPinnedKeyWithStrictBool returned true,
        // but guard anyway so we don't corrupt anything in odd edge cases.
        if (!sawPin) return fullBody

        val tailStart = closeMarker + "\n---".length
        var tailCut = tailStart
        // Preserve the legacy "eat trailing blank separator" behavior only
        // when we're removing the whole block — otherwise users who want a
        // blank line between frontmatter and body keep it.
        if (nonPinLines.isEmpty()) {
            while (tailCut < normalized.length && normalized[tailCut] == '\n') tailCut++
            return normalized.substring(tailCut)
        }
        // Rebuild a frontmatter block that contains only the non-pin keys.
        val rebuilt = buildString {
            append("---\n")
            for (l in nonPinLines) {
                append(l)
                append('\n')
            }
            append("---")
        }
        return rebuilt + normalized.substring(tailStart)
    }

    /**
     * 给 body 加或去 `pinned: true`。
     *
     * pinned=true：先 strip 掉已有 pin 行（保留用户其他键），然后：
     *   - 如果 stripped 结果以另一个合法 user-yaml 块开头，把 `pinned: true`
     *     **合并**进那个块顶部（避免嵌套出两层 `---` 导致用户 YAML 被 body 吞掉）。
     *   - 否则前置一个新的 `---\npinned: true\n---\n\n<body>` 块。
     * pinned=false：strip（只去 pinned 行、保留用户其他键），不改用户自己的 YAML。
     *
     * Data-1 R4 联动：strip 现在保留非 pin 键，applyPin(true) 必须 merge
     * 才不会把用户的 `author: alice` 永久塞进 markdown body。
     */
    fun applyPin(fullBody: String, pinned: Boolean): String {
        val stripped = strip(fullBody)
        if (!pinned) return stripped

        // Check whether the stripped result still carries a user-YAML block
        // (i.e. strip removed our pin line but left `author: alice` keys in
        // place). If so, merge rather than nest.
        val normalized = stripped.replace("\r\n", "\n")
        if (normalized.startsWith("---\n")) {
            val afterOpen = normalized.indexOf('\n') + 1
            val closeMarker = normalized.indexOf("\n---", afterOpen)
            if (closeMarker > 0) {
                val block = normalized.substring(afterOpen, closeMarker)
                // Every non-blank line must look like `key: value` for this
                // to be a user YAML block (vs. an HR + heading pattern).
                if (blockIsAllKeyValue(block) && !blockMentionsPinned(block)) {
                    val merged = "---\npinned: true\n$block\n---"
                    val tailStart = closeMarker + "\n---".length
                    val out = merged + normalized.substring(tailStart)
                    return out.trimEnd('\n') + "\n"
                }
            }
        }

        val body = stripped.trimStart('\n')
        return "---\npinned: true\n---\n\n${body}".trimEnd('\n') + "\n"
    }

    /** True when every non-blank line in [block] matches `key: value`. */
    private fun blockIsAllKeyValue(block: String): Boolean {
        if (block.isEmpty()) return false
        var anyLine = false
        for (raw in block.split('\n')) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val idx = line.indexOf(':')
            if (idx <= 0) return false
            val key = line.substring(0, idx).trim()
            if (key.isEmpty() || !key.all { it.isLetterOrDigit() || it == '_' || it == '-' }) return false
            anyLine = true
        }
        return anyLine
    }

    /** True when [block] contains any `pinned:` key (strict bool or not). */
    private fun blockMentionsPinned(block: String): Boolean {
        for (raw in block.split('\n')) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val key = line.substring(0, idx).trim()
            if (key == "pinned") return true
        }
        return false
    }

    /**
     * 识别"只有 pin 标志"的 front-matter。
     * 非空行必须全部是 `pinned: true|false`（严格 bool，case-insensitive，不可带引号的 `"true"`）。
     * 含其他键（如 `author: alice`）→ false。
     * P6 修复保留：`pinned: 1`、`pinned: yes`、`pinned: "true"` 都判为 false（用户的 YAML）。
     */
    fun looksLikePinOnlyFrontMatter(firstLines: List<String>): Boolean {
        var sawPinned = false
        for (raw in firstLines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val idx = line.indexOf(':')
            if (idx <= 0) return false
            val key = line.substring(0, idx).trim()
            if (key.isEmpty() || !key.all { it.isLetterOrDigit() || it == '_' || it == '-' }) return false
            val rawValue = line.substring(idx + 1).trim()
            if (key == "pinned") {
                // 带引号的 "true" / 'true' 不当作严格 bool。
                if (rawValue.startsWith('"') || rawValue.startsWith('\'')) return false
                if (!rawValue.equals("true", ignoreCase = true) &&
                    !rawValue.equals("false", ignoreCase = true)) return false
                sawPinned = true
            } else {
                return false
            }
        }
        return sawPinned
    }

    /**
     * 内部：判断 `---` 包围的块是"我们的 pin block"（可安全吃掉）
     * 还是"用户的 YAML/Markdown HR"（必须保留）。
     * 要求：每个非空行都是合法 `key: value`；且至少有一行 key=pinned value=严格 bool。
     * 允许其他键同时存在（该情境下 applyPin(false) 仍只吃整块——这是 P6 简化策略）。
     */
    internal fun hasPinnedKeyWithStrictBool(block: String): Boolean {
        if (block.isEmpty()) return false
        var sawPinned = false
        for (raw in block.split('\n')) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val idx = line.indexOf(':')
            if (idx <= 0) return false
            val key = line.substring(0, idx).trim()
            if (key.isEmpty() || !key.all { it.isLetterOrDigit() || it == '_' || it == '-' }) return false
            if (key == "pinned") {
                val rawValue = line.substring(idx + 1).trim()
                if (rawValue.startsWith('"') || rawValue.startsWith('\'')) return false
                if (!rawValue.equals("true", ignoreCase = true) &&
                    !rawValue.equals("false", ignoreCase = true)) return false
                sawPinned = true
            }
        }
        return sawPinned
    }
}
