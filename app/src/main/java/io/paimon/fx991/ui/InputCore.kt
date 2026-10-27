package io.paimon.fx991.ui

// ---------------------------------------------------------------------------
// Input Experience Refactor - Batch 1: Structure-aware editing core (pure Kotlin, JVM testable)
//
//   Pain point of legacy implementation: Backspace only deleted a single character,
//   so deleting 3 from 3÷ left a bare ÷, and deleting a character in sin() left
//   a sin( remnant, requiring the user to press backspace several times to clean up.
//
//   Here we do not rewrite expressions or change grammar — we only inspect the
//   bracket/operator pairings on the original linear text to determine how much a
//   single deletion should consume, and clean up dangling structures:
//   never leaving empty ÷ / ^ / √ after editing.
//   This leaves the rendering layer (buildNat) and evaluation layer (CalcEngine) completely untouched.
// ---------------------------------------------------------------------------

object InputCore {

    private const val SQRT = '\u221A'
    private const val DIV = '\u00F7'
    private const val MUL = '\u00D7'
    private const val MINUS = '\u2212'

    /** Operators in expression: used to check if "one side is empty" */
    private fun isOp(c: Char) =
        c == '+' || c == MINUS || c == '-' || c == MUL || c == DIV || c == '^' || c == '(' || c == ',' || c == '='

    private fun isDelim(c: Char) = c == '(' || c == ')' || c == ',' || c == '=' || c.isWhitespace()

    /** Bracket pair table: open -> close, close -> open; unmatched remains -1 */
    fun pairs(t: String): IntArray {
        val m = IntArray(t.length) { -1 }
        val st = ArrayDeque<Int>()
        for (i in t.indices) {
            when (t[i]) {
                '(' -> st.addLast(i)
                ')' -> if (st.isNotEmpty()) {
                    val o = st.removeLast()
                    m[o] = i
                    m[i] = o
                }
            }
        }
        return m
    }

    /**
     * The innermost "empty bracket group" where the cursor is located. Empty group = no characters between brackets.
     * pos is allowed within the (open, close] range (i.e. cursor is inside the brackets).
     */
    fun emptyGroupAt(t: String, pos: Int): Pair<Int, Int>? {
        val m = pairs(t)
        var best: Pair<Int, Int>? = null
        for (i in t.indices) {
            if (t[i] != '(') continue
            val c = m[i]
            if (c == i + 1 && pos in (i + 1)..c) {
                if (best == null || i > best.first) best = i to c
            }
        }
        return best
    }

    private fun isNameChar(c: Char) =
        c.isLetter() || c == '\u00B2' || c == '\u00B3' || c == '\u207B' || c == '\u00B9' || c == '\''

    /** Start index of function name preceding empty group: sin( -> 0; +sin( -> 1 */
    fun nameStart(t: String, open: Int): Int {
        var s = open
        while (s > 0 && isNameChar(t[s - 1])) s--
        return s
    }

    /** Expands to start of the "entire structure": function name + optional √ prefix */
    private fun structStart(t: String, open: Int): Int {
        var s = nameStart(t, open)
        // √ can be part of the function name (e.g. √( ), or standalone: √ in √(x) is at open-1
        if (s > 0 && t[s - 1] == SQRT) s--
        return s
    }

    /** Whether right side (after pos) up to next operator is empty */
    private fun rightEmpty(t: String, pos: Int): Boolean {
        if (pos >= t.length) return true
        val c = t[pos]
        return isOp(c) || c == ')'
    }

    /** Whether left side (before pos) up to previous operator is empty */
    private fun leftEmpty(t: String, pos: Int): Boolean {
        if (pos <= 0) return true
        val c = t[pos - 1]
        return isOp(c) || c == '('
    }

