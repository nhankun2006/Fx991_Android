package io.paimon.fx991.engine

import java.math.BigInteger
import kotlin.math.abs
import kotlin.math.floor

// ---------------------------------------------------------------------------
// Main line direct solve (REFERENCE item 7)
//
// Decision rules:
//   · Expression contains unknown variables (x / y / z) and `=` → solve as equation
//   · No unknown variables → normal evaluation (handled by caller)
//   · Variable assignment uses STO panel / ▶, not `=`
//
// Single variable: polynomial gives all roots (including complex roots, gives exact fractions / surds when possible);
//       transcendental equations automatically scan multiple roots from multiple initial values.
// System of equations: separated by `,` or `;`; linear uses Gaussian elimination + exact fractions, nonlinear uses numerical Newton.
// No Solution / Infinite Solutions are explicitly reported.
// ---------------------------------------------------------------------------

enum class SolveKind { SOLUTIONS, NONE, INFINITE, ERROR }

/** A root that can be inserted into the main line one by one (empty insert means non-insertable, e.g. complex roots) */
class SolveItem(val label: String, val insert: String?)

class SolveResult(
    val kind: SolveKind,
    /** Main line display text */
    val text: String,
    val items: List<SolveItem> = emptyList(),
    val note: String = "",
)

// ---------------------------------------------------------------------------

private typealias Mono = List<Int>

private class RootText(val display: String, val insert: String?)

/** A batch of roots + whether fallback to approximate numerical values (true when surd is too large / complex for exact form) */
private class RootList(val roots: List<RootText>, val approx: Boolean)

object EquationSolver {

    private val VARS3 = listOf("x", "y", "z")

    /** Contains equals sign → delegate to equation solver (whether truly treated as equation determined by variables) */
    @JvmStatic
    fun looksLikeEquation(expr: String): Boolean = expr.contains('=')

    /** Unknown variables appearing in expression (in order of x / y / z) */
    @JvmStatic
    fun unknowns(expr: String): List<String> {
        val found = LinkedHashSet<String>()
        var i = 0
        while (i < expr.length) {
            val c = expr[i]
            if (c == 'x' || c == 'y' || c == 'z') {
                // Only counted at "independent variable" position (cannot be preceded/followed by letters, like x in exp / preAns)
                val prev = if (i > 0) expr[i - 1] else ' '
                val next = if (i + 1 < expr.length) expr[i + 1] else ' '
                if (!prev.isLetter() && !next.isLetter()) found.add(c.toString())
            }
            i++
        }
        return VARS3.filter { found.contains(it) }
    }

    /**
     * Main line solve entry. expr should be the full string after auto-completing right parentheses.
     */
    @JvmStatic
    fun solve(expr: String, mode: AngleMode): SolveResult {
        val parts = splitTop(expr, ",;")
        val eqs = ArrayList<Pair<String, String>>()
        for (p in parts) {
            if (p.isBlank()) continue
            val sides = splitTop(p, "=")
            if (sides.size != 2) throw CalcSyntaxError("Syntax ERROR")
            if (sides[1].isBlank()) throw CalcSyntaxError("Syntax ERROR")
            eqs.add(sides[0] to sides[1])
        }
        if (eqs.isEmpty()) throw CalcSyntaxError("Syntax ERROR")

        val vars = unknowns(expr)
        if (vars.isEmpty()) throw CalcMathError("No unknown variable in equation")
        return if (eqs.size == 1 && vars.size == 1) {
            solveSingle(vars[0], eqs[0].first, eqs[0].second, mode)
        } else {
            solveSystem(eqs, vars, mode)
        }
    }

    // -----------------------------------------------------------------------
    // Single variable equation
    // -----------------------------------------------------------------------

