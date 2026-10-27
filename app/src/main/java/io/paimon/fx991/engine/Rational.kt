package io.paimon.fx991.engine

import java.math.BigInteger
import kotlin.math.abs
import kotlin.math.floor

/** Math ERROR (domain error, division by zero, overflow, etc.) */
/**
 * Exact rational number: numerator / denominator are BigInteger, always kept irreducible with positive denominator.
 *
 * v2 numeric core is "dual-track":
 *   - Exact track Rational -- input decimals are converted on the spot to fractions; addition, subtraction, multiplication, division, integer powers, and perfect roots √ are exact throughout
 *   - Floating track Double -- falls back only for irrational operations (sin/log/imperfect √ ...)
 */
class Rational private constructor(
    val num: BigInteger,
    val den: BigInteger,
) : Comparable<Rational> {

    companion object {
        private val MAX_BITS = 128   // Numerator/denominator exceeds limit -> falls back to floating point (same as physical calculator)

        val ZERO = Rational(BigInteger.ZERO, BigInteger.ONE)
        val ONE = Rational(BigInteger.ONE, BigInteger.ONE)

        fun of(n: BigInteger, d: BigInteger): Rational {
            if (d == BigInteger.ZERO) throw CalcMathError("Math ERROR")
            var nn = n
            var dd = d
            if (dd.signum() < 0) {
                nn = nn.negate()
                dd = dd.negate()
            }
            val g = nn.gcd(dd)
            if (g > BigInteger.ONE) {
                nn /= g
                dd /= g
            }
            return Rational(nn, dd)
        }

        fun of(n: Long, d: Long = 1L): Rational =
            of(BigInteger.valueOf(n), BigInteger.valueOf(d))

        fun ofDouble(v: Double): Rational? {
            if (v.isNaN() || v.isInfinite()) return null
            if (v == 0.0) return ZERO
            // Decimal expansion accurate to 10 significant digits (matching reference machine)
            val bd = java.math.BigDecimal(v)
            return fromDecimal(bd.toPlainString())
        }

        /** "589.3" -> 5893/10; "1.52" -> 152/100 -> 38/25; returns null if invalid */
        fun fromDecimal(s: String): Rational? {
            if (s.isEmpty()) return null
            val dot = s.indexOf('.')
            return if (dot < 0) {
                val n = s.toBigIntegerOrNull() ?: return null
                of(n, BigInteger.ONE)
            } else {
                val intPart = s.substring(0, dot).ifEmpty { "0" }
                val fracPart = s.substring(dot + 1)
                if (fracPart.any { !it.isDigit() }) return null
                val digits = fracPart.length
                val whole = (intPart + fracPart).toBigIntegerOrNull() ?: return null
                of(whole, BigInteger.TEN.pow(digits))
            }
        }

        /**
         * Continued fraction approximation: reconstruct floating-point value to the "closest" small-denominator fraction.
         * Used to convert numerical solutions (such as root-finding results) back to exact fractions; returns null if not found.
         */
        fun approx(v: Double, maxDen: Long): Rational? {
            if (v.isNaN() || v.isInfinite()) return null
            if (v == 0.0) return ZERO
            val sign = if (v < 0.0) -1L else 1L
            val target = abs(v)
            var h0 = 0L
            var h1 = 1L
            var k0 = 1L
            var k1 = 0L
            var b = target
            var iter = 0
            while (iter < 64) {
                iter++
                val a = floor(b).toLong()
                if (a > 1_000_000_000_000L) break
                val h2 = a * h1 + h0
                val k2 = a * k1 + k0
                if (k2 > maxDen || k2 <= 0L) break
                h0 = h1
                h1 = h2
                k0 = k1
                k1 = k2
                val frac = b - a
                if (frac < 1e-15) break
                b = 1.0 / frac
            }
            if (k1 == 0L) return null
            return of(BigInteger.valueOf(sign * h1), BigInteger.valueOf(k1))
        }
    }

    val isInteger: Boolean get() = den == BigInteger.ONE
    val isZero: Boolean get() = num.signum() == 0
    val signum: Int get() = num.signum()

    /** Scale too large -> caller should fall back to floating point */
    val tooLarge: Boolean
        get() = num.bitLength() > MAX_BITS || den.bitLength() > MAX_BITS

    fun negate(): Rational = Rational(num.negate(), den)
    fun abs(): Rational = if (num.signum() < 0) negate() else this

    fun plus(o: Rational): Rational = of(num * o.den + o.num * den, den * o.den)
    fun minus(o: Rational): Rational = of(num * o.den - o.num * den, den * o.den)
    fun times(o: Rational): Rational = of(num * o.num, den * o.den)
    fun div(o: Rational): Rational {
        if (o.isZero) throw CalcMathError("Math ERROR")
        return of(num * o.den, den * o.num)
    }

    /** Integer power (including negative exponents) */
    fun pow(exp: Int): Rational {
        if (exp == 0) return ONE
        if (isZero && exp < 0) throw CalcMathError("Math ERROR")
        return if (exp > 0) of(num.pow(exp), den.pow(exp))
        else of(den.pow(-exp), num.pow(-exp))
    }

    /** Exact square root when a perfect square, otherwise null (delegated to floating-point track) */
    fun sqrtExact(): Rational? {
        if (num.signum() < 0) throw CalcMathError("Math ERROR")
        val rn = num.sqrt()
        if (rn * rn != num) return null
        val rd = den.sqrt()
        if (rd * rd != den) return null
        return of(rn, rd)
    }

    /**
     * nth root: exact rational if it extracts completely, otherwise null.
     * Negative numbers only have real roots when n is odd (matching reference machine).
     */
    fun nthRootExact(n: Int): Rational? {
        if (n <= 0) return null
        val negative = num.signum() < 0
        if (negative && n % 2 == 0) throw CalcMathError("Math ERROR")
        val a = if (negative) num.negate() else num
        val ra = intRoot(a, n) ?: return null
        val rd = intRoot(den, n) ?: return null
        val r = of(ra, rd)
        return if (negative) r.negate() else r
    }

    private fun intRoot(v: BigInteger, n: Int): BigInteger? {
        if (v.signum() < 0) return null
        if (v <= BigInteger.ONE) return v
        var x = BigInteger.ONE.shiftLeft(v.bitLength() / n + 1)
        val nn = BigInteger.valueOf(n.toLong())
        var guard = 0
        while (guard++ < 200) {
            val y = (BigInteger.valueOf((n - 1).toLong()) * x + v / x.pow(n - 1)) / nn
            if (y >= x) break
            x = y
        }
        while (x.pow(n) > v) x -= BigInteger.ONE
        while ((x + BigInteger.ONE).pow(n) <= v) x += BigInteger.ONE
        return if (x.pow(n) == v) x else null
    }

    override fun compareTo(o: Rational): Int = (num * o.den).compareTo(o.num * den)

    fun toDouble(): Double {
        if (isInteger) return num.toDouble()
        // Use BigDecimal division to ensure 15 digits of precision
        return java.math.BigDecimal(num).divide(
            java.math.BigDecimal(den),
            java.math.MathContext(20, java.math.RoundingMode.HALF_UP)
        ).toDouble()
    }

    fun toBigDecimalString(scale: Int): String =
        java.math.BigDecimal(num).divide(
            java.math.BigDecimal(den), scale, java.math.RoundingMode.HALF_UP
        ).toPlainString()

    /** Mixed fraction: integer part + remainder/denominator */
    fun toMixed(): Triple<BigInteger, BigInteger, BigInteger> {
        if (isInteger) return Triple(num, BigInteger.ZERO, BigInteger.ONE)
        val q = num.divide(den)
        val r = num.remainder(den)
        return Triple(q, r.abs(), den)
    }

    override fun equals(other: Any?): Boolean =
        other is Rational && num == other.num && den == other.den

    override fun hashCode(): Int = 31 * num.hashCode() + den.hashCode()

    override fun toString(): String = if (isInteger) num.toString() else "$num/$den"
}

/** Dual-track value: exact if possible, otherwise floating point */
sealed interface Value {
    fun toDouble(): Double
    val exact: Rational?

    data class Exact(val r: Rational) : Value {
        override fun toDouble(): Double = r.toDouble()
        override val exact: Rational get() = r
    }

    data class Floating(val v: Double) : Value {
        override fun toDouble(): Double = v
        override val exact: Rational? get() = null
    }

    companion object {
        fun of(r: Rational): Value = if (r.tooLarge) Floating(r.toDouble()) else Exact(r)
        fun of(v: Double): Value = Floating(v)
        fun of(v: Double, lit: String?): Value {
            if (lit != null) {
                val r = Rational.fromDecimal(lit)
                if (r != null) return of(r)
            }
            return Floating(v)
        }
    }
}
