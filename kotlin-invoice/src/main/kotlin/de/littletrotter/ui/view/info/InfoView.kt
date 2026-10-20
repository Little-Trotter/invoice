package de.littletrotter.ui.view.info

import com.vaadin.flow.component.UI
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import de.littletrotter.application.system.port.inbound.ApplicationIdentityQueryPort
import de.littletrotter.ui.MainLayout
import de.littletrotter.ui.component.PageShell
import de.littletrotter.ui.i18n.UiTranslations
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty

@Route(value = "info", layout = MainLayout::class)
@PageTitle("Hinweise")
class InfoView
@Inject
constructor(
    @param:ConfigProperty(name = "quarkus.application.version", defaultValue = "unknown")
    private val appVersion: String,
    applicationIdentity: ApplicationIdentityQueryPort,
) : VerticalLayout() {

    init {
        isPadding = true
        isSpacing = true
        add(PageShell(UiTranslations.tr(this, "info.title")).narrow().content(DisclaimerPanel(appVersion, applicationIdentity.displayName)))
        updatePageTitle()
    }

    private fun updatePageTitle() {
        val ui = UI.getCurrent() ?: return
        ui.page.setTitle("${UiTranslations.tr(this, "info.title")} | ${UiTranslations.tr(this, "app.title")}")
    }
}
