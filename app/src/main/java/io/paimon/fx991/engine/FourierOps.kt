package io.paimon.fx991.engine

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

// ---------------------------------------------------------------------------
// Batch G: Fourier series expansion (pure Kotlin, JVM regressible)
//
// Main line syntax: fourier(f(x), a, b, n)
//   fourier(x^2, -pi, pi, 5)     Expand x² on [−π, π] for 5 terms
//   fourier(x, 0, 2pi, 4)        Expand x on [0, 2π] for 4 terms
//
// Convention: period T = b − a, ω = 2π/T,
//   f(x) ≈ a0/2 + Σ [ an·cos(nωx) + bn·sin(nωx) ]
//   an = (2/T)∫[a,b] f·cos(nωx) dx     bn = (2/T)∫[a,b] f·sin(nωx) dx
// Coefficients are computed using adaptive Simpson numerical integration (same method as NumericOps, tolerance 1e-9).
// Automatically detects parity when interval is symmetric about the origin: even function has bn all 0, odd function has an/a0 all 0.
// Trigonometric functions inside Fourier calculations always use radians (independent of user's angle mode).
// ---------------------------------------------------------------------------

object FourierOps {

    enum class Parity(val label: String) {
        EVEN("Even function"),
        ODD("Odd function"),
        GENERAL("General (neither even nor odd)"),
    }

    /** Arguments parsed from main line fourier(...) */
    class MainArgs(val f: String, val a: Double, val b: Double, val n: Int)

    class Result(
        val f: String,
        val a: Double,
        val b: Double,
        val n: Int,
        /** Textbook a0 (constant term is a0/2) */
        val a0: Double,
        /** an[1..n] (index 0 unused) */
        val an: DoubleArray,
        /** bn[1..n] */
        val bn: DoubleArray,
        val parity: Parity,
        val period: Double,
        val omega: Double,
        /** Count of coefficients dropped to 0 (|c| < 1e-9 · scale) */
        val dropped: Int,
    ) {
        /** Numerical value of partial sum S_n(x) (radians) */
        fun partialAt(x: Double): Double {
            var s = a0 / 2.0
            for (k in 1..n) {
                val t = k * omega * x
                s += an[k] * cos(t) + bn[k] * sin(t)
            }
            return s
        }

        /** Original function value (radians; null if undefined) */
        fun fAt(x: Double): Double? = evalF(f, x)
    }

    // -----------------------------------------------------------------------
    // Main line parsing: fourier(f, a, b, n)
    // -----------------------------------------------------------------------

    /** Returns arguments if main line call is fourier (lazy: endpoint expressions evaluated here), otherwise null */
    @JvmStatic
    fun parseMain(src: String): MainArgs? {
        val t = src.trim()
        val low = t.lowercase()
        if (!low.startsWith("fourier")) return null
        val rest = t.substring(7).trim()
        if (!rest.startsWith("(")) {
            throw NumericError("fourier usage: fourier(f(x), lower, upper, terms)")
        }
        val inner = rest.substring(1)
        val closed = if (inner.endsWith(")")) inner.substring(0, inner.length - 1) else inner
        val parts = splitTop(closed)
        if (parts.size != 4) throw NumericError("fourier requires 4 arguments: f(x), lower, upper, terms")
        val f = parts[0].trim()
        if (f.isEmpty()) throw NumericError("fourier missing f(x)")
        val a = evalBound(parts[1], "lower bound")
        val b = evalBound(parts[2], "upper bound")
        val nd = evalBound(parts[3], "number of terms")
        if (nd != floor(nd) || nd < 1.0 || nd > 64.0) {
            throw NumericError("Number of terms n must be an integer between 1..64")
        }
        return MainArgs(f, a, b, nd.toInt())
    }

