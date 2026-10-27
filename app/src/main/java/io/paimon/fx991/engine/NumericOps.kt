package io.paimon.fx991.engine

import java.math.BigInteger
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sin

/**
 * Numerical algorithms (Batch A): Numerical root finding / definite integrals / numerical derivatives / summation / limits / permutations & combinations / degrees-minutes-seconds / polar coordinates.
 *
 * All handwritten with zero third-party dependencies. The independent variable in expressions is uniformly `x` (using the same
 * `CalcEngine.evaluateWith` pipeline as differential equations).
 */

/** Numerical algorithm error (root-finding failure / non-convergence / parameter out of range, etc.) */
class NumericError(message: String) : Exception(message)

/** Numerical root-finding result; `exact` is non-null when recognizable as an exact rational number */
class RootResult(
    val root: Double,
    val exact: Rational?,
    val iterations: Int,
    val method: String,
)

/** Limit result: left and right limits are provided separately; `equal` is false when they are not equal */
class LimitResult(val left: Double, val right: Double) {
    val equal: Boolean
        get() = abs(left - right) <= 1e-7 * (1.0 + maxOf(abs(left), abs(right)))

    /** Returns the limit value when two-sided limits are equal, otherwise NaN */
    fun value(): Double = if (equal) (left + right) / 2.0 else Double.NaN
}

/** Degrees, minutes, and seconds (deg / min / sec are all non-negative magnitudes; sign is stored in negative to avoid losing sign on -0) */
class Dms(val negative: Boolean, val deg: Long, val min: Long, val sec: Double) {
    override fun toString(): String {
        val sign = if (negative) "\u2212" else ""
        return "$sign$deg\u00B0$min\u2032${trim(sec)}\u2033"
    }

    private fun trim(v: Double): String {
        val r = round(v * 1e4) / 1e4
        return if (r == floor(r)) r.toLong().toString() else r.toString()
    }
}

/** Cartesian complex number (used only to carry the result of a∠θ, without full complex arithmetic) */
class ComplexRect(val re: Double, val im: Double)

/** Polar coordinates */
class PolarPair(val r: Double, val theta: Double)

object NumericOps {

    const val MAX_TERMS = 1_000_000L
    private const val MAX_BISECT_SCAN = 400

    // -----------------------------------------------------------------------
    // Common evaluation entry point
    // -----------------------------------------------------------------------

    /** Returns null on failure (undefined / non-finite) */
    private fun safeEval(expr: String, mode: AngleMode, x: Double): Double? = try {
        val v = CalcEngine.evaluateWith(expr, mode, x, 0.0)
        if (v.isNaN() || v.isInfinite()) null else v
    } catch (_: Exception) {
        null
    }

    private fun evalAt(expr: String, mode: AngleMode, x: Double): Double =
        CalcEngine.evaluateWith(expr, mode, x, 0.0)

    // -----------------------------------------------------------------------
    // Numerical derivative: central difference + Richardson extrapolation
    // -----------------------------------------------------------------------

    fun derivative(expr: String, mode: AngleMode, x: Double): Double {
        val h0 = 1e-3 * maxOf(1.0, abs(x))
        fun d(h: Double): Double {
            val a = evalAt(expr, mode, x + h)
            val b = evalAt(expr, mode, x - h)
            return (a - b) / (2.0 * h)
        }
        val d1 = d(h0)
        val d2 = d(h0 / 2.0)
        val r = d2 + (d2 - d1) / 3.0        // 4th order extrapolation
        if (r.isNaN() || r.isInfinite()) throw NumericError("Derivative calculation failed")
        return r
    }

    // -----------------------------------------------------------------------
    // Numerical definite integral: adaptive Simpson's rule, tolerance can be explicitly configured
    // -----------------------------------------------------------------------

    fun integrate(expr: String, mode: AngleMode, a: Double, b: Double, tol: Double): Double {
        if (tol <= 0.0 || tol.isNaN()) throw NumericError("Tolerance must be positive")
        if (a == b) return 0.0
        val fa = safeEval(expr, mode, a) ?: throw NumericError("Integral endpoint undefined")
        val fb = safeEval(expr, mode, b) ?: throw NumericError("Integral endpoint undefined")
        val fm = safeEval(expr, mode, (a + b) / 2.0) ?: throw NumericError("Undefined within integral interval")
        val whole = (b - a) / 6.0 * (fa + 4.0 * fm + fb)
        val r = adaptive(expr, mode, a, b, tol, whole, fa, fm, fb, 60)
        if (r.isNaN() || r.isInfinite()) throw NumericError("Integral does not converge")
        return r
    }

