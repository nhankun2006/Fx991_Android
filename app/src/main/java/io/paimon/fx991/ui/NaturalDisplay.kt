package io.paimon.fx991.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class NatStyle(
    val size: TextUnit,
    val color: Color,
    val bold: Boolean = false,
)

private val MonoFont = FontFamily.Monospace

// Fraction line / radical overbar thickness, fraction horizontal padding — consistent with original implementation (styling unchanged)
private val RuleThickness = 1.5.dp
private val FracPadH = 3.dp

/** Natural display rendering: fractions vertically stacked, √ with overbar vinculum, superscript exponents (pure Compose custom layout).
 *  When cursor >= 0, draws a blinking vertical caret at the corresponding offset (Batch K3-A). */
@Composable
fun NaturalView(
    expr: String,
    style: NatStyle,
    modifier: Modifier = Modifier,
    cursor: Int = -1,
    cursorExit: Int = 0,
) {
    val node = remember(expr, cursor, cursorExit) {
        if (cursor >= 0) buildNatCursor(expr, cursor, cursorExit) else buildNat(expr)
    }
    Box(modifier) { NatNodeView(node, style, cursor) }
}

/**
 * Blinking vertical line caret (SHIFT Orange).
 * When signal changes (caret movement / text edit), requests bringIntoView — ensures caret is never lost in horizontally scrolling LCD lines / fields.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CursorGlyph(style: NatStyle, signal: Int) {
    val transition = rememberInfiniteTransition(label = "natCursor")
    val alpha by transition.animateFloat(
        initialValue = 0.15f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 530, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "natCursorAlpha",
    )
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(signal) { requester.bringIntoView() }
    Box(
        Modifier
            .bringIntoViewRequester(requester)
            .width(2.dp)
            .height((style.size.value * 1.25f).coerceAtLeast(13f).dp)
            .background(ShiftOrange.copy(alpha = alpha))
    )
}

@Composable
private fun NatNodeView(node: Nat, style: NatStyle, signal: Int = -1) {
    when (node) {
        is Nat.Sym -> if (node.text.isEmpty()) {
            // Empty slot (fraction numerator/denominator, empty exponent...): draw small rectangle prompting input
            val w = (style.size.value * 0.46f).dp
            val h = (style.size.value * 0.86f).dp
            Box(
                Modifier
                    .padding(horizontal = (style.size.value * 0.07f).dp)
                    .width(w)
                    .height(h)
                    .border(1.dp, style.color.copy(alpha = 0.45f), RoundedCornerShape(2.dp))
            )
        } else Text(
            text = natDisplayName(node.text),
            color = style.color,
            fontFamily = MonoFont,
            fontSize = style.size,
            fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
        )

        is Nat.Cursor -> CursorGlyph(style, signal)

        is Nat.Row -> Row(verticalAlignment = Alignment.CenterVertically) {
            node.items.forEach { NatNodeView(it, style, signal) }
        }

        is Nat.Frac -> FracView(node, style, signal)

        is Nat.Sqrt -> SqrtView(node, style, signal)

        is Nat.Sup -> SupView(node, style, signal)

        // Batch K3: Definite integral template — ∫ with upper limit top-right, lower limit bottom-right, integrand follows, ends with dx
        is Nat.Integ -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "\u222B",
                color = style.color,
                fontFamily = MonoFont,
                fontSize = style.size * 1.5f,
                fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                NatNodeView(node.hi, style.copy(size = style.size * 0.68f), signal)
                NatNodeView(node.lo, style.copy(size = style.size * 0.68f), signal)
            }
            NatNodeView(node.body, style, signal)
            // Keep slight spacing between dx and integrand: when integrand ends with fraction, dx won't look like part of denominator
            Box(Modifier.padding(start = 5.dp)) {
                Text(
                    text = "dx",
                    color = style.color,
                    fontFamily = MonoFont,
                    fontSize = style.size,
                    fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Superscript: Exponent attaches to **top-right of base** (baseline elevation ≈ 0.42 em, font size 0.62x).
 *
 * Legacy implementation used Row(Top) + bottom padding to fake "elevation", where padding was in dp while font was in sp —
 * if system font scaling was not 1.0, conversion distorted and exponent fell back to baseline center.
 * Here we use a custom Layout: derives baseline from actual measured font height, elevating precisely with measured px.
 */
