package de.littletrotter.ui.view.referencedata

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.router.RouteConfiguration
import com.vaadin.flow.router.RouteParameters
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Verifies that `referenzdaten/:gruppe?/:tab?` (two chained optional route
 * parameters, following [de.littletrotter.ui.view.invoice.InvoiceDetailView]'s
 * `BeforeEnterObserver` precedent) is actually accepted by Vaadin's router and resolves to the
 * expected URLs for the bare route, group-only, and group+tab cases.
 *
 * Doesn't instantiate [ReferenceDataView] — its constructor needs CDI (same constraint noted in
 * `MessageCenterViewKaribuTest`). `setAnnotatedRoute` only parses the `@Route` annotation into a
 * route template; it doesn't construct the view, so this is safe to run standalone.
 */
class ReferenceDataViewRouteKaribuTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    @Test
    fun `referenzdaten route accepts bare, group-only and group+tab URLs`() {
        val routeConfiguration = RouteConfiguration.forSessionScope()
        routeConfiguration.setAnnotatedRoute(ReferenceDataView::class.java)

        assertEquals("referenzdaten", routeConfiguration.getUrl(ReferenceDataView::class.java))
        assertEquals(
            "referenzdaten/e-rechnung",
            routeConfiguration.getUrl(ReferenceDataView::class.java, RouteParameters(mapOf("gruppe" to "e-rechnung"))),
        )
        assertEquals(
            "referenzdaten/e-rechnung/validierung",
            routeConfiguration.getUrl(
                ReferenceDataView::class.java,
                RouteParameters(mapOf("gruppe" to "e-rechnung", "tab" to "validierung")),
            ),
        )
    }
}
