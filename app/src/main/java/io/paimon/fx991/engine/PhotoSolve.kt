package io.paimon.fx991.engine

import java.util.Base64

// ---------------------------------------------------------------------------
// Photo solving · Pure logic layer (no Android / Compose dependency -> can run regression directly on JVM)
//
// Note: JSON construction and parsing could have used Android built-in org.json, but org.json is not available
// in pure JVM regression environments (plain javac/java with only kotlin-stdlib). To fulfill the commitment that "multi-segment JSON
// response parsing is testable on JVM", a minimal JSON parser (string escaping + recursive descent parser) is handwritten here.
// Its functional scope equals this feature's actual usage of org.json. Still zero third-party dependencies.
// ---------------------------------------------------------------------------

/** Solver API configuration (freely entered by user, stored in plain text in SharedPreferences — disclosed in UI) */
data class ApiConfig(
    val baseUrl: String = DEFAULT_BASE,
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
    val timeoutSec: Int = DEFAULT_TIMEOUT_SEC,
) {
    /** Whether minimal conditions to send requests are met (missing any counts as "unconfigured") */
    fun isComplete(): Boolean =
        baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

    /** Validate; returns null if valid, otherwise user-friendly error string */
    fun validate(): String? {
        val b = baseUrl.trim()
        if (b.isEmpty()) return "Base URL cannot be empty"
        if (!b.startsWith("https://") && !b.startsWith("http://"))
            return "Base URL must start with https:// or http://"
        if (b.contains(' ') || b.contains('\n') || b.contains('\t'))
            return "Base URL cannot contain whitespace"
        if (model.isBlank()) return "Model name cannot be empty"
        if (timeoutSec !in MIN_TIMEOUT_SEC..MAX_TIMEOUT_SEC)
            return "Timeout must be between $MIN_TIMEOUT_SEC–$MAX_TIMEOUT_SEC seconds"
        return null
    }

    /** Clamp timeout to valid range (UI input is lenient, clamped before storage) */
    fun clamped(): ApiConfig = copy(timeoutSec = timeoutSec.coerceIn(MIN_TIMEOUT_SEC, MAX_TIMEOUT_SEC))

    companion object {
        const val DEFAULT_BASE = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-4o-mini"
        const val DEFAULT_TIMEOUT_SEC = 45
        const val MIN_TIMEOUT_SEC = 5
        const val MAX_TIMEOUT_SEC = 180
    }
}

/** Presets: one-click fill for Base URL + recommended vision model name (Key is never prefilled, always entered by user) */
data class ApiPreset(val name: String, val baseUrl: String, val model: String, val note: String)

val API_PRESETS: List<ApiPreset> = listOf(
    ApiPreset("DeepSeek", "https://api.deepseek.com/v1", "deepseek-v4-flash-vision-exp",
        "Models with vision suffix are vision models (deepseek-v4-flash-vision-exp)"),
    ApiPreset("Zhipu GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4v-flash",
        "glm-4v series supports image input"),
    ApiPreset("Kimi (Moonshot)", "https://api.moonshot.cn/v1", "moonshot-v1-8k-vision-preview",
        "Models with vision suffix support image input"),
    ApiPreset("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini",
        "gpt-4o series supports image input"),
)

object PhotoSolve {

    /** Image longest edge compression target / JPEG quality (consistent with task conventions) */
    const val MAX_EDGE = 1600
    const val JPEG_QUALITY = 85

    // -----------------------------------------------------------------------
    // URL concatenation
    // -----------------------------------------------------------------------

    /** baseUrl -> complete chat/completions URL (tolerates trailing /, tolerates user already specifying endpoint) */
    fun chatUrl(baseUrl: String): String {
        var b = baseUrl.trim().trimEnd('/')
        if (b.endsWith("/chat/completions")) return b
        return "$b/chat/completions"
    }

    // -----------------------------------------------------------------------
    // Prompt (instructs model to return fixed tags for easy parsing and backfilling)
    // -----------------------------------------------------------------------

