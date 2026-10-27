package io.paimon.fx991.ui

// ---------------------------------------------------------------------------
// Batch K3-A: Natural display editor "Visible Caret" — Position model + Caret placement (pure Kotlin, JVM testable)
//   - Caret = offset within expression string (0..length)
//   - Left/right movement / backspace skip by "atoms" (sin(, √(, 10^ etc. as a whole), falling back to char-by-char
//   - Caret follows after insert / backspace / forward delete (EditResult yields both new text and new offset)
//   - buildNatCursor: inserts Nat.Cursor marker into natural display layout tree — reaches into
//     fraction numerator/denominator, inside radicals, super/subscripts, bracket groups, empty slots, allowing renderer to draw blinking caret
//   - natLinear: natural display node tree -> linear text (reverse channel of B4 bidirectional transformation)
// ---------------------------------------------------------------------------

/** Result of a single edit: new text + new caret offset */
data class EditResult(val text: String, val cursor: Int)

object CursorModel {

    /**
     * Atoms treated as a single unit when moving / deleting (longest match first).
     * Consistent with main row backspace semantics: pressing DEL once deletes an entire sin( / √( / 10^.
     */
    val ATOMS: List<String> = listOf(
        "sin\u207B\u00B9(", "cos\u207B\u00B9(", "tan\u207B\u00B9(",
        "asinh(", "acosh(", "atanh(", "sinh(", "cosh(", "tanh(",
        "cbrt(", "abs(", "fourier(", "cint(", "conj(", "res(",
        "sin(", "cos(", "tan(", "logb(", "log(", "ln(",
        "root(", "npr(", "ncr(", "exp(",
        // Batch K4
        "acot(", "cot(", "ceil(", "floor(", "gcd(", "lcm(", "mod(",
        // Batch K3: In-place bracket call templates (longest match first; ranint( precedes int()
        "ranint(", "deriv(", "solve(", "const(", "conv(", "calc(",
        "dms(", "int(", "sum(", "lim(", "pol(", "rec(", "sto(", "si(",
        "\u221A(",
        "sin\u207B\u00B9", "cos\u207B\u00B9", "tan\u207B\u00B9",
        "\u00D710^", "PreAns", "10^", "Ans", "\u207B\u00B9",
    ).sortedByDescending { it.length }

    fun clamp(expr: String, pos: Int): Int = pos.coerceIn(0, expr.length)

    /** Move left by one step (skips entire atom preceding caret) */
    fun moveLeft(expr: String, pos: Int): Int {
        val p = clamp(expr, pos)
        if (p == 0) return 0
        for (a in ATOMS) {
            if (p >= a.length && expr.regionMatches(p - a.length, a, 0, a.length)) return p - a.length
        }
        return p - 1
    }

    /** Move right by one step (skips entire atom at caret) */
    fun moveRight(expr: String, pos: Int): Int {
        val p = clamp(expr, pos)
        if (p >= expr.length) return expr.length
        for (a in ATOMS) {
            if (expr.startsWith(a, p)) return (p + a.length).coerceAtMost(expr.length)
        }
        return p + 1
    }

    // -------------------------------------------------------------------
    // Batch K3-symbolic: Up/Down arrow keys = vertical caret movement inside 2D structures (no longer history)
    //   - Fractions: Denominator ↔ Numerator  - Superscript: ▲ into exponent; ▼ out to baseline after superscript block
    //   - Radicals: Inside/outside (inside √ press ↑ to exit after radical; after radical press ↓ to return into √)
    //   - ∫ template: Integrand ↑ -> Upper limit / ↓ -> Lower limit; Upper limit ↓ -> Lower limit; Lower limit ↑ -> Upper limit
    //   - Relative offset is mapped equidistantly across slots (numerator col 2 ↓ to denominator col 2), clamped to slot end
    //   - When caret is not in any 2D structure slot: ↑ = go to start of expression, ↓ = go to end
    //     (including structural "edges": √ symbol, int( prefix, after template closing brackets —
    //      legacy logic counted these as "inside structure" causing ▲▼ to freeze completely)
    //   - When caret is in a 2D slot but has no destination in that direction (e.g. in numerator and pressing ↑) -> stay in place
    // -------------------------------------------------------------------

    /** Move up by one step (moves upward inside 2D structures) */
    fun moveUp(expr: String, pos: Int): Int = moveVertical(expr, pos, up = true)

    /** Move down by one step (moves downward inside 2D structures) */
    fun moveDown(expr: String, pos: Int): Int = moveVertical(expr, pos, up = false)

