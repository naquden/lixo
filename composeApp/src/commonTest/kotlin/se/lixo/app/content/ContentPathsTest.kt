package se.lixo.app.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import se.lixo.app.domain.content.ContentPaths

class ContentPathsTest {

    @Test
    fun strictErrorRejectsMalformedPaths() {
        assertNotNull(ContentPaths.strictError("M 0 0 L"), "missing args for L")
        assertNotNull(ContentPaths.strictError("L 1 1"), "must start with M/m")
        assertNotNull(ContentPaths.strictError("M 0 0 Q 1"), "wrong arity for Q")
        assertNotNull(ContentPaths.strictError("M 0 0 A 1 1 0 01 2 2"), "clubbed arc flags")
        assertNotNull(ContentPaths.strictError("M 0 0 X 1"), "illegal command letter")
    }

    @Test
    fun strictErrorAcceptsWellFormedPaths() {
        assertNull(ContentPaths.strictError("M0,0 L10-5 z"))
        assertNull(ContentPaths.strictError("M 4 4 A 15 15 0 0 1 34 4 L 34 30 L 4 30 Z"))
    }

    @Test
    fun boundsOfCubicCurve() {
        val nodes = ContentPaths.parseNodes("M 0 0 C 10 -10 20 10 30 0")
        val bounds = assertNotNull(ContentPaths.bounds(nodes))
        assertEquals(0f, bounds.left)
        assertEquals(-10f, bounds.top)
        assertEquals(30f, bounds.right)
        assertEquals(10f, bounds.bottom)
    }

    @Test
    fun boundsOfArcIncludesEllipseExtent() {
        val nodes = ContentPaths.parseNodes("M 0 0 A 10 10 0 0 1 20 0")
        val bounds = assertNotNull(ContentPaths.bounds(nodes))
        // A semicircle from (0,0) to (20,0): the ellipse extends to y=-10 or y=10 (or both).
        assertEquals(true, bounds.top == -10f || bounds.bottom == 10f)
    }
}
