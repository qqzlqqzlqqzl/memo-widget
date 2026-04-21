package dev.aria.memo.data

import kotlinx.serialization.Serializable

/**
 * DTOs for the GitHub "Contents" REST API (v2022-11-28).
 *
 * Matches AGENT_SPEC.md section 2.3. These are transport types only; callers
 * should work with decoded strings + [MemoEntry] once the response is parsed.
 *
 * GitHub's `content` field from GET is base64 **with embedded newlines every
 * 60 chars** (RFC 2045). Callers decoding [GhContents.content] MUST strip
 * whitespace first — see [GhContents.decodedContent].
 */
@Serializable
data class GhContents(
    val sha: String,
    val content: String,
    val encoding: String,
) {
    /**
     * Base64-decoded file content as a UTF-8 string.
     *
     * GitHub returns base64 wrapped at 60 columns with `\n` separators. We
     * strip *all* whitespace before decoding; [android.util.Base64.DEFAULT]
     * is lenient, but callers may also use java.util.Base64 which is not.
     */
    val decodedContent: String
        get() {
            val stripped = content.replace("\\s".toRegex(), "")
            val bytes = java.util.Base64.getDecoder().decode(stripped)
            return String(bytes, Charsets.UTF_8)
        }
}

@Serializable
data class GhPutRequest(
    val message: String,
    val content: String,
    val branch: String,
    val sha: String? = null,
)

@Serializable
data class GhPutResponse(
    val content: GhFileInfo,
)

@Serializable
data class GhFileInfo(
    val sha: String,
    val path: String,
)
