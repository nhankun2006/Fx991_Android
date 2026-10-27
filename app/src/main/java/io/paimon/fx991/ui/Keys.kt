package io.paimon.fx991.ui

import io.paimon.fx991.FuncKind
import io.paimon.fx991.ModeEntry
import io.paimon.fx991.Screen
import io.paimon.fx991.engine.AngleMode
import io.paimon.fx991.engine.label

/** Key actions */
sealed interface KeyAction {
    data class Insert(val text: String) : KeyAction
    data object Ac : KeyAction
    data object Del : KeyAction
    data object Equals : KeyAction
    data object ToggleAngle : KeyAction
    data object MPlus : KeyAction
    data object MMinus : KeyAction
    data object Mrc : KeyAction
    data object HistUp : KeyAction
    data object HistDown : KeyAction
    data object SignToggle : KeyAction
    data object Fraction : KeyAction
    data object Sd : KeyAction
    /** Fraction display format cycle: Improper Fraction ⇄ Mixed Fraction ⇄ Decimal (linked with S⇔D) */
    data object FracFormat : KeyAction
    data object Shift : KeyAction
    data object Alpha : KeyAction
    data object Mode : KeyAction
    data object PadUp : KeyAction
    data object PadDown : KeyAction
    data object PadLeft : KeyAction
    data object PadRight : KeyAction
    data object PadOk : KeyAction

    // ---- Input Experience Refactor: Undo / Redo ----
    data object Undo : KeyAction
    data object Redo : KeyAction

    // ---- Batch A: Top bar 6 items + Numerical function entry points ----
    data object Menu : KeyAction
    data object Settings : KeyAction
    data object More : KeyAction
    data object Pro : KeyAction
    data object Photo : KeyAction
    /** Opens a numerical function dialog (∫dx / d/dx / Σ / SOLVE / Limit / CALC / °′″ / hyp / Pol / Rec / RanInt)
     *  From Batch K3 onward, main keys no longer invoke this (all changed to in-place bracket calls), retained as internal entry point */
    data class OpenFunc(val kind: FuncKind) : KeyAction

    // ---- Batch K3: In-place bracket calls (no secondary popup panels) ----
    /** Inserts template text in-place on main line, moving cursor back cursorBack positions -> inside brackets / empty slots */
    data class Template(val text: String, val cursorBack: Int) : KeyAction
    /** hyp: inserts sinh(); repeated presses while cursor remains at hyperbolic function entry cycle through sinh -> cosh -> tanh */
    data object HypCycle : KeyAction
    /** °′″: cycles degree -> minute -> second symbols on press (no longer inserts dms(,,) function) */
    data object Dms : KeyAction

    // ---- Batch B ----
    /** STO: Store into variable (A–F / x / y / M)
     *  From Batch K3 onward, main key uses in-place sto(expr, var); panel retained as internal entry point */
    data object OpenSto : KeyAction
    /** CONST: Scientific constants table */
    data object OpenConst : KeyAction
    /** CONV: Unit conversion */
    data object OpenConv : KeyAction
    /** SI: SI prefix conversion */
    data object OpenSi : KeyAction
    /** History: History page (SHIFT layer of −) */
    data object OpenHistory : KeyAction
    /** CLR ALL: Clear all (confirmation prompt first) */
    data object ClrAll : KeyAction
    /** ENG: Engineering notation display toggle */
    data object EngToggle : KeyAction
    /** Ran#: Inserts a random number between 0–1 */
    data object RandomInsert : KeyAction

    // ---- Batch K4: Completed according to reference screenshots ----
    /** COPY: Copy main line expression (copies result if line is empty) */
    data object CopyExpr : KeyAction
    /** PASTE: Paste copied expression */
    data object PasteExpr : KeyAction
    /** CLRv: Clear STO variables (A–F / x / y) */
    data object ClrVars : KeyAction
    /** x⇄y: Swap values of x and y */
    data object SwapXY : KeyAction

    // ---- Batch C: Six subsystem entry points (switches directly to corresponding screen) ----
    data class GoScreen(val screen: Screen) : KeyAction
}

/** Hand-drawn vector icons (no external image/icon resources) */
enum class KeyIcon { NONE, MENU, PRO, SIGMA, GEAR, PLUSMINUS, CAMERA, GALLERY, BACKSPACE }

/** Keycap classification (determines color styling) */
enum class KeyKind { UTIL, PRO, MORE, DIGIT, FUNC, OP, EQUALS, DANGER, MEM, SHIFT, ALPHA, NAV }

/** Secondary function (SHIFT / ALPHA layer) */
data class SubKey(val label: String, val action: KeyAction)

