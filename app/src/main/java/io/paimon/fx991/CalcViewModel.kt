package io.paimon.fx991

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.paimon.fx991.engine.AngleMode
import io.paimon.fx991.engine.ApiConfig
import io.paimon.fx991.engine.ApiPreset
import io.paimon.fx991.engine.CalcEngine
import io.paimon.fx991.engine.CalcMathError
import io.paimon.fx991.engine.CalcSyntaxError
import io.paimon.fx991.engine.CalcValue
import io.paimon.fx991.engine.ComplexRect
import io.paimon.fx991.engine.EquationSolver
import io.paimon.fx991.engine.FourierOps
import io.paimon.fx991.engine.InlineFuncs
import io.paimon.fx991.engine.MatrixStore
import io.paimon.fx991.engine.NumberNotation
import io.paimon.fx991.engine.NumericError
import io.paimon.fx991.engine.NumericOps
import io.paimon.fx991.engine.PhotoNet
import io.paimon.fx991.engine.PolarForm
import io.paimon.fx991.engine.RandomOps
import io.paimon.fx991.engine.Registers
import io.paimon.fx991.engine.Sexagesimal
import io.paimon.fx991.engine.SolveItem
import io.paimon.fx991.engine.SolveKind
import io.paimon.fx991.engine.Unified
import io.paimon.fx991.engine.UpdateCheck
import io.paimon.fx991.engine.UpdateException
import io.paimon.fx991.engine.UpdateNetException
import io.paimon.fx991.engine.ReleaseInfo
import io.paimon.fx991.engine.UserFnDef
import io.paimon.fx991.engine.UserFunctions
import io.paimon.fx991.engine.Value
import io.paimon.fx991.engine.VectorStore
import io.paimon.fx991.ui.KeyAction
import io.paimon.fx991.ui.APP_VERSION
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.floor

data class HistoryEntry(val expr: String, val result: String)

/** Display format: fraction / mixed fraction / decimal (cycled with S⇔D) */
enum class DisplayMode(val shortLabel: String) {
    FRAC("FRAC"),
    MIXED("MXD"),
    DEC("DEC");

    fun next(): DisplayMode = when (this) {
        FRAC -> MIXED
        MIXED -> DEC
        DEC -> FRAC
    }
}

class CalcViewModel(app: Application) : AndroidViewModel(app) {

    var expression by mutableStateOf("")
        private set

    // ---- Input experience: Undo / Redo ----
    // Push (expr, cursor) to stack before structural edits; cap at 200 steps.
    // undoCount/redoCount trigger Compose recomposition.

    private data class EditSnapshot(val text: String, val cursor: Int, val exit: Int)

    private val undoStack = ArrayDeque<EditSnapshot>()
    private val redoStack = ArrayDeque<EditSnapshot>()

    var undoCount by mutableStateOf(0)
        private set
    var redoCount by mutableStateOf(0)
        private set

    val canUndo: Boolean get() = undoCount > 0
    val canRedo: Boolean get() = redoCount > 0

    private fun pushUndo() {
        val last = undoStack.lastOrNull()
        if (last != null && last.text == expression && last.cursor == cursor) return
        undoStack.addLast(EditSnapshot(expression, cursor, cursorExit))
        if (undoStack.size > 200) undoStack.removeFirst()
        redoStack.clear()
        undoCount = undoStack.size
        redoCount = 0
    }

    fun undoEdit() {
        val snap = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(EditSnapshot(expression, cursor, cursorExit))
        expression = snap.text
        cursor = snap.cursor
        cursorExit = snap.exit
        justEvaluated = false
        resultText = ""
        isError = false
        histCursor = -1
        undoCount = undoStack.size
        redoCount = redoStack.size
        refreshPreview()
    }

    fun redoEdit() {
        val snap = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(EditSnapshot(expression, cursor, cursorExit))
        expression = snap.text
        cursor = snap.cursor
        cursorExit = snap.exit
        justEvaluated = false
        resultText = ""
        isError = false
        histCursor = -1
        undoCount = undoStack.size
        redoCount = redoStack.size
        refreshPreview()
    }

    /** Batch K3-A: Main-line visible cursor offset in expression string (0..length) */
    var cursor by mutableStateOf(0)
        private set

    /**
     * Cursor structural level (v2.1):
     *   0 = innermost layer (denominator end / exponent end / radical end)
     *   n = jumped out to outer level across n structural layers
     * End of content and structural boundary share the same offset; level distinguishes them.
     */
    var cursorExit by mutableStateOf(0)
        private set

    /** Batch K3-B: Focus state of natural display input in secondary dialogs */
    val natInput = io.paimon.fx991.ui.NatInput()

    fun moveCursorLeft() {
        val next = io.paimon.fx991.ui.CaretModel.left(
            expression,
            io.paimon.fx991.ui.Caret(cursor, cursorExit),
        )
        cursor = next.offset
        cursorExit = next.exit
    }

    fun moveCursorRight() {
        val next = io.paimon.fx991.ui.CaretModel.right(
            expression,
            io.paimon.fx991.ui.Caret(cursor, cursorExit),
        )
        cursor = next.offset
        cursorExit = next.exit
    }

    /** Up/Down D-pad: vertical cursor movement in 2D structures (fraction, power, radical, integral) */
    fun moveCursorUp() {
        cursorExit = 0
        cursor = io.paimon.fx991.ui.CursorModel.moveUp(expression, cursor)
    }

    fun moveCursorDown() {
        // Press Down in exponent: exit to normal baseline
        val exit = io.paimon.fx991.ui.CursorModel.exitSupIfAtEnd(expression, cursor)
        if (exit != null && io.paimon.fx991.ui.CursorModel.moveDown(expression, cursor) == cursor) {
            expression = exit.text
            cursor = exit.cursor
            refreshPreview()
            return
        }
        cursor = io.paimon.fx991.ui.CursorModel.moveDown(expression, cursor)
    }

    /** Tap LCD expression: place cursor proportionally at touch position */
    fun tapToCursor(fraction: Float) {
        cursorExit = 0
        if (expression.isEmpty()) return
        val f = fraction.coerceIn(0f, 1f)
        cursor = Math.round(f * expression.length).coerceIn(0, expression.length)
    }

    var resultText by mutableStateOf("")
        private set

    var previewText by mutableStateOf("")
        private set

    var isError by mutableStateOf(false)
        private set

    var angleMode by mutableStateOf(AngleMode.DEG)
        private set

    var memory by mutableStateOf(0.0)
        private set