    private fun moveVertical(expr: String, pos: Int, up: Boolean): Int {
        val p = clamp(expr, pos)
        if (expr.isEmpty()) return 0
        val root = try {
            buildNat(expr)
        } catch (_: Exception) {
            return if (up) 0 else expr.length     // Fall back to start/end if cannot parse
        }
        verticalTarget(root, p, up)?.let { return it }
        // Inside a 2D structure slot but no destination in that direction -> stay in place;
        // Pure linear expression, or adjacent only to structural edges (√ symbol / int( prefix / after closing bracket) -> start/end
        return if (insideSlot(root, p)) p else if (up) 0 else expr.length
    }

    /** Equidistant mapping between slots: relative column in from slot -> same column in to slot (clamped to end) */
    private fun mapSlot(from: Nat, to: Nat, cur: Int): Int? {
        if (to.srcStart < 0 || to.srcEnd < to.srcStart) return null
        val len = to.srcEnd - to.srcStart
        val rel = if (from.srcStart >= 0) cur - from.srcStart else 0
        return to.srcStart + rel.coerceIn(0, len)
    }

    /**
     * Finds target source offset for vertical movement: deepest first (nested structures move inside first),
     * only moving at current level if inner levels have no valid moves. Returns null if not found.
     */
    private fun verticalTarget(node: Nat, cur: Int, up: Boolean): Int? {
        // 1) Check child structures first (deepest first)
        when (node) {
            is Nat.Row -> for (c in node.items) {
                if (inSpan(c, cur)) verticalTarget(c, cur, up)?.let { return it }
            }
            is Nat.Frac -> {
                if (inSpan(node.n, cur)) verticalTarget(node.n, cur, up)?.let { return it }
                if (inSpan(node.d, cur)) verticalTarget(node.d, cur, up)?.let { return it }
            }
            is Nat.Sqrt -> if (inSpan(node.a, cur)) {
                verticalTarget(node.a, cur, up)?.let { return it }
            }
            is Nat.Sup -> {
                if (inSpan(node.base, cur)) verticalTarget(node.base, cur, up)?.let { return it }
                if (inSpan(node.exp, cur)) verticalTarget(node.exp, cur, up)?.let { return it }
            }
            is Nat.Integ -> {
                if (inSpan(node.body, cur)) verticalTarget(node.body, cur, up)?.let { return it }
                if (inSpan(node.lo, cur)) verticalTarget(node.lo, cur, up)?.let { return it }
                if (inSpan(node.hi, cur)) verticalTarget(node.hi, cur, up)?.let { return it }
            }
            else -> {}
        }
        // 2) Current level mapping
        return when (node) {
            is Nat.Frac -> when {
                !up && inSpan(node.n, cur) -> mapSlot(node.n, node.d, cur)
                up && inSpan(node.d, cur) -> mapSlot(node.d, node.n, cur)
                else -> null
            }
            is Nat.Sup -> when {
                up && inSpan(node.base, cur) -> mapSlot(node.base, node.exp, cur)
                // K3-symbolic fix: ▼ exits exponent to baseline position following entire superscript block,
                // rather than landing inside base (legacy logic landed at base end = before "^",
                // causing subsequent typing of x^2+3 to mistakenly produce x+3^2)
                !up && inSpan(node.exp, cur) -> node.srcEnd.takeIf { it >= 0 }
                else -> null
            }
            is Nat.Sqrt -> when {
                // Inside √ ↑ -> exit after radical; after radical ↓ -> return into √ (end of radical content, before closing paren)
                up && inSpan(node.a, cur) -> if (node.srcEnd >= 0) node.srcEnd else null
                !up && node.srcEnd == cur && inSpan(node, cur) -> innerEnd(node.a).takeIf { it >= 0 }
                else -> null
            }
            is Nat.Integ -> when {
                up && inSpan(node.body, cur) -> mapSlot(node.body, node.hi, cur)
                !up && inSpan(node.body, cur) -> mapSlot(node.body, node.lo, cur)
                up && inSpan(node.lo, cur) -> mapSlot(node.lo, node.hi, cur)
                !up && inSpan(node.hi, cur) -> mapSlot(node.hi, node.lo, cur)
                else -> null
            }
            else -> null
        }
    }

    /** End position of radical content: parenthesized groups must return before closing parenthesis */
    private fun innerEnd(a: Nat): Int =
        if (a.srcStart < 0) -1
        else if (isGroupNode(a)) (a.srcEnd - 1).coerceAtLeast(a.srcStart)
        else a.srcEnd

