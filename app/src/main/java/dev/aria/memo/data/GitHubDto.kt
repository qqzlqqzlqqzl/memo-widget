package dev.aria.memo.data

import kotlinx.serialization.Serializable

/**
 * DTOs for the GitHub "Contents" REST API (v2022-11-28).
 *
 * GET on a file path returns [GhContents]; on a directory path it returns a
 * JSON array of items (same fields but `content` is absent for listings).
 * [GhContentListItem] covers that listing shape.
 *
 * GitHub's `content` field from GET is base64 with embedded newlines every
 * 60 chars (RFC 2045). [GhContents.decodedContent] strips whitespace first.
 */
@Serializable
data class GhContents(
    val sha: String,
    val content: String,
    val encoding: String,
) {
    val decodedContent: String
        get() {
            val stripped = content.replace("\\s".toRegex(), "")
            val bytes = java.util.Base64.getDecoder().decode(stripped)
            return String(bytes, Charsets.UTF_8)
        }
}

@Serializable
data class GhContentListItem(
    val name: String,
    val path: String,
    val sha: String,
    val type: String, // "file" | "dir" | "symlink" | "submodule"
    val size: Long = 0,
)

@Serializable
data class GhPutRequest(
    val message: String,
    val content: String,
    val branch: String,
    val sha: String? = null,
)

@Serializable
data class GhDeleteRequest(
    val message: String,
    val sha: String,
    val branch: String,
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