    var memorySet by mutableStateOf(false)
        private set

    var ans by mutableStateOf(0.0)
        private set

    /** PreAns result (coexists with Ans, maintained in history) */
    var preAns by mutableStateOf(0.0)
        private set

    /** Variable registers (STO into A–F / x / y) */
    private val registers = Registers()

    /** Confirmation message in STO panel */
    var storeMessage by mutableStateOf("")
        private set

    /** Set of populated variables (for UI display) */
    var variables by mutableStateOf<Map<String, Double>>(emptyMap())
        private set

    /** SHIFT / ALPHA modifier states */
    var shiftActive by mutableStateOf(false)
        private set

    var alphaActive by mutableStateOf(false)
        private set

    var stoActive by mutableStateOf(false)
        private set

    var rclActive by mutableStateOf(false)
        private set

    var displayMode by mutableStateOf(DisplayMode.FRAC)
        private set

    /** Main screen: Calculation / Differential Eq (switched via MODE menu) */
    var screen by mutableStateOf(Screen.CALC)
        private set

    /** Current active overlay */
    var overlay by mutableStateOf(Overlay.NONE)
        private set

    /** Calculator settings (theme / precision / vibration) */
    var settings by mutableStateOf(CalculatorSettings())
        private set

    /** Numerical function dialog state */
    var funcDialog by mutableStateOf<FuncDialog?>(null)
        private set

    /** Cartesian coordinates of last complex result; S⇔D toggles Rect ⇄ Polar */
    private var lastComplex: ComplexRect? = null
    private var polarFormShown = false

    /** Matrix / Vector variables (unified line can reference MatA / VctA directly) */
    val matrixStore = MatrixStore()
    val vectorStore = VectorStore()

    /** Batch G: Defined GeoGebra-style user functions */
    var definedFunctions by mutableStateOf<List<UserFnDef>>(emptyList())
        private set

    fun syncUserFuncs() {
        definedFunctions = UserFunctions.list()
    }

    /** Algebra screen: delete a user function */
    fun removeUserFunction(name: String) {
        UserFunctions.remove(name)
        syncUserFuncs()
    }

    /** Algebra screen: input function definition directly */
    fun defineFromAlgebra(src: String): String {
        val def = UserFunctions.tryDefine(src.trim())
            ?: throw NumericError("Not a valid definition; if function exists, redefine with :=")
        syncUserFuncs()
        return "Defined ${def.fullText()}"
    }

    /** Algebra screen: delete an STO variable */
    fun removeVariable(name: String) {
        registers.remove(name)
        variables = registers.snapshot()
    }

    /** Solution list for equations (can be inserted into main line) */
    var solutionItems by mutableStateOf<List<SolveItem>>(emptyList())
        private set

    /** Solution notes (no solution reason / scan interval) */
    var resultNote by mutableStateOf("")
        private set

    fun openOverlay(o: Overlay) {
        overlay = o
    }

    fun closeOverlay() {
        overlay = Overlay.NONE
        funcDialog = null
    }

    fun goto(target: Screen) {
        screen = target
        overlay = Overlay.NONE
        funcDialog = null
        natInput.clear()
    }

    fun openFunc(kind: FuncKind) {
        funcDialog = newFuncDialog(kind, expression)
        overlay = Overlay.FUNC
        natInput.dismiss()
        cancelLayers()
    }

    /** Tap hyperbolic function in hyp dialog: write directly to expression */
    fun insertHyper(name: String) {
        closeOverlay()
        insert(name + "(")
    }

    // ---- Settings ----
    fun setTheme(t: AppTheme) {
        settings = settings.copy(theme = t)
    }

    fun setAngle(m: AngleMode) {
        angleMode = m
        reformatResult()
        refreshPreview()
    }

    fun setPrecision(p: PrecisionMode) {
        settings = settings.copy(precision = p)
        reformatResult()
    }

    fun setSigDigits(n: Int) {
        settings = settings.copy(sigDigits = n.coerceIn(1, 15))
        reformatResult()
    }

    fun setDecimals(n: Int) {
        settings = settings.copy(decimals = n.coerceIn(0, 12))
        reformatResult()
    }

    fun setVibration(b: Boolean) {
        settings = settings.copy(vibration = b)
    }

    /** Number display notation: Norm / Sci / Eng */
    fun setNotation(n: NumberNotation) {
        settings = settings.copy(notation = n)
        reformatResult()
    }

    /** ENG key: toggle engineering notation */
    fun toggleEng() {
        settings = settings.copy(
            notation = if (settings.notation == NumberNotation.ENG) NumberNotation.NORM
            else NumberNotation.ENG,
        )
        reformatResult()
    }

    /** Output result in degrees, minutes, seconds (°′″) */
    fun setDmsOutput(b: Boolean) {
        settings = settings.copy(dmsOutput = b)
        reformatResult()
        refreshPreview()
    }

    /** Ran#: insert uniform random number between 0 and 1 */
    fun insertRandom() {
        insert(CalcEngine.format(RandomOps.uniform(), 10, null))
    }

    /** Fraction display: improper / mixed / decimal (cycled via S⇔D) */
    fun chooseDisplayMode(m: DisplayMode) {
        displayMode = m
        reformatResult()
    }

    val history = mutableStateListOf<HistoryEntry>()

    /** Set after evaluation (main line displays result, cursor hidden) */
    var justEvaluated by mutableStateOf(false)
        private set
    private var mrcArmed = false
    private var histCursor = -1
    private var lastValue: Double? = null

    /** Last exact result (for S⇔D toggling between fraction and decimal) */
    private var lastExact: io.paimon.fx991.engine.Value? = null

    private fun memValue(): Double = if (memorySet) memory else 0.0

    fun cancelLayers() {
        shiftActive = false
        alphaActive = false
    }

    private fun currentValue(): Double {
        if (expression.isNotBlank()) {
            try {
                return CalcEngine.evaluate(
                    CalcEngine.autoClose(expression), angleMode, ans, memValue(), preAns, varsMap(),
                )
            } catch (_: Exception) {
                // Fall back below
            }
        }
        lastValue?.let { return it }
        return ans
    }

    /** Map of STO variables (A–F / x / y) */
    private fun varsMap(): Map<String, Double> = registers.snapshot()

