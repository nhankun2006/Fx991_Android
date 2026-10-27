package io.paimon.fx991.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.paimon.fx991.engine.AngleMode
import io.paimon.fx991.engine.OdeError
import io.paimon.fx991.engine.OdeSolver
import io.paimon.fx991.engine.OdeTable

private enum class OdeKind(val label: String) {
    FIRST("1st Order  dy/dx = f(x, y)"),
    SECOND("2nd Order  y'' = f(x, y, y')"),
    ANALYTIC("Linear Const-Coeff  ay'' + by' + cy = 0"),
}

private fun fmt(v: Double): String {
    if (v.isNaN()) return "-"
    val a = kotlin.math.abs(v)
    return if (a != 0.0 && (a >= 1e7 || a < 1e-4)) String.format("%.6e", v)
    else String.format("%.6f", v).trimEnd('0').trimEnd('.')
}

@Composable
fun OdeScreen(onBack: () -> Unit) {
    val c = LocalCalcColors.current
    var kind by remember { mutableStateOf(OdeKind.FIRST) }
    var fExpr by remember { mutableStateOf("x - y") }
    var aExpr by remember { mutableStateOf("1") }
    var bExpr by remember { mutableStateOf("3") }
    var cExpr by remember { mutableStateOf("2") }
    var x0 by remember { mutableStateOf("0") }
    var y0 by remember { mutableStateOf("1") }
    var v0 by remember { mutableStateOf("0") }
    var xn by remember { mutableStateOf("2") }
    var h by remember { mutableStateOf("0.1") }

    var table by remember { mutableStateOf<OdeTable?>(null) }
    var formula by remember { mutableStateOf<String?>(null) }
    var kindText by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun num(s: String): Double = s.trim().toDoubleOrNull()
        ?: throw OdeError("Invalid numerical value: $s")

    fun solve() {
        error = null
        table = null
        formula = null
        kindText = null
        try {
            val x0v = num(x0)
            val y0v = num(y0)
            val xnv = num(xn)
            val hv = num(h)
            val mode = AngleMode.RAD
            val t = when (kind) {
                OdeKind.FIRST -> OdeSolver.firstOrder(fExpr.ifBlank { "0" }, x0v, y0v, xnv, hv, mode)
                OdeKind.SECOND -> OdeSolver.secondOrder(
                    fExpr.ifBlank { "0" }, x0v, y0v, num(v0), xnv, hv, mode
                )
                OdeKind.ANALYTIC -> {
                    val sol = OdeSolver.analyticLinear(
                        num(aExpr), num(bExpr), num(cExpr), x0v, y0v, num(v0)
                    )
                    formula = sol.formula
                    kindText = sol.kind
                    OdeSolver.sampleAnalytic(sol, x0v, xnv, hv)
                }
            }
            table = t
        } catch (e: OdeError) {
            error = e.message ?: "Solving failed"
        } catch (e: Exception) {
            error = "Solving failed: ${e.message}"
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = onBack,
                colors = ButtonDefaults.buttonColors(
                    containerColor = c.keyNeutral,
                    contentColor = c.bodyInk,
                ),
                shape = RoundedCornerShape(8.dp),
            ) { Text("◀ Back", fontSize = 13.sp) }
            Spacer(Modifier.width(10.dp))
            Text(
                "Differential Equations",
                color = c.bodyInk,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OdeKind.entries.forEach { k ->
                val on = kind == k
                Button(
                    onClick = { kind = k },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (on) ShiftOrange else c.keyNeutral,
                        contentColor = if (on) ShiftOrangeInk else c.bodyInk,
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f),
                ) { Text(k.label, fontSize = 9.sp, maxLines = 2) }
            }
        }
        Spacer(Modifier.height(10.dp))

        if (kind == OdeKind.ANALYTIC) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Field("a", aExpr, { aExpr = it }, Modifier.weight(1f))
                Field("b", bExpr, { bExpr = it }, Modifier.weight(1f))
                Field("c", cExpr, { cExpr = it }, Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
        } else {
            Field(
                if (kind == OdeKind.FIRST) "f(x, y)=" else "y'' = f(x, y, y')=",
                fExpr, { fExpr = it }, Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Field("x0", x0, { x0 = it }, Modifier.weight(1f))
            Field("y0", y0, { y0 = it }, Modifier.weight(1f))
            if (kind != OdeKind.FIRST) {
                Field("y'(0)", v0, { v0 = it }, Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Field("xn", xn, { xn = it }, Modifier.weight(1f))
            Field("Step h", h, { h = it }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))

        Button(
            onClick = { solve() },
            colors = ButtonDefaults.buttonColors(
                containerColor = c.keyEquals,
                contentColor = c.keyEqualsInk,
            ),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().height(46.dp),
        ) { Text("Solve (RK4)", fontWeight = FontWeight.Bold) }

        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = c.lcdError, fontSize = 13.sp)
        }

        formula?.let {
            Spacer(Modifier.height(12.dp))
            Text("Analytic Solution (${kindText ?: ""})", color = c.bodyInk, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(c.lcdBg)
                    .border(1.dp, c.lcdEdge, RoundedCornerShape(6.dp))
                    .padding(8.dp)
            ) { Text(it, color = c.lcdFg, fontFamily = Mono, fontSize = 12.sp) }
        }

        table?.let { t ->
            Spacer(Modifier.height(12.dp))
            Text(
                "Curve Plot (${t.steps + 1} pts)",
                color = c.bodyInk, fontSize = 12.sp
            )
            Spacer(Modifier.height(4.dp))
            Chart(t, Modifier.fillMaxWidth().height(180.dp))

            Spacer(Modifier.height(10.dp))
            Text("Data Table  x / y" + if (t.zs != null) " / y'" else "", color = c.bodyInk, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(c.lcdBg)
            ) {
                LazyColumn(Modifier.fillMaxSize().padding(6.dp)) {
                    itemsIndexed(t.xs.indices.toList()) { _, i ->
                        Row(Modifier.fillMaxWidth()) {
                            Cell("${i}", c, 0.8f)
                            Cell(fmt(t.xs[i]), c, 1.2f)
                            Cell(fmt(t.ys[i]), c, 1.2f)
                            if (t.zs != null) Cell(fmt(t.zs!![i]), c, 1.2f)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Cell(text: String, c: CalcColors, w: Float) {
    Text(
        text = text,
        color = c.lcdFg,
        fontFamily = Mono,
        fontSize = 11.sp,
        maxLines = 1,
        modifier = Modifier.weight(w).padding(vertical = 2.dp),
    )
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier,
) {
    // Batch K3-B: Consistent with other secondary screens, uses natural display input field (custom keyboard + caret)
    NumField(label, value, onChange, modifier)
}

/** Curve plot: Canvas hand-drawn axes + curves */
@Composable
private fun Chart(t: OdeTable, modifier: Modifier) {
    val c = LocalCalcColors.current
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(c.lcdBg)
            .border(1.dp, c.lcdEdge, RoundedCornerShape(6.dp))
    ) {
        Canvas(Modifier.fillMaxSize().padding(10.dp)) {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@Canvas

            val xMin = t.xs.min()
            val xMax = t.xs.max()
            val yVals = ArrayList<Double>(t.xs.size * 2)
            t.ys.forEach { yVals.add(it) }
            t.zs?.forEach { yVals.add(it) }
            var yMin = yVals.min()
            var yMax = yVals.max()
            if (yMax - yMin < 1e-12) {
                yMin -= 1.0
                yMax += 1.0
            }
            val padY = (yMax - yMin) * 0.08
            yMin -= padY
            yMax += padY

            fun sx(x: Double): Float = ((x - xMin) / (xMax - xMin)).toFloat() * w
            fun sy(y: Double): Float = h - ((y - yMin) / (yMax - yMin)).toFloat() * h

            // Coordinate axes
            val axisColor = c.lcdDim
            if (yMin <= 0.0 && yMax >= 0.0) {
                val y0p = sy(0.0)
                drawLine(axisColor, Offset(0f, y0p), Offset(w, y0p), strokeWidth = 1.2f)
            }
            if (xMin <= 0.0 && xMax >= 0.0) {
                val x0p = sx(0.0)
                drawLine(axisColor, Offset(x0p, 0f), Offset(x0p, h), strokeWidth = 1.2f)
            }
            drawRect(axisColor, style = Stroke(width = 1f))

            // y curve
            val path = Path()
            for (i in t.xs.indices) {
                val px = sx(t.xs[i])
                val py = sy(t.ys[i])
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawPath(path, ShiftOrange, style = Stroke(width = 2.2f))

            // y' curve (when 2nd order)
            t.zs?.let { zs ->
                val p2 = Path()
                var started = false
                for (i in t.xs.indices) {
                    val z = zs[i]
                    if (z.isNaN() || z.isInfinite()) continue
                    val px = sx(t.xs[i])
                    val py = sy(z)
                    if (!started) {
                        p2.moveTo(px, py); started = true
                    } else p2.lineTo(px, py)
                }
                drawPath(p2, AlphaPurple, style = Stroke(width = 1.6f))
            }
        }
    }
}
