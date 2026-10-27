package io.paimon.fx991.engine

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.acosh
import kotlin.math.asin
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.atanh
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/** Angle mode (DEG degrees / RAD radians / GRAD gradians, 400 grad = 360°) */
enum class AngleMode { DEG, RAD, GRAD }

/** Number display notation (normal / scientific / engineering) */
enum class NumberNotation(val shortLabel: String) {
    NORM("NORM"),
    SCI("SCI"),
    ENG("ENG");

    fun next(): NumberNotation = when (this) {
        NORM -> SCI
        SCI -> ENG
        ENG -> NORM
    }
}

/** Angle mode label */
val AngleMode.label: String
    get() = when (this) {
        AngleMode.DEG -> "DEG"
        AngleMode.RAD -> "RAD"
        AngleMode.GRAD -> "GRAD"
    }

/** Syntax error */
class CalcSyntaxError(message: String) : Exception(message)

/** Math error (domain, divide by zero, overflow, etc.) */
class CalcMathError(message: String) : Exception(message)

// ---------------------------------------------------------------------------
// Lexer
// ---------------------------------------------------------------------------

internal enum class Tok {
    NUM, IDENT, PLUS, MINUS, MUL, DIV, POW,
    FACT, PCT, SQ2, CUBE, RECIP, LP, RP, SQRT, COMMA, ANGLE, DOT, PRIME, END
}

internal class Token(val t: Tok, val s: String)

private const val CH_MINUS = '\u2212'   // −
private const val CH_MUL = '\u00D7'     // ×
private const val CH_DIV = '\u00F7'     // ÷
private const val CH_SQ2 = '\u00B2'     // ²
private const val CH_CUBE = '\u00B3'    // ³
private const val CH_SUP_MINUS = '\u207B' // ⁻
private const val CH_SUP_ONE = '\u00B9'   // ¹
private const val CH_SQRT = '\u221A'    // √

internal class Lexer(private val src: String) {
    private var i = 0

    fun lex(): List<Token> {
        val out = ArrayList<Token>()
        while (i < src.length) {
            val c = src[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() || c == '.' -> out.add(number())
                c.isLetter() -> out.add(identifier())
                c == '+' -> { out.add(Token(Tok.PLUS, "+")); i++ }
                c == '-' || c == CH_MINUS -> { out.add(Token(Tok.MINUS, "-")); i++ }
                c == '*' || c == CH_MUL -> { out.add(Token(Tok.MUL, "\u00D7")); i++ }
                c == '/' || c == CH_DIV -> { out.add(Token(Tok.DIV, "\u00F7")); i++ }
                c == '^' -> { out.add(Token(Tok.POW, "^")); i++ }
                c == '!' -> { out.add(Token(Tok.FACT, "!")); i++ }
                c == '%' -> { out.add(Token(Tok.PCT, "%")); i++ }
                c == '(' -> { out.add(Token(Tok.LP, "(")); i++ }
                c == ')' -> { out.add(Token(Tok.RP, ")")); i++ }
                c == ',' -> { out.add(Token(Tok.COMMA, ",")); i++ }
                c == '\u2220' -> { out.add(Token(Tok.ANGLE, "\u2220")); i++ }
                c == '\u00B7' -> { out.add(Token(Tok.DOT, "\u00B7")); i++ }
                // Batch K4: ∞ infinity symbol (inserted in Limit and other contexts; treated as IDENT, parser maps to positive infinity)
                c == '\u221E' -> { out.add(Token(Tok.IDENT, "\u221E")); i++ }
                // Batch G: Derivative prime f'(x) (ASCII '); Unicode ′ ″ are DMS symbols,
                // skipped directly without reporting syntax error when not following digits (e.g. accidental extra press of °′″)
                c == '\'' -> { out.add(Token(Tok.PRIME, "'")); i++ }
                c == '\u2032' || c == '\u2033' -> i++
                c == CH_SQRT -> { out.add(Token(Tok.SQRT, "\u221A")); i++ }
                c == CH_SQ2 -> { out.add(Token(Tok.SQ2, "\u00B2")); i++ }
                c == CH_CUBE -> { out.add(Token(Tok.CUBE, "\u00B3")); i++ }
                c == CH_SUP_MINUS -> {
                    if (i + 1 < src.length && src[i + 1] == CH_SUP_ONE) {
                        out.add(Token(Tok.RECIP, "\u207B\u00B9"))
                        i += 2
                    } else {
                        throw CalcSyntaxError("Syntax ERROR")
                    }
                }
                else -> throw CalcSyntaxError("Syntax ERROR")
            }
        }
        out.add(Token(Tok.END, ""))
        return out
    }

    private fun number(): Token {
        val start = i
        var dot = false
        while (i < src.length) {
            val c = src[i]
            if (c.isDigit()) {
                i++
            } else if (c == '.' && !dot) {
                dot = true
                i++
            } else {
                break
            }
        }
        // DMS literals: 12°34′56″ / 12°34′ / 12°
        if (i < src.length && src[i] == '\u00B0') {
            val degText = src.substring(start, i)
            i++
            var minText = ""
            var secText = ""
            if (i < src.length && src[i].isDigit()) {
                val ms = i
                while (i < src.length && (src[i].isDigit() || src[i] == '.')) i++
                minText = src.substring(ms, i)
                if (i < src.length && (src[i] == '\u2032' || src[i] == '\'')) i++
            }
            if (i < src.length && src[i].isDigit()) {
                val ss = i
                while (i < src.length && (src[i].isDigit() || src[i] == '.')) i++
                secText = src.substring(ss, i)
                if (i < src.length && (src[i] == '\u2033' || src[i] == '"')) i++
            }
            val d = degText.toDoubleOrNull() ?: 0.0
            val m = minText.toDoubleOrNull() ?: 0.0
            val s = secText.toDoubleOrNull() ?: 0.0
            val v = d + m / 60.0 + s / 3600.0
            return Token(Tok.NUM, java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString())
        }
        return Token(Tok.NUM, src.substring(start, i))
    }

    private fun identifier(): Token {
        val start = i
        while (i < src.length && src[i].isLetter()) i++
        // Allows function names with superscript suffix like sin⁻¹ / cos⁻¹ / tan⁻¹
        if (i + 1 < src.length && src[i] == CH_SUP_MINUS && src[i + 1] == CH_SUP_ONE) {
            i += 2
        }
        return Token(Tok.IDENT, src.substring(start, i))
    }
}