    fun onAction(action: KeyAction) {
        val layer = shiftActive || alphaActive
        when (action) {
            KeyAction.Shift -> {
                shiftActive = !shiftActive
                alphaActive = false
                return
            }
            KeyAction.Alpha -> {
                alphaActive = !alphaActive
                shiftActive = false
                return
            }
            else -> Unit
        }
        if (layer) cancelLayers()
        if (action !is KeyAction.Mrc) mrcArmed = false
        when (action) {
            KeyAction.Ac -> clearAll()
            KeyAction.Del -> backspace()
            KeyAction.Equals -> evaluateNow()
            KeyAction.ToggleAngle -> toggleAngle()
            KeyAction.MPlus -> memoryOp(1.0)
            KeyAction.MMinus -> memoryOp(-1.0)
            KeyAction.Mrc -> mrc()
            KeyAction.HistUp -> historyUp()
            KeyAction.HistDown -> historyDown()
            // 4-way D-Pad moves cursor (Up/Down navigates 2D structures vertically; history via SHIFT+=)
            KeyAction.PadUp -> moveCursorUp()
            KeyAction.PadDown -> moveCursorDown()
            KeyAction.PadLeft -> moveCursorLeft()
            KeyAction.PadRight -> moveCursorRight()
            KeyAction.PadOk -> evaluateNow()
            KeyAction.Undo -> undoEdit()
            KeyAction.Redo -> redoEdit()
            KeyAction.Sd -> cycleDisplay()
            KeyAction.FracFormat -> cycleDisplay()
            KeyAction.Fraction -> insertFractionKey()
            KeyAction.Dms -> insertDmsKey()
            KeyAction.SignToggle -> insert("\u2212")
            KeyAction.Mode, KeyAction.Menu -> openOverlay(Overlay.MODE)
            KeyAction.Settings -> openOverlay(Overlay.SETTINGS)
            KeyAction.More -> openOverlay(Overlay.MORE)
            KeyAction.Pro -> openOverlay(Overlay.PRO)
            // Camera key opens Photo Solve screen directly
            KeyAction.Photo -> goto(Screen.PHOTO_SOLVE)
            is KeyAction.OpenFunc -> openFunc(action.kind)
            // In-place bracket invocation (no pop-up modal)
            is KeyAction.Template -> insertTemplate(action.text, action.cursorBack)
            KeyAction.HypCycle -> insertHyperCycle()
            KeyAction.OpenSto -> {
                storeMessage = ""
                openOverlay(Overlay.STO)
            }
            KeyAction.OpenConst -> openOverlay(Overlay.CONST)
            KeyAction.OpenConv -> openOverlay(Overlay.CONV)
            KeyAction.OpenSi -> openOverlay(Overlay.SI)
            KeyAction.OpenHistory -> openOverlay(Overlay.HISTORY)
            KeyAction.ClrAll -> requestClearAll()
            KeyAction.EngToggle -> toggleEng()
            KeyAction.RandomInsert -> insertRandom()
            // Batch K4: COPY / PASTE / CLRv / x⇄y
            KeyAction.CopyExpr -> copyExpr()
            KeyAction.PasteExpr -> pasteExpr()
            KeyAction.ClrVars -> clearVars()
            KeyAction.SwapXY -> swapXY()
            is KeyAction.GoScreen -> goto(action.screen)
            KeyAction.Shift, KeyAction.Alpha -> Unit
            is KeyAction.Insert -> insert(action.text)
        }
    }

    private fun shouldRemainNewEntry(): Boolean = justEvaluated

    private fun insert(text: String) {
        pushUndo()
        if (shouldRemainNewEntry()) {
            val c = text.firstOrNull()
            val startsOperand = c != null && (c.isDigit() || c == '.' || c == '(' || c == '\u221A' ||
                c.isLetter() || c == '\u2212')
            expression = if (startsOperand) text else "Ans$text"
            justEvaluated = false
            resultText = ""
            isError = false
            cursor = expression.length
            cursorExit = 0
        } else {
            // In fraction slot, typing * or / stays in slot
            val r = if (cursorExit > 0)
                insertOutside(text)
            else if (text == "\u00D7" || text == "\u00F7")
                io.paimon.fx991.ui.CursorModel.insertOperator(expression, cursor, text)
            else
                io.paimon.fx991.ui.CursorModel.insert(expression, cursor, text)
            expression = r.text
            cursor = r.cursor
            cursorExit = 0
        }
        histCursor = -1
        solutionItems = emptyList()
        resultNote = ""
        refreshPreview()
    }

    /**
     * Insertion when cursor has exited structure (cursorExit > 0):
     * Appending a digit directly would merge into denominator/exponent, so insert a * based on position.
     *   Right of structure: 9÷6 + 3 -> 9÷6×3
     *   Left of structure: 2 + 9÷6 -> 2×9÷6
     */
    private fun insertOutside(text: String): io.paimon.fx991.ui.EditResult {
        val operand = text.isNotEmpty() && run {
            val c = text[0]
            c.isDigit() || c == '.' || c.isLetter() || c == '\u221A' || c == '('
        }
        if (!operand) return io.paimon.fx991.ui.CursorModel.insert(expression, cursor, text)
        return if (cursor == 0) {
            val r = io.paimon.fx991.ui.CursorModel.insert(expression, 0, text + "\u00D7")
            io.paimon.fx991.ui.EditResult(r.text, r.cursor - 1)
        } else {
            io.paimon.fx991.ui.CursorModel.insert(expression, cursor, "\u00D7" + text)
        }
    }

    /** In-place bracket invocation: inserts template text and moves cursor back into slot */
    private fun insertTemplate(text: String, back: Int) {
        insert(text)
        cursor = (cursor - back).coerceIn(0, expression.length)
    }

    /**
     * Fraction key: if numerator is empty slot, cursor stays in numerator,
     * matching secondary keypad a/b behavior; if numerator has content, enters denominator.
     * After evaluation, first press follows Ans prefix logic (Ans as numerator, cursor in denominator).
     */
    private fun insertFractionKey() {
        if (shouldRemainNewEntry()) {
            insert("\u00F7")
            return
        }
        pushUndo()
        val r = io.paimon.fx991.ui.CursorModel.insertFraction(expression, cursor)
        expression = r.text
        cursor = r.cursor
        cursorExit = 0
        histCursor = -1
        solutionItems = emptyList()
        resultNote = ""
        refreshPreview()
    }

    /** DMS key: cycles Degrees -> Minutes -> Seconds */
    private fun insertDmsKey() {
        if (shouldRemainNewEntry()) {
            expression = ""
            cursor = 0
            justEvaluated = false
            resultText = ""
            isError = false
        }
        pushUndo()
        val r = io.paimon.fx991.ui.CursorModel.insertDmsSeparator(expression, cursor)
        expression = r.text
        cursor = r.cursor
        cursorExit = 0
        histCursor = -1
        solutionItems = emptyList()
        resultNote = ""
        refreshPreview()
    }