    /** Backspace: structure-first (delete empty group whole / dangling operator cleanup), then atoms, finally single character */
    fun backspace(text: String, pos: Int): EditResult {
        val p = pos.coerceIn(0, text.length)
        if (p == 0) return EditResult(text, 0)

        // 1) Cursor inside empty bracket group: delete entire invocation together (sin() / √() / () )
        emptyGroupAt(text, p)?.let { (o, c) ->
            val s = structStart(text, o)
            return EditResult(text.removeRange(s, c + 1), s)
        }

        // 1b) Cursor right after empty group's ): delete whole group from outside as well
        if (text[p - 1] == ')') {
            val m = pairs(text)
            val o = m[p - 1]
            if (o >= 0 && o + 1 == p - 1) {
                val s = structStart(text, o)
                return EditResult(text.removeRange(s, p), s)
            }
        }

        // 2) Cursor directly behind ÷ / ^ / √, and other side of structure is empty -> delete structure together
        val prev = text[p - 1]
        if (prev == DIV && (leftEmpty(text, p - 1) || rightEmpty(text, p))) {
            return EditResult(text.removeRange(p - 1, p), p - 1)
        }
        if (prev == '^' && rightEmpty(text, p)) {
            return EditResult(text.removeRange(p - 1, p), p - 1)
        }
        if (prev == SQRT && rightEmpty(text, p)) {
            return EditResult(text.removeRange(p - 1, p), p - 1)
        }

        // 3) Normal deletion: first by atom (sin( / √( / 10^ ...), then by single character
        for (a in CursorModel.ATOMS) {
            if (p >= a.length && text.regionMatches(p - a.length, a, 0, a.length)) {
                return cleanup(text.removeRange(p - a.length, p), p - a.length)
            }
        }
        return cleanup(text.removeRange(p - 1, p), p - 1)
    }

    /** Forward delete (Delete semantics), rules mirrored with backspace */
    fun deleteForward(text: String, pos: Int): EditResult {
        val p = pos.coerceIn(0, text.length)
        if (p >= text.length) return EditResult(text, p)

        emptyGroupAt(text, p)?.let { (o, c) ->
            val s = structStart(text, o)
            return EditResult(text.removeRange(s, c + 1), s)
        }

        // Cursor on ( and group is empty: delete entire invocation together
        if (text[p] == '(') {
            val m = pairs(text)
            val c = m[p]
            if (c == p + 1) {
                val s = structStart(text, p)
                return EditResult(text.removeRange(s, c + 1), s)
            }
        }

        val c0 = text[p]
        if (c0 == DIV && (leftEmpty(text, p) || rightEmpty(text, p + 1))) {
            return EditResult(text.removeRange(p, p + 1), p)
        }
        if (c0 == '^' && rightEmpty(text, p + 1)) return EditResult(text.removeRange(p, p + 1), p)
        if (c0 == SQRT && rightEmpty(text, p + 1)) return EditResult(text.removeRange(p, p + 1), p)

        for (a in CursorModel.ATOMS) {
            if (text.startsWith(a, p)) {
                return cleanup(text.removeRange(p, (p + a.length).coerceAtMost(text.length)), p)
            }
        }
        return cleanup(text.removeRange(p, p + 1), p)
    }

    /**
     * Dangling structure cleanup: After deleting characters, if a ÷ / ^ with both sides empty
     * or a leading/trailing ÷ / ^ is left behind, remove them. Only cleans definitively meaningless
     * structures, leaving slots that the user is currently typing into intact
     * (e.g. freshly pressed a/b resulting in "3÷" with cursor at end — this is a valid input state).
     */
    fun cleanup(t: String, cursor: Int): EditResult {
        var s = t
        var c = cursor.coerceIn(0, s.length)
        // Trailing ÷ / ^ / √: remove (no further input possible on right side in this state)
        while (s.isNotEmpty() && (s.last() == DIV || s.last() == '^' || s.last() == '√') && c >= s.length) {
            s = s.dropLast(1)
            c = s.length
        }
        // Leading ÷ / × / ^
        while (s.isNotEmpty() && (s.first() == DIV || s.first() == MUL || s.first() == '^')) {
            s = s.drop(1)
            c = (c - 1).coerceAtLeast(0)
        }
        // Adjacent operators: a÷÷b -> a÷b; a+÷b -> a+b
        var i = 1
        while (i < s.length) {
            if ((s[i] == DIV || s[i] == MUL || s[i] == '^') && isOp(s[i - 1])) {
                val dropIdx = i
                s = s.removeRange(dropIdx, dropIdx + 1)
                if (c > dropIdx) c--
                i = 1
                continue
            }
            i++
        }
        return EditResult(s, c.coerceIn(0, s.length))
    }

    /**
     * Checks if cursor position is inside a "structure slot" (fraction numerator/denominator,
     * inside radical, inside brackets, superscript exponent).
     * Used by UI to determine whether key presses should follow slot semantics (e.g. keeping × ÷ inside fraction slot).
     */
    fun inSlot(t: String, pos: Int): Boolean {
        val m = pairs(t)
        for (i in t.indices) {
            if (t[i] != '(') continue
            val c = m[i]
            if (c > i && pos in (i + 1)..c) return true
        }
        return false
    }
}
