package com.narcictub.app.ui.theme

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * HONEY toolkit — the recurring beehive motifs of the design language:
 * the hexagon shape, the honeycomb texture, the hand-drawn bee, the hive
 * folder tab, the hexagon progress gauge and the striped honey bar.
 */

/** Hexagon shape; pointy = apex top/bottom (the primary motif), flat = apex left/right. */
fun hexagonShape(pointy: Boolean = true) = GenericShape { size, _ ->
    if (pointy) {
        moveTo(size.width * 0.5f, 0f); lineTo(size.width, size.height * 0.25f)
        lineTo(size.width, size.height * 0.75f); lineTo(size.width * 0.5f, size.height)
        lineTo(0f, size.height * 0.75f); lineTo(0f, size.height * 0.25f)
    } else {
        moveTo(size.width * 0.25f, 0f); lineTo(size.width * 0.75f, 0f)
        lineTo(size.width, size.height * 0.5f); lineTo(size.width * 0.75f, size.height)
        lineTo(size.width * 0.25f, size.height); lineTo(0f, size.height * 0.5f)
    }
    close()
}

val HexPointyShape = hexagonShape(true)

/** Reusable hexagon container with optional gradient/solid fill and content. */
@Composable
fun Hexagon(
    size: Dp,
    modifier: Modifier = Modifier,
    pointy: Boolean = true,
    fill: Brush? = null,
    color: Color = Color.Transparent,
    content: @Composable BoxScope.() -> Unit = {},
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(hexagonShape(pointy))
            .then(if (fill != null) Modifier.background(fill) else Modifier.background(color)),
        content = content,
    )
}

/** Subtle tileable honeycomb texture drawn procedurally (no assets). */
fun Modifier.honeycomb(color: Color, alpha: Float = 0.10f, tile: Dp = 64.dp): Modifier =
    drawBehind {
        val w = ceil(tile.value * density).toInt().coerceAtLeast(8)
        val h = ceil(w * 1.732f).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = density * 1.1f
            this.color = android.graphics.Color.argb(
                (alpha * 255).roundToInt(),
                (color.red * 255).roundToInt(),
                (color.green * 255).roundToInt(),
                (color.blue * 255).roundToInt(),
            )
        }
        fun hexPath(cx: Float, cy: Float, r: Float): android.graphics.Path {
            val path = android.graphics.Path()
            for (i in 0 until 6) {
                val a = Math.toRadians(60.0 * i - 30.0)
                val x = (cx + r * cos(a)).toFloat()
                val y = (cy + r * sin(a)).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            return path
        }
        val r = w / 2f
        val row = r * 1.5f
        listOf(
            0f to 0f, w.toFloat() to 0f,
            w / 2f to row,
            0f to row * 2f, w.toFloat() to row * 2f,
        ).forEach { (cx, cy) -> c.drawPath(hexPath(cx, cy, r), p) }
        val shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        drawContext.canvas.nativeCanvas.drawPaint(Paint().apply { this.shader = shader })
    }

/**
 * The hand-drawn bee: honey body with black stripes, translucent flapping
 * wings, antenna and stinger — fully parametric on Canvas, no assets.
 * @param flapAngle wing deflection from an infinite transition (degrees)
 * @param mirrored flips the body (flight direction)
 */
@Composable
fun BeeIcon(
    modifier: Modifier = Modifier,
    flapAngle: Float = 0f,
    mirrored: Boolean = false,
    bodyColor: Color = Color(0xFFFFD25E),
    stripeColor: Color = Color(0xFF261D02),
) {
    Canvas(modifier = modifier.graphicsLayer { if (mirrored) scaleX = -1f }) {
        val w = size.width
        val h = size.height
        val bodyTop = h * 0.42f
        val bodyH = h * 0.40f
        val bodyLeft = w * 0.12f
        val bodyRight = w * 0.82f

        fun wing(cx: Float, cy: Float, rw: Float, rh: Float, angle: Float) {
            rotate(angle) {
                drawOval(Color.White.copy(alpha = 0.55f), Offset(cx - rw, cy - rh), Size(rw * 2f, rh * 2f))
                drawOval(
                    stripeColor.copy(alpha = 0.30f), Offset(cx - rw, cy - rh), Size(rw * 2f, rh * 2f),
                    style = Stroke(width = w * 0.02f),
                )
            }
        }
        wing(w * 0.40f, bodyTop - h * 0.10f, w * 0.24f, h * 0.13f, -24f + flapAngle * 0.6f)
        wing(w * 0.56f, bodyTop - h * 0.08f, w * 0.20f, h * 0.11f, 6f - flapAngle * 0.6f)

        drawLine(stripeColor, Offset(w * 0.72f, bodyTop + h * 0.02f), Offset(w * 0.86f, bodyTop - h * 0.14f), w * 0.035f, StrokeCap.Round)
        drawCircle(stripeColor, w * 0.035f, Offset(w * 0.87f, bodyTop - h * 0.15f))

        drawPath(
            Path().apply {
                moveTo(bodyLeft + w * 0.02f, bodyTop + bodyH * 0.35f)
                lineTo(bodyLeft - w * 0.07f, bodyTop + bodyH * 0.52f)
                lineTo(bodyLeft + w * 0.02f, bodyTop + bodyH * 0.68f)
                close()
            },
            stripeColor,
        )

        drawRoundRect(bodyColor, Offset(bodyLeft, bodyTop), Size(bodyRight - bodyLeft, bodyH), CornerRadius(bodyH * 0.55f))
        drawRoundRect(
            stripeColor.copy(alpha = 0.9f), Offset(bodyLeft, bodyTop), Size(bodyRight - bodyLeft, bodyH),
            CornerRadius(bodyH * 0.55f), style = Stroke(width = w * 0.035f),
        )
        // stripes clipped inside the body
        clipRect(bodyLeft, bodyTop, bodyRight, bodyTop + bodyH) {
            val sw = w * 0.10f
            drawRoundRect(stripeColor, Offset(bodyLeft + (bodyRight - bodyLeft) * 0.30f, bodyTop - 2f), Size(sw, bodyH + 4f), CornerRadius(sw / 2f))
            drawRoundRect(stripeColor, Offset(bodyLeft + (bodyRight - bodyLeft) * 0.58f, bodyTop - 2f), Size(sw, bodyH + 4f), CornerRadius(sw / 2f))
        }

        drawCircle(stripeColor, bodyH * 0.34f, Offset(bodyRight, bodyTop + bodyH * 0.5f))
        drawCircle(Color.White, bodyH * 0.10f, Offset(bodyRight + bodyH * 0.08f, bodyTop + bodyH * 0.42f))

        drawLine(stripeColor, Offset(w * 0.36f, bodyTop + bodyH), Offset(w * 0.33f, h * 0.95f), w * 0.03f, StrokeCap.Round)
        drawLine(stripeColor, Offset(w * 0.55f, bodyTop + bodyH), Offset(w * 0.55f, h * 0.97f), w * 0.03f, StrokeCap.Round)
    }
}