    private fun evalBound(s: String, what: String): Double {
        val t = s.trim()
        if (t.isEmpty()) throw NumericError("fourier missing $what")
        val v = try {
            // Endpoints allow -pi / 2pi, etc.; trig constants evaluated with radian semantics (endpoints are radian quantities)
            CalcEngine.evaluate(t, AngleMode.RAD, 0.0, 0.0)
        } catch (e: Exception) {
            throw NumericError("Cannot evaluate fourier $what: $t")
        }
        if (v.isNaN() || v.isInfinite()) throw NumericError("fourier $what is not finite")
        return v
    }

    /** Top-level comma splitting (does not split inside parentheses) */
    private fun splitTop(s: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        val sb = StringBuilder()
        for (ch in s) {
            when {
                ch == '(' -> { depth++; sb.append(ch) }
                ch == ')' -> { depth--; sb.append(ch) }
                depth == 0 && ch == ',' -> { out.add(sb.toString()); sb.clear() }
                else -> sb.append(ch)
            }
        }
        out.add(sb.toString())
        return out
    }

    // -----------------------------------------------------------------------
    // Coefficient calculation
    // -----------------------------------------------------------------------

    @JvmStatic
    fun compute(f: String, a: Double, b: Double, n: Int): Result {
        if (a.isNaN() || b.isNaN() || a.isInfinite() || b.isInfinite()) {
            throw NumericError("Interval endpoints must be finite")
        }
        if (a == b) throw NumericError("Interval length cannot be 0")
        if (a > b) throw NumericError("Invalid interval: lower bound must be less than upper bound")
        if (n <= 0) throw NumericError("Number of terms n must be a positive integer")
        if (n > 64) throw NumericError("Maximum number of terms n is 64")
        val fexpr = f.trim()
        if (fexpr.isEmpty()) throw NumericError("Please enter f(x)")
        // Parsing check (report syntax errors early)
        Parser(Lexer(fexpr).lex()).parse()

        val period = b - a
        val omega = 2.0 * Math.PI / period

        // Domain definition check on interval: endpoints / midpoint + 11 sample points, rejected if any undefined
        val probes = ArrayList<Double>()
        probes.add(a); probes.add(b); probes.add((a + b) / 2.0)
        for (k in 1..11) probes.add(a + period * k / 12.0)
        for (p in probes) {
            if (evalF(fexpr, p) == null) {
                throw NumericError("Function undefined in interval (x ≈ ${CalcEngine.format(p)})")
            }
        }

        val tol = 1e-9
        val a0 = (2.0 / period) * integratePanels({ x -> evalF(fexpr, x) }, a, b, tol, 1)
        val an = DoubleArray(n + 1)
        val bn = DoubleArray(n + 1)
        for (k in 1..n) {
            // Carrier cos(kωx) oscillates k periods over [a,b]: at least 8 panels per period,
            // otherwise adaptive Simpson can alias on sample points (all hitting integer phases → delta=0 false convergence)
            val panels = 8 * k
            an[k] = (2.0 / period) * integratePanels({ x ->
                evalF(fexpr, x)?.let { it * cos(k * omega * x) }
            }, a, b, tol, panels)
            bn[k] = (2.0 / period) * integratePanels({ x ->
                evalF(fexpr, x)?.let { it * sin(k * omega * x) }
            }, a, b, tol, panels)
        }

        // Parity detection (only meaningful on symmetric interval)
        val parity = detectParity(fexpr, a, b)

        // Numerical noise zeroing
        var scale = maxOf(1.0, abs(a0))
        for (k in 1..n) {
            scale = maxOf(scale, abs(an[k]), abs(bn[k]))
        }
        val zeroTol = 1e-8 * scale
        var dropped = 0
        var a0c = a0
        if (abs(a0c) < zeroTol) { if (a0c != 0.0) dropped++; a0c = 0.0 }
        for (k in 1..n) {
            if (abs(an[k]) < zeroTol) { if (an[k] != 0.0) dropped++; an[k] = 0.0 }
            if (abs(bn[k]) < zeroTol) { if (bn[k] != 0.0) dropped++; bn[k] = 0.0 }
        }
        // Zero out directly by symmetry once parity is determined (numerical integration only resolves vanishing coefficients to 1e-9 scale)
        if (parity == Parity.EVEN) for (k in 1..n) bn[k] = 0.0
        if (parity == Parity.ODD) {
            a0c = 0.0
            for (k in 1..n) an[k] = 0.0
        }
        return Result(fexpr, a, b, n, a0c, an, bn, parity, period, omega, dropped)
    }