    /** hyp key: inserts sinh(); repeated presses cycle sinh -> cosh -> tanh */
    private fun insertHyperCycle() {
        val names = listOf("sinh", "cosh", "tanh")
        names.forEachIndexed { ix, nm ->
            val s = cursor - 1 - nm.length
            if (s >= 0 && expression.regionMatches(s, nm, 0, nm.length) &&
                expression.getOrNull(cursor - 1) == '(' && expression.getOrNull(cursor) == ')'
            ) {
                pushUndo()
                val next = names[(ix + 1) % names.size]
                expression = expression.substring(0, s) + next + expression.substring(cursor - 1)
                cursor = s + next.length + 1
                refreshPreview()
                return
            }
        }
        insertTemplate("sinh()", 1)
    }

    private fun clearAll() {
        expression = ""
        cursor = 0
        cursorExit = 0
        resultText = ""
        previewText = ""
        isError = false
        justEvaluated = false
        histCursor = -1
        lastExact = null
        lastComplex = null
        polarFormShown = false
        solutionItems = emptyList()
        resultNote = ""
        cancelLayers()
    }

    // ---- CLR ALL / STO variables ----

    /** CLR ALL: show confirmation dialog */
    fun requestClearAll() {
        cancelLayers()
        overlay = Overlay.CLRCONFIRM
    }

    /** On confirm: clear history / variables / M / settings */
    fun doClearAll() {
        clearAll()
        history.clear()
        registers.clearAll()
        // Clear custom functions as well
        UserFunctions.clear()
        syncUserFuncs()
        memory = 0.0
        memorySet = false
        ans = 0.0
        preAns = 0.0
        lastValue = null
        displayMode = DisplayMode.FRAC
        settings = CalculatorSettings()
        storeMessage = ""
        variables = emptyMap()
        closeOverlay()
    }

    /** STO: store current result into variable (A–F / x / y / M) */
    fun storeVariable(name: String) {
        val v = currentValue()
        storeMessage = if (name == "M") {
            memory = v
            memorySet = true
            "Stored M = " + formatNumber(v)
        } else {
            registers.store(name, v)
        }
        variables = registers.snapshot()
    }

    /** Insert variable into expression (A–F / x / y / M) */
    fun insertVariable(name: String) {
        closeOverlay()
        insert(name)
    }

    /** Scientific constants: insert value as literal into expression */
    fun insertConstant(v: Double) {
        closeOverlay()
        insert(CalcEngine.literal(v))
    }

    /** History: delete an entry */
    fun removeHistory(index: Int) {
        if (index in history.indices) history.removeAt(index)
    }

    private fun backspace() {
        if (justEvaluated) {
            justEvaluated = false
            resultText = ""
            isError = false
            cursor = expression.length
        }
        if (expression.isEmpty()) return
        pushUndo()
        val r = io.paimon.fx991.ui.CursorModel.backspace(expression, cursor)
        expression = r.text
        cursor = r.cursor
        cursorExit = 0
        histCursor = -1
        refreshPreview()
    }

    private fun toggleAngle() {
        angleMode = if (angleMode == AngleMode.DEG) AngleMode.RAD else AngleMode.DEG
        refreshPreview()
    }

    private fun cycleDisplay() {
        val c = lastComplex
        if (c != null) {
            polarFormShown = !polarFormShown
            resultText = if (polarFormShown) polarText(c) else rectText(c)
            isError = false
            return
        }
        displayMode = displayMode.next()
        val v = lastExact ?: return
        resultText = formatValue(v)
        isError = false
    }

    /** Re-evaluate result when precision or angle unit changes */
    private fun reformatResult() {
        val c = lastComplex
        if (c != null) {
            resultText = if (polarFormShown) polarText(c) else rectText(c)
            return
        }
        val v = lastExact ?: return
        resultText = formatValue(v)
    }

    private fun evaluateNow() {
        cursorExit = 0
        val src = CalcEngine.autoClose(expression)
        if (src.isBlank()) return
        expression = src
        cursor = src.length
        solutionItems = emptyList()
        resultNote = ""
        try {
            // In-place call interception: solve(eqn) outputs solution report / sto(expr, var) writes register
            InlineFuncs.unwrapSolve(src)?.let { inner ->
                runEquation(inner)
                return
            }
            InlineFuncs.parseSto(src)?.let { (ex, name) ->
                runSto(src, ex, name)
                return
            }
            // Function definition precedence: f(x)=x^2 defines function; if already defined, solves as equation
            val defined = UserFunctions.tryDefine(src)
            if (defined != null) {
                presentDefinition(src, defined)
                return
            }
            // Fourier main line: fourier(f(x), lower, upper, terms)
            if (Regex("^fourier\\s*\\(", RegexOption.IGNORE_CASE).containsMatchIn(src.trim())) {
                runFourier(src)
                return
            }
            // Contains unknown variable with = -> solve equation; without variable -> normal evaluation
            if (EquationSolver.looksLikeEquation(src)) {
                if (EquationSolver.unknowns(src).isNotEmpty()) {
                    runEquation(src)
                    return
                }
                val lhs = src.substringBefore('=').trim()
                if (lhs.isEmpty()) {
                    syntaxError()
                    return
                }
                presentValue(src, Unified.evaluate(
                    lhs, angleMode, ans, memValue(), lastExact, preAns, varsMap(), matrixStore, vectorStore,
                ))
                if (Unified.lastNote.isNotEmpty()) resultNote = Unified.lastNote
                return
            }
            // Unified main line input (scalar / complex / matrix / vector / statistics / distribution)
            val v = Unified.evaluate(
                src, angleMode, ans, memValue(), lastExact, preAns, varsMap(), matrixStore, vectorStore,
            )
            presentValue(src, v)
            if (Unified.lastNote.isNotEmpty()) resultNote = Unified.lastNote
        } catch (e: CalcSyntaxError) {
            // Specific syntax error with detailed reason
            val msg = e.message
            if (!msg.isNullOrBlank() && msg != "Syntax ERROR") {
                resultText = msg
                previewText = ""
                isError = true
                solutionItems = emptyList()
                justEvaluated = false
            } else {
                syntaxError()
            }
        } catch (_: CalcMathError) {
            mathError()
        } catch (e: NumericError) {
            resultText = e.message ?: "Math ERROR"
            previewText = ""
            isError = true
            solutionItems = emptyList()
            justEvaluated = false
        } catch (_: Exception) {
            mathError()
        }
    }

