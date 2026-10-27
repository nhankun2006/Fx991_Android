package io.paimon.fx991.engine

import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

// ---------------------------------------------------------------------------
// Batch K5: Automatic update check
// Data source: GitHub Releases public API (public repo read-only, no token needed).
// Note: Unauthenticated requests are rate-limited to 60 requests/hour/IP -- so auto-check has a 12-hour cache window,
// and 403 clearly indicates "Too many requests".
// Zero third-party dependencies: HttpURLConnection + reuses PhotoSolve handwritten minimal JSON parser.
// This file uses only java.net / java.io, testable on pure JVM (see tools/UpdateCheckTest.java).
// Network calls strictly prohibited on main thread (caller is responsible for Dispatchers.IO).
// ---------------------------------------------------------------------------

/** Update check failure (messages are user-friendly, without stack traces) */
open class UpdateException(message: String) : Exception(message)

/** Server responded with non-2xx (rate limit / no release, etc.) -- reached server, counted towards cache window */
class UpdateHttpException(val code: Int) : UpdateException(UpdateCheck.httpErrorText(code))

/** Network layer failure (offline / DNS / timeout) -- not counted towards cache window, will retry on next launch */
class UpdateNetException(message: String) : UpdateException(message)

/** Server returned 2xx but content cannot be parsed -- reached server, counted towards cache window */
class UpdateParseException(message: String) : UpdateException(message)

/** Key information of a Release */
data class ReleaseInfo(
    val tag: String, // Raw tag, e.g. "v1.10.0"
    val version: String, // Digit string with 'v' prefix and '-' suffix removed, e.g. "1.10.0"
    val pageUrl: String, // Release webpage (html_url)
    val apkUrl: String, // Direct link to first .apk asset (empty string if none)
    val title: String, // Release title (can be empty string)
    val notes: String = "", // Release notes body (raw markdown, can be empty string)
    val apkSize: Long = 0L, // APK byte size (0 when no asset)
)

object UpdateCheck {

    const val API_URL = "https://api.github.com/repos/xiaozishan/fx991-android/releases/latest"

    /** Minimum interval for automatic checks: 12 hours */
    const val AUTO_WINDOW_MS: Long = 12L * 60 * 60 * 1000

    // -----------------------------------------------------------------------
    // Version comparison (numeric segments, never string comparison; 'v' prefix and '-' suffix excluded from comparison)
    // -----------------------------------------------------------------------

