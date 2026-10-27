package io.paimon.fx991.engine

// ---------------------------------------------------------------------------
// Batch F: Local offline OCR · Pure logic layer (no Android / ML Kit dependency -> can run regression directly on JVM)
//
// Responsibilities: Clean multi-line / multi-block raw text recognized by ML Kit into expressions directly parseable by this engine:
//   Merge lines -> OCR-specific symbol correction (√ variants / full-width equals / decimal dot misrecognition) ->
//   Reuse PhotoSolve.normalizeExpr (full-width -> half-width, ×÷ normalization, ** -> ^, remove whitespace) ->
//   Confusable character correction (O/o -> 0, l/I/| -> 1, only in numerical contexts to prevent over-correction).
//
// Principle: Prefer under-correction over mis-correction — all correction rules require an "adjacent number or decimal point" context;
// standalone letters (which could be variables / function names) are left untouched. The UI lets users review before confirming evaluation,
// so under-corrections can be fixed manually, whereas mis-corrections alter the original meaning.
// ---------------------------------------------------------------------------

object OcrText {

    // -----------------------------------------------------------------------
    // Multi-line / multi-block merging
    // -----------------------------------------------------------------------

    /** Merge multiple lines provided by ML Kit: trim whitespace, drop blank lines, concatenate in order */
    fun mergeLines(lines: List<String>): String =
        lines.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("")

    // -----------------------------------------------------------------------
    // Main entry point
    // -----------------------------------------------------------------------

    /** Clean raw text (possibly multi-line) into an expression; returns "" for blank input */
    fun clean(raw: String): String {
        if (raw.isBlank()) return ""
        var s = mergeLines(raw.split('\n', '\r'))
        s = preNormalize(s)
        s = PhotoSolve.normalizeExpr(s)
        s = correctConfusables(s)
        return s
    }

    /** Clean expressions from a list of ML Kit lines */
    fun cleanLines(lines: List<String>): String {
        if (lines.isEmpty()) return ""
        var s = mergeLines(lines)
        if (s.isBlank()) return ""
        s = preNormalize(s)
        s = PhotoSolve.normalizeExpr(s)
        s = correctConfusables(s)
        return s
    }

    // -----------------------------------------------------------------------
    // OCR-specific symbol correction (run before normalizeExpr to handle symbols normalizeExpr does not recognize)
    // -----------------------------------------------------------------------

    fun preNormalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when (ch) {
                '✓', '✔', '∨', '⎷' -> sb.append('√')     // Common square root misrecognitions
                '＝' -> sb.append('=')                      // Full-width equals sign
                '﹣', '−' -> sb.append('-')                 // Small hyphen-minus / mathematical minus sign
                '〇' -> sb.append('0')                      // Chinese ideographic numeral zero
                else -> sb.append(ch)
            }
        }
        return fixDecimalDots(sb.toString())
    }

    /**
     * Decimal point misrecognition: change `。` or `·` between two digits to `.`.
     * Any `。` not between digits is an invalid character and directly discarded (keeping it would cause syntax errors).
     * Intentionally leaves `,` untouched — comma is the delimiter for two-argument functions (logb(2,8)), changing it to a decimal point would alter original meaning.
     */
    fun fixDecimalDots(s: String): String {
        val ch = s.toCharArray()
        for (i in ch.indices) {
            if (ch[i] == '。' || ch[i] == '·') {
                val left = i > 0 && ch[i - 1].isDigit()
                val right = i < ch.size - 1 && ch[i + 1].isDigit()
                if (left && right) ch[i] = '.'
            }
        }
        return String(ch).filter { it != '。' }
    }

    // -----------------------------------------------------------------------
    // Confusable character correction (run after normalizeExpr: full-width converted to half-width, whitespace removed)
    // -----------------------------------------------------------------------

    private fun isNumCtx(c: Char): Boolean = c.isDigit() || c == '.'

    /**
     * O/o->0, l/I/|->1: **Correct only when adjacent characters (at least one side) are digits or a decimal point**.
     * Standalone O / o / l / I are preserved as-is (could be variables, function name parts, or genuine letters).
     */
    fun correctConfusables(s: String): String {
        val ch = s.toCharArray()
        for (i in ch.indices) {
            val left = i > 0 && isNumCtx(ch[i - 1])
            val right = i < ch.size - 1 && isNumCtx(ch[i + 1])
            if (!left && !right) continue
            when (ch[i]) {
                'O', 'o' -> ch[i] = '0'
                'l', 'I', '|' -> ch[i] = '1'
            }
        }
        return String(ch)
    }
}
