package de.littletrotter.ui

import com.vaadin.flow.component.dependency.CssImport
import com.vaadin.flow.component.dependency.StyleSheet
import com.vaadin.flow.component.page.Inline
import com.vaadin.flow.component.page.Push
import com.vaadin.flow.component.page.TargetElement
import com.vaadin.flow.server.AppShellSettings
import com.vaadin.flow.shared.communication.PushMode
import com.vaadin.flow.shared.ui.Transport
import org.jsoup.nodes.Element
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText

/**
 * [AppShell] carries no logic of its own beyond the dark-mode bootstrap script; a plain unit test
 * is enough here (`AppShell` may only be constructible/assertable for its
 * annotations and the PWA/theme configuration).
 */
class AppShellTest {

    @Test
    fun `happy path - configurePage inlines the dark-mode script prepended and the base-href pin appended to head`() {
        val shell = AppShell()
        val settings = AppShellSettings()

        shell.configurePage(settings)

        val inlines = declaredField<MutableList<Any>>(AppShellSettings::class.java, settings, "inlines")
        assertEquals(2, inlines.size)
        val element = inlines.first()
        assertEquals(TargetElement.HEAD, declaredField(element.javaClass, element, "target"))
        assertEquals(Inline.Position.PREPEND, declaredField(element.javaClass, element, "position"))
        assertEquals(Inline.Wrapping.JAVASCRIPT, declaredField(element.javaClass, element, "type"))
        val content: String = declaredField(element.javaClass, element, "content")
        assertTrue(content.contains("prefers-color-scheme: dark"))
        assertTrue(content.contains("setAttribute('theme'"))
    }

    @Test
    fun `L15 - a base-href pin script is appended to head so it runs after the base element exists`() {
        val settings = AppShellSettings()

        AppShell().configurePage(settings)

        val inlines = declaredField<MutableList<Any>>(AppShellSettings::class.java, settings, "inlines")
        val pin = inlines.single { declaredField<String>(it.javaClass, it, "content") == AppShell.BASE_HREF_PIN_SCRIPT }
        assertEquals(TargetElement.HEAD, declaredField(pin.javaClass, pin, "target"))
        assertEquals(Inline.Position.APPEND, declaredField(pin.javaClass, pin, "position"))
        assertEquals(Inline.Wrapping.JAVASCRIPT, declaredField(pin.javaClass, pin, "type"))
        // Reads the resolved (absolute) b.href and writes it back as the attribute.
        assertTrue(AppShell.BASE_HREF_PIN_SCRIPT.contains("setAttribute('href',b.href)"))
    }

    @Test
    fun `happy path - configurePage registers the navy signet as favicon and touch icon`() {
        val shell = AppShell()
        val settings = AppShellSettings()

        shell.configurePage(settings)

        val elements = declaredField<Map<Inline.Position, List<Element>>>(AppShellSettings::class.java, settings, "elements")
        val links = elements.values.flatten().filter { it.tagName() == "link" }
        assertEquals(
            listOf(
                "icons/favicon.svg",
                "icons/favicon-32.png",
                "icons/favicon-16.png",
                "icons/apple-touch-icon-180.png",
            ),
            links.map { it.attr("href") },
        )
        val svg = links[0]
        assertEquals("icon", svg.attr("rel"))
        assertEquals("image/svg+xml", svg.attr("type"))
        assertEquals("any", svg.attr("sizes"))
        val png = links[1]
        assertEquals("icon", png.attr("rel"))
        assertEquals("32x32", png.attr("sizes"))
        assertEquals("16x16", links[2].attr("sizes"))
        assertEquals("apple-touch-icon", links[3].attr("rel"))
    }

    @Test
    fun `boundary - class loads Lumo first, then the littletrotter stylesheet, and automatic push`() {
        val cssImports = AppShell::class.java.getAnnotationsByType(CssImport::class.java).map { it.value }
        val push = AppShell::class.java.getAnnotation(Push::class.java)

        assertEquals(listOf("@vaadin/vaadin-lumo-styles/lumo.css", "./themes/littletrotter/styles.css"), cssImports)
        assertEquals(PushMode.AUTOMATIC, push.value)
        assertEquals(Transport.LONG_POLLING, push.transport)
    }

    @Test
    fun `boundary - configurePage can be invoked repeatedly, appending two inline scripts each time`() {
        val shell = AppShell()
        val settings = AppShellSettings()

        shell.configurePage(settings)
        shell.configurePage(settings)

        val inlines = declaredField<MutableList<Any>>(AppShellSettings::class.java, settings, "inlines")
        assertEquals(4, inlines.size)
    }

    @Test
    fun `boundary - no view relies on Lumo utility classes, which are deliberately not loaded`() {
        // AppShell loads Lumo's tokens/component styling via lumo.css but not its utility
        // stylesheet (Lumo.UTILITY_STYLESHEET) — the app styles everything through its own
        // stylesheet, so a LumoUtility class name on any view would be silently inert. Surefire
        // runs with the module directory as CWD, so this path is relative to it; the directory
        // check fails loudly if that assumption is ever wrong, instead of the walk vacuously
        // matching nothing and passing green.
        val sourceRoot = Path.of("src/main/kotlin")
        assertTrue(
            Files.isDirectory(sourceRoot),
            "expected [$sourceRoot] to exist relative to the working directory " +
                "[${Path.of("").toAbsolutePath()}] - is surefire's CWD no longer the module directory?",
        )

        Files.walk(sourceRoot).use { paths ->
            paths.filter { it.extension == "kt" }.forEach { file ->
                assertTrue(
                    !file.readText().contains("LumoUtility"),
                    "[$file] references LumoUtility, but its stylesheet is deliberately not loaded (see AppShell)",
                )
            }
        }

        assertNull(
            AppShell::class.java.getAnnotation(StyleSheet::class.java),
            "AppShell must not carry @StyleSheet - that is how the Lumo utility stylesheet would arrive",
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> declaredField(clazz: Class<*>, instance: Any, name: String): T {
        val f = clazz.getDeclaredField(name)
        f.isAccessible = true
        return f.get(instance) as T
    }
}
