package dev.aria.memo.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Thin GitHub Contents API client.
 *
 * Contract:
 *  - Never throws; all failures become [MemoResult.Err].
 *  - HTTP 404 → [ErrorCode.NOT_FOUND] (benign — caller may treat as empty).
 *  - HTTP 401/403 → [ErrorCode.UNAUTHORIZED], except when the response headers
 *    show the PAT was rate-limited (X-RateLimit-Remaining == 0), in which case
 *    we surface [ErrorCode.UNKNOWN] with a "rate limit exceeded: reset at HH:mm"
 *    message so the UI doesn't scare the user into rotating a good token
 *    (Fixes #26). There's no dedicated RATE_LIMITED code yet.
 *  - HTTP 409/422 (sha conflict) → [ErrorCode.CONFLICT].
 *  - Any IO/network failure → [ErrorCode.NETWORK].
 *  - [listDir] on a path that points to a file instead of a directory returns
 *    [ErrorCode.UNKNOWN] with an explanatory message instead of a silent empty
 *    list (Fixes #25).
 *
 * PAT is only ever used as a bearer header — never included in error messages.
 */
class GitHubApi(private val httpClient: HttpClient) {

    suspend fun getFile(config: AppConfig, path: String): MemoResult<GhContents> {
        val url = buildUrl(config, path)
        return runCatchingHttp {
            val response: HttpResponse = httpClient.get(url) {
                githubHeaders(config)
                url { parameters.append("ref", config.branch) }
            }
            mapGetOrPut(response) { response.body<GhContents>() }
        }
    }

    suspend fun listDir(config: AppConfig, path: String): MemoResult<List<GhContentListItem>> {
        val url = buildUrl(config, path)
        return runCatchingHttp {
            val response: HttpResponse = httpClient.get(url) {
                githubHeaders(config)
                url { parameters.append("ref", config.branch) }
            }
            // Fixes #25: GitHub's Contents API returns a JSON *object* (not an
            // array) when `path` names a file. Deserialising that as a List
            // raises SerializationException — we translate it into an explicit
            // error so callers don't think the directory is empty.
            try {
                mapGetOrPut(response) { response.body<List<GhContentListItem>>() }
            } catch (e: SerializationException) {
                MemoResult.Err(
                    ErrorCode.UNKNOWN,
                    "expected directory, got file or malformed response",
                )
            }
        }
    }

    suspend fun putFile(
        config: AppConfig,
        path: String,
        request: GhPutRequest,
    ): MemoResult<GhPutResponse> {
        val url = buildUrl(config, path)
        return runCatchingHttp {
            val response: HttpResponse = httpClient.put(url) {
                githubHeaders(config)
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            mapGetOrPut(response) { response.body<GhPutResponse>() }
        }
    }

    suspend fun deleteFile(
        config: AppConfig,
        path: String,
        request: GhDeleteRequest,
    ): MemoResult<Unit> {
        val url = buildUrl(config, path)
        return runCatchingHttp {
            val response: HttpResponse = httpClient.delete(url) {
                githubHeaders(config)
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            when (response.status.value) {
                in 200..299 -> MemoResult.Ok(Unit)
                404 -> MemoResult.Err(ErrorCode.NOT_FOUND, "file not found")
                401, 403 -> rateLimitedOrAuthError(response, "DELETE")
                409, 422 -> MemoResult.Err(ErrorCode.CONFLICT, "sha conflict on DELETE")
                else -> MemoResult.Err(ErrorCode.UNKNOWN, "github DELETE ${response.status.value}: ${safeBody(response)}")
            }
        }
    }

    // --- helpers -----------------------------------------------------------

    private suspend inline fun <reified T> mapGetOrPut(
        response: HttpResponse,
        body: () -> T,
    ): MemoResult<T> = when (response.status.value) {
        in 200..299 -> MemoResult.Ok(body())
        404 -> MemoResult.Err(ErrorCode.NOT_FOUND, "not found")
        401, 403 -> rateLimitedOrAuthError(response, response.status.value.toString())
        409, 422 -> MemoResult.Err(ErrorCode.CONFLICT, "sha conflict")
        else -> MemoResult.Err(ErrorCode.UNKNOWN, "github ${response.status.value}: ${safeBody(response)}")
    }

    /**
     * Fixes #26: when GitHub responds 401/403 *and* the rate-limit counter is
     * exhausted, the cause is almost certainly throttling — not a bad PAT.
     * Surface it as a distinct UNKNOWN message so the user isn't nagged to
     * rotate credentials they didn't break.
     */
    private fun rateLimitedOrAuthError(response: HttpResponse, tag: String): MemoResult<Nothing> {
        val remaining = response.headers["X-RateLimit-Remaining"]
        return if (remaining == "0") {
            val resetHeader = response.headers["X-RateLimit-Reset"]
            val resetEpoch = resetHeader?.toLongOrNull()
            val resetAt = if (resetEpoch != null) {
                runCatching {
                    RESET_FORMATTER.format(
                        Instant.ofEpochSecond(resetEpoch).atZone(ZoneId.systemDefault()),
                    )
                }.getOrElse { "unknown" }
            } else {
                "unknown"
            }
            MemoResult.Err(ErrorCode.UNKNOWN, "rate limit exceeded: reset at $resetAt")
        } else {
            MemoResult.Err(ErrorCode.UNAUTHORIZED, "github auth failed ($tag)")
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.githubHeaders(config: AppConfig) {
        header("Authorization", "Bearer ${config.pat}")
        accept(ContentType.parse("application/vnd.github+json"))
        header("X-GitHub-Api-Version", "2022-11-28")
    }

    private fun buildUrl(config: AppConfig, path: String): String {
        val encodedPath = path.split('/')
            .filter { it.isNotEmpty() }
            .joinToString("/") { segment ->
                URLEncoder.encode(segment, Charsets.UTF_8.name()).replace("+", "%20")
            }
        return "https://api.github.com/repos/${config.owner}/${config.repo}/contents/$encodedPath"
    }

    private suspend fun safeBody(response: HttpResponse): String = try {
        response.bodyAsText().take(200)
    } catch (_: Throwable) {
        "<no body>"
    }

    private inline fun <T> runCatchingHttp(block: () -> MemoResult<T>): MemoResult<T> = try {
        block()
    } catch (e: IOException) {
        MemoResult.Err(ErrorCode.NETWORK, e.message ?: "network error")
    } catch (e: Throwable) {
        val cause = generateSequence<Throwable>(e) { it.cause }.firstOrNull { it is IOException }
        if (cause != null) {
            MemoResult.Err(ErrorCode.NETWORK, cause.message ?: "network error")
        } else {
            MemoResult.Err(ErrorCode.UNKNOWN, e::class.simpleName + ": " + (e.message ?: ""))
        }
    }

    @Suppress("unused")
    private companion object {
        private val OK = HttpStatusCode.OK
        private val RESET_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
