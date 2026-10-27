package io.paimon.fx991.engine

import kotlin.math.floor

// ---------------------------------------------------------------------------
// Batch D: Function Table (TABLE)
//
// Input f(x) (optional g(x) for dual-function comparison), specify start / end / step to generate a numerical table.
// Evaluation reuses core engine CalcEngine.evaluateWith (trig functions follow angle mode).
// When a point is undefined (e.g. 1/x at x=0), that cell displays an error without aborting the entire table.
// ---------------------------------------------------------------------------

object TableGen {

    /** Maximum rows limit (paginated display to prevent generating too many at once) */
    const val MAX_ROWS = 200

    /** One row: f(x) and g(x) at x (null = undefined at this point / calculation error) */
    class TableRow(val x: Double, val f: Double?, val g: Double?)

    class TableData(val rows: List<TableRow>, val hasG: Boolean)

    fun parseNumber(s: String, what: String): Double =
        s.trim().toDoubleOrNull() ?: throw NumericError("$what is not a valid number")

    /**
     * Generate numerical table. [step] cannot be 0, and direction must match (end - start);
     * f(x) syntax errors are thrown immediately; single-point math errors only apply to that specific cell.
     */
    fun generate(
        fx: String,
        gx: String,
        start: Double,
        end: Double,
        step: Double,
        mode: AngleMode,
    ): TableData {
        val f = fx.trim()
        if (f.isEmpty()) throw NumericError("Please enter f(x)")
        val g = gx.trim()
        if (step.isNaN() || step == 0.0) throw NumericError("Step size cannot be 0")
        val span = end - start
        if (span.isNaN()) throw NumericError("Start / end values are not valid numbers")
        if (span * step < 0) throw NumericError("Step direction does not match start and end values")
        val count = if (span == 0.0) 1L else floor(span / step + 1e-9).toLong() + 1L
        if (count > MAX_ROWS) throw NumericError("Too many rows (limit $MAX_ROWS rows, please increase step size or narrow range)")

        // Preliminary syntax pre-check: syntax errors are thrown immediately, math errors are left to cells
        try {
            CalcEngine.evaluateWith(f, mode, start, 0.0)
        } catch (_: CalcSyntaxError) {
            throw NumericError("f(x) Syntax ERROR")
        } catch (_: Exception) {
            // Do not block single-point undefined
        }
        if (g.isNotEmpty()) {
            try {
                CalcEngine.evaluateWith(g, mode, start, 0.0)
            } catch (_: CalcSyntaxError) {
                throw NumericError("g(x) Syntax ERROR")
            } catch (_: Exception) {
            }
        }

        val rows = ArrayList<TableRow>()
        var i = 0L
        while (i < count) {
            val x = start + step * i
            val fv = try {
                CalcEngine.evaluateWith(f, mode, x, 0.0)
            } catch (_: Exception) {
                null
            }
            val gv = if (g.isEmpty()) null else try {
                CalcEngine.evaluateWith(g, mode, x, 0.0)
            } catch (_: Exception) {
                null
            }
            rows.add(TableRow(x, fv, gv))
            i++
        }
        return TableData(rows, g.isNotEmpty())
    }
}
