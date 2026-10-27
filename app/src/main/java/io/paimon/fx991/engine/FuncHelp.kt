package io.paimon.fx991.engine

// ---------------------------------------------------------------------------
// Batch C: Function Help FUNC HELP (function catalog + syntax descriptions; pure Kotlin, JVM regressible)
//
// Each entry includes an "insert expression" text (clicks to insert into formula) and an evaluatable sample,
// regression tests use samples to verify syntax keys match the engine parser.
// ---------------------------------------------------------------------------

/** A single function help entry */
class FuncHelpEntry(
    /** Short name (unique) */
    val name: String,
    val category: String,
    /** Syntax notation, e.g. logb(base, x) */
    val syntax: String,
    /** English description */
    val desc: String,
    /** Text inserted into expression when clicked */
    val insert: String,
    /** Evaluatable sample (used to verify syntax key integrity) */
    val sample: String,
)

object FuncHelp {

    const val CAT_TRIG = "Trigonometric Functions"
    const val CAT_INVTRIG = "Inverse Trigonometric Functions"
    const val CAT_HYPER = "Hyperbolic & Inverse Hyperbolic"
    const val CAT_LOG = "Logarithmic & Exponential"
    const val CAT_ROOT = "Radical & Power"
    const val CAT_COMB = "Combinatorics & Permutations"
    const val CAT_INT = "Integer & Rounding"
    const val CAT_CONST = "Constants & Registers"
    const val CAT_UNIFIED = "Matrix / Vector (Main Line)"
    const val CAT_STAT = "Statistics / Distribution (Main Line)"
    const val CAT_EQUATION = "Equation Solving (Main Line)"
    const val CAT_GEOGEBRA = "User Functions (GeoGebra Style)"
    const val CAT_FOURIER = "Fourier Series (Main Line)"
    const val CAT_CPLXF = "Complex Analysis Functions (Main Line)"
    const val CAT_INLINE = "In-place Calls (Batch K3, Direct Main Line)"

