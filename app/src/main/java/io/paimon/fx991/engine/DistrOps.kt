package io.paimon.fx991.engine

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

// ---------------------------------------------------------------------------
// Batch C: Probability distributions (normal / binomial / Poisson; pure Kotlin, JVM regressible)
//
// No 3rd-party statistics libraries: erf and lnΓ error functions are handwritten.
// ---------------------------------------------------------------------------

/** Three-part probability of normal distribution: P(left tail) / Q(right tail) / R(two-sided center) */
class NormalProb(
    /** P = P(X < x) (left tail) */
    val p: Double,
    /** Q = P(X > x) (right tail) = 1 − P */
    val q: Double,
    /** R = P(μ−|x−μ| < X < μ+|x−μ|) (symmetric interval centered at mean) */
    val r: Double,
)

object DistrOps {

    // -----------------------------------------------------------------------
    // Error function (Abramowitz & Stegun 7.1.26, |error| ≤ 1.5e-7)
    // -----------------------------------------------------------------------

    fun erf(x: Double): Double {
        val sign = if (x < 0.0) -1.0 else 1.0
        val a = abs(x)
        val t = 1.0 / (1.0 + 0.3275911 * a)
        val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t
            + 0.254829592) * t * exp(-a * a)
        return sign * y
    }

    /** Standard normal cumulative distribution Φ(t) = P(Z < t) */
    fun normCdf(t: Double): Double = 0.5 * (1.0 + erf(t / sqrt(2.0)))

    /** Standard normal probability density φ(t) */
    fun normPdf(t: Double): Double = exp(-t * t / 2.0) / sqrt(2.0 * Math.PI)

    /**
     * Standard normal quantile Φ⁻¹(p) (inverse normal).
     * Acklam rational approximation + one Halley correction, |error| ≲ 1e-12.
     */
    fun invNormCdf(p: Double): Double {
        if (p <= 0.0 || p >= 1.0) throw NumericError("p must be between (0, 1)")
        val a = doubleArrayOf(
            -3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02,
            1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00,
        )
        val b = doubleArrayOf(
            -5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02,
            6.680131188771972e+01, -1.328068155288572e+01,
        )
        val c = doubleArrayOf(
            -7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00,
            -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00,
        )
        val d = doubleArrayOf(
            7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00,
            3.754408661907416e+00,
        )
        val plow = 0.02425
        val phigh = 1.0 - plow
        var x: Double
        if (p < plow) {
            val q = sqrt(-2.0 * ln(p))
            x = (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) /
                ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1.0)
        } else if (p <= phigh) {
            val q = p - 0.5
            val r = q * q
            x = (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q /
                (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1.0)
        } else {
            val q = sqrt(-2.0 * ln(1.0 - p))
            x = -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) /
                ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1.0)
        }
        // Halley correction (one iteration is sufficient)
        val e = normCdf(x) - p
        val u = e * sqrt(2.0 * Math.PI) * exp(x * x / 2.0)
        return x - u / (1.0 + x * u / 2.0)
    }

    // -----------------------------------------------------------------------
    // Normal distribution: calculate P / Q / R given x (and μ, σ)
    // -----------------------------------------------------------------------

    /** Calculates three-part probability using standardized value t = (x − μ) / σ */
    fun normalProb(t: Double): NormalProb {
        val p = normCdf(t)
        val q = 1.0 - p
        val r = normCdf(abs(t)) - normCdf(-abs(t))
        return NormalProb(p, q, r)
    }

    /** Calculates P / Q / R given μ / σ / x */
    fun normal(x: Double, mu: Double, sigma: Double): NormalProb {
        if (sigma <= 0.0) throw NumericError("Standard deviation must be positive")
        return normalProb((x - mu) / sigma)
    }

    // -----------------------------------------------------------------------
    // Binomial distribution X ~ B(n, p)
    // -----------------------------------------------------------------------

    /** Binomial distribution probability P(X = k) */
    fun binomialPdf(n: Int, k: Int, p: Double): Double {
        if (n < 0) throw NumericError("n must be >= 0")
        if (k < 0 || k > n) return 0.0
        if (p < 0.0 || p > 1.0) throw NumericError("Probability p must be between [0, 1]")
        if (p == 0.0) return if (k == 0) 1.0 else 0.0
        if (p == 1.0) return if (k == n) 1.0 else 0.0
        val logC = lnGamma(n + 1.0) - lnGamma(k + 1.0) - lnGamma(n - k + 1.0)
        return exp(logC + k * ln(p) + (n - k) * ln(1.0 - p))
    }

    /** Binomial distribution cumulative P(X ≤ k) */
    fun binomialCdf(n: Int, k: Int, p: Double): Double {
        if (k < 0) return 0.0
        if (k >= n) return 1.0
        var s = 0.0
        for (i in 0..k) s += binomialPdf(n, i, p)
        return s.coerceIn(0.0, 1.0)
    }

    // -----------------------------------------------------------------------
    // Poisson distribution X ~ Poisson(λ)
    // -----------------------------------------------------------------------

    /** Poisson distribution probability P(X = k) */
    fun poissonPdf(lambda: Double, k: Int): Double {
        if (lambda <= 0.0) throw NumericError("λ must be positive")
        if (k < 0) return 0.0
        return exp(-lambda + k * ln(lambda) - lnGamma(k + 1.0))
    }

    /** Poisson distribution cumulative P(X ≤ k) */
    fun poissonCdf(lambda: Double, k: Int): Double {
        if (k < 0) return 0.0
        var s = 0.0
        for (i in 0..k) s += poissonPdf(lambda, i)
        return s.coerceIn(0.0, 1.0)
    }

    // -----------------------------------------------------------------------
    // lnΓ (Lanczos approximation, g = 7, n = 9)
    // -----------------------------------------------------------------------

    private val LANCZOS = doubleArrayOf(
        0.99999999999980993,
        676.5203681218851,
        -1259.1392167224028,
        771.32342877765313,
        -176.61502916214059,
        12.507343278686905,
        -0.13857109526572012,
        9.9843695780195716e-6,
        1.5056327351493116e-7,
    )

    fun lnGamma(x: Double): Double {
        if (x < 0.5) {
            // Reflection formula
            return ln(Math.PI / kotlin.math.sin(Math.PI * x)) - lnGamma(1.0 - x)
        }
        val z = x - 1.0
        var a = LANCZOS[0]
        val t = z + 7.5
        for (i in 1 until LANCZOS.size) a += LANCZOS[i] / (z + i)
        return 0.5 * ln(2.0 * Math.PI) + (z + 0.5) * ln(t) - t + ln(a)
    }
}
