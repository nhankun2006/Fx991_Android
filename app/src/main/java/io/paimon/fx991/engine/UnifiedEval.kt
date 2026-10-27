package io.paimon.fx991.engine

import java.math.BigInteger
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

// ---------------------------------------------------------------------------
// Unified Input Surface (REFERENCE item 6)
//
// Single entry point for main calculation line: scalars / complex numbers / matrices / vectors / statistics / distributions are evaluated here,
// without needing to switch CMPLX / MATRIX / VECTOR / STAT / DISTR modes beforehand.
//
// Design: **Pure numeric subexpressions completely reuse ValueEvaluator** (dual-track exact fraction core unchanged),
// branching to unified evaluation only when "new syntax" appears (∠ / · / MatA–D / VctA–D / variadic functions),
// thereby preventing any drift in original scalar semantics.
// ---------------------------------------------------------------------------

/** Values supported by the main calculation line: scalars (including complex) / matrices / vectors */
sealed interface CalcValue {

    /** Scalar (ordinary real numbers when imaginary part is 0, exact track remains effective) */
    class Scalar(val c: ComplexNum) : CalcValue

    class MatVal(val m: Matrix) : CalcValue

    class VecVal(val v: Vector3) : CalcValue
}

// ---------------------------------------------------------------------------

object Unified {

    /** Notes on principal branch for complex functions, etc. (cleared at start of eval, read by UI at end) */
    @JvmStatic
    var lastNote: String = ""

    /**
     * Main calculation line evaluation. Throws [CalcSyntaxError] / [CalcMathError] / [NumericError] on failure.
     *
     * @param lastExact Previous exact result (exact track for Ans)
     * @param mats Matrix variables (MatA–MatD), can be null
     * @param vecs Vector variables (VctA–VctD), can be null
     */
    @JvmStatic
    @JvmOverloads
    fun evaluate(
        expr: String,
        mode: AngleMode,
        ans: Double = 0.0,
        mem: Double = 0.0,
        lastExact: Value? = null,
        preAns: Double = 0.0,
        vars: Map<String, Double> = emptyMap(),
        mats: MatrixStore? = null,
        vecs: VectorStore? = null,
    ): CalcValue {
        lastNote = ""
        val ast = Parser(Lexer(expr).lex()).parse()
        val ansValue = lastExact ?: Value.Floating(ans)
        val ev = UnifiedEvaluator(mode, ansValue, Value.Floating(mem), preAns, vars, mats, vecs)
        val r = ev.eval(ast)
        if (r is CalcValue.Scalar) {
            val d = r.c.re.toDouble()
            if (d.isNaN() || d.isInfinite()) throw CalcMathError("Math ERROR")
        }
        return r
    }

    /** Whether the result is on the complex track (non-zero imaginary part) */
    @JvmStatic
    fun isComplex(v: CalcValue): Boolean =
        v is CalcValue.Scalar && abs(v.c.im.toDouble()) > 1e-12

    /** Pure real value (throws if non-scalar / non-zero imaginary part) */
    @JvmStatic
    fun asValue(v: CalcValue): Value {
        if (v !is CalcValue.Scalar) throw CalcMathError("Result is not numeric")
        if (abs(v.c.im.toDouble()) > 1e-12) throw CalcMathError("Result is not a real number")
        return v.c.re
    }

    /** Complex Cartesian coordinates (returns null if non-scalar) */
    @JvmStatic
    fun asComplex(v: CalcValue): ComplexNum? = if (v is CalcValue.Scalar) v.c else null

    /** Main line display text */
    @JvmStatic
    fun format(v: CalcValue): String = when (v) {
        is CalcValue.Scalar -> v.c.format()
        is CalcValue.MatVal -> v.m.format()
        is CalcValue.VecVal -> v.v.format()
    }
}

// ---------------------------------------------------------------------------