    private fun solveSingle(varName: String, lhsS: String, rhsS: String, mode: AngleMode): SolveResult {
        val lhs = parse(lhsS)
        val rhs = parse(rhsS)
        val idx = mapOf("x" to 0, "y" to 1, "z" to 2)
        val v = idx.getValue(varName)

        val pl = PolyTools.from(lhs, idx, 3)
        val pr = PolyTools.from(rhs, idx, 3)
        val coeffs = if (pl != null && pr != null) {
            val diff = PolyTools.sub(3, pl, pr)
            PolyTools.univariate(diff, v)
        } else null

        if (coeffs != null) return solvePolynomial(varName, coeffs)
        return solveNumericSingle(varName, lhs, rhs, mode)
    }

    private fun solvePolynomial(varName: String, raw: List<Rational>): SolveResult {
        val c = trim(raw)
        if (c.isEmpty()) {
            return SolveResult(SolveKind.INFINITE, "Infinite Solutions", note = "Identity: satisfied for any $varName")
        }
        if (c.size == 1) {
            return SolveResult(SolveKind.NONE, "No Solution", note = "Equation does not contain $varName after simplification and is non-zero")
        }
        val deg = c.size - 1
        val solved: RootList? = when (deg) {
            1 -> RootList(listOfRoots(c[0].div(c[1]).negate(), null), approx = false)
            2 -> quadraticRoots(c[0], c[1], c[2])
            else -> rationalFactorRoots(c)
        }
        if (solved == null) {
            return SolveResult(SolveKind.ERROR, "Root Finding Failed", note = "Higher-degree polynomial failed to yield exact roots")
        }
        val roots = solved.roots
        if (roots.isEmpty()) return SolveResult(SolveKind.NONE, "No Solution")
        // Sorted by magnitude when all are rational roots (looks good and matches reference calculator)
        val ordered = if (roots.all { it.insert != null }) {
            roots.sortedBy { it.insert!!.toDoubleOrNull() ?: 0.0 }
        } else roots
        val text = varName + " = " + ordered.joinToString(", ") { it.display }
        val items = ordered.map { SolveItem("$varName = ${it.display}", it.insert) }
        val note = if (solved.approx) "Approximate numerical solution: surd too large or complex to simplify exactly" else ""
        return SolveResult(SolveKind.SOLUTIONS, text, items, note)
    }

    private fun listOfRoots(r: Rational, exact: Rational?): List<RootText> =
        listOf(RootText(rt(r), rt(exact ?: r)))

    private fun rt(r: Rational): String = CalcEngine.formatRational(r, false)

    /**
     * Quadratic equation ax² + bx + c = 0:
     * Discriminant has exact square root → exact fraction; no exact square root → exact surd (e.g. ±√2); negative → complex roots (e.g. ±i, (−1±√3i)/2).
     * Surd too large / complex → numerical fallback marked with approx (UI indicates approximation).
     */
    private fun quadraticRoots(c0: Rational, c1: Rational, c2: Rational): RootList {
        val disc = c1.times(c1).minus(c2.times(c0).times(Rational.of(4)))
        val den = c2.times(Rational.of(2))
        val p = c1.negate().div(den)
        if (disc.isZero) return RootList(listOfRoots(p, null), approx = false)
        if (disc.signum > 0) {
            val s = disc.sqrtExact()
            if (s != null) {
                return RootList(
                    listOf(
                        RootText(rt(p.plus(s.div(den))), rt(p.plus(s.div(den)))),
                        RootText(rt(p.minus(s.div(den))), rt(p.minus(s.div(den)))),
                    ),
                    approx = false,
                )
            }
        }
        val neg = disc.signum < 0
        val mag = if (neg) disc.negate() else disc
        val simp = simplifySqrt(mag) ?: return numericQuadratic(p, mag, den, neg)
        val (outer, rad) = simp
        // Take absolute value of surd coefficient: q<0 when leading coefficient is negative, but ± written in pairs is independent of sign (otherwise "±−i" would appear)
        val q = outer.div(den).abs()
        val radPart = radText(q, rad, neg)
        return if (p.isZero) {
            RootList(listOf(RootText("\u00B1$radPart", null)), approx = false)
        } else if (p.den == q.den && p.den > java.math.BigInteger.ONE) {
            // Combined same denominator format: (-1 ± √3i)/2 (like cube roots of unity, easier to read than -1/2 ± 1/2√3i)
            val core = radCore(rad, neg)
            val qn = q.num
            val qpart = if (qn == java.math.BigInteger.ONE) core else qn.toString() + core
            RootList(listOf(RootText("(${p.num} \u00B1 $qpart)/${p.den}", null)), approx = false)
        } else {
            RootList(listOf(RootText("${rt(p)} \u00B1 $radPart", null)), approx = false)
        }
    }