    private fun evalF(f: String, x: Double): Double? = try {
        val v = CalcEngine.evaluateWith(f, AngleMode.RAD, x, 0.0)
        if (v.isNaN() || v.isInfinite()) null else v
    } catch (_: Exception) {
        null
    }

    /** Parity detection on symmetric interval [−L, L]: samples 32 pairs of points comparing f(−x) with ±f(x) */
    private fun detectParity(f: String, a: Double, b: Double): Parity {
        val period = b - a
        if (abs(a + b) > 1e-10 * period) return Parity.GENERAL
        var evenErr = 0.0
        var oddErr = 0.0
        var scale = 0.0
        var cnt = 0
        for (k in 1..32) {
            val x = period / 2.0 * k / 33.0
            val fp = evalF(f, x) ?: return Parity.GENERAL
            val fn = evalF(f, -x) ?: return Parity.GENERAL
            evenErr += abs(fp - fn)
            oddErr += abs(fp + fn)
            scale += abs(fp) + abs(fn)
            cnt++
        }
        val tol = 1e-7 * maxOf(1.0, scale / (2.0 * cnt))
        val meanEven = evenErr / cnt
        val meanOdd = oddErr / cnt
        return when {
            meanEven <= tol && meanOdd > tol -> Parity.EVEN
            meanOdd <= tol && meanEven > tol -> Parity.ODD
            meanEven <= tol -> Parity.EVEN   // Zero function, etc.: holds on both sides, reported as even
            else -> Parity.GENERAL
        }
    }

    // -----------------------------------------------------------------------
    // Adaptive Simpson (function handle version, same method as NumericOps; tolerance 1e-9, depth 40)
    // -----------------------------------------------------------------------

    /** Subdivide into panels first then run adaptive Simpson on each (anti-oscillation aliasing) */
    private fun integratePanels(g: (Double) -> Double?, a: Double, b: Double, tol: Double, panels: Int): Double {
        val m = maxOf(1, panels)
        if (m == 1) return integrate(g, a, b, tol)
        val h = (b - a) / m
        var sum = 0.0
        var j = 0
        while (j < m) {
            sum += integrate(g, a + j * h, a + (j + 1) * h, tol / m)
            j++
        }
        return sum
    }

    private fun integrate(g: (Double) -> Double?, a: Double, b: Double, tol: Double): Double {
        val fa = g(a) ?: throw NumericError("Integration endpoint undefined")
        val fb = g(b) ?: throw NumericError("Integration endpoint undefined")
        val fm = g((a + b) / 2.0) ?: throw NumericError("Undefined in integration interval")
        val whole = (b - a) / 6.0 * (fa + 4.0 * fm + fb)
        val r = adaptive(g, a, b, tol, whole, fa, fm, fb, 40)
        if (r.isNaN() || r.isInfinite()) throw NumericError("Integration does not converge")
        return r
    }

    private fun adaptive(
        g: (Double) -> Double?,
        a: Double, b: Double, eps: Double, whole: Double,
        fa: Double, fm: Double, fb: Double, depth: Int,
    ): Double {
        val m = (a + b) / 2.0
        val lm = (a + m) / 2.0
        val rm = (m + b) / 2.0
        val flm = g(lm) ?: throw NumericError("Undefined in integration interval")
        val frm = g(rm) ?: throw NumericError("Undefined in integration interval")
        val left = (m - a) / 6.0 * (fa + 4.0 * flm + fm)
        val right = (b - m) / 6.0 * (fm + 4.0 * frm + fb)
        val delta = left + right - whole
        if (depth <= 0 || abs(delta) <= 15.0 * eps) {
            return left + right + delta / 15.0
        }
        return adaptive(g, a, m, eps / 2.0, left, fa, flm, fm, depth - 1) +
            adaptive(g, m, b, eps / 2.0, right, fm, frm, fb, depth - 1)
    }

