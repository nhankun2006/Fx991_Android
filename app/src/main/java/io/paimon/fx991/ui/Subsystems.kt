package io.paimon.fx991.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.paimon.fx991.engine.AngleMode
import io.paimon.fx991.engine.CalcEngine
import io.paimon.fx991.engine.ComplexNum
import io.paimon.fx991.engine.DistrOps
import io.paimon.fx991.engine.ExactMath
import io.paimon.fx991.engine.FuncHelp
import io.paimon.fx991.engine.Matrix
import io.paimon.fx991.engine.MatrixStore
import io.paimon.fx991.engine.StatOps
import io.paimon.fx991.engine.Value
import io.paimon.fx991.engine.Vector3
import io.paimon.fx991.engine.VectorStore
import io.paimon.fx991.engine.label
import io.paimon.fx991.CalcViewModel

// ---------------------------------------------------------------------------
// Batch C: Dedicated screens for six subsystems (Complex / Matrix / Vector / Stat / Distr / Function Help)
// Each screen is a Material 3 component accessible from Mode menu or SHIFT key, returning to main screen.
// ---------------------------------------------------------------------------

/** Value formatting (10 significant digits) */
internal fun dstr(v: Double): String = CalcEngine.format(v, 10, null)

internal fun angleUnit(mode: AngleMode): String = when (mode) {
    AngleMode.DEG -> "\u00B0"
    AngleMode.GRAD -> " grad"
    AngleMode.RAD -> " rad"
}

/** Subsystem screen top bar (title + back) */
@Composable
internal fun SubHeader(title: String, subtitle: String? = null, onBack: () -> Unit) {
    val c = LocalCalcColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = onBack,
            colors = ButtonDefaults.buttonColors(
                containerColor = c.keyNeutral,
                contentColor = c.bodyInk,
            ),
            shape = RoundedCornerShape(8.dp),
        ) { Text("\u25C0 Back", fontSize = 13.sp) }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, color = c.bodyInk, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            subtitle?.let {
                Text(it, color = c.keyNeutralInk.copy(alpha = 0.7f), fontSize = 10.sp)
            }
        }
    }
}

/**
 * Single-line numeric input field - starting from Batch K3-B uses natural display field:
 * Defaults to rendered formula view (read-only); tap enters edit mode without system IME,
 * handled by the app's own keyboard panel (with blinking cursor, real-time layout, template keys);
 * A button on keyboard allows switching back to system IME for typing shorthand (e.g. 1e-10).
 * Falls back to OutlinedTextField if LocalNatInput is not available.
 */
@Composable
internal fun NumField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier,
) {
    val input = LocalNatInput.current
    if (input != null) {
        NatValueField(label, value, onChange, input, modifier)
        return
    }
    val c = LocalCalcColors.current
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 11.sp) },
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(
            fontFamily = Mono, fontSize = 14.sp, color = c.bodyInk
        ),
        colors = androidx.compose.material3.TextFieldDefaults.colors(
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
}

/** Operation button row */
@Composable
internal fun SubButton(
    label: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    val c = LocalCalcColors.current
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) c.keyEquals else c.keyNeutral,
            contentColor = if (primary) c.keyEqualsInk else c.bodyInk,
        ),
        border = if (primary) null else BorderStroke(1.dp, c.keyEdge),
        shape = RoundedCornerShape(9.dp),
        modifier = modifier.height(42.dp),
    ) { Text(label, fontSize = 12.sp, maxLines = 1) }
}

/** Result display area (monospaced, LCD background) */
@Composable
internal fun ResultBox(text: String, error: Boolean = false) {
    val c = LocalCalcColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(c.lcdBg)
            .border(1.dp, c.lcdEdge, RoundedCornerShape(6.dp))
            .padding(8.dp)
    ) {
        Text(
            text = text.ifEmpty { "(Awaiting calculation)" },
            color = if (error) c.lcdError else c.lcdFg,
            fontFamily = Mono,
            fontSize = 14.sp,
        )
    }
}

private val CM_OPS = listOf("A + B", "A \u2212 B", "A \u00D7 B", "A \u00F7 B")

/**
 * ① Complex Mode CMPLX: a+bi input / 4 basic ops / modulus · arg · conjugate / cartesian ⇄ polar.
 * Uses exact fractions when possible (decimals converted to rationals immediately).
 */
