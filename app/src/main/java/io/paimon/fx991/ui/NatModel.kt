package io.paimon.fx991.ui

// ---------------------------------------------------------------------------
// Natural display layout tree parsing (pure Kotlin, no Compose dependencies -> directly testable via JVM regression)
//   - Fraction: a÷b -> vertically stacked
//   - √       : overbar vinculum
//   - Superscript: x² / x^y / scientific notation ×10ⁿ
// ---------------------------------------------------------------------------

sealed interface Nat {
    /** Source range in the original expression string ([srcStart, srcEnd)); -1 = no corresponding source (e.g. fallback plain text) */
    var srcStart: Int
    var srcEnd: Int

    class Row(val items: List<Nat>) : Nat {
        override var srcStart: Int = -1
        override var srcEnd: Int = -1
    }
    class Frac(val n: Nat, val d: Nat) : Nat {
        override var srcStart: Int = -1
        override var srcEnd: Int = -1
    }
    class Sqrt(val a: Nat) : Nat {
        override var srcStart: Int = -1
        override var srcEnd: Int = -1
    }
    class Sup(val base: Nat, val exp: Nat) : Nat {
        override var srcStart: Int = -1
        override var srcEnd: Int = -1
    }

    /** Batch K3: 2D definite integral template int(expr, lower, upper) -> screen display ∫_lower^upper expr dx */
    class Integ(val body: Nat, val lo: Nat, val hi: Nat) : Nat {
        override var srcStart: Int = -1
        override var srcEnd: Int = -1
    }
    class Sym(val text: String) : Nat {
        override var srcStart: Int = -1
        override var srcEnd: Int = -1
    }

    /** Batch K3-A: Visible caret marker (inserted by buildNatCursor, rendered as blinking vertical bar) */
    data object Cursor : Nat {
        override var srcStart: Int
            get() = -1
            set(_) {}
        override var srcEnd: Int
            get() = -1
            set(_) {}
    }
}

internal enum class DT {
    NUM, IDENT, PLUS, MINUS, MUL, DIV, POW, FACT, PCT, SQ2, CUBE, RECIP, SUP, LP, RP, SQRT, COMMA, ANGLE, EQ, SEMI, DOT, PM, END
}

internal class DTok(val t: DT, val s: String, val start: Int = -1, val end: Int = -1)

private const val CH_MINUS = '\u2212'
private const val CH_MUL = '\u00D7'
private const val CH_DIV = '\u00F7'
private const val CH_SQ2 = '\u00B2'
private const val CH_CUBE = '\u00B3'
private const val CH_SUP_MINUS = '\u207B'
private const val CH_SUP_ONE = '\u00B9'
private const val CH_SQRT = '\u221A'
private const val CH_SIGMA = '\u03A3'

internal const val SUP_ALL = "\u2070\u00B9\u00B2\u00B3\u2074\u2075\u2076\u2077\u2078\u2079"

/** Batch K3: Screen display aliases (only affects rendering and ASCII projection; source and natLinear writeback retain original name) */
internal fun natDisplayName(s: String): String = when (s) {
    "deriv" -> "d/dx"
    "sum" -> "\u03A3"
    else -> s
}

/** Superscript character -> regular character */
internal fun deSup(s: String): String {
    val sb = StringBuilder()
    for (ch in s) {
        when (ch) {
            '\u207B' -> sb.append('-')
            else -> {
                val k = SUP_ALL.indexOf(ch)
                sb.append(if (k >= 0) ('0' + k) else ch)
            }
        }
    }
    return sb.toString()
}

