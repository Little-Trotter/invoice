package de.littletrotter.ui.view.settings

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.H3
import com.vaadin.flow.component.html.Paragraph
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.EmailField
import de.littletrotter.application.mail.port.inbound.ArchiveHandoverSettings
import de.littletrotter.application.mail.port.inbound.ArchiveHandoverSettingsQueryPort
import de.littletrotter.application.mail.port.inbound.ArchiveHandoverSettingsUpdate
import de.littletrotter.application.mail.port.inbound.UpdateArchiveHandoverSettingsUseCase
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.i18n.TranslationProvider
import de.littletrotter.ui.notification.UiNotifications
import java.time.Instant

/**
 * The settings card for handing a copy of every sent mail to an external mail archive: the archive
 * address and one switch each for invoices and offers. The component stores the whole setting at
 * once, so the card saves it with an explicit button instead of per field. A failed read leaves the
 * form empty and disabled rather than showing defaults that were never stored; without the right
 * to change it the fields are read-only and the button is absent.
 */
class ArchiveHandoverSettingsCard(
    private val query: ArchiveHandoverSettingsQueryPort,
    private val updater: UpdateArchiveHandoverSettingsUseCase,
    private val mayEdit: Boolean,
    private val lastChange: (Instant?, String?) -> Component,
) : Div(), TranslationProvider {

    internal val address = EmailField(tr("settings.archiveHandover.address"))
    internal val invoiceSwitch = Checkbox(tr("settings.archiveHandover.invoices"))
    internal val quoteSwitch = Checkbox(tr("settings.archiveHandover.quotes"))
    internal val saveButton = AppButtons.primary(tr("app.save")) { save() }
    private val meta = Div().apply { addClassName("settings-meta") }

    override fun getComponent(): Component = this

    init {
        setId("settings-card-archive-handover")
        addClassName("settings-card")
        add(Div().apply { addClassName("settings-rail") })
        add(content())
        load()
    }

    private fun content(): VerticalLayout {
        address.apply {
            setId("settings-archive-handover-address")
            helperText = tr("settings.archiveHandover.address.helper")
            setWidthFull()
            isClearButtonVisible = true
        }
        listOf(invoiceSwitch, quoteSwitch).forEach { it.element.themeList.add("toggle"); it.addClassName("settings-switch") }
        invoiceSwitch.setId("settings-archive-handover-invoices")
        quoteSwitch.setId("settings-archive-handover-quotes")
        saveButton.setId("settings-archive-handover-save")
        return VerticalLayout(
            H3(tr("settings.archiveHandover.title")).apply { addClassName("settings-card-title") },
            Paragraph(tr("settings.archiveHandover.description")).apply { addClassName("settings-card-description") },
            address,
            invoiceSwitch,
            quoteSwitch,
            actions(),
            meta,
        ).apply { isPadding = false; isSpacing = false }
    }

    private fun actions(): HorizontalLayout =
        HorizontalLayout(saveButton).apply {
            setWidthFull()
            justifyContentMode = FlexComponent.JustifyContentMode.END
            isVisible = mayEdit
        }

    private fun load() {
        val settings = runCatching { query.settings() }
            .onFailure { UiNotifications.error(tr("settings.archiveHandover.loadFailed", it.message ?: it.javaClass.simpleName)) }
            .getOrNull()
        show(settings)
        val editable = mayEdit && settings != null
        listOf(address, invoiceSwitch, quoteSwitch, saveButton).forEach { it.isEnabled = editable }
    }

    private fun show(settings: ArchiveHandoverSettings?) {
        address.value = settings?.address.orEmpty()
        invoiceSwitch.value = settings?.invoiceEnabled ?: true
        quoteSwitch.value = settings?.quoteEnabled ?: true
        meta.removeAll()
        meta.add(lastChange(settings?.updatedAt, settings?.updatedBy))
    }

    internal fun save() {
        if (address.isInvalid) {
            UiNotifications.error(tr("settings.archiveHandover.addressInvalid"))
            return
        }
        runCatching {
            updater.update(ArchiveHandoverSettingsUpdate(address.value.trim().ifEmpty { null }, invoiceSwitch.value, quoteSwitch.value))
        }.onSuccess {
            show(it)
            UiNotifications.confirmOn(saveButton)
        }.onFailure {
            UiNotifications.error(tr("settings.saveFailed") + " " + (it.message ?: it.javaClass.simpleName))
        }
    }
}