// ---------------------------------------------------------------------------
// AST (Abstract Syntax Tree)
// ---------------------------------------------------------------------------

internal sealed class Node {
    /** lit preserves raw literal text for exact track to convert directly to Rational; π/e etc. have no literal text → null */
    class Num(val v: Double, val lit: String? = null) : Node()
    data object AnsRef : Node()
    data object MemRef : Node()
    data object XRef : Node()
    data object YRef : Node()
    data object ZRef : Node()

    // ---- Batch B ----
    /** PreAns: result before Ans */
    data object PreAnsRef : Node()
    /** STO variables: A–F (x / y handled via XRef / YRef) */
    class VarRef(val name: String) : Node()
    class Neg(val a: Node) : Node()
    class Add(val a: Node, val b: Node) : Node()
    class Sub(val a: Node, val b: Node) : Node()
    class Mul(val a: Node, val b: Node) : Node()
    class Div(val a: Node, val b: Node) : Node()
    class Pow(val a: Node, val b: Node) : Node()
    class Fact(val a: Node) : Node()
    class Pct(val a: Node) : Node()
    class Recip(val a: Node) : Node()
    class Sqrt(val a: Node) : Node()
    class Fn(val name: String, val a: Node) : Node()

    /** Two-parameter functions: logb(base, x) / root(n, x) / npr(n, r) / ncr(n, r) */
    class Fn2(val name: String, val a: Node, val b: Node) : Node()
    // ---- Unified input surface (REFERENCE item 6) ----

    /** Polar coordinates r∠θ: true operator, chainable, has precedence; result is complex */
    class Polar(val r: Node, val t: Node) : Node()

    /** Dot product A·B: vector dot product (equivalent to multiplication for scalars) */
    class Dot(val a: Node, val b: Node) : Node()

    /** Matrix variables MatA–MatD */
    class MatRef(val name: String) : Node()

    /** Vector variables VctA–VctD */
    class VecRef(val name: String) : Node()

    /** Vararg functions: mean / sd / ssd / normcdf / binompdf … */
    class FnN(val name: String, val args: List<Node>) : Node()

    // ---- Batch G: GeoGebra style user-defined functions + complex analysis ----

    /** Imaginary unit i (complex extension; reports Math ERROR on scalar track, evaluated as complex on unified surface) */
    data object IRef : Node()

    /** User-defined function call: f(3) / f(x)+1 / f'(2) (deriv = derivative order 0/1/2) */
    class UFn(val name: String, val args: List<Node>, val deriv: Int) : Node()
}

internal val FUNC_NAMES = setOf(
    "sin", "cos", "tan", "asin", "acos", "atan", "log", "ln", "exp", "sqrt",
    "sinh", "cosh", "tanh", "asinh", "acosh", "atanh", "cbrt", "abs", "conj",
    // Batch K4: cotangent / arccotangent / ceil / floor
    "cot", "acot", "ceil", "floor"
)

/** STO variable names (A–F), case-insensitive only when single letter and uppercase */
internal val VAR_NAMES = setOf("A", "B", "C", "D", "E", "F")

internal val FUNC2_NAMES = setOf("logb", "root", "npr", "ncr",
    // Batch K4: gcd / lcm / mod
    "gcd", "lcm", "mod")

/** Matrix single-parameter functions: det(MatA) / inv(MatA) / trn(MatA) */
internal val MAT_FUNC_NAMES = setOf("det", "inv", "trn")

/** Unified input surface vararg functions (1~4 parameters): statistics / distribution / vector;
 *  Batch G adds res / cint (complex analysis, first parameter is z expression) and fourier (intercepted by main line, parsed here for pass-through);
 *  Batch K3 adds int / deriv / sum / lim / dms / calc / pol / rec / ranint (in-place parenthesis calls, main line form of original panels) */
internal val VARARG_NAMES = setOf(
    "mean", "sd", "ssd", "sigma",
    "cross", "dot",
    "normpdf", "normcdf", "invnorm",
    "binompdf", "binomcdf",
    "poissonpdf", "poissoncdf",
    "res", "cint", "fourier",
    // Batch K3
    "int", "deriv", "sum", "lim", "dms", "calc", "pol", "rec", "ranint",
)

// ---------------------------------------------------------------------------
// Syntax-directed translation (recursive descent)
//
//   expr    -> term (('+'|'-') term)*              // addition/subtraction, % participates in "relative percentage"
//   term    -> factor (('*'|'/') factor)*          // multiplication/division
//   factor  -> unary (unary)*                      // implicit multiplication (tighter than ÷, matches reference calculator)
//   unary   -> ('-'|'+') unary | power
//   power   -> postfix ('^' unary)?                // right-associative: 2^3^2 = 512; 2^-3 valid
//   postfix -> primary ('!'|'%'|'²'|'³'|'⁻¹')*
//   primary -> NUM | CONST | FUNC '(' expr ')' | '√' unary | '(' expr ')'
// ---------------------------------------------------------------------------

internal class Parser(private val ts: List<Token>, private val extraIdents: Set<String> = emptySet()) {
    private var i = 0

    private fun cur(): Token = ts[i]

    private fun eat(t: Tok): Boolean {
        if (ts[i].t == t) {
            i++
            return true
        }
        return false
    }

    fun parse(): Node {
        if (cur().t == Tok.END) throw CalcSyntaxError("Syntax ERROR")
        val n = expr()
        if (cur().t != Tok.END) throw CalcSyntaxError("Syntax ERROR")
        return n
    }

    private fun expr(): Node {
        var left = term()
        while (true) {
            when {
                eat(Tok.PLUS) -> {
                    val r = term()
                    left = if (r is Node.Pct) {
                        // Reference calculator semantics: A + B% = A + A*B/100
                        Node.Add(left, Node.Div(Node.Mul(left, r.a), Node.Num(100.0, "100")))
                    } else {
                        Node.Add(left, r)
                    }
                }
                eat(Tok.MINUS) -> {
                    val r = term()
                    left = if (r is Node.Pct) {
                        Node.Sub(left, Node.Div(Node.Mul(left, r.a), Node.Num(100.0, "100")))
                    } else {
                        Node.Sub(left, r)
                    }
                }
                else -> return left
            }
        }
    }