    /** Function definition result display (added to history) */
    private fun presentDefinition(src: String, def: UserFnDef) {
        resultText = "Defined ${def.fullText()}"
        resultNote = "Available directly: ${def.name}(3) · ${def.name}'(2) · nested calls; view/edit in MODE -> Algebra"
        previewText = ""
        isError = false
        solutionItems = emptyList()
        lastComplex = null
        lastExact = null
        syncUserFuncs()
        pushHistory(src, resultText)
        justEvaluated = true
    }

    /** Fourier main line: coefficients + parity + partial sum */
    private fun runFourier(src: String) {
        try {
            val args = FourierOps.parseMain(src)
                ?: throw NumericError("fourier usage: fourier(f(x), lower, upper, terms)")
            val r = FourierOps.compute(args.f, args.a, args.b, args.n)
            resultText = FourierOps.formatReport(r)
            resultNote = "Coefficients via adaptive Simpson integration (rad); last line is partial sum S${r.n}(x)"
            previewText = ""
            isError = false
            // Partial sum expression can be tapped to bring back to main line
            solutionItems = listOf(SolveItem("Substitute S${r.n}(x)", FourierOps.partialSumExpr(r)))
            lastComplex = null
            lastExact = null
            pushHistory(src, "fourier → a0=${CalcEngine.format(r.a0, 10, null)} …")
            justEvaluated = true
        } catch (e: NumericError) {
            resultText = e.message ?: "Numeric Error"
            previewText = ""
            isError = true
            solutionItems = emptyList()
            justEvaluated = false
        } catch (e: CalcSyntaxError) {
            syntaxError()
        }
    }

    private fun syntaxError() {
        resultText = "Syntax ERROR"
        previewText = ""
        isError = true
        solutionItems = emptyList()
        justEvaluated = false
    }

    private fun mathError() {
        resultText = "Math ERROR"
        previewText = ""
        isError = true
        solutionItems = emptyList()
        justEvaluated = false
    }

    /** In-place variable assignment sto(expr, var) */
    private fun runSto(src: String, exprSrc: String, varName: String) {
        try {
            val v = Unified.evaluate(
                exprSrc, angleMode, ans, memValue(), lastExact, preAns, varsMap(), matrixStore, vectorStore,
            )
            val cx = Unified.asComplex(v) ?: throw NumericError("STO can only store numbers")
            if (abs(cx.im.toDouble()) > 1e-12) throw NumericError("Complex numbers cannot be stored into variables")
            val d = cx.re.toDouble()
            val msg = if (varName == "M") {
                memory = d
                memorySet = true
                "Stored M = " + formatNumber(d)
            } else {
                registers.store(varName, d)
            }
            variables = registers.snapshot()
            presentValue(src, v)
            resultNote = msg
        } catch (e: CalcSyntaxError) {
            syntaxError()
        } catch (e: NumericError) {
            resultText = e.message ?: "Math ERROR"
            previewText = ""
            isError = true
            solutionItems = emptyList()
            justEvaluated = false
        } catch (_: CalcMathError) {
            mathError()
        } catch (_: Exception) {
            mathError()
        }
    }

    /** Normal evaluation result: complex uses complex track, matrix/vector directly displayed */
    private fun presentValue(src: String, v: CalcValue) {
        when (v) {
            is CalcValue.Scalar -> {
                val c = v.c
                if (abs(c.im.toDouble()) > 1e-12 || src.contains(ANGLE_SIGN)) {
                    // Complex track: does not enter Ans, S⇔D toggles Rect ⇄ r∠θ
                    lastComplex = ComplexRect(c.re.toDouble(), c.im.toDouble())
                    lastExact = null
                    polarFormShown = false
                    resultText = rectText(lastComplex!!)
                } else {
                    val value = c.re
                    val d = value.toDouble()
                    val text = formatValue(value)
                    preAns = ans
                    ans = d
                    lastValue = d
                    lastExact = value
                    lastComplex = null
                    resultText = text
                }
            }
            is CalcValue.MatVal -> {
                lastComplex = null
                lastExact = null
                resultText = v.m.format()
            }
            is CalcValue.VecVal -> {
                lastComplex = null
                lastExact = null
                resultText = v.v.format()
            }
        }
        previewText = ""
        isError = false
        pushHistory(src, resultText)
        justEvaluated = true
    }

    /** Direct equation solving on main line (equations / systems) */
    private fun runEquation(src: String) {
        val r = EquationSolver.solve(src, angleMode)
        resultText = r.text
        resultNote = r.note
        solutionItems = r.items
        previewText = ""
        lastComplex = null
        lastExact = null
        isError = r.kind == SolveKind.ERROR
        if (r.kind == SolveKind.SOLUTIONS) {
            val first = r.items.firstOrNull()?.insert?.toDoubleOrNull()
            if (first != null) {
                preAns = ans
                ans = first
                lastValue = first
                lastExact = Value.Floating(first)
            }
        }
        pushHistory(src, r.text)
        justEvaluated = r.kind != SolveKind.ERROR
    }