    private fun adaptive(
        expr: String,
        mode: AngleMode,
        a: Double,
        b: Double,
        eps: Double,
        whole: Double,
        fa: Double,
        fm: Double,
        fb: Double,
        depth: Int,
    ): Double {
        val m = (a + b) / 2.0
        val lm = (a + m) / 2.0
        val rm = (m + b) / 2.0
        val flm = evalAt(expr, mode, lm)
        val frm = evalAt(expr, mode, rm)
        val left = (m - a) / 6.0 * (fa + 4.0 * flm + fm)
        val right = (b - m) / 6.0 * (fm + 4.0 * frm + fb)
        val delta = left + right - whole
        if (depth <= 0 || abs(delta) <= 15.0 * eps) {
            return left + right + delta / 15.0
        }
        return adaptive(expr, mode, a, m, eps / 2.0, left, fa, flm, fm, depth - 1) +
            adaptive(expr, mode, m, b, eps / 2.0, right, fm, frm, fb, depth - 1)
    }

    // -----------------------------------------------------------------------
    // Summation: Σ(f(x), x, a, b), where a and b are integers
    // -----------------------------------------------------------------------

    fun summation(expr: String, mode: AngleMode, a: Long, b: Long): Double {
        if (a > b) throw NumericError("Summation lower bound cannot be greater than upper bound")
        if (b - a + 1 > MAX_TERMS) throw NumericError("Too many summation terms (> $MAX_TERMS)")
        var s = 0.0
        var k = a
        while (k <= b) {
            s += evalAt(expr, mode, k.toDouble())
            k++
        }
        if (s.isNaN() || s.isInfinite()) throw NumericError("Summation diverges")
        return s
    }

    // -----------------------------------------------------------------------
    // Numerical equation root finding: Newton's method primarily, falling back to bisection on non-convergence / degenerate derivative
    // -----------------------------------------------------------------------

    fun solveRoot(expr: String, mode: AngleMode, guess: Double): RootResult {
        val newton = tryNewton(expr, mode, guess)
        if (newton != null) {
            val (x, it) = newton
            return RootResult(x, rationalize(expr, mode, x), it, "Newton's method")
        }
        val bisect = bisect(expr, mode, guess)
            ?: throw NumericError("Root finding failed: no sign change interval found, please try another initial guess")
        val (x, it) = bisect
        return RootResult(x, rationalize(expr, mode, x), it, "Bisection method")
    }

    private fun tryNewton(expr: String, mode: AngleMode, guess: Double): Pair<Double, Int>? {
        var x = guess
        var k = 0
        while (k < 100) {
            k++
            val fx = safeEval(expr, mode, x) ?: return null
            if (abs(fx) < 1e-13) return x to k
            val dfx = try {
                derivative(expr, mode, x)
            } catch (_: Exception) {
                return null
            }
            if (dfx == 0.0 || dfx.isNaN() || dfx.isInfinite()) return null
            val nx = x - fx / dfx
            if (nx.isNaN() || nx.isInfinite() || abs(nx) > 1e100) return null
            if (abs(nx - x) < 1e-14 * maxOf(1.0, abs(nx))) {
                return if (abs(safeEval(expr, mode, nx) ?: Double.MAX_VALUE) < 1e-6) nx to k else null
            }
            x = nx
        }
        return null
    }

    private fun bisect(expr: String, mode: AngleMode, guess: Double): Pair<Double, Int>? {
        // Scan centered at initial guess to find the sign change interval closest to initial guess
        val span = 1e3
        var bestL = Double.NaN
        var bestR = Double.NaN
        var bestD = Double.MAX_VALUE
        var prevX = guess - span
        var prevF = safeEval(expr, mode, prevX)
        var i = 1
        while (i <= MAX_BISECT_SCAN) {
            val xcur = guess - span + 2.0 * span * i / MAX_BISECT_SCAN
            val fcur = safeEval(expr, mode, xcur)
            if (prevF != null && fcur != null && prevF != 0.0 && (prevF > 0.0) != (fcur > 0.0)) {
                val mid = (prevX + xcur) / 2.0
                val dist = abs(mid - guess)
                if (dist < bestD) {
                    bestD = dist
                    bestL = prevX
                    bestR = xcur
                }
            }
            prevX = xcur
            prevF = fcur
            i++
        }
        if (bestL.isNaN()) return null
        var lo = bestL
        var hi = bestR
        var flo = evalAt(expr, mode, lo)
        var mid = (lo + hi) / 2.0
        var it = 0
        while (it < 200) {
            it++
            mid = (lo + hi) / 2.0
            val fm = safeEval(expr, mode, mid) ?: return null
            if (abs(fm) < 1e-15 || (hi - lo) < 1e-15 * (1.0 + abs(mid))) break
            if ((flo > 0.0) != (fm > 0.0)) hi = mid else { lo = mid; flo = fm }
        }
        return mid to it
    }

    /** If the root can be expressed as an exact fraction with a small denominator and strictly passes through zero, recover it */
    private fun rationalize(expr: String, mode: AngleMode, x: Double): Rational? {
        val r = Rational.approx(x, 1000L) ?: return null
        val d = r.toDouble()
        if (abs(d - x) > 1e-9 * maxOf(1.0, abs(x))) return null
        val fv = safeEval(expr, mode, d) ?: return null
        return if (abs(fv) < 1e-9) r else null
    }