    private fun term(): Node {
        var left = polar()
        while (true) {
            when {
                eat(Tok.MUL) -> left = Node.Mul(left, polar())
                eat(Tok.DIV) -> left = Node.Div(left, polar())
                else -> return left
            }
        }
    }

    /** ∠ (polar) and · (dot product): tighter than × ÷, binds tightly to operands on both sides */
    private fun polar(): Node {
        var left = factor()
        while (true) {
            when {
                eat(Tok.ANGLE) -> left = Node.Polar(left, factor())
                eat(Tok.DOT) -> left = Node.Dot(left, factor())
                else -> return left
            }
        }
    }

    private fun factor(): Node {
        var left = unary()
        while (startsPrimary()) {
            left = Node.Mul(left, unary())
        }
        return left
    }

    private fun startsPrimary(): Boolean = when (ts[i].t) {
        Tok.NUM, Tok.IDENT, Tok.LP, Tok.SQRT -> true
        else -> false
    }

    private fun unary(): Node {
        if (eat(Tok.MINUS)) return Node.Neg(unary())
        if (eat(Tok.PLUS)) return unary()
        return power()
    }

    private fun power(): Node {
        val base = postfix()
        if (eat(Tok.POW)) return Node.Pow(base, unary())
        return base
    }

    private fun postfix(): Node {
        var n = primary()
        while (true) {
            when {
                eat(Tok.FACT) -> n = Node.Fact(n)
                eat(Tok.PCT) -> n = Node.Pct(n)
                eat(Tok.SQ2) -> n = Node.Pow(n, Node.Num(2.0, "2"))
                eat(Tok.CUBE) -> n = Node.Pow(n, Node.Num(3.0, "3"))
                eat(Tok.RECIP) -> n = Node.Recip(n)
                else -> return n
            }
        }
    }

    private fun primary(): Node {
        val t = cur()
        return when (t.t) {
            Tok.NUM -> {
                i++
                Node.Num(t.s.toDoubleOrNull() ?: throw CalcSyntaxError("Syntax ERROR"), t.s)
            }
            Tok.LP -> paren()
            Tok.SQRT -> {
                i++
                Node.Sqrt(unary())
            }
            Tok.IDENT -> {
                i++
                ident(t.s)
            }
            else -> throw CalcSyntaxError("Syntax ERROR")
        }
    }

    private fun paren(): Node {
        eat(Tok.LP)
        val e = expr()
        if (!eat(Tok.RP)) throw CalcSyntaxError("Missing right parenthesis")
        return e
    }

    private fun ident(text: String): Node {
        when (text) {
            "\u03C0", "pi", "PI" -> return Node.Num(Math.PI)
            "e" -> return Node.Num(Math.E)
            // Batch K4: ∞ = positive infinity (participates in operations e.g. 1÷∞→0; standalone evaluation reports Math ERROR, same as reference calculator)
            "\u221E" -> return Node.Num(Double.POSITIVE_INFINITY)
            "Ans" -> return Node.AnsRef
            "PreAns" -> return Node.PreAnsRef
            "M" -> return Node.MemRef
            "x" -> return Node.XRef
            "y" -> return Node.YRef
            "z" -> return Node.ZRef
            // Batch G: Imaginary unit i (evaluated as complex on unified surface)
            "i" -> return Node.IRef
        }
        // Batch G: Formal parameters in function definitions (t is valid variable when defining f(t)=t^2)
        if (text in extraIdents) return Node.VarRef(text)
        // Matrix / Vector variables: MatA–MatD / VctA–VctD
        if (text.length == 4 && text.startsWith("Mat") && text[3] in 'A'..'D') {
            return Node.MatRef(text.substring(3))
        }
        if (text.length == 4 && text.startsWith("Vct") && text[3] in 'A'..'D') {
            return Node.VecRef(text.substring(3))
        }
        // STO variables: single letters A–F
        if (text.length == 1 && text in VAR_NAMES) return Node.VarRef(text)
        val name = when (text) {
            "sin\u207B\u00B9", "asin" -> "asin"
            "cos\u207B\u00B9", "acos" -> "acos"
            "tan\u207B\u00B9", "atan" -> "atan"
            else -> text.lowercase()
        }
        // Batch K3: In-place parenthesis calls — constants / SI prefixes / unit conversions expand directly into numeric nodes at syntax level
        // (const(c) → speed of light; si(1500,k) → 1500÷10³; conv(5,km,mi) → 5×10³÷1609.344)
        if (name == "const") return constInline()
        if (name == "si") return siInline()
        if (name == "conv") return convInline()
        if (name in FUNC2_NAMES) {
            if (!eat(Tok.LP)) throw CalcSyntaxError("Syntax ERROR")
            val a = expr()
            if (!eat(Tok.COMMA)) throw CalcSyntaxError("Syntax ERROR")
            val b = expr()
            if (!eat(Tok.RP)) throw CalcSyntaxError("Missing right parenthesis")
            return Node.Fn2(name, a, b)
        }
        // Matrix functions: det(MatA) / inv(MatA) / trn(MatA)
        if (name in MAT_FUNC_NAMES) {
            if (!eat(Tok.LP)) throw CalcSyntaxError("Syntax ERROR")
            val a = expr()
            if (!eat(Tok.RP)) throw CalcSyntaxError("Missing right parenthesis")
            return Node.FnN(name, listOf(a))
        }
        // Vararg functions: mean(...) / cross(A,B) / normcdf(x,μ,σ) / binompdf(n,k,p) …
        if (name in VARARG_NAMES) {
            if (!eat(Tok.LP)) throw CalcSyntaxError("Syntax ERROR")
            val args = ArrayList<Node>()
            if (ts[i].t != Tok.RP) {
                args.add(expr())
                while (eat(Tok.COMMA)) args.add(expr())
            }
            if (!eat(Tok.RP)) throw CalcSyntaxError("Missing right parenthesis")
            return Node.FnN(name, args)
        }
        if (name !in FUNC_NAMES) {
            // Batch G: GeoGebra style user-defined functions f(3) / f(x)+1 / f'(2) / f''(x)
            if (UserFunctions.contains(text)) {
                var deriv = 0
                while (eat(Tok.PRIME)) deriv++
                if (deriv > 2) throw CalcSyntaxError("At most second-order derivative supported")
                if (!eat(Tok.LP)) throw CalcSyntaxError("Function $text requires parentheses arguments")
                val args = ArrayList<Node>()
                if (ts[i].t != Tok.RP) {
                    args.add(expr())
                    while (eat(Tok.COMMA)) args.add(expr())
                }
                if (!eat(Tok.RP)) throw CalcSyntaxError("Missing right parenthesis")
                return Node.UFn(text, args, deriv)
            }
            throw CalcSyntaxError("Syntax ERROR")
        }
        val arg = if (ts[i].t == Tok.LP) paren() else unary()
        return Node.Fn(name, arg)
    }

