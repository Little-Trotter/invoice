package de.littletrotter.ui.view.system

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.HasText
import com.vaadin.flow.component.html.Span
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SystemUiComponentsTest {

    @Test
    fun `status dot uses green for up and red for down`() {
        val up = SystemUiComponents.statusDot(true)
        val down = SystemUiComponents.statusDot(false)

        assertTrue(up.hasClassName("lt-status-dot--up"))
        assertTrue(down.hasClassName("lt-status-dot--down"))
    }

    @Test
    fun `info card includes title value and extra component`() {
        val extra = SystemUiComponents.statusDot(true)
        val card = SystemUiComponents.infoCard("Runtime", "Java 25", extra)

        assertEquals(3, card.children.count())
        assertTrue(card.classNames.contains("system-info-card"))
    }

    @Test
    fun `service card reflects status text`() {
        val online = SystemUiComponents.serviceCard(SystemUiComponents.ServiceCardContent("Kafka", "Bus", "kafka:9092"), SystemUiComponents.ServiceCardStatus("Aktiv", true))
        val offline = SystemUiComponents.serviceCard(SystemUiComponents.ServiceCardContent("Valkey", "Cache", "valkey:6379"), SystemUiComponents.ServiceCardStatus("Nicht erreichbar", false))

        assertTrue(flatten(online).filterIsInstance<HasText>().any { it.text.contains("Aktiv") })
        assertTrue(flatten(offline).filterIsInstance<HasText>().any { it.text.contains("Nicht erreichbar") })
    }

    @Test
    fun `service card text stays inside card with overflow containment`() {
        val description = "Konformitätsprüfung erzeugter PDFs (optionale Laufzeitstufe, Schalter in den Referenzdaten)."
        val detail = "Instanz: xpdf • URL: xpdf:8081/status/verapdf"
        val card = SystemUiComponents.serviceCard(
            SystemUiComponents.ServiceCardContent(name = "veraPDF", description = description, detail = detail),
            SystemUiComponents.ServiceCardStatus(label = "Aktiv", up = true),
        )

        val components = flatten(card)
        val allSpans = components.filterIsInstance<Span>()

        val descSpan = allSpans.find { it.text == description }
        assertTrue(descSpan?.hasClassName("lt-sys-service-description") == true)

        val detailSpan = allSpans.find { it.text == detail }
        assertTrue(detailSpan?.hasClassName("lt-sys-service-detail") == true)
        assertEquals(detail, detailSpan?.element?.getAttribute("title"))

        val nameSpan = allSpans.find { it.text == "veraPDF" }
        assertTrue(nameSpan?.hasClassName("lt-sys-service-name") == true)
        // Same idiom as detailSpan above: the ellipsized name still needs a title
        // so the full value is reachable once it clips.
        assertEquals("veraPDF", nameSpan?.element?.getAttribute("title"))

        val contentLayout = components.find { it.classNames.contains("system-service-card-content") }
        assertTrue(contentLayout?.classNames?.contains("lt-sys-service-content") == true)

        // An inline "width" on the card itself always wins over the
        // ".system-service-card { width: 100% }" stylesheet rule, pinning the card at a
        // fixed size regardless of its grid cell; the card must not set one.
        assertEquals(null, card.style.get("width"))
    }

    private fun flatten(component: Component): List<Component> =
        listOf(component) + component.children.toList().flatMap { flatten(it) }
}