@Composable
private fun SupView(node: Nat.Sup, style: NatStyle, signal: Int = -1) {
    val density = LocalDensity.current
    val expStyle = style.copy(size = style.size * 0.62f)
    val raisePx = style.size.value * 0.42f * density.density * density.fontScale

    Layout(
        content = {
            Box { NatNodeView(node.base, style, signal) }
            Box { NatNodeView(supExpNode(node.exp), expStyle, signal) }
        },
    ) { measurables, constraints ->
        val relaxed = constraints.copy(minWidth = 0, minHeight = 0)
        val base = measurables[0].measure(relaxed)
        val exp = measurables[1].measure(relaxed)
        // Inline baseline is roughly at 80% of line height (Compose Text default line height ≈ 1.2 em)
        val baseBaseline = base.height * 0.80f
        val expBaseline = exp.height * 0.80f
        // Elevate exponent by 0.42em based on baseline, allowing it to protrude above node top (negative y) —
        // this avoids pushing base downward (e.g. 9³ in denominator won't sink) while exponent remains firmly at top-right.
        // Parent layout does not clip, so negative y renders cleanly.
        val expTop = baseBaseline - raisePx - expBaseline
        val w = base.width + exp.width
        val h = maxOf(base.height, (expTop + exp.height).toInt())
        layout(w, h) {
            base.place(x = 0, y = 0)
            exp.place(x = base.width, y = expTop.toInt())
        }
    }
}

/**
 * Omits brackets when exponent slot wraps only a single fraction: the fraction bar itself groups,
 * displaying stacked fraction like x^(2/3) without redundant enclosing brackets.
 */
private fun supExpNode(n: Nat): Nat {
    if (n !is Nat.Row) return n
    val items = n.items
    if (items.size < 3) return n
    val first = items.first()
    val last = items.last()
    if (!(first is Nat.Sym && first.text == "(" && last is Nat.Sym && last.text == ")")) return n
    val inner = items.subList(1, items.size - 1)
    val atoms = inner.filter { it !is Nat.Cursor }
    // Omits brackets when wrapping only a single atom (number/variable/fraction): x^(2) still displays as x²
    val singleAtom = atoms.size == 1 && (atoms[0] is Nat.Sym || atoms[0] is Nat.Frac)
    if (!singleAtom) return n
    val out = Nat.Row(inner)
    out.srcStart = inner.first().srcStart
    out.srcEnd = inner.last().srcEnd
    return out
}

/**
 * Omits brackets in fraction slots: when numerator/denominator is enclosed in an outer pair of brackets,
 * omit them during rendering. The fraction bar itself groups items, matching real calculator display;
 * brackets are preserved in source for semantic correctness.
 */
private fun slotNode(n: Nat): Nat {
    if (n !is Nat.Row) return n
    val items = n.items
    if (items.size < 3) return n
    val first = items.first()
    val last = items.last()
    if (!(first is Nat.Sym && first.text == "(" && last is Nat.Sym && last.text == ")")) return n
    var depth = 0
    for (i in items.indices) {
        val s = items[i]
        if (s is Nat.Sym && s.text == "(") depth++
        else if (s is Nat.Sym && s.text == ")") {
            depth--
            if (depth == 0 && i != items.lastIndex) return n
        }
    }
    if (depth != 0) return n
    val inner = Nat.Row(items.subList(1, items.size - 1))
    inner.srcStart = items[1].srcStart
    inner.srcEnd = items[items.size - 2].srcEnd
    return inner
}

/**
 * Fraction: Numerator / Fraction bar / Denominator vertically stacked.
 *
 * Width **naturally wraps content** (custom measure takes the larger of numerator and denominator),
 * fraction bar is drawn during draw phase according to **measured `size.width`** —
 * completely avoids intrinsic size measurement and child stretch, preventing measurement collapse in unbounded scrolling containers.
 */