    // -------------------------------------------------------------------
    // Batch K3: Syntax-level expansion of in-place parenthesis calls (const / si / conv)
    //   The "arguments" of these three are symbol names (g / k / km / mi …), not expressions,
    //   so raw tokens are read at the syntax level and expanded directly into numeric nodes — zero changes to dual-track core.
    // -------------------------------------------------------------------

    /** Reads a raw symbol: letters / digits / superscript ² ³ tokens concatenated directly until comma or right parenthesis (e.g. cm², NA, μ0, R∞) */
    private fun rawSymbol(): String {
        val sb = StringBuilder()
        while (true) {
            val t = cur()
            when (t.t) {
                Tok.IDENT, Tok.NUM -> {
                    sb.append(t.s)
                    i++
                }
                Tok.SQ2 -> {
                    sb.append('\u00B2')
                    i++
                }
                Tok.CUBE -> {
                    sb.append('\u00B3')
                    i++
                }
                else -> return sb.toString()
            }
        }
    }

    /** const(symbol): in-place scientific constant reference, e.g. const(g) / const(c) / const(NA) */
    private fun constInline(): Node {
        if (!eat(Tok.LP)) throw CalcSyntaxError("const requires parentheses: const(symbol), e.g. const(g)")
        val sym = rawSymbol()
        if (!eat(Tok.RP)) throw CalcSyntaxError("Missing right parenthesis")
        val k = SciConstants.find(sym)
            ?: throw CalcSyntaxError("Unknown scientific constant $sym (e.g. const(g) / const(c) / const(NA))")
        return Node.Num(k.value, CalcEngine.literal(k.value))
    }

    /** si(value, prefix): reading scaled by target prefix = value ÷ prefix factor, e.g. si(1500, k) = 1.5 (i.e. 1.5k) */
    private fun siInline(): Node {
        if (!eat(Tok.LP)) throw CalcSyntaxError("si requires parentheses: si(value, prefix), e.g. si(1500, k)")
        val v = expr()
        if (!eat(Tok.COMMA)) throw CalcSyntaxError("si requires two arguments: si(value, prefix)")
        val sym = rawSymbol()
        if (!eat(Tok.RP)) throw CalcSyntaxError("Missing right parenthesis")
        val p = SiPrefixes.find(sym)
            ?: throw CalcSyntaxError("Unknown SI prefix $sym (Y Z E P T G M k h da d c m μ n p f a z y)")
        return Node.Div(v, Node.Num(p.factor, CalcEngine.literal(p.factor)))
    }

    /** conv(value, fromUnit, toUnit): conversion between same dimensions; temperature (written as C / F / K) uses affine expansion */
    private fun convInline(): Node {
        if (!eat(Tok.LP)) throw CalcSyntaxError("conv requires parentheses: conv(value, from, to), e.g. conv(5, km, mi)")
        val v = expr()
        if (!eat(Tok.COMMA)) throw CalcSyntaxError("conv requires three arguments: conv(value, from, to)")
        val from = rawSymbol()
        if (!eat(Tok.COMMA)) throw CalcSyntaxError("conv requires three arguments: conv(value, from, to)")
        val to = rawSymbol()
        if (!eat(Tok.RP)) throw CalcSyntaxError("Missing right parenthesis")
        val f = UnitConvert.findUnit(from) ?: throw CalcSyntaxError("Unknown unit $from (e.g. km / mi / kg / lb / mL / gal / C / F / K)")
        val t = UnitConvert.findUnit(to) ?: throw CalcSyntaxError("Unknown unit $to (e.g. km / mi / kg / lb / mL / gal / C / F / K)")
        if (f.first !== t.first) throw CalcSyntaxError("Unit dimension mismatch: $from and $to are not of the same quantity")
        if (!f.first.affine) {
            // v × factor(source) ÷ factor(target)
            return Node.Div(
                Node.Mul(v, Node.Num(f.second.factor, CalcEngine.literal(f.second.factor))),
                Node.Num(t.second.factor, CalcEngine.literal(t.second.factor)),
            )
        }
        // Temperature: normalize to Celsius first, then expand from Celsius to target
        val celsius = when (f.second.symbol) {
            "°C" -> v
            "°F" -> Node.Mul(Node.Sub(v, Node.Num(32.0, "32")), Node.Num(5.0 / 9.0))
            else -> Node.Sub(v, Node.Num(273.15, "273.15"))
        }
        return when (t.second.symbol) {
            "°C" -> celsius
            "°F" -> Node.Add(Node.Mul(celsius, Node.Num(9.0 / 5.0)), Node.Num(32.0, "32"))
            else -> Node.Add(celsius, Node.Num(273.15, "273.15"))
        }
    }
}

// ---------------------------------------------------------------------------
// Mathematical functions (shared by floating track)
// ---------------------------------------------------------------------------

internal object MathOps {

    fun toRad(mode: AngleMode, x: Double): Double = when (mode) {
        AngleMode.DEG -> x * Math.PI / 180.0
        AngleMode.GRAD -> x * Math.PI / 200.0
        AngleMode.RAD -> x
    }

    fun fromRad(mode: AngleMode, x: Double): Double = when (mode) {
        AngleMode.DEG -> x * 180.0 / Math.PI
        AngleMode.GRAD -> x * 200.0 / Math.PI
        AngleMode.RAD -> x
    }

    /** Eliminates floating-point noise so sin(180°) is exactly 0, tan(45°) is exactly 1 */
    fun clean(v: Double): Double = when {
        abs(v) < 1e-12 -> 0.0
        abs(v - 1.0) < 1e-12 -> 1.0
        abs(v + 1.0) < 1e-12 -> -1.0
        abs(v - 0.5) < 1e-12 -> 0.5
        abs(v + 0.5) < 1e-12 -> -0.5
        else -> v
    }