    /**
     * Whether caret is located in the source range of a **slot** within a 2D structure (fraction/superscript/radical/integral).
     * Only counts slots (numerator/denominator/base/exponent/radical content/integral 3 slots), not edges of container itself
     * (√ symbol, int( prefix, after closing bracket) — those positions have no vertical destination, navigating start/end linearly.
     */
    private fun insideSlot(node: Nat, cur: Int): Boolean = when (node) {
        is Nat.Row -> node.items.any { insideSlot(it, cur) }
        is Nat.Frac -> inSpan(node.n, cur) || inSpan(node.d, cur)
        is Nat.Sqrt -> inSpan(node.a, cur)
        is Nat.Sup -> inSpan(node.base, cur) || inSpan(node.exp, cur)
        is Nat.Integ -> inSpan(node.body, cur) || inSpan(node.lo, cur) || inSpan(node.hi, cur)
        else -> false
    }

    /** Inserts text at caret; new caret lands after inserted text */
    fun insert(expr: String, pos: Int, text: String): EditResult {
        val p = clamp(expr, pos)
        return EditResult(expr.substring(0, p) + text + expr.substring(p), p + text.length)
    }

    /** Backspace: structure-first (delegated to InputCore from v1.13: empty group whole delete / dangling cleanup), then atoms, finally single character */
    fun backspace(expr: String, pos: Int): EditResult = InputCore.backspace(expr, pos)

    /** Forward delete (Delete key semantics), mirrored with backspace rules */
    fun deleteForward(expr: String, pos: Int): EditResult = InputCore.deleteForward(expr, pos)

    /** Legacy implementation (retained for regression comparison, no longer called) */
    fun backspaceLegacy(expr: String, pos: Int): EditResult {
        val p = clamp(expr, pos)
        if (p == 0) return EditResult(expr, 0)
        for (a in ATOMS) {
            if (p >= a.length && expr.regionMatches(p - a.length, a, 0, a.length)) {
                return EditResult(expr.removeRange(p - a.length, p), p - a.length)
            }
        }
        return EditResult(expr.removeRange(p - 1, p), p - 1)
    }

    /** Legacy implementation (retained for regression comparison, no longer called) */
    fun deleteForwardLegacy(expr: String, pos: Int): EditResult {
        val p = clamp(expr, pos)
        if (p >= expr.length) return EditResult(expr, p)
        for (a in ATOMS) {
            if (expr.startsWith(a, p)) {
                return EditResult(expr.removeRange(p, (p + a.length).coerceAtMost(expr.length)), p)
            }
        }
        return EditResult(expr.removeRange(p, p + 1), p)
    }

    /**
     * Operator input (Batch K6): When cursor is in fraction numerator/denominator slot, keep × ÷ inside slot.
     *
     * In linear source, grouping within slots can only be represented by brackets, so we automatically add a pair of brackets around slot content;
     * rendering layer omits this pair of brackets (fraction bar itself groups).
     * + − are not included — pressing + after typing fraction falls outside fraction as before.
     */
    private val SLOT_OPS = setOf("\u00D7", "\u00F7")

    fun insertOperator(expr: String, pos: Int, op: String): EditResult {
        val p = clamp(expr, pos)
        // ÷ in exponent: like fraction key, wrap exponent first, otherwise it falls below baseline (e^x÷2 = (e^x)÷2)
        if (op == "÷") {
            val exp = supExpSlotAt(expr, p)
            if (exp != null) {
                val s = exp.first
                val e = exp.second
                if (s == e && p == s) {
                    return EditResult(expr.substring(0, s) + "(÷)" + expr.substring(s), s + 1)
                }
                if (p > s && e > s) {
                    val content = expr.substring(s, e)
                    if (isWholeParenGroup(content)) {
                        val at = if (p >= e) e - 1 else p
                        return insert(expr, at, "÷")
                    }
                    val wrapped = expr.substring(0, s) + "(" + content + ")" + expr.substring(e)
                    return insert(wrapped, p + 1, "÷")
                }
            }
        }
        if (op !in SLOT_OPS) return insert(expr, p, op)
        val span = fracSlotAt(expr, p) ?: return insert(expr, p, op)
        val s = span.first
        val e = span.last + 1
        if (e <= s || p <= s) return insert(expr, p, op)   // Empty slot / slot start: normal input
        val content = expr.substring(s, e)
        if (isWholeParenGroup(content)) {
            // Already an entire bracket group: insert operator inside brackets when cursor is before ')'
            val at = if (p >= e) e - 1 else p
            return insert(expr, at, op)
        }
        val wrapped = expr.substring(0, s) + "(" + content + ")" + expr.substring(e)
        return insert(wrapped, p + 1, op)
    }

