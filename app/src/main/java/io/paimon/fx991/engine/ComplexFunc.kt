package io.paimon.fx991.engine

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt

// ---------------------------------------------------------------------------
// Batch G: Complex analysis functions (pure Kotlin, JVM regressible)
//
// Extended on top of Batch C CMPLX without rewriting:
//   1. Complex function evaluation: exp / ln / power / trig / hyperbolic / sqrt (principal branch, hints shown when crossing branch cuts)
//   2. Residue Res(f, z0): computed via numerical Laurent coefficients on small circle (trapezoidal spectral method),
//      automatically determines pole order (1..8), explicitly states "not a pole" for removable singularities, rejects essential singularities
//   3. Residue theorem contour integration: cint(f, p1, p2, …) = 2πi·ΣRes(f, pk)
//
// Complex expressions use z as independent variable, supporting i (imaginary unit) and complex generalizations of all real functions;
// Trigonometric / hyperbolic functions always use radians (complex arguments have no angle mode).
// ---------------------------------------------------------------------------

/** Lightweight complex number (Double precision; display layer converts back to dual-track ComplexNum) */
class Cx(val re: Double, val im: Double) {

    operator fun plus(o: Cx): Cx = Cx(re + o.re, im + o.im)
    operator fun minus(o: Cx): Cx = Cx(re - o.re, im - o.im)
    operator fun unaryMinus(): Cx = Cx(-re, -im)

    operator fun times(o: Cx): Cx = Cx(re * o.re - im * o.im, re * o.im + im * o.re)

    operator fun div(o: Cx): Cx {
        val d = o.re * o.re + o.im * o.im
        if (d == 0.0) throw CalcMathError("Math ERROR")
        return Cx((re * o.re + im * o.im) / d, (im * o.re - re * o.im) / d)
    }

    fun conj(): Cx = Cx(re, -im)
    fun modulus(): Double = hypot(re, im)
    fun arg(): Double = atan2(im, re)
    fun finite(): Boolean = re.isFinite() && im.isFinite()
    fun isRealish(eps: Double = 1e-12): Boolean = abs(im) <= eps

    override fun toString(): String = "($re${if (im < 0) "" else "+"}${im}i)"

    companion object {
        @JvmField
        val ZERO = Cx(0.0, 0.0)
        @JvmField
        val ONE = Cx(1.0, 0.0)
        @JvmField
        val I = Cx(0.0, 1.0)
        @JvmStatic
        fun of(re: Double, im: Double = 0.0): Cx = Cx(re, im)
    }
}

/** Residue result: value is residue, order is pole order (0 = removable singularity / analytic point) */
class ResidueResult(val value: Cx, val order: Int, val note: String)

object ComplexFunc {

    /** Principal branch note (ln / sqrt / general power cross branch cuts) */
    const val BRANCH_NOTE = "Complex functions take principal branch (arg ∈ (−π, π], negative real axis is branch cut)"

    /** Complex functions available in main line (real arguments still go through original real track, unaffected) */
    @JvmField
    val FN1_NAMES: Set<String> = setOf(
        "sin", "cos", "tan", "sinh", "cosh", "tanh", "exp", "ln", "sqrt", "conj", "abs",
    )

    /** Functions using principal branch (triggers branch note) */
    private val BRANCHY = setOf("ln", "sqrt")

    // -----------------------------------------------------------------------
    // Complex elementary functions
    // -----------------------------------------------------------------------

    fun fn1(name: String, z: Cx): Cx {
        val r = when (name) {
            "exp" -> {
                val e = exp(z.re)
                Cx(e * cos(z.im), e * sin(z.im))
            }
            "ln" -> {
                if (z.re == 0.0 && z.im == 0.0) throw CalcMathError("Math ERROR")
                Cx(ln(z.modulus()), z.arg())
            }
            "sin" -> Cx(sin(z.re) * cosh(z.im), cos(z.re) * sinh(z.im))
            "cos" -> Cx(cos(z.re) * cosh(z.im), -sin(z.re) * sinh(z.im))
            "tan" -> fn1("sin", z) / fn1("cos", z)
            "sinh" -> Cx(sinh(z.re) * cos(z.im), cosh(z.re) * sin(z.im))
            "cosh" -> Cx(cosh(z.re) * cos(z.im), sinh(z.re) * sin(z.im))
            "tanh" -> fn1("sinh", z) / fn1("cosh", z)
            "sqrt" -> sqrtC(z)
            "conj" -> z.conj()
            "abs" -> Cx(z.modulus(), 0.0)
            else -> throw CalcMathError("Complex numbers do not support function $name yet")
        }
        if (!r.finite()) throw CalcMathError("Math ERROR")
        return r
    }

    /** Principal square root: real part >= 0, negative real axis maps to positive imaginary axis */
    fun sqrtC(z: Cx): Cx {
        val r = z.modulus()
        if (r == 0.0) return Cx.ZERO
        val rePart = sqrt((r + z.re) / 2.0)
        val imPart = sqrt(maxOf(0.0, (r - z.re) / 2.0)) * if (z.im < 0.0) -1.0 else 1.0
        return Cx(rePart, imPart)
    }

