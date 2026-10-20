package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.UI
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

class ReceiptPoolViewNoUiKaribuTest {
    @Test
    fun `constructing the view outside a UI context skips the page-title update`() {
        assertNull(UI.getCurrent(), "precondition: no UI on this thread")
        ReceiptPoolView(mock(), mock(), mock(), mock(), mock())
        assertNull(UI.getCurrent(), "constructing the view must not create a UI")
    }
}