    const val PROMPT_TEXT: String =
        "Please recognize the math formula in this image and calculate the result, strictly following these output conventions:\n" +
            "1. Transcribe the formula in the image into a single-line plain text expression. Rules: use + - * / for basic arithmetic, ^ for power, " +
            "√(..) for square root, π for pi, sin cos tan log ln for trig and logs, x for variable. " +
            "Do not omit operators, do not include units, do not include \$ or LaTeX notations.\n" +
            "2. Calculate the result of this expression.\n" +
            "3. Output strictly in the following format, without any extra text:\n" +
            "<EXPR>single-line expression</EXPR>\n" +
            "<RESULT>calculation result</RESULT>\n" +
            "<EXPLAIN>one or two sentences explanation</EXPLAIN>\n" +
            "4. If there is no calculable math formula in the image, output only: <EXPR>NONE</EXPR>"

    // -----------------------------------------------------------------------
    // Request body construction (handwritten JSON escaping)
    // -----------------------------------------------------------------------

    fun buildChatBody(cfg: ApiConfig, imageDataUri: String, maxTokens: Int = 800): String {
        val sb = StringBuilder()
        sb.append("{\"model\":").append(jsonStr(cfg.model.trim()))
        sb.append(",\"messages\":[{\"role\":\"user\",\"content\":[")
        sb.append("{\"type\":\"text\",\"text\":").append(jsonStr(PROMPT_TEXT)).append("},")
        sb.append("{\"type\":\"image_url\",\"image_url\":{\"url\":")
            .append(jsonStr(imageDataUri)).append("}}")
        sb.append("]}],\"max_tokens\":").append(maxTokens).append("}")
        return sb.toString()
    }

    /** Minimal request for connection testing (a 1x1 white dot JPEG to minimize token usage) */
    fun buildTestBody(cfg: ApiConfig): String {
        val sb = StringBuilder()
        sb.append("{\"model\":").append(jsonStr(cfg.model.trim()))
        sb.append(",\"messages\":[{\"role\":\"user\",\"content\":")
            .append(jsonStr("Reply with the two letters ok."))
        sb.append("}],\"max_tokens\":4}")
        return sb.toString()
    }

    /** JSON string escaping (RFC 8259) */
    fun jsonStr(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    // -----------------------------------------------------------------------
    // Response parsing: minimal JSON recursive descent parser (Map/List/String/Double/Boolean/null)
    // -----------------------------------------------------------------------

    class JsonError(message: String) : Exception(message)

    private class JsonReader(val src: String) {
        var i = 0

        fun parse(): Any? {
            skipWs()
            val v = value()
            skipWs()
            return v
        }

        private fun skipWs() {
            while (i < src.length && src[i].isWhitespace()) i++
        }

        private fun value(): Any? {
            if (i >= src.length) throw JsonError("Incomplete JSON")
            return when (val c = src[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> if (c == '-' || c.isDigit()) num() else throw JsonError("Unexpected character in JSON: '$c'")
            }
        }

        private fun expect(w: String) {
            if (src.regionMatches(i, w, 0, w.length)) i += w.length
            else throw JsonError("Invalid JSON literal")
        }

        private fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++ // {
            skipWs()
            if (i < src.length && src[i] == '}') { i++; return m }
            while (true) {
                skipWs()
                val k = str()
                skipWs()
                if (i >= src.length || src[i] != ':') throw JsonError("Expected ':' in object")
                i++
                skipWs()
                m[k] = value()
                skipWs()
                if (i >= src.length) throw JsonError("Unclosed object")
                when (src[i]) {
                    ',' -> i++
                    '}' -> { i++; return m }
                    else -> throw JsonError("Unexpected character in object")
                }
            }
        }

        private fun arr(): List<Any?> {
            val list = ArrayList<Any?>()
            i++ // [
            skipWs()
            if (i < src.length && src[i] == ']') { i++; return list }
            while (true) {
                skipWs()
                list.add(value())
                skipWs()
                if (i >= src.length) throw JsonError("Unclosed array")
                when (src[i]) {
                    ',' -> i++
                    ']' -> { i++; return list }
                    else -> throw JsonError("Unexpected character in array")
                }
            }
        }

        private fun str(): String {
            if (i >= src.length || src[i] != '"') throw JsonError("Expected string")
            i++
            val sb = StringBuilder()
            while (i < src.length) {
                val c = src[i]
                when (c) {
                    '"' -> { i++; return sb.toString() }
                    '\\' -> {
                        i++
                        if (i >= src.length) throw JsonError("Incomplete escape sequence")
                        when (val e = src[i]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (i + 4 >= src.length) throw JsonError("Incomplete \\u escape sequence")
                                sb.append(src.substring(i + 1, i + 5).toInt(16).toChar())
                                i += 4
                            }
                            else -> throw JsonError("Unknown escape sequence \\$e")
                        }
                        i++
                    }
                    else -> { sb.append(c); i++ }
                }
            }
            throw JsonError("Unclosed string")
        }

        private fun num(): Double {
            val start = i
            if (i < src.length && src[i] == '-') i++
            while (i < src.length && (src[i].isDigit() || src[i] in ".eE+-")) i++
            return src.substring(start, i).toDoubleOrNull()
                ?: throw JsonError("Invalid number")
        }
    }