internal fun dlex(src: String): List<DTok> {
    val out = ArrayList<DTok>()
    var i = 0
    fun tok(t: DT, s: String, from: Int, to: Int) {
        out.add(DTok(t, s, from, to))
    }
    while (i < src.length) {
        val c = src[i]
        when {
            c.isWhitespace() -> i++
            c.isDigit() || c == '.' -> {
                val s = i
                var dot = false
                while (i < src.length) {
                    val d = src[i]
                    if (d.isDigit()) i++ else if (d == '.' && !dot) { dot = true; i++ } else break
                }
                tok(DT.NUM, src.substring(s, i), s, i)
            }
            c.isLetter() -> {
                val s = i
                while (i < src.length && src[i].isLetter()) i++
                if (i + 1 < src.length && src[i] == CH_SUP_MINUS && src[i + 1] == CH_SUP_ONE) i += 2
                tok(DT.IDENT, src.substring(s, i), s, i)
            }
            c == '+' -> { tok(DT.PLUS, "+", i, i + 1); i++ }
            c == '-' || c == CH_MINUS -> { tok(DT.MINUS, "\u2212", i, i + 1); i++ }
            c == '*' || c == CH_MUL -> { tok(DT.MUL, "\u00D7", i, i + 1); i++ }
            c == '/' || c == CH_DIV -> { tok(DT.DIV, "\u00F7", i, i + 1); i++ }
            c == '^' -> { tok(DT.POW, "^", i, i + 1); i++ }
            c == '!' -> { tok(DT.FACT, "!", i, i + 1); i++ }
            c == '%' -> { tok(DT.PCT, "%", i, i + 1); i++ }
            c == '(' -> { tok(DT.LP, "(", i, i + 1); i++ }
            c == ')' -> { tok(DT.RP, ")", i, i + 1); i++ }
            c == CH_SQRT -> { tok(DT.SQRT, "\u221A", i, i + 1); i++ }
            c == CH_SQ2 -> { tok(DT.SQ2, CH_SQ2.toString(), i, i + 1); i++ }
            c == CH_CUBE -> { tok(DT.CUBE, CH_CUBE.toString(), i, i + 1); i++ }
            c == CH_SIGMA -> { tok(DT.IDENT, "\u03A3", i, i + 1); i++ }
            c == ',' -> { tok(DT.COMMA, ",", i, i + 1); i++ }
            c == '=' -> { tok(DT.EQ, "=", i, i + 1); i++ }
            c == ';' -> { tok(DT.SEMI, ";", i, i + 1); i++ }
            c == '\u00B7' -> { tok(DT.DOT, "\u00B7", i, i + 1); i++ }
            // Batch K3-symbolic: ± in equation solutions (e.g. x = ±i / −1 ± 2i) — previously silently dropped by lexer,
            // causing result row to render as "x=i" / "−12i" (misleading); now drawn as inline symbol
            c == '\u00B1' -> { tok(DT.PM, "\u00B1", i, i + 1); i++ }
            c == '\u2220' -> { tok(DT.ANGLE, "\u2220", i, i + 1); i++ }
            // Batch G: Derivative prime f'(x) (ASCII '); Unicode ′ ″ reserved for DMS (deg/min/sec)
            c == '\'' -> { tok(DT.IDENT, "'", i, i + 1); i++ }
            c == '\u2032' -> { tok(DT.IDENT, "\u2032", i, i + 1); i++ }
            c == '\u2033' -> { tok(DT.IDENT, "\u2033", i, i + 1); i++ }
            c == '\u00B0' -> { tok(DT.IDENT, "\u00B0", i, i + 1); i++ }
            c in SUP_ALL || c == CH_SUP_MINUS -> {
                val s = i
                while (i < src.length && (src[i] in SUP_ALL || src[i] == CH_SUP_MINUS)) i++
                val raw = src.substring(s, i)
                if (raw == "\u207B\u00B9") {
                    tok(DT.RECIP, raw, s, i)
                } else {
                    tok(DT.SUP, raw, s, i)
                }
            }
            else -> i++
        }
    }
    out.add(DTok(DT.END, "", src.length, src.length))
    return out
}

internal class NatParser(private val ts: List<DTok>) {
    private var i = 0
    private fun cur() = ts[i]
    private fun take(t: DT): DTok? = if (ts[i].t == t) ts[i++] else null
    private fun pos(): Int = ts[i].start
    private fun <T : Nat> T.sp(s: Int, e: Int): T = apply { srcStart = s; srcEnd = e }
    private fun empty(): Nat.Sym = Nat.Sym("").sp(pos(), pos())

