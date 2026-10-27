package io.paimon.fx991.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.paimon.fx991.CalcViewModel
import io.paimon.fx991.engine.AngleMode
import io.paimon.fx991.engine.CalcEngine
import io.paimon.fx991.engine.ComplexFunc
import io.paimon.fx991.engine.Cx
import io.paimon.fx991.engine.FourierOps
import io.paimon.fx991.engine.UserFunctions
import io.paimon.fx991.engine.Value
import kotlin.math.abs

// ---------------------------------------------------------------------------
// Batch G: Three new screens - Algebra (GeoGebra-style definition management + plotting) / Fourier Series / Complex Functions
// Visuals consistent with batches C / D: SubHeader + NumField + SubButton + ResultBox,
// Root nodes all use Modifier.safeAreaPadding(), dark/light themes share LocalCalcColors tokens.
// ---------------------------------------------------------------------------

private fun fmt(v: Double): String = CalcEngine.format(v, 10, null)

private fun fmtCx(z: Cx): String {
    val re = fmt(z.re)
    if (abs(z.im) <= 1e-12) return re
    val im = fmt(abs(z.im))
    return if (z.im < 0.0) "$re − ${im}i" else "$re + ${im}i"
}

/** Curve to plot (sample points + color) */
private class PlotSeries(val pts: List<Pair<Double, Double>>, val color: Color)

/** Function sampling (undefined / non-finite / blown-up points are skipped) */
private fun sampleFn(f: (Double) -> Double?, xmin: Double, xmax: Double, n: Int = 241): List<Pair<Double, Double>> {
    val out = ArrayList<Pair<Double, Double>>()
    for (k in 0 until n) {
        val x = xmin + (xmax - xmin) * k / (n - 1)
        val y = try {
            f(x)
        } catch (_: Exception) {
            null
        }
        if (y != null && y.isFinite() && abs(y) < 1e6) out.add(x to y)
    }
    return out
}

/** Universal curve plot: Canvas axes + multiple polylines (reuses ODE plot style) */
@Composable
private fun FunPlot(series: List<PlotSeries>, modifier: Modifier) {
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
            val all = series.flatMap { it.pts }
            if (all.isEmpty()) return@Canvas
            val xMin = all.minOf { it.first }
            val xMax = all.maxOf { it.first }
            var yMin = all.minOf { it.second }
            var yMax = all.maxOf { it.second }
            if (xMax - xMin < 1e-12) return@Canvas
            if (yMax - yMin < 1e-12) {
                yMin -= 1.0
                yMax += 1.0
            }
            val rawRange = yMax - yMin
            val padY = rawRange * 0.08
            yMin -= padY
            yMax += padY

            fun sx(x: Double): Float = ((x - xMin) / (xMax - xMin)).toFloat() * w
            fun sy(y: Double): Float = h - ((y - yMin) / (yMax - yMin)).toFloat() * h

            val axisColor = c.lcdDim
            if (yMin <= 0.0 && yMax >= 0.0) {
                val y0 = sy(0.0)
                drawLine(axisColor, Offset(0f, y0), Offset(w, y0), strokeWidth = 1.2f)
            }
            if (xMin <= 0.0 && xMax >= 0.0) {
                val x0 = sx(0.0)
                drawLine(axisColor, Offset(x0, 0f), Offset(x0, h), strokeWidth = 1.2f)
            }
            drawRect(axisColor, style = Stroke(width = 1f))

            for (s in series) {
                val path = Path()
                var started = false
                var prevY = 0.0
                for ((x, y) in s.pts) {
                    // Jump > 4x value range -> treat as discontinuity, break polyline (e.g. 1/x asymptote)
                    val jump = started && abs(y - prevY) > 4.0 * rawRange
                    if (!started || jump) {
                        path.moveTo(sx(x), sy(y))
                        started = true
                    } else {
                        path.lineTo(sx(x), sy(y))
                    }
                    prevY = y
                }
                drawPath(path, s.color, style = Stroke(width = 2.2f))
            }
        }
    }
}

