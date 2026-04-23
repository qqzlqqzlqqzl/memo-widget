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
        val normalized = fullBody.replace("\r\n", "\n")
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
     * 只去除含 `pinned: true|false` 的块（严格 bool 值）。
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
        var cut = closeMarker + "\n---".length
        while (cut < normalized.length && normalized[cut] == '\n') cut++
        return normalized.substring(cut)
    }

    /**
     * 给 body 加或去 `pinned: true`。
     * pinned=true：strip 已有块后前置 `---\npinned: true\n---\n\n<body>`。
     * pinned=false：strip（只吃 pin 块），不改用户自己的 YAML。
     */
    fun applyPin(fullBody: String, pinned: Boolean): String {
        val stripped = strip(fullBody)
        return if (pinned) {
            val body = stripped.trimStart('\n')
            "---\npinned: true\n---\n\n${body}".trimEnd('\n') + "\n"
        } else {
            stripped
        }
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
