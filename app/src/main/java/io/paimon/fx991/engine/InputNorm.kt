package io.paimon.fx991.engine

/**
 * Input normalization: replaces lookalike characters pasted from elsewhere or passed in via ProcessText
 * with characters recognized by this engine.
 *
 * Corresponds to real reproduction cases (on 2026-10-21 the real device reported "Syntax ERROR"):
 *   - Hyphens/minus signs: – ‑ ‒ — ― － instead of − / -
 *   - Multiplication signs: ✕ ✖ ＊ instead of × / *
 *   - Fraction slashes: ⁄ ∕ ／ instead of /
 *   - Full-width parentheses （） and full-width numbers
 *   - Embedded zero-width spaces / soft hyphens / various special spaces pasted in
 *
 * Principle: only perform "equivalent replacement", never touch characters with potential semantic meaning
 * (· is dot product, ′″ are DMS, letter variables are never touched), avoiding unintended meaning changes. Pure Kotlin, JVM testable.
 */
object InputNorm {

    /** Normalizes raw input; returns "" for empty input */
    fun normalize(raw: String): String {
        if (raw.isEmpty()) return ""
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            when (ch) {
                // Various hyphens/minus signs → ASCII '-'
                '\u2010', '\u2011', '\u2012', '\u2013', '\u2014', '\u2015',
                '\u2212', '\uFF0D', '\u00AD', '\uFE63', '\u2E3A', '\u2E3B' -> sb.append('-')

                // Various division signs / fraction slashes → '/'
                '\u2044', '\u2215', '\uFF0F', '\u29F8' -> sb.append('/')

                // Various multiplication signs → '*'
                '\u2715', '\u2716', '\uFF0A', '\u22C6', '\u2217' -> sb.append('*')

                // Full-width punctuation / parentheses → half-width
                '\uFF08' -> sb.append('(')
                '\uFF09' -> sb.append(')')
                '\uFF0B' -> sb.append('+')
                '\uFF1D' -> sb.append('=')
                '\uFF0C' -> sb.append(',')
                '\uFF1A' -> sb.append(':')
                '\uFF05' -> sb.append('%')

                // Zero-width / direction control characters: discarded directly
                '\u200B', '\u200C', '\u200D', '\u2060', '\uFEFF', '\u00A0' -> Unit

                // Various special spaces → normal space (\u00A0 already discarded; handle the rest here)
                '\u2007', '\u2009', '\u202F', '\u3000', '\u2002', '\u2003' -> sb.append(' ')

                // Full-width digits → half-width
                in '\uFF10'..'\uFF19' -> sb.append('0' + (ch - '\uFF10'))

                else -> sb.append(ch)
            }
        }
        return sb.toString().trim()
    }

    /**
     * Normalizes and performs in-place parentheses auto-closing: only appends missing closing parentheses, never deletes.
     * Excess right parentheses are preserved (engine will report Syntax ERROR, which is safer than silently modifying the formula).
     */
    fun normalizeAndAutoClose(raw: String): String {
        val s = normalize(raw)
        var open = 0
        for (ch in s) when (ch) {
            '(' -> open++
            ')' -> if (open > 0) open--
        }
        return s + ")".repeat(open)
    }
}