/** Slanted-edge tab for the hive folder (honey gradient behind). */
val HiveTabShape = GenericShape { size, _ ->
    moveTo(0f, 0f)
    lineTo(size.width, 0f)
    lineTo(size.width - size.height * 0.55f, size.height)
    lineTo(0f, size.height)
    close()
}

/**
 * The signature completion moment: a bee flies across the whole screen
 * left → right (~1.9s), bobbing ±8dp on an inner wrapper, wings flapping
 * at ~0.12s, body mirrored for the flight direction. Re-fires whenever
 * [flightKey] increments.
 */
@Composable
fun BeeFlightOverlay(flightKey: Int, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    val flight = remember { Animatable(0f) }
    LaunchedEffect(flightKey) {
        if (flightKey > 0) {
            visible = true
            flight.snapTo(0f)
            flight.animateTo(1f, tween(1900, easing = LinearEasing))
            visible = false
        }
    }
    if (!visible) return
    val bob = rememberInfiniteTransition(label = "beeBob").animateFloat(
        -8f, 8f, infiniteRepeatable(tween(260), RepeatMode.Reverse), label = "y",
    )
    val flap = rememberInfiniteTransition(label = "beeWing").animateFloat(
        -20f, 20f, infiniteRepeatable(tween(120), RepeatMode.Reverse), label = "f",
    )
    BoxWithConstraints(modifier.fillMaxSize()) {
        val travel = maxWidth + 140.dp
        BeeIcon(
            modifier = Modifier
                .offset(x = (-120).dp + travel * flight.value, y = 120.dp + bob.value.dp)
                .size(64.dp),
            flapAngle = flap.value,
            mirrored = true,
        )
    }
}

/**
 * Hexagon progress gauge: bottom-anchored honey fill clipped inside the
 * hex, centered percent label (ink once the fill passes behind it).
 */
@Composable
fun HexGauge(
    progress: Float?,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val p = progress?.coerceIn(0f, 1f)
    Box(
        modifier
            .size(44.dp, 50.dp)
            .clip(hexagonShape(true))
            .background(colors.surfaceContainerHighest),
    ) {
        if (p != null) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height((50f * p).dp)
                    .background(HoneyGradient),
            )
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = if (p != null) "${(p * 100).roundToInt()}%" else "…",
                style = MaterialTheme.typography.labelMedium,
                color = if (p != null && p >= 0.45f) InkOnHoney else colors.onSurfaceVariant,
            )
        }
    }
}

/**
 * Striped honey progress bar (8dp): running = sliding diagonal candy
 * stripes over the honey fill with a droplet on the leading edge;
 * paused = flat warning amber; unknown total = indeterminate stripes.
 */
@Composable
fun StripedHoneyBar(
    fraction: Float?,
    modifier: Modifier = Modifier,
    running: Boolean = true,
    paused: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val phase = rememberInfiniteTransition(label = "stripe").animateFloat(
        0f, 28f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "p",
    )
    Canvas(
        modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(colors.surfaceContainerHighest),
    ) {
        val track = size.width
        val w = when {
            fraction != null -> track * fraction.coerceIn(0f, 1f)
            running -> track // unknown total: full-width indeterminate stripes
            else -> 0f
        }
        val fill = when {
            paused -> Brush.horizontalGradient(listOf(colors.tertiary, colors.tertiary))
            fraction == null -> Brush.horizontalGradient(listOf(Color(0x66FFD25E), Color(0x66EFA100)))
            else -> HoneyGradient
        }
        drawRoundRect(fill, Offset.Zero, Size(w, size.height), CornerRadius(4.dp.toPx()))
        if (running && w > 2f) {
            clipRect(0f, 0f, w, size.height) {
                rotate(-55f, pivot = Offset(w / 2f, size.height / 2f)) {
                    val step = 12.dp.toPx()
                    var x = -size.height + (phase.value % step)
                    while (x < w + size.height) {
                        drawLine(
                            Color.White.copy(alpha = 0.35f),
                            Offset(x, -size.height), Offset(x, size.height * 2f),
                            4.dp.toPx(), StrokeCap.Butt,
                        )
                        x += step
                    }
                }
            }
            if (fraction != null) {
                // honey droplet on the leading edge
                drawCircle(HoneyGradient, size.height * 0.95f, Offset(w, size.height / 2f))
                drawCircle(Color.White.copy(alpha = 0.8f), size.height * 0.28f, Offset(w - size.height * 0.25f, size.height * 0.3f))
            }
        }
    }
}