@Composable
private fun FracView(node: Nat.Frac, style: NatStyle, signal: Int = -1) {
    val density = LocalDensity.current
    val linePx = with(density) { RuleThickness.toPx() }
    val linePxInt = with(density) { RuleThickness.roundToPx() }

    // Written during measure, read during draw: shared within same composable scope, measure always precedes draw
    var ruleCenterY = Float.NaN

    Layout(
        modifier = Modifier.drawBehind {
            if (!ruleCenterY.isNaN() && size.width > 0f) {
                drawLine(
                    color = style.color,
                    start = Offset(0f, ruleCenterY),
                    end = Offset(size.width, ruleCenterY),
                    strokeWidth = linePx,
                )
            }
        },
        content = {
            Box(Modifier.padding(horizontal = FracPadH)) { NatNodeView(slotNode(node.n), style, signal) }
            Box(Modifier.padding(horizontal = FracPadH)) { NatNodeView(slotNode(node.d), style, signal) }
        },
    ) { measurables, constraints ->
        val relaxed = constraints.copy(minWidth = 0, minHeight = 0)
        val num = measurables[0].measure(relaxed)
        val den = measurables[1].measure(relaxed)
        val w = maxOf(num.width, den.width)
        ruleCenterY = num.height + linePxInt / 2f
        val h = num.height + linePxInt + den.height
        layout(w, h) {
            num.place(x = (w - num.width) / 2, y = 0)
            den.place(x = (w - den.width) / 2, y = num.height + linePxInt)
        }
    }
}

/**
 * Radical: `√` side-by-side with "overbar vinculum + radicand content".
 * Overbar is also drawn by `drawBehind` using measured width, radicand wraps naturally — avoids intrinsic size and unbounded stretch.
 */
@Composable
private fun SqrtView(node: Nat.Sqrt, style: NatStyle, signal: Int = -1) {
    val density = LocalDensity.current
    val linePx = with(density) { RuleThickness.toPx() }
    val linePxInt = with(density) { RuleThickness.roundToPx() }

    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = "\u221A",
            color = style.color,
            fontFamily = MonoFont,
            fontSize = style.size * 1.45f,
            fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
        )
        Layout(
            modifier = Modifier.drawBehind {
                if (size.width > 0f) {
                    drawLine(
                        color = style.color,
                        start = Offset(0f, linePx / 2f),
                        end = Offset(size.width, linePx / 2f),
                        strokeWidth = linePx,
                    )
                }
            },
            content = { Box(Modifier.padding(horizontal = FracPadH)) { NatNodeView(node.a, style, signal) } },
        ) { measurables, constraints ->
            val inner = measurables[0].measure(constraints.copy(minWidth = 0, minHeight = 0))
            layout(inner.width, inner.height + linePxInt) {
                inner.place(x = 0, y = linePxInt)
            }
        }
    }
}

/**
 * LCD expression / result area (bottom-aligned, horizontally scrolls when overflowing).
 *
 * - Content wraps naturally by width, aligned to right via `Box(contentAlignment = End)` — independent of unbounded end-alignment;
 * - Scrolling container has neither intrinsic measurements nor fill-parent modifiers, so multiple adjacent fractions won't squash into vertical columns and fraction lines won't lose width;
 * - Initially scrolled to rightmost end: short expressions right-align, long expressions show the tail by default and can scroll left without truncation.
 */
@Composable
fun LcdMathLine(
    expr: String,
    style: NatStyle,
    modifier: Modifier = Modifier,
    cursor: Int = -1,
    cursorExit: Int = 0,
    align: Alignment = Alignment.BottomEnd,
    rowAlign: Alignment.Vertical = Alignment.Bottom,
    initialScroll: Int = Int.MAX_VALUE,
) {
    val scroll = rememberScrollState(initial = initialScroll)
    Box(modifier, contentAlignment = align) {
        Row(
            modifier = Modifier.horizontalScroll(scroll),
            verticalAlignment = rowAlign,
        ) {
            NaturalView(expr, style, cursor = cursor, cursorExit = cursorExit)
        }
    }
}

val LcdSmall = 15.sp
val LcdBig = 26.sp
