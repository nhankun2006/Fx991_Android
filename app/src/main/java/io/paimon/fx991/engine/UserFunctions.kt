package io.paimon.fx991.engine

import kotlin.math.abs

// ---------------------------------------------------------------------------
// Batch G: GeoGebra-style user-defined functions (pure Kotlin, JVM regression testable)
//
// Direct input on main line:
//   f(x)=x^2      define f (undefined name + `=` -> definition)
//   f(x):=x^2     force define / redefine (even if f already exists)
//   f(3)          call -> 9
//   f(x)+1        participates in expressions
//   f'(2)         first-order numerical derivative -> 4; f''(x) supports second-order
//   f(x)=0        when f is already defined -> solved as equation (via EquationSolver numerical scan)
//
// Registry is an in-process singleton (same semantics as STO registers): not preserved across restarts, documented in README.
// When parser / evaluator encounters a registered name, it queries this table directly, so existing channels like
// equation solving, ∫dx, Σ (which all use the same Parser / ValueEvaluator) automatically gain invocation capability.
// ---------------------------------------------------------------------------

/** A defined user function */
class UserFnDef internal constructor(
    val name: String,
    val params: List<String>,
    val bodyText: String,
    internal val body: Node,
) {
    /** Signature in f(x) form */
    fun signature(): String = "$name(${params.joinToString(",")})"

    /** Full definition text: f(x) = x^2 */
    fun fullText(): String = "${signature()} = $bodyText"
}

object UserFunctions {

    /** Recursion guard: maximum nested call depth (mutual redefinitions may form cycles) */
    private const val MAX_DEPTH = 24

    /** Upper limit on parameter count */
    const val MAX_PARAMS = 4

    private val defs = LinkedHashMap<String, UserFnDef>()

    /** Definition table revision number (for UI observation) */
    var revision = 0
        private set

    @Volatile
    private var callDepth = 0

    // -----------------------------------------------------------------------
    // Definition
    // -----------------------------------------------------------------------

    /** Parsed result of a definition string (when shapeOnly=true, body is not parsed, used for preview) */
    class DefShape(val name: String, val params: List<String>, val bodyText: String, val colonEq: Boolean)

    /**
     * Recognize the definition shape "f(x)=…" / "f(x):=…"; returns null if not a definition.
     * Only checks LHS shape and name validity, does not parse body (used for preview / dispatch).
     */
    @JvmStatic
    fun definitionShape(src: String): DefShape? {
        val t = src.trim()
        // Find top-level '=' (parentheses in LHS do not count toward depth)
        var depth = 0
        var eq = -1
        var i = 0
        while (i < t.length) {
            when (t[i]) {
                '(' -> depth++
                ')' -> depth--
                '=' -> if (depth == 0) { eq = i; break }
            }
            i++
        }
        if (eq <= 0) return null
        val colonEq = t[eq - 1] == ':'
        val lhs = t.substring(0, if (colonEq) eq - 1 else eq).trim()
        val bodyText = t.substring(eq + 1).trim()
        if (bodyText.isEmpty()) return null
        val m = Regex("^([A-Za-z]+)\\(([A-Za-z,\\s]*)\\)$").matchEntire(lhs) ?: return null
        val name = m.groupValues[1]
        val params = m.groupValues[2].split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (params.isEmpty() || params.size > MAX_PARAMS) return null
        // Any invalid shape returns null (falls back to existing paths such as equation solving), do not throw here—
        // otherwise equations like sin(x)=0.5 whose LHS happens to be a built-in function call would be mistakenly affected
        if (params.distinct().size != params.size) return null
        if (!nameOk(name)) return null
        return DefShape(name, params, bodyText, colonEq)
    }

    /**
     * Try treating src as a function definition:
     *  - If it is a definition shape and (name is unregistered OR `:=` is used) -> parse body, register and return definition
     *  - If it is a definition shape but name is already registered and only `=` was written -> return null (leave for equation solving)
     *  - Not a definition shape -> return null
     * Throws CalcSyntaxError when body has syntax errors.
     */
    @JvmStatic
    fun tryDefine(src: String): UserFnDef? {
        val shape = definitionShape(src) ?: return null
        if (defs.containsKey(shape.name) && !shape.colonEq) return null
        val body = Parser(Lexer(shape.bodyText).lex(), shape.params.toSet()).parse()
        val def = UserFnDef(shape.name, shape.params, shape.bodyText, body)
        defs[shape.name] = def
        revision++
        return def
    }