    // -----------------------------------------------------------------------
    // Numerical limit: h→0 extrapolation, giving both left and right limits
    // -----------------------------------------------------------------------

    fun limit(expr: String, mode: AngleMode, x0: Double): LimitResult =
        LimitResult(oneSided(expr, mode, x0, -1.0), oneSided(expr, mode, x0, 1.0))

    private fun oneSided(expr: String, mode: AngleMode, x0: Double, dir: Double): Double {
        val hs = doubleArrayOf(1e-2, 1e-3, 1e-4, 1e-5, 1e-6)
        val vals = ArrayList<Double>()
        for (h in hs) {
            val v = safeEval(expr, mode, x0 + dir * h)
            if (v != null) vals.add(v)
        }
        if (vals.isEmpty()) throw NumericError("Limit calculation failed: function is undefined near this point")
        val n = vals.size
        if (n == 1) return vals[0]
        // f(h) ≈ L + c·h, two-point extrapolation (step ratio 10: L = (10·f(h/10) − f(h))/9)
        val a = vals[n - 1]
        val b = vals[n - 2]
        val r = (10.0 * a - b) / 9.0
        return if (r.isNaN() || r.isInfinite()) vals[n - 1] else r
    }

    // -----------------------------------------------------------------------
    // Permutations and combinations: large numbers use BigInteger without overflow
    // -----------------------------------------------------------------------

    fun nPr(n: Long, r: Long): BigInteger {
        if (n < 0 || r < 0 || r > n) throw NumericError("Permutation requires n ≥ r ≥ 0")
        if (n > 1_000_000L) throw NumericError("n is too large")
        var res = BigInteger.ONE
        var i = 0L
        while (i < r) {
            res = res.multiply(BigInteger.valueOf(n - i))
            i++
        }
        return res
    }

    fun nCr(n: Long, r: Long): BigInteger {
        if (n < 0 || r < 0 || r > n) throw NumericError("Combination requires n ≥ r ≥ 0")
        val k = if (r > n - r) n - r else r
        var res = BigInteger.ONE
        var i = 1L
        while (i <= k) {
            res = res.multiply(BigInteger.valueOf(n - k + i)).divide(BigInteger.valueOf(i))
            i++
        }
        return res
    }
}

// ---------------------------------------------------------------------------
// Degrees, minutes, seconds (base-60 conversion)
// ---------------------------------------------------------------------------

object Sexagesimal {

    /** Degrees, minutes, seconds -> decimal degrees (minutes/seconds follow the sign of degrees, consistent with reference machine) */
    fun toDegrees(deg: Double, min: Double, sec: Double): Double {
        val negative = deg < 0.0 || (deg == 0.0 && (min < 0.0 || sec < 0.0))
        val mag = abs(deg) + abs(min) / 60.0 + abs(sec) / 3600.0
        return if (negative) -mag else mag
    }

    /** Decimal degrees -> degrees, minutes, seconds */
    fun fromDegrees(x: Double): Dms {
        val neg = x < 0.0
        val a = abs(x)
        var d = floor(a).toLong()
        val remMin = (a - d) * 60.0
        var m = floor(remMin).toLong()
        var s = round(((remMin - m) * 60.0) * 1e4) / 1e4
        if (s >= 60.0) {
            s -= 60.0
            m += 1
        }
        if (m >= 60L) {
            m -= 60L
            d += 1
        }
        return Dms(neg, d, m, s)
    }
}

// ---------------------------------------------------------------------------
// Polar coordinates ⇄ Cartesian coordinates (complex form)
// ---------------------------------------------------------------------------

object PolarForm {

    /** r∠θ -> Cartesian coordinates */
    fun toRect(r: Double, theta: Double, mode: AngleMode): ComplexRect {
        val t = CalcEngine.toRadians(mode, theta)
        return ComplexRect(clean(r * cos(t)), clean(r * sin(t)))
    }

    /** Cartesian coordinates -> r∠θ */
    fun toPolar(re: Double, im: Double, mode: AngleMode): PolarPair {
        val r = hypot(re, im)
        val theta = CalcEngine.fromRadians(mode, atan2(im, re))
        return PolarPair(clean(r), clean(theta))
    }

    /**
     * The polar operator `∠` has been promoted to a true operator in expression syntax (see Unified),
     * so the legacy branch of "entire string special-case parsing, at most one ∠" is no longer retained.
     */

    /** Polar form text: r∠θ */
    fun formatPolar(p: PolarPair): String =
        "${CalcEngine.format(p.r)}$ANGLE_MARK${CalcEngine.format(p.theta)}"

    private fun clean(v: Double): Double = if (abs(v) < 1e-12) 0.0 else v
}

internal const val ANGLE_MARK = "\u2220"