    fun pow(a: Double, b: Double): Double {
        if (a == 0.0 && b == 0.0) return 1.0
        if (a < 0.0 && b != floor(b)) throw CalcMathError("Math ERROR")
        val r = a.pow(b)
        if (r.isNaN() || r.isInfinite()) throw CalcMathError("Math ERROR")
        return r
    }

    fun factorial(x: Double): Double {
        if (x < 0.0 || x != floor(x) || x > 170.0) throw CalcMathError("Math ERROR")
        var r = 1.0
        var k = 2
        val n = x.toInt()
        while (k <= n) {
            r *= k
            k++
        }
        return r
    }

    fun fn(mode: AngleMode, name: String, x: Double): Double {
        val r = when (name) {
            "sin" -> clean(sin(toRad(mode, x)))
            "cos" -> clean(cos(toRad(mode, x)))
            "tan" -> {
                if (mode != AngleMode.RAD) {
                    val half = if (mode == AngleMode.DEG) 180.0 else 200.0
                    val m = ((x % half) + half) % half
                    if (abs(m - half / 2.0) < 1e-9) throw CalcMathError("Math ERROR")
                }
                clean(tan(toRad(mode, x)))
            }
            "asin" -> {
                if (x < -1.0 || x > 1.0) throw CalcMathError("Math ERROR")
                fromRad(mode, asin(x))
            }
            "acos" -> {
                if (x < -1.0 || x > 1.0) throw CalcMathError("Math ERROR")
                fromRad(mode, acos(x))
            }
            "atan" -> fromRad(mode, atan(x))
            "log" -> {
                if (x <= 0.0) throw CalcMathError("Math ERROR")
                log10(x)
            }
            "ln" -> {
                if (x <= 0.0) throw CalcMathError("Math ERROR")
                ln(x)
            }
            "exp" -> exp(x)
            "sqrt" -> {
                if (x < 0.0) throw CalcMathError("Math ERROR")
                sqrt(x)
            }
            "sinh" -> sinh(x)
            "cosh" -> cosh(x)
            "tanh" -> tanh(x)
            "asinh" -> asinh(x)
            "acosh" -> {
                if (x < 1.0) throw CalcMathError("Math ERROR")
                acosh(x)
            }
            "atanh" -> {
                if (x <= -1.0 || x >= 1.0) throw CalcMathError("Math ERROR")
                atanh(x)
            }
            "cbrt" -> {
                val mag = abs(x).pow(1.0 / 3.0)
                if (x < 0.0) -mag else mag
            }
            "abs" -> abs(x)
            "conj" -> x   // Complex conjugate of real number is itself; complex numbers go through ComplexFunc
            // ---- Batch K4 ----
            "cot" -> {
                // Cotangent = cos/sin; Math ERROR at sin=0 (0°/180°…)
                if (mode != AngleMode.RAD) {
                    val half = if (mode == AngleMode.DEG) 180.0 else 200.0
                    val m = ((x % half) + half) % half
                    if (m < 1e-9 || abs(m - half) < 1e-9) throw CalcMathError("Math ERROR")
                }
                val r0 = toRad(mode, x)
                val s = sin(r0)
                if (abs(s) < 1e-15) throw CalcMathError("Math ERROR")
                clean(cos(r0) / s)
            }
            "acot" -> fromRad(mode, atan2(1.0, x))   // Range (0, 180°), x=0 → 90°
            "ceil" -> ceil(x)
            "floor" -> floor(x)
            else -> throw CalcSyntaxError("Syntax ERROR")
        }
        if (r.isNaN() || r.isInfinite()) throw CalcMathError("Math ERROR")
        return r
    }

    /** Two-parameter function evaluation */
    fun fn2(name: String, a: Double, b: Double): Double {
        val r = when (name) {
            "logb" -> {
                if (a <= 0.0 || a == 1.0) throw CalcMathError("Math ERROR")
                if (b <= 0.0) throw CalcMathError("Math ERROR")
                ln(b) / ln(a)
            }
            "root" -> {
                if (a != floor(a) || a < 1.0 || a > 1e6) throw CalcMathError("Math ERROR")
                val n = a.toInt()
                if (b < 0.0 && n % 2 == 0) throw CalcMathError("Math ERROR")
                val mag = abs(b).pow(1.0 / n)
                if (b < 0.0) -mag else mag
            }
            "npr" -> NumericOps.nPr(countOf(a), countOf(b)).toDouble()
            "ncr" -> NumericOps.nCr(countOf(a), countOf(b)).toDouble()
            // ---- Batch K4 ----
            "gcd" -> intOf(a).gcd(intOf(b)).abs().toDouble()
            "lcm" -> {
                val ga = intOf(a); val gb = intOf(b)
                if (ga.signum() == 0 || gb.signum() == 0) throw CalcMathError("Math ERROR")
                ga.divide(ga.gcd(gb)).multiply(gb).abs().toDouble()
            }
            "mod" -> {
                if (b == 0.0) throw CalcMathError("Math ERROR")
                a - b * floor(a / b)   // Floor division modulo (result has same sign as divisor)
            }
            else -> throw CalcSyntaxError("Syntax ERROR")
        }
        if (r.isNaN() || r.isInfinite()) throw CalcMathError("Math ERROR")
        return r
    }

    private fun countOf(v: Double): Long {
        if (v != floor(v) || v < 0.0 || v > 1.0e9) throw CalcMathError("Math ERROR")
        return v.toLong()
    }

    /** Batch K4: integer arguments for gcd / lcm (negatives allowed, |v| ≤ 1e15 ensures toLong does not overflow) */
    private fun intOf(v: Double): BigInteger {
        if (v != floor(v) || abs(v) > 1.0e15) throw CalcMathError("Math ERROR")
        return BigInteger.valueOf(v.toLong())
    }
}

// ---------------------------------------------------------------------------
// Floating track evaluation (v1 behavior, kept unchanged)
// ---------------------------------------------------------------------------

