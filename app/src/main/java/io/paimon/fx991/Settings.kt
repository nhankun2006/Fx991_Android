package io.paimon.fx991

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.paimon.fx991.engine.NumberNotation

// ---------------------------------------------------------------------------
// Application Settings (Theme / Angle Unit / Precision / Fraction / Vibration)
// ---------------------------------------------------------------------------

/** Theme: Follow System / Light / Dark */
enum class AppTheme(val title: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark"),
}

/** Display Precision: Significant Figures / Fixed Decimal Places */
enum class PrecisionMode(val title: String) {
    SIGFIG("Significant Figures"),
    DECIMALS("Decimal Places"),
}

data class CalculatorSettings(
    val theme: AppTheme = AppTheme.SYSTEM,
    val precision: PrecisionMode = PrecisionMode.SIGFIG,
    val sigDigits: Int = 10,
    val decimals: Int = 3,
    val vibration: Boolean = true,
    // ---- Batch B: Number display notation (Norm / Sci / Eng), linked with ENG key ----
    val notation: NumberNotation = NumberNotation.NORM,
    // ---- Batch K7: Output result as degrees, minutes, seconds (°′″) ----
    val dmsOutput: Boolean = false,
)

// ---------------------------------------------------------------------------
// Batch A Numerical Functions: Dialog Kinds
// ---------------------------------------------------------------------------

enum class FuncKind(val title: String) {
    INTEGRAL("Definite Integral ∫dx"),
    DERIV("Numerical Derivative d/dx"),
    SUMMATION("Summation Σ"),
    SOLVE("Equation Solver SOLVE"),
    LIMIT("Limit"),
    CALC("Evaluate CALC"),
    DMS("Degrees Minutes Seconds °′″"),
    HYPER("Hyperbolic hyp"),

    // ---- Batch B ----
    POL("Rectangular → Polar Pol"),
    REC("Polar → Rectangular Rec"),
    RANINT("Random Integer RanInt"),
}

/** Field labels to display in the dialog */
fun fieldLabels(kind: FuncKind): List<String> = when (kind) {
    FuncKind.INTEGRAL -> listOf("f(x)", "Lower a", "Upper b", "Tolerance")
    FuncKind.DERIV -> listOf("f(x)", "x value")
    FuncKind.SUMMATION -> listOf("f(x)", "Lower a", "Upper b")
    FuncKind.SOLVE -> listOf("f(x) = 0", "Initial x0")
    FuncKind.LIMIT -> listOf("f(x)", "x →")
    FuncKind.CALC -> listOf("Expression", "x =", "y =")
    FuncKind.DMS -> listOf("Degrees", "Minutes", "Seconds")
    FuncKind.HYPER -> emptyList()
    FuncKind.POL -> listOf("x", "y")
    FuncKind.REC -> listOf("r", "θ")
    FuncKind.RANINT -> listOf("Lower", "Upper", "Count")
}

/**
 * An input session for a numerical function dialog.
 */
class FuncDialog(val kind: FuncKind) {
    var f by mutableStateOf("")
    var a by mutableStateOf("")
    var b by mutableStateOf("")
    var c by mutableStateOf("")
    var result by mutableStateOf("")
    var error by mutableStateOf("")

    val fields: List<String> get() = fieldLabels(kind)
}

/** Initial values when opening dialog (populated with context from current expression) */
fun newFuncDialog(kind: FuncKind, seed: String): FuncDialog = FuncDialog(kind).apply {
    val usable = if ('x' in seed) seed else ""
    when (kind) {
        FuncKind.INTEGRAL -> {
            f = usable
            a = "0"
            b = "1"
            c = "1e-10"
        }
        FuncKind.DERIV -> {
            f = usable
            a = "1"
        }
        FuncKind.SUMMATION -> {
            f = usable
            a = "1"
            b = "10"
        }
        FuncKind.SOLVE -> {
            f = usable
            a = "1"
        }
        FuncKind.LIMIT -> {
            f = usable
            a = "0"
        }
        FuncKind.CALC -> {
            f = seed
            a = ""
            b = ""
        }
        FuncKind.DMS -> {
            a = ""
            b = ""
            c = ""
        }
        FuncKind.HYPER -> Unit
        FuncKind.POL -> {
            a = ""
            b = ""
        }
        FuncKind.REC -> {
            a = "1"
            b = "0"
        }
        FuncKind.RANINT -> {
            a = "1"
            b = "6"
            c = "1"
        }
    }
}

// ---------------------------------------------------------------------------
// Overlays (Menu / Settings / More / About / History / Function Dialogs)
// ---------------------------------------------------------------------------

enum class Overlay {
    NONE, MODE, SETTINGS, MORE, PRO, HISTORY, FUNC, HELP,

    // ---- Batch B ----
    /** STO variable store / recall */
    STO,
    /** Scientific constant catalog */
    CONST,
    /** Unit conversion */
    CONV,
    /** SI prefix conversion */
    SI,
    // ---- Batch D ----
    /** CLR ALL confirmation dialog */
    CLRCONFIRM,

    // ---- Batch E ----
    // (Camera key opens Screen.PHOTO_SOLVE directly)
}

/** MODE menu item */
class ModeEntry(val title: String, val screen: Screen?)