    /** Principal power a^b = exp(b·Ln a); integer powers use repeated multiplication (more accurate) */
    fun powC(a: Cx, b: Cx): Cx {
        if (a.re == 0.0 && a.im == 0.0) {
            if (b.re == 0.0 && b.im == 0.0) return Cx.ONE
            if (b.im == 0.0 && b.re > 0.0) return Cx.ZERO
            throw CalcMathError("Math ERROR")
        }
        if (b.isRealish() && b.re == floor(b.re) && abs(b.re) <= 64.0) {
            return powIntC(a, b.re.toInt())
        }
        return fn1("exp", b * fn1("ln", a))
    }

    private fun powIntC(a: Cx, k: Int): Cx {
        if (k == 0) return Cx.ONE
        var base = if (k < 0) Cx.ONE / a else a
        var n = abs(k)
        var acc = Cx.ONE
        while (n > 0) {
            if (n and 1 == 1) acc = acc * base
            base = base * base
            n = n shr 1
        }
        return acc
    }

    // -----------------------------------------------------------------------
    // Complex expression evaluation (z as independent variable; reuses same lexer / parser)
    // -----------------------------------------------------------------------

    /**
     * Evaluates f(z). In expressions: `z` is independent variable, `i` is imaginary unit,
     * remaining tokens (+ − × ÷ ^ √ functions…) match main line, evaluated with complex semantics (radians).
     */
    @JvmStatic
    fun evalExpr(expr: String, z: Cx): Cx {
        val ast = Parser(Lexer(expr).lex()).parse()
        val r = evalNode(ast, z)
        if (!r.finite()) throw CalcMathError("Math ERROR")
        return r
    }

    internal fun evalNode(n: Node, z: Cx): Cx = when (n) {
        is Node.Num -> Cx(n.v, 0.0)
        is Node.IRef -> Cx.I
        is Node.ZRef -> z
        is Node.XRef, is Node.YRef ->
            throw CalcMathError("Please use z as the independent variable in complex expressions")
        is Node.Neg -> -evalNode(n.a, z)
        is Node.Add -> evalNode(n.a, z) + evalNode(n.b, z)
        is Node.Sub -> evalNode(n.a, z) - evalNode(n.b, z)
        is Node.Mul -> evalNode(n.a, z) * evalNode(n.b, z)
        is Node.Div -> evalNode(n.a, z) / evalNode(n.b, z)
        is Node.Pow -> powC(evalNode(n.a, z), evalNode(n.b, z))
        is Node.Recip -> Cx.ONE / evalNode(n.a, z)
        is Node.Sqrt -> sqrtC(evalNode(n.a, z))
        is Node.Pct -> evalNode(n.a, z) / Cx(100.0, 0.0)
        is Node.Fact -> {
            val v = evalNode(n.a, z)
            if (!v.isRealish()) throw CalcMathError("Factorial is only supported for real numbers")
            Cx(MathOps.factorial(v.re), 0.0)
        }
        is Node.Fn -> {
            val v = evalNode(n.a, z)
            if (v.isRealish()) {
                // Real arguments preserve real semantics (radians, consistent with complex)
                Cx(MathOps.fn(AngleMode.RAD, n.name, v.re), 0.0)
            } else {
                fn1(n.name, v)
            }
        }
        is Node.Polar -> {
            val r = evalNode(n.r, z)
            val t = evalNode(n.t, z)
            if (!r.isRealish() || !t.isRealish()) throw CalcMathError("Both sides of ∠ must be real numbers")
            Cx(r.re * cos(t.re), r.re * sin(t.re))
        }
        is Node.UFn -> {
            if (n.deriv > 0) throw CalcMathError("Derivatives of user-defined functions are not supported in complex expressions yet")
            val args = n.args.map {
                val c = evalNode(it, z)
                if (!c.isRealish()) throw CalcMathError("User-defined function arguments must be real numbers")
                Value.of(c.re)
            }
            Cx(UserFunctions.call(n.name, args, 0, AngleMode.RAD).toDouble(), 0.0)
        }
        else -> throw CalcMathError("Unsupported token in complex expression")
    }

    // -----------------------------------------------------------------------
    // Residue: Laurent coefficients on small circle (trapezoidal spectral method)
    //
    //   a_n = (1/(2π rⁿ)) ∫₀^{2π} f(z0 + r·e^{it})·e^{−int} dt
    // The trapezoidal rule achieves spectral convergence for periodic analytic functions; a₋₁ is the residue, no need to know the order beforehand.
    // Cross-verified using two radii; pole order determined by the last significant negative power in a₋₁…a₋₈.
    // -----------------------------------------------------------------------

    private const val QUAD_N = 96

