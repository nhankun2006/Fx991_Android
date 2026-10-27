package io.paimon.fx991.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text

/**
 * All icons are drawn with vector code (Canvas / Path), without referencing any external image, icon, or font resources.
 */
@Composable
fun KeyGlyph(icon: KeyIcon, tint: Color, size: Dp = 18.dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        when (icon) {
            KeyIcon.NONE -> Unit
            KeyIcon.MENU -> Canvas(Modifier.size(size)) {
                val w = this.size.width
                val h = this.size.height
                val sw = h * 0.11f
                for (k in -1..1) {
                    val y = h / 2 + k * h * 0.26f
                    drawLine(tint, Offset(w * 0.16f, y), Offset(w * 0.84f, y), strokeWidth = sw)
                }
            }
            KeyIcon.PRO -> Canvas(Modifier.size(size)) {
                val w = this.size.width
                val h = this.size.height
                val p = Path().apply {
                    moveTo(w * 0.5f, h * 0.14f)
                    lineTo(w * 0.86f, h * 0.5f)
                    lineTo(w * 0.5f, h * 0.86f)
                    lineTo(w * 0.14f, h * 0.5f)
                    close()
                }
                drawPath(p, tint, style = Stroke(width = h * 0.1f))
                drawCircle(tint, radius = h * 0.1f, center = Offset(w * 0.5f, h * 0.5f))
            }
            KeyIcon.SIGMA -> Text(
                "\u03A3", color = tint, fontSize = (size.value * 0.95f).sp,
                fontWeight = FontWeight.Bold
            )
            KeyIcon.GEAR -> Canvas(Modifier.size(size)) {
                val w = this.size.width
                val h = this.size.height
                val c = Offset(w / 2, h / 2)
                val rOuter = h * 0.42f
                val rInner = h * 0.20f
                val teeth = 8
                val p = Path()
                for (i in 0 until teeth * 2) {
                    val a = (Math.PI * i / teeth).toFloat() - 0.2f
                    val rr = if (i % 2 == 0) rOuter else rOuter * 0.72f
                    val x = c.x + rr * kotlin.math.cos(a)
                    val y = c.y + rr * kotlin.math.sin(a)
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close()
                drawPath(p, tint)
                drawCircle(Color.Transparent, radius = rInner, center = c)
                drawCircle(tint, radius = rInner, center = c, style = Stroke(width = h * 0.09f))
            }
            KeyIcon.PLUSMINUS -> Text(
                "\u00B1", color = tint, fontSize = (size.value * 1.05f).sp,
                fontWeight = FontWeight.Bold
            )
            KeyIcon.CAMERA -> Canvas(Modifier.size(size)) {
                val w = this.size.width
                val h = this.size.height
                val sw = h * 0.09f
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.1f, h * 0.28f),
                    size = Size(w * 0.8f, h * 0.56f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(h * 0.12f),
                    style = Stroke(width = sw)
                )
                drawCircle(tint, radius = h * 0.15f, center = Offset(w * 0.5f, h * 0.57f),
                    style = Stroke(width = sw))
                drawLine(tint, Offset(w * 0.34f, h * 0.28f), Offset(w * 0.42f, h * 0.16f), sw)
                drawLine(tint, Offset(w * 0.42f, h * 0.16f), Offset(w * 0.58f, h * 0.16f), sw)
                drawLine(tint, Offset(w * 0.58f, h * 0.16f), Offset(w * 0.66f, h * 0.28f), sw)
            }
            KeyIcon.GALLERY -> Canvas(Modifier.size(size)) {
                // Gallery: frame + mountain + sun (all hand-drawn, no external image resources)
                val w = this.size.width
                val h = this.size.height
                val sw = h * 0.09f
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.12f, h * 0.18f),
                    size = Size(w * 0.76f, h * 0.64f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(h * 0.1f),
                    style = Stroke(width = sw)
                )
                // Sun
                drawCircle(tint, radius = h * 0.08f, center = Offset(w * 0.34f, h * 0.36f))
                // Mountain (polyline)
                val p = Path().apply {
                    moveTo(w * 0.16f, h * 0.78f)
                    lineTo(w * 0.38f, h * 0.52f)
                    lineTo(w * 0.54f, h * 0.68f)
                    lineTo(w * 0.66f, h * 0.56f)
                    lineTo(w * 0.84f, h * 0.78f)
                }
                drawPath(p, tint, style = Stroke(width = sw))
            }
            KeyIcon.BACKSPACE -> Canvas(Modifier.size(size)) {
                val w = this.size.width
                val h = this.size.height
                val sw = h * 0.1f
                val p = Path().apply {
                    moveTo(w * 0.42f, h * 0.2f)
                    lineTo(w * 0.92f, h * 0.2f)
                    lineTo(w * 0.92f, h * 0.8f)
                    lineTo(w * 0.42f, h * 0.8f)
                    lineTo(w * 0.08f, h * 0.5f)
                    close()
                }
                drawPath(p, tint, style = Stroke(width = sw))
                drawLine(tint, Offset(w * 0.52f, h * 0.38f), Offset(w * 0.78f, h * 0.62f), sw)
                drawLine(tint, Offset(w * 0.78f, h * 0.38f), Offset(w * 0.52f, h * 0.62f), sw)
            }
        }
    }
}