    /** Source range of the fraction slot nearest (deepest) to cursor */
    private fun fracSlotAt(expr: String, pos: Int): IntRange? {
        val root = try {
            buildNat(expr)
        } catch (_: Exception) {
            return null
        }
        var best: IntRange? = null
        fun walk(n: Nat) {
            when (n) {
                is Nat.Row -> n.items.forEach { if (inSpan(it, pos)) walk(it) }
                is Nat.Frac -> {
                    if (inSpan(n.n, pos)) {
                        if (n.n.srcStart >= 0 && n.n.srcEnd > n.n.srcStart) best = n.n.srcStart until n.n.srcEnd
                        walk(n.n)
                    }
                    if (inSpan(n.d, pos)) {
                        if (n.d.srcStart >= 0 && n.d.srcEnd > n.d.srcStart) best = n.d.srcStart until n.d.srcEnd
                        walk(n.d)
                    }
                }
                is Nat.Sqrt -> if (inSpan(n.a, pos)) walk(n.a)
                is Nat.Sup -> {
                    if (inSpan(n.base, pos)) walk(n.base)
                    if (inSpan(n.exp, pos)) walk(n.exp)
                }
                is Nat.Integ -> {
                    if (inSpan(n.body, pos)) walk(n.body)
                    if (inSpan(n.lo, pos)) walk(n.lo)
                    if (inSpan(n.hi, pos)) walk(n.hi)
                }
                else -> {}
            }
        }
        walk(root)
        return best
    }

