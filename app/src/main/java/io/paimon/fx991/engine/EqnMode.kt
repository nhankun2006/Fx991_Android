package io.paimon.fx991.engine

import kotlin.math.abs

// ---------------------------------------------------------------------------
// Batch D: Equation mode EQN
//
// · Polynomial equations (degree 2 / 3 / 4): coefficients → expression string → reuses
//   exact root logic from main line EquationSolver (rational roots / surds / complex roots);
//   when exact solving fails (degree 3/4 without rational roots), Durand-Kerner numerical fallback
//   provides all roots (including complex roots).
// · Simultaneous linear systems (2–4 unknowns): Gaussian elimination + exact fractions,
//   No Solution / Infinite Solutions explicitly reported.
//   (Main line EquationSolver only supports 3 unknowns x/y/z; here implemented independently up to 4 unknowns w.)
// ---------------------------------------------------------------------------

object EqnMode {

    /** Names of unknowns in simultaneous equations */
    private val VAR_NAMES = listOf("x", "y", "z", "w")

    /** Parses coefficient input (decimal / integer → exact rational) */
    fun parseCoeff(s: String, what: String): Rational {
        val t = s.trim().replace('−', '-')
        if (t.isEmpty()) throw NumericError("Please enter $what")
        return Rational.fromDecimal(t) ?: throw NumericError("$what is not a valid number")
    }

    // -----------------------------------------------------------------------
    // Polynomial equations
    // -----------------------------------------------------------------------

    /**
     * Solves nth degree polynomial equation. coeffsDesc from highest degree to constant term, length n+1 (n = 2 / 3 / 4).
     * Gives exact roots when possible; otherwise numerical roots (including complex roots).
     */
    fun solvePolynomial(coeffsDesc: List<Rational>): SolveResult {
        val n = coeffsDesc.size - 1
        if (n < 1) throw NumericError("Degree must be at least 1")
        if (coeffsDesc[0].isZero) throw NumericError("Leading coefficient cannot be 0")
        val expr = polyString(coeffsDesc) + "=0"
        val r = EquationSolver.solve(expr, AngleMode.DEG)
        if (r.kind != SolveKind.ERROR) return r
        // Degree 3 / 4 without rational roots → exact solving failed, fall back to numerical
        return numericPoly(coeffsDesc)
    }

    /** Coefficient list → expression string of form "1x^2-3x+2" (zero coefficient terms omitted) */
    fun polyString(coeffsDesc: List<Rational>): String {
        val n = coeffsDesc.size - 1
        val sb = StringBuilder()
        var terms = 0
        for (i in coeffsDesc.indices) {
            val c = coeffsDesc[i]
            if (c.isZero) continue
            val deg = n - i
            sb.append(coeffTerm(c, terms == 0))
            when (deg) {
                0 -> Unit
                1 -> sb.append("x")
                else -> sb.append("x^").append(deg)
            }
            terms++
        }
        if (terms == 0) sb.append("0")
        return sb.toString()
    }

    /**
     * Expression fragment for a single coefficient (includes sign; non-first positive coefficients get +).
     * Coefficients parsed via fromDecimal only contain factors 2 / 5 in denominator, finite decimal expansion → exact round-trip.
     */
    private fun coeffTerm(c: Rational, first: Boolean): String {
        val neg = c.signum < 0
        val mag = if (neg) c.negate() else c
        val body = if (mag.isInteger) {
            mag.num.toString()
        } else {
            mag.toBigDecimalString(40).trimEnd('0').trimEnd('.')
        }
        return when {
            first && !neg -> body
            first -> "-$body"
            neg -> "-$body"
            else -> "+$body"
        }
    }

    // ---- Durand-Kerner numerical fallback ----

    private class Cx(val re: Double, val im: Double)

    /**
     * Durand-Kerner finds all roots of an nth degree polynomial (including complex roots).
     * coeffsDesc from highest degree to constant term; returns array of length 2n [re0, im0, re1, im1, …].
     */
    fun durandKerner(coeffsDesc: DoubleArray): DoubleArray {
        val n = coeffsDesc.size - 1
        require(n >= 1) { "degree >= 1" }
        val lead = coeffsDesc[0]
        require(lead != 0.0) { "leading coefficient != 0" }
        val c = DoubleArray(n + 1) { coeffsDesc[it] / lead } // Monic normalization

        // Cauchy root radius upper bound (monic polynomial): |x| ≤ 1 + max|c_i|
        var m = 0.0
        for (i in 1..n) m = maxOf(m, abs(c[i]))
        val radius = 1.0 + m

        // Initial values: (0.4 + 0.9i)^(k+1) × radius — classical choice to avoid symmetry traps
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        var br = 0.4
        var bi = 0.9
        for (k in 0 until n) {
            re[k] = br * radius
            im[k] = bi * radius
            val nr = br * 0.4 - bi * 0.9
            val ni = br * 0.9 + bi * 0.4
            br = nr
            bi = ni
        }

        var iter = 0
        while (iter < 500) {
            iter++
            var maxDelta = 0.0
            for (k in 0 until n) {
                // p = f(roots[k]) (Horner)
                var pr = c[0]
                var pi = 0.0
                for (j in 1..n) {
                    val nr = pr * re[k] - pi * im[k] + c[j]
                    val ni = pr * im[k] + pi * re[k]
                    pr = nr
                    pi = ni
                }
                // denom = ∏_{j≠k} (roots[k] − roots[j])
                var dr = 1.0
                var di = 0.0
                for (j in 0 until n) {
                    if (j == k) continue
                    val er = re[k] - re[j]
                    val ei = im[k] - im[j]
                    val nr = dr * er - di * ei
                    val ni = dr * ei + di * er
                    dr = nr
                    di = ni
                }
                var den = dr * dr + di * di
                if (den < 1e-300) {
                    dr += 1e-9 // Initial value collision fallback: perturbation
                    den = dr * dr + di * di
                }
                val qr = (pr * dr + pi * di) / den
                val qi = (pi * dr - pr * di) / den
                re[k] -= qr
                im[k] -= qi
                val d = abs(qr) + abs(qi)
                if (d > maxDelta) maxDelta = d
            }
            if (maxDelta < 1e-13) break
        }
        val out = DoubleArray(2 * n)
        for (k in 0 until n) {
            out[2 * k] = re[k]
            out[2 * k + 1] = im[k]
        }
        return out
    }