@Composable
fun CmplxScreen(onBack: () -> Unit) {
    val c = LocalCalcColors.current
    var op by remember { mutableStateOf(0) }
    var are by remember { mutableStateOf("1") }
    var aim by remember { mutableStateOf("2") }
    var bre by remember { mutableStateOf("3") }
    var bim by remember { mutableStateOf("\u22124") }
    var mode by remember { mutableStateOf(AngleMode.DEG) }
    var out by remember { mutableStateOf("") }
    var extra by remember { mutableStateOf("") }
    var err by remember { mutableStateOf(false) }
    var polar by remember { mutableStateOf(false) }

    fun a(): ComplexNum = ComplexNum.ofLiteral(are, aim)
    fun b(): ComplexNum = ComplexNum.ofLiteral(bre, bim)

    fun run() {
        err = false
        try {
            val x = a()
            val y = b()
            val r = when (op) {
                0 -> x.plus(y)
                1 -> x.minus(y)
                2 -> x.times(y)
                else -> x.div(y)
            }
            out = if (polar) polarText(r, mode) else r.format()
            extra = ""
        } catch (e: Exception) {
            out = e.message ?: "Calculation failed"
            err = true
            extra = ""
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.body)
            .safeAreaPadding()
            .padding(10.dp)
            .verticalScroll(rememberScrollState())
    ) {
        SubHeader("Complex CMPLX", "a+bi · Four arithmetic ops · Modulus/Arg/Conjugate · Rect ⇄ Polar", onBack)
        Spacer(Modifier.height(10.dp))

        Text("Operation", color = c.chromeInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            CM_OPS.forEachIndexed { i, t ->
                SubButton(
                    label = t,
                    primary = i == op,
                    modifier = Modifier.weight(1f),
                ) { op = i; run() }
            }
        }
        Spacer(Modifier.height(12.dp))

        Text("Complex A", color = c.chromeInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumField("Real a", are, { are = it }, Modifier.weight(1f))
            NumField("Imag b", aim, { aim = it }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Text("Complex B", color = c.chromeInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumField("Real c", bre, { bre = it }, Modifier.weight(1f))
            NumField("Imag d", bim, { bim = it }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SubButton("A + B", Modifier.weight(1f), primary = true) { op = 0; run() }
            SubButton("A \u2212 B", Modifier.weight(1f)) { op = 1; run() }
            SubButton("A \u00D7 B", Modifier.weight(1f)) { op = 2; run() }
            SubButton("A \u00F7 B", Modifier.weight(1f)) { op = 3; run() }
        }
        Spacer(Modifier.height(8.dp))

        Text("Unary Complex Operations", color = c.chromeInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SubButton("Modulus |A|", Modifier.weight(1f)) {
                err = false
                try {
                    val x = a()
                    val ex = x.modulusExact()
                    out = "|A| = " + (if (ex != null) CalcEngine.formatRational(ex, false) else dstr(x.modulus()))
                    extra = ""
                } catch (e: Exception) { out = e.message ?: ""; err = true }
            }
            SubButton("Arg arg A", Modifier.weight(1f)) {
                err = false
                try {
                    out = "arg A = " + dstr(a().arg(mode)) + angleUnit(mode)
                    extra = ""
                } catch (e: Exception) { out = e.message ?: ""; err = true }
            }
            SubButton("Conjugate A", Modifier.weight(1f)) {
                err = false
                try { out = a().conjugate().format() } catch (e: Exception) { out = e.message ?: ""; err = true }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SubButton("A → r∠θ", Modifier.weight(1f)) {
                err = false
                try { out = polarText(a(), mode) } catch (e: Exception) { out = e.message ?: ""; err = true }
            }
            SubButton("A → a+bi", Modifier.weight(1f)) {
                err = false
                try { out = a().format() } catch (e: Exception) { out = e.message ?: ""; err = true }
            }
            SubButton("r∠θ → A", Modifier.weight(1f)) {
                err = false
                try {
                    val r = are.trim().toDoubleOrNull() ?: throw Exception("r is not a valid number")
                    val th = aim.trim().toDoubleOrNull() ?: throw Exception("θ is not a valid number")
                    out = ComplexNum.fromPolar(r, th, mode).format()
                } catch (e: Exception) { out = e.message ?: ""; err = true }
            }
        }
        Spacer(Modifier.height(10.dp))

        Text("Angle Unit", color = c.chromeInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AngleMode.entries.forEach { m ->
                SubButton(m.label, Modifier.weight(1f), primary = m == mode) { mode = m }
            }
        }
        Spacer(Modifier.height(12.dp))

        Text("Result", color = c.chromeInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        ResultBox(out, err)
        if (extra.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            ResultBox(extra)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Decimals are converted to rational numbers, and arithmetic runs on exact fraction paths; modulus gives exact radicals when reducible.",
            color = c.keyNeutralInk.copy(alpha = 0.65f), fontSize = 11.sp,
        )
    }
}

/** Complex number → r∠θ text */
private fun polarText(z: ComplexNum, mode: AngleMode): String {
    val p = z.toPolar(mode)
    return "${dstr(p.r)}\u2220${dstr(p.theta)}${angleUnit(mode)}"
}

private val MAT_NAMES = listOf("A", "B", "C", "D")

/**
 * ② Matrix Mode MATRIX: MatA–MatD (up to 4×4); input/edit, add/subtract/multiply, determinant, inverse,
 * transpose, identity, scalar multiplication. Integer / fraction matrices remain fully exact.
 */
@Composable
fun MatrixScreen(onBack: () -> Unit, store: MatrixStore = MatrixStore()) {
    val c = LocalCalcColors.current
    val cells = remember { mutableStateListOf(*Array(16) { "0" }) }
    var cur by remember { mutableStateOf("A") }
    var other by remember { mutableStateOf("B") }
    var rows by remember { mutableStateOf(2) }
    var cols by remember { mutableStateOf(2) }
    var k by remember { mutableStateOf("2") }
    var out by remember { mutableStateOf("") }
    var err by remember { mutableStateOf(false) }

    fun build(r: Int, cc: Int): Matrix {
        val list = ArrayList<Value>()
        for (i in 0 until r) for (j in 0 until cc) list.add(ComplexNum.literal(cells[i * 4 + j]))
        return Matrix.of(r, cc, list)
    }

    fun writeBack(m: Matrix) {
        rows = m.rows
        cols = m.cols
        for (i in 0 until m.rows) for (j in 0 until m.cols) cells[i * 4 + j] = ExactMath.str(m[i, j])
    }

    fun load(name: String) {
        val m = store.get(name)
        if (m != null) {
            writeBack(m)
        } else {
            for (i in 0 until 16) cells[i] = "0"
            rows = 2; cols = 2
        }
        out = ""
        err = false
    }

    fun guard(block: () -> Unit) {
        err = false
        try {
            block()
        } catch (e: Exception) {
            out = e.message ?: "Calculation failed"
            err = true
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.body)
            .safeAreaPadding()
            .padding(10.dp)
            .verticalScroll(rememberScrollState())
    ) {
        SubHeader("Matrix MATRIX", "MatA\u2013D (≤4\u00D74) · Add/Sub/Mul · det · Inverse · Transpose · Identity · Scalar", onBack)
        Spacer(Modifier.height(10.dp))

        Text("Current Matrix", color = c.chromeInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MAT_NAMES.forEach { n ->
                SubButton("Mat$n", Modifier.weight(1f), primary = n == cur) { cur = n; load(n) }
            }
        }
        Spacer(Modifier.height(10.dp))

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Rows $rows", color = c.chromeInk, fontSize = 12.sp, modifier = Modifier.weight(1f))
            SubButton("−", Modifier.width(52.dp)) { if (rows > 1) rows-- }
            Spacer(Modifier.width(6.dp))
            SubButton("+", Modifier.width(52.dp)) { if (rows < 4) rows++ }
            Spacer(Modifier.width(16.dp))
            Text("Cols $cols", color = c.chromeInk, fontSize = 12.sp, modifier = Modifier.weight(1f))
            SubButton("−", Modifier.width(52.dp)) { if (cols > 1) cols-- }
            Spacer(Modifier.width(6.dp))
            SubButton("+", Modifier.width(52.dp)) { if (cols < 4) cols++ }
        }
        Spacer(Modifier.height(10.dp))

        for (i in 0 until rows) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                for (j in 0 until cols) {
                    val idx = i * 4 + j
                    NumField("$i,$j", cells[idx], { cells[idx] = it }, Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        Spacer(Modifier.height(4.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SubButton("Store Mat$cur", Modifier.weight(1f), primary = true) {
                guard { store.set(cur, build(rows, cols)); out = "Stored into Mat$cur" }
            }
            SubButton("Identity", Modifier.weight(1f)) {
                guard { val m = Matrix.identity(rows); store.set(cur, m); writeBack(m); out = "Identity $rows\u00D7$rows" }
            }
            SubButton("Zero", Modifier.weight(1f)) {
                guard { val m = Matrix.zeros(rows, cols); store.set(cur, m); writeBack(m); out = "Zero matrix" }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SubButton("Transpose", Modifier.weight(1f)) { guard { writeBack(build(rows, cols).transpose()); out = "Transposed" } }
            SubButton("Determinant", Modifier.weight(1f)) { guard { out = "det = " + ExactMath.str(build(rows, cols).det()) } }
            SubButton("Inverse", Modifier.weight(1f)) { guard { writeBack(build(rows, cols).inverse()); out = "Inverted" } }
        }
        Spacer(Modifier.height(10.dp))

        Text("Second Matrix", color = c.chromeInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MAT_NAMES.forEach { n ->
                SubButton("Mat$n", Modifier.weight(1f), primary = n == other) { other = n }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SubButton("$cur + $other", Modifier.weight(1f)) {
                guard { writeBack(build(rows, cols).plus(store.get(other) ?: throw Exception("Mat$other not stored"))); out = "Mat$cur + Mat$other" }
            }
            SubButton("$cur − $other", Modifier.weight(1f)) {
                guard { writeBack(build(rows, cols).minus(store.get(other) ?: throw Exception("Mat$other not stored"))); out = "Mat$cur − Mat$other" }
            }
            SubButton("$cur × $other", Modifier.weight(1f)) {
                guard { writeBack(build(rows, cols).times(store.get(other) ?: throw Exception("Mat$other not stored"))); out = "Mat$cur × Mat$other" }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            NumField("Scalar k", k, { k = it }, Modifier.weight(1f))
            SubButton("$cur × k", Modifier.weight(1f)) {
                guard { writeBack(build(rows, cols).scalar(ComplexNum.literal(k))); out = "Mat$cur × $k" }
            }
        }
        Spacer(Modifier.height(12.dp))

        Text("Result", color = c.chromeInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        ResultBox(out, err)
        Spacer(Modifier.height(8.dp))
        Text(
            "Elements support decimals and a/b fractions; integer/fraction matrix arithmetic, det, and inverse remain exact throughout.",
            color = c.keyNeutralInk.copy(alpha = 0.65f), fontSize = 11.sp,
        )
    }
}

private val VEC_NAMES = listOf("A", "B", "C", "D")

/**
 * ③ Vector Mode VECTOR: VctA–VctD (3D); add/sub, dot, cross, norm, unit vector, angle.
 */
@Composable
fun VectorScreen(onBack: () -> Unit, store: VectorStore = VectorStore()) {
    val c = LocalCalcColors.current
    var cur by remember { mutableStateOf("A") }
    var other by remember { mutableStateOf("B") }
    var ax by remember { mutableStateOf("1") }
    var ay by remember { mutableStateOf("2") }
    var az by remember { mutableStateOf("3") }
    var mode by remember { mutableStateOf(AngleMode.DEG) }
    var out by remember { mutableStateOf("") }
    var err by remember { mutableStateOf(false) }

    fun cur3(): Vector3 = Vector3.ofLiteral(ax, ay, az)
    fun writeBack(v: Vector3) {
        ax = ExactMath.str(v.x)
        ay = ExactMath.str(v.y)
        az = ExactMath.str(v.z)
    }
    fun load(name: String) {
        val v = store.get(name)
        if (v != null) writeBack(v) else { ax = "0"; ay = "0"; az = "0" }
        out = ""
        err = false
    }
    fun guard(block: () -> Unit) {
        err = false
        try {
            block()
        } catch (e: Exception) {
            out = e.message ?: "Calculation failed"
            err = true
        }
    }
    fun other3(): Vector3 = store.get(other) ?: throw Exception("Vct$other not stored")

    Column(
        Modifier
            .fillMaxSize()
            .background(c.body)
            .safeAreaPadding()
            .padding(10.dp)
            .verticalScroll(rememberScrollState())
    ) {
        SubHeader("Vector VECTOR", "VctA\u2013D (3D) · Add/Sub · Dot · Cross · Norm · Unit · Angle", onBack)
        Spacer(Modifier.height(10.dp))

        Text("Current Vector", color = c.chromeInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            VEC_NAMES.forEach { n ->
                SubButton("Vct$n", Modifier.weight(1f), primary = n == cur) { cur = n; load(n) }
            }
        }
        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumField("x", ax, { ax = it }, Modifier.weight(1f))
            NumField("y", ay, { ay = it }, Modifier.weight(1f))
            NumField("z", az, { az = it }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SubButton("Store Vct$cur", Modifier.weight(1f), primary = true) {
                guard { store.set(cur, cur3()); out = "Stored Vct$cur = ${cur3().format()}" }
            }
            SubButton("Norm |$cur|", Modifier.weight(1f)) {
                guard {
                    val v = cur3()
                    val ex = v.normExact()
                    out = "|Vct$cur| = " + (if (ex != null) CalcEngine.formatRational(ex, false) else dstr(v.norm()))
                }
            }
            SubButton("Unit $cur", Modifier.weight(1f)) {
                guard { val u = cur3().unit(); writeBack(u); out = "Normalized = ${u.format()}" }
            }
        }
        Spacer(Modifier.height(10.dp))

        Text("Second Vector", color = c.chromeInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            VEC_NAMES.forEach { n ->
                SubButton("Vct$n", Modifier.weight(1f), primary = n == other) { other = n }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SubButton("$cur + $other", Modifier.weight(1f)) {
                guard { val r = cur3().plus(other3()); writeBack(r); out = r.format() }
            }
            SubButton("$cur − $other", Modifier.weight(1f)) {
                guard { val r = cur3().minus(other3()); writeBack(r); out = r.format() }
            }
            SubButton("$cur · $other", Modifier.weight(1f)) {
                guard { out = "${cur}·${other} = " + ExactMath.str(cur3().dot(other3())) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SubButton("$cur × $other", Modifier.weight(1f)) {
                guard { val r = cur3().cross(other3()); writeBack(r); out = r.format() }
            }
            SubButton("Angle θ", Modifier.weight(1f)) {
                guard { out = "θ = " + dstr(cur3().angleTo(other3(), mode)) + angleUnit(mode) }
            }
        }
        Spacer(Modifier.height(10.dp))

        Text("Angle Unit (Angle output)", color = c.chromeInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AngleMode.entries.forEach { m ->
                SubButton(m.label, Modifier.weight(1f), primary = m == mode) { mode = m }
            }
        }
        Spacer(Modifier.height(12.dp))

        Text("Result", color = c.chromeInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        ResultBox(out, err)
        Spacer(Modifier.height(8.dp))
        Text(
            "Integer / fraction vector add, sub, dot, and cross products remain exact; norm gives exact radicals when reducible.",
            color = c.keyNeutralInk.copy(alpha = 0.65f), fontSize = 11.sp,
        )
    }
}

/**
 * ④ Statistics Mode STAT: Single-variable (n / Σx / Σx² / mean / population σ / sample s / min max / median),
 * Two-variable linear regression y = a + b·x and correlation r; editable data table.
 */
@Composable
fun StatScreen(onBack: () -> Unit) {
    val c = LocalCalcColors.current
    var twoVar by remember { mutableStateOf(false) }
    var xIn by remember { mutableStateOf("") }
    var yIn by remember { mutableStateOf("") }
    val xs = remember { mutableStateListOf<Double>() }
    val ys = remember { mutableStateListOf<Double>() }
    var msg by remember { mutableStateOf("") }

    fun parse(s: String): Double? = try {
        if (s.isBlank()) null else ComplexNum.literal(s).toDouble()
    } catch (_: Exception) {
        null
    }

    fun add() {
        val x = parse(xIn)
        if (x == null) { msg = "x is not a valid number"; return }
        if (twoVar) {
            val y = parse(yIn)
            if (y == null) { msg = "y is not a valid number"; return }
            xs.add(x); ys.add(y)
        } else {
            xs.add(x)
        }
        xIn = ""; yIn = ""; msg = "Added (${xs.size} entries)"
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.body)
            .safeAreaPadding()
            .padding(10.dp)
            .verticalScroll(rememberScrollState())
    ) {
        SubHeader("Statistics & Regression STAT", "Single-variable descriptive statistics · Two-variable linear regression y = a + b·x", onBack)
        Spacer(Modifier.height(10.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SubButton("Single Variable", Modifier.weight(1f), primary = !twoVar) { twoVar = false }
            SubButton("Two Variables", Modifier.weight(1f), primary = twoVar) { twoVar = true }
        }
        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumField("x", xIn, { xIn = it }, Modifier.weight(1f))
            if (twoVar) NumField("y", yIn, { yIn = it }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SubButton("Add", Modifier.weight(1f), primary = true) { add() }
            SubButton("Clear All", Modifier.weight(1f)) { xs.clear(); ys.clear(); msg = "Cleared" }
        }
        if (msg.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(msg, color = c.keyNeutralInk.copy(alpha = 0.7f), fontSize = 11.sp)
        }
        Spacer(Modifier.height(10.dp))

        Text("Data Table (tap right to delete)", color = c.chromeInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        if (xs.isEmpty()) {
            Text("(No data)", color = c.keyNeutralInk.copy(alpha = 0.6f), fontSize = 12.sp)
        } else {
            xs.indices.forEach { i ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (twoVar) "${i + 1}.  x=${dstr(xs[i])}   y=${dstr(ys[i])}"
                        else "${i + 1}.  x=${dstr(xs[i])}",
                        fontFamily = Mono, fontSize = 12.sp, color = c.bodyInk,
                        modifier = Modifier.weight(1f),
                    )
                    SubButton("Delete", Modifier.width(60.dp)) {
                        xs.removeAt(i)
                        if (twoVar) ys.removeAt(i)
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
        Spacer(Modifier.height(10.dp))

        Text("Result", color = c.chromeInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        if (xs.isEmpty()) {
            ResultBox("")
        } else if (!twoVar) {
            val r = StatOps.oneVar(xs.toList())
            ResultBox(
                listOf(
                    "n      = ${r.n}",
                    "Σx     = ${dstr(r.sum)}",
                    "Σx²    = ${dstr(r.sumSq)}",
                    "x̄      = ${dstr(r.mean)}",
                    "σx     = ${dstr(r.popSigma)}   (Population)",
                    "sx     = ${if (r.sampleS.isNaN()) "—" else dstr(r.sampleS)}   (Sample)",
                    "min    = ${dstr(r.min)}",
                    "max    = ${dstr(r.max)}",
                    "Median = ${dstr(r.median)}",
                ).joinToString("\n")
            )
        } else {
            val r = StatOps.linearRegression(xs.toList(), ys.toList())
            ResultBox(
                listOf(
                    "n  = ${r.n}",
                    "a  = ${dstr(r.a)}",
                    "b  = ${dstr(r.b)}",
                    "r  = ${dstr(r.r)}",
                    "y = a + b·x",
                ).joinToString("\n")
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "σ uses denominator n, s uses denominator n−1; regression form is y = a + b·x.",
            color = c.keyNeutralInk.copy(alpha = 0.65f), fontSize = 11.sp,
        )
    }
}

private val DISTR_TABS = listOf("Normal", "Binomial", "Poisson")

/**
 * ⑤ Distribution Mode DISTR: Normal (given μ, σ find P/Q/R), Binomial, Poisson.
 */
@Composable
fun DistrScreen(onBack: () -> Unit) {
    val c = LocalCalcColors.current
    var tab by remember { mutableStateOf(0) }
    var a1 by remember { mutableStateOf("0") }
    var a2 by remember { mutableStateOf("1") }
    var a3 by remember { mutableStateOf("1") }
    var out by remember { mutableStateOf("") }
    var err by remember { mutableStateOf(false) }

    fun num(s: String, what: String): Double =
        s.trim().toDoubleOrNull() ?: throw Exception("$what is not a valid number")

    fun intNum(s: String, what: String): Int {
        val d = num(s, what)
        if (d != Math.floor(d)) throw Exception("$what must be an integer")
        return d.toInt()
    }

    fun run() {
        err = false
        try {
            out = when (tab) {
                0 -> {
                    val r = DistrOps.normal(num(a3, "x"), num(a1, "μ"), num(a2, "σ"))
                    listOf(
                        "Standardized x: t = (x − μ) / σ",
                        "P = P(X < x)  = ${dstr(r.p)}",
                        "Q = P(X > x)  = ${dstr(r.q)}",
                        "R = Symmetric  = ${dstr(r.r)}",
                        "Density φ = ${dstr(DistrOps.normPdf((num(a3, "x") - num(a1, "μ")) / num(a2, "σ")))}",
                    ).joinToString("\n")
                }
                1 -> {
                    val n = intNum(a1, "n")
                    val p = num(a2, "p")
                    val k = intNum(a3, "k")
                    listOf(
                        "P(X = k) = ${dstr(DistrOps.binomialPdf(n, k, p))}",
                        "P(X ≤ k) = ${dstr(DistrOps.binomialCdf(n, k, p))}",
                        "P(X ≥ k) = ${dstr(1.0 - DistrOps.binomialCdf(n, k - 1, p))}",
                    ).joinToString("\n")
                }
                else -> {
                    val lam = num(a1, "λ")
                    val k = intNum(a3, "k")
                    listOf(
                        "P(X = k) = ${dstr(DistrOps.poissonPdf(lam, k))}",
                        "P(X ≤ k) = ${dstr(DistrOps.poissonCdf(lam, k))}",
                        "P(X ≥ k) = ${dstr(1.0 - DistrOps.poissonCdf(lam, k - 1))}",
                    ).joinToString("\n")
                }
            }
        } catch (e: Exception) {
            out = e.message ?: "Calculation failed"
            err = true
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.body)
            .safeAreaPadding()
            .padding(10.dp)
            .verticalScroll(rememberScrollState())
    ) {
        SubHeader("Probability Distribution DISTR", "Normal · Binomial · Poisson", onBack)
        Spacer(Modifier.height(10.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DISTR_TABS.forEachIndexed { i, t ->
                SubButton(t, Modifier.weight(1f), primary = i == tab) { tab = i }
            }
        }
        Spacer(Modifier.height(12.dp))

        when (tab) {
            0 -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NumField("μ", a1, { a1 = it }, Modifier.weight(1f))
                NumField("σ", a2, { a2 = it }, Modifier.weight(1f))
                NumField("x", a3, { a3 = it }, Modifier.weight(1f))
            }
            1 -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NumField("n", a1, { a1 = it }, Modifier.weight(1f))
                NumField("p", a2, { a2 = it }, Modifier.weight(1f))
                NumField("k", a3, { a3 = it }, Modifier.weight(1f))
            }
            else -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NumField("λ", a1, { a1 = it }, Modifier.weight(1f))
                NumField("k", a3, { a3 = it }, Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(10.dp))
        SubButton("Calculate", Modifier.fillMaxWidth(), primary = true) { run() }
        Spacer(Modifier.height(12.dp))

        Text("Result", color = c.chromeInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        ResultBox(out, err)
        Spacer(Modifier.height(8.dp))
        Text(
            "Normal: P is left tail, Q is right tail, R is symmetric central area about mean;\n" +
                "Binomial / Poisson provide point probabilities and cumulative distribution.",
            color = c.keyNeutralInk.copy(alpha = 0.65f), fontSize = 11.sp,
        )
    }
}

/**
 * ⑥ Function Help FUNC HELP: Function catalog + syntax help; tap an item to insert syntax into expression.
 */
@Composable
fun FuncHelpScreen(vm: CalcViewModel, onBack: () -> Unit) {
    val c = LocalCalcColors.current
    Column(
        Modifier
            .fillMaxSize()
            .background(c.body)
            .safeAreaPadding()
            .padding(10.dp)
            .verticalScroll(rememberScrollState())
    ) {
        SubHeader("Function Help FUNC HELP", "${FuncHelp.COUNT} functions · Tap to insert into expression", onBack)
        Spacer(Modifier.height(10.dp))

        FuncHelp.byCategory().forEach { (cat, list) ->
            Text(cat, color = c.chromeInk, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            list.forEach { e ->
                Button(
                    onClick = { vm.insertFromHelp(e.insert) },
                    shape = RoundedCornerShape(9.dp),
                    border = BorderStroke(1.dp, c.keyEdge),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = c.keyNeutral,
                        contentColor = c.bodyInk,
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 12.dp, vertical = 6.dp
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Text("${e.name}    ${e.syntax}", fontFamily = Mono, fontSize = 12.sp, maxLines = 1)
                        Text(
                            e.desc,
                            fontSize = 11.sp, maxLines = 2,
                            color = c.keyNeutralInk.copy(alpha = 0.7f),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}