data class Key(
    val label: String,
    val kind: KeyKind,
    val action: KeyAction,
    /** Orange small text (SHIFT layer), printed above keycap */
    val shift: SubKey? = null,
    /** Purple small text (ALPHA layer), printed above keycap */
    val alpha: SubKey? = null,
    /** Long-press alternate key (triggers after 420ms hold, main key doesn't trigger) — MD3 touch keyboard key-hold layer */
    val hold: SubKey? = null,
    /** Horizontal weight, used for direction keys and wide keys */
    val weight: Float = 1f,
    val icon: KeyIcon = KeyIcon.NONE,
    /** Main label font size scale */
    val labelScale: Float = 1f,
    val subBelow: String? = null,
)

private fun ins(t: String) = KeyAction.Insert(t)

/** MODE menu: All modes implemented (Batch D completed the last 4) */
fun modeEntries(): List<ModeEntry> = listOf(
    ModeEntry("Calculate", Screen.CALC),
    ModeEntry("Differential Equations", Screen.ODE),
    ModeEntry("Equation (Polynomial / Simultaneous)", Screen.EQUATION),
    ModeEntry("Matrix", Screen.MATRIX),
    ModeEntry("Vector", Screen.VECTOR),
    ModeEntry("Statistics & Regression", Screen.STAT),
    ModeEntry("Complex", Screen.CMPLX),
    ModeEntry("Base-N", Screen.BASEN),
    ModeEntry("Table", Screen.TABLE),
    ModeEntry("Distribution", Screen.DISTR),
    ModeEntry("Function Help", Screen.FUNC_HELP),
    ModeEntry("Ratio", Screen.RATIO),
    // ---- Batch G ----
    ModeEntry("Algebra (Custom Functions)", Screen.ALGEBRA),
    ModeEntry("Fourier Series", Screen.FOURIER),
    ModeEntry("Complex Functions", Screen.CPLXF),
)

/** Top bar: Menu · PRO · Σ · Gear · ± · Camera | MORE · DEG */
fun utilityBar(mode: AngleMode): List<Key> = listOf(
    Key("", KeyKind.UTIL, KeyAction.Menu, icon = KeyIcon.MENU),
    Key("PRO", KeyKind.PRO, KeyAction.Pro, icon = KeyIcon.PRO),
    Key("", KeyKind.UTIL, KeyAction.Template("sum(,,)", 3), icon = KeyIcon.SIGMA),
    Key("", KeyKind.UTIL, KeyAction.Settings, icon = KeyIcon.GEAR),
    Key("", KeyKind.UTIL, KeyAction.SignToggle, icon = KeyIcon.PLUSMINUS),
    Key("", KeyKind.UTIL, KeyAction.Photo, icon = KeyIcon.CAMERA),
    Key("MORE", KeyKind.MORE, KeyAction.More),
    Key(mode.label, KeyKind.UTIL, KeyAction.ToggleAngle),
)