    @JvmStatic
    internal fun residue(f: Node, z0: Cx): ResidueResult {
        val scale = 1.0 + z0.modulus()
        val r1 = 0.05 * scale
        val r2 = 0.025 * scale

        fun coeff(r: Double, n: Int): Cx {
            var sum = Cx.ZERO
            val rn = Math.pow(r, n.toDouble())
            for (k in 0 until QUAD_N) {
                val t = 2.0 * Math.PI * k / QUAD_N
                val zz = z0 + Cx(r * cos(t), r * sin(t))
                val fz = try {
                    evalNode(f, zz)
                } catch (e: Exception) {
                    throw NumericError("f is numerically unstable near z0 (a branch cut may pass through the circle)")
                }
                if (!fz.finite()) throw NumericError("f is numerically unstable near z0")
                sum += fz * Cx(cos(n * t), -sin(n * t))
            }
            return sum / Cx(QUAD_N * rn, 0.0)
        }

        val res1 = coeff(r1, -1)
        val res2 = coeff(r2, -1)
        val resMag = maxOf(1.0, res1.modulus())
        val agree = (res1 - res2).modulus() <= 1e-5 * resMag

        // Pole order: last significant negative power among a₋₈ … a₋₁
        var order = 0
        for (k in 8 downTo 1) {
            val a = coeff(r1, -k)
            if (a.modulus() > 1e-7 * resMag) {
                order = k
                break
            }
        }
        val higher = coeff(r1, -9).modulus()
        if (order >= 8 && higher > 1e-7 * resMag) {
            throw NumericError("Cannot determine as a finite-order pole (possibly an essential singularity), unable to compute")
        }

        // Removable singularity / analytic point: residue is 0, explicitly states "not a pole"
        if (order == 0) {
            return ResidueResult(Cx.ZERO, 0, "z0 is not a pole (removable singularity or analytic point), residue is 0")
        }
        if (!agree) {
            return ResidueResult(res1, order, "Results from two radii do not match, low numerical confidence (other singularities may exist nearby)")
        }
        val ordText = when (order) {
            1 -> "Order 1 (simple) pole"
            2 -> "Order 2 pole"
            3 -> "Order 3 pole"
            else -> "Order $order pole"
        }
        return ResidueResult(res1, order, ordText)
    }

    /** Convenient entry: string expression + Cartesian coordinate z0 (for direct call by UI / regression tests) */
    @JvmStatic
    fun residueOf(expr: String, zRe: Double, zIm: Double): ResidueResult {
        val ast = Parser(Lexer(expr).lex()).parse()
        return residue(ast, Cx(zRe, zIm))
    }

    /** Convenient entry: string expression + pole list (for direct call by UI) */
    @JvmStatic
    fun contourOf(expr: String, poles: List<Cx>): Pair<Cx, String> =
        contourByResidues(Parser(Lexer(expr).lex()).parse(), poles)

    // -----------------------------------------------------------------------
    // Residue theorem contour integration: ∮ f dz = 2πi·ΣRes(f, pk)
    // -----------------------------------------------------------------------

    /** @return Integration value + residue details for each pole (for display) */
    internal fun contourByResidues(f: Node, poles: List<Cx>): Pair<Cx, String> {
        if (poles.isEmpty()) throw NumericError("At least one pole inside contour must be specified")
        var sum = Cx.ZERO
        val detail = StringBuilder()
        for ((idx, p) in poles.withIndex()) {
            val r = residue(f, p)
            sum += r.value
            if (idx > 0) detail.append(", ")
            detail.append("Res(${fmtC(p)})=${fmtC(r.value)}")
        }
        val value = Cx(-2.0 * Math.PI * sum.im, 2.0 * Math.PI * sum.re)
        val note = "Residue Theorem: ∮f dz = 2πi·ΣRes (contour must be a positively oriented simple closed curve enclosing only the listed poles)\n$detail"
        return value to note
    }

    private fun fmtC(z: Cx): String {
        val re = CalcEngine.format(z.re, 10, null)
        if (abs(z.im) <= 1e-12) return re
        val im = CalcEngine.format(abs(z.im), 10, null)
        return if (z.im < 0.0) "$re−${im}i" else "$re+${im}i"
    }

    /** Dual-track ComplexNum → Cx */
    fun toCx(c: ComplexNum): Cx = Cx(c.re.toDouble(), c.im.toDouble())

    /** Cx → dual-track ComplexNum (floating track) */
    fun toComplex(z: Cx): ComplexNum = ComplexNum(Value.of(clean(z.re)), Value.of(clean(z.im)))

    private fun clean(v: Double): Double = if (abs(v) < 1e-12) 0.0 else v

    /** Dual-track wrapper for fn1 (used by UnifiedEvaluator) */
    fun fn1Value(name: String, c: ComplexNum): ComplexNum = toComplex(fn1(name, toCx(c)))

    /** Whether this function uses the principal branch (for UI hints) */
    @JvmStatic
    fun isBranchy(name: String): Boolean = name in BRANCHY
}
