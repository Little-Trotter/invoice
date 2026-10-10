package de.littletrotter.ui.component

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.html.Span
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * AP-IMP-5 — [Pager] had no dedicated test before this file, despite carrying every one of its
 * own bounds-checking decisions: prev/next enablement, the totalPages<=0 and pageSize<=0 guards,
 * the current-page clamp after a shrinking [Pager.setTotalItems], and [Pager.goToPage]'s
 * out-of-range/no-op/fire-listener branches.
 */
class PagerTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun prevButton(p: Pager): Button = p.children.toList().filterIsInstance<Button>()[0]
    private fun nextButton(p: Pager): Button = p.children.toList().filterIsInstance<Button>()[1]
    private fun label(p: Pager): Span = p.children.toList().filterIsInstance<Span>()[0]

    @Test
    fun `a fresh pager starts on page 1 with both buttons reflecting a single page`() {
        val p = Pager()
        assertEquals("1 / 1", label(p).text)
        assertFalse(prevButton(p).isEnabled, "no previous page from page 1")
        assertFalse(nextButton(p).isEnabled, "a single page has no next page")
    }

    @Test
    fun `setTotalItems with zero or negative items still shows exactly one page`() {
        val p = Pager()
        p.setTotalItems(0)
        assertEquals("1 / 1", label(p).text)
        p.setTotalItems(-5)
        assertEquals("1 / 1", label(p).text)
    }

    @Test
    fun `setTotalItems computes the ceiling page count from pageSize`() {
        val p = Pager()
        p.setTotalItems(41) // default pageSize 20 -> 3 pages
        assertEquals("1 / 3", label(p).text)
        assertTrue(nextButton(p).isEnabled)
    }

    @Test
    fun `setPageSize of zero or negative falls back to 20`() {
        val p = Pager()
        p.setPageSize(0)
        p.setTotalItems(21)
        assertEquals("1 / 2", label(p).text, "pageSize<=0 must fall back to 20, not divide by zero/negative")
    }

    @Test
    fun `clicking next advances the page and fires the listener, clicking prev goes back`() {
        val p = Pager()
        p.setTotalItems(41)
        var lastPage: Int? = null
        var calls = 0
        p.onPageChange { page, _ -> lastPage = page; calls++ }

        nextButton(p).click()
        assertEquals("2 / 3", label(p).text)
        assertEquals(1, lastPage)
        assertTrue(prevButton(p).isEnabled)

        prevButton(p).click()
        assertEquals("1 / 3", label(p).text)
        assertEquals(0, lastPage)
        assertEquals(2, calls)
    }

    @Test
    fun `next is disabled on the last page and prev is disabled on the first`() {
        val p = Pager()
        p.setTotalItems(41) // 3 pages
        nextButton(p).click()
        nextButton(p).click()
        assertEquals("3 / 3", label(p).text)
        assertFalse(nextButton(p).isEnabled, "no next page from the last page")

        // clicking next again must be a no-op (guarded by `current < totalPages - 1`)
        nextButton(p).click()
        assertEquals("3 / 3", label(p).text)
    }

    @Test
    fun `shrinking the total items clamps the current page back into range`() {
        val p = Pager()
        p.setTotalItems(41) // 3 pages
        nextButton(p).click(); nextButton(p).click() // page index 2 (page 3)
        p.setTotalItems(5) // now 1 page
        assertEquals("1 / 1", label(p).text, "current must clamp back once totalPages shrank under it")
    }

    @Test
    fun `goToPage ignores an out-of-range index`() {
        val p = Pager()
        p.setTotalItems(41)
        var fired = false
        p.onPageChange { _, _ -> fired = true }

        p.goToPage(5)
        assertEquals("1 / 3", label(p).text, "index 5 is out of range for 3 pages — must be a no-op")
        assertFalse(fired)

        p.goToPage(-1)
        assertEquals("1 / 3", label(p).text)
    }

    @Test
    fun `goToPage without fireListener is silent even for a real page change`() {
        val p = Pager()
        p.setTotalItems(41)
        var fired = false
        p.onPageChange { _, _ -> fired = true }

        p.goToPage(1, fireListener = false)
        assertEquals("2 / 3", label(p).text, "the page must still change")
        assertFalse(fired, "fireListener=false must suppress the callback")
    }

    @Test
    fun `goToPage to the already-current page without firing is a total no-op`() {
        val p = Pager()
        p.setTotalItems(41)
        var calls = 0
        p.onPageChange { _, _ -> calls++ }

        p.goToPage(0, fireListener = false)
        assertEquals(0, calls, "already on page 0 with fireListener=false — the early-return branch")
    }

    @Test
    fun `goToPage with fireListener true invokes the listener even for the same page`() {
        val p = Pager()
        p.setTotalItems(41)
        var calls = 0
        p.onPageChange { _, _ -> calls++ }

        p.goToPage(0, fireListener = true)
        assertEquals(1, calls, "current==pageIndex but fireListener=true must still fire")
    }

    @Test
    fun `clicking prev on the first page is a no-op`() {
        val p = Pager()
        p.setTotalItems(41)
        var fired = false
        p.onPageChange { _, _ -> fired = true }

        prevButton(p).click()

        assertEquals("1 / 3", label(p).text, "already on the first page — must not go negative")
        assertFalse(fired)
    }

    @Test
    fun `clicking next or prev with no listener registered does not throw`() {
        val p = Pager()
        p.setTotalItems(41)

        org.junit.jupiter.api.Assertions.assertDoesNotThrow {
            nextButton(p).click()
            prevButton(p).click()
        }
    }

    @Test
    fun `goToPage with fireListener true and no listener registered does not throw`() {
        val p = Pager()
        p.setTotalItems(41)

        org.junit.jupiter.api.Assertions.assertDoesNotThrow { p.goToPage(1, fireListener = true) }
        assertEquals("2 / 3", label(p).text)
    }
}