    /** Parse JSON text into Kotlin structure (for testing and internal use) */
    fun parseJson(text: String): Any? = JsonReader(text).parse()

    /**
     * Extract choices[0].message.content from chat/completions response.
     * Throws [JsonError] (user-friendly message) if response contains error field / structure mismatch / not JSON.
     */
    fun extractContent(body: String): String {
        val root = try {
            parseJson(body)
        } catch (e: JsonError) {
            throw JsonError("Server returned invalid JSON (${e.message}): ${snippet(body)}")
        }
        if (root !is Map<*, *>) throw JsonError("Response is not a JSON object: ${snippet(body)}")
        val err = root["error"]
        if (err is Map<*, *>) {
            val msg = (err["message"] as? String) ?: err.toString()
            throw JsonError("API returned error: $msg")
        }
        val choices = root["choices"] as? List<*>
            ?: throw JsonError("Response missing choices field: ${snippet(body)}")
        val first = choices.firstOrNull() as? Map<*, *>
            ?: throw JsonError("choices is empty, model provided no response")
        val message = first["message"] as? Map<*, *>
            ?: throw JsonError("Response missing message field: ${snippet(body)}")
        val content = message["content"]
        // Some implementations return content as segment array: [{type:"text", text:"..."}]
        if (content is List<*>) {
            val joined = content.mapNotNull { seg ->
                (seg as? Map<*, *>)?.let { it["text"] as? String }
            }.joinToString("\n")
            if (joined.isNotBlank()) return joined
        }
        return content as? String
            ?: throw JsonError("message.content is not text: ${snippet(body)}")
    }

    /** Truncate a snippet of raw response for error display (whitespace stripped, max 120 chars) */
    fun snippet(body: String, max: Int = 120): String {
        val t = body.replace(Regex("\\s+"), " ").trim()
        return if (t.length <= max) t else t.substring(0, max) + "…"
    }

    // -----------------------------------------------------------------------
    // Tag extraction and reply parsing
    // -----------------------------------------------------------------------

    /** Extract content between <TAG>…</TAG> (trimmed); returns null if tag not found */
    fun extractTag(text: String, tag: String): String? {
        val open = "<$tag>"
        val close = "</$tag>"
        val a = text.indexOf(open, ignoreCase = true)
        if (a < 0) return null
        val b = text.indexOf(close, startIndex = a + open.length, ignoreCase = true)
        val inner = if (b < 0) text.substring(a + open.length) else text.substring(a + open.length, b)
        return inner.trim()
    }

    /** Model reply parsing result */
    sealed class Reply {
        /** Success: expression + model-provided result + explanation */
        data class Solved(val expr: String, val result: String, val explain: String) : Reply()

        /** Model indicated no math formula in image */
        data object NoFormula : Reply()

        /** Model reply did not follow expected format */
        data class BadFormat(val detail: String) : Reply()
    }