    /** Whether whole string is enclosed by exactly one pair of brackets (e.g. "(5×6)"); returns false if closed early */
    private fun isWholeParenGroup(s: String): Boolean {
        if (s.length < 2 || s[0] != '(' || s[s.length - 1] != ')') return false
        var depth = 0
        for (i in s.indices) {
            when (s[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth < 0) return false
                    if (depth == 0 && i != s.length - 1) return false
                }
            }
        }
        return depth == 0
    }

    private val DMS_CHARS = setOf('\u00B0', '\u2032', '\u2033', '\'', '"')

    /**
     * °′″ key: cycles degree -> minute -> second symbols.
     * Scans backward to start of current DMS literal to see existing symbols and decide next.
     */
    fun insertDmsSeparator(expr: String, pos: Int): EditResult {
        val p = clamp(expr, pos)
        var s = p
        while (s > 0) {
            val ch = expr[s - 1]
            if (ch.isDigit() || ch == '.' || ch in DMS_CHARS) s-- else break
        }
        var hasDeg = false
        var hasMin = false
        var hasSec = false
        for (k in s until p) {
            when (expr[k]) {
                '\u00B0' -> hasDeg = true
                '\u2032', '\'' -> hasMin = true
                '\u2033', '"' -> hasSec = true
            }
        }
        val next = when {
            !hasDeg -> "\u00B0"   // °
            !hasMin -> "\u2032"   // ′
            !hasSec -> "\u2033"   // ″
            else -> "\u00B0"
        }
        return insert(expr, p, next)
    }

    /**
     * Main row fraction key (K3-symbolic fix): inserts ÷.
     * When numerator is an empty slot (line start / after operator / open paren / separator), cursor stays in numerator slot
     * (consistent with secondary screen keypad a/b with cursorBack=1); enters denominator slot when numerator has content.
     */
    fun insertFraction(expr: String, pos: Int): EditResult {
        val p = clamp(expr, pos)
        // Pressing fraction key in exponent slot: wrap exponent content in brackets then insert ÷, keeping fraction inside exponent,
        // otherwise ÷ falls below baseline according to precedence (x^2÷3 = (x^2)÷3)
        val expSpan = supExpSlotAt(expr, p)
        if (expSpan != null) {
            val s = expSpan.first
            val e = expSpan.second            // Open interval (end excluded)
            // Empty exponent (fraction key pressed right after x^): create parenthesized empty fraction, cursor in numerator
            if (s == e && p == s) {
                val text = expr.substring(0, s) + "(÷)" + expr.substring(s)
                return EditResult(text, s + 1)
            }
            if (p > s && e > s) {
                val content = expr.substring(s, e)
                if (isWholeParenGroup(content)) {
                    val at = if (p >= e) e - 1 else p
                    return insert(expr, at, "÷")
                }
                val wrapped = expr.substring(0, s) + "(" + content + ")" + expr.substring(e)
                return insert(wrapped, p + 1, "÷")
            }
        }
        val r = insert(expr, p, "÷")
        val emptyNum = p == 0 || expr[p - 1] in "+−×÷^(,=;∠·±"
        return if (emptyNum) EditResult(r.text, p) else r
    }

    /**
     * Pressing ▶ / ▼ at exponent end: wraps exponent in brackets, moves cursor behind ')' —
     * allowing subsequent input to resume on baseline rather than appending to exponent.
     */
    fun exitSupIfAtEnd(expr: String, pos: Int): EditResult? {
        val p = clamp(expr, pos)
        val exp = supExpSlotAt(expr, p) ?: return null
        val s = exp.first
        val e = exp.second
        if (p != e) return null
        // Empty exponent (nothing typed after e^): drop dangling ^, cursor returns to baseline
        if (s == e) {
            if (s <= 0 || expr[s - 1] != '^') return null
            return EditResult(expr.removeRange(s - 1, s), s - 1)
        }
        val content = expr.substring(s, e)
        if (isWholeParenGroup(content)) return null   // Already outside brackets, no handling needed
        val wrapped = expr.substring(0, s) + "(" + content + ")" + expr.substring(e)
        return EditResult(wrapped, e + 2)
    }

    /** Overall end position of integral block containing cursor (after dx); returns null if not inside integral */
    fun integNodeEnd(expr: String, pos: Int): Int? {
        val p = clamp(expr, pos)
        val root = try {
            buildNat(expr)
        } catch (_: Exception) {
            return null
        }
        var hit: Int? = null
        fun walk(n: Nat) {
            when (n) {
                is Nat.Row -> n.items.forEach { walk(it) }
                is Nat.Frac -> {
                    walk(n.n)
                    walk(n.d)
                }
                is Nat.Sqrt -> walk(n.a)
                is Nat.Sup -> {
                    walk(n.base)
                    walk(n.exp)
                }
                is Nat.Integ -> {
                    if (inSpan(n.body, p) || inSpan(n.lo, p) || inSpan(n.hi, p)) hit = n.srcEnd
                    walk(n.body)
                    walk(n.lo)
                    walk(n.hi)
                }
                else -> {}
            }
        }
        walk(root)
        return hit
    }

    /** Range (start, end) of integral slot containing cursor; returns null if not in slot */
    fun integSlotBounds(expr: String, pos: Int): Pair<Int, Int>? {
        val p = clamp(expr, pos)
        val root = try {
            buildNat(expr)
        } catch (_: Exception) {
            return null
        }
        var hit: Pair<Int, Int>? = null
        fun walk(n: Nat) {
            when (n) {
                is Nat.Row -> n.items.forEach { walk(it) }
                is Nat.Frac -> {
                    walk(n.n)
                    walk(n.d)
                }
                is Nat.Sqrt -> walk(n.a)
                is Nat.Sup -> {
                    walk(n.base)
                    walk(n.exp)
                }
                is Nat.Integ -> {
                    if (inSpan(n.body, p)) hit = n.body.srcStart to n.body.srcEnd
                    if (inSpan(n.lo, p)) hit = n.lo.srcStart to n.lo.srcEnd
                    if (inSpan(n.hi, p)) hit = n.hi.srcStart to n.hi.srcEnd
                    walk(n.body)
                    walk(n.lo)
                    walk(n.hi)
                }
                else -> {}
            }
        }
        walk(root)
        return hit
    }

    /**
     * Arrow key navigation for integral template (by screen orientation, not source order):
     *  - Pressing ▶ at integrand end -> Upper limit (top slot on screen)
     *  - Pressing ◀ at upper limit start -> Integrand end
     * Upper limit -> Lower limit via ▼, handled by moveVertical.
     */
    fun integNavTarget(expr: String, pos: Int, toUpper: Boolean): Int? {
        val p = clamp(expr, pos)
        val root = try {
            buildNat(expr)
        } catch (_: Exception) {
            return null
        }
        var hit: Int? = null
        fun walk(n: Nat) {
            when (n) {
                is Nat.Row -> n.items.forEach { walk(it) }
                is Nat.Frac -> {
                    walk(n.n)
                    walk(n.d)
                }
                is Nat.Sqrt -> walk(n.a)
                is Nat.Sup -> {
                    walk(n.base)
                    walk(n.exp)
                }
                is Nat.Integ -> {
                    // Integrand end ▶ -> integral block end (right of dx), does not jump to upper limit
                    if (toUpper && n.body.srcEnd == p) hit = n.srcEnd
                    walk(n.body)
                    walk(n.lo)
                    walk(n.hi)
                }
                else -> {}
            }
        }
        walk(root)
        return hit
    }

    /** Source range of exponent slot (Nat.Sup.exp) nearest to cursor; returns (start, end), end is open */
    private fun supExpSlotAt(expr: String, pos: Int): Pair<Int, Int>? {
        val root = try {
            buildNat(expr)
        } catch (_: Exception) {
            return null
        }
        var best: Pair<Int, Int>? = null
        fun walk(n: Nat) {
            when (n) {
                is Nat.Row -> n.items.forEach { if (inSpan(it, pos)) walk(it) }
                is Nat.Frac -> {
                    if (inSpan(n.n, pos)) walk(n.n)
                    if (inSpan(n.d, pos)) walk(n.d)
                }
                is Nat.Sqrt -> if (inSpan(n.a, pos)) walk(n.a)
                is Nat.Sup -> {
                    if (inSpan(n.base, pos)) walk(n.base)
                    if (n.exp.srcStart >= 0 && pos >= n.exp.srcStart && pos <= n.exp.srcEnd) {
                        // Empty exponent (just pressed ^) must also be recognized: start == end
                        best = n.exp.srcStart to n.exp.srcEnd
                        walk(n.exp)
                    }
                }
                is Nat.Integ -> {
                    if (inSpan(n.body, pos)) walk(n.body)
                    if (inSpan(n.lo, pos)) walk(n.lo)
                    if (inSpan(n.hi, pos)) walk(n.hi)
                }
                else -> {}
            }
        }
        walk(root)
        return best
    }
}

