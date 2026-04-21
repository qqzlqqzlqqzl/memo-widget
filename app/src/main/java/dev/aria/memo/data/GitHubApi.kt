package dev.aria.memo.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.io.IOException
import java.net.URLEncoder

/**
 * Thin GitHub Contents API client.
 *
 * Contract (AGENT_SPEC.md section 4.2):
 *  - Never throws; all failures become [MemoResult.Err].
 *  - HTTP 404 → [ErrorCode.NOT_FOUND] (benign — caller may treat as "empty").
 *  - HTTP 401/403 → [ErrorCode.UNAUTHORIZED].
 *  - HTTP 409 or 422 (sha conflict) → [ErrorCode.CONFLICT].
 *  - Any IO/network failure → [ErrorCode.NETWORK].
 *  - Anything else → [ErrorCode.UNKNOWN].
 *
 * PAT handling: the token is only used as a bearer header; it is NEVER
 * included in returned error messages or logs.
 */
class GitHubApi(private val httpClient: HttpClient) {

    suspend fun getFile(config: AppConfig, path: String): MemoResult<GhContents> {
        val url = buildUrl(config, path)
        return runCatchingHttp {
            val response: HttpResponse = httpClient.get(url) {
                githubHeaders(config)
                // GET ?ref=branch — ensures we read the right branch.
                url { parameters.append("ref", config.branch) }
            }
            when (response.status.value) {
                in 200..299 -> MemoResult.Ok(response.body<GhContents>())
                404 -> MemoResult.Err(ErrorCode.NOT_FOUND, "file not found")
                401, 403 -> MemoResult.Err(ErrorCode.UNAUTHORIZED, "github auth failed (${response.status.value})")
                409, 422 -> MemoResult.Err(ErrorCode.CONFLICT, "sha conflict")
                else -> MemoResult.Err(ErrorCode.UNKNOWN, "github GET ${response.status.value}: ${safeBody(response)}")
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
            when (response.status.value) {
                in 200..299 -> MemoResult.Ok(response.body<GhPutResponse>())
                404 -> MemoResult.Err(ErrorCode.NOT_FOUND, "repo or branch not found")
                401, 403 -> MemoResult.Err(ErrorCode.UNAUTHORIZED, "github auth failed (${response.status.value})")
                409, 422 -> MemoResult.Err(ErrorCode.CONFLICT, "sha conflict on PUT")
                else -> MemoResult.Err(ErrorCode.UNKNOWN, "github PUT ${response.status.value}: ${safeBody(response)}")
            }
        }
    }

    // --- helpers -----------------------------------------------------------

    private fun io.ktor.client.request.HttpRequestBuilder.githubHeaders(config: AppConfig) {
        header("Authorization", "Bearer ${config.pat}")
        accept(ContentType.parse("application/vnd.github+json"))
        header("X-GitHub-Api-Version", "2022-11-28")
    }

    private fun buildUrl(config: AppConfig, path: String): String {
        val encodedPath = path.split('/')
            .filter { it.isNotEmpty() }
            .joinToString("/") { segment ->
                // URLEncoder uses + for space; GitHub paths want %20, and we
                // also don't want / to be encoded inside a segment. Since we
                // split on / first, the segment has none — just swap + for %20.
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
        // Ktor wraps some IO failures in its own exceptions; treat as network
        // if the cause chain contains one, else UNKNOWN. We never rethrow.
        val cause = generateSequence<Throwable>(e) { it.cause }.firstOrNull { it is IOException }
        if (cause != null) {
            MemoResult.Err(ErrorCode.NETWORK, cause.message ?: "network error")
        } else {
            MemoResult.Err(ErrorCode.UNKNOWN, e::class.simpleName + ": " + (e.message ?: ""))
        }
    }

    @Suppress("unused")
    private companion object {
        // HttpStatusCode referenced to keep the import stable across ktor versions.
        private val OK = HttpStatusCode.OK
    }
}