    @JvmField
    val ALL: List<FuncHelpEntry> = listOf(
        // ---- Trigonometric Functions ----
        FuncHelpEntry("sin", CAT_TRIG, "sin(θ)", "Sine; θ in current angle mode (DEG/RAD/GRAD)", "sin(", "sin(30)"),
        FuncHelpEntry("cos", CAT_TRIG, "cos(θ)", "Cosine; θ in current angle mode", "cos(", "cos(60)"),
        FuncHelpEntry("tan", CAT_TRIG, "tan(θ)", "Tangent; θ in current angle mode", "tan(", "tan(45)"),
        FuncHelpEntry("cot", CAT_TRIG, "cot(θ)", "Cotangent = cos/sin (Batch K4, ALPHA layer of ENG)", "cot(", "cot(30)"),

        // ---- Inverse Trigonometric ----
        FuncHelpEntry("sin⁻¹", CAT_INVTRIG, "sin⁻¹(x)", "Arcsine, domain [−1, 1], output in current angle mode", "sin\u207B\u00B9(", "sin\u207B\u00B9(0.5)"),
        FuncHelpEntry("cos⁻¹", CAT_INVTRIG, "cos⁻¹(x)", "Arccosine, domain [−1, 1]", "cos\u207B\u00B9(", "cos\u207B\u00B9(0.5)"),
        FuncHelpEntry("tan⁻¹", CAT_INVTRIG, "tan⁻¹(x)", "Arctangent", "tan\u207B\u00B9(", "tan\u207B\u00B9(1)"),
        FuncHelpEntry("cot⁻¹", CAT_INVTRIG, "acot(x)", "Arccotangent, range (0, 180°) (Batch K4, ALPHA layer of '(')", "acot(", "acot(1)"),

        // ---- Hyperbolic & Inverse Hyperbolic ----
        FuncHelpEntry("sinh", CAT_HYPER, "sinh(x)", "Hyperbolic sine", "sinh(", "sinh(1)"),
        FuncHelpEntry("cosh", CAT_HYPER, "cosh(x)", "Hyperbolic cosine", "cosh(", "cosh(1)"),
        FuncHelpEntry("tanh", CAT_HYPER, "tanh(x)", "Hyperbolic tangent", "tanh(", "tanh(1)"),
        FuncHelpEntry("sinh⁻¹", CAT_HYPER, "sinh⁻¹(x)", "Inverse hyperbolic sine (asinh)", "asinh(", "asinh(1)"),
        FuncHelpEntry("cosh⁻¹", CAT_HYPER, "cosh⁻¹(x)", "Inverse hyperbolic cosine (acosh), domain x ≥ 1", "acosh(", "acosh(2)"),
        FuncHelpEntry("tanh⁻¹", CAT_HYPER, "tanh⁻¹(x)", "Inverse hyperbolic tangent (atanh), domain |x| < 1", "atanh(", "atanh(0.5)"),

        // ---- Logarithmic & Exponential ----
        FuncHelpEntry("log", CAT_LOG, "log(x)", "Common logarithm (base 10)", "log(", "log(100)"),
        FuncHelpEntry("ln", CAT_LOG, "ln(x)", "Natural logarithm (base e)", "ln(", "ln(1)"),
        FuncHelpEntry("logb", CAT_LOG, "logb(base, x)", "Arbitrary base logarithm; exact when base/x are integers and result is integer", "logb(", "logb(2, 8)"),
        FuncHelpEntry("10ˣ", CAT_LOG, "10^(x)", "10 to the power of x", "10^", "10^3"),
        FuncHelpEntry("eˣ", CAT_LOG, "e^(x)", "e to the power of x (exp)", "exp(", "exp(0)"),

        // ---- Radical & Power ----
        FuncHelpEntry("√", CAT_ROOT, "√(x)", "Square root; preserves exact fraction if a perfect square", "\u221A(", "\u221A(9)"),
        FuncHelpEntry("³√", CAT_ROOT, "³√(x)", "Cube root (cbrt), supports negative numbers", "cbrt(", "cbrt(27)"),
        FuncHelpEntry("ˣ√y", CAT_ROOT, "root(n, x)", "nth root; preserves exact fraction if an exact power", "root(", "root(3, 27)"),
        FuncHelpEntry("x²", CAT_ROOT, "x²", "Square", "\u00B2", "3\u00B2"),
        FuncHelpEntry("x³", CAT_ROOT, "x³", "Cube", "\u00B3", "2\u00B3"),
        FuncHelpEntry("xʸ", CAT_ROOT, "x^(y)", "Power (right-associative: 2^3^2 = 512)", "^", "2^3"),
        FuncHelpEntry("x⁻¹", CAT_ROOT, "x⁻¹", "Reciprocal", "\u207B\u00B9(", "2\u207B\u00B9"),
        FuncHelpEntry("|x|", CAT_ROOT, "abs(x)", "Absolute value; exact track stays exact", "abs(", "abs(\u2212(3))"),

        // ---- Combinatorics & Permutations ----
        FuncHelpEntry("nPr", CAT_COMB, "npr(n, r)", "Permutations; large numbers use BigInteger without overflow", "npr(", "npr(5, 2)"),
        FuncHelpEntry("nCr", CAT_COMB, "ncr(n, r)", "Combinations; exact when ≤ 128-bit", "ncr(", "ncr(5, 2)"),
        FuncHelpEntry("!", CAT_COMB, "x!", "Factorial (non-negative integer)", "!", "5!"),
        FuncHelpEntry("%", CAT_COMB, "x%", "Percentage; A + B% = A + A·B/100", "%", "200+10%"),

        // ---- Integer & Rounding (Batch K4) ----
        FuncHelpEntry("GCD", CAT_INT, "gcd(a, b)", "Greatest common divisor (integers, negative allowed; ALPHA layer of ×)", "gcd(", "gcd(12, 18)"),
        FuncHelpEntry("LCM", CAT_INT, "lcm(a, b)", "Least common multiple (ALPHA layer of ÷)", "lcm(", "lcm(4, 6)"),
        FuncHelpEntry("mod", CAT_INT, "mod(a, b)", "Modulo (floor division style; ALPHA layer of √x)", "mod(", "mod(7, 3)"),
        FuncHelpEntry("Ceil", CAT_INT, "ceil(x)", "Ceiling (round up; ALPHA layer of +)", "ceil(", "ceil(2.3)"),
        FuncHelpEntry("Floor", CAT_INT, "floor(x)", "Floor (round down; ALPHA layer of −)", "floor(", "floor(2.7)"),

        // ---- Constants & Registers ----
        FuncHelpEntry("π", CAT_CONST, "π", "Pi", "\u03C0", "\u03C0"),
        FuncHelpEntry("e", CAT_CONST, "e", "Euler's number (base of natural log)", "e", "e"),
        FuncHelpEntry("Ans", CAT_CONST, "Ans", "Previous calculation result", "Ans", "Ans"),
        FuncHelpEntry("PreAns", CAT_CONST, "PreAns", "Calculation result before Ans", "PreAns", "PreAns"),
        FuncHelpEntry("M", CAT_CONST, "M", "Independent memory (M+ / M− / MRC)", "M", "M"),
        FuncHelpEntry("Variables A–F", CAT_CONST, "A .. F", "Variables stored via STO, directly referenced in expressions", "A", "A+B"),
        FuncHelpEntry("∠", CAT_CONST, "r∠θ", "Polar coordinate operator (chainable): 9∠60+5∠6 adds two complex numbers", "\u2220", "2"),
        FuncHelpEntry("∞", CAT_CONST, "∞", "Positive infinity symbol (ALPHA layer of 9; 1÷∞→0, standalone evaluation throws Math ERROR)", "\u221E", "1\u00F7\u221E"),
        FuncHelpEntry("Σ", CAT_CONST, "sum(f(x), a, b)", "Summation: sum(expr, lower, upper) in main line, displayed as Σ", "sum(", "sum(x,1,10)"),

        // ---- Batch K3: In-place parenthesis calls (direct main line, no popup panels) ----
        FuncHelpEntry("solve", CAT_INLINE, "solve(equation)", "Equation solver: solve(2x+3=7) / solve(x²-3x+2=0); treated as f(x)=0 without equals sign", "solve(", "1+1"),
        FuncHelpEntry("sto", CAT_INLINE, "sto(expr, var)", "In-place variable assignment: sto(5+3, A); variables A–F / x / y / M", "sto(", "1+1"),
        FuncHelpEntry("int", CAT_INLINE, "int(f(x), a, b)", "Definite integral (displayed as ∫_a^b expr dx): int(x^2, 0, 1)", "int(", "int(x^2,0,1)"),
        FuncHelpEntry("deriv", CAT_INLINE, "deriv(f(x), x)", "Numerical derivative (displayed as d/dx): deriv(sin(x), 1)", "deriv(", "deriv(sin(x),1)"),
        FuncHelpEntry("sum2", CAT_INLINE, "sum(f(x), a, b)", "Summation (displayed as Σ): sum(x, 1, 10) = 55", "sum(", "sum(x,1,10)"),
        FuncHelpEntry("lim", CAT_INLINE, "lim(f(x), x0)", "Limit: lim(sin(x)/x, 0); explicitly reports if left/right limits differ", "lim(", "lim(sin(x)/x,0)"),
        FuncHelpEntry("calc", CAT_INLINE, "calc(expr)", "Evaluate with substitution: evaluates expression using current x / y / variable values", "calc(", "calc(1+1)"),
        FuncHelpEntry("dms", CAT_INLINE, "dms(deg, min, sec)", "Degrees-Minutes-Seconds → decimal degrees: dms(1, 30, 0) = 1.5", "dms(", "dms(1,30,0)"),
        FuncHelpEntry("pol", CAT_INLINE, "pol(x, y)", "Rectangular → Polar: note provides r / θ, S⇔D toggles r∠θ display", "pol(", "pol(1,1)"),
        FuncHelpEntry("rec", CAT_INLINE, "rec(r, θ)", "Polar → Rectangular: result displays x + yi", "rec(", "rec(2,60)"),
        FuncHelpEntry("ranint", CAT_INLINE, "ranint(a, b)", "Uniform random integer in [a, b]", "ranint(", "ranint(1,6)"),
        FuncHelpEntry("const", CAT_INLINE, "const(symbol)", "In-place scientific constant reference: const(g) / const(c) / const(NA)", "const(", "const(g)"),
        FuncHelpEntry("conv", CAT_INLINE, "conv(val, from, to)", "Unit conversion: conv(5, km, mi); temperatures written as C / F / K", "conv(", "conv(5,km,mi)"),
        FuncHelpEntry("si", CAT_INLINE, "si(val, prefix)", "SI prefix reading: si(1500, k) = 1.5 (i.e. 1.5k)", "si(", "si(1500,k)"),

        // ---- Unified Input Surface: Matrix / Vector (direct in main line, no mode switch needed) ----
        FuncHelpEntry("MatA", CAT_UNIFIED, "MatA .. MatD", "Matrix variables; define data in MATRIX interface, participate directly in main line calculations", "MatA", "MatA"),
        FuncHelpEntry("VctA", CAT_UNIFIED, "VctA .. VctD", "Vector variables; define data in VECTOR interface, participate directly in main line calculations", "VctA", "VctA"),
        FuncHelpEntry("det", CAT_UNIFIED, "det(MatA)", "Matrix determinant (square matrix)", "det(", "det(MatA)"),
        FuncHelpEntry("inv", CAT_UNIFIED, "inv(MatA)", "Matrix inverse (error if singular)", "inv(", "inv(MatA)"),
        FuncHelpEntry("trn", CAT_UNIFIED, "trn(MatA)", "Matrix transpose", "trn(", "trn(MatA)"),
        FuncHelpEntry("×", CAT_UNIFIED, "MatA×MatB", "Matrix multiplication (left columns = right rows) / scalar multiplication", "\u00D7", "MatA\u00D7MatB"),
        FuncHelpEntry("·", CAT_UNIFIED, "VctA·VctB", "Vector dot product (also written as dot(A,B))", "\u00B7", "VctA\u00B7VctB"),
        FuncHelpEntry("cross", CAT_UNIFIED, "cross(VctA, VctB)", "Vector cross product", "cross(", "cross(VctA,VctB)"),
        FuncHelpEntry("dot", CAT_UNIFIED, "dot(VctA, VctB)", "Vector dot product", "dot(", "dot(VctA,VctB)"),
        FuncHelpEntry("abs", CAT_UNIFIED, "abs(VctA)", "Vector magnitude (absolute value for scalar)", "abs(", "abs(1)"),

        // ---- Unified Input Surface: Statistics / Distribution (callable in main line) ----
        FuncHelpEntry("mean", CAT_STAT, "mean(data…)", "Mean (data separated by commas)", "mean(", "mean(1,2,3)"),
        FuncHelpEntry("sd", CAT_STAT, "sd(data…)", "Population standard deviation σ (denominator n)", "sd(", "sd(1,2,3)"),
        FuncHelpEntry("ssd", CAT_STAT, "ssd(data…)", "Sample standard deviation s (denominator n−1)", "ssd(", "ssd(1,2,3)"),
        FuncHelpEntry("normpdf", CAT_STAT, "normpdf(x[, μ, σ])", "Normal probability density; defaults to standard normal", "normpdf(", "normpdf(0,1,1)"),
        FuncHelpEntry("normcdf", CAT_STAT, "normcdf(x[, μ, σ])", "Normal cumulative distribution P(X < x)", "normcdf(", "normcdf(0,1,1)"),
        FuncHelpEntry("invnorm", CAT_STAT, "invnorm(p[, μ, σ])", "Inverse normal (calculates x given probability p)", "invnorm(", "invnorm(0.5,0,1)"),
        FuncHelpEntry("binompdf", CAT_STAT, "binompdf(n, k, p)", "Binomial distribution P(X = k)", "binompdf(", "binompdf(10,3,0.5)"),
        FuncHelpEntry("binomcdf", CAT_STAT, "binomcdf(n, k, p)", "Binomial distribution cumulative P(X ≤ k)", "binomcdf(", "binomcdf(10,3,0.5)"),
        FuncHelpEntry("poissonpdf", CAT_STAT, "poissonpdf(λ, k)", "Poisson distribution P(X = k)", "poissonpdf(", "poissonpdf(2,1)"),
        FuncHelpEntry("poissoncdf", CAT_STAT, "poissoncdf(λ, k)", "Poisson distribution cumulative P(X ≤ k)", "poissoncdf(", "poissoncdf(2,1)"),

        // ---- Main Line Solve (REFERENCE item 7) ----
        FuncHelpEntry("Equals Sign", CAT_EQUATION, "LHS = RHS", "Press = to solve when unknowns are present (e.g. 2x+3=7)", "=", "1+1"),
        FuncHelpEntry("System of Equations", CAT_EQUATION, "eq1, eq2", "Separated by comma or semicolon (e.g. 2x+y=5, x-y=1)", ",", "1+1"),

        // ---- Batch G: User Functions (GeoGebra Style) ----
        FuncHelpEntry("Define Function", CAT_GEOGEBRA, "f(x) = x^2", "Define function in main line; f(3), f(x)+1, nested calls directly available afterwards; view/edit/delete in algebra area", "f(x)=x^2", "1+1"),
        FuncHelpEntry("Redefine Function", CAT_GEOGEBRA, "f(x) := x^3", ":= forces redefinition of existing function; writing = on same name solves equation", "f(x):=x^3", "1+1"),
        FuncHelpEntry("Call & Derivative", CAT_GEOGEBRA, "f(3) · f'(2)", "Call defined function; ' first derivative, '' second derivative (single-variable only)", "f'(", "1+1"),

        // ---- Batch G: Fourier Series ----
        FuncHelpEntry("fourier", CAT_FOURIER, "fourier(f(x), a, b, n)", "Fourier series expansion: first n terms a0/an/bn and partial sum on interval [a, b]; automatic parity detection; radians", "fourier(", "fourier(1, 0, 1, 1)"),

        // ---- Batch G: Complex Analysis Functions ----
        FuncHelpEntry("i", CAT_CPLXF, "i", "Imaginary unit (SHIFT layer of ENG): exp(i*π) = −1; complex functions take principal branch (arg ∈ (−π, π])", "i", "i"),
        FuncHelpEntry("conj", CAT_CPLXF, "conj(z)", "Complex conjugate (unchanged for real numbers); modulus abs(z), argument see CMPLX mode", "conj(", "conj(2)"),
        FuncHelpEntry("res", CAT_CPLXF, "res(f(z), z0)", "Residue: automatically determines pole order (1–8), explicitly states removable singularity, rejects essential singularity", "res(", "res(1/z, 0)"),
        FuncHelpEntry("cint", CAT_CPLXF, "cint(f(z), p1, …)", "Residue theorem contour integral ∮f dz = 2πi·ΣRes (lists all poles inside contour)", "cint(", "cint(1/z, 0)"),
    )

    /** Grouped by category (maintains definition order) */
    @JvmStatic
    fun byCategory(): List<Pair<String, List<FuncHelpEntry>>> {
        val order = ArrayList<String>()
        val map = LinkedHashMap<String, MutableList<FuncHelpEntry>>()
        for (e in ALL) {
            if (!map.containsKey(e.category)) {
                map[e.category] = ArrayList()
                order.add(e.category)
            }
            map[e.category]!!.add(e)
        }
        return order.map { it to (map[it]!!.toList()) }
    }

    @JvmField
    val COUNT: Int = ALL.size
}
