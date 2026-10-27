package io.paimon.fx991.engine

import kotlin.math.abs
import kotlin.math.sqrt

// ---------------------------------------------------------------------------
// Batch C: Statistics and regression (single variable / two variables; pure Kotlin, testable on JVM)
// ---------------------------------------------------------------------------

/** Single-variable statistics result */
class OneVarStat(
    val n: Int,
    val sum: Double,
    val sumSq: Double,
    val mean: Double,
    /** Population standard deviation σ (denominator n) */
    val popSigma: Double,
    /** Sample standard deviation s (denominator n−1) */
    val sampleS: Double,
    val min: Double,
    val max: Double,
    val median: Double,
)

/** Two-variable linear regression y = a + b·x and correlation coefficient r */
class LinReg(
    val a: Double,
    val b: Double,
    val r: Double,
    val n: Int,
)

object StatOps {

    /** Single-variable statistics; throws on empty data */
    fun oneVar(xs: List<Double>): OneVarStat {
        if (xs.isEmpty()) throw NumericError("No data")
        val n = xs.size
        var sum = 0.0
        var sumSq = 0.0
        for (v in xs) {
            sum += v
            sumSq += v * v
        }
        val mean = sum / n
        var dev = 0.0
        for (v in xs) dev += (v - mean) * (v - mean)
        val pop = sqrt(dev / n)
        val samp = if (n >= 2) sqrt(dev / (n - 1)) else Double.NaN
        val sorted = xs.sorted()
        val median = if (n % 2 == 1) sorted[n / 2]
        else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
        return OneVarStat(n, sum, sumSq, mean, pop, samp, sorted.first(), sorted.last(), median)
    }

    /** Two-variable linear regression y = a + b·x */
    fun linearRegression(xs: List<Double>, ys: List<Double>): LinReg {
        if (xs.isEmpty() || xs.size != ys.size) throw NumericError("Two-variable data lengths do not match")
        val n = xs.size
        if (n < 2) throw NumericError("Linear regression requires at least 2 pairs of data")
        var sx = 0.0
        var sy = 0.0
        var sxy = 0.0
        var sxx = 0.0
        var syy = 0.0
        for (i in 0 until n) {
            sx += xs[i]
            sy += ys[i]
            sxy += xs[i] * ys[i]
            sxx += xs[i] * xs[i]
            syy += ys[i] * ys[i]
        }
        val denom = n * sxx - sx * sx
        if (abs(denom) < 1e-300) throw NumericError("All x values are identical, cannot perform regression")
        val b = (n * sxy - sx * sy) / denom
        val a = (sy - b * sx) / n
        val rDenom = sqrt(denom * (n * syy - sy * sy))
        val r = if (rDenom < 1e-300) 0.0 else (n * sxy - sx * sy) / rDenom
        return LinReg(a, b, r, n)
    }
}