    // -----------------------------------------------------------------------
    // Output
    // -----------------------------------------------------------------------

    private fun fmt(v: Double): String = CalcEngine.format(v, 10, null)

    /** Multi-line report (shared by main line result area / MODE interface) */
    @JvmStatic
    fun formatReport(r: Result): String {
        val sb = StringBuilder()
        sb.append("Interval [${fmt(r.a)}, ${fmt(r.b)}], T = ${fmt(r.period)}, ω = ${fmt(r.omega)}; ${r.parity.label}\n")
        sb.append("a0 = ${fmt(r.a0)} (constant term a0/2 = ${fmt(r.a0 / 2.0)})\n")
        val anList = (1..r.n).joinToString("  ") { "a$it=${fmt(r.an[it])}" }
        val bnList = (1..r.n).joinToString("  ") { "b$it=${fmt(r.bn[it])}" }
        sb.append("an: $anList\n")
        when (r.parity) {
            Parity.EVEN -> sb.append("bn: all 0 (even function)\n")
            Parity.ODD -> sb.append("an/a0: all 0 (odd function)\nbn: $bnList\n")
            Parity.GENERAL -> sb.append("bn: $bnList\n")
        }
        sb.append("S${r.n}(x) = ${partialSumText(r)}")
        return sb.toString()
    }

    /** Readable text of partial sum: 3.289868134 − 4·cos(x) + cos(2x) − … */
    @JvmStatic
    fun partialSumText(r: Result): String {
        val sb = StringBuilder()
        sb.append(fmt(r.a0 / 2.0))
        for (k in 1..r.n) {
            appendTerm(sb, r.an[k], "cos", k, r.omega)
            appendTerm(sb, r.bn[k], "sin", k, r.omega)
        }
        return sb.toString()
    }

    private fun appendTerm(sb: StringBuilder, coef: Double, fn: String, k: Int, omega: Double) {
        if (coef == 0.0) return
        val sign = if (coef < 0.0) " − " else " + "
        val mag = abs(coef)
        val arg = if (abs(omega - 1.0) < 1e-12) {
            if (k == 1) "x" else "${k}x"
        } else {
            "${fmt(k * omega)}x"
        }
        val coefText = if (mag == 1.0) "" else fmt(mag) + "·"
        sb.append(sign).append(coefText).append(fn).append('(').append(arg).append(')')
    }

    /**
     * Evaluatable expression of partial sum (can be pasted back to main line / plotted;
     * internal trig understood in radians, use RAD mode or watch units if main line is DEG/GRAD).
     */
    @JvmStatic
    fun partialSumExpr(r: Result): String {
        val sb = StringBuilder()
        sb.append(CalcEngine.literal(r.a0 / 2.0, 12))
        for (k in 1..r.n) {
            appendExprTerm(sb, r.an[k], "cos", k, r.omega)
            appendExprTerm(sb, r.bn[k], "sin", k, r.omega)
        }
        return sb.toString()
    }

    private fun appendExprTerm(sb: StringBuilder, coef: Double, fn: String, k: Int, omega: Double) {
        if (coef == 0.0) return
        val arg = if (abs(omega - 1.0) < 1e-12) {
            if (k == 1) "x" else "$k*x"
        } else {
            "${CalcEngine.literal(k * omega, 12)}*x"
        }
        sb.append(if (coef < 0.0) "-" else "+")
        sb.append('(').append(CalcEngine.literal(abs(coef), 12)).append(')')
        sb.append('*').append(fn).append('(').append(arg).append(')')
    }
}
