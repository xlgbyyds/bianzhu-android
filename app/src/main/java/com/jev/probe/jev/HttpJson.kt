package com.jev.probe.jev

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import android.util.Log

/**
 * Which of the three API routes a failure came from. Used to build error text
 * the user can act on ("判断接口 HTTP 401：…" vs "回复接口 …").
 */
object Route {
    const val JUDGE = "判断接口"
    const val REPLY = "回复接口"
    const val VISION = "视觉接口"
}

/**
 * Carries the route, the HTTP status (null = transport failure) and the first
 * 120 chars of the response body so the settings page can show the real reason.
 */
class ApiException(
    val route: String,
    val status: Int?,
    val snippet: String
) : RuntimeException(buildMessage(route, status, snippet)) {

    companion object {
        fun buildMessage(route: String, status: Int?, snippet: String): String =
            if (status != null) "$route HTTP $status：${snippet.take(120)}"
            else "$route 请求失败：${snippet.take(120)}"
    }
}

/**
 * Shared POST-JSON helper: UTF-8 body, one retry on transient failures, no retry
 * on other 4xx, and every failure normalized to [ApiException]. Keys are passed
 * in per call and never logged.
 */
object HttpJson {

    private const val MAX_ATTEMPTS = 2
    private val watchdog = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "jev-network-deadline").apply { isDaemon = true }
    }

    /**
     * @param route one of [Route], used only for error text.
     * @param extraHeaders additional request headers (e.g. OpenRouter attribution).
     */
    fun post(
        url: String,
        key: String,
        body: JSONObject,
        route: String,
        extraHeaders: Map<String, String> = emptyMap(),
        timeoutMs: Long = 40_000L,
        readTimeoutMs: Long = 25_000L
    ): JSONObject {
        require(timeoutMs > 0)
        require(readTimeoutMs > 0)
        val started = System.nanoTime()
        val deadline = started + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        fun remainingMs() = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(0)
        fun timeout() = ApiException(route, null, "请求已超时（${timeoutMs / 1000} 秒），请检查网络后重试")
        var attempt = 0
        var last: ApiException? = null
        while (attempt < MAX_ATTEMPTS) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Request cancelled")
            if (remainingMs() == 0L) throw timeout()
            var conn: HttpURLConnection? = null
            var completed = false
            val expired = AtomicBoolean(false)
            var timer: java.util.concurrent.ScheduledFuture<*>? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = minOf(10_000L, remainingMs().coerceAtLeast(1)).toInt()
                    readTimeout = minOf(readTimeoutMs, remainingMs().coerceAtLeast(1), Int.MAX_VALUE.toLong()).toInt()
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $key")
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
                }
                val connection = conn
                val caller = Thread.currentThread()
                timer = watchdog.scheduleAtFixedRate({
                    if (caller.isInterrupted || remainingMs() == 0L) {
                        expired.set(true)
                        runCatching { connection.disconnect() }
                    }
                }, minOf(100L, timeoutMs), minOf(100L, timeoutMs), TimeUnit.MILLISECONDS)
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                conn.outputStream.use { os: OutputStream -> os.write(bytes) }
                val code = conn.responseCode
                if (code == 429 || code == 529) {
                    last = ApiException(route, code, "服务繁忙，已重试")
                    attempt++
                    if (attempt < MAX_ATTEMPTS) Thread.sleep(500L * (1L shl attempt))
                    continue
                }
                // Branch on the status code FIRST. Reading the body must never be
                // able to lose it: errorStream is null on some failures (and on
                // some OEM stacks), and a read can throw on a truncated response —
                // either way this used to surface as a transport failure with no
                // status, which then got retried even for a 401.
                if (code !in 200..299) {
                    val errText = try { readBody(conn.errorStream) } catch (e: Exception) {
                        "响应读取失败：${describe(e)}"
                    }
                    throw ApiException(route, code, errText.ifBlank { "（响应体为空）" })
                }
                val text = try { readBody(conn.inputStream) } catch (e: Exception) {
                    throw ApiException(route, code, "响应读取失败：${describe(e)}")
                }
                if (text.isBlank()) throw ApiException(route, code, "响应体为空")
                val parsed = JSONObject(text)
                val usage = parsed.optJSONObject("usage")
                val inputTokens = usage?.optLong("input_tokens", usage.optLong("prompt_tokens", -1)) ?: -1
                val outputTokens = usage?.optLong("output_tokens", usage.optLong("completion_tokens", -1)) ?: -1
                val reasoningTokens = usage?.optJSONObject("completion_tokens_details")?.optLong("reasoning_tokens", -1) ?: -1
                val finish = parsed.optJSONArray("choices")?.optJSONObject(0)?.optString("finish_reason").orEmpty()
                Log.i("JEVASSIST", "http route=$route chars=${body.toString().length} bytes=${bytes.size} attempts=${attempt + 1} " +
                    "elapsed=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)}ms " +
                    "inputTokens=$inputTokens outputTokens=$outputTokens reasoningTokens=$reasoningTokens finish=$finish")
                // The response stream is closed by readBody. Keeping a successful
                // connection eligible for HTTP keep-alive avoids another TLS setup
                // for the subsequent Jev ranking request to the same host.
                completed = true
                return parsed
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            } catch (e: ApiException) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Request cancelled")
                if (e.status != null && e.status in 400..499) throw e  // client error: no retry
                if (expired.get() || remainingMs() == 0L) throw timeout()
                last = e
                attempt++
                if (attempt < MAX_ATTEMPTS) Thread.sleep(500L * (1L shl attempt))
            } catch (e: Exception) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Request cancelled")
                if (expired.get() || remainingMs() == 0L) throw timeout()
                last = ApiException(route, null, describe(e))
                attempt++
                if (attempt < MAX_ATTEMPTS) Thread.sleep(500L * (1L shl attempt))
            } finally {
                timer?.cancel(false)
                if (!completed) conn?.disconnect()
            }
        }
        if (remainingMs() == 0L) throw timeout()
        throw last ?: ApiException(route, null, "请求失败")
    }

    /** Body text, or "" when the HTTP response has no stream/body. Read errors are preserved. */
    private fun readBody(stream: java.io.InputStream?): String {
        stream ?: return ""
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
    }

    /** OpenRouter wants attribution headers; other hosts reject unknown ones politely. */
    fun headersFor(url: String): Map<String, String> =
        if (url.contains("openrouter.ai", ignoreCase = true))
            mapOf("HTTP-Referer" to "https://jev-assistant.local", "X-Title" to "Jev Assistant")
        else emptyMap()

    /** Human-readable transport failures (no key material ever appears here). */
    private fun describe(e: Exception): String {
        val m = e.message ?: e.javaClass.simpleName
        return when {
            m.contains("timed out") || m.contains("timeout", true) -> "网络超时，请检查连接"
            m.contains("Unable to resolve host") -> "域名解析失败，地址填错或无网络"
            m.contains("Failed to connect") || m.contains("ECONNREFUSED") -> "无法连接该地址"
            m.contains("CertPath") || m.contains("SSL") -> "HTTPS 证书校验失败"
            else -> m
        }
    }
}
