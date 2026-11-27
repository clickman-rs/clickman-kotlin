package com.lockvoid.clickman

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The ingest server's answer to a batch: status 0 when none came, and what it said, for the log. */
internal class Response(val status: Int, val retryAfter: Duration?, val detail: String)

/**
 * POSTs gzipped batches to `{endpoint}/v1/batch` (docs/PROTOCOL.md, Endpoint) and
 * uncompressed visitor links to `{endpoint}/v1/identify` (Visitors and links).
 */
internal class Sender(endpoint: String, private val writeKey: String) {
    private val batchUrl = url(endpoint, "v1/batch")
    private val identifyUrl = url(endpoint, "v1/identify")

    init {
        require(writeKey.isNotEmpty() && writeKey.all { it.code in 33..126 }) {
            "a public source write key without whitespace is required"
        }
    }

    /** POSTs a gzipped batch. */
    fun post(body: ByteArray): Response = post(batchUrl, body, gzipped = true)

    /** POSTs the JSON of one link, uncompressed. */
    fun postLink(body: ByteArray): Response = post(identifyUrl, body, gzipped = false)

    private fun post(url: URL, body: ByteArray, gzipped: Boolean): Response =
        try {
            exchange(url, url.openConnection() as HttpURLConnection, body, gzipped)
        } catch (error: IOException) {
            Response(0, null, "no answer from $url: $error")
        }

    private fun exchange(url: URL, connection: HttpURLConnection, body: ByteArray, gzipped: Boolean): Response =
        try {
            write(connection, body, gzipped)
            read(url, connection)
        } finally {
            connection.disconnect()
        }

    private fun write(connection: HttpURLConnection, body: ByteArray, gzipped: Boolean) {
        connection.requestMethod = "POST"
        connection.instanceFollowRedirects = false
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("Authorization", "Bearer $writeKey")
        connection.setRequestProperty("Content-Type", "application/json")
        if (gzipped) connection.setRequestProperty("Content-Encoding", "gzip")
        connection.doOutput = true
        connection.outputStream.use { it.write(body) }
    }

    private fun read(url: URL, connection: HttpURLConnection): Response {
        val status = connection.responseCode
        val answer = (if (status >= 400) connection.errorStream else connection.inputStream)?.use { it.prefix(DETAIL_BYTES) }
        return Response(status, retryAfter(url, connection.getHeaderField("Retry-After")), "HTTP $status ${answer.orEmpty()}")
    }

    /** `Retry-After` in seconds, the form the ingest server sends; any other form is ignored with a warning. */
    private fun retryAfter(url: URL, header: String?): Duration? {
        if (header == null) return null
        val seconds = header.trim().toLongOrNull()
        if (seconds == null || seconds < 0) {
            logger.warning("ClickMan ignores the Retry-After \"$header\" of $url: it is not a number of seconds")
            return null
        }
        return seconds.seconds
    }

    private fun InputStream.prefix(limit: Int): String {
        val buffer = ByteArray(limit)
        var size = 0
        while (size < limit) {
            val count = read(buffer, size, limit - size)
            if (count < 0) break
            size += count
        }
        return buffer.decodeToString(0, size)
    }

    private companion object {
        const val TIMEOUT_MS = 30_000
        const val DETAIL_BYTES = 512

        fun url(endpoint: String, path: String): URL {
            val base = URI.create(endpoint)
            require(
                base.scheme?.lowercase() in setOf("http", "https") &&
                    !base.host.isNullOrEmpty() && base.rawUserInfo == null &&
                    base.rawQuery == null && base.rawFragment == null,
            ) { "the endpoint must be an absolute HTTP(S) URL without credentials, query or fragment" }
            return URI.create("${base.toASCIIString().trimEnd('/')}/$path").toURL()
        }
    }
}