internal class UnifiedEvaluator(
    private val mode: AngleMode,
    private val ans: Value,
    private val mem: Value,
    private val preAns: Double,
    private val vars: Map<String, Double>,
    private val mats: MatrixStore?,
    private val vecs: VectorStore?,
) {
    private val scalar = ValueEvaluator(mode, ans, mem, preAns = preAns, vars = vars)
    private val zeroV: Value = Value.of(Rational.ZERO)
    private val oneV: Value = Value.of(Rational.ONE)
    private val zeroI: ComplexNum = ComplexNum(zeroV, zeroV)
    private val oneI: ComplexNum = ComplexNum(oneV, zeroV)

    fun eval(n: Node): CalcValue {
        // Pure scalar subexpressions: passed as-is to dual-track core, semantics identical to evaluateValue
        if (!hasNew(n)) return CalcValue.Scalar(ComplexNum(scalar.eval(n), zeroV))
        return when (n) {
            is Node.IRef -> CalcValue.Scalar(ComplexNum(zeroV, oneV))
            is Node.UFn -> {
                val args = n.args.map {
                    val c = one(eval(it))
                    if (abs(c.im.toDouble()) > 1e-9) throw CalcMathError("Custom function parameters must be real numbers")
                    c.re
                }
                CalcValue.Scalar(ComplexNum(UserFunctions.call(n.name, args, n.deriv, mode), zeroV))
            }
            is Node.Polar -> polar(one(eval(n.r)), one(eval(n.t)))
            is Node.Dot -> dotOp(eval(n.a), eval(n.b))
            is Node.MatRef -> {
                val m = mats?.get(n.name) ?: throw CalcMathError("Undefined matrix Mat${n.name}")
                CalcValue.MatVal(m)
            }
            is Node.VecRef -> {
                val v = vecs?.get(n.name) ?: throw CalcMathError("Undefined vector Vct${n.name}")
                CalcValue.VecVal(v)
            }
            is Node.FnN -> when (n.name) {
                // Batch G: Complex analysis -- first parameter is expression of z (not evaluated), passed directly to ComplexFunc
                "res" -> cplxRes(n.args)
                "cint" -> cplxCint(n.args)
                "fourier" -> throw CalcMathError("Please evaluate fourier separately as a main-line expression with =")
                // ---- Batch K3: In-place parenthesized calls (main line form of former numeric panels; algorithms reuse NumericOps etc.) ----
                "int" -> {
                    if (n.args.size != 3) throw CalcSyntaxError("int requires 3 parameters: int(f(x), lower, upper)")
                    val f = NodeText.render(n.args[0])
                    val a = real(one(eval(n.args[1])))
                    val b = real(one(eval(n.args[2])))
                    CalcValue.Scalar(ComplexNum(Value.of(NumericOps.integrate(f, mode, a, b, 1e-10)), zeroV))
                }
                "deriv" -> {
                    if (n.args.size != 2) throw CalcSyntaxError("deriv requires 2 parameters: deriv(f(x), xValue)")
                    val f = NodeText.render(n.args[0])
                    val x = real(one(eval(n.args[1])))
                    CalcValue.Scalar(ComplexNum(Value.of(NumericOps.derivative(f, mode, x)), zeroV))
                }
                "sum" -> {
                    if (n.args.size != 3) throw CalcSyntaxError("sum requires 3 parameters: sum(f(x), lower, upper)")
                    val f = NodeText.render(n.args[0])
                    val a = longOf(n.args[1], "Lower bound")
                    val b = longOf(n.args[2], "Upper bound")
                    CalcValue.Scalar(ComplexNum(Value.of(NumericOps.summation(f, mode, a, b)), zeroV))
                }
                "lim" -> {
                    if (n.args.size != 2) throw CalcSyntaxError("lim requires 2 parameters: lim(f(x), x0)")
                    val f = NodeText.render(n.args[0])
                    val x0 = real(one(eval(n.args[1])))
                    val r = NumericOps.limit(f, mode, x0)
                    if (r.equal) {
                        CalcValue.Scalar(ComplexNum(Value.of(r.value()), zeroV))
                    } else {
                        throw NumericError(
                            "Left and right limits are not equal: Left ${CalcEngine.format(r.left, 10, null)}" +
                                " | Right ${CalcEngine.format(r.right, 10, null)}",
                        )
                    }
                }
                "dms" -> {
                    if (n.args.size != 3) throw CalcSyntaxError("dms requires 3 parameters: dms(deg, min, sec)")
                    val d = real(one(eval(n.args[0])))
                    val m = real(one(eval(n.args[1])))
                    val s = real(one(eval(n.args[2])))
                    CalcValue.Scalar(ComplexNum(Value.of(Sexagesimal.toDegrees(d, m, s)), zeroV))
                }
                "calc" -> {
                    // Substitution evaluation: calculate with current variable values (x / y / STO variables) -- this is current context evaluation itself
                    if (n.args.size != 1) throw CalcSyntaxError("calc requires 1 parameter: calc(expression)")
                    eval(n.args[0])
                }
                "pol" -> {
                    if (n.args.size != 2) throw CalcSyntaxError("pol requires 2 parameters: pol(x, y)")
                    val x = real(one(eval(n.args[0])))
                    val y = real(one(eval(n.args[1])))
                    val p = PolarForm.toPolar(x, y, mode)
                    Unified.lastNote = "Pol: r = ${CalcEngine.format(p.r, 10, null)}, " +
                        "θ = ${CalcEngine.format(p.theta, 10, null)} (S<=>D toggles polar display)"
                    CalcValue.Scalar(ComplexNum(Value.of(x), Value.of(y)))
                }
                "rec" -> {
                    if (n.args.size != 2) throw CalcSyntaxError("rec requires 2 parameters: rec(r, θ)")
                    val r = real(one(eval(n.args[0])))
                    val th = real(one(eval(n.args[1])))
                    val c = PolarForm.toRect(r, th, mode)
                    CalcValue.Scalar(ComplexNum(Value.of(c.re), Value.of(c.im)))
                }
                "ranint" -> {
                    if (n.args.size != 2) throw CalcSyntaxError("ranint requires 2 parameters: ranint(lower, upper)")
                    val a = longOf(n.args[0], "Lower bound")
                    val b = longOf(n.args[1], "Upper bound")
                    CalcValue.Scalar(ComplexNum(Value.of(RandomOps.ranInt(a, b).toDouble()), zeroV))
                }
                else -> fnN(n.name, n.args.map { eval(it) })
            }
            is Node.Neg -> CalcValue.Scalar(negC(one(eval(n.a))))
            is Node.Add -> add(eval(n.a), eval(n.b))
            is Node.Sub -> sub(eval(n.a), eval(n.b))
            is Node.Mul -> mul(eval(n.a), eval(n.b))
            is Node.Div -> div(eval(n.a), eval(n.b))
            is Node.Pow -> CalcValue.Scalar(powC(one(eval(n.a)), one(eval(n.b))))
            is Node.Fact -> CalcValue.Scalar(factC(one(eval(n.a))))
            is Node.Pct -> CalcValue.Scalar(pctC(one(eval(n.a))))
            is Node.Recip -> CalcValue.Scalar(recipC(one(eval(n.a))))
            is Node.Sqrt -> CalcValue.Scalar(sqrtC(one(eval(n.a))))
            is Node.Fn -> fn(n.name, eval(n.a))
            is Node.Fn2 -> CalcValue.Scalar(
                ComplexNum(Value.of(MathOps.fn2(n.name, real(one(eval(n.a))), real(one(eval(n.b))))), zeroV)
            )
            else -> CalcValue.Scalar(ComplexNum(scalar.eval(n), zeroV))
        }
    }

    // ---- Helper functions ----

    private fun one(v: CalcValue): ComplexNum = when (v) {
        is CalcValue.Scalar -> v.c
        else -> throw CalcMathError("Numeric value required at this position")
    }

    /** Extract real number (throws if non-zero imaginary part or non-scalar) */
    private fun real(c: ComplexNum): Double {
        if (abs(c.im.toDouble()) > 1e-9) throw CalcMathError("Real number required at this position")
        return c.re.toDouble()
    }

    private fun negC(c: ComplexNum): ComplexNum =
        ComplexNum(ExactMath.neg(c.re), ExactMath.neg(c.im))

    private fun add(a: CalcValue, b: CalcValue): CalcValue = when {
        a is CalcValue.Scalar && b is CalcValue.Scalar -> CalcValue.Scalar(a.c.plus(b.c))
        a is CalcValue.MatVal && b is CalcValue.MatVal -> CalcValue.MatVal(a.m.plus(b.m))
        a is CalcValue.VecVal && b is CalcValue.VecVal -> CalcValue.VecVal(a.v.plus(b.v))
        else -> throw CalcMathError("Addition not supported (type mismatch)")
    }

    private fun sub(a: CalcValue, b: CalcValue): CalcValue = when {
        a is CalcValue.Scalar && b is CalcValue.Scalar -> CalcValue.Scalar(a.c.minus(b.c))
        a is CalcValue.MatVal && b is CalcValue.MatVal -> CalcValue.MatVal(a.m.minus(b.m))
        a is CalcValue.VecVal && b is CalcValue.VecVal -> CalcValue.VecVal(a.v.minus(b.v))
        else -> throw CalcMathError("Subtraction not supported (type mismatch)")
    }

    private fun mul(a: CalcValue, b: CalcValue): CalcValue = when {
        a is CalcValue.Scalar && b is CalcValue.Scalar -> CalcValue.Scalar(a.c.times(b.c))
        a is CalcValue.MatVal && b is CalcValue.MatVal -> CalcValue.MatVal(a.m.times(b.m))
        a is CalcValue.MatVal && b is CalcValue.Scalar -> CalcValue.MatVal(a.m.scalar(b.c.re))
        a is CalcValue.Scalar && b is CalcValue.MatVal -> CalcValue.MatVal(b.m.scalar(a.c.re))
        a is CalcValue.VecVal && b is CalcValue.Scalar -> CalcValue.VecVal(a.v.scale(b.c.re))
        a is CalcValue.Scalar && b is CalcValue.VecVal -> CalcValue.VecVal(b.v.scale(a.c.re))
        else -> throw CalcMathError("Multiplication not supported (type mismatch)")
    }

    private fun div(a: CalcValue, b: CalcValue): CalcValue = when {
        a is CalcValue.Scalar && b is CalcValue.Scalar -> CalcValue.Scalar(a.c.div(b.c))
        else -> throw CalcMathError("Division not supported (type mismatch)")
    }

    /** r∠θ -> Cartesian complex number */
    private fun polar(r: ComplexNum, t: ComplexNum): CalcValue {
        val rv = real(r)
        val tv = real(t)
        return CalcValue.Scalar(
            ComplexNum(Value.of(clean(rv * kotlin.math.cos(rad(tv)))), Value.of(clean(rv * kotlin.math.sin(rad(tv)))))
        )
    }

    private fun rad(theta: Double): Double = CalcEngine.toRadians(mode, theta)

    private fun clean(v: Double): Double = if (abs(v) < 1e-12) 0.0 else v

    /** ·: vector dot product; degenerates to multiplication for scalars */
    private fun dotOp(a: CalcValue, b: CalcValue): CalcValue = when {
        a is CalcValue.VecVal && b is CalcValue.VecVal -> CalcValue.Scalar(ComplexNum(a.v.dot(b.v), zeroV))
        else -> mul(a, b)
    }

    // ---- Scalar functions ----

    private fun powC(a: ComplexNum, b: ComplexNum): ComplexNum {
        val aIm = abs(a.im.toDouble()) > 1e-12
        val bIm = abs(b.im.toDouble()) > 1e-12
        val eb = b.re.exact
        if (!bIm && eb != null && eb.isInteger && eb.num.abs() <= BigInteger.valueOf(1000)) {
            return powIntC(a, eb.num.toInt())
        }
        if (!aIm && !bIm) {
            val ea = a.re.exact
            if (ea != null && eb != null) {
                if (eb.isInteger && eb.num.abs() <= BigInteger.valueOf(1000)) {
                    return ComplexNum(Value.of(ea.pow(eb.num.toInt())), zeroV)
                }
                if (eb.num == BigInteger.ONE && eb.den == BigInteger.TWO) {
                    val s = ea.sqrtExact()
                    if (s != null) return ComplexNum(Value.of(s), zeroV)
                }
            }
            return ComplexNum(Value.of(MathOps.pow(a.re.toDouble(), b.re.toDouble())), zeroV)
        }
        // Batch G: Complex power (principal branch a^b = exp(b·Ln a); integer power repeated multiplication)
        Unified.lastNote = ComplexFunc.BRANCH_NOTE
        return ComplexFunc.toComplex(ComplexFunc.powC(ComplexFunc.toCx(a), ComplexFunc.toCx(b)))
    }

    private fun powIntC(a: ComplexNum, k: Int): ComplexNum {
        if (k == 0) return oneI
        var base = if (k < 0) oneI.div(a) else a
        var n = abs(k)
        var acc = oneI
        while (n > 0) {
            if (n and 1 == 1) acc = acc.times(base)
            base = base.times(base)
            n = n shr 1
        }
        return acc
    }

    private fun factC(c: ComplexNum): ComplexNum {
        if (abs(c.im.toDouble()) > 1e-12) throw CalcMathError("Math ERROR")
        val e = c.re.exact
        if (e != null && e.isInteger && e.signum >= 0 && e.num <= BigInteger.valueOf(200)) {
            var r = BigInteger.ONE
            var k = 2
            val nn = e.num.toInt()
            while (k <= nn) {
                r = r.multiply(BigInteger.valueOf(k.toLong()))
                k++
            }
            return ComplexNum(Value.of(Rational.of(r, BigInteger.ONE)), zeroV)
        }
        return ComplexNum(Value.of(MathOps.factorial(c.re.toDouble())), zeroV)
    }

    private fun pctC(c: ComplexNum): ComplexNum {
        if (abs(c.im.toDouble()) > 1e-12) throw CalcMathError("Math ERROR")
        val e = c.re.exact
        if (e != null) return ComplexNum(Value.of(e.div(Rational.of(100))), zeroV)
        return ComplexNum(Value.of(c.re.toDouble() / 100.0), zeroV)
    }

    private fun recipC(c: ComplexNum): ComplexNum = oneI.div(c)

    private fun sqrtC(c: ComplexNum): ComplexNum {
        // Batch G: Complex square root (principal branch, negative real axis maps to positive imaginary axis)
        if (abs(c.im.toDouble()) > 1e-12) {
            Unified.lastNote = ComplexFunc.BRANCH_NOTE
            return ComplexFunc.fn1Value("sqrt", c)
        }
        val e = c.re.exact
        if (e != null) {
            if (e.signum < 0) throw CalcMathError("Math ERROR")
            val s = e.sqrtExact()
            if (s != null) return ComplexNum(Value.of(s), zeroV)
        }
        val d = c.re.toDouble()
        if (d < 0.0) throw CalcMathError("Math ERROR")
        return ComplexNum(Value.of(sqrt(d)), zeroV)
    }

    private fun fn(name: String, v: CalcValue): CalcValue {
        if (name == "abs") {
            if (v is CalcValue.VecVal) {
                val ex = v.v.normExact()
                val value = if (ex != null) Value.of(ex) else Value.of(v.v.norm())
                return CalcValue.Scalar(ComplexNum(value, zeroV))
            }
            val c = one(v)
            // Batch G: Complex abs is modulus |z|
            if (abs(c.im.toDouble()) > 1e-12) {
                return CalcValue.Scalar(ComplexNum(Value.of(c.modulus()), zeroV))
            }
            val e = c.re.exact
            if (e != null) return CalcValue.Scalar(ComplexNum(Value.of(e.abs()), zeroV))
            return CalcValue.Scalar(ComplexNum(Value.of(abs(c.re.toDouble())), zeroV))
        }
        val c = one(v)
        // Batch G: Elementary functions with complex arguments (exp/ln/trig/hyperbolic/conj, notes given for principal branch)
        if (abs(c.im.toDouble()) > 1e-12) {
            if (name !in ComplexFunc.FN1_NAMES) throw CalcMathError("Function $name not yet supported for complex numbers")
            if (ComplexFunc.isBranchy(name)) Unified.lastNote = ComplexFunc.BRANCH_NOTE
            return CalcValue.Scalar(ComplexFunc.fn1Value(name, c))
        }
        return CalcValue.Scalar(ComplexNum(Value.of(MathOps.fn(mode, name, real(c))), zeroV))
    }

    // ---- Variadic functions: matrix / vector / statistics / distributions ----

    private fun fnN(name: String, args: List<CalcValue>): CalcValue = when (name) {
        "det" -> {
            val m = mat(args, 0, "det")
            if (m.rows != m.cols) throw NumericError("Determinant requires a square matrix")
            CalcValue.Scalar(ComplexNum(m.det(), zeroV))
        }
        "inv" -> CalcValue.MatVal(mat(args, 0, "inv").inverse())
        "trn" -> CalcValue.MatVal(mat(args, 0, "trn").transpose())
        "cross" -> {
            if (args.size != 2) throw CalcSyntaxError("cross requires two vectors")
            CalcValue.VecVal(vec(args, 0, "cross").cross(vec(args, 1, "cross")))
        }
        "dot" -> {
            if (args.size != 2) throw CalcSyntaxError("dot requires two vectors")
            CalcValue.Scalar(ComplexNum(vec(args, 0, "dot").dot(vec(args, 1, "dot")), zeroV))
        }
        "mean", "sd", "ssd", "sigma" -> {
            val xs = args.map { real(one(it)) }
            val s = StatOps.oneVar(xs)
            val v = when (name) {
                "mean" -> s.mean
                "sd", "sigma" -> s.popSigma
                else -> s.sampleS
            }
            CalcValue.Scalar(ComplexNum(Value.of(v), zeroV))
        }
        "normpdf" -> {
            val (x, mu, sigma) = normArgs(args, name)
            CalcValue.Scalar(ComplexNum(Value.of(DistrOps.normPdf((x - mu) / sigma) / sigma), zeroV))
        }
        "normcdf" -> {
            val (x, mu, sigma) = normArgs(args, name)
            CalcValue.Scalar(ComplexNum(Value.of(DistrOps.normCdf((x - mu) / sigma)), zeroV))
        }
        "invnorm" -> {
            val (x, mu, sigma) = normArgs(args, name)
            CalcValue.Scalar(ComplexNum(Value.of(mu + sigma * DistrOps.invNormCdf(x)), zeroV))
        }
        "binompdf", "binomcdf" -> {
            if (args.size != 3) throw CalcSyntaxError("$name requires three parameters (n, k, p)")
            val n = intArg(args[0], "n")
            val k = intArg(args[1], "k")
            val p = real(one(args[2]))
            val v = if (name == "binompdf") DistrOps.binomialPdf(n, k, p) else DistrOps.binomialCdf(n, k, p)
            CalcValue.Scalar(ComplexNum(Value.of(v), zeroV))
        }
        "poissonpdf", "poissoncdf" -> {
            if (args.size != 2) throw CalcSyntaxError("$name requires two parameters (λ, k)")
            val lambda = real(one(args[0]))
            val k = intArg(args[1], "k")
            val v = if (name == "poissonpdf") DistrOps.poissonPdf(lambda, k) else DistrOps.poissonCdf(lambda, k)
            CalcValue.Scalar(ComplexNum(Value.of(v), zeroV))
        }
        else -> throw CalcSyntaxError("Syntax ERROR")
    }

    /** normpdf / normcdf / invnorm: 1 parameter (standard normal) or 3 parameters (x, μ, σ) */
    private fun normArgs(args: List<CalcValue>, name: String): Triple<Double, Double, Double> = when (args.size) {
        1 -> Triple(real(one(args[0])), 0.0, 1.0)
        3 -> Triple(real(one(args[0])), real(one(args[1])), real(one(args[2])))
        else -> throw CalcSyntaxError("$name requires 1 or 3 parameters")
    }

    private fun intArg(v: CalcValue, label: String): Int {
        val d = real(one(v))
        if (d != kotlin.math.floor(d)) throw NumericError("$label must be an integer")
        return d.toInt()
    }

    /** Batch K3: Evaluate subtree directly for integer parameter (lower/upper bound of sum / ranint) */
    private fun longOf(node: Node, label: String): Long {
        val d = real(one(eval(node)))
        if (d != kotlin.math.floor(d)) throw NumericError("$label must be an integer")
        return d.toLong()
    }

    // ---- Batch G: Complex analysis (residue / contour integration) ----

    /** res(f(z), z0): Residue; first parameter not evaluated, passed directly to ComplexFunc */
    private fun cplxRes(args: List<Node>): CalcValue {
        if (args.size != 2) throw CalcSyntaxError("res requires two parameters: res(f(z), z0)")
        val z0 = ComplexFunc.toCx(one(eval(args[1])))
        val r = ComplexFunc.residue(args[0], z0)
        Unified.lastNote = r.note
        return CalcValue.Scalar(ComplexFunc.toComplex(r.value))
    }

    /** cint(f(z), p1, p2, …): Residue theorem contour integration ∮f dz = 2πi·ΣRes */
    private fun cplxCint(args: List<Node>): CalcValue {
        if (args.size < 2) throw CalcSyntaxError("cint requires f(z) and at least one pole: cint(f(z), p1, …)")
        val poles = args.drop(1).map { ComplexFunc.toCx(one(eval(it))) }
        val (value, note) = ComplexFunc.contourByResidues(args[0], poles)
        Unified.lastNote = note
        return CalcValue.Scalar(ComplexFunc.toComplex(value))
    }

    private fun mat(args: List<CalcValue>, i: Int, fn: String): Matrix {
        val a = args.getOrNull(i) ?: throw CalcSyntaxError("$fn requires matrix parameter")
        return (a as? CalcValue.MatVal)?.m ?: throw CalcMathError("$fn requires matrix parameter")
    }

    private fun vec(args: List<CalcValue>, i: Int, fn: String): Vector3 {
        val a = args.getOrNull(i) ?: throw CalcSyntaxError("$fn requires vector parameter")
        return (a as? CalcValue.VecVal)?.v ?: throw CalcMathError("$fn requires vector parameter")
    }

    // ---- "Contains new syntax" check: determines which subtrees go through unified evaluation ----

    private fun hasNew(n: Node): Boolean = when (n) {
        is Node.Polar, is Node.Dot, is Node.MatRef, is Node.VecRef, is Node.FnN -> true
        // Batch G: Imaginary unit and custom functions also go through unified evaluation (to accommodate complex results)
        is Node.IRef, is Node.UFn -> true
        is Node.Neg -> hasNew(n.a)
        is Node.Add -> hasNew(n.a) || hasNew(n.b)
        is Node.Sub -> hasNew(n.a) || hasNew(n.b)
        is Node.Mul -> hasNew(n.a) || hasNew(n.b)
        is Node.Div -> hasNew(n.a) || hasNew(n.b)
        is Node.Pow -> hasNew(n.a) || hasNew(n.b)
        is Node.Fact -> hasNew(n.a)
        is Node.Pct -> hasNew(n.a)
        is Node.Recip -> hasNew(n.a)
        is Node.Sqrt -> hasNew(n.a)
        is Node.Fn -> hasNew(n.a)
        is Node.Fn2 -> hasNew(n.a) || hasNew(n.b)
        else -> false
    }
}
