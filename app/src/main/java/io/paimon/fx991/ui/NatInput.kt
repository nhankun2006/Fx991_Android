package io.paimon.fx991.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------
// Batch K3-B: Secondary screens "Natural Display Input Field"
//   - Default read-only display of formatted expression; tap to enter editing mode — does not pop up system IME;
//     handled directly by the app's own keyboard panel (NatKeyboardPanel) with blinking caret and live formatting
//   - Multi-field focus: keyboard tracks active field, separate text and carets without mutual interference
//   - Fallback: keyboard panel can toggle back to system keyboard with one tap (e.g. for typing 1e-10)
// ---------------------------------------------------------------------------

/** A field editable by the keyboard panel (read/write channel for text + caret) */
class NatFocusTarget(
    val label: String,
    val getText: () -> String,
    val getCursor: () -> Int,
    val setText: (String) -> Unit,
    val setCursor: (Int) -> Unit,
)

/** Shared state for secondary screen natural input: current focused field + system keyboard fallback switch */
class NatInput {
    var focused by mutableStateOf<NatFocusTarget?>(null)
        private set

    /** Fallback switch: when true, focused field switches to system keyboard (OutlinedTextField) */
    var sysMode by mutableStateOf(false)

    fun focus(t: NatFocusTarget) {
        focused = t
    }

    /** Dismisses keyboard (retains sysMode preference) */
    fun dismiss() {
        focused = null
    }

    /** Clears all state when switching screens */
    fun clear() {
        focused = null
        sysMode = false
    }

    /** Inserts text at caret; cursorBack places caret into empty slot for template keys (e.g. a/b lands in numerator) */
    fun type(text: String, cursorBack: Int = 0) {
        val t = focused ?: return
        val r = CursorModel.insert(t.getText(), t.getCursor(), text)
        t.setText(r.text)
        t.setCursor((r.cursor - cursorBack).coerceIn(0, r.text.length))
    }

    fun backspace() {
        val t = focused ?: return
        val r = CursorModel.backspace(t.getText(), t.getCursor())
        t.setText(r.text)
        t.setCursor(r.cursor)
    }

    fun moveLeft() {
        val t = focused ?: return
        t.setCursor(CursorModel.moveLeft(t.getText(), t.getCursor()))
    }

    fun moveRight() {
        val t = focused ?: return
        t.setCursor(CursorModel.moveRight(t.getText(), t.getCursor()))
    }
}

val LocalNatInput = staticCompositionLocalOf<NatInput?> { null }

/**
 * Natural display input field (natural display edition of NumField).
 * Unfocused state: formatted expression (no caret); Focused state: blinking caret + live formatting, no system IME.
 */