    /** Combines multiple child nodes into a row (range = union of child ranges); returns single element directly */
    private fun rowOf(items: List<Nat>): Nat {
        if (items.size == 1) return items[0]
        val s = items.minOf { if (it.srcStart >= 0) it.srcStart else Int.MAX_VALUE }
        val e = items.maxOf { it.srcEnd }
        return Nat.Row(items).sp(if (s == Int.MAX_VALUE) -1 else s, e)
    }

    private fun startsPrimary(): Boolean = when (ts[i].t) {
        DT.NUM, DT.IDENT, DT.LP, DT.SQRT -> true
        else -> false
    }

    fun parse(): Nat = expr()

    private fun expr(): Nat = exprLevel(true)

    /** Batch K3: Expression used in template slots — comma is not treated as inline symbol (reserved for slot splitting) */
    private fun argExpr(): Nat = exprLevel(false)

    private fun exprLevel(commaInline: Boolean): Nat {
        val parts = ArrayList<Nat>()
        parts.add(term())
        while (true) {
            // Argument separator / polar coord / equals / semicolon / dot product: all treated as inline symbols to ensure rendering in natural display
            val op = when (cur().t) {
                DT.PLUS -> take(DT.PLUS)!!.let { Nat.Sym("+").sp(it.start, it.end) }
                DT.MINUS -> take(DT.MINUS)!!.let { Nat.Sym("\u2212").sp(it.start, it.end) }
                DT.COMMA -> if (commaInline) {
                    take(DT.COMMA)!!.let { Nat.Sym(",").sp(it.start, it.end) }
                } else {
                    null
                }
                DT.ANGLE -> take(DT.ANGLE)!!.let { Nat.Sym("\u2220").sp(it.start, it.end) }
                DT.EQ -> take(DT.EQ)!!.let { Nat.Sym("=").sp(it.start, it.end) }
                DT.SEMI -> take(DT.SEMI)!!.let { Nat.Sym(";").sp(it.start, it.end) }
                DT.DOT -> take(DT.DOT)!!.let { Nat.Sym("\u00B7").sp(it.start, it.end) }
                DT.PM -> take(DT.PM)!!.let { Nat.Sym("\u00B1").sp(it.start, it.end) }
                else -> null
            } ?: break
            parts.add(op)
            parts.add(term())
        }
        return rowOf(parts)
    }

    private fun term(): Nat {
        var left = factor()
        while (true) {
            when (cur().t) {
                DT.MUL -> {
                    val op = take(DT.MUL)!!
                    val right = factor()
                    left = rowOf(listOf(left, Nat.Sym("\u00D7").sp(op.start, op.end), right))
                }
                DT.DIV -> {
                    take(DT.DIV)
                    val right = factor()
                    left = Nat.Frac(left, right).sp(left.srcStart, right.srcEnd)
                }
                else -> return left
            }
        }
    }

    private fun factor(): Nat {
        val parts = ArrayList<Nat>()
        parts.add(unary())
        while (startsPrimary()) parts.add(unary())
        return rowOf(parts)
    }

    private fun unary(): Nat {
        take(DT.MINUS)?.let { op ->
            val n = unary()
            return rowOf(listOf(Nat.Sym("\u2212").sp(op.start, op.end), n))
        }
        if (take(DT.PLUS) != null) return unary()
        return power()
    }

    private fun power(): Nat {
        val base = postfix()
        take(DT.POW)?.let {
            val e = unary()
            return Nat.Sup(base, e).sp(base.srcStart, e.srcEnd)
        }
        return base
    }