    private fun refreshPreview() {
        if (justEvaluated) {
            previewText = ""
            return
        }
        val src = CalcEngine.autoClose(expression)
        if (src.isBlank()) {
            previewText = ""
            return
        }
        // GeoGebra definition preview (f(x)=... / f(x):=...)
        val shape = try {
            UserFunctions.definitionShape(src)
        } catch (_: Exception) {
            null
        }
        if (shape != null) {
            previewText = if (UserFunctions.contains(shape.name) && !shape.colonEq) {
                "${shape.name} is defined: press = to solve as equation, use := to redefine"
            } else {
                "Press = to define ${shape.name}(${shape.params.joinToString(",")}) = ${shape.bodyText}"
            }
            return
        }
        // Fourier main line preview
        if (Regex("^fourier\\s*\\(", RegexOption.IGNORE_CASE).containsMatchIn(src.trim())) {
            previewText = "Press = to compute Fourier coefficients"
            return
        }
        if (src.contains('=')) {
            // Real-time equation solving preview
            // Strip solve(...) wrapper before preview
            previewText = try {
                val eqSrc = InlineFuncs.unwrapSolve(src) ?: src
                if (EquationSolver.unknowns(eqSrc).isEmpty()) ""
                else {
                    val r = EquationSolver.solve(eqSrc, angleMode)
                    if (r.kind == SolveKind.ERROR) "" else r.text + " (Press = to confirm)"
                }
            } catch (_: Exception) {
                ""
            }
            return
        }
        // Heavy numerical calls skip real-time preview to avoid lag on every keystroke
        if (Regex("(int|deriv|sum|lim)\\s*\\(").containsMatchIn(src)) {
            previewText = "Press = to calculate"
            return
        }
        previewText = try {
            when (val v = Unified.evaluate(
                src, angleMode, ans, memValue(), lastExact, preAns, varsMap(), matrixStore, vectorStore,
            )) {
                is CalcValue.Scalar ->
                    // Complex results real-time preview (i / ∠ / res / cint)
                    if (abs(v.c.im.toDouble()) > 1e-12) {
                        rectText(ComplexRect(v.c.re.toDouble(), v.c.im.toDouble()))
                    } else formatValue(v.c.re)
                is CalcValue.MatVal -> ""
                is CalcValue.VecVal -> ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    // -----------------------------------------------------------------------
    // Batch K4: COPY / PASTE / CLRv / x⇄y
    // -----------------------------------------------------------------------

    /** Expression clipboard (COPY / PASTE) */
    private var exprClipboard = ""

    /** COPY: copy main line expression; if empty, copy result text */
    private fun copyExpr() {
        val src = if (expression.isNotBlank()) expression else resultText
        if (src.isBlank()) return
        exprClipboard = src
        storeMessage = "Copied: $src"
    }

    /** PASTE: insert clipboard content at cursor */
    private fun pasteExpr() {
        if (exprClipboard.isBlank()) {
            storeMessage = "Clipboard is empty (copy with SHIFT+0 first)"
            return
        }
        insert(exprClipboard)
    }

    /** CLRv: clear STO variables (A–F / x / y); preserves M and Ans */
    private fun clearVars() {
        registers.clearAll()
        variables = registers.snapshot()
        storeMessage = "Variables cleared (A–F / x / y)"
    }

    /** x⇄y: swap values of x and y */
    private fun swapXY() {
        val xv = registers.get("x") ?: 0.0
        val yv = registers.get("y") ?: 0.0
        registers.store("x", yv)
        registers.store("y", xv)
        variables = registers.snapshot()
        storeMessage = "x⇄y：x=${formatNumber(yv)}，y=${formatNumber(xv)}"
        refreshPreview()
    }

    private fun memoryOp(sign: Double) {
        if (expression.isBlank() && lastValue == null) return
        memory += sign * currentValue()
        memorySet = true
        resultText = formatNumber(memory)
        isError = false
        justEvaluated = true
        previewText = ""
        lastExact = Value.Floating(memory)
        lastComplex = null
    }

    private fun mrc() {
        if (mrcArmed) {
            memory = 0.0
            memorySet = false
            mrcArmed = false
            resultText = "0"
            isError = false
            return
        }
        insert("M")
        mrcArmed = true
    }

    private fun historyUp() {
        if (history.isEmpty()) return
        histCursor = if (histCursor < 0) 0 else minOf(histCursor + 1, history.size - 1)
        loadFromHistory()
    }

    private fun historyDown() {
        if (history.isEmpty()) return
        if (histCursor <= 0) {
            histCursor = -1
            expression = ""
        } else {
            histCursor -= 1
        }
        loadFromHistory()
    }

    private fun loadFromHistory() {
        justEvaluated = false
        resultText = ""
        isError = false
        if (histCursor in history.indices) {
            expression = history[histCursor].expr
        }
        cursor = expression.length
        refreshPreview()
    }

    // -----------------------------------------------------------------------
    // Batch A: Display formatting / polar text / numerical function dialogs
    // -----------------------------------------------------------------------

    private fun pushHistory(expr: String, text: String) {
        history.add(0, HistoryEntry(expr, text))
        while (history.size > 40) history.removeAt(history.size - 1)
        histCursor = -1
    }

    private fun fixDecimals(): Int? =
        if (settings.precision == PrecisionMode.DECIMALS) settings.decimals else null

    /** Display dual-track result according to current settings */
    private fun formatValue(v: Value): String = CalcEngine.formatValue(
        v,
        displayMode == DisplayMode.DEC,
        displayMode == DisplayMode.MIXED,
        settings.sigDigits,
        fixDecimals(),
        settings.notation,
    )

    /** Estimated value / pure float display in dialogs */
    fun formatNumber(v: Double): String {
        // DMS output mode: all scalars displayed as Degrees°Minutes′Seconds″
        if (settings.dmsOutput && v.isFinite() && abs(v) < 1e15) {
            return Sexagesimal.fromDegrees(v).toString()
        }
        return CalcEngine.format(v, settings.sigDigits, fixDecimals(), settings.notation)
    }

    /** Suffix for current angle unit */
    private fun angleUnit(): String = when (angleMode) {
        AngleMode.DEG -> "\u00B0"
        AngleMode.GRAD -> " grad"
        AngleMode.RAD -> " rad"
    }

    /** a∠θ result: Cartesian coordinates x+yi */
    private fun rectText(c: ComplexRect): String {
        val re = formatNumber(c.re)
        return when {
            abs(c.im) < 1e-12 -> re
            c.im > 0.0 -> "$re + ${formatNumber(c.im)}i"
            else -> "$re \u2212 ${formatNumber(-c.im)}i"
        }
    }

    /** a∠θ result: Polar coordinates r∠θ */
    private fun polarText(c: ComplexRect): String {
        val p = PolarForm.toPolar(c.re, c.im, angleMode)
        return "${formatNumber(p.r)}${ANGLE_SIGN}${formatNumber(p.theta)}${angleUnit()}"
    }

    /** Function help: insert syntax into expression and return to calculation */
    fun insertFromHelp(text: String) {
        goto(Screen.CALC)
        insert(text)
    }

    /** History panel: tap an entry to restore expression */
    fun selectHistory(expr: String) {
        justEvaluated = false
        resultText = ""
        isError = false
        expression = expr
        cursor = expr.length
        lastComplex = null
        polarFormShown = false
        solutionItems = emptyList()
        resultNote = ""
        closeOverlay()
        refreshPreview()
    }

    /** Insert an equation solution into main line */
    fun insertSolution(text: String) {
        if (text.isEmpty()) return
        justEvaluated = false
        resultText = ""
        isError = false
        solutionItems = emptyList()
        resultNote = ""
        expression = text
        cursor = text.length
        refreshPreview()
    }

    /** "Calculate" button in dialogs */
    fun runFunc() {
        val d = funcDialog ?: return
        d.error = ""
        d.result = ""
        try {
            when (d.kind) {
                FuncKind.INTEGRAL -> {
                    val f = need(d.f, "f(x)")
                    val a = num(d.a, "lower limit")
                    val b = num(d.b, "upper limit")
                    val tol = d.c.trim().toDoubleOrNull() ?: 1e-10
                    d.result = "= " + formatNumber(NumericOps.integrate(f, angleMode, a, b, tol))
                }
                FuncKind.DERIV -> {
                    val f = need(d.f, "f(x)")
                    val x = num(d.a, "x value")
                    d.result = "= " + formatNumber(NumericOps.derivative(f, angleMode, x))
                }
                FuncKind.SUMMATION -> {
                    val f = need(d.f, "f(x)")
                    val a = longNum(d.a, "lower bound")
                    val b = longNum(d.b, "upper bound")
                    d.result = "= " + formatNumber(NumericOps.summation(f, angleMode, a, b))
                }
                FuncKind.SOLVE -> {
                    val f = need(d.f, "f(x)")
                    val g = num(d.a, "initial value")
                    val r = NumericOps.solveRoot(f, angleMode, g)
                    val ex = r.exact
                    d.result = if (ex != null)
                        "x = ${CalcEngine.formatRational(ex, false)}（${r.method}）"
                    else "x = ${formatNumber(r.root)}（${r.method}）"
                }
                FuncKind.LIMIT -> {
                    val f = need(d.f, "f(x)")
                    val x0 = num(d.a, "x →")
                    val r = NumericOps.limit(f, angleMode, x0)
                    d.result = if (r.equal)
                        "lim = ${formatNumber(r.value())}"
                    else "Left ${formatNumber(r.left)} | Right ${formatNumber(r.right)} (not equal)"
                }
                FuncKind.CALC -> {
                    val f = need(d.f, "expression")
                    val x = d.a.trim().toDoubleOrNull() ?: 0.0
                    val y = d.b.trim().toDoubleOrNull() ?: 0.0
                    val v = CalcEngine.evaluateWith(f, angleMode, x, y)
                    ans = v
                    d.result = "= " + formatNumber(v)
                }
                FuncKind.DMS -> {
                    val deg = d.a.trim().toDoubleOrNull() ?: throw NumericError("Please enter degrees")
                    val minv = d.b.trim().toDoubleOrNull() ?: 0.0
                    val sec = d.c.trim().toDoubleOrNull() ?: 0.0
                    d.result = if (d.b.isBlank() && d.c.isBlank()) {
                        Sexagesimal.fromDegrees(deg).toString()
                    } else {
                        formatNumber(Sexagesimal.toDegrees(deg, minv, sec)) + "\u00B0"
                    }
                }
                FuncKind.HYPER -> Unit
                FuncKind.POL -> {
                    val x = num(d.a, "x")
                    val y = num(d.b, "y")
                    val p = PolarForm.toPolar(x, y, angleMode)
                    d.result = "r = ${formatNumber(p.r)}，θ = ${formatNumber(p.theta)}${angleUnit()}"
                }
                FuncKind.REC -> {
                    val r = num(d.a, "r")
                    val th = num(d.b, "θ")
                    val c = PolarForm.toRect(r, th, angleMode)
                    d.result = "x = ${formatNumber(c.re)}，y = ${formatNumber(c.im)}"
                }
                FuncKind.RANINT -> {
                    val a = longNum(d.a, "lower bound")
                    val b = longNum(d.b, "upper bound")
                    val n = longNum(d.c, "count").toInt()
                    val list = RandomOps.ranInts(a, b, n)
                    d.result = list.joinToString(" ")
                }
            }
        } catch (e: Exception) {
            d.error = e.message ?: "Calculation failed"
        }
    }

    private fun need(s: String, what: String): String {
        val t = CalcEngine.autoClose(s.trim())
        if (t.isBlank()) throw NumericError("Please enter $what")
        return t
    }

    private fun num(s: String, what: String): Double =
        s.trim().toDoubleOrNull() ?: throw NumericError("$what is not a valid number")

    private fun longNum(s: String, what: String): Long {
        val v = s.trim().toDoubleOrNull() ?: throw NumericError("$what is not a valid number")
        if (v != floor(v)) throw NumericError("$what must be an integer")
        return v.toLong()
    }

    // -----------------------------------------------------------------------
    // Batch E: Solving API configuration (stored in SharedPreferences)
    // -----------------------------------------------------------------------

    private val apiPrefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var apiBaseUrl by mutableStateOf(
        apiPrefs.getString(KEY_API_BASE, ApiConfig.DEFAULT_BASE) ?: ApiConfig.DEFAULT_BASE
    )
        private set

    var apiKey by mutableStateOf(apiPrefs.getString(KEY_API_KEY, "") ?: "")
        private set

    var apiModel by mutableStateOf(
        apiPrefs.getString(KEY_API_MODEL, ApiConfig.DEFAULT_MODEL) ?: ApiConfig.DEFAULT_MODEL
    )
        private set

    /** Timeout in seconds (clamped to 5–180 seconds) */
    var apiTimeoutText by mutableStateOf(
        apiPrefs.getInt(KEY_API_TIMEOUT, ApiConfig.DEFAULT_TIMEOUT_SEC).toString()
    )
        private set

    /** Test connection state / result message */
    var apiTestBusy by mutableStateOf(false)
        private set
    var apiTestResult by mutableStateOf("")
        private set

    /** Currently effective API configuration */
    fun apiConfig(): ApiConfig = ApiConfig(
        baseUrl = apiBaseUrl.trim(),
        apiKey = apiKey.trim(),
        model = apiModel.trim(),
        timeoutSec = apiTimeoutText.trim().toIntOrNull() ?: ApiConfig.DEFAULT_TIMEOUT_SEC,
    ).clamped()

    /** Photo solve readiness: all three items configured */
    fun apiReady(): Boolean = apiConfig().isComplete()

    fun updateApiBase(v: String) {
        apiBaseUrl = v
        persistApi()
    }

    fun updateApiKey(v: String) {
        apiKey = v
        persistApi()
    }

    fun updateApiModel(v: String) {
        apiModel = v
        persistApi()
    }

    fun updateApiTimeout(v: String) {
        apiTimeoutText = v.filter { it.isDigit() }.take(3)
        persistApi()
    }

    /** Preset autofill: sets Base URL + model name, key untouched */
    fun applyApiPreset(p: ApiPreset) {
        apiBaseUrl = p.baseUrl
        apiModel = p.model
        persistApi()
    }

    private fun persistApi() {
        apiPrefs.edit()
            .putString(KEY_API_BASE, apiBaseUrl.trim())
            .putString(KEY_API_KEY, apiKey.trim())
            .putString(KEY_API_MODEL, apiModel.trim())
            .putInt(
                KEY_API_TIMEOUT,
                (apiTimeoutText.trim().toIntOrNull() ?: ApiConfig.DEFAULT_TIMEOUT_SEC)
                    .coerceIn(ApiConfig.MIN_TIMEOUT_SEC, ApiConfig.MAX_TIMEOUT_SEC),
            )
            .apply()
        apiTestResult = ""
    }

    /** Test connection: sends minimal request in coroutine IO */
    fun testApiConnection() {
        if (apiTestBusy) return
        apiTestBusy = true
        apiTestResult = ""
        val cfg = apiConfig()
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { PhotoNet.testConnection(cfg) }
            apiTestResult = r
            apiTestBusy = false
        }
    }

    // -----------------------------------------------------------------------
    // Batch K5: Check for updates (GitHub Releases API; 12-hour cache window)
    // -----------------------------------------------------------------------

    private val updatePrefs = app.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)

    /** Manual update check in progress / result message */
    var updateCheckBusy by mutableStateOf(false)
        private set
    var updateCheckResult by mutableStateOf("")
        private set

    /** Latest confirmed version tag (cached or from network) */
    var latestVersionText by mutableStateOf(updatePrefs.getString(KEY_UPDATE_TAG, "") ?: "")
        private set

    /** Discovered new version (null = up to date / not checked) */
    var updateAvailable by mutableStateOf<ReleaseInfo?>(null)
        private set

    /** Version tag dismissed with "Later" in current session */
    var updateDismissedTag by mutableStateOf("")
        private set

    /** Version tag marked as skipped */
    var skippedVersionTag by mutableStateOf(updatePrefs.getString(KEY_UPDATE_SKIP, "") ?: "")
        private set

    fun dismissUpdate() {
        updateDismissedTag = updateAvailable?.tag ?: ""
    }

    /** Skip current version tag */
    fun skipUpdate() {
        val tag = updateAvailable?.tag ?: return
        skippedVersionTag = tag
        updateDismissedTag = tag
        updateAvailable = null
        updatePrefs.edit().putString(KEY_UPDATE_SKIP, tag).apply()
    }

    /** "Check Now" in settings */
    fun checkUpdateNow() {
        if (updateCheckBusy) return
        fetchUpdate()
    }

    private fun fetchUpdate() {
        if (updateCheckBusy) return
        updateCheckBusy = true
        updateCheckResult = ""
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                try {
                    Result.success(UpdateCheck.fetchLatest())
                } catch (e: UpdateException) {
                    Result.failure(e)
                } catch (e: Exception) {
                    Result.failure(UpdateException("Check failed: ${e.message ?: e.javaClass.simpleName}"))
                }
            }
            updateCheckBusy = false
            r.onSuccess { info ->
                updatePrefs.edit()
                    .putLong(KEY_UPDATE_AT, System.currentTimeMillis())
                    .putString(KEY_UPDATE_TAG, info.tag)
                    .putString(KEY_UPDATE_PAGE, info.pageUrl)
                    .putString(KEY_UPDATE_APK, info.apkUrl)
                    .putString(KEY_UPDATE_NOTES, info.notes)
                    .putLong(KEY_UPDATE_SIZE, info.apkSize)
                    .apply()
                latestVersionText = info.tag
                if (UpdateCheck.isNewer(info.tag, APP_VERSION)) {
                    updateAvailable = info
                    updateCheckResult = "New version found: ${info.tag} (current: $APP_VERSION)"
                } else {
                    updateAvailable = null
                    updateCheckResult = "Already the latest version (${info.tag})"
                }
            }.onFailure { e ->
                // Reached server (rate-limited / parse error): record window to prevent spam
                if (e !is UpdateNetException) {
                    updatePrefs.edit().putLong(KEY_UPDATE_AT, System.currentTimeMillis()).apply()
                }
                updateCheckResult = e.message ?: "Check failed, please try again later"
            }
        }
    }

    /**
     * Photo solve fill: inserts model expression into main line and evaluates immediately,
     * model explanation displayed in result note.
     */
    fun applyPhotoExpr(expr: String, explain: String) {
        goto(Screen.CALC)
        justEvaluated = false
        val clean = io.paimon.fx991.engine.InputNorm.normalize(expr)
        expression = clean
        cursor = clean.length
        resultText = ""
        previewText = ""
        isError = false
        solutionItems = emptyList()
        resultNote = ""
        lastComplex = null
        polarFormShown = false
        evaluateNow()
        if (explain.isNotBlank() && !isError) {
            resultNote = if (resultNote.isBlank()) explain else "$resultNote ｜ $explain"
        }
    }

    /**
     * External text entrance (ProcessText / shortcut / share):
     * Normalizes lookalike characters (– ✕ ⁄ full-width parentheses zero-width spaces),
     * then inserts into main line and evaluates immediately.
     */
    fun applyIncomingText(raw: String) {
        val clean = io.paimon.fx991.engine.InputNorm.normalize(raw)
        if (clean.isBlank()) return
        goto(Screen.CALC)
        justEvaluated = false
        expression = clean
        cursor = clean.length
        resultText = ""
        previewText = ""
        isError = false
        solutionItems = emptyList()
        resultNote = ""
        lastComplex = null
        polarFormShown = false
        evaluateNow()
    }

    private companion object {
        const val ANGLE_SIGN = '\u2220'

        // ---- Batch E: API config persistence (SharedPreferences) ----
        const val PREFS_NAME = "fx991_api_config"
        const val KEY_API_BASE = "api_base_url"
        const val KEY_API_KEY = "api_key"
        const val KEY_API_MODEL = "api_model"
        const val KEY_API_TIMEOUT = "api_timeout_sec"

        // ---- Batch K5: Update check persistence ----
        const val UPDATE_PREFS = "fx991_update"
        const val KEY_UPDATE_AUTO = "auto_check"
        const val KEY_UPDATE_AT = "last_check_ms"
        const val KEY_UPDATE_TAG = "last_tag"
        const val KEY_UPDATE_PAGE = "last_page_url"
        const val KEY_UPDATE_APK = "last_apk_url"
    const val KEY_UPDATE_NOTES = "last_notes"
    const val KEY_UPDATE_SIZE = "last_apk_size"
    const val KEY_UPDATE_SKIP = "skip_tag"
    }
}
