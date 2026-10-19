package com.openski.android

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder

/**
 * Posts one Overpass query over HTTPS with a clear User-Agent, connect and read timeouts and a response size cap.
 * On HTTP 429 or 504 it waits (Retry-After when given, clamped) and tries exactly once more; nothing else is retried.
 * Blocking: call it from a background thread, never while skiing (the app downloads a resort once and reads the cache).
 */
class HttpResortFetcher(
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
    private val maxBytes: Long = MAX_RESPONSE_BYTES,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : ResortFetcher {
    override val source: String get() = "Overpass API ($endpoint)"

    override fun fetch(query: String): FetchResult {
        val first = attempt(query)
        val delay = first.retryAfterMs ?: return first.result
        sleep(delay)
        return attempt(query).result
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
                    ?.toByteArray()?.toString(Charsets.UTF_8)?.let(::plainText).orEmpty()
                val error = FetchResult.Failed(FetchError(FetchError.Kind.HTTP,
                    "HTTP $code${if (detail.isNotEmpty()) ": $detail" else ""}", code))
                return Attempt(error, if (code == 429 || code == 504) retryDelayMs(connection.getHeaderField("Retry-After")) else null)
            }
            val length = connection.contentLengthLong
            if (length > maxBytes) return Attempt(tooLarge())
            val bytes = connection.inputStream.use { readCapped(it, maxBytes, truncate = false) } ?: return Attempt(tooLarge())
            Attempt(FetchResult.Body(bytes.toByteArray().toString(Charsets.UTF_8)))
        } catch (e: SocketTimeoutException) {
            Attempt(FetchResult.Failed(FetchError(FetchError.Kind.TIMEOUT, "Timed out: ${e.message ?: "no answer"}")))
        } catch (e: IOException) {
            Attempt(FetchResult.Failed(FetchError(FetchError.Kind.NETWORK, "Network error: ${e.message ?: e.javaClass.simpleName}")))
        } catch (e: SecurityException) {
            Attempt(FetchResult.Failed(FetchError(FetchError.Kind.NETWORK, "No network permission: ${e.message ?: ""}")))
        } finally {
            connection?.disconnect()
        }
    }

    private fun tooLarge() = FetchResult.Failed(FetchError(FetchError.Kind.TOO_LARGE,
        "Response larger than ${maxBytes / (1024 * 1024)} MB; choose a smaller radius"))

    companion object {
        const val DEFAULT_ENDPOINT = "https://overpass-api.de/api/interpreter"
        /** Placeholder: set a real contact (an address or project URL) before any public release. */
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

        /** The wait before the single retry: Retry-After in whole seconds, clamped; a missing or date-form value gives the default. */
        fun retryDelayMs(retryAfter: String?): Long {
            val seconds = retryAfter?.trim()?.toLongOrNull() ?: return DEFAULT_RETRY_DELAY_MS
            return (seconds * 1000).coerceIn(MIN_RETRY_DELAY_MS, MAX_RETRY_DELAY_MS)
        }

        /** Reads at most [limit] bytes; past the limit returns null, or the first [limit] bytes when [truncate]. */
        internal fun readCapped(input: InputStream, limit: Long, truncate: Boolean): ByteArrayOutputStream? {
            val out = ByteArrayOutputStream()
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