    /** Extract numeric core segments of version: "v1.9.0-keymap" → [1, 9, 0]. Throws IllegalArgumentException on invalid segments. */
    private fun versionCore(v: String): IntArray {
        var s = v.trim()
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1)
        val dash = s.indexOf('-')
        if (dash >= 0) s = s.substring(0, dash)
        if (s.isEmpty()) throw IllegalArgumentException("Empty version string: '$v'")
        return s.split('.').map { seg ->
            if (seg.isEmpty() || seg.any { !it.isDigit() }) {
                throw IllegalArgumentException("Invalid version segment: '$v'")
            }
            seg.toIntOrNull() ?: throw IllegalArgumentException("Version segment too large: '$v'")
        }.toIntArray()
    }

    /** Numeric segment comparison: >0 means a is newer, <0 means b is newer, 0 means equal. Different lengths padded with 0 (1.9 == 1.9.0). */
    fun compareVersions(a: String, b: String): Int {
        val x = versionCore(a)
        val y = versionCore(b)
        val n = maxOf(x.size, y.size)
        for (i in 0 until n) {
            val p = if (i < x.size) x[i] else 0
            val q = if (i < y.size) y[i] else 0
            if (p != q) return if (p > q) 1 else -1
        }
        return 0
    }

    /** Whether remote tag is newer than local version. Returns false (silent, non-intrusive) if either side cannot be parsed. */
    fun isNewer(remoteTag: String, localVersion: String): Boolean = try {
        compareVersions(remoteTag, localVersion) > 0
    } catch (_: IllegalArgumentException) {
        false
    }

    // -----------------------------------------------------------------------
    // Cache time window
    // -----------------------------------------------------------------------

    /** Whether the window since last check has elapsed (due to send request). Never checked before (lastMs=0) is always due. */
    fun dueForAutoCheck(lastCheckMs: Long, nowMs: Long): Boolean =
        lastCheckMs <= 0L || nowMs - lastCheckMs >= AUTO_WINDOW_MS

    // -----------------------------------------------------------------------
    // Release JSON parsing (reuses PhotoSolve handwritten minimal JSON parser)
    // -----------------------------------------------------------------------

    fun parseRelease(json: String): ReleaseInfo {
        val root = try {
            PhotoSolve.parseJson(json)
        } catch (e: PhotoSolve.JsonError) {
            throw UpdateParseException("Response is not valid update information (${e.message})")
        }
        if (root !is Map<*, *>) throw UpdateParseException("Response is not valid update information")
        val tag = (root["tag_name"] as? String)?.trim()
        if (tag.isNullOrEmpty()) throw UpdateParseException("Version number (tag_name) not found in response")
        val page = (root["html_url"] as? String)?.trim().orEmpty()
        if (page.isEmpty()) throw UpdateParseException("Download page (html_url) not found in response")
        val title = (root["name"] as? String)?.trim().orEmpty()
        val notes = (root["body"] as? String)?.trim().orEmpty()
        var apk = ""
        var apkSize = 0L
        val assets = root["assets"]
        if (assets is List<*>) {
            for (a in assets) {
                if (a !is Map<*, *>) continue
                val name = (a["name"] as? String) ?: continue
                if (name.endsWith(".apk")) {
                    apk = (a["browser_download_url"] as? String)?.trim().orEmpty()
                    apkSize = (a["size"] as? Number)?.toLong() ?: 0L
                    if (apk.isNotEmpty()) break
                }
            }
        }
        // tag must parse into a comparable version number, otherwise this Release cannot be used
        try {
            versionCore(tag)
        } catch (_: IllegalArgumentException) {
            throw UpdateParseException("Unrecognized version format: $tag")
        }
        var version = tag
        if (version.startsWith("v") || version.startsWith("V")) version = version.substring(1)
        val dash = version.indexOf('-')
        if (dash >= 0) version = version.substring(0, dash)
        return ReleaseInfo(tag = tag, version = version, pageUrl = page, apkUrl = apk, title = title, notes = notes, apkSize = apkSize)
    }

    // -----------------------------------------------------------------------
    // Error messages (user-friendly, without stack traces)
    // -----------------------------------------------------------------------

    fun httpErrorText(code: Int): String = when (code) {
        403 -> "Too many requests (GitHub rate limit: 60/hr unauthenticated), please try again later"
        404 -> "No release published in repository yet"
        else -> "Update server returned HTTP $code, please try again later"
    }

    /** Network exception -> user-friendly text */
    fun netErrorText(e: Throwable): String = when (e) {
        is SocketTimeoutException -> "Connection timed out: network is slow or unavailable, please try again later"
        is UnknownHostException -> "No internet connection or DNS resolution failed: please check your network connection"
        is ConnectException -> "Connection failed: unable to reach update server"
        is javax.net.ssl.SSLException -> "Secure connection failed: please check network environment"
        else -> "Network request failed: ${e.message ?: e.javaClass.simpleName}"
    }

    // -----------------------------------------------------------------------
    // Actual request (caller is responsible for running on worker thread)
    // -----------------------------------------------------------------------

    /** GET latest Release. Throws [UpdateHttpException] / [UpdateNetException] / [UpdateParseException] on failure. */
    fun fetchLatest(timeoutSec: Int = 15): ReleaseInfo {
        val conn = try {
            URL(API_URL).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw UpdateNetException("Unable to establish connection: ${e.message}")
        }
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = timeoutSec * 1000
            conn.readTimeout = timeoutSec * 1000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            // GitHub API returns 403 without User-Agent
            conn.setRequestProperty("User-Agent", "fx991-android-update-check")
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            if (code !in 200..299) throw UpdateHttpException(code)
            return parseRelease(body)
        } catch (e: UpdateException) {
            throw e
        } catch (e: java.io.IOException) {
            throw UpdateNetException(netErrorText(e))
        } finally {
            conn.disconnect()
        }
    }
}
