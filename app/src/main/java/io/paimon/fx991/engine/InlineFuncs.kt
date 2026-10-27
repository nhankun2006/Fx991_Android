package io.paimon.fx991.engine

// ---------------------------------------------------------------------------
// Batch K3: Main line "in-place parenthesis call" entry interception (pure Kotlin, JVM regressible)
//
//   solve(equation)  → Strip shell and pass to EquationSolver (solve(x^2-1=0) / solve(2x+3=7))
//   sto(expr, var)   → Evaluate and store into register (sto(5+3, A); M goes to memory)
//
// These two are not pure functions (one produces a solve report, one has a register write side effect),
// so they are intercepted by ViewModel on pressing =; only side-effect-free unpacking logic is placed here.
// ---------------------------------------------------------------------------

object InlineFuncs {

    /**
     * Strips the outermost `name(...)` wrapper from the string; returns null if parentheses are unbalanced or it's not a full-string wrapper.
     * Example: unwrapCall("solve(2x+3=7)", "solve") = "2x+3=7"; unwrapCall("solve(x)+1", "solve") = null
     */
    @JvmStatic
    fun unwrapCall(src: String, name: String): String? {
        val t = src.trim()
        val head = name + "("
        if (!t.startsWith(head) || !t.endsWith(")")) return null
        val inner = t.substring(head.length, t.length - 1)
        var depth = 0
        for (c in inner) {
            when (c) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth < 0) return null      // Like solve(a),(b): not a full-string wrapper
                }
            }
        }
        return if (depth == 0) inner else null
    }

    /**
     * solve(...) wrapper → inner string passed to equation solver.
     * When inner string has no equals sign, treated as f(x)=0 (appends "=0"). Returns null if blank / not a solve wrapper.
     */
    @JvmStatic
    fun unwrapSolve(src: String): String? {
        val inner = unwrapCall(src, "solve") ?: return null
        val t = inner.trim()
        if (t.isEmpty()) return null
        return if (t.contains('=')) t else "$t=0"
    }

    /**
     * sto(expr, variable) → (expression, variableName); recognizes variables A–F / x / y / M (normalized:
     * single letters a–f normalized to uppercase, m normalized to uppercase M). Returns null if structure is invalid.
     * Variable name is taken from after the last **top-level** comma (expression can contain nested commas, e.g. sto(mean(1,2),B)).
     */
    @JvmStatic
    fun parseSto(src: String): Pair<String, String>? {
        val inner = unwrapCall(src, "sto") ?: return null
        var depth = 0
        var cut = -1
        for (i in inner.indices) {
            when (inner[i]) {
                '(' -> depth++
                ')' -> depth--
                ',' -> if (depth == 0) cut = i
            }
        }
        if (cut < 0) return null
        val expr = inner.substring(0, cut).trim()
        var name = inner.substring(cut + 1).trim()
        if (expr.isEmpty()) return null
        name = when (name) {
            "a", "b", "c", "d", "e", "f" -> name.uppercase()
            "m" -> "M"
            else -> name
        }
        if (name !in Registers.VARIABLES && name != "M") return null
        return expr to name
    }
}
