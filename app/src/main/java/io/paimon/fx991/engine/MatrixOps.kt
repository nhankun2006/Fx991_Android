package io.paimon.fx991.engine

// ---------------------------------------------------------------------------
// Batch C: Matrix operations (MatA–MatD, max 4×4; pure Kotlin, JVM regressible)
//
// Elements are dual-track Values: addition, subtraction, multiplication, determinant,
// and inverse of integer/fraction matrices all preserve exact fractions.
// ---------------------------------------------------------------------------

/** Matrix (row-major, elements are dual-track Values) */
class Matrix(val rows: Int, val cols: Int, val cells: List<Value>) {

    init {
        if (rows !in 1..MAX_DIM || cols !in 1..MAX_DIM) {
            throw NumericError("Matrix dimensions must be between 1..$MAX_DIM")
        }
        if (cells.size != rows * cols) {
            throw NumericError("Matrix element count does not match dimensions")
        }
    }

    operator fun get(r: Int, c: Int): Value = cells[r * cols + c]

    /** Whether matrix elements are all on the exact track */
    val exact: Boolean get() = cells.all { it.exact != null }

    fun plus(o: Matrix): Matrix {
        requireSame(o)
        return Matrix(rows, cols, cells.indices.map { ExactMath.add(cells[it], o.cells[it]) })
    }

    fun minus(o: Matrix): Matrix {
        requireSame(o)
        return Matrix(rows, cols, cells.indices.map { ExactMath.sub(cells[it], o.cells[it]) })
    }

    fun times(o: Matrix): Matrix {
        if (cols != o.rows) throw NumericError("Matrix multiplication requires left columns = right rows")
        val out = ArrayList<Value>(rows * o.cols)
        for (i in 0 until rows) {
            for (j in 0 until o.cols) {
                var s = ExactMath.zero()
                for (k in 0 until cols) s = ExactMath.add(s, ExactMath.mul(this[i, k], o[k, j]))
                out.add(s)
            }
        }
        return Matrix(rows, o.cols, out)
    }

    fun scalar(k: Value): Matrix = Matrix(rows, cols, cells.map { ExactMath.mul(it, k) })

    fun transpose(): Matrix {
        val out = ArrayList<Value>(rows * cols)
        for (j in 0 until cols) for (i in 0 until rows) out.add(this[i, j])
        return Matrix(cols, rows, out)
    }

    /** Determinant (square matrix; orders 1..4 expanded by minors, exact track preferred) */
    fun det(): Value {
        if (rows != cols) throw NumericError("Determinant requires a square matrix")
        return detOf(cells, rows)
    }

    /** Inverse matrix (throws on non-invertible) */
    fun inverse(): Matrix {
        if (rows != cols) throw NumericError("Inverse requires a square matrix")
        val n = rows
        val d = det()
        if (ExactMath.isZero(d)) throw NumericError("Matrix is not invertible (determinant is 0)")
        val out = ArrayList<Value>(n * n)
        for (i in 0 until n) {
            for (j in 0 until n) {
                // (i,j) = cofactor (j,i) / det
                val cof = cofactor(this, j, i, n)
                out.add(ExactMath.div(cof, d))
            }
        }
        return Matrix(n, n, out)
    }

    /** Multi-line text (elements in each row separated by two spaces) */
    fun format(): String {
        val sb = StringBuilder()
        for (i in 0 until rows) {
            if (i > 0) sb.append('\n')
            for (j in 0 until cols) {
                if (j > 0) sb.append("  ")
                sb.append(ExactMath.str(this[i, j]))
            }
        }
        return sb.toString()
    }

    override fun toString(): String = format()

    private fun requireSame(o: Matrix) {
        if (rows != o.rows || cols != o.cols) throw NumericError("Matrix dimensions do not match")
    }

    private fun detOf(c: List<Value>, n: Int): Value {
        if (n == 1) return c[0]
        if (n == 2) return ExactMath.sub(
            ExactMath.mul(c[0], c[3]), ExactMath.mul(c[1], c[2])
        )
        var s = ExactMath.zero()
        for (j in 0 until n) {
            val m = minor(c, n, 0, j)
            val term = ExactMath.mul(c[j], detOf(m, n - 1))
            s = if (j % 2 == 0) ExactMath.add(s, term) else ExactMath.sub(s, term)
        }
        return s
    }

    /** (n−1)th order minor after removing row r and column cc */
    private fun minor(c: List<Value>, n: Int, r: Int, cc: Int): List<Value> {
        val out = ArrayList<Value>((n - 1) * (n - 1))
        for (i in 0 until n) {
            if (i == r) continue
            for (j in 0 until n) {
                if (j == cc) continue
                out.add(c[i * n + j])
            }
        }
        return out
    }

    /** Cofactor (−1)^(i+j) · M(i,j) */
    private fun cofactor(m: Matrix, i: Int, j: Int, n: Int): Value {
        val mn = minor(m.cells, n, i, j)
        val d = detOf(mn, n - 1)
        return if ((i + j) % 2 == 0) d else ExactMath.neg(d)
    }

    companion object {
        const val MAX_DIM = 4

        @JvmStatic
        fun of(rows: Int, cols: Int, cells: List<Value>): Matrix = Matrix(rows, cols, cells)

        /** Constructs from multiple integers (convenient for Java regression tests) */
        @JvmStatic
        fun ofInts(rows: Int, cols: Int, values: IntArray): Matrix {
            val list = values.map { ExactMath.long(it.toLong()) }
            return Matrix(rows, cols, list)
        }

        @JvmStatic
        fun identity(n: Int): Matrix {
            if (n !in 1..MAX_DIM) throw NumericError("Matrix dimensions must be between 1..$MAX_DIM")
            val out = ArrayList<Value>(n * n)
            for (i in 0 until n) for (j in 0 until n) {
                out.add(if (i == j) ExactMath.one() else ExactMath.zero())
            }
            return Matrix(n, n, out)
        }

        /** All-zero matrix */
        @JvmStatic
        fun zeros(rows: Int, cols: Int): Matrix =
            Matrix(rows, cols, List(rows * cols) { ExactMath.zero() })
    }
}

/** MatA–MatD four matrix variables */
class MatrixStore {

    private val mats = LinkedHashMap<String, Matrix?>()

    fun set(name: String, m: Matrix?) {
        key(name)
        mats[name] = m
    }

    fun get(name: String): Matrix? {
        key(name)
        return mats[name]
    }

    fun clearAll() {
        mats.clear()
    }

    fun snapshot(): Map<String, Matrix?> = LinkedHashMap(mats)

    private fun key(name: String) {
        if (name !in NAMES) throw NumericError("Unknown matrix variable $name")
    }

    companion object {
        @JvmField
        val NAMES: List<String> = listOf("A", "B", "C", "D")
    }
}
