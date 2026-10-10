package de.littletrotter.ui.view.settings

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.FlexComponent.JustifyContentMode
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.H3
import com.vaadin.flow.component.html.Paragraph
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.radiobutton.RadioButtonGroup
import com.vaadin.flow.component.textfield.IntegerField
import de.littletrotter.application.quote.port.inbound.QuoteSettings
import de.littletrotter.application.quote.port.inbound.QuoteSettingsQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteXmlFormat
import de.littletrotter.application.quote.port.inbound.UpdateQuoteSettingsUseCase
import de.littletrotter.application.settings.FeatureSettingsGate
import de.littletrotter.application.shared.AuditActorProvider
import de.littletrotter.domain.settings.FeatureFlag
import de.littletrotter.ui.i18n.TranslationProvider
import de.littletrotter.ui.notification.UiNotifications

internal const val MIN_VALIDITY_DAYS = 1
internal const val MAX_VALIDITY_DAYS = 365

/** The runtime switch of the offer module: its state and the write on behalf of the acting user. */
class QuoteSwitch(private val gate: FeatureSettingsGate, private val actorProvider: AuditActorProvider) {

    fun isOn(): Boolean = gate.isEnabled(FeatureFlag.QUOTE)

    fun set(on: Boolean) = gate.setEnabled(FeatureFlag.QUOTE, on, actorProvider.currentActor())
}

/**
 * The quote card of the settings page: the switch of the offer module, the default validity of new
 * quotes and the machine-readable format their PDFs carry. The card stays visible while the module
 * is off so it can be switched on again and the defaults prepared. Every client-initiated change is
 * written at once and reverted when the write fails; without the settings permission the fields
 * are read-only.
 */
class QuoteSettingsCard(
    private val settingsQuery: QuoteSettingsQueryPort,
    private val updateSettings: UpdateQuoteSettingsUseCase,
    private val moduleSwitch: QuoteSwitch,
    private val mayEdit: Boolean,
    private val lastChange: (java.time.Instant?) -> Component,
) : Div(), TranslationProvider {

    internal val validityDays = IntegerField(tr("quote.settings.validityDays"))
    internal val xmlFormat = RadioButtonGroup<QuoteXmlFormat>()
    private val meta = Div()
    internal val toggle = Checkbox()
    internal val chip = Span()

    private var current: QuoteSettings = loadSettings()

    override fun getComponent(): Component = this

    init {
        setId("settings-card-quote")
        addClassName("settings-card")
        add(Div().apply { addClassNames("settings-rail", "settings-rail-quote") })
        add(content())
        validityDays.addValueChangeListener { event ->
            if (event.isFromClient) onValidityChange(event.value)
        }
        xmlFormat.addValueChangeListener { event ->
            if (event.isFromClient) onFormatChange(event.value)
        }
        toggle.addValueChangeListener { event ->
            if (event.isFromClient) onSwitchChange(event.value)
        }
    }

    private fun loadSettings(): QuoteSettings =
        runCatching { settingsQuery.settings() }.getOrDefault(QuoteSettings(DEFAULT_VALIDITY_DAYS))

    private fun content(): VerticalLayout {
        configureFields()
        val on = moduleSwitch.isOn()
        toggle.apply {
            element.themeList.add("toggle")
            addClassName("settings-switch")
            setId("settings-switch-quote")
            value = on
            element.setAttribute("aria-label", tr(TITLE_KEY))
        }
        chip.setId("settings-status-quote")
        chip.addClassName("settings-chip")
        chip.showSwitchState(on)
        meta.apply {
            addClassName("settings-meta")
            setId("settings-meta-quote")
        }
        renderMeta()
        return VerticalLayout(
            HorizontalLayout(H3(tr(TITLE_KEY)).apply { addClassName("settings-card-title") }, toggle).apply {
                setWidthFull()
                justifyContentMode = JustifyContentMode.BETWEEN
                defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
                isPadding = false
                isSpacing = false
            },
            Paragraph(tr("settings.quote.description")).apply { addClassName("settings-card-description") },
            validityDays,
            xmlFormat,
            HorizontalLayout(chip).apply { isPadding = false },
            meta,
        ).apply { isPadding = false; isSpacing = false }
    }

    /** Listener body of [toggle], separate for the same reason as the field listeners below. */
    internal fun onSwitchChange(on: Boolean) {
        runCatching { moduleSwitch.set(on) }
            .onSuccess {
                chip.showSwitchState(on)
            }
            .onFailure { ex ->
                toggle.value = !on
                UiNotifications.error(tr("settings.saveFailed") + " " + (ex.message ?: ex.javaClass.simpleName))
            }
    }

    private fun configureFields() {
        validityDays.apply {
            setId("settings-quote-validity")
            min = MIN_VALIDITY_DAYS
            max = MAX_VALIDITY_DAYS
            isStepButtonsVisible = true
            helperText = tr("quote.settings.validityDays.helper")
            value = current.validityDays.toInt()
            isEnabled = mayEdit
        }
        xmlFormat.apply {
            setId("settings-quote-xml")
            label = tr("quote.settings.xmlFormat")
            setItems(QuoteXmlFormat.NONE, QuoteXmlFormat.UBL)
            setItemLabelGenerator { tr("quote.settings.xmlFormat.${it.name}") }
            value = current.xmlFormat
            addThemeName("lt-segmented")
            isEnabled = mayEdit
        }
    }

    private fun renderMeta() {
        meta.removeAll()
        meta.add(lastChange(current.updatedAt))
    }

    /** Listener bodies are separate so tests can drive them: a programmatic `value =` never
     *  looks client-initiated. */
    internal fun onValidityChange(days: Int?) {
        if (days == null || days !in MIN_VALIDITY_DAYS..MAX_VALIDITY_DAYS) {
            validityDays.value = current.validityDays.toInt()
            return
        }
        save(days.toLong(), current.xmlFormat) { validityDays.value = current.validityDays.toInt() }
    }

    internal fun onFormatChange(format: QuoteXmlFormat?) {
        if (format == null) {
            xmlFormat.value = current.xmlFormat
            return
        }
        save(current.validityDays, format) { xmlFormat.value = current.xmlFormat }
    }

    private fun save(days: Long, format: QuoteXmlFormat, revert: () -> Unit) {
        runCatching { updateSettings.update(days, format) }
            .onSuccess {
                current = QuoteSettings(days, format, loadSettings().updatedAt)
                renderMeta()
            }
            .onFailure { ex ->
                revert()
                UiNotifications.error(tr("settings.saveFailed") + " " + (ex.message ?: ex.javaClass.simpleName))
            }
    }

    private companion object {
        const val DEFAULT_VALIDITY_DAYS = 30L
        const val TITLE_KEY = "settings.quote.title"
    }
}
