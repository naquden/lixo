package se.lixo.app.ui.components.celebration

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path

/**
 * Shared chest geometry used by both [RewardChest] (the big wooden reveal chest) and
 * [FlitChestMeter] (the small holo progress chest), so both draw the exact same silhouette.
 */
internal const val CHEST_LID_TOP_FRACTION = 0.06f
internal const val CHEST_BODY_TOP_FRACTION = 0.42f

/** The chest body rectangle (everything below the lid). */
internal fun chestBodyRect(size: Size): Rect =
    Rect(0f, size.height * CHEST_BODY_TOP_FRACTION, size.width, size.height)

/**
 * The curved lid contour, identical to [RewardChest]'s original inline lid path: rounded top
 * corners, straight sides down to the body seam.
 */
internal fun chestLidPath(size: Size, cornerPx: Float): Path {
    val w = size.width
    val h = size.height
    val lidTop = h * CHEST_LID_TOP_FRACTION
    val lidBottom = h * CHEST_BODY_TOP_FRACTION
    return Path().apply {
        moveTo(0f, lidBottom)
        lineTo(0f, lidTop + cornerPx)
        quadraticTo(0f, lidTop, cornerPx, lidTop)
        lineTo(w - cornerPx, lidTop)
        quadraticTo(w, lidTop, w, lidTop + cornerPx)
        lineTo(w, lidBottom)
        close()
    }
}

/**
 * One closed contour covering the whole chest silhouette (lid + body): rounded top corners at
 * the lid top, straight sides, rounded bottom corners at the base. Used as the clip for the
 * meter's glass, gold fill and shimmer.
 */
internal fun chestSilhouettePath(size: Size, cornerPx: Float): Path {
    val w = size.width
    val h = size.height
    val lidTop = h * CHEST_LID_TOP_FRACTION
    return Path().apply {
        moveTo(0f, lidTop + cornerPx)
        quadraticTo(0f, lidTop, cornerPx, lidTop)
        lineTo(w - cornerPx, lidTop)
        quadraticTo(w, lidTop, w, lidTop + cornerPx)
        lineTo(w, h - cornerPx)
        quadraticTo(w, h, w - cornerPx, h)
        lineTo(cornerPx, h)
        quadraticTo(0f, h, 0f, h - cornerPx)
        close()
    }
}