    /** Core surd part (without coefficient): √m / √m·i / i */
    private fun radCore(m: Long, imaginary: Boolean): String {
        val i = if (imaginary) "i" else ""
        return if (m == 1L) (if (imaginary) "i" else "1") else "\u221A$m$i"
    }

    /** Surd part: form of √m (with i for negative), q is coefficient */
    private fun radText(q: Rational, m: Long, imaginary: Boolean): String {
        val i = if (imaginary) "i" else ""
        val core = if (m == 1L) "1" else "\u221A$m"
        val coef = if (m == 1L) {
            if (q == Rational.ONE) "i" else if (q == Rational.of(-1L)) "\u2212i" else rt(q) + "i"
        } else {
            when {
                q == Rational.ONE -> core + i
                q == Rational.of(-1L) -> "\u2212" + core + i
                else -> rt(q) + core + i
            }
        }
        return coef
    }

    /** Surd too large / complex → numerical fallback (marked approx, UI indicates approximation) */
    private fun numericQuadratic(p: Rational, mag: Rational, den: Rational, imaginary: Boolean): RootList {
        val half = mag.toDouble().let { kotlin.math.sqrt(it) }.let { it / den.abs().toDouble() }
        val pr = p.toDouble()
        return RootList(
            if (imaginary) {
                listOf(RootText("${CalcEngine.format(pr)} \u00B1 ${CalcEngine.format(half)}i", null))
            } else {
                listOf(
                    RootText(CalcEngine.format(pr + half), CalcEngine.format(pr + half)),
                    RootText(CalcEngine.format(pr - half), CalcEngine.format(pr - half)),
                )
            },
            approx = true,
        )
    }

    /** Degree 3 and above: extract rational roots first (rational root theorem) to reduce degree, then solve remaining quadratic by formula */
    private fun rationalFactorRoots(coeffs: List<Rational>): RootList? {
        var cur = coeffs
        val out = ArrayList<RootText>()
        var approx = false
        var guard = 0
        while (cur.size - 1 > 2 && guard++ < 8) {
            val root = findRationalRoot(cur) ?: return null
            out.add(RootText(rt(root), rt(root)))
            val (q, rest) = syntheticDivide(cur, root)
            if (!q.isZero) return null
            cur = trim(rest)
            if (cur.size <= 1) break
        }
        val deg = cur.size - 1
        when (deg) {
            2 -> {
                val quad = quadraticRoots(cur[0], cur[1], cur[2])
                out.addAll(quad.roots)
                approx = quad.approx
            }
            1 -> out.addAll(listOfRoots(cur[0].div(cur[1]).negate(), null))
            0 -> Unit
            else -> return null
        }
        return RootList(dedupe(out), approx)
    }

    private fun findRationalRoot(coeffs: List<Rational>): Rational? {
        val a0 = coeffs[0]
        val an = coeffs[coeffs.size - 1]
        if (a0.isZero) return Rational.ZERO
        val ps = divisors(a0.num.abs())
        val qs = divisors(an.num.abs())
        for (p in ps) {
            for (q in qs) {
                for (sign in intArrayOf(1, -1)) {
                    val cand = Rational.of(BigInteger.valueOf(sign * p), BigInteger.valueOf(q))
                    if (evalPoly(coeffs, cand).isZero) return cand
                }
            }
        }
        return null
    }