    /** Whether name can be used as a custom function name (no clash with built-ins / constants / registers) */
    @JvmStatic
    fun nameOk(name: String): Boolean {
        if (name.isEmpty() || !name.all { it.isLetter() }) return false
        val low = name.lowercase()
        if (low in FUNC_NAMES || low in FUNC2_NAMES || low in MAT_FUNC_NAMES || low in VARARG_NAMES) return false
        if (low in setOf("fourier", "pi")) return false
        if (name in setOf("x", "y", "z", "i", "e", "M", "Ans", "PreAns", "\u03C0")) return false
        if (name.length == 1 && name in VAR_NAMES) return false   // A–F are STO variables
        if (name.length == 4 && name.startsWith("Mat") && name[3] in 'A'..'D') return false
        if (name.length == 4 && name.startsWith("Vct") && name[3] in 'A'..'D') return false
        return true
    }

    // -----------------------------------------------------------------------
    // Query / Management (algebra pane)
    // -----------------------------------------------------------------------

    @JvmStatic
    fun contains(name: String): Boolean = defs.containsKey(name)

    @JvmStatic
    fun get(name: String): UserFnDef? = defs[name]

    @JvmStatic
    fun list(): List<UserFnDef> = defs.values.toList()

    @JvmStatic
    fun remove(name: String): Boolean {
        val r = defs.remove(name) != null
        if (r) revision++
        return r
    }

    @JvmStatic
    fun clear() {
        if (defs.isNotEmpty()) {
            defs.clear()
            revision++
        }
    }

    // -----------------------------------------------------------------------
    // Invocation (evaluator entry; args use dual-track Value, parameters bound as Float - see README known limitations)
    // -----------------------------------------------------------------------

    /**
     * Invoke a defined function.
     * @param deriv Derivative order: 0 invoke / 1 first derivative / 2 second derivative (single-variable functions only)
     */
    @JvmStatic
    fun call(name: String, args: List<Value>, deriv: Int, mode: AngleMode): Value {
        val def = defs[name] ?: throw CalcSyntaxError("Undefined function $name")
        if (args.size != def.params.size) {
            throw CalcSyntaxError("$name requires ${def.params.size} arguments")
        }
        if (deriv > 0) {
            if (def.params.size != 1) throw CalcMathError("Derivative only supports single-variable functions")
            if (deriv > 2) throw CalcMathError("Supports up to 2nd order derivative")
            return Value.of(derivAt(def, args[0].toDouble(), deriv, mode))
        }
        if (callDepth >= MAX_DEPTH) throw CalcMathError("Function recursion too deep (possible circular definition)")
        callDepth++
        try {
            val bindings = HashMap<String, Double>()
            for (k in def.params.indices) bindings[def.params[k]] = args[k].toDouble()
            return ValueEvaluator(
                mode, Value.Floating(0.0), Value.Floating(0.0), vars = bindings,
            ).eval(def.body)
        } finally {
            callDepth--
        }
    }

    /** Float track convenience entry (used by Batch A Evaluator) */
    @JvmStatic
    fun callD(name: String, args: List<Double>, deriv: Int, mode: AngleMode): Double =
        call(name, args.map { Value.of(it) }, deriv, mode).toDouble()

    /** Numerical derivative: central difference + Richardson extrapolation (same method as NumericOps.derivative) */
    private fun derivAt(def: UserFnDef, x: Double, order: Int, mode: AngleMode): Double {
        val scale = maxOf(1.0, abs(x))
        fun f(t: Double): Double = call(def.name, listOf(Value.of(t)), 0, mode).toDouble()
        val r = if (order == 1) {
            val h0 = 1e-3 * scale
            fun d(h: Double) = (f(x + h) - f(x - h)) / (2.0 * h)
            val d1 = d(h0)
            val d2 = d(h0 / 2.0)
            d2 + (d2 - d1) / 3.0
        } else {
            val h0 = 5e-3 * scale
            fun d(h: Double) = (f(x + h) - 2.0 * f(x) + f(x - h)) / (h * h)
            val d1 = d(h0)
            val d2 = d(h0 / 2.0)
            d2 + (d2 - d1) / 3.0
        }
        if (r.isNaN() || r.isInfinite()) throw CalcMathError("Derivative calculation failed")
        return r
    }
}