    private fun postfix(): Nat {
        var n = primary()
        while (true) {
            when (cur().t) {
                DT.FACT -> {
                    val op = take(DT.FACT)!!
                    n = rowOf(listOf(n, Nat.Sym("!").sp(op.start, op.end)))
                }
                DT.PCT -> {
                    val op = take(DT.PCT)!!
                    n = rowOf(listOf(n, Nat.Sym("%").sp(op.start, op.end)))
                }
                DT.SQ2 -> {
                    val op = take(DT.SQ2)!!
                    n = Nat.Sup(n, Nat.Sym("2").sp(op.start, op.end)).sp(n.srcStart, op.end)
                }
                DT.CUBE -> {
                    val op = take(DT.CUBE)!!
                    n = Nat.Sup(n, Nat.Sym("3").sp(op.start, op.end)).sp(n.srcStart, op.end)
                }
                DT.RECIP -> {
                    val op = take(DT.RECIP)!!
                    // x⁻¹ = 1/x: numerator "1" is synthetic (zero-width, located at token start);
                    // denominator slot (= base) extends to cover ⁻¹ token so caret can enter denominator slot
                    n.srcEnd = op.end
                    n = Nat.Frac(Nat.Sym("1").sp(op.start, op.start), n).sp(n.srcStart, op.end)
                }
                DT.SUP -> {
                    val op = take(DT.SUP)!!
                    n = Nat.Sup(n, Nat.Sym(deSup(op.s)).sp(op.start, op.end)).sp(n.srcStart, op.end)
                }
                else -> return n
            }
        }
    }

    private fun primary(): Nat = when (cur().t) {
        DT.NUM -> take(DT.NUM)!!.let { Nat.Sym(it.s).sp(it.start, it.end) }
        DT.SQRT -> {
            val op = take(DT.SQRT)!!
            val a = unary()
            Nat.Sqrt(a).sp(op.start, a.srcEnd)
        }
        DT.IDENT -> {
            val tok = take(DT.IDENT)!!
            // Batch K3: int(expr, lo, hi) -> 2D definite integral template (falls back to generic rendering if structure doesn't match)
            if (tok.s == "int" && cur().t == DT.LP) {
                integTemplate(tok)?.let { return it }
            }
            val arg = when {
                cur().t == DT.LP -> group()
                // Implicit arguments (e.g. sin x, x y) may only start with digit/identifier/radical/minus;
                // must NEVER consume PLUS — unary() would silently consume '+', causing "x+3" to render as "x3" (fixes pluskey bug)
                cur().t == DT.NUM || cur().t == DT.IDENT ||
                    cur().t == DT.SQRT || cur().t == DT.MINUS -> unary()
                else -> empty()
            }
            rowOf(listOf(Nat.Sym(tok.s).sp(tok.start, tok.end), arg))
        }
        DT.LP -> group()
        else -> empty()
    }

    private fun group(): Nat {
        val lp = take(DT.LP)!!
        val e = if (cur().t == DT.RP) empty() else expr()
        val rp = take(DT.RP)
        val items = ArrayList<Nat>()
        items.add(Nat.Sym("(").sp(lp.start, lp.end))
        items.add(e)
        items.add(Nat.Sym(")").sp(rp?.start ?: e.srcEnd, rp?.end ?: e.srcEnd))
        return rowOf(items)
    }

    /** Batch K3: int(expr, lower, upper) -> Nat.Integ 2D template; slots can be empty (empty slot accepts caret) */
    private fun integTemplate(tok: DTok): Nat? {
        val save = i
        take(DT.LP)!!
        val body = argExpr()
        if (take(DT.COMMA) == null) {
            i = save
            return null
        }
        val lo = argExpr()
        if (take(DT.COMMA) == null) {
            i = save
            return null
        }
        val hi = argExpr()
        val rp = take(DT.RP)
        return Nat.Integ(body, lo, hi).sp(tok.start, rp?.end ?: hi.srcEnd)
    }
}

/** Parses expression string into natural display layout tree (falls back to plain text on parse failure) */
fun buildNat(expr: String): Nat = try {
    if (expr.isBlank()) Nat.Sym("") else NatParser(dlex(expr)).parse()
} catch (_: Exception) {
    Nat.Sym(expr)
}