    private fun divisors(v: BigInteger): List<Long> {
        if (v > BigInteger.valueOf(1_000_000L)) return emptyList()
        val n = v.toLong()
        val out = ArrayList<Long>()
        var d = 1L
        while (d * d <= n) {
            if (n % d == 0L) {
                out.add(d)
                if (d != n / d) out.add(n / d)
            }
            d++
        }
        return out
    }

    private fun evalPoly(c: List<Rational>, x: Rational): Rational {
        var acc = Rational.ZERO
        for (i in c.indices.reversed()) acc = acc.times(x).plus(c[i])
        return acc
    }

    /** Synthetic division: returns (remainder, quotient) */
    private fun syntheticDivide(c: List<Rational>, root: Rational): Pair<Rational, List<Rational>> {
        val n = c.size - 1
        val out = MutableList(n) { Rational.ZERO }
        var carry = c[n]
        out[n - 1] = carry
        for (i in n - 1 downTo 0) {
            carry = c[i].plus(carry.times(root))
            if (i > 0) out[i - 1] = carry
        }
        return carry to out
    }

    private fun dedupe(list: List<RootText>): List<RootText> {
        val seen = LinkedHashSet<String>()
        val out = ArrayList<RootText>()
        for (r in list) if (seen.add(r.display)) out.add(r)
        return out
    }

    // -----------------------------------------------------------------------
    // Single-variable transcendental equations: multi-initial-value scanning
    // -----------------------------------------------------------------------

    private fun solveNumericSingle(varName: String, lhs: Node, rhs: Node, mode: AngleMode): SolveResult {
        val (lo, hi) = scanRange(mode)
        val roots = numericRoots(varName, lhs, rhs, lo, hi, 4000, mode)
        if (roots.isEmpty()) {
            return SolveResult(
                SolveKind.NONE, "No Solution",
                note = "No real roots found in [${CalcEngine.format(lo)}, ${CalcEngine.format(hi)}]",
            )
        }
        val cap = 12
        val shown = roots.take(cap)
        val suffix = angleSuffix(mode)
        val texts = shown.map { RootText(CalcEngine.format(it) + suffix, CalcEngine.format(it)) }
        val more = if (roots.size > cap) " …" else ""
        val text = varName + " = " + texts.joinToString(", ") { it.display } + more
        val items = texts.map { SolveItem("$varName = ${it.display}", it.insert) }
        val note = "Numerical solution: automatically scanned ${roots.size} roots in [${CalcEngine.format(lo)}, ${CalcEngine.format(hi)}]"
        return SolveResult(SolveKind.SOLUTIONS, text, items, note)
    }

    private fun scanRange(mode: AngleMode): Pair<Double, Double> {
        val period = when (mode) {
            AngleMode.DEG -> 360.0
            AngleMode.GRAD -> 400.0
            AngleMode.RAD -> 2.0 * Math.PI
        }
        return -period to 3.0 * period
    }

    private fun angleSuffix(mode: AngleMode): String = when (mode) {
        AngleMode.DEG -> "\u00B0"
        AngleMode.GRAD -> " grad"
        AngleMode.RAD -> ""
    }

    private fun numericRoots(
        varName: String,
        lhs: Node,
        rhs: Node,
        lo: Double,
        hi: Double,
        samples: Int,
        mode: AngleMode,
    ): List<Double> {
        val f = fun(t: Double): Double? {
            val m = mapOf(varName to t)
            return try {
                val a = scalarEval(lhs, m, mode)
                val b = scalarEval(rhs, m, mode)
                val r = a - b
                if (r.isNaN() || r.isInfinite()) null else r
            } catch (_: Exception) {
                null
            }
        }
        val out = ArrayList<Double>()
        var prevT = lo
        var prevF = f(lo)
        for (i in 1..samples) {
            val t = lo + (hi - lo) * i / samples
            val ft = f(t)
            if (prevF != null && ft != null) {
                if (prevF == 0.0) addRoot(out, prevT)
                if ((prevF > 0.0) != (ft > 0.0)) {
                    val r = bisect(f, prevT, t)
                    if (r != null) addRoot(out, r)
                } else if (ft == 0.0) {
                    addRoot(out, t)
                }
            }
            prevT = t
            prevF = ft
        }
        return out.sorted()
    }

