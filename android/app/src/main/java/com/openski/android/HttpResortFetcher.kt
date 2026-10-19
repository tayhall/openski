package com.openski.android

import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Posts one Overpass query over HTTPS with a clear User-Agent, connect and read timeouts and a response size cap.
 * On HTTP 429 it waits (Retry-After when given, at least 30 s) and tries exactly once more; nothing else is retried
 * (a 504 means the server is busy or the query is too heavy, and repeating a resort download would add load).
 *
 * Blocking: call it from a background thread. On the main thread it refuses with [FetchError.Kind.MAIN_THREAD]
 * before sending anything. It never throws: interrupts, network and unexpected errors come back as [FetchError]s.
 */
class HttpResortFetcher(
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
    private val maxBytes: Long = MAX_RESPONSE_BYTES,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val isMainThread: () -> Boolean = { Looper.myLooper() == Looper.getMainLooper() },
    private val clock: () -> Long = System::currentTimeMillis,
) : ResortFetcher {
    override val source: String get() = "Overpass API ($endpoint)"

    override fun fetch(query: String): FetchResult = try {
        if (isMainThread()) failed(FetchError.Kind.MAIN_THREAD, "Resort download called on the main thread; nothing was sent")
        else {
            val first = attempt(query)
            val delay = first.retryAfterMs
            if (delay == null) first.result else {
                sleep(delay)
                attempt(query).result
            }
        }
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        failed(FetchError.Kind.INTERRUPTED, "Interrupted")
    } catch (e: RuntimeException) {
        failed(FetchError.Kind.FAILED, "Unexpected error: ${e.message ?: e.javaClass.simpleName}")
    }

    private class Attempt(val result: FetchResult, val retryAfterMs: Long? = null)

    private fun attempt(query: String): Attempt {
        var connection: HttpURLConnection? = null
        return try {
            val body = ("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray(Charsets.UTF_8)
            connection = open(URL(endpoint)).apply {
                requestMethod = "POST"
                doOutput = true
                instanceFollowRedirects = false
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setFixedLengthStreamingMode(body.size)
            }
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                val detail = connection.errorStream?.use { readCapped(it, ERROR_DETAIL_BYTES, truncate = true) }
                    ?.text()?.let(::plainText).orEmpty()
                val error = FetchResult.Failed(FetchError(FetchError.Kind.HTTP,
                    "HTTP $code${if (detail.isNotEmpty()) ": $detail" else ""}", code))
                return Attempt(error, if (code == 429) retryDelayMs(connection.getHeaderField("Retry-After"), clock()) else null)
            }
            val length = connection.contentLengthLong
            if (length > maxBytes) return Attempt(tooLarge())
            val bytes = connection.inputStream.use { readCapped(it, maxBytes, truncate = false, expected = length) }
                ?: return Attempt(tooLarge())
            Attempt(FetchResult.Body(bytes.text()))
        } catch (e: SocketTimeoutException) {
            Attempt(failed(FetchError.Kind.TIMEOUT, "Timed out: ${e.message ?: "no answer"}"))
        } catch (e: IOException) {
            Attempt(failed(FetchError.Kind.NETWORK, "Network error: ${e.message ?: e.javaClass.simpleName}"))
        } catch (e: SecurityException) {
            Attempt(failed(FetchError.Kind.NETWORK, "No network permission: ${e.message ?: ""}"))
        } finally {
            connection?.disconnect()
        }
    }

    private fun failed(kind: FetchError.Kind, message: String) = FetchResult.Failed(FetchError(kind, message))

    private fun tooLarge() = failed(FetchError.Kind.TOO_LARGE, "Response larger than ${maxBytes / (1024 * 1024)} MB; choose a smaller radius")

    /** A byte buffer that decodes its contents in place, without first copying them into a new array. */
    internal class Buffer(size: Int) : ByteArrayOutputStream(size) {
        fun text(): String = String(buf, 0, count, Charsets.UTF_8)
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://overpass-api.de/api/interpreter"
        /**
         * Placeholder: set a real contact (an address or project URL) before any public release. `ReleaseCheckTest`
         * fails on this value when OPENSKI_RELEASE_CHECK is set.
         */
        const val CONTACT = "contact-placeholder@openski.invalid"
        const val USER_AGENT = "OpenSki/0.1 (experimental ski-boot sensor app for Android; $CONTACT)"
        const val CONNECT_TIMEOUT_MS = 15_000
        /** Longer than the query's own server timeout so the server's answer, not ours, ends a slow query. */
        const val READ_TIMEOUT_MS = (OverpassQuery.AREA_TIMEOUT_S + 30) * 1000
        const val MAX_RESPONSE_BYTES = 16L * 1024 * 1024
        const val DEFAULT_RETRY_DELAY_MS = 30_000L
        /** The OSM wiki Overpass API page (read 19 Oct 2026) asks clients to pause 30 s after a 429. */
        const val MIN_RETRY_DELAY_MS = 30_000L
        const val MAX_RETRY_DELAY_MS = 120_000L
        private const val ERROR_DETAIL_BYTES = 2_048L
        private const val INITIAL_BUFFER_BYTES = 64 * 1024

        /**
         * The wait before the single retry: Retry-After as whole seconds or as an HTTP date (relative to [nowMs]),
         * clamped to [MIN_RETRY_DELAY_MS]..[MAX_RETRY_DELAY_MS]; missing or unreadable gives the default.
         */
        fun retryDelayMs(retryAfter: String?, nowMs: Long): Long {
            val value = retryAfter?.trim()?.takeIf { it.isNotEmpty() } ?: return DEFAULT_RETRY_DELAY_MS
            val ms = value.toLongOrNull()?.let { it * 1000 } ?: try {
                ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowMs
            } catch (e: DateTimeParseException) {
                return DEFAULT_RETRY_DELAY_MS
            }
            return ms.coerceIn(MIN_RETRY_DELAY_MS, MAX_RETRY_DELAY_MS)
        }

        /**
         * Reads at most [limit] bytes; past the limit returns null, or the first [limit] bytes when [truncate].
         * [expected] (the declared length, if any) sizes the buffer up front so it rarely has to grow.
         */
        internal fun readCapped(input: InputStream, limit: Long, truncate: Boolean, expected: Long = -1): Buffer? {
            val initial = if (expected in 1..limit) expected.toInt() else minOf(limit, INITIAL_BUFFER_BYTES.toLong()).toInt()
            val out = Buffer(initial.coerceAtLeast(32))
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) return out
                if (total + n > limit) {
                    if (!truncate) return null
                    out.write(buffer, 0, (limit - total).toInt())
                    return out
                }
                out.write(buffer, 0, n)
                total += n
            }
        }

        /** An error page (often HTML) reduced to one short line of text. */
        private fun plainText(s: String): String = s.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim().take(200)
    }
}