// ---------------------------------------------------------------------------
// ASCII projection: Flattens natural display layout for JVM regression testing of "fractions vertically stacked"
// ---------------------------------------------------------------------------

class AsciiBlock(val lines: List<String>, val baseline: Int)

private fun blockedWidth(b: AsciiBlock): Int = b.lines.maxOfOrNull { it.length } ?: 0

private fun padRight(s: String, w: Int): String =
    if (s.length >= w) s else s + " ".repeat(w - s.length)

private fun center(s: String, w: Int): String {
    if (s.length >= w) return s
    val total = w - s.length
    val l = total / 2
    return " ".repeat(l) + s + " ".repeat(total - l)
}

private fun hcat(a: AsciiBlock, b: AsciiBlock): AsciiBlock {
    val base = maxOf(a.baseline, b.baseline)
    val below = maxOf(a.lines.size - 1 - a.baseline, b.lines.size - 1 - b.baseline)
    val h = base + 1 + below
    val out = MutableList(h) { StringBuilder() }
    fun place(x: AsciiBlock) {
        val w = blockedWidth(x)
        val top = base - x.baseline
        for (ix in x.lines.indices) out[top + ix].append(padRight(x.lines[ix], w))
    }
    place(a)
    place(b)
    return AsciiBlock(out.map { it.toString().trimEnd() }, base)
}

fun natAscii(n: Nat): AsciiBlock = when (n) {
    is Nat.Sym -> AsciiBlock(listOf(if (n.text.isEmpty()) " " else natDisplayName(n.text)), 0)

    // Batch K3-A: Caret rendered as ▏ in ASCII projection (regression tests assert caret landing position against this)
    is Nat.Cursor -> AsciiBlock(listOf("\u258F"), 0)

    is Nat.Row -> {
        var acc = AsciiBlock(listOf(""), 0)
        for (item in n.items) acc = hcat(acc, natAscii(item))
        acc
    }

    is Nat.Frac -> {
        val a = natAscii(n.n)
        val b = natAscii(n.d)
        val w = maxOf(blockedWidth(a), blockedWidth(b))
        val lines = ArrayList<String>()
        a.lines.forEach { lines.add(center(it.trim(), w)) }
        lines.add("\u2500".repeat(w))
        b.lines.forEach { lines.add(center(it.trim(), w)) }
        AsciiBlock(lines, a.lines.size)
    }

    is Nat.Sqrt -> {
        val a = natAscii(n.a)
        val w = blockedWidth(a)
        val lines = ArrayList<String>()
        lines.add("  " + "\u2500".repeat(w))
        a.lines.forEachIndexed { ix, s ->
            lines.add((if (ix == 0) "\u221A " else "  ") + s)
        }
        AsciiBlock(lines, a.baseline + 1)
    }

    is Nat.Sup -> {
        val b = natAscii(n.base)
        val e = natAscii(n.exp)
        hcat(b, AsciiBlock(e.lines, e.baseline + 1))
    }

    // Batch K3: ∫ upper limit top-right, lower limit bottom-right, integrand follows, ends with dx
    is Nat.Integ -> {
        val b = natAscii(n.body)
        val hiB = natAscii(n.hi)
        val loB = natAscii(n.lo)
        val wLim = maxOf(blockedWidth(hiB), blockedWidth(loB))
        val mid = hcat(hcat(AsciiBlock(listOf("\u222B"), 0), b), AsciiBlock(listOf(" dx"), 0))
        val lines = ArrayList<String>()
        hiB.lines.forEach { lines.add(" " + center(it.trimEnd(), wLim)) }
        lines.addAll(mid.lines)
        loB.lines.forEach { lines.add(" " + center(it.trimEnd(), wLim)) }
        AsciiBlock(lines, hiB.lines.size + mid.baseline)
    }
}

/** Flatten to single string (for testing/logging) */
fun natAsciiText(expr: String): String =
    natAscii(buildNat(expr)).lines.joinToString("\n")