    private fun addRoot(out: MutableList<Double>, r: Double) {
        for (e in out) {
            val scale = 1.0 + maxOf(abs(e), abs(r))
            if (abs(e - r) <= 1e-7 * scale) return
        }
        out.add(r)
    }

    private fun bisect(f: (Double) -> Double?, lo0: Double, hi0: Double): Double? {
        var lo = lo0
        var hi = hi0
        var flo = f(lo) ?: return null
        var mid = (lo + hi) / 2.0
        var i = 0
        while (i < 200) {
            i++
            mid = (lo + hi) / 2.0
            val fm = f(mid) ?: return null
            if (abs(fm) < 1e-15 || (hi - lo) < 1e-14 * (1.0 + abs(mid))) break
            if ((flo > 0.0) != (fm > 0.0)) hi = mid else { lo = mid; flo = fm }
        }
        return mid
    }

    private fun scalarEval(node: Node, vars: Map<String, Double>, mode: AngleMode): Double =
        ValueEvaluator(
            mode, Value.Floating(0.0), Value.Floating(0.0), preAns = 0.0, vars = vars,
        ).eval(node).toDouble()

    // -----------------------------------------------------------------------
    // System of equations
    // -----------------------------------------------------------------------

    private fun solveSystem(
        eqs: List<Pair<String, String>>,
        varNames: List<String>,
        mode: AngleMode,
    ): SolveResult {
        val idx = mapOf("x" to 0, "y" to 1, "z" to 2)
        val polys = ArrayList<Map<Mono, Rational>>()
        for ((l, r) in eqs) {
            val pl = PolyTools.from(parse(l), idx, 3)
            val pr = PolyTools.from(parse(r), idx, 3)
            if (pl == null || pr == null) return numericSystem(eqs, varNames, mode)
            polys.add(PolyTools.sub(3, pl, pr))
        }
        val linear = polys.all { p -> p.keys.all { k -> k.sum() <= 1 } }
        if (!linear) return numericSystem(eqs, varNames, mode)

        // Construct augmented matrix (unknowns in varNames order)
        val k = varNames.size
        val m = Array(eqs.size) { arrayOfNulls<Rational>(k + 1) }
        for (i in polys.indices) {
            val p = polys[i]
            val constTerm = p[listOf(0, 0, 0)] ?: Rational.ZERO
            for (j in 0 until k) {
                val v = idx.getValue(varNames[j])
                val mono = MutableList(3) { 0 }.also { it[v] = 1 }
                m[i][j] = p[mono] ?: Rational.ZERO
            }
            m[i][k] = constTerm.negate()
        }
        return gaussian(m, varNames)
    }

    private fun gaussian(m: Array<Array<Rational?>>, varNames: List<String>): SolveResult {
        val rows = m.size
        val cols = varNames.size
        val hasSol = BooleanArray(rows) { false }
        var rank = 0
        val pivotCol = IntArray(rows) { -1 }
        for (col in 0 until cols) {
            var sel = -1
            for (r in rank until rows) {
                val v = m[r][col]
                if (v != null && !v.isZero) { sel = r; break }
            }
            if (sel < 0) continue
            val tmp = m[rank]; m[rank] = m[sel]; m[sel] = tmp
            val pv = m[rank][col]!!
            for (c in 0..cols) {
                m[rank][c] = m[rank][c]!!.div(pv)
            }
            for (r in 0 until rows) {
                if (r == rank) continue
                val factor = m[r][col]!!
                if (factor.isZero) continue
                for (c in 0..cols) {
                    m[r][c] = m[r][c]!!.minus(factor.times(m[rank][c]!!))
                }
            }
            pivotCol[rank] = col
            hasSol[rank] = true
            rank++
        }
        // Check inconsistent rows: 0 = non-zero
        for (r in rank until rows) {
            val b = m[r][cols]!!
            if (!b.isZero) return SolveResult(SolveKind.NONE, "No Solution", note = "Inconsistent system of equations")
        }
        if (rank < cols) {
            return SolveResult(
                SolveKind.INFINITE, "Infinite Solutions",
                note = "Number of independent equations ($rank) is less than unknowns ($cols), free variables exist",
            )
        }
        val values = arrayOfNulls<Rational>(cols)
        for (r in 0 until rank) {
            val col = pivotCol[r]
            if (col < 0) continue
            values[col] = m[r][cols]
        }
        val parts = ArrayList<String>()
        val items = ArrayList<SolveItem>()
        for (j in 0 until cols) {
            val v = values[j] ?: Rational.ZERO
            parts.add("${varNames[j]} = ${rt(v)}")
            items.add(SolveItem("${varNames[j]} = ${rt(v)}", rt(v)))
        }
        return SolveResult(SolveKind.SOLUTIONS, parts.joinToString(", "), items)
    }