    fun parseReply(content: String): Reply {
        val rawExpr = extractTag(content, "EXPR")
            ?: return Reply.BadFormat("Model reply missing <EXPR> tag. Raw snippet: ${snippet(content)}")
        if (rawExpr.equals("NONE", ignoreCase = true) || rawExpr.isBlank()) return Reply.NoFormula
        val expr = normalizeExpr(rawExpr)
        if (expr.isBlank()) return Reply.BadFormat("Expression tag is empty. Raw snippet: ${snippet(content)}")
        val result = extractTag(content, "RESULT") ?: ""
        val explain = extractTag(content, "EXPLAIN") ?: ""
        return Reply.Solved(expr, result, explain)
    }

    // -----------------------------------------------------------------------
    // Expression normalization: convert model's ASCII / full-width format into engine parseable form
    // -----------------------------------------------------------------------

    fun normalizeExpr(raw: String): String {
        var s = raw.trim()
        // Remove markdown code fences / backticks / inline $
        s = s.replace("`", "").replace("$", "")
        // Full-width -> half-width
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when (ch) {
                '＋' -> sb.append('+')
                '－', '–', '—' -> sb.append('-')   // Full-width minus / em-en dash
                '＊', '✕', '✖' -> sb.append('×')   // Full-width asterisk / multiplication variants
                '／', '∕' -> sb.append('÷')
                '（', '【', '[' -> sb.append('(')
                '）', '】', ']' -> sb.append(')')
                '＾' -> sb.append('^')
                '，' -> sb.append(',')
                '．' -> sb.append('.')
                '：' -> sb.append(':')
                '％' -> sb.append('%')
                '　' -> Unit                        // Discard full-width space
                else -> {
                    // Full-width digits 0-9
                    if (ch in '０'..'９') sb.append('0' + (ch - '０')) else sb.append(ch)
                }
            }
        }
        s = sb.toString()
        // Alternative power notation
        s = s.replace("**", "^")
        // ASCII multiplication and division -> engine display symbols × ÷ (engine recognizes both, normalize to display form)
        s = s.replace('*', '×').replace('/', '÷')
        // Remove all whitespace (engine lexer skips whitespace, but removing is safer)
        s = s.filter { !it.isWhitespace() }
        return s
    }

    // -----------------------------------------------------------------------
    // Image compression dimension calculation (pure math, Android side scales according to this)
    // -----------------------------------------------------------------------

    /** Scale longest edge to within maxEdge (maintaining aspect ratio); returns as-is if already small enough; returns (width, height) */
    fun scaledSize(w: Int, h: Int, maxEdge: Int = MAX_EDGE): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return Pair(maxOf(1, w), maxOf(1, h))
        val longest = maxOf(w, h)
        if (longest <= maxEdge) return Pair(w, h)
        val scale = maxEdge.toDouble() / longest.toDouble()
        val nw = maxOf(1, (w * scale).toInt())
        val nh = maxOf(1, (h * scale).toInt())
        return Pair(nw, nh)
    }

    /** Byte length after base64 encoding (every 3 bytes -> 4 characters, including padding) */
    fun base64Length(rawBytes: Int): Int = if (rawBytes <= 0) 0 else 4 * ((rawBytes + 2) / 3)

    /** JPEG bytes -> data URI (java.util.Base64, built-in API 26+, testable on JVM) */
    fun jpegDataUri(jpeg: ByteArray): String =
        "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg)

    // -----------------------------------------------------------------------
    // Error messages (HTTP status code -> user-friendly text)
    // -----------------------------------------------------------------------

    fun httpErrorText(code: Int, body: String): String = when (code) {
        400 -> "Request rejected (HTTP 400): Request format not accepted, model may not support image input. ${snippet(body)}"
        401, 403 -> "Authentication failed (HTTP $code): API Key is invalid, expired, or lacks permission. Please check Key in Settings."
        404 -> "Endpoint not found (HTTP 404): Base URL mistyped or service lacks /chat/completions endpoint. Please check Base URL."
        408 -> "Request timeout (HTTP 408): Server took too long to process. Please retry later or increase timeout."
        413 -> "Image too large (HTTP 413): Exceeds service limits even after compression."
        429 -> "Rate limit or quota exceeded (HTTP 429): Please retry later, or check account balance / rate limits."
        in 500..599 -> "Internal server error (HTTP $code): Upstream service error, please retry later. ${snippet(body, 80)}"
        else -> "Request failed (HTTP $code): ${snippet(body)}"
    }
}
