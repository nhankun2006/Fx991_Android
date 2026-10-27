package io.paimon.fx991.ui

// ---------------------------------------------------------------------------
// Caret Model v2: Structural hierarchy (Caret = offset + exit level)
//
//   Problem: For fractions/superscripts/radicals, the "content end" and
//   "structural right boundary" share the same offset in the string.
//   The legacy model only had an Int offset, so pressing -> at the end of 9÷6
//   could only wrap directly to the beginning, skipping the two stages:
//   "right of fraction -> left of fraction -> start of numerator".
//
//   New model: Caret(offset, exit)
//     exit = 0      Innermost layer (end of denominator / end of exponent)
//     exit = 1      Stepped out one level (right of the whole fraction)
//     exit = n      Stepped out n levels
//
//   Moving right: inner end -> step out layer by layer -> wrap to outermost at document start -> step in layer by layer
//   Moving left: mirrored.
//   Structural spans are derived directly from buildNat (same parser as renderer), without a separate grammar.
// ---------------------------------------------------------------------------

data class Caret(val offset: Int, val exit: Int = 0)

object CaretModel {

    private data class Span(val s: Int, val e: Int)

    /** Structures with ambiguous "content boundary == structural boundary": fractions / superscripts / radicals */
    private fun spans(expr: String): List<Span> {
        val out = ArrayList<Span>()
        val root = try {
            buildNat(expr)
        } catch (_: Exception) {
            return out
        }
        collect(root, out)
        return out
    }

    private fun collect(n: Nat, out: MutableList<Span>) {
        when (n) {
            is Nat.Row -> n.items.forEach { collect(it, out) }
            is Nat.Frac -> {
                collect(n.n, out)
                collect(n.d, out)
                push(n, out)
            }
            is Nat.Sup -> {
                collect(n.base, out)
                collect(n.exp, out)
                push(n, out)
            }
            is Nat.Sqrt -> {
                collect(n.a, out)
                push(n, out)
            }
            else -> Unit
        }
    }

    private fun push(n: Nat, out: MutableList<Span>) {
        if (n.srcStart >= 0 && n.srcEnd > n.srcStart) out.add(Span(n.srcStart, n.srcEnd))
    }

    /** Structures that "end" at current offset, innermost first */
    private fun endsAt(sp: List<Span>, o: Int): List<Span> =
        sp.filter { it.e == o }.sortedByDescending { it.s }

    /** Structures that "start" at current offset, innermost first */
    private fun startsAt(sp: List<Span>, o: Int): List<Span> =
        sp.filter { it.s == o }.sortedBy { it.e }

    /** Maximum exit allowed at this offset (= number of enclosing structure levels) */
    fun maxExit(expr: String, offset: Int): Int {
        if (expr.isEmpty()) return 0
        val o = offset.coerceIn(0, expr.length)
        val sp = spans(expr)
        return maxOf(endsAt(sp, o).size, startsAt(sp, o).size)
    }

    /** Normalizes any (offset, exit) into a valid state */
    fun normalize(expr: String, caret: Caret): Caret {
        val o = caret.offset.coerceIn(0, expr.length)
        return Caret(o, caret.exit.coerceIn(0, maxExit(expr, o)))
    }

    /**
     * Move right by one step (visual reading order):
     *   1. If content end is still inside structure -> step out one level first (same offset, exit+1)
     *   2. If already at rightmost end and finished stepping out -> wrap to outermost layer at document start
     *   3. If outside left of structure -> step in one level (exit-1)
     *   4. Regular move right
     */
    fun right(expr: String, caret: Caret): Caret {
        val len = expr.length
        if (len == 0) return Caret(0, 0)
        val o = caret.offset.coerceIn(0, len)
        val e = caret.exit.coerceAtLeast(0)
        val sp = spans(expr)
        val ends = endsAt(sp, o)
        val starts = startsAt(sp, o)

        if (e < ends.size) return Caret(o, e + 1)

        if (o >= len) {
            return Caret(0, startsAt(sp, 0).size)
        }

        if (e > 0 && starts.size >= e) return Caret(o, e - 1)

        return Caret(o + 1, 0)
    }

    /**
     * Move left by one step (mirrored with right):
     *   1. If already at leftmost end -> wrap to outermost layer at document end
     *   2. If outside right of structure -> step in one level
     *   3. Regular move left
     */
    fun left(expr: String, caret: Caret): Caret {
        val len = expr.length
        if (len == 0) return Caret(0, 0)
        val o = caret.offset.coerceIn(0, len)
        val e = caret.exit.coerceAtLeast(0)
        val sp = spans(expr)

        if (o == 0) return Caret(len, endsAt(sp, len).size)
        if (e > 0) return Caret(o, e - 1)
        return Caret(o - 1, 0)
    }

    /** Debug/test: structural path description of the current caret */
    fun describe(expr: String, caret: Caret): String {
        val sp = spans(expr)
        val e = endsAt(sp, caret.offset).getOrNull(caret.exit - 1)
        val s = startsAt(sp, caret.offset).getOrNull(caret.exit - 1)
        return when {
            caret.exit == 0 -> "inner@${caret.offset}"
            e != null -> "after(${e.s}..${e.e})"
            s != null -> "before(${s.s}..${s.e})"
            else -> "exit${caret.exit}@${caret.offset}"
        }
    }
}
