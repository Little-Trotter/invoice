package de.littletrotter.ui.navigation

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._fireShortcut
import com.vaadin.flow.component.Key
import com.vaadin.flow.component.textfield.TextField
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * UI-5 slice 3c — two branch gaps [CommandPaletteDialogKaribuTest] leaves open because every
 * navigation-triggering test there passes a capturing `navigate` lambda instead of the real
 * default (`UI.getCurrent()?.navigate(path)`), and every test runs under `MockVaadin.setup()` so
 * `VaadinSession.getCurrent()` is never null: [CommandPaletteDialog]'s default `navigate` param,
 * and the no-session early returns in `currentRecents`/`recordVisit`.
 */
class CommandPaletteDialogNoSessionKaribuTest {

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun getPrivateField(target: Any, name: String): Any? {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(target)
    }

    @Test
    fun `pressing Enter with the real default navigate reaches a live UI and actually navigates`() {
        MockVaadin.setup()
        // Deliberately no `navigate` override — the real default lambda,
        // `UI.getCurrent()?.navigate(path)`, must dereference a real, non-null UI here. This
        // module's minimal test setup has no routes registered, so the reachable-but-unroutable
        // target proves the non-null branch ran (same idiom as MainLayoutBellButtonKaribuTest and
        // StatusPanelCoverageKaribuTest's reload() tests) rather than short-circuiting on null.
        val dialog = CommandPaletteDialog()
        dialog.open()
        val searchField = getPrivateField(dialog, "searchField") as TextField
        searchField.value = "kilo"

        val thrown = try {
            searchField._fireShortcut(Key.ENTER)
            null
        } catch (ex: com.vaadin.flow.router.NotFoundException) {
            ex
        }

        assertTrue(thrown != null, "expected navigate() to reach Vaadin's router (proving the default lambda dereferenced a real UI)")
        assertTrue(thrown!!.message.orEmpty().contains("referenzdaten/reisekosten/kilometersaetze"))
    }

    @Test
    fun `currentRecents returns empty and recordVisit is a no-op without an active VaadinSession`() {
        // Deliberately no MockVaadin.setup() at all — VaadinSession.getCurrent() is null in a
        // plain JUnit thread, exercising both methods' early-return branch.
        val dialog = CommandPaletteDialog()

        val currentRecents = CommandPaletteDialog::class.java.getDeclaredMethod("currentRecents").apply { isAccessible = true }
        val recents = currentRecents.invoke(dialog) as List<*>
        assertTrue(recents.isEmpty(), "no session means no recents, never a crash")

        val recordVisit = CommandPaletteDialog::class.java.getDeclaredMethod("recordVisit", String::class.java).apply { isAccessible = true }
        recordVisit.invoke(dialog, "statistics") // must not throw
    }

    // ── default navigate lambda with no active UI session ───────────────────────────────────────

    @Test
    fun `the default navigate lambda dereferences a null UI without throwing when there is no active session`() {
        // The test above proves the non-null-UI branch of the real default `navigate` lambda
        // (`UI.getCurrent()?.navigate(path)`) by driving it through a live UI and a fired Enter
        // shortcut; no existing test reaches the other outcome of that same null-safety check —
        // UI.getCurrent() itself returning null. Deliberately no MockVaadin.setup() here, and the
        // lambda is invoked directly via reflection (rather than through navigateTo/Dialog.close())
        // so this test exercises exactly that one branch without depending on Dialog behaviour
        // outside a UI.
        val dialog = CommandPaletteDialog()

        val navigateField = CommandPaletteDialog::class.java.getDeclaredField("navigate").apply { isAccessible = true }
        val navigateFn = navigateField.get(dialog)
        val invoke = navigateFn.javaClass.methods.first { it.name == "invoke" && it.parameterCount == 1 }

        org.junit.jupiter.api.Assertions.assertDoesNotThrow { invoke.invoke(navigateFn, "statistics") }
    }
}
