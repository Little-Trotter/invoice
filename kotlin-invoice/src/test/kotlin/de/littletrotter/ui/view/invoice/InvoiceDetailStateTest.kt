package de.littletrotter.ui.view.invoice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [InvoiceDetailState] carries the former `InvoiceDetailView.refreshAfterDataChange`/
 * `renderInvoiceOrDeferIfEditing`/`pendingRefreshAfterEdit` behaviour, tested here without any
 * Vaadin component.
 */
class InvoiceDetailStateTest {

    @Test
    fun `happy path - applyChange renders immediately outside edit mode`() {
        val state = InvoiceDetailState()
        var rendered: de.littletrotter.application.dto.InvoiceDetailDto? = null
        state.onInvoiceChanged { rendered = it }
        val dto = DetailViewTestHarness.dto()

        state.applyChange(dto)

        assertEquals(dto, state.dto)
        assertEquals(dto, rendered)
        assertFalse(state.pendingChange)
    }

    @Test
    fun `edge case - applyChange defers instead of rendering while editMode is on`() {
        val state = InvoiceDetailState()
        var callCount = 0
        state.onInvoiceChanged { callCount++ }
        state.enterEditMode()

        state.applyChange(DetailViewTestHarness.dto(id = 2L))

        assertEquals(0, callCount, "the listener must not run while editMode is on")
        assertTrue(state.pendingChange)
        assertNull(state.dto, "the deferred change's dto is discarded, same as the pre-T14-D5 behaviour")
    }

    @Test
    fun `edge case - exitEditMode consumes pendingChange without replaying it`() {
        val state = InvoiceDetailState()
        var callCount = 0
        state.onInvoiceChanged { callCount++ }
        state.enterEditMode()
        state.applyChange(DetailViewTestHarness.dto(id = 3L))
        assertTrue(state.pendingChange)

        state.exitEditMode()

        assertFalse(state.editMode)
        assertFalse(state.pendingChange)
        assertEquals(0, callCount, "leaving edit mode must not itself trigger a render")
    }

    @Test
    fun `edge case - applyChange with no registered listener does not throw`() {
        val state = InvoiceDetailState()
        val dto = DetailViewTestHarness.dto(id = 4L)

        state.applyChange(dto)

        assertEquals(dto, state.dto)
    }

    @Test
    fun `edge case - enterEditMode after a pending change leaves the flag untouched`() {
        val state = InvoiceDetailState()
        state.enterEditMode()
        state.applyChange(DetailViewTestHarness.dto(id = 5L))
        assertTrue(state.pendingChange)

        state.enterEditMode()

        assertTrue(state.pendingChange, "entering edit mode again must not itself clear a pending flag")
    }

    @Test
    fun `reloadOnConflict invokes the registered reload only for concurrency conflicts`() {
        val state = InvoiceDetailState()
        var reloads = 0
        state.onReloadRequested { reloads++ }

        state.reloadOnConflict(RuntimeException("wrapped", jakarta.persistence.OptimisticLockException("stale")))
        state.reloadOnConflict(de.littletrotter.domain.exception.InvoiceImmutableException("R-1"))
        state.reloadOnConflict(IllegalStateException("boom"))

        assertEquals(2, reloads)
    }

    @Test
    fun `reloadOnConflict tolerates a null state and an unregistered listener`() {
        val none: InvoiceDetailState? = null
        none.reloadOnConflict(jakarta.persistence.OptimisticLockException("stale"))
        InvoiceDetailState().reloadAfterConflict()
    }
}