    /** Degree 3 / 4 polynomials where exact solving failed: numerically yields all roots (real roots first, complex roots merged into conjugate pairs) */
    private fun numericPoly(coeffsDesc: List<Rational>): SolveResult {
        val cf = DoubleArray(coeffsDesc.size) { coeffsDesc[it].toDouble() }
        val flat = durandKerner(cf)
        val roots = (0 until flat.size / 2).map { Cx(flat[2 * it], flat[2 * it + 1]) }

        fun isReal(c: Cx): Boolean = abs(c.im) <= 1e-7 * (1.0 + abs(c.re))

        // Real roots sorted ascending first; complex roots sorted by real part and |imaginary part| second
        val sorted = roots.sortedWith(
            compareBy({ if (isReal(it)) 0 else 1 }, { it.re }, { abs(it.im) }),
        )
        val texts = ArrayList<String>()
        val items = ArrayList<SolveItem>()
        val used = BooleanArray(sorted.size)
        for (i in sorted.indices) {
            if (used[i]) continue
            used[i] = true
            val r = sorted[i]
            if (isReal(r)) {
                val s = CalcEngine.format(r.re)
                texts.add(s)
                items.add(SolveItem("x = $s", s))
            } else {
                // Find conjugate, merge into a ± bi
                var conj = -1
                for (j in sorted.indices) {
                    if (used[j]) continue
                    val q = sorted[j]
                    if (abs(q.re - r.re) < 1e-7 * (1.0 + abs(r.re)) &&
                        abs(q.im + r.im) < 1e-7 * (1.0 + abs(r.im))
                    ) {
                        conj = j
                        break
                    }
                }
                if (conj >= 0) used[conj] = true
                val a = CalcEngine.format(r.re)
                val b = CalcEngine.format(abs(r.im))
                val t = "$a ± ${b}i"
                texts.add(t)
                items.add(SolveItem("x = $t", null))
            }
        }
        return SolveResult(
            SolveKind.SOLUTIONS,
            "x = " + texts.joinToString(", "),
            items,
            note = "Numerical solution: this equation has no rational roots that can be solved exactly; the following are Durand-Kerner numerical roots (including complex roots)",
        )
    }

    // -----------------------------------------------------------------------
    // Simultaneous linear equations (2–4 unknowns, Gaussian elimination + exact fractions)
    // -----------------------------------------------------------------------

    /**
     * Solves system of n linear equations (2 ≤ n ≤ 4).
     * a[i][j] = coefficient of jth unknown in ith equation; b[i] = constant on right-hand side of ith equation.
     * Unique solution gives exact fractions; No Solution / Infinite Solutions explicitly reported.
     */
    fun solveLinear(a: List<List<Rational>>, b: List<Rational>): SolveResult {
        val n = b.size
        if (n < 2 || n > 4) throw NumericError("Number of unknowns must be 2..4")
        if (a.size != n || a.any { it.size != n }) throw NumericError("Coefficient matrix dimensions are invalid")

        // Augmented matrix (in-place elimination)
        val m = Array(n) { i -> Array(n + 1) { j -> if (j < n) a[i][j] else b[i] } }
        var rank = 0
        val pivotCol = IntArray(n) { -1 }
        for (col in 0 until n) {
            var sel = -1
            for (r in rank until n) {
                if (!m[r][col].isZero) {
                    sel = r
                    break
                }
            }
            if (sel < 0) continue
            val tmp = m[rank]; m[rank] = m[sel]; m[sel] = tmp
            val pv = m[rank][col]
            for (cc in 0..n) m[rank][cc] = m[rank][cc].div(pv)
            for (r in 0 until n) {
                if (r == rank) continue
                val f = m[r][col]
                if (f.isZero) continue
                for (cc in 0..n) m[r][cc] = m[r][cc].minus(f.times(m[rank][cc]))
            }
            pivotCol[rank] = col
            rank++
        }
        // Inconsistent: 0 = non-zero
        for (r in rank until n) {
            if (!m[r][n].isZero) {
                return SolveResult(SolveKind.NONE, "No Solution", note = "System is inconsistent (0 = non-zero row occurred during reduction)")
            }
        }
        if (rank < n) {
            return SolveResult(
                SolveKind.INFINITE, "Infinite Solutions",
                note = "Number of independent equations ($rank) is less than unknowns ($n), free variables exist",
            )
        }
        val values = arrayOfNulls<Rational>(n)
        for (r in 0 until rank) {
            val colIdx = pivotCol[r]
            if (colIdx >= 0) values[colIdx] = m[r][n]
        }
        val parts = ArrayList<String>()
        val items = ArrayList<SolveItem>()
        for (j in 0 until n) {
            val v = values[j] ?: Rational.ZERO
            val s = CalcEngine.formatRational(v, false)
            parts.add("${VAR_NAMES[j]} = $s")
            items.add(SolveItem("${VAR_NAMES[j]} = $s", s))
        }
        return SolveResult(SolveKind.SOLUTIONS, parts.joinToString(", "), items)
    }
}
