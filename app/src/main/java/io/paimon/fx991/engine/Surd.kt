package io.paimon.fx991.engine

/**
 * Surd (radical) exact arithmetic: coef · √radicand, where radicand is a square-free positive integer.
 *
 * Corresponds to Sumi's core/value/surd_ops.dart concept (rewritten per design, not ported line-by-line):
 *   - √8        → 2√2            (extract square factor)
 *   - √2 + √8   → 3√2            (combine like radicals)
 *   - √2 · √8   → 4              (multiply within radical then simplify)
 *   - 1/√2      → √2/2           (rationalize denominator)
 *   - √(a/b)    → √a/√b          (fraction radicand)
 *
 * All coefficients are carried using Rational to maintain exactness; radicand=1 indicates degeneration to a rational number.
 */
object Surd {

    /** coef * √radicand */
    data class Radical(val coef: Rational, val radicand: Int) {
        val isRational: Boolean get() = radicand == 1
        fun toRational(): Rational? = if (isRational) coef else null
        fun toDouble(): Double = coef.toDouble() * Math.sqrt(radicand.toDouble())
    }

    /** Factor integer into square factor * square-free factor */
    fun squareFree(n: Int): Pair<Int, Int> {
        require(n > 0)
        var rest = n
        var outside = 1
        var p = 2
        while (p * p <= rest) {
            while (rest % (p * p) == 0) {
                rest /= (p * p)
                outside *= p
            }
            p++
        }
        return outside to rest
    }

    /** Simplify √n (n ≥ 0); returns radicand=1 when n is a perfect square */
    fun sqrt(n: Long): Radical {
        require(n >= 0) { "Negative numbers have no real square root" }
        return sqrt(Rational.of(n))
    }

    /** Simplify √r (rational r ≥ 0): √(a/b) = √(a·b)/b */
    fun sqrt(r: Rational): Radical {
        if (r.signum < 0) throw CalcMathError("Math ERROR")
        if (r.isZero) return Radical(Rational.ZERO, 1)
        // √(a/b) = √(a·b) / b -> first convert to integer radicand
        val a = r.num
        val b = r.den
        val under = a * b           // BigInteger
        if (under.bitLength() > 62) {
            // Scale too large: delegate to floating-point track
            return Radical(Rational.ZERO, 1)
        }
        val n = under.toLong()
        val (outside, rad) = squareFreeLong(n)
        val coef = Rational.of(outside.toBigInteger(), b)
        // Factor radicand once more (rad is already square-free; b's square part has been merged into outside)
        return Radical(coef, rad.toInt())
    }

    private fun squareFreeLong(n: Long): Pair<Long, Long> {
        var rest = n
        var outside = 1L
        var p = 2L
        while (p * p <= rest) {
            while (rest % (p * p) == 0L) {
                rest /= (p * p)
                outside *= p
            }
            p++
        }
        return outside to rest
    }

    /** Add like radicals; returns null if not like radicals (caller falls back to floating point or keeps sum) */
    fun add(a: Radical, b: Radical): Radical? {
        if (a.radicand != b.radicand) return null
        return normalize(Radical(a.coef.plus(b.coef), a.radicand))
    }

    fun sub(a: Radical, b: Radical): Radical? {
        if (a.radicand != b.radicand) return null
        return normalize(Radical(a.coef.minus(b.coef), a.radicand))
    }

    fun mul(a: Radical, b: Radical): Radical {
        val under = a.radicand.toLong() * b.radicand.toLong()
        val (outside, rad) = squareFreeLong(under)
        val coef = a.coef.times(b.coef).times(Rational.of(outside))
        return normalize(Radical(coef, rad.toInt()))
    }

    /** a / b: b must be non-zero; rationalizes denominator */
    fun div(a: Radical, b: Radical): Radical {
        if (b.coef.isZero) throw CalcMathError("Math ERROR")
        // a/b = (a.coef/b.coef) * √(a.rad/b.rad)
        // First rationalize 1/√b: 1/√q = √q/q
        val invCoef = a.coef.div(b.coef)
        val under = a.radicand.toLong() * b.radicand.toLong()
        val (outside, rad) = squareFreeLong(under)
        // Multiply denominator by b.radicand after expanding √(a/b)
        val coef = invCoef.div(Rational.of(b.radicand.toLong())).times(Rational.of(outside))
        return normalize(Radical(coef, rad.toInt()))
    }

    /** Degenerates to rational when coefficient is 0 or radicand=1 */
    fun normalize(r: Radical): Radical {
        if (r.coef.isZero) return Radical(Rational.ZERO, 1)
        if (r.radicand == 1) return Radical(r.coef, 1)
        return r
    }

    /** Display: 3√2 / -√2 / √2/2 / 4 */
    fun format(r: Radical): String {
        val n = normalize(r)
        if (n.isRational) return n.coef.toString()
        val absCoef = n.coef.abs()
        val sign = if (n.coef.signum < 0) "-" else ""
        val numPart =
            if (absCoef.num == java.math.BigInteger.ONE) "$sign√${n.radicand}"
            else "$sign${absCoef.num}√${n.radicand}"
        return if (absCoef.isInteger) numPart else "$numPart/${absCoef.den}"
    }
}
