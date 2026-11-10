package se.lixo.app

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import se.lixo.app.ui.screens.wardrobe.wardrobeGridMetrics
import se.lixo.app.ui.screens.wardrobe.wardrobeUsesScrollFallback

/**
 * Unit tests for the pure wardrobe grid/fallback math (doc/plans/034). `gap`/`minCard` pairs are
 * 12dp/130dp and 24dp/260dp at density 2.625 (a 411dp-wide phone), rounded to whole pixels.
 */
class WardrobeLayoutTest {

    private val gapPx1 = 32
    private val minCardPx1 = 341

    private val gapPx2 = 24
    private val minCardPx2 = 260

    @Test
    fun metrics_wholePixelRows_noRemainder() {
        for ((gapPx, minCardPx) in listOf(gapPx1 to minCardPx1, gapPx2 to minCardPx2)) {
            var area = 200
            while (area <= 1200) {
                val m = wardrobeGridMetrics(area, gapPx, minCardPx)
                assertEquals(
                    m.gridHeightPx,
                    m.cellPx * m.rows + gapPx * (m.rows - 1),
                    "area=$area gap=$gapPx minCard=$minCardPx"
                )
                assertTrue(m.gridHeightPx <= area, "area=$area gap=$gapPx minCard=$minCardPx")
                assertTrue(m.rows in 1..3, "area=$area gap=$gapPx minCard=$minCardPx")
                assertTrue(
                    m.cellPx <= (minCardPx * 1.4f).toInt(),
                    "area=$area gap=$gapPx minCard=$minCardPx"
                )
                area += 7
            }
        }
    }

    @Test
    fun metrics_rowCountBounds() {
        val gapPx = gapPx1
        val minCardPx = minCardPx1

        val oneRow = wardrobeGridMetrics(minCardPx + 1, gapPx, minCardPx)
        assertEquals(1, oneRow.rows)

        val threeRows = wardrobeGridMetrics(20_000, gapPx, minCardPx)
        assertEquals(3, threeRows.rows)
        assertEquals((minCardPx * 1.4f).toInt(), threeRows.cellPx)

        val zeroArea = wardrobeGridMetrics(0, gapPx, minCardPx)
        assertEquals(1, zeroArea.rows)
        assertTrue(zeroArea.cellPx >= 0)

        val tinyArea = wardrobeGridMetrics(50, gapPx, minCardPx)
        assertEquals(1, tinyArea.rows)
        assertTrue(tinyArea.cellPx >= 0)
    }

    @Test
    fun metrics_cellAtLeastMinCard() {
        for ((gapPx, minCardPx) in listOf(gapPx1 to minCardPx1, gapPx2 to minCardPx2)) {
            var area = minCardPx
            while (area <= 3000) {
                val m = wardrobeGridMetrics(area, gapPx, minCardPx)
                assertTrue(m.cellPx >= minCardPx, "area=$area gap=$gapPx minCard=$minCardPx cellPx=${m.cellPx}")
                area += 5
            }
        }
    }

    @Test
    fun scrollFallback_decision() {
        // 360x640 at fontScale 1: plenty of room, no fallback.
        assertFalse(wardrobeUsesScrollFallback(608.dp, 260.dp, 130.dp))
        // fontScale 1.6: fixed rows and minCard both grow, triggers the fallback.
        assertTrue(wardrobeUsesScrollFallback(608.dp, 332.dp, 148.dp))
        // Taller screen, same fixed rows: no fallback.
        assertFalse(wardrobeUsesScrollFallback(868.dp, 260.dp, 130.dp))
        // Very short screen: triggers the fallback.
        assertTrue(wardrobeUsesScrollFallback(300.dp, 260.dp, 130.dp))
    }
}
