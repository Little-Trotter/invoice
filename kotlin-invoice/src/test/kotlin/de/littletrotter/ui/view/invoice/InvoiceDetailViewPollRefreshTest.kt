package de.littletrotter.ui.view.invoice

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins [shouldRefreshOnPollTick]'s transition semantics — especially the completion tick
 * (active → inactive), which MUST refresh: generation jobs complete in the separate worker
 * process, the in-memory UiEventBus never fires in the web process, and the poll tick that first
 * observes "no active jobs anymore" is the only carrier of the DELEGATED/RUNNING → COMPLETED
 * result. The original listener returned early on exactly that tick, leaving the status chip on
 * "Weitergeleitet" until a manual reload (PO report 30.08.2026).
 */
class InvoiceDetailViewPollRefreshTest {

    @Test
    fun `refreshes while a job is active`() {
        assertTrue(shouldRefreshOnPollTick(hadActiveJobs = false, hasActiveJobs = true))
        assertTrue(shouldRefreshOnPollTick(hadActiveJobs = true, hasActiveJobs = true))
    }

    @Test
    fun `refreshes once more on the completion tick`() {
        assertTrue(shouldRefreshOnPollTick(hadActiveJobs = true, hasActiveJobs = false))
    }

    @Test
    fun `stays quiet while no jobs are and were active`() {
        assertFalse(shouldRefreshOnPollTick(hadActiveJobs = false, hasActiveJobs = false))
    }
}