private class Evaluator(
    private val mode: AngleMode,
    private val ans: Double,
    private val mem: Double,
    private val xv: Double = 0.0,
    private val yv: Double = 0.0,
    private val zv: Double = 0.0,
    private val preAns: Double = 0.0,
    private val vars: Map<String, Double> = emptyMap(),
) {
    private fun v(name: String, fallback: Double): Double = vars[name] ?: fallback

    fun eval(n: Node): Double = when (n) {
        is Node.Num -> n.v
        is Node.AnsRef -> ans
        is Node.PreAnsRef -> preAns
        is Node.VarRef -> v(n.name, 0.0)
        is Node.MemRef -> mem
        is Node.XRef -> v("x", xv)
        is Node.YRef -> v("y", yv)
        is Node.ZRef -> zv
        is Node.Neg -> -eval(n.a)
        is Node.Add -> eval(n.a) + eval(n.b)
        is Node.Sub -> eval(n.a) - eval(n.b)
        is Node.Mul -> eval(n.a) * eval(n.b)
        is Node.Div -> {
            val d = eval(n.b)
            if (d == 0.0) throw CalcMathError("Math ERROR")
            eval(n.a) / d
        }
        is Node.Pow -> MathOps.pow(eval(n.a), eval(n.b))
        is Node.Fact -> MathOps.factorial(eval(n.a))
        is Node.Pct -> eval(n.a) / 100.0
        is Node.Recip -> {
            val v = eval(n.a)
            if (v == 0.0) throw CalcMathError("Math ERROR")
            1.0 / v
        }
        is Node.Sqrt -> {
            val v = eval(n.a)
            if (v < 0.0) throw CalcMathError("Math ERROR")
            sqrt(v)
        }
        is Node.Fn -> MathOps.fn(mode, n.name, eval(n.a))
        is Node.Fn2 -> MathOps.fn2(n.name, eval(n.a), eval(n.b))
        // Batch G: Floating track also supports user-defined functions (legacy channels like ∫dx / d/dx / Σ automatically available)
        is Node.UFn -> UserFunctions.callD(n.name, n.args.map { eval(it) }, n.deriv, mode)
        is Node.IRef,
        is Node.Polar, is Node.Dot, is Node.MatRef, is Node.VecRef, is Node.FnN ->
            throw CalcMathError("Math ERROR")
    }
}

// ---------------------------------------------------------------------------
// Exact track evaluation: exact whenever possible, falls back to Double only on irrational operations
// ---------------------------------------------------------------------------

internal class ValueEvaluator(
    private val mode: AngleMode,
    val ans: Value,
    private val mem: Value,
    private val xv: Double = 0.0,
    private val yv: Double = 0.0,
    private val zv: Double = 0.0,
    private val preAns: Double = 0.0,
    private val vars: Map<String, Double> = emptyMap(),
) {
    private fun v(name: String, fallback: Double): Double = vars[name] ?: fallback

    /** Scalar track evaluation (reused by unified input surface to guarantee identical semantics for numeric sub-expressions) */
    fun eval(n: Node): Value = when (n) {
        is Node.Num -> Value.of(n.v, n.lit)
        is Node.AnsRef -> ans
        is Node.PreAnsRef -> Value.Floating(preAns)
        is Node.VarRef -> Value.Floating(v(n.name, 0.0))
        is Node.MemRef -> mem
        is Node.XRef -> Value.Floating(v("x", xv))
        is Node.YRef -> Value.Floating(v("y", yv))
        is Node.ZRef -> Value.Floating(v("z", zv))
        is Node.Neg -> neg(eval(n.a))
        is Node.Add -> combine(eval(n.a), eval(n.b), { a, b -> a.plus(b) }, { a, b -> a + b })
        is Node.Sub -> combine(eval(n.a), eval(n.b), { a, b -> a.minus(b) }, { a, b -> a - b })
        is Node.Mul -> combine(eval(n.a), eval(n.b), { a, b -> a.times(b) }, { a, b -> a * b })
        is Node.Div -> combine(eval(n.a), eval(n.b), { a, b -> a.div(b) }, { a, b ->
            if (b == 0.0) throw CalcMathError("Math ERROR") else a / b
        })
        is Node.Pow -> pow(eval(n.a), eval(n.b))
        is Node.Fact -> fact(eval(n.a))
        is Node.Pct -> {
            val v = eval(n.a)
            val e = v.exact
            if (e != null) Value.of(Rational.of(e.num, e.den.multiply(BigInteger.TEN)))
            else Value.Floating(v.toDouble() / 100.0)
        }
        is Node.Recip -> {
            val v = eval(n.a)
            val e = v.exact
            if (e != null) Value.of(Rational.ONE.div(e)) else {
                val d = v.toDouble()
                if (d == 0.0) throw CalcMathError("Math ERROR")
                Value.Floating(1.0 / d)
            }
        }
        is Node.Sqrt -> sqrtValue(eval(n.a))
        is Node.Fn -> fn1(n.name, eval(n.a))
        is Node.Fn2 -> fn2(n.name, eval(n.a), eval(n.b))
        // Batch G: User-defined functions (exact track as usual: after f(x)=x^2, f(3) is still exact 9)
        is Node.UFn -> UserFunctions.call(n.name, n.args.map { eval(it) }, n.deriv, mode)
        is Node.IRef,
        is Node.Polar, is Node.Dot, is Node.MatRef, is Node.VecRef, is Node.FnN ->
            throw CalcMathError("Math ERROR")
    }

    /** Single-parameter functions: cbrt preserves exactness when exact; abs preserves exactness */
    private fun fn1(name: String, v: Value): Value {
        if (name == "cbrt") {
            val e = v.exact
            if (e != null) {
                val r = e.nthRootExact(3)
                if (r != null) return Value.of(r)
            }
        }
        if (name == "abs") {
            val e = v.exact
            if (e != null) return Value.of(e.abs())
        }
        return Value.Floating(MathOps.fn(mode, name, v.toDouble()))
    }

    /** Two-parameter functions: root / logb exact when possible; npr / ncr use BigInteger to preserve large numbers */
    private fun fn2(name: String, av: Value, bv: Value): Value {
        val ea = av.exact
        val eb = bv.exact
        when (name) {
            "root" -> {
                if (ea != null && eb != null && ea.isInteger && ea.signum > 0 &&
                    ea.num <= BigInteger.valueOf(1000)
                ) {
                    val r = eb.nthRootExact(ea.num.toInt())
                    if (r != null) return Value.of(r)
                }
            }
            "logb" -> {
                if (ea != null && eb != null && ea.isInteger && eb.isInteger &&
                    ea.signum > 0 && ea != Rational.ONE && eb.signum > 0 &&
                    ea.num.bitLength() < 64 && eb.num.bitLength() < 200
                ) {
                    val d = MathOps.fn2(name, av.toDouble(), bv.toDouble())
                    val k = Math.round(d)
                    if (k in 0..1000 && abs(d - k) < 1e-9 && ea.pow(k.toInt()) == eb) {
                        return Value.of(Rational.of(BigInteger.valueOf(k), BigInteger.ONE))
                    }
                    return Value.Floating(d)
                }
            }
            "npr", "ncr" -> {
                if (ea != null && eb != null && ea.isInteger && eb.isInteger &&
                    ea.signum >= 0 && eb.signum >= 0 &&
                    ea.num.bitLength() < 62 && eb.num.bitLength() < 62
                ) {
                    val big = if (name == "npr") NumericOps.nPr(ea.num.toLong(), eb.num.toLong())
                    else NumericOps.nCr(ea.num.toLong(), eb.num.toLong())
                    if (big.bitLength() <= 128) return Value.of(Rational.of(big, BigInteger.ONE))
                    return Value.Floating(big.toDouble())
                }
            }
            // Batch K4: gcd / lcm integer exact track (BigInteger built-in gcd)
            "gcd", "lcm" -> {
                if (ea != null && eb != null && ea.isInteger && eb.isInteger &&
                    ea.num.bitLength() < 62 && eb.num.bitLength() < 62
                ) {
                    val g = ea.num.gcd(eb.num)
                    val big = if (name == "gcd") g.abs()
                    else {
                        if (ea.num.signum() == 0 || eb.num.signum() == 0) throw CalcMathError("Math ERROR")
                        ea.num.divide(g).multiply(eb.num).abs()
                    }
                    if (big.bitLength() <= 128) return Value.of(Rational.of(big, BigInteger.ONE))
                    return Value.Floating(big.toDouble())
                }
            }
        }
        return Value.Floating(MathOps.fn2(name, av.toDouble(), bv.toDouble()))
    }

    private fun sqrtValue(v: Value): Value {
        val e = v.exact
        if (e != null) {
            if (e.signum < 0) throw CalcMathError("Math ERROR")
            val s = e.sqrtExact()
            if (s != null) return Value.of(s)
        }
        val d = v.toDouble()
        if (d < 0.0) throw CalcMathError("Math ERROR")
        return Value.Floating(sqrt(d))
    }

    private fun neg(v: Value): Value {
        val e = v.exact
        return if (e != null) Value.of(e.negate()) else Value.Floating(-v.toDouble())
    }

    private inline fun combine(
        a: Value,
        b: Value,
        exact: (Rational, Rational) -> Rational,
        float: (Double, Double) -> Double,
    ): Value {
        val ea = a.exact
        val eb = b.exact
        if (ea != null && eb != null) return Value.of(exact(ea, eb))
        return Value.Floating(float(a.toDouble(), b.toDouble()))
    }

    private fun pow(a: Value, b: Value): Value {
        val ea = a.exact
        val eb = b.exact
        if (ea != null && eb != null) {
            if (eb.isInteger && eb.num.abs() <= BigInteger.valueOf(1000)) {
                return Value.of(ea.pow(eb.num.toInt()))
            }
            // x^(1/2) and exact root
            if (eb.num == BigInteger.ONE && eb.den == BigInteger.TWO) {
                val s = ea.sqrtExact()
                if (s != null) return Value.of(s)
            }
        }
        return Value.Floating(MathOps.pow(a.toDouble(), b.toDouble()))
    }

    private fun fact(v: Value): Value {
        val e = v.exact
        if (e != null && e.isInteger && e.signum >= 0 && e.num <= BigInteger.valueOf(200)) {
            var r = BigInteger.ONE
            var k = 2
            val nn = e.num.toInt()
            while (k <= nn) {
                r = r.multiply(BigInteger.valueOf(k.toLong()))
                k++
            }
            return Value.of(Rational.of(r, BigInteger.ONE))
        }
        return Value.Floating(MathOps.factorial(v.toDouble()))
    }
}