    /** Non-linear system of equations: numerical Newton (multiple starting points) */
    private fun numericSystem(
        eqs: List<Pair<String, String>>,
        varNames: List<String>,
        mode: AngleMode,
    ): SolveResult {
        val n = varNames.size
        if (n == 0 || n > 3) return SolveResult(SolveKind.ERROR, "Root Finding Failed", note = "Too many unknowns")
        val nodes = eqs.map { (l, r) -> parse(l) to parse(r) }
        val roots = ArrayList<DoubleArray>()
        val starts = ArrayList<DoubleArray>()
        starts.add(DoubleArray(n))
        for (j in 0 until n) {
            for (s in doubleArrayOf(1.0, -1.0, 2.0, -2.0)) {
                val p = DoubleArray(n)
                p[j] = s
                starts.add(p)
            }
        }
        for (st in starts) {
            val r = newtonSystem(nodes, varNames, st, mode) ?: continue
            if (roots.none { same(it, r) }) roots.add(r)
        }
        if (roots.isEmpty()) {
            return SolveResult(SolveKind.ERROR, "Root Finding Failed", note = "Non-linear system did not converge to a solution")
        }
        val parts = ArrayList<String>()
        val items = ArrayList<SolveItem>()
        for (j in varNames.indices) {
            val vals = roots.map { CalcEngine.format(it[j]) }
            val text = vals.joinToString(", ")
            parts.add("${varNames[j]} = $text")
            if (roots.size == 1) items.add(SolveItem("${varNames[j]} = $text", vals[0]))
        }
        return SolveResult(
            SolveKind.SOLUTIONS, parts.joinToString(", "), items,
            note = if (roots.size > 1) "Found ${roots.size} numerical solution sets (omitted)" else "Numerical solution",
        )
    }

    private fun same(a: DoubleArray, b: DoubleArray): Boolean =
        a.indices.all { abs(a[it] - b[it]) <= 1e-6 * (1.0 + abs(a[it])) }

    private fun newtonSystem(
        nodes: List<Pair<Node, Node>>,
        varNames: List<String>,
        start: DoubleArray,
        mode: AngleMode,
    ): DoubleArray? {
        val n = varNames.size
        val x = start.copyOf()
        var iter = 0
        while (iter < 200) {
            iter++
            val f = DoubleArray(n) { evalDiff(nodes[it], varNames, x, mode) ?: return null }
            if (f.indices.all { abs(f[it]) < 1e-13 }) return x
            // Numerical Jacobian
            val jac = Array(n) { DoubleArray(n) }
            for (c in 0 until n) {
                val h = 1e-7 * maxOf(1.0, abs(x[c]))
                val xp = x.copyOf()
                xp[c] += h
                for (r in 0 until n) {
                    val fr = evalDiff(nodes[r], varNames, xp, mode) ?: return null
                    jac[r][c] = (fr - f[r]) / h
                }
            }
            val dx = solveLinear(jac, f) ?: return null
            var converged = true
            for (c in 0 until n) {
                x[c] -= dx[c]
                if (abs(dx[c]) > 1e-10 * (1.0 + abs(x[c]))) converged = false
            }
            if (converged) return x
        }
        return if (DoubleArray(n) { evalDiff(nodes[it], varNames, x, mode) ?: Double.MAX_VALUE }
                .all { abs(it) < 1e-8 }) x else null
    }

