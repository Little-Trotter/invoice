package de.littletrotter.ui

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.button.Button
import de.littletrotter.application.ApplicationIdentity
import de.littletrotter.application.security.AppRoleProvider
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * UI-5 slice 3c — [MainLayout.createBellWrapper]'s click listener
 * (`UI.getCurrent()?.navigate(MessageCenterView::class.java)`) was never exercised: neither
 * direction of the null-safe chain. Same reflection idiom as
 * [MainLayoutDrawerKaribuTest.mainLayoutWithFullDrawer] — `createHeader()` is normally invoked by
 * the `@PostConstruct` callback a plain unit test never triggers.
 */
class MainLayoutBellButtonKaribuTest {

    private fun setPrivateField(target: MainLayout, name: String, value: Any) {
        val field = MainLayout::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }

    private fun mainLayoutWithHeader(): MainLayout {
        val mainLayout = MainLayout(null, null)
        setPrivateField(mainLayout, "applicationIdentity", mock<ApplicationIdentity>().also { whenever(it.displayName).thenReturn("Little Trotter") })
        setPrivateField(mainLayout, "appRoleProvider", mock<AppRoleProvider>())
        val createHeader = MainLayout::class.java.getDeclaredMethod("createHeader")
        createHeader.isAccessible = true
        createHeader.invoke(mainLayout)
        return mainLayout
    }

    private fun bellButton(mainLayout: MainLayout): Button {
        val f = MainLayout::class.java.getDeclaredField("bellButton")
        f.isAccessible = true
        return f.get(mainLayout) as Button
    }

    @Test
    fun `clicking the bell with no active UI does not throw`() {
        // Deliberately no MockVaadin.setup() — UI.getCurrent() is null in a plain JUnit thread,
        // exercising the safe-call chain's null branch.
        val mainLayout = mainLayoutWithHeader()

        bellButton(mainLayout).click()
        // No assertion beyond "did not throw" — with no active UI there is nothing else to observe.
    }

    @Test
    fun `clicking the bell with an active UI dereferences the current UI - the non-null branch runs`() {
        MockVaadin.setup()
        try {
            val mainLayout = mainLayoutWithHeader()

            // This module's minimal test setup has no routes registered, so the click reaching
            // Vaadin's router with a NotFoundException naming MessageCenterView's route is proof
            // the safe-call chain dereferenced a real, non-null UI instead of short-circuiting —
            // same idiom as StatusPanelCoverageKaribuTest's reload() tests.
            val thrown = try {
                bellButton(mainLayout).click()
                null
            } catch (ex: com.vaadin.flow.router.NotFoundException) {
                ex
            }
            assertTrue(thrown != null, "expected the click to reach UI.getCurrent()?.navigate(...)'s non-null branch")
        } finally {
            MockVaadin.tearDown()
        }
    }
}