// ---------------------------------------------------------------------------
// Public facade
// ---------------------------------------------------------------------------

object CalcEngine {

    /** Degrees → Radians (reused by external algorithms such as polar coordinates) */
    fun toRadians(mode: AngleMode, x: Double): Double = MathOps.toRad(mode, x)

    /** Radians → Degrees */
    fun fromRadians(mode: AngleMode, x: Double): Double = MathOps.fromRad(mode, x)

    /** Evaluates (floating track, v1 behavior); throws CalcSyntaxError / CalcMathError on failure */
    @JvmOverloads
    fun evaluate(
        expr: String,
        mode: AngleMode,
        ans: Double,
        mem: Double,
        preAns: Double = 0.0,
        vars: Map<String, Double> = emptyMap(),
    ): Double {
        val ast = Parser(Lexer(expr).lex()).parse()
        val v = Evaluator(mode, ans, mem, preAns = preAns, vars = vars).eval(ast)
        if (v.isNaN() || v.isInfinite()) throw CalcMathError("Math ERROR")
        return v
    }

    /** Evaluates (dual-track: exact rational preferred, irrational falls back to floating) */
    @JvmOverloads
    fun evaluateValue(
        expr: String,
        mode: AngleMode,
        ans: Double,
        mem: Double,
        lastExact: Value? = null,
        preAns: Double = 0.0,
        vars: Map<String, Double> = emptyMap(),
    ): Value {
        val ast = Parser(Lexer(expr).lex()).parse()
        val ansValue = lastExact ?: Value.Floating(ans)
        val v = ValueEvaluator(mode, ansValue, Value.Floating(mem), preAns = preAns, vars = vars).eval(ast)
        val d = v.toDouble()
        if (d.isNaN() || d.isInfinite()) throw CalcMathError("Math ERROR")
        return v
    }

    /** Evaluates with variables x / y / z (used by differential equations f(x,y), f(x,y,z)) */
    fun evaluateWith(
        expr: String,
        mode: AngleMode,
        x: Double,
        y: Double,
        z: Double = 0.0,
    ): Double {
        val ast = Parser(Lexer(expr).lex()).parse()
        val v = Evaluator(mode, 0.0, 0.0, x, y, z).eval(ast)
        if (v.isNaN() || v.isInfinite()) throw CalcMathError("Math ERROR")
        return v
    }

    /** Auto-closes unclosed left parentheses (reference calculator automatically closes right parentheses on =) */
    fun autoClose(expr: String): String {
        var open = 0
        for (c in expr) {
            when (c) {
                '(' -> open++
                ')' -> open--
            }
        }
        if (open <= 0) return expr
        return expr + ")".repeat(open)
    }