@Composable
fun NatValueField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    input: NatInput,
    modifier: Modifier = Modifier,
) {
    val c = LocalCalcColors.current
    val cursorState = remember { mutableStateOf(value.length) }
    val valueState = rememberUpdatedState(value)
    val changeState = rememberUpdatedState(onChange)
    val target = remember {
        NatFocusTarget(
            label = label,
            getText = { valueState.value },
            getCursor = { cursorState.value.coerceIn(0, valueState.value.length) },
            setText = { changeState.value(it) },
            setCursor = { cursorState.value = it.coerceIn(0, valueState.value.length) },
        )
    }
    val focused = input.focused === target

    if (focused && input.sysMode) {
        // Fallback: system keyboard input (e.g. for typing 1e-10)
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label, fontSize = 11.sp) },
            singleLine = true,
            textStyle = TextStyle(fontFamily = Mono, fontSize = 14.sp, color = c.bodyInk),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = c.keyNeutral,
                unfocusedContainerColor = c.keyNeutral,
                focusedIndicatorColor = ShiftOrange,
                unfocusedIndicatorColor = c.keyEdge,
                focusedLabelColor = ShiftOrange,
                unfocusedLabelColor = c.bodyInk,
                cursorColor = ShiftOrange,
            ),
            modifier = modifier,
        )
        return
    }

    val safeCursor = cursorState.value.coerceIn(0, value.length)
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(c.keyNeutral)
            .border(
                width = if (focused) 1.5.dp else 1.dp,
                color = if (focused) ShiftOrange else c.keyEdge,
                shape = RoundedCornerShape(6.dp),
            )
            .clickable {
                input.focus(target)
                if (cursorState.value > value.length) cursorState.value = value.length
            }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Column {
            Text(
                label,
                fontSize = 9.5.sp,
                maxLines = 1,
                color = if (focused) ShiftOrange else c.keyNeutralInk.copy(alpha = 0.65f),
            )
            Box(Modifier.fillMaxWidth().heightIn(min = 24.dp), contentAlignment = Alignment.CenterStart) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (value.isEmpty() && !focused) {
                        Text(" ", fontFamily = Mono, fontSize = 14.sp, color = c.keyNeutralInk.copy(alpha = 0.4f))
                    } else {
                        NaturalView(
                            expr = value,
                            style = NatStyle(14.sp, c.bodyInk),
                            cursor = if (focused) safeCursor else -1,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Secondary screen custom keyboard panel (template keys fully cover main row ins-type keys)
// ---------------------------------------------------------------------------

private enum class PadAct { LEFT, RIGHT, DEL }

private class PadKey(val label: String, val insert: String? = null, val back: Int = 0, val act: PadAct? = null)

/** Main area: digits / arithmetic / brackets / common functions + caret movement and backspace */
private val PAD_ROWS: List<List<PadKey>> = listOf(
    listOf(
        PadKey("◀", act = PadAct.LEFT), PadKey("▶", act = PadAct.RIGHT),
        PadKey("a/b", "÷", back = 1), PadKey("√", "√("),
        PadKey("x²", "²", back = 1), PadKey("xʸ", "^", back = 1),
        PadKey("⌫", act = PadAct.DEL),
    ),
    listOf(
        PadKey("7", "7"), PadKey("8", "8"), PadKey("9", "9"),
        PadKey("÷", "÷"), PadKey("(", "("), PadKey(")", ")"), PadKey("x³", "³", back = 1),
    ),
    listOf(
        PadKey("4", "4"), PadKey("5", "5"), PadKey("6", "6"),
        PadKey("×", "×"), PadKey("sin", "sin("), PadKey("cos", "cos("), PadKey("tan", "tan("),
    ),
    listOf(
        PadKey("1", "1"), PadKey("2", "2"), PadKey("3", "3"),
        PadKey("−", "−"), PadKey("ln", "ln("), PadKey("log", "log("), PadKey("x", "x"),
    ),
    listOf(
        PadKey("0", "0"), PadKey(".", "."), PadKey("π", "π"), PadKey("+", "+"),
        PadKey(",", ","), PadKey("=", "="), PadKey("y", "y"),
    ),
)

/** Template key area (comprehensive set of main row ins-type keys): powers/roots, log/exp, trig/inverse/hyperbolic, comb and others */
private val PAD_FUNCS: List<PadKey> = listOf(
    PadKey("x⁻¹", "⁻¹"),                 // Caret lands in denominator slot (x⁻¹ = 1/□)
    PadKey("³√", "cbrt("),
    PadKey("ʸ√x", "root("),
    PadKey("logₓy", "logb("),
    PadKey("10ˣ", "10^"),
    PadKey("eˣ", "exp("),
    PadKey("Exp", "×10^"),
    PadKey("sin⁻¹", "sin⁻¹("),
    PadKey("cos⁻¹", "cos⁻¹("),
    PadKey("tan⁻¹", "tan⁻¹("),
    PadKey("sinh", "sinh("),
    PadKey("cosh", "cosh("),
    PadKey("tanh", "tanh("),
    PadKey("hyp⁻¹", "asinh("),
    PadKey("abs", "abs("),
    PadKey("nPr", "npr("),
    PadKey("nCr", "ncr("),
    PadKey("∠", "∠"),
    PadKey("i", "i"),
    PadKey("e", "e"),
    PadKey("z", "z"),
    PadKey("!", "!"),
    PadKey("%", "%"),
    PadKey("·", "·"),
    PadKey("Ans", "Ans"),
    PadKey("'", "'"),
)

/**
 * Secondary screen custom keyboard: floats at bottom of screen when a NatValueField is focused.
 * In sysMode, only retains control row (toggle back to natural keyboard / done).
 */
@Composable
fun NatKeyboardPanel(input: NatInput, modifier: Modifier = Modifier) {
    val c = LocalCalcColors.current
    Column(
        modifier
            .background(c.bodyEdge)
            .border(1.dp, c.keyEdge)
            .safeAreaPadding()
            .padding(horizontal = 5.dp, vertical = 4.dp),
    ) {
        if (!input.sysMode) {
            Column(
                Modifier
                    .heightIn(max = 296.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                PAD_ROWS.forEach { PadRow(it, input) }
                PAD_FUNCS.chunked(9).forEach { PadRow(it, input, small = true) }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Button(
                onClick = { input.sysMode = !input.sysMode },
                modifier = Modifier.weight(1f).height(36.dp),
                shape = RoundedCornerShape(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                border = BorderStroke(1.dp, ShiftOrange),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (input.sysMode) ShiftOrange else c.keyNeutral,
                    contentColor = if (input.sysMode) ShiftOrangeInk else ShiftOrange,
                ),
            ) {
                Text(
                    if (input.sysMode) "⌨ Switch to Natural Keyboard" else "⌨ Use System Keyboard",
                    fontSize = 12.sp, maxLines = 1,
                )
            }
            Button(
                onClick = { input.dismiss() },
                modifier = Modifier.weight(1f).height(36.dp),
                shape = RoundedCornerShape(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = c.keyEquals,
                    contentColor = c.keyEqualsInk,
                ),
            ) { Text("Done ▾", fontSize = 12.sp, maxLines = 1) }
        }
    }
}

@Composable
private fun PadRow(keys: List<PadKey>, input: NatInput, small: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        keys.forEach { PadKeyCap(it, input, small) }
        repeat((if (small) 9 else 7) - keys.size) { Box(Modifier.weight(1f)) }
    }
    Box(Modifier.height(3.dp))
}

@Composable
private fun RowScope.PadKeyCap(k: PadKey, input: NatInput, small: Boolean) {
    val c = LocalCalcColors.current
    val isNav = k.act != null
    Button(
        onClick = {
            when (k.act) {
                PadAct.LEFT -> input.moveLeft()
                PadAct.RIGHT -> input.moveRight()
                PadAct.DEL -> input.backspace()
                else -> k.insert?.let { input.type(it, k.back) }
            }
        },
        modifier = Modifier.weight(1f).height(if (small) 34.dp else 40.dp),
        shape = RoundedCornerShape(7.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        border = BorderStroke(1.dp, c.keyEdge),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isNav) c.keyOp else c.keyDigit,
            contentColor = if (isNav) c.keyOpInk else c.keyDigitInk,
        ),
    ) {
        Text(
            k.label,
            fontFamily = Mono,
            fontSize = if (small) 10.5.sp else 13.sp,
            maxLines = 1,
        )
    }
}
