package io.paimon.fx991.engine

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

// ---------------------------------------------------------------------------
// Photo solving · Network layer (HttpURLConnection, JDK built-in; OkHttp / Retrofit not used)
// This file uses only java.net / java.io, and can run on pure JVM (tested with local HttpServer in regression).
// Callers must invoke on a worker thread / coroutine IO dispatcher, strictly prohibited on main thread.
// ---------------------------------------------------------------------------

/** Network / protocol layer failure (with user-friendly message) */
class PhotoApiException(message: String) : Exception(message)

/** HTTP status code non-2xx */
class PhotoHttpException(val code: Int, val body: String) :
    Exception(PhotoSolve.httpErrorText(code, body))

object PhotoNet {

    /**
     * POST chat/completions, returns raw response body.
     * Throws [PhotoHttpException] (with status code) or [PhotoApiException] (user-friendly message) on failure.
     */
    fun postChat(cfg: ApiConfig, bodyJson: String): String {
        cfg.validate()?.let { throw PhotoApiException(it) }
        val url = try {
            URL(PhotoSolve.chatUrl(cfg.baseUrl))
        } catch (_: Exception) {
            throw PhotoApiException("Base URL is not a valid address: ${cfg.baseUrl}")
        }
        val conn = try {
            url.openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw PhotoApiException("Unable to establish connection: ${e.message}")
        }
        val timeoutMs = cfg.timeoutSec * 1000
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Authorization", "Bearer ${cfg.apiKey.trim()}")
            conn.setRequestProperty("Accept", "application/json")
            val payload = bodyJson.toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(payload.size)
            conn.outputStream.use { it.write(payload) }

            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.use { it.readUtf8() } ?: ""
            if (code !in 200..299) throw PhotoHttpException(code, body)
            return body
        } catch (e: PhotoHttpException) {
            throw e
        } catch (e: PhotoApiException) {
            throw e
        } catch (_: SocketTimeoutException) {
            throw PhotoApiException(
                "Connection timed out (${cfg.timeoutSec}s without response): network is slow or server did not respond. " +
                    "You can increase timeout in Settings, or check if Base URL requires a proxy."
            )
        } catch (_: UnknownHostException) {
            throw PhotoApiException(
                "Domain name resolution failed (DNS): please check if Base URL is mistyped and device is online."
            )
        } catch (e: java.net.ConnectException) {
            throw PhotoApiException("Connection refused: target address / port unreachable, please check Base URL.")
        } catch (e: javax.net.ssl.SSLException) {
            throw PhotoApiException("TLS/SSL handshake failed: ${e.message ?: "https connection insecure or intercepted"}")
        } catch (e: java.io.IOException) {
            throw PhotoApiException("Network request failed: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            conn.disconnect()
        }
    }

    /** Send photo solving request: returns model response text (choices[0].message.content) */
    fun solve(cfg: ApiConfig, imageDataUri: String): String {
        val body = postChat(cfg, PhotoSolve.buildChatBody(cfg, imageDataUri))
        return try {
            PhotoSolve.extractContent(body)
        } catch (e: PhotoSolve.JsonError) {
            throw PhotoApiException(e.message ?: "Failed to parse response")
        }
    }

    /** Test connection: send a minimal request; returns user-friendly result (success is also text) */
    fun testConnection(cfg: ApiConfig): String {
        cfg.validate()?.let { return "Configuration incomplete: $it" }
        if (cfg.apiKey.isBlank()) return "API Key not entered yet: please enter your Key before testing."
        return try {
            val body = postChat(cfg, PhotoSolve.buildTestBody(cfg))
            val content = try {
                PhotoSolve.extractContent(body)
            } catch (_: Exception) {
                "(Response structure non-standard, but HTTP 200)"
            }
            "Connection successful: HTTP 200, model ${cfg.model} available. Model replied: ${PhotoSolve.snippet(content, 40)}"
        } catch (e: PhotoHttpException) {
            e.message ?: "HTTP ${e.code}"
        } catch (e: PhotoApiException) {
            e.message ?: "Connection failed"
        }
    }

    private fun java.io.InputStream.readUtf8(): String {
        val buf = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val n = read(chunk)
            if (n < 0) break
            buf.write(chunk, 0, n)
        }
        return buf.toByteArray().toString(Charsets.UTF_8)
    }
}
