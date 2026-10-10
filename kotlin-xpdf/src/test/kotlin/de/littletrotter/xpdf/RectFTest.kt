package de.littletrotter.xpdf

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Branch coverage tests for PdfLayoutInspector.RectF.
 * Tests the union() and overlaps() methods with various boundary conditions.
 */
class RectFTest {

    @Test
    fun `union combines two rectangles into bounding box`() {
        val rect1 = PdfLayoutInspector.RectF(x = 10f, y = 20f, width = 30f, height = 40f)
        val rect2 = PdfLayoutInspector.RectF(x = 50f, y = 60f, width = 20f, height = 30f)

        val union = rect1.union(rect2)

        assertEquals(10f, union.x, "union x should be min of x1 and x2")
        assertEquals(20f, union.y, "union y should be min of y1 and y2")
        assertEquals(60f, union.width, "union width should span from min x to max right edge")
        assertEquals(70f, union.height, "union height should span from min y to max bottom edge")
    }

    @Test
    fun `union with overlapping rectangles`() {
        val rect1 = PdfLayoutInspector.RectF(x = 10f, y = 10f, width = 30f, height = 30f)
        val rect2 = PdfLayoutInspector.RectF(x = 25f, y = 25f, width = 25f, height = 25f)

        val union = rect1.union(rect2)

        assertEquals(10f, union.x)
        assertEquals(10f, union.y)
        assertEquals(40f, union.width, "Should span from x=10 to x=50")
        assertEquals(40f, union.height, "Should span from y=10 to y=50")
    }

    @Test
    fun `union where rect2 is completely contained in rect1`() {
        val rect1 = PdfLayoutInspector.RectF(x = 0f, y = 0f, width = 100f, height = 100f)
        val rect2 = PdfLayoutInspector.RectF(x = 25f, y = 25f, width = 50f, height = 50f)

        val union = rect1.union(rect2)

        assertEquals(0f, union.x)
        assertEquals(0f, union.y)
        assertEquals(100f, union.width)
        assertEquals(100f, union.height)
    }

    @Test
    fun `overlaps returns true when rectangles overlap`() {
        val rect1 = PdfLayoutInspector.RectF(x = 10f, y = 10f, width = 30f, height = 30f)
        val rect2 = PdfLayoutInspector.RectF(x = 25f, y = 25f, width = 30f, height = 30f)

        assertTrue(rect1.overlaps(rect2), "Rectangles should overlap")
        assertTrue(rect2.overlaps(rect1), "Overlaps should be symmetric")
    }

    @Test
    fun `overlaps returns false when rectangles are separated horizontally`() {
        val rect1 = PdfLayoutInspector.RectF(x = 10f, y = 10f, width = 20f, height = 30f)
        val rect2 = PdfLayoutInspector.RectF(x = 100f, y = 10f, width = 20f, height = 30f)

        assertFalse(rect1.overlaps(rect2), "Horizontally separated rectangles should not overlap")
    }

    @Test
    fun `overlaps returns false when rectangles are separated vertically`() {
        val rect1 = PdfLayoutInspector.RectF(x = 10f, y = 10f, width = 30f, height = 20f)
        val rect2 = PdfLayoutInspector.RectF(x = 10f, y = 100f, width = 30f, height = 20f)

        assertFalse(rect1.overlaps(rect2), "Vertically separated rectangles should not overlap")
    }

    @Test
    fun `overlaps handles touching rectangles with default tolerance`() {
        val rect1 = PdfLayoutInspector.RectF(x = 10f, y = 10f, width = 20f, height = 20f)
        // rect2 right edge at x=30, rect1 right edge at x=30, tolerance is 0.5
        val rect2 = PdfLayoutInspector.RectF(x = 30f, y = 10f, width = 20f, height = 20f)

        assertFalse(rect1.overlaps(rect2), "Touching edges should not overlap with default tolerance")
    }

    @Test
    fun `overlaps detects clearly overlapping rectangles`() {
        val rect1 = PdfLayoutInspector.RectF(x = 10f, y = 10f, width = 50f, height = 50f)
        val rect2 = PdfLayoutInspector.RectF(x = 30f, y = 30f, width = 50f, height = 50f)

        assertTrue(rect1.overlaps(rect2), "Clearly overlapping rectangles should be detected as overlapping")
    }

    @Test
    fun `overlaps with custom tolerance parameter works`() {
        val rect1 = PdfLayoutInspector.RectF(x = 10f, y = 10f, width = 20f, height = 20f)
        val rect2 = PdfLayoutInspector.RectF(x = 25f, y = 10f, width = 20f, height = 20f)

        // With default tolerance they overlap
        assertTrue(rect1.overlaps(rect2), "Overlapping rectangles should be detected")
        // With different tolerance should still work
        assertTrue(rect1.overlaps(rect2, tolerance = 1.0f), "Custom tolerance should work")
    }

    @Test
    fun `overlaps returns true for completely overlapping rectangles`() {
        val rect1 = PdfLayoutInspector.RectF(x = 10f, y = 10f, width = 50f, height = 50f)
        val rect2 = PdfLayoutInspector.RectF(x = 20f, y = 20f, width = 30f, height = 30f)

        assertTrue(rect1.overlaps(rect2), "Completely overlapping rectangles should overlap")
    }

    // The horizontal/vertical "separated" tests above always land on the receiver's *left*/*top*
    // condition being false (the receiver sits to the left of / above the other rect). The two
    // tests below flip the receiver to the *right*/*below* side instead, so the first ("x <
    // other.right") and third ("y < other.bottom") comparisons in overlaps() are each exercised on
    // their false branch too, not only the true side already covered elsewhere.

    @Test
    fun `boundary - overlaps returns false when the receiver lies entirely to the right of the other rectangle`() {
        val rect1 = PdfLayoutInspector.RectF(x = 100f, y = 10f, width = 20f, height = 20f)
        val rect2 = PdfLayoutInspector.RectF(x = 10f, y = 10f, width = 20f, height = 20f)

        assertFalse(rect1.overlaps(rect2), "A rectangle entirely to the right of the other must not overlap")
    }

    @Test
    fun `boundary - overlaps returns false when the receiver lies entirely below the other rectangle`() {
        val rect1 = PdfLayoutInspector.RectF(x = 10f, y = 100f, width = 20f, height = 20f)
        val rect2 = PdfLayoutInspector.RectF(x = 10f, y = 10f, width = 20f, height = 20f)

        assertFalse(rect1.overlaps(rect2), "A rectangle entirely below the other must not overlap")
    }
}