// ---------------------------------------------------------------------------
// Caret landing point: Insert Nat.Cursor into layout tree
// ---------------------------------------------------------------------------

private fun inSpan(n: Nat, cur: Int): Boolean =
    n.srcStart >= 0 && cur >= n.srcStart && cur <= n.srcEnd

/**
 * Places caret within node whose source range is [srcStart, srcEnd] (caller guarantees cur is within node span).
 * Deepens preferentially into child nodes containing cur — allowing caret to enter fraction numerator/denominator, radicals, superscripts, brackets, empty slots.
 */
private fun placeCursor(node: Nat, cur: Int): Nat = when (node) {
    is Nat.Sym -> {
        val s = node.srcStart
        val e = node.srcEnd
        when {
            s < 0 || e < s -> Nat.Row(listOf(node, Nat.Cursor))
            s == e -> Nat.Row(listOf(Nat.Cursor, node))               // Empty slot
            cur <= s -> Nat.Row(listOf(Nat.Cursor, node))
            cur >= e -> Nat.Row(listOf(node, Nat.Cursor))
            // deSup is 1:1 mapping, so token text length == source length is required to split midway
            e - s == node.text.length -> {
                val k = cur - s
                Nat.Row(
                    listOf(
                        Nat.Sym(node.text.substring(0, k)),
                        Nat.Cursor,
                        Nat.Sym(node.text.substring(k)),
                    )
                )
            }
            else -> Nat.Row(listOf(node, Nat.Cursor))
        }
    }

    is Nat.Row -> {
        val idx = node.items.indexOfFirst { inSpan(it, cur) }
        if (idx >= 0) {
            val items = node.items.toMutableList()
            items[idx] = placeCursor(items[idx], cur)
            Nat.Row(items)
        } else {
            // K3-symbolic fix: when cur falls outside all child items, place by position —
            // place caret at line start if before line start, rather than always throwing to line end
            if (cur <= node.srcStart) Nat.Row(listOf(Nat.Cursor) + node.items)
            else Nat.Row(node.items + Nat.Cursor)
        }
    }

    is Nat.Frac -> when {
        inSpan(node.n, cur) -> Nat.Frac(placeCursor(node.n, cur), node.d)
        inSpan(node.d, cur) -> Nat.Frac(node.n, placeCursor(node.d, cur))
        cur <= node.srcStart -> Nat.Row(listOf(Nat.Cursor, node))
        else -> Nat.Row(listOf(node, Nat.Cursor))
    }

    // K3-symbolic fix: when caret is on √ symbol position (before content slot), draw before radical;
    // legacy logic threw it after radical (navigating ◀ to line start displayed caret at radical end)
    is Nat.Sqrt ->
        if (inSpan(node.a, cur)) Nat.Sqrt(placeCursor(node.a, cur))
        else if (cur <= node.srcStart) Nat.Row(listOf(Nat.Cursor, node))
        else Nat.Row(listOf(node, Nat.Cursor))

    is Nat.Sup -> when {
        inSpan(node.base, cur) -> Nat.Sup(placeCursor(node.base, cur), node.exp)
        inSpan(node.exp, cur) -> Nat.Sup(node.base, placeCursor(node.exp, cur))
        cur <= node.srcStart -> Nat.Row(listOf(Nat.Cursor, node))
        else -> Nat.Row(listOf(node, Nat.Cursor))
    }

    // Batch K3: Caret can land in integrand / lower limit / upper limit slots of ∫ template;
    // K3-symbolic fix: int( prefix position draws before ∫, no longer thrown behind dx
    is Nat.Integ -> when {
        inSpan(node.body, cur) -> Nat.Integ(placeCursor(node.body, cur), node.lo, node.hi)
        inSpan(node.lo, cur) -> Nat.Integ(node.body, placeCursor(node.lo, cur), node.hi)
        inSpan(node.hi, cur) -> Nat.Integ(node.body, node.lo, placeCursor(node.hi, cur))
        cur <= node.srcStart -> Nat.Row(listOf(Nat.Cursor, node))
        else -> Nat.Row(listOf(node, Nat.Cursor))
    }

    is Nat.Cursor -> node
}