/** Keypad body: 9 rows, arranged according to reference layout (Batch K4: SHIFT/ALPHA layers aligned key-by-key) */
fun keypadRows(): List<List<Key>> = listOf(

    // Row 2: SHIFT (Orange) · ALPHA (Purple) · 5-way D-pad · MODE · 2nd
    listOf(
        Key("SHIFT", KeyKind.SHIFT, KeyAction.Shift, labelScale = 0.85f),
        Key("ALPHA", KeyKind.ALPHA, KeyAction.Alpha, labelScale = 0.85f),
        Key("", KeyKind.NAV, KeyAction.PadOk, weight = 3.2f, icon = KeyIcon.NONE),
        Key("MODE", KeyKind.FUNC, KeyAction.Mode, labelScale = 0.85f),
        // 2nd is synonymous with SHIFT: toggles second function
        Key("2nd", KeyKind.FUNC, KeyAction.Shift, labelScale = 0.85f),
    ),

    // Row 3: CALC · ∫dx · x⁻¹ · logₓy (Reference: CALC ALPHA layer is =, ∫dx ALPHA layer is ;)
    // Batch K3: CALC / SOLVE / ∫dx / d/dx / Σ all use in-place bracket calls, no popup panels
    listOf(
        Key("CALC", KeyKind.FUNC, KeyAction.Template("calc()", 1),
            shift = SubKey("SOLVE", KeyAction.Template("solve()", 1)),
            alpha = SubKey("=", ins("="))),
        Key("∫dx", KeyKind.FUNC, KeyAction.Template("int(,,)", 3),
            shift = SubKey("d/dx", KeyAction.Template("deriv(,)", 2)),
            alpha = SubKey(";", ins(";"))),
        Key("x\u207B\u00B9", KeyKind.FUNC, ins("\u207B\u00B9("), shift = SubKey("x!", ins("!"))),
        Key("log\u2093y", KeyKind.FUNC, ins("logb("),
            shift = SubKey("Σ", KeyAction.Template("sum(,,)", 3))),
    ),

    // Row 4: Fraction · Radical · Power · Log (Reference main label shows x/y; √x ALPHA layer is mod)
    listOf(
        Key("x/y", KeyKind.FUNC, KeyAction.Fraction,
            shift = SubKey("ab/c", KeyAction.FracFormat),
            hold = SubKey("Mixed Frac", KeyAction.FracFormat)),
        Key("\u221Ax", KeyKind.FUNC, ins("\u221A("), shift = SubKey("\u00B3\u221Ax", ins("cbrt(")),
            alpha = SubKey("mod", ins("mod(")),
            hold = SubKey("\u02B8\u221Ax", ins("root("))),
        Key("x\u00B2", KeyKind.FUNC, ins("\u00B2"), shift = SubKey("x\u00B3", ins("\u00B3")),
            hold = SubKey("x\u207B\u00B9", ins("\u207B\u00B9("))),
        Key("x\u02B8", KeyKind.FUNC, ins("^"), shift = SubKey("\u02E3\u221Ay", ins("root(")),
            hold = SubKey("10\u02E3", ins("10^"))),
        Key("log", KeyKind.FUNC, ins("log("), shift = SubKey("10\u02E3", ins("10^")),
            hold = SubKey("log\u2093y", ins("logb("))),
        Key("ln", KeyKind.FUNC, ins("ln("), shift = SubKey("e\u02E3", ins("exp(")),
            hold = SubKey("e\u02E3", ins("exp("))),
    ),

    // Row 5: (-) · °'" · hyp · sin · cos · tan (Reference: (-) ALPHA layer is a, °'" is b)
    listOf(
        Key("(\u2212)", KeyKind.FUNC, KeyAction.SignToggle,
            shift = SubKey("∠", ins("\u2220")),
            alpha = SubKey("a", ins("A"))),
        Key("\u00B0\u2032\u2033", KeyKind.FUNC, KeyAction.Dms,
            shift = SubKey("FACT", ins("!")),
            alpha = SubKey("b", ins("B"))),
        Key("hyp", KeyKind.FUNC, KeyAction.HypCycle,
            shift = SubKey("|x|", ins("abs(")),
            alpha = SubKey("c", ins("C"))),
        Key("sin", KeyKind.FUNC, ins("sin("), shift = SubKey("sin\u207B\u00B9", ins("sin\u207B\u00B9(")),
            alpha = SubKey("d", ins("D")), hold = SubKey("sinh", ins("sinh("))),
        Key("cos", KeyKind.FUNC, ins("cos("), shift = SubKey("cos\u207B\u00B9", ins("cos\u207B\u00B9(")),
            alpha = SubKey("e", ins("E")), hold = SubKey("cosh", ins("cosh("))),
        Key("tan", KeyKind.FUNC, ins("tan("), shift = SubKey("tan\u207B\u00B9", ins("tan\u207B\u00B9(")),
            alpha = SubKey("f", ins("F")), hold = SubKey("tanh", ins("tanh("))),
    ),

    // Row 6: RCL · ENG · ( · ) · S⇔D · M+ (Reference: STO/CLRv · i/Cot · %/Cot⁻¹ · ,/x · x⇄y/y · M−/m)
    listOf(
        Key("RCL", KeyKind.MEM, KeyAction.Mrc,
            shift = SubKey("STO", KeyAction.Template("sto(,)", 2)),
            alpha = SubKey("CLRv", KeyAction.ClrVars)),
        Key("ENG", KeyKind.MEM, KeyAction.EngToggle,
            shift = SubKey("i", ins("i")),
            alpha = SubKey("Cot", ins("cot("))),
        Key("(", KeyKind.FUNC, ins("("),
            shift = SubKey("%", ins("%")),
            alpha = SubKey("Cot\u207B\u00B9", ins("acot("))),
        Key(")", KeyKind.FUNC, ins(")"),
            shift = SubKey(",", ins(",")),
            alpha = SubKey("x", ins("x"))),
        Key("S\u21D4D", KeyKind.FUNC, KeyAction.Sd,
            shift = SubKey("x\u21C4y", KeyAction.SwapXY),
            alpha = SubKey("y", ins("y"))),
        Key("M+", KeyKind.MEM, KeyAction.MPlus,
            shift = SubKey("M\u2212", KeyAction.MMinus),
            alpha = SubKey("m", ins("M"))),
    ),

    // Row 7: 7 8 9 ⌫ AC (Reference: CONST · CONV/SI · Limit/∞ · (none) · CLR ALL)
    listOf(
        Key("7", KeyKind.DIGIT, ins("7"), shift = SubKey("CONST", KeyAction.Template("const()", 1))),
        Key("8", KeyKind.DIGIT, ins("8"),
            shift = SubKey("CONV", KeyAction.Template("conv(,,)", 3)),
            alpha = SubKey("SI", KeyAction.Template("si(,)", 2))),
        Key("9", KeyKind.DIGIT, ins("9"),
            shift = SubKey("Limit", KeyAction.Template("lim(,)", 2)),
            alpha = SubKey("\u221E", ins("\u221E"))),
        Key("", KeyKind.DANGER, KeyAction.Del, icon = KeyIcon.BACKSPACE),
        Key("AC", KeyKind.DANGER, KeyAction.Ac, labelScale = 0.9f,
            shift = SubKey("CLR ALL", KeyAction.ClrAll)),
    ),

    // Row 8: 4 5 6 × ÷ (Reference: MATRIX · VECTOR · FUNC/HELP · nPr/GCD · nCr/LCM)
    listOf(
        Key("4", KeyKind.DIGIT, ins("4"), shift = SubKey("MATRIX", KeyAction.GoScreen(Screen.MATRIX))),
        Key("5", KeyKind.DIGIT, ins("5"), shift = SubKey("VECTOR", KeyAction.GoScreen(Screen.VECTOR))),
        Key("6", KeyKind.DIGIT, ins("6"),
            shift = SubKey("FUNC", KeyAction.GoScreen(Screen.FUNC_HELP)),
            alpha = SubKey("HELP", KeyAction.GoScreen(Screen.FUNC_HELP))),
        Key("\u00D7", KeyKind.OP, ins("\u00D7"),
            shift = SubKey("nPr", ins("npr(")),
            alpha = SubKey("GCD", ins("gcd(")),
            hold = SubKey("\u00B7", ins("\u00B7"))),
        Key("\u00F7", KeyKind.OP, ins("\u00F7"),
            shift = SubKey("nCr", ins("ncr(")),
            alpha = SubKey("LCM", ins("lcm(")),
            hold = SubKey("x/y", KeyAction.Fraction)),
    ),

    // Row 9: 1 2 3 + − (Reference: STAT · CMPLX · DISTR · Pol/Ceil · Rec/Floor)
    listOf(
        Key("1", KeyKind.DIGIT, ins("1"), shift = SubKey("STAT", KeyAction.GoScreen(Screen.STAT))),
        Key("2", KeyKind.DIGIT, ins("2"), shift = SubKey("CMPLX", KeyAction.GoScreen(Screen.CMPLX))),
        Key("3", KeyKind.DIGIT, ins("3"), shift = SubKey("DISTR", KeyAction.GoScreen(Screen.DISTR))),
        Key("+", KeyKind.OP, ins("+"),
            shift = SubKey("Pol", KeyAction.Template("pol(,)", 2)),
            alpha = SubKey("Ceil", ins("ceil("))),
        Key("\u2212", KeyKind.OP, ins("\u2212"),
            shift = SubKey("Rec", KeyAction.Template("rec(,)", 2)),
            alpha = SubKey("Floor", ins("floor("))),
    ),

    // Row 10: 0 . Exp Ans = (Reference: COPY/PASTE · Ran#/RanInt · π/e · PreAns · History)
    listOf(
        Key("0", KeyKind.DIGIT, ins("0"),
            shift = SubKey("COPY", KeyAction.CopyExpr),
            alpha = SubKey("PASTE", KeyAction.PasteExpr)),
        Key(".", KeyKind.DIGIT, ins("."),
            shift = SubKey("Ran#", KeyAction.RandomInsert),
            alpha = SubKey("RanInt", KeyAction.Template("ranint(,)", 2))),
        Key("Exp", KeyKind.FUNC, ins("\u00D710^"), labelScale = 0.8f,
            shift = SubKey("\u03C0", ins("\u03C0")),
            alpha = SubKey("e", ins("e"))),
        Key("Ans", KeyKind.MEM, ins("Ans"), labelScale = 0.8f,
            alpha = SubKey("PreAns", ins("PreAns"))),
        Key("=", KeyKind.EQUALS, KeyAction.Equals,
            shift = SubKey("History", KeyAction.OpenHistory)),
    ),
)