    private fun evalDiff(eq: Pair<Node, Node>, varNames: List<String>, x: DoubleArray, mode: AngleMode): Double? = try {
        val map = HashMap<String, Double>()
        for (i in varNames.indices) map[varNames[i]] = x[i]
        val f = ValueEvaluator(
            mode, Value.Floating(0.0), Value.Floating(0.0), preAns = 0.0, vars = map,
        )
        val v = f.eval(eq.first).toDouble() - f.eval(eq.second).toDouble()
        if (v.isNaN() || v.isInfinite()) null else v
    } catch (_: Exception) {
        null
    }

    /** Gaussian elimination solves J·dx = f (floating point) */
    private fun solveLinear(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val m = Array(n) { r -> DoubleArray(n + 1) { c -> if (c < n) a[r][c] else b[r] } }
        for (col in 0 until n) {
            var sel = col
            for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[sel][col])) sel = r
            if (abs(m[sel][col]) < 1e-14) return null
            val t = m[col]; m[col] = m[sel]; m[sel] = t
            for (r in col + 1 until n) {
                val f = m[r][col] / m[col][col]
                for (c in col..n) m[r][c] -= f * m[col][c]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            var s = m[r][n]
            for (c in r + 1 until n) s -= m[r][c] * x[c]
            x[r] = s / m[r][r]
        }
        return x
    }

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------

    private fun parse(s: String): Node = Parser(Lexer(s.trim()).lex()).parse()

    private fun trim(c: List<Rational>): List<Rational> {
        var n = c.size
        while (n > 0 && c[n - 1].isZero) n--
        return c.subList(0, n).toList()
    }

    /** Splits at delimiter when parentheses depth is 0 */
    private fun splitTop(s: String, seps: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        val sb = StringBuilder()
        for (ch in s) {
            when {
                ch == '(' -> { depth++; sb.append(ch) }
                ch == ')' -> { depth--; sb.append(ch) }
                depth == 0 && seps.indexOf(ch) >= 0 -> { out.add(sb.toString()); sb.clear() }
                else -> sb.append(ch)
            }
        }
        out.add(sb.toString())
        return out.map { it.trim() }
    }

    /** Splits √(a/b) into (coefficient, square-free integer): √(a/b) = coefficient · √m */
    private fun simplifySqrt(r: Rational): Pair<Rational, Long>? {
        val a = r.num.abs()
        val b = r.den
        if (b.bitLength() > 62) return null
        val k = a.multiply(b)
        if (k.bitLength() > 62) return null
        var m = k.toLong()
        var s = 1L
        var d = 2L
        while (d * d <= m && d <= 1_000_000L) {
            while (m % (d * d) == 0L) {
                m /= d * d
                s *= d
            }
            d++
        }
        if (d * d <= m) return null
        if (s > 1_000_000_000L) return null
        return Rational.of(s, b.toLong()) to m
    }
}

// ---------------------------------------------------------------------------
// Polynomials (multivariate, exponent vector as key; coefficients are exact rationals)
// ---------------------------------------------------------------------------

private object PolyTools {