    private const val SUP_DIGITS = "\u2070\u00B9\u00B2\u00B3\u2074\u2075\u2076\u2077\u2078\u2079"

    /**
     * Defaults to 10 significant digits; scientific notation displays ×10ⁿ with superscript.
     * `sigDigits` is number of significant digits; `fixDecimals` when non-null uses fixed decimal places (NORM / FIX setting);
     * `notation` selects display mode (normal / scientific SCI / engineering ENG).
     */
    @JvmOverloads
    fun format(
        v: Double,
        sigDigits: Int = 10,
        fixDecimals: Int? = null,
        notation: NumberNotation = NumberNotation.NORM,
    ): String {
        if (v.isNaN() || v.isInfinite()) throw CalcMathError("Math ERROR")
        if (v == 0.0) return "0"
        when (notation) {
            NumberNotation.SCI -> return scientific(v, sigDigits, fixDecimals)
            NumberNotation.ENG -> return engineering(v, sigDigits, fixDecimals)
            NumberNotation.NORM -> Unit
        }
        val a = abs(v)
        if (fixDecimals != null && a >= 1e-9 && a < 1e10) return fixed(v, fixDecimals)
        if (a >= 1e10 || a < 1e-9) {
            var e = floor(log10(a)).toInt()
            var m = v / 10.0.pow(e)
            if (abs(m) >= 10.0) {
                m /= 10.0
                e++
            } else if (abs(m) < 1.0) {
                m *= 10.0
                e--
            }
            return mantissa(m, sigDigits, fixDecimals) + "\u00D710" + superscript(e)
        }
        return sig(v, sigDigits)
    }

    /** Scientific notation: mantissa ×10ⁿ (n is any integer) */
    fun scientific(v: Double, sigDigits: Int = 10, fixDecimals: Int? = null): String {
        if (v == 0.0) return "0"
        var e = floor(log10(abs(v))).toInt()
        var m = v / 10.0.pow(e)
        if (abs(m) >= 10.0) {
            m /= 10.0
            e++
        } else if (abs(m) < 1.0) {
            m *= 10.0
            e--
        }
        return mantissa(m, sigDigits, fixDecimals) + "\u00D710" + superscript(e)
    }

    /** Engineering notation: exponent must be a multiple of 3, mantissa falls in [1, 1000) */
    fun engineering(v: Double, sigDigits: Int = 10, fixDecimals: Int? = null): String {
        if (v == 0.0) return "0"
        val a = abs(v)
        var e3 = Math.floorDiv(floor(log10(a)).toInt(), 3) * 3
        var m = v / 10.0.pow(e3)
        if (abs(m) >= 1000.0) {
            m /= 1000.0
            e3 += 3
        } else if (abs(m) < 1.0) {
            m *= 1000.0
            e3 -= 3
        }
        val mt = mantissa(m, sigDigits, fixDecimals)
        return if (e3 == 0) mt else mt + "\u00D710" + superscript(e3)
    }

    private fun mantissa(m: Double, sigDigits: Int, fixDecimals: Int?): String =
        if (fixDecimals != null) fixed(m, fixDecimals) else sig(m, sigDigits)

    /**
     * Converts constant into a literal that can be written back into expressions:
     * Normal magnitudes use decimal fractions, extreme magnitudes use `mantissa×10^exponent` (natural formatting displays superscript).
     */
    @JvmOverloads
    fun literal(v: Double, sigDigits: Int = 12): String {
        if (v == 0.0) return "0"
        val a = abs(v)
        if (a >= 1e-4 && a < 1e15) return plainDecimal(v, sigDigits)
        var e = floor(log10(a)).toInt()
        var m = v / 10.0.pow(e)
        if (abs(m) >= 10.0) {
            m /= 10.0
            e++
        } else if (abs(m) < 1.0) {
            m *= 10.0
            e--
        }
        return plainDecimal(m, sigDigits) + "\u00D710^" + e
    }

    private fun plainDecimal(v: Double, sigDigits: Int): String =
        BigDecimal(v)
            .round(MathContext(sigDigits.coerceIn(1, 15), RoundingMode.HALF_UP))
            .stripTrailingZeros()
            .toPlainString()

    private fun fixed(v: Double, decimals: Int): String {
        val d = decimals.coerceIn(0, 12)
        val bd = BigDecimal(v).setScale(d, RoundingMode.HALF_UP).stripTrailingZeros()
        var s = bd.toPlainString()
        if (s.contains('.')) s = s.trimEnd('0').trimEnd('.')
        return if (s == "-0") "0" else s
    }

    /**
     * Dual-track result formatting:
     *  - Exact track: fraction / mixed fraction (forced decimal when decimal=true or non-NORM notation)
     *  - Floating track: 10 significant digits decimal
     */
    @JvmOverloads
    fun formatValue(
        v: Value,
        decimal: Boolean = false,
        mixed: Boolean = false,
        sigDigits: Int = 10,
        fixDecimals: Int? = null,
        notation: NumberNotation = NumberNotation.NORM,
    ): String {
        if (!decimal && fixDecimals == null && notation == NumberNotation.NORM) {
            val r = v.exact
            if (r != null) return formatRational(r, mixed)
        }
        return format(v.toDouble(), sigDigits, fixDecimals, notation)
    }

    /** Rational formatting: integer / improper fraction / mixed fraction */
    fun formatRational(r: Rational, mixed: Boolean): String {
        if (r.isInteger) return r.num.toString()
        if (mixed) {
            val (q, rem, den) = r.toMixed()
            return if (q.signum() == 0) "$rem/$den" else "$q $rem/$den"
        }
        return "${r.num}/${r.den}"
    }

    private fun sig(v: Double, digits: Int): String {
        val bd = BigDecimal(v).round(MathContext(digits.coerceIn(1, 15), RoundingMode.HALF_UP)).stripTrailingZeros()
        var s = bd.toPlainString()
        if (s.contains('.')) s = s.trimEnd('0').trimEnd('.')
        return s
    }

    private fun superscript(e: Int): String {
        val sb = StringBuilder()
        for (c in e.toString()) {
            if (c == '-') sb.append('\u207B') else sb.append(SUP_DIGITS[c - '0'])
        }
        return sb.toString()
    }
}
