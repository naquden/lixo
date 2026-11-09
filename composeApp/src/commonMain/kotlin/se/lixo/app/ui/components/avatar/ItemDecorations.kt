package se.lixo.app.ui.components.avatar

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import se.lixo.app.domain.model.ItemOrnament
import se.lixo.app.domain.model.ItemPattern

/**
 * Pattern/ornament drawing helpers shared by the generic layer renderer (020).
 */

internal fun darken(color: Color, f: Float): Color = color.copy(
    red = (color.red * f).coerceIn(0f, 1f),
    green = (color.green * f).coerceIn(0f, 1f),
    blue = (color.blue * f).coerceIn(0f, 1f)
)

internal fun lighten(color: Color, f: Float): Color = color.copy(
    red = (color.red + (1f - color.red) * f).coerceIn(0f, 1f),
    green = (color.green + (1f - color.green) * f).coerceIn(0f, 1f),
    blue = (color.blue + (1f - color.blue) * f).coerceIn(0f, 1f)
)

/** Fills [path], clipped to its own outline, with a repeating [pattern] tinted by [secondary]. */
internal fun DrawScope.drawPatternFill(path: Path, bounds: Rect, pattern: ItemPattern, secondary: Color) {
    if (pattern == ItemPattern.NONE || bounds.width <= 0f || bounds.height <= 0f) return
    clipPath(path) {
        when (pattern) {
            ItemPattern.STRIPES -> {
                val stripeW = (bounds.width / 4.5f).coerceAtLeast(2f)
                var x = bounds.left - bounds.height
                while (x < bounds.right) {
                    drawRect(
                        color = secondary.copy(alpha = 0.4f),
                        topLeft = Offset(x, bounds.top),
                        size = Size(stripeW * 0.55f, bounds.height)
                    )
                    x += stripeW
                }
            }
            ItemPattern.DOTS -> {
                val step = (min(bounds.width, bounds.height) / 3f).coerceAtLeast(3f)
                var yy = bounds.top + step / 2f
                while (yy < bounds.bottom) {
                    var xx = bounds.left + step / 2f
                    while (xx < bounds.right) {
                        drawCircle(secondary.copy(alpha = 0.5f), radius = step * 0.22f, center = Offset(xx, yy))
                        xx += step
                    }
                    yy += step
                }
            }
            ItemPattern.STARS -> {
                val step = (min(bounds.width, bounds.height) / 3f).coerceAtLeast(4f)
                var yy = bounds.top + step / 2f
                var row = 0
                while (yy < bounds.bottom) {
                    var xx = bounds.left + step / 2f + if (row % 2 == 1) step / 2f else 0f
                    while (xx < bounds.right) {
                        drawStar(Offset(xx, yy), step * 0.28f, secondary.copy(alpha = 0.55f))
                        xx += step
                    }
                    yy += step
                    row++
                }
            }
            ItemPattern.ZIGZAG -> {
                val amplitude = bounds.height * 0.12f
                val waveLen = (bounds.width / 5f).coerceAtLeast(4f)
                var y = bounds.top + amplitude
                while (y < bounds.bottom) {
                    val zigPath = Path()
                    var x = bounds.left
                    zigPath.moveTo(x, y)
                    var up = true
                    while (x < bounds.right) {
                        x += waveLen / 2f
                        zigPath.lineTo(x, y + if (up) -amplitude else amplitude)
                        up = !up
                    }
                    drawPath(
                        zigPath,
                        color = secondary.copy(alpha = 0.5f),
                        style = Stroke(width = bounds.height * 0.06f)
                    )
                    y += amplitude * 3f
                }
            }
            ItemPattern.CHECKER -> {
                val step = (min(bounds.width, bounds.height) / 4f).coerceAtLeast(3f)
                var row = 0
                var yy = bounds.top
                while (yy < bounds.bottom) {
                    var col = 0
                    var xx = bounds.left
                    while (xx < bounds.right) {
                        if ((row + col) % 2 == 0) {
                            drawRect(
                                color = secondary.copy(alpha = 0.4f),
                                topLeft = Offset(xx, yy),
                                size = Size(step, step)
                            )
                        }
                        xx += step
                        col++
                    }
                    yy += step
                    row++
                }
            }
            ItemPattern.NONE -> Unit
        }
    }
}

internal fun DrawScope.drawStar(center: Offset, radius: Float, color: Color) {
    val path = Path()
    val points = 4
    for (i in 0 until points * 2) {
        val r = if (i % 2 == 0) radius else radius * 0.4f
        val angle = (PI * i / points).toFloat() - (PI / 2f).toFloat()
        val px = center.x + cos(angle) * r
        val py = center.y + sin(angle) * r
        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    path.close()
    drawPath(path, color = color)
}

/** Soft halo behind an item, used for [ItemOrnament.GLOW]. Draw this before the item's shape. */
internal fun DrawScope.drawGlowHalo(center: Offset, radius: Float, color: Color) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = 0.55f), color.copy(alpha = 0.0f)),
            center = center,
            radius = radius
        ),
        radius = radius,
        center = center
    )
}

/** The [ItemOrnament.GEM] decoration: a small secondary-coloured gem with a white highlight. */
internal fun DrawScope.drawGem(bounds: Rect, color: Color, scale: Float) {
    val gemCenter = Offset(bounds.center.x, bounds.top + bounds.height * 0.3f)
    val gemRadius = 4f * scale
    drawCircle(color = color, radius = gemRadius, center = gemCenter)
    drawCircle(
        color = Color.White.copy(alpha = 0.75f),
        radius = gemRadius * 0.35f,
        center = gemCenter - Offset(gemRadius * 0.3f, gemRadius * 0.3f)
    )
}

/** The [ItemOrnament.SPARKLE] decoration: 4 twinkling dots orbiting [bounds]'s centre. */
internal fun DrawScope.drawSparkle(bounds: Rect, color: Color, scale: Float, auraPhase: Float) {
    for (i in 0 until 4) {
        val angle = (auraPhase * 2 * PI + i * (PI / 2)).toFloat()
        val px = bounds.center.x + cos(angle) * bounds.width * 0.5f
        val py = bounds.center.y + sin(angle) * bounds.height * 0.5f
        val twinkle = (sin(auraPhase * 2f * PI.toFloat() * 3f + i) * 0.5f + 0.5f)
        drawCircle(
            color = color.copy(alpha = 0.35f + 0.55f * twinkle),
            radius = (1.2f + 1.6f * twinkle) * scale,
            center = Offset(px, py)
        )
    }
}

/** Trim/gem/sparkle decoration drawn on top of an item's shape, scaling with rarity. */
internal fun DrawScope.drawOrnamentOverlay(
    path: Path,
    bounds: Rect,
    ornament: ItemOrnament,
    secondary: Color,
    scale: Float,
    auraPhase: Float
) {
    when (ornament) {
        ItemOrnament.NONE -> Unit
        ItemOrnament.TRIM -> drawPath(path, color = secondary, style = Stroke(width = 2.5f * scale))
        ItemOrnament.GEM -> drawGem(bounds, secondary, scale)
        ItemOrnament.GLOW -> drawGlowHalo(bounds.center, bounds.maxDimension * 0.7f, secondary)
        ItemOrnament.SPARKLE -> drawSparkle(bounds, secondary, scale, auraPhase)
    }
}