    fun from(n: Node, varIndex: Map<String, Int>, nVars: Int): Map<Mono, Rational>? {
        return when (n) {
        is Node.Num -> {
            val lit = n.lit ?: return null
            val r = Rational.fromDecimal(lit) ?: return null
            mapOf(List(nVars) { 0 } to r)
        }
        is Node.XRef -> mono(varIndex["x"], nVars)
        is Node.YRef -> mono(varIndex["y"], nVars)
        is Node.ZRef -> mono(varIndex["z"], nVars)
        is Node.Neg -> from(n.a, varIndex, nVars)?.let { neg(it) }
        is Node.Add -> {
            val a = from(n.a, varIndex, nVars) ?: return null
            val b = from(n.b, varIndex, nVars) ?: return null
            add(a, b)
        }
        is Node.Sub -> {
            val a = from(n.a, varIndex, nVars) ?: return null
            val b = from(n.b, varIndex, nVars) ?: return null
            add(a, neg(b))
        }
        is Node.Mul -> {
            val a = from(n.a, varIndex, nVars) ?: return null
            val b = from(n.b, varIndex, nVars) ?: return null
            mul(a, b)
        }
        is Node.Div -> {
            val a = from(n.a, varIndex, nVars) ?: return null
            val b = from(n.b, varIndex, nVars) ?: return null
            val c = constantOf(b) ?: return null
            if (c.isZero) return null
            divBy(a, c)
        }
        is Node.Pow -> {
            val a = from(n.a, varIndex, nVars) ?: return null
            val e = (n.b as? Node.Num)?.lit?.toIntOrNull() ?: return null
            if (e < 0 || e > 12) return null
            powInt(a, e)
        }
        else -> null
        }
    }

    private fun mono(i: Int?, nVars: Int): Map<Mono, Rational>? {
        if (i == null) return null
        val m = MutableList(nVars) { 0 }
        m[i] = 1
        return mapOf(m to Rational.ONE)
    }

    private fun constantOf(p: Map<Mono, Rational>): Rational? {
        if (p.isEmpty()) return Rational.ZERO
        if (p.size != 1) return null
        val (k, v) = p.entries.first()
        return if (k.all { it == 0 }) v else null
    }

    fun add(a: Map<Mono, Rational>, b: Map<Mono, Rational>): Map<Mono, Rational> {
        val out = LinkedHashMap(a)
        for ((k, v) in b) {
            val cur = out[k]
            val nv = if (cur == null) v else cur.plus(v)
            if (nv.isZero) out.remove(k) else out[k] = nv
        }
        return out
    }

    fun sub(nVars: Int, a: Map<Mono, Rational>, b: Map<Mono, Rational>): Map<Mono, Rational> =
        add(a, neg(b))

    fun neg(a: Map<Mono, Rational>): Map<Mono, Rational> =
        a.mapValues { it.value.negate() }

    fun mul(a: Map<Mono, Rational>, b: Map<Mono, Rational>): Map<Mono, Rational> {
        val out = LinkedHashMap<Mono, Rational>()
        for ((ka, va) in a) {
            for ((kb, vb) in b) {
                val key = ka.indices.map { ka[it] + kb[it] }
                val v = va.times(vb)
                val cur = out[key]
                val nv = if (cur == null) v else cur.plus(v)
                if (nv.isZero) out.remove(key) else out[key] = nv
            }
        }
        return out
    }

    private fun divBy(a: Map<Mono, Rational>, c: Rational): Map<Mono, Rational> =
        a.mapValues { it.value.div(c) }

    private fun powInt(a: Map<Mono, Rational>, e: Int): Map<Mono, Rational> {
        var acc = mapOf(List(a.keys.firstOrNull()?.size ?: 0) { 0 } to Rational.ONE)
        var base = a
        var n = e
        while (n > 0) {
            if (n and 1 == 1) acc = mul(acc, base)
            n = n shr 1
            if (n > 0) base = mul(base, base)
        }
        return acc
    }

    /** Univariate conversion: extracts coefficient vector of a variable (index = exponent of that variable) */
    fun univariate(p: Map<Mono, Rational>, varIdx: Int): List<Rational>? {
        var maxDeg = 0
        for (k in p.keys) {
            if (k.indices.any { it != varIdx && k[it] != 0 }) return null
            if (k[varIdx] > maxDeg) maxDeg = k[varIdx]
        }
        val out = MutableList(maxDeg + 1) { Rational.ZERO }
        for ((k, v) in p) out[k[varIdx]] = v
        return out
    }
}