/**
 * Parses expression and inserts caret marker into layout tree (exactly one Nat.Cursor).
 * On parse failure, falls back to "plain text + trailing caret" to ensure caret is never lost.
 */
fun buildNatCursor(expr: String, cursor: Int): Nat {
    val root = buildNat(expr)
    return placeCursor(root, cursor.coerceIn(0, expr.length))
}

/**
 * Structural hierarchy version: cursorExit > 0 indicates caret has stepped outside structure.
 * For fractions/superscripts/radicals, "content end" and "structural right boundary" share string offset;
 * when stepping out, placing caret at document end (or start) visually exits the structure.
 */
fun buildNatCursor(expr: String, cursor: Int, cursorExit: Int): Nat {
    val root = buildNat(expr)
    val p = cursor.coerceIn(0, expr.length)
    if (cursorExit <= 0) return placeCursor(root, p)

    // Caret is outside right boundary of a structure: wrap structure whole, draw caret at parent level (outside structure)
    val ends = ArrayList<Nat>()
    collectEnds(root, p, ends)
    ends.sortByDescending { it.srcStart }
    ends.getOrNull(cursorExit - 1)?.let { target ->
        wrapNode(root, target, before = false)?.let { return it }
    }

    // Outside left boundary
    val starts = ArrayList<Nat>()
    collectStarts(root, p, starts)
    starts.sortBy { it.srcEnd }
    starts.getOrNull(cursorExit - 1)?.let { target ->
        wrapNode(root, target, before = true)?.let { return it }
    }

    return placeCursor(root, p)
}

private fun collectEnds(n: Nat, p: Int, out: MutableList<Nat>) {
    when (n) {
        is Nat.Row -> n.items.forEach { collectEnds(it, p, out) }
        is Nat.Frac -> {
            collectEnds(n.n, p, out)
            collectEnds(n.d, p, out)
            if (n.srcEnd == p) out.add(n)
        }
        is Nat.Sup -> {
            collectEnds(n.base, p, out)
            collectEnds(n.exp, p, out)
            if (n.srcEnd == p) out.add(n)
        }
        is Nat.Sqrt -> {
            collectEnds(n.a, p, out)
            if (n.srcEnd == p) out.add(n)
        }
        is Nat.Integ -> {
            collectEnds(n.body, p, out)
            collectEnds(n.lo, p, out)
            collectEnds(n.hi, p, out)
            if (n.srcEnd == p) out.add(n)
        }
        else -> Unit
    }
}

private fun collectStarts(n: Nat, p: Int, out: MutableList<Nat>) {
    when (n) {
        is Nat.Row -> n.items.forEach { collectStarts(it, p, out) }
        is Nat.Frac -> {
            collectStarts(n.n, p, out)
            collectStarts(n.d, p, out)
            if (n.srcStart == p) out.add(n)
        }
        is Nat.Sup -> {
            collectStarts(n.base, p, out)
            collectStarts(n.exp, p, out)
            if (n.srcStart == p) out.add(n)
        }
        is Nat.Sqrt -> {
            collectStarts(n.a, p, out)
            if (n.srcStart == p) out.add(n)
        }
        is Nat.Integ -> {
            collectStarts(n.body, p, out)
            collectStarts(n.lo, p, out)
            collectStarts(n.hi, p, out)
            if (n.srcStart == p) out.add(n)
        }
        else -> Unit
    }
}