private val SERIES_COLORS = listOf(ShiftOrange, AlphaPurple, Color(0xFF2E9E5B), Color(0xFF3B7DD8))

// ---------------------------------------------------------------------------
// ① Algebra (GeoGebra style: view, insert, delete, plot defined functions and variables)
// ---------------------------------------------------------------------------

@Composable
fun AlgebraScreen(vm: CalcViewModel, onBack: () -> Unit) {
    val c = LocalCalcColors.current
    var defineInput by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var msgError by remember { mutableStateOf(false) }
    val plotOn = remember { mutableStateListOf<String>() }
    var xminText by remember { mutableStateOf("-10") }
    var xmaxText by remember { mutableStateOf("10") }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.body)
            .safeAreaPadding()
            .padding(horizontal = 12.dp)
            .verticalScroll(rememberScrollState())
    ) {
        SubHeader("Algebra", "GeoGebra style: enter f(x)=x^2 and press = to define; f(3) eval, f'(2) derivative, nesting supported", onBack)
        Spacer(Modifier.height(10.dp))

        // Quick definition
        Row(verticalAlignment = Alignment.CenterVertically) {
            NumField("Define here, e.g. f(x)=x^2 or g(t):=t^2+1", defineInput, { defineInput = it }, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            SubButton("Define", primary = true) {
                try {
                    message = vm.defineFromAlgebra(defineInput)
                    msgError = false
                    defineInput = ""
                } catch (e: Exception) {
                    message = e.message ?: "Definition failed"
                    msgError = true
                }
            }
        }
        if (message.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            ResultBox(message, error = msgError)
        }
        Spacer(Modifier.height(14.dp))

        // Defined functions
        Text("Defined Functions (${vm.definedFunctions.size})", color = c.chromeInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        if (vm.definedFunctions.isEmpty()) {
            Text(
                "No custom functions yet. Enter f(x)=x^2 on main line and press =, or define above.",
                color = c.keyNeutralInk.copy(alpha = 0.65f), fontSize = 11.sp,
            )
        }
        vm.definedFunctions.forEach { def ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    def.fullText(),
                    color = c.bodyInk, fontFamily = Mono, fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                SubButton("Insert") { vm.insertFromHelp(def.name + "(") }
                Spacer(Modifier.width(5.dp))
                SubButton(if (def.name in plotOn) "Hide" else "Plot",
                    primary = def.name in plotOn) {
                    if (def.name in plotOn) plotOn.remove(def.name) else plotOn.add(def.name)
                }
                Spacer(Modifier.width(5.dp))
                SubButton("Delete") {
                    plotOn.remove(def.name)
                    vm.removeUserFunction(def.name)
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        // STO variables
        Text("Variables (Stored via STO)", color = c.chromeInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        if (vm.variables.isEmpty()) {
            Text(
                "No variables yet. After calculating a result on main line, press STO (SHIFT + RCL) to store into A–F / x / y.",
                color = c.keyNeutralInk.copy(alpha = 0.65f), fontSize = 11.sp,
            )
        }
        vm.variables.forEach { (name, v) ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "$name = ${vm.formatNumber(v)}",
                    color = c.bodyInk, fontFamily = Mono, fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                SubButton("Insert") { vm.insertFromHelp(name) }
                Spacer(Modifier.width(5.dp))
                SubButton("Delete") { vm.removeVariable(name) }
            }
        }

        // Plotting
        if (plotOn.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text("Function Plot", color = c.chromeInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            Row {
                NumField("x Min", xminText, { xminText = it }, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                NumField("x Max", xmaxText, { xmaxText = it }, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            val xmin = xminText.toDoubleOrNull() ?: -10.0
            val xmax = xmaxText.toDoubleOrNull() ?: 10.0
            if (xmax > xmin) {
                val series = plotOn.mapIndexed { idx, name ->
                    PlotSeries(
                        sampleFn({ x ->
                            UserFunctions.call(name, listOf(Value.of(x)), 0, AngleMode.RAD).toDouble()
                        }, xmin, xmax),
                        SERIES_COLORS[idx % SERIES_COLORS.size],
                    )
                }
                FunPlot(series, Modifier.fillMaxWidth().height(220.dp))
                Spacer(Modifier.height(4.dp))
                Text(
                    plotOn.mapIndexed { idx, n -> "${n}(x)" }.joinToString(" · ") + " (sampled in radians)",
                    color = c.keyNeutralInk.copy(alpha = 0.65f), fontSize = 10.sp,
                )
            } else {
                Text("x Max must be greater than x Min", color = c.lcdError, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

// ---------------------------------------------------------------------------
// ② Fourier Series: First n terms of f(x) on [a, b] + partial sum + comparison curve
// ---------------------------------------------------------------------------

@Composable
fun FourierScreen(onBack: () -> Unit) {
    val c = LocalCalcColors.current
    var f by remember { mutableStateOf("x^2") }
    var aText by remember { mutableStateOf("-pi") }
    var bText by remember { mutableStateOf("pi") }
    var nText by remember { mutableStateOf("5") }
    var result by remember { mutableStateOf<FourierOps.Result?>(null) }
    var report by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.body)
            .safeAreaPadding()
            .padding(horizontal = 12.dp)
            .verticalScroll(rememberScrollState())
    ) {
        SubHeader("Fourier Series", "You can also enter fourier(x^2, -pi, pi, 5) on the main line", onBack)
        Spacer(Modifier.height(10.dp))
        NumField("f(x)", f, { f = it }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row {
            NumField("Lower limit a", aText, { aText = it }, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            NumField("Upper limit b", bText, { bText = it }, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            NumField("Terms n", nText, { nText = it }, Modifier.weight(0.7f))
        }
        Spacer(Modifier.height(8.dp))
        SubButton("Compute Fourier Coefficients", primary = true, modifier = Modifier.fillMaxWidth()) {
            error = ""
            try {
                val args = FourierOps.parseMain("fourier($f,$aText,$bText,$nText)")
                    ?: throw IllegalStateException("parse")
                val r = FourierOps.compute(args.f, args.a, args.b, args.n)
                result = r
                report = FourierOps.formatReport(r)
            } catch (e: Exception) {
                result = null
                report = ""
                error = e.message ?: "Calculation failed"
            }
        }
        Spacer(Modifier.height(8.dp))
        if (error.isNotEmpty()) ResultBox(error, error = true)
        if (report.isNotEmpty()) {
            ResultBox(report)
            Spacer(Modifier.height(6.dp))
            Text(
                "Coefficients computed via adaptive Simpson numerical integration (tol 1e-9, radians); partial sum expression can be pasted to main line (note main line trig uses angle mode).",
                color = c.keyNeutralInk.copy(alpha = 0.65f), fontSize = 10.sp,
            )
        }
        val r = result
        if (r != null) {
            Spacer(Modifier.height(12.dp))
            Text("Original Function vs Partial Sum S${r.n}(x)", color = c.chromeInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            val orig = sampleFn({ x -> r.fAt(x) }, r.a, r.b)
            val part = sampleFn({ x -> r.partialAt(x) }, r.a, r.b)
            FunPlot(
                listOf(PlotSeries(orig, ShiftOrange), PlotSeries(part, AlphaPurple)),
                Modifier.fillMaxWidth().height(220.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Orange: f(x)  Purple: S${r.n}(x) (Gibbs phenomenon near discontinuities)",
                color = c.keyNeutralInk.copy(alpha = 0.65f), fontSize = 10.sp,
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

// ---------------------------------------------------------------------------
// ③ Complex Functions: Evaluation / Residue / Residue Theorem Contour Integration
// ---------------------------------------------------------------------------

@Composable
fun CplxFuncScreen(onBack: () -> Unit) {
    val c = LocalCalcColors.current
    // Evaluation
    var evalF by remember { mutableStateOf("exp(z)") }
    var evalZ by remember { mutableStateOf("i*pi") }
    var evalOut by remember { mutableStateOf("") }
    var evalErr by remember { mutableStateOf(false) }
    // Residue
    var resF by remember { mutableStateOf("1/(z^2+1)") }
    var resZ by remember { mutableStateOf("i") }
    var resOut by remember { mutableStateOf("") }
    var resErr by remember { mutableStateOf(false) }
    // Contour integration
    var cintF by remember { mutableStateOf("1/(z^2+1)") }
    var cintPoles by remember { mutableStateOf("i, -i") }
    var cintOut by remember { mutableStateOf("") }
    var cintErr by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.body)
            .safeAreaPadding()
            .padding(horizontal = 12.dp)
            .verticalScroll(rememberScrollState())
    ) {
        SubHeader("Complex Functions", "z is the variable, i is imaginary unit; or enter res(f(z), z0) / cint(f(z), p1, …) on main line", onBack)
        Spacer(Modifier.height(6.dp))
        Text(
            ComplexFunc.BRANCH_NOTE + "; trig / hyperbolic always in radians. See CMPLX mode for modulus / arg / conjugate input.",
            color = c.keyNeutralInk.copy(alpha = 0.65f), fontSize = 10.sp,
        )
        Spacer(Modifier.height(12.dp))

        // ---- Complex Function Evaluation ----
        Text("Complex Function Evaluation", color = c.chromeInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Row {
            NumField("f(z), e.g. exp(z) / sin(z) / z^2+1", evalF, { evalF = it }, Modifier.weight(1.4f))
            Spacer(Modifier.width(8.dp))
            NumField("z =", evalZ, { evalZ = it }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        SubButton("Evaluate", primary = true, modifier = Modifier.fillMaxWidth()) {
            try {
                val z = ComplexFunc.evalExpr(evalZ, Cx.ZERO)
                val v = ComplexFunc.evalExpr(evalF, z)
                evalOut = "f(${fmtCx(z)}) = ${fmtCx(v)}\n|Value| = ${fmt(v.modulus())}, arg = ${fmt(v.arg())} rad"
                evalErr = false
            } catch (e: Exception) {
                evalOut = e.message ?: "Calculation failed"
                evalErr = true
            }
        }
        if (evalOut.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            ResultBox(evalOut, error = evalErr)
        }
        Spacer(Modifier.height(14.dp))

        // ---- Residue ----
        Text("Residue Res(f, z0)", color = c.chromeInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Row {
            NumField("f(z)", resF, { resF = it }, Modifier.weight(1.4f))
            Spacer(Modifier.width(8.dp))
            NumField("z0 =", resZ, { resZ = it }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        SubButton("Find Residue", primary = true, modifier = Modifier.fillMaxWidth()) {
            try {
                val z0 = ComplexFunc.evalExpr(resZ, Cx.ZERO)
                val r = ComplexFunc.residueOf(resF, z0.re, z0.im)
                resOut = "Res = ${fmtCx(r.value)} (${r.note})"
                resErr = false
            } catch (e: Exception) {
                resOut = e.message ?: "Calculation failed"
                resErr = true
            }
        }
        if (resOut.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            ResultBox(resOut, error = resErr)
        }
        Spacer(Modifier.height(14.dp))

        // ---- Contour Integration ----
        Text("Contour Integral ∮ f(z) dz (Residue Theorem)", color = c.chromeInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        NumField("f(z)", cintF, { cintF = it }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        NumField("Poles inside contour (comma-separated), e.g. i, -i", cintPoles, { cintPoles = it }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        SubButton("Integrate via 2πi·ΣRes", primary = true, modifier = Modifier.fillMaxWidth()) {
            try {
                val poles = cintPoles.split(',', ';', '\uff0c')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map { ComplexFunc.evalExpr(it, Cx.ZERO) }
                val (v, note) = ComplexFunc.contourOf(cintF, poles)
                cintOut = "∮ f dz = ${fmtCx(v)}\n$note"
                cintErr = false
            } catch (e: Exception) {
                cintOut = e.message ?: "Calculation failed"
                cintErr = true
            }
        }
        if (cintOut.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            ResultBox(cintOut, error = cintErr)
        }
        Spacer(Modifier.height(16.dp))
    }
}
