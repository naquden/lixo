package se.lixo.app.domain.content

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.PathParser
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Path-data helpers shared by [ContentPackValidator] and the layer renderer (020). Everything
 * here works on plain [PathNode] lists, never on `androidx.compose.ui.graphics.Path`: on the
 * host unit-test JVM, `Path()` is backed by `AndroidPath`, which throws "Method ... not mocked"
 * against the android.jar stubs the host tests run against. Only
 * [ActiveContent.parsedPath] (never called from `commonTest`) creates a real `Path`.
 */
object ContentPaths {

    /** Maximum length of a single layer's `d` string. */
    const val MAX_PATH_CHARS = 4096

    /** Characters a well-formed path may contain, before even tokenizing it. */
    private val ALLOWED_CHARS = Regex("^[MmLlHhVvCcSsQqTtAaZz0-9.+\\-eE\\s,]*$")

    /** A single path command letter, or a single (possibly signed, possibly exponential) number. */
    private val TOKEN_REGEX =
        Regex("[MmLlHhVvCcSsQqTtAaZz]|[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?")

    /** Text allowed between tokens: whitespace and commas only. */
    private val SEPARATOR_REGEX = Regex("^[\\s,]*$")

    /**
     * Strictly validates SVG-style path data, returning a human-readable reason it is invalid,
     * or null if it is OK. Compose's [PathParser] is lenient: a command with too few arguments
     * (e.g. `"M 0 0 L"`) silently parses to whatever came before it, instead of failing, so this
     * must run BEFORE handing [d] to [PathParser].
     */
    fun strictError(d: String): String? {
        if (d.isBlank()) return "path data is blank"
        if (d.length > MAX_PATH_CHARS) return "path data exceeds $MAX_PATH_CHARS characters"
        if (!ALLOWED_CHARS.matches(d)) return "path data contains an illegal character"

        val tokens = ArrayList<String>()
        var lastEnd = 0
        for (match in TOKEN_REGEX.findAll(d)) {
            val between = d.substring(lastEnd, match.range.first)
            if (!SEPARATOR_REGEX.matches(between)) return "path data has an illegal separator"
            tokens.add(match.value)
            lastEnd = match.range.last + 1
        }
        if (!SEPARATOR_REGEX.matches(d.substring(lastEnd))) return "path data has an illegal separator"
        if (tokens.isEmpty()) return "path data has no commands"
        if (tokens[0] != "M" && tokens[0] != "m") return "path data must start with M or m"

        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            val cmd = token[0]
            if (!cmd.isLetter()) return "expected a command, found a number"
            i++
            val arity = when (cmd.uppercaseChar()) {
                'M', 'L', 'T' -> 2
                'H', 'V' -> 1
                'C' -> 6
                'S', 'Q' -> 4
                'A' -> 7
                'Z' -> 0
                else -> return "unknown command '$cmd'"
            }
            if (arity == 0) continue
            val start = i
            while (i < tokens.size && !tokens[i][0].isLetter()) i++
            val count = i - start
            if (count == 0 || count % arity != 0) return "wrong argument count for '$cmd'"
            var group = start
            while (group < start + count) {
                for (k in 0 until arity) {
                    val numberToken = tokens[group + k]
                    val value = numberToken.toFloatOrNull() ?: return "invalid number '$numberToken'"
                    if (!value.isFinite()) return "non-finite number in path data"
                    if (cmd.uppercaseChar() == 'A' && (k == 3 || k == 4) && value != 0f && value != 1f) {
                        return "arc flag must be 0 or 1"
                    }
                }
                group += arity
            }
        }
        return null
    }

    /**
     * Parses [d] into [PathNode]s. Callers MUST run [strictError] first: [PathParser] silently
     * accepts malformed data that [strictError] rejects. Uses a fresh [PathParser] every call,
     * since it is not thread-safe / reusable across different path strings.
     */
    fun parseNodes(d: String): List<PathNode> = PathParser().parsePathString(d).toNodes()

    /**
     * A conservative bounding box for [nodes], in the same local coordinate space the path data
     * is written in. "Conservative" means every endpoint AND every Bezier control point is
     * included (the curve itself always lies within the convex hull of its control polygon, so
     * this is always a superset of the true bounds), and an elliptical arc's FULL rotated
     * ellipse extent is included rather than just the arc's drawn portion.
     *
     * Returns null if [nodes] contains no drawing command (only [PathNode.MoveTo] /
     * [PathNode.RelativeMoveTo]).
     */
    fun bounds(nodes: List<PathNode>): Rect? {
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var any = false

        fun include(x: Float, y: Float) {
            any = true
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
        }

        var cur = Offset.Zero
        var subpathStart = Offset.Zero
        var lastCtrl = Offset.Zero
        // 'C' = inside a cubic family (so a following S/s may reflect lastCtrl), 'Q' = quad
        // family (so a following T/t may reflect lastCtrl), null = neither.
        var lastFamily: Char? = null

        for (node in nodes) {
            when (node) {
                is PathNode.MoveTo -> {
                    cur = Offset(node.x, node.y)
                    subpathStart = cur
                    lastFamily = null
                }
                is PathNode.RelativeMoveTo -> {
                    cur = cur + Offset(node.dx, node.dy)
                    subpathStart = cur
                    lastFamily = null
                }
                is PathNode.Close -> {
                    include(cur.x, cur.y)
                    include(subpathStart.x, subpathStart.y)
                    cur = subpathStart
                    lastFamily = null
                }
                is PathNode.LineTo -> {
                    include(cur.x, cur.y)
                    cur = Offset(node.x, node.y)
                    include(cur.x, cur.y)
                    lastFamily = null
                }
                is PathNode.RelativeLineTo -> {
                    include(cur.x, cur.y)
                    cur = cur + Offset(node.dx, node.dy)
                    include(cur.x, cur.y)
                    lastFamily = null
                }
                is PathNode.HorizontalTo -> {
                    include(cur.x, cur.y)
                    cur = Offset(node.x, cur.y)
                    include(cur.x, cur.y)
                    lastFamily = null
                }
                is PathNode.RelativeHorizontalTo -> {
                    include(cur.x, cur.y)
                    cur = Offset(cur.x + node.dx, cur.y)
                    include(cur.x, cur.y)
                    lastFamily = null
                }
                is PathNode.VerticalTo -> {
                    include(cur.x, cur.y)
                    cur = Offset(cur.x, node.y)
                    include(cur.x, cur.y)
                    lastFamily = null
                }
                is PathNode.RelativeVerticalTo -> {
                    include(cur.x, cur.y)
                    cur = Offset(cur.x, cur.y + node.dy)
                    include(cur.x, cur.y)
                    lastFamily = null
                }
                is PathNode.CurveTo -> {
                    include(cur.x, cur.y)
                    val ctrl1 = Offset(node.x1, node.y1)
                    val ctrl2 = Offset(node.x2, node.y2)
                    val end = Offset(node.x3, node.y3)
                    include(ctrl1.x, ctrl1.y)
                    include(ctrl2.x, ctrl2.y)
                    include(end.x, end.y)
                    cur = end
                    lastCtrl = ctrl2
                    lastFamily = 'C'
                }
                is PathNode.RelativeCurveTo -> {
                    include(cur.x, cur.y)
                    val ctrl1 = cur + Offset(node.dx1, node.dy1)
                    val ctrl2 = cur + Offset(node.dx2, node.dy2)
                    val end = cur + Offset(node.dx3, node.dy3)
                    include(ctrl1.x, ctrl1.y)
                    include(ctrl2.x, ctrl2.y)
                    include(end.x, end.y)
                    cur = end
                    lastCtrl = ctrl2
                    lastFamily = 'C'
                }
                is PathNode.ReflectiveCurveTo -> {
                    include(cur.x, cur.y)
                    val ctrl1 = if (lastFamily == 'C') cur * 2f - lastCtrl else cur
                    val ctrl2 = Offset(node.x1, node.y1)
                    val end = Offset(node.x2, node.y2)
                    include(ctrl1.x, ctrl1.y)
                    include(ctrl2.x, ctrl2.y)
                    include(end.x, end.y)
                    cur = end
                    lastCtrl = ctrl2
                    lastFamily = 'C'
                }
                is PathNode.RelativeReflectiveCurveTo -> {
                    include(cur.x, cur.y)
                    val ctrl1 = if (lastFamily == 'C') cur * 2f - lastCtrl else cur
                    val ctrl2 = cur + Offset(node.dx1, node.dy1)
                    val end = cur + Offset(node.dx2, node.dy2)
                    include(ctrl1.x, ctrl1.y)
                    include(ctrl2.x, ctrl2.y)
                    include(end.x, end.y)
                    cur = end
                    lastCtrl = ctrl2
                    lastFamily = 'C'
                }
                is PathNode.QuadTo -> {
                    include(cur.x, cur.y)
                    val ctrl = Offset(node.x1, node.y1)
                    val end = Offset(node.x2, node.y2)
                    include(ctrl.x, ctrl.y)
                    include(end.x, end.y)
                    cur = end
                    lastCtrl = ctrl
                    lastFamily = 'Q'
                }
                is PathNode.RelativeQuadTo -> {
                    include(cur.x, cur.y)
                    val ctrl = cur + Offset(node.dx1, node.dy1)
                    val end = cur + Offset(node.dx2, node.dy2)
                    include(ctrl.x, ctrl.y)
                    include(end.x, end.y)
                    cur = end
                    lastCtrl = ctrl
                    lastFamily = 'Q'
                }
                is PathNode.ReflectiveQuadTo -> {
                    include(cur.x, cur.y)
                    val ctrl = if (lastFamily == 'Q') cur * 2f - lastCtrl else cur
                    val end = Offset(node.x, node.y)
                    include(ctrl.x, ctrl.y)
                    include(end.x, end.y)
                    cur = end
                    lastCtrl = ctrl
                    lastFamily = 'Q'
                }
                is PathNode.RelativeReflectiveQuadTo -> {
                    include(cur.x, cur.y)
                    val ctrl = if (lastFamily == 'Q') cur * 2f - lastCtrl else cur
                    val end = cur + Offset(node.dx, node.dy)
                    include(ctrl.x, ctrl.y)
                    include(end.x, end.y)
                    cur = end
                    lastCtrl = ctrl
                    lastFamily = 'Q'
                }
                is PathNode.ArcTo -> {
                    include(cur.x, cur.y)
                    val end = Offset(node.arcStartX, node.arcStartY)
                    includeArc(
                        start = cur,
                        end = end,
                        rx = node.horizontalEllipseRadius,
                        ry = node.verticalEllipseRadius,
                        thetaDegrees = node.theta,
                        largeArc = node.isMoreThanHalf,
                        sweep = node.isPositiveArc,
                        include = { x, y -> include(x, y) }
                    )
                    cur = end
                    lastFamily = null
                }
                is PathNode.RelativeArcTo -> {
                    include(cur.x, cur.y)
                    val end = cur + Offset(node.arcStartDx, node.arcStartDy)
                    includeArc(
                        start = cur,
                        end = end,
                        rx = node.horizontalEllipseRadius,
                        ry = node.verticalEllipseRadius,
                        thetaDegrees = node.theta,
                        largeArc = node.isMoreThanHalf,
                        sweep = node.isPositiveArc,
                        include = { x, y -> include(x, y) }
                    )
                    cur = end
                    lastFamily = null
                }
            }
        }
        return if (any) Rect(minX, minY, maxX, maxY) else null
    }

    /**
     * Includes an elliptical arc's bounding contribution: both endpoints (already included by
     * the caller for [start], but re-included here for safety) plus the full rotated ellipse's
     * extent, per the SVG implementation notes (F.6.5 "Conversion from endpoint to center
     * parameterization"). This over-approximates the true arc bounds (it includes the whole
     * ellipse, not just the drawn portion), which is fine for a conservative bone-box check.
     */
    private fun includeArc(
        start: Offset,
        end: Offset,
        rx: Float,
        ry: Float,
        thetaDegrees: Float,
        largeArc: Boolean,
        sweep: Boolean,
        include: (Float, Float) -> Unit
    ) {
        include(start.x, start.y)
        include(end.x, end.y)
        if (rx == 0f || ry == 0f || start == end) return

        var radiusX = kotlin.math.abs(rx)
        var radiusY = kotlin.math.abs(ry)
        val phi = thetaDegrees * PI.toFloat() / 180f
        val cosPhi = cos(phi)
        val sinPhi = sin(phi)

        val dx2 = (start.x - end.x) / 2f
        val dy2 = (start.y - end.y) / 2f
        val x1p = cosPhi * dx2 + sinPhi * dy2
        val y1p = -sinPhi * dx2 + cosPhi * dy2

        val lambda = (x1p * x1p) / (radiusX * radiusX) + (y1p * y1p) / (radiusY * radiusY)
        if (lambda > 1f) {
            val scale = sqrt(lambda)
            radiusX *= scale
            radiusY *= scale
        }

        val sign = if (largeArc == sweep) -1f else 1f
        val numerator =
            radiusX * radiusX * radiusY * radiusY - radiusX * radiusX * y1p * y1p - radiusY * radiusY * x1p * x1p
        val denom = radiusX * radiusX * y1p * y1p + radiusY * radiusY * x1p * x1p
        val coef = if (denom == 0f) 0f else sign * sqrt(max(0f, numerator / denom))

        val cxp = coef * radiusX * y1p / radiusY
        val cyp = -coef * radiusY * x1p / radiusX

        val cx = cosPhi * cxp - sinPhi * cyp + (start.x + end.x) / 2f
        val cy = sinPhi * cxp + cosPhi * cyp + (start.y + end.y) / 2f

        val extentX = sqrt(radiusX * radiusX * cosPhi * cosPhi + radiusY * radiusY * sinPhi * sinPhi)
        val extentY = sqrt(radiusX * radiusX * sinPhi * sinPhi + radiusY * radiusY * cosPhi * cosPhi)

        include(cx - extentX, cy - extentY)
        include(cx + extentX, cy + extentY)
    }

    /** Mirrors [r] horizontally within a bone box of [boneWidth], for `Mirror.X` layers. */
    fun mirrorX(r: Rect, boneWidth: Float): Rect =
        Rect(boneWidth - r.right, r.top, boneWidth - r.left, r.bottom)

    /** The smallest [Rect] containing both [a] and [b]. `androidx.compose.ui.geometry.Rect` has no union(). */
    fun union(a: Rect, b: Rect): Rect = Rect(
        left = min(a.left, b.left),
        top = min(a.top, b.top),
        right = max(a.right, b.right),
        bottom = max(a.bottom, b.bottom)
    )
}
