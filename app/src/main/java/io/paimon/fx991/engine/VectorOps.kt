package io.paimon.fx991.engine

import kotlin.math.acos
import kotlin.math.sqrt

// ---------------------------------------------------------------------------
// Batch C: 3D Vectors (VctA–VctD; pure Kotlin, JVM regression testable)
// Dual-track Value: integer / fractional components remain exact in addition, subtraction, dot product, and cross product.
// ---------------------------------------------------------------------------

/** 3D Vector */
class Vector3(val x: Value, val y: Value, val z: Value) {

    val exact: Boolean get() = x.exact != null && y.exact != null && z.exact != null

    fun plus(o: Vector3): Vector3 =
        Vector3(ExactMath.add(x, o.x), ExactMath.add(y, o.y), ExactMath.add(z, o.z))

    fun minus(o: Vector3): Vector3 =
        Vector3(ExactMath.sub(x, o.x), ExactMath.sub(y, o.y), ExactMath.sub(z, o.z))

    /** Scalar multiplication */
    fun scale(k: Value): Vector3 =
        Vector3(ExactMath.mul(x, k), ExactMath.mul(y, k), ExactMath.mul(z, k))

    /** Dot product A·B */
    fun dot(o: Vector3): Value = ExactMath.sum(
        listOf(
            ExactMath.mul(x, o.x),
            ExactMath.mul(y, o.y),
            ExactMath.mul(z, o.z),
        )
    )

    /** Cross product A×B */
    fun cross(o: Vector3): Vector3 = Vector3(
        ExactMath.sub(ExactMath.mul(y, o.z), ExactMath.mul(z, o.y)),
        ExactMath.sub(ExactMath.mul(z, o.x), ExactMath.mul(x, o.z)),
        ExactMath.sub(ExactMath.mul(x, o.y), ExactMath.mul(y, o.x)),
    )

    /** Magnitude |A| */
    fun norm(): Double = sqrt(
        x.toDouble() * x.toDouble() +
            y.toDouble() * y.toDouble() +
            z.toDouble() * z.toDouble()
    )

    /** Exact magnitude (yields rational number when sum of squares has an exact square root) */
    fun normExact(): Rational? {
        val ex = x.exact ?: return null
        val ey = y.exact ?: return null
        val ez = z.exact ?: return null
        val s = ex.times(ex).plus(ey.times(ey)).plus(ez.times(ez))
        return s.sqrtExact()
    }

    fun isZero(): Boolean = ExactMath.isZero(x) && ExactMath.isZero(y) && ExactMath.isZero(z)

    /** Normalize (throws on zero vector) */
    fun unit(): Vector3 {
        val n = norm()
        if (n == 0.0) throw NumericError("Cannot normalize zero vector")
        return Vector3(Value.of(x.toDouble() / n), Value.of(y.toDouble() / n), Value.of(z.toDouble() / n))
    }

    /** Angle between A and B (returned in current angle mode); throws on zero vector */
    fun angleTo(o: Vector3, mode: AngleMode): Double {
        val na = norm()
        val nb = o.norm()
        if (na == 0.0 || nb == 0.0) throw NumericError("Zero vector has no direction")
        val c = dot(o).toDouble() / (na * nb)
        return CalcEngine.fromRadians(mode, acos(c.coerceIn(-1.0, 1.0)))
    }

    fun format(): String = "(" +
        ExactMath.str(x) + ", " + ExactMath.str(y) + ", " + ExactMath.str(z) + ")"

    override fun toString(): String = format()

    companion object {

        @JvmStatic
        fun of(x: Double, y: Double, z: Double): Vector3 =
            Vector3(Value.of(x), Value.of(y), Value.of(z))

        /** Construct from literals (uses exact track if convertible to rational) */
        @JvmStatic
        fun ofLiteral(x: String, y: String, z: String): Vector3 =
            Vector3(ComplexNum.literal(x), ComplexNum.literal(y), ComplexNum.literal(z))

        @JvmStatic
        fun ofInts(x: Int, y: Int, z: Int): Vector3 =
            Vector3(ExactMath.long(x.toLong()), ExactMath.long(y.toLong()), ExactMath.long(z.toLong()))
    }
}

/** Four vector variables VctA–VctD */
class VectorStore {

    private val vecs = LinkedHashMap<String, Vector3?>()

    fun set(name: String, v: Vector3?) {
        key(name)
        vecs[name] = v
    }

    fun get(name: String): Vector3? {
        key(name)
        return vecs[name]
    }

    fun clearAll() {
        vecs.clear()
    }

    fun snapshot(): Map<String, Vector3?> = LinkedHashMap(vecs)

    private fun key(name: String) {
        if (name !in NAMES) throw NumericError("Unknown vector variable $name")
    }

    companion object {
        @JvmField
        val NAMES: List<String> = listOf("A", "B", "C", "D")
    }
}
