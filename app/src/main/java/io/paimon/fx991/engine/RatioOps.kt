package io.paimon.fx991.engine

// ---------------------------------------------------------------------------
// Batch D: Ratio (RATIO)
//
// Two forms:
//   a:b = c:x  →  x = b·c ÷ a
//   a:b = x:d  →  x = a·d ÷ b
// Input decimals are converted on the spot to rationals, providing exact fractions when possible (along with decimal approximations).
// ---------------------------------------------------------------------------

object RatioOps {

    /** Ratio forms: CX = a:b = c:x; XD = a:b = x:d */
    enum class Form(val title: String) {
        CX("a:b = c:x"),
        XD("a:b = x:d"),
    }

    /** Parse coefficient input (decimal / integer -> exact rational) */
    fun parseCoefficient(s: String, what: String): Rational {
        val t = s.trim().replace('−', '-')
        if (t.isEmpty()) throw NumericError("Please enter $what")
        return Rational.fromDecimal(t) ?: throw NumericError("$what is not a valid number")
    }

    /**
     * Solve ratio. [other] is c in CX form, or d in XD form.
     * Throws NumericError when denominator (a in CX / b in XD) is 0.
     */
    fun solve(form: Form, a: Rational, b: Rational, other: Rational): Rational = when (form) {
        Form.CX -> {
            if (a.isZero) throw NumericError("a cannot be 0 (a is the divisor in a:b = c:x)")
            b.times(other).div(a)
        }
        Form.XD -> {
            if (b.isZero) throw NumericError("b cannot be 0 (b is the divisor in a:b = x:d)")
            a.times(other).div(b)
        }
    }

    /** Result text: integer displayed as integer; fraction displayed as "fraction (≈ decimal)" */
    fun textOf(x: Rational): String {
        val exact = CalcEngine.formatRational(x, false)
        if (x.isInteger) return exact
        val dec = CalcEngine.format(x.toDouble(), 10, null)
        return "$exact (≈ $dec)"
    }
}
