package io.paimon.fx991.engine

import java.math.BigInteger
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

// ---------------------------------------------------------------------------
// Batch C: Complex number operations (pure Kotlin, no Compose dependencies → JVM regressible)
//
// Dual-track core: real / imaginary parts are each a Value (Exact(Rational) | Floating(Double)),
// so "exact when possible" holds true in the complex domain as well: rational arithmetic stays rational.
// ---------------------------------------------------------------------------

/** Common utilities for dual-track arithmetic (shared by complex / matrix / vector) */
object ExactMath {

    fun zero(): Value = Value.of(Rational.ZERO)
    fun one(): Value = Value.of(Rational.ONE)
    fun long(n: Long): Value = Value.of(Rational.of(BigInteger.valueOf(n), BigInteger.ONE))

    fun add(a: Value, b: Value): Value {
        val ea = a.exact
        val eb = b.exact
        return if (ea != null && eb != null) Value.of(ea.plus(eb))
        else Value.of(a.toDouble() + b.toDouble())
    }

    fun sub(a: Value, b: Value): Value {
        val ea = a.exact
        val eb = b.exact
        return if (ea != null && eb != null) Value.of(ea.minus(eb))
        else Value.of(a.toDouble() - b.toDouble())
    }

    fun mul(a: Value, b: Value): Value {
        val ea = a.exact
        val eb = b.exact
        return if (ea != null && eb != null) Value.of(ea.times(eb))
        else Value.of(a.toDouble() * b.toDouble())
    }

    fun div(a: Value, b: Value): Value {
        val ea = a.exact
        val eb = b.exact
        if (ea != null && eb != null) return Value.of(ea.div(eb))
        val d = b.toDouble()
        if (d == 0.0) throw CalcMathError("Math ERROR")
        return Value.of(a.toDouble() / d)
    }

    fun neg(a: Value): Value {
        val e = a.exact
        return if (e != null) Value.of(e.negate()) else Value.of(-a.toDouble())
    }

    fun isZero(a: Value): Boolean {
        val e = a.exact
        return if (e != null) e.isZero else a.toDouble() == 0.0
    }

    fun sum(list: List<Value>): Value {
        var s = zero()
        for (v in list) s = add(s, v)
        return s
    }

    /** Dual-track formatting (rational on exact track, 10 significant digits on floating track) */
    fun str(v: Value): String =
        CalcEngine.formatValue(v, false, false, 10, null, NumberNotation.NORM)
}

/** Complex number a + bi (real / imaginary parts are dual-track Values) */
class ComplexNum(val re: Value, val im: Value) {

    /** Both real and imaginary parts are on the exact track */
    val exact: Boolean get() = re.exact != null && im.exact != null

    fun plus(o: ComplexNum): ComplexNum =
        ComplexNum(ExactMath.add(re, o.re), ExactMath.add(im, o.im))

    fun minus(o: ComplexNum): ComplexNum =
        ComplexNum(ExactMath.sub(re, o.re), ExactMath.sub(im, o.im))

    fun times(o: ComplexNum): ComplexNum = ComplexNum(
        ExactMath.sub(ExactMath.mul(re, o.re), ExactMath.mul(im, o.im)),
        ExactMath.add(ExactMath.mul(re, o.im), ExactMath.mul(im, o.re)),
    )

    fun div(o: ComplexNum): ComplexNum {
        val d = ExactMath.add(ExactMath.mul(o.re, o.re), ExactMath.mul(o.im, o.im))
        if (ExactMath.isZero(d)) throw CalcMathError("Math ERROR")
        val nr = ExactMath.add(ExactMath.mul(re, o.re), ExactMath.mul(im, o.im))
        val ni = ExactMath.sub(ExactMath.mul(im, o.re), ExactMath.mul(re, o.im))
        return ComplexNum(ExactMath.div(nr, d), ExactMath.div(ni, d))
    }

    /** Conjugate a − bi */
    fun conjugate(): ComplexNum = ComplexNum(re, ExactMath.neg(im))

    /** Modulus |a + bi| = √(a² + b²) */
    fun modulus(): Double = hypot(re.toDouble(), im.toDouble())

    /** Exact value of modulus (gives Rational when a²+b² is a perfect square, else null) */
    fun modulusExact(): Rational? {
        val er = re.exact
        val ei = im.exact
        if (er == null || ei == null) return null
        val s = er.times(er).plus(ei.times(ei))
        return s.sqrtExact()
    }

    /** Argument (returned in current angle mode) */
    fun arg(mode: AngleMode): Double =
        CalcEngine.fromRadians(mode, atan2(im.toDouble(), re.toDouble()))

    /** Argument (radians) */
    fun argRad(): Double = atan2(im.toDouble(), re.toDouble())

    fun isReal(eps: Double = 1e-12): Boolean = abs(im.toDouble()) <= eps

    /** Polar ⇄ Cartesian coordinates */
    fun toPolar(mode: AngleMode): PolarPair =
        PolarForm.toPolar(re.toDouble(), im.toDouble(), mode)

    /** Formats as a + bi / a − bi / a */
    fun format(): String {
        val s = ExactMath.str(re)
        if (isReal()) return s
        val imv = im.toDouble()
        return if (imv < 0.0) "$s \u2212 ${ExactMath.str(ExactMath.neg(im))}i"
        else "$s + ${ExactMath.str(im)}i"
    }

    override fun toString(): String = format()

    companion object {

        /** Constructs from two doubles */
        @JvmStatic
        fun of(re: Double, im: Double = 0.0): ComplexNum =
            ComplexNum(Value.of(re), Value.of(im))

        /** Constructs from two literals: uses exact track when literals can be converted to rational */
        @JvmStatic
        fun ofLiteral(re: String, im: String): ComplexNum =
            ComplexNum(literal(re), literal(im))

        /** Constructs from two rational numbers (exact) */
        @JvmStatic
        fun ofExact(re: Rational, im: Rational = Rational.ZERO): ComplexNum =
            ComplexNum(Value.of(re), Value.of(im))

        /** Polar coordinates → complex number (r∠θ, using angle mode) */
        @JvmStatic
        fun fromPolar(r: Double, theta: Double, mode: AngleMode): ComplexNum {
            val c = PolarForm.toRect(r, theta, mode)
            return ComplexNum(Value.of(c.re), Value.of(c.im))
        }

        /** Parses numeric literal into Value: supports decimals and a/b fractions, throws on invalid */
        @JvmStatic
        fun literal(s: String): Value {
            val t = s.trim()
            if (t.isEmpty()) return Value.of(0.0)
            val slash = t.indexOf('/')
            if (slash > 0) {
                val n = t.substring(0, slash).trim().toBigDecimalOrNull()
                val d = t.substring(slash + 1).trim().toBigDecimalOrNull()
                if (n != null && d != null && d.signum() != 0) {
                    val rn = Rational.fromDecimal(n.toPlainString())!!
                    val rd = Rational.fromDecimal(d.toPlainString())!!
                    return Value.of(rn.div(rd))
                }
            }
            val d = t.toDoubleOrNull() ?: throw NumericError("Not a valid number: $s")
            return Value.of(d, t)
        }
    }
}
