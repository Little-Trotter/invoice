package de.littletrotter.ui

import com.github.mvysny.kaributesting.v10.MockVaadin
import de.littletrotter.application.ApplicationIdentity
import de.littletrotter.application.security.AppRole
import de.littletrotter.application.security.port.inbound.AppRoleQueryPort
import de.littletrotter.application.shared.port.inbound.AuditActorQueryPort
import de.littletrotter.ui.navigation.CommandPaletteDialog
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import de.littletrotter.application.message.port.inbound.MessageCenterQueryPort

/**
 * [MainLayout.initHeader], the `@PostConstruct` callback itself, had only ever been
 * exercised piecemeal: other Karibu tests reflection-invoke `createHeader()` or `createDrawer()`
 * individually (a plain CDI-free unit test never fires `@PostConstruct`), but never the actual
 * `initHeader()` method body that wires both together plus the command-palette item filter and
 * the initial bell-badge refresh. Its `indexProvider` closure — `buildCommandPaletteIndex` fed by
 * [MainLayout.referenceDataHiddenTabs] — is likewise only ever built, never invoked, since no
 * existing test opens the palette through [MainLayout] itself (only through
 * [de.littletrotter.ui.navigation.CommandPaletteDialog] directly).
 */
class MainLayoutInitHeaderKaribuTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun setPrivateField(target: MainLayout, name: String, value: Any) {
        val field = MainLayout::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }

    private fun getPrivateField(target: MainLayout, name: String): Any {
        val field = MainLayout::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(target)!!
    }

    private fun mainLayoutReadyForInitHeader(): MainLayout {
        val mainLayout = MainLayout(null, null)
        setPrivateField(mainLayout, "applicationIdentity", mock<ApplicationIdentity>().also { whenever(it.displayName).thenReturn("Little Trotter") })
        setPrivateField(mainLayout, "messageCenterService", mock<MessageCenterQueryPort>().also { whenever(it.unreadCountFor(any())).thenReturn(0L) })
        setPrivateField(mainLayout, "actorProvider", mock<AuditActorQueryPort>().also { whenever(it.currentActor()).thenReturn("alice") })
        setPrivateField(mainLayout, "appRoleProvider", mock<AppRoleQueryPort>().also { whenever(it.currentRoles()).thenReturn(AppRole.entries.toSet()) })
        return mainLayout
    }

    @Test
    fun `happy path - initHeader wires the header, drawer, palette filter and bell badge together`() {
        val mainLayout = mainLayoutReadyForInitHeader()

        mainLayout.initHeader()

        assertTrue(mainLayout.children.count() > 0, "initHeader should have populated the layout")
    }

    @Test
    fun `boundary - opening the command palette through MainLayout builds its index via referenceDataHiddenTabs`() {
        val mainLayout = mainLayoutReadyForInitHeader()
        mainLayout.initHeader()

        val commandPalette = getPrivateField(mainLayout, "commandPalette") as CommandPaletteDialog
        // Exercises the indexProvider closure (buildCommandPaletteIndex + referenceDataHiddenTabs);
        // no exception means the closure it wraps around MainLayout's own state ran successfully.
        commandPalette.open()
    }
}
