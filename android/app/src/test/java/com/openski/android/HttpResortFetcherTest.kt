package com.openski.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLDecoder

/** The HTTP client against scripted connections: nothing here opens a socket. */
class HttpResortFetcherTest {
    private class Reply(val code: Int, val body: String = "", val headers: Map<String, String> = emptyMap(),
                        val length: Long = -1, val failure: IOException? = null)

    private class FakeConnection(url: URL, private val reply: Reply) : HttpURLConnection(url) {
        val sent = ByteArrayOutputStream()
        val properties = mutableMapOf<String, String>()
        var disconnected = false
        override fun connect() {}
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun setRequestProperty(key: String, value: String) { properties[key] = value }
        override fun getOutputStream(): OutputStream { reply.failure?.let { throw it }; return sent }
        override fun getResponseCode(): Int = reply.code
        override fun getInputStream(): InputStream = ByteArrayInputStream(reply.body.toByteArray())
        override fun getErrorStream(): InputStream? = if (reply.code == 200) null else ByteArrayInputStream(reply.body.toByteArray())
        override fun getHeaderField(name: String): String? = reply.headers[name]
        override fun getContentLengthLong(): Long = reply.length
    }

    private class Harness(vararg replies: Reply, maxBytes: Long = HttpResortFetcher.MAX_RESPONSE_BYTES) {
        val queue = ArrayDeque(replies.toList())
        val connections = mutableListOf<FakeConnection>()
        val sleeps = mutableListOf<Long>()
        val fetcher = HttpResortFetcher(maxBytes = maxBytes, sleep = { sleeps += it },
            open = { url -> FakeConnection(url, queue.removeFirst()).also { connections += it } })
    }

    @Test fun successPostsTheQueryWithUserAgent() {
        val h = Harness(Reply(200, "{\"elements\":[]}"))
        assertEquals(FetchResult.Body("{\"elements\":[]}"), h.fetcher.fetch("[out:json];node(1);out;"))
        val c = h.connections.single()
        assertEquals("POST", c.requestMethod)
        assertEquals(HttpResortFetcher.DEFAULT_ENDPOINT, c.url.toString())
        assertEquals(HttpResortFetcher.USER_AGENT, c.properties["User-Agent"])
        assertTrue(HttpResortFetcher.USER_AGENT.startsWith("OpenSki/") && HttpResortFetcher.USER_AGENT.contains(HttpResortFetcher.CONTACT))
        assertEquals(HttpResortFetcher.CONNECT_TIMEOUT_MS, c.connectTimeout)
        assertEquals(90_000, c.readTimeout)
        assertEquals("data=[out:json];node(1);out;", URLDecoder.decode(c.sent.toString(Charsets.UTF_8.name()), "UTF-8"))
        assertTrue(c.disconnected)
        assertTrue(h.sleeps.isEmpty())
    }

    @Test fun retriesOnceOn429HonouringRetryAfter() {
        val h = Harness(Reply(429, "<html><body><p>Too many requests</p></body></html>", mapOf("Retry-After" to "45")), Reply(200, "{}"))
        assertEquals(FetchResult.Body("{}"), h.fetcher.fetch("q"))
        assertEquals(listOf(45_000L), h.sleeps)
        assertEquals(2, h.connections.size)
    }

    @Test fun secondBusyAnswerIsReturnedNotRetried() {
        val h = Harness(Reply(504, "Gateway Timeout"), Reply(504, "Gateway Timeout"))
        val r = h.fetcher.fetch("q") as FetchResult.Failed
        assertEquals(FetchError.Kind.HTTP, r.error.kind)
        assertEquals(504, r.error.httpCode)
        assertEquals("HTTP 504: Gateway Timeout", r.error.message)
        assertEquals(listOf(HttpResortFetcher.DEFAULT_RETRY_DELAY_MS), h.sleeps)
        assertEquals(2, h.connections.size)
    }

    @Test fun otherErrorsAreNotRetried() {
        val h = Harness(Reply(400, "<p>Error: line 1: parse error</p>"))
        val r = h.fetcher.fetch("q") as FetchResult.Failed
        assertEquals(400, r.error.httpCode)
        assertEquals("HTTP 400: Error: line 1: parse error", r.error.message)
        assertTrue(h.sleeps.isEmpty())
        assertEquals(1, h.connections.size)
    }

    @Test fun networkAndTimeoutErrorsAreTyped() {
        assertEquals(FetchError.Kind.TIMEOUT, (Harness(Reply(200, failure = SocketTimeoutException("connect"))).fetcher.fetch("q") as FetchResult.Failed).error.kind)
        assertEquals(FetchError.Kind.NETWORK, (Harness(Reply(200, failure = IOException("no route"))).fetcher.fetch("q") as FetchResult.Failed).error.kind)
    }

    @Test fun sizeCap() {
        val declared = Harness(Reply(200, "{}", length = 2_000), maxBytes = 1_000).fetcher.fetch("q") as FetchResult.Failed
        assertEquals(FetchError.Kind.TOO_LARGE, declared.error.kind)
        val streamed = Harness(Reply(200, "x".repeat(1_001)), maxBytes = 1_000).fetcher.fetch("q") as FetchResult.Failed
        assertEquals(FetchError.Kind.TOO_LARGE, streamed.error.kind)
        assertEquals(FetchResult.Body("x".repeat(1_000)), Harness(Reply(200, "x".repeat(1_000)), maxBytes = 1_000).fetcher.fetch("q"))
    }

    @Test fun retryDelay() {
        assertEquals(HttpResortFetcher.DEFAULT_RETRY_DELAY_MS, HttpResortFetcher.retryDelayMs(null))
        assertEquals(HttpResortFetcher.DEFAULT_RETRY_DELAY_MS, HttpResortFetcher.retryDelayMs("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertEquals(45_000L, HttpResortFetcher.retryDelayMs(" 45 "))
        assertEquals(HttpResortFetcher.MIN_RETRY_DELAY_MS, HttpResortFetcher.retryDelayMs("7"))
        assertEquals(HttpResortFetcher.MAX_RETRY_DELAY_MS, HttpResortFetcher.retryDelayMs("3600"))
        assertEquals(HttpResortFetcher.MIN_RETRY_DELAY_MS, HttpResortFetcher.retryDelayMs("0"))
    }
}