/** Replaces target node with Row(target, Cursor) or Row(Cursor, target); returns null if not found */
private fun wrapNode(n: Nat, target: Nat, before: Boolean): Nat? {
    if (n === target) {
        val row = if (before) Nat.Row(listOf(Nat.Cursor, n)) else Nat.Row(listOf(n, Nat.Cursor))
        row.srcStart = n.srcStart
        row.srcEnd = n.srcEnd
        return row
    }
    fun rep(c: Nat): Nat? = wrapNode(c, target, before)
    return when (n) {
        is Nat.Row -> {
            var changed = false
            val items = n.items.map { c ->
                val r = rep(c)
                if (r != null) { changed = true; r } else c
            }
            if (!changed) null else Nat.Row(items).also { it.srcStart = n.srcStart; it.srcEnd = n.srcEnd }
        }
        is Nat.Frac -> {
            val a = rep(n.n)
            val b = rep(n.d)
            if (a == null && b == null) null
            else Nat.Frac(a ?: n.n, b ?: n.d).also { it.srcStart = n.srcStart; it.srcEnd = n.srcEnd }
        }
        is Nat.Sup -> {
            val a = rep(n.base)
            val b = rep(n.exp)
            if (a == null && b == null) null
            else Nat.Sup(a ?: n.base, b ?: n.exp).also { it.srcStart = n.srcStart; it.srcEnd = n.srcEnd }
        }
        is Nat.Sqrt -> {
            val a = rep(n.a) ?: return null
            Nat.Sqrt(a).also { it.srcStart = n.srcStart; it.srcEnd = n.srcEnd }
        }
        is Nat.Integ -> {
            val a = rep(n.body)
            val b = rep(n.lo)
            val c = rep(n.hi)
            if (a == null && b == null && c == null) null
            else Nat.Integ(a ?: n.body, b ?: n.lo, c ?: n.hi)
                .also { it.srcStart = n.srcStart; it.srcEnd = n.srcEnd }
        }
        else -> null
    }
}

/**
 * Structural path of caret within layout tree (for testing / debugging), e.g. root/Row[1]/num, root/exp.
 * Returns empty string if not found.
 */
fun natCursorPath(n: Nat): String {
    fun walk(node: Nat, path: String): String? = when (node) {
        is Nat.Cursor -> path
        is Nat.Sym -> null
        is Nat.Row -> {
            for ((ix, c) in node.items.withIndex()) {
                walk(c, "$path/Row[$ix]")?.let { return it }
            }
            null
        }
        is Nat.Frac -> walk(node.n, "$path/num") ?: walk(node.d, "$path/den")
        is Nat.Sqrt -> walk(node.a, "$path/sqrt")
        is Nat.Sup -> walk(node.base, "$path/base") ?: walk(node.exp, "$path/exp")
        is Nat.Integ -> walk(node.body, "$path/body")
            ?: walk(node.lo, "$path/lo")
            ?: walk(node.hi, "$path/hi")
    }
    return walk(n, "root") ?: ""
}

// ---------------------------------------------------------------------------
// Natural display node tree -> linear text (B4: reverse of linear text <-> node tree bidirectional transformation)
// ---------------------------------------------------------------------------

/** Whether node is a "self-grouping" group node (already has grouping capability, doesn't need outer wrap) */
private fun isGroupNode(n: Nat): Boolean = n is Nat.Row && n.items.size >= 2 &&
    (n.items.first() as? Nat.Sym)?.text == "(" &&
    (n.items.last() as? Nat.Sym)?.text == ")"

/** Whether to add brackets when outputting as fraction numerator/denominator or superscript exponent */
private fun argText(n: Nat, forSup: Boolean): String {
    val t = natLinear(n)
    val need = when {
        isGroupNode(n) -> false
        n is Nat.Sym -> false
        n is Nat.Sqrt -> false            // √(..) has inherent brackets
        n is Nat.Frac -> true             // a÷b÷c associativity ambiguity
        n is Nat.Sup -> false             // ^ is right-associative: x^2^3 == x^(2^3)
        n is Nat.Row -> n.items.any {
            (it is Nat.Sym && (it.text == "+" || it.text == "\u2212" || it.text == "\u00F7" ||
                (forSup && it.text == "\u00D7"))) || it is Nat.Frac
        }
        else -> false
    }
    return if (need) "($t)" else t
}

/** Natural display node tree -> linear text (caret markers emit no characters) */
fun natLinear(n: Nat): String = when (n) {
    is Nat.Sym -> n.text
    is Nat.Cursor -> ""
    is Nat.Row -> buildString { n.items.forEach { append(natLinear(it)) } }
    is Nat.Frac -> argText(n.n, forSup = false) + "\u00F7" + argText(n.d, forSup = false)
    is Nat.Sqrt -> if (isGroupNode(n.a)) "\u221A" + natLinear(n.a) else "\u221A(" + natLinear(n.a) + ")"
    is Nat.Sup -> natLinear(n.base) + "^" + argText(n.exp, forSup = true)
    // Batch K3: Integral template linearized to int(expr, lo, hi) (slots linearized as-is, empty slot is empty)
    is Nat.Integ -> "int(" + natLinear(n.body) + "," + natLinear(n.lo) + "," + natLinear(n.hi) + ")"
}

/** Expression string -> node tree -> linear text (normalized synonymous linear expression) */
fun natLinearOf(expr: String): String = natLinear(buildNat(expr))
