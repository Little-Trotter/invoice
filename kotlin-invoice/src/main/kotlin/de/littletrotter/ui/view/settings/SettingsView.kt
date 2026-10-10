package de.littletrotter.ui.view.settings

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.H2
import com.vaadin.flow.component.html.H3
import com.vaadin.flow.component.html.Paragraph
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.FlexComponent.JustifyContentMode
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.radiobutton.RadioButtonGroup
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import de.littletrotter.application.mail.MailAvailability
import de.littletrotter.application.settings.FeatureSettingsGate
import de.littletrotter.application.settings.WordingSettingsGate
import de.littletrotter.application.shared.AuditActorProvider
import de.littletrotter.domain.settings.FeatureFlag
import de.littletrotter.shared.GermanTime
import de.littletrotter.shared.wording.Term
import de.littletrotter.shared.wording.TermResolver
import de.littletrotter.shared.wording.WordingProfile
import de.littletrotter.ui.MainLayout
import de.littletrotter.ui.component.PageShell
import de.littletrotter.ui.i18n.TranslationProvider
import de.littletrotter.ui.i18n.TranslationService
import de.littletrotter.ui.notification.UiNotifications
import jakarta.inject.Inject
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

private const val SETTINGS_CARD_DESCRIPTION = "settings-card-description"

/**
 * SFS — "System → Einstellungen": the feature switches an administrator can flip at runtime,
 * [FeatureFlag.PER_DIEM_UI], [FeatureFlag.EMAIL_SEND] and [FeatureFlag.EXTENDED_PROFILE]. One card
 * per flag — a hairline-framed
 * Bauhaus panel with a colored rail, the flag's name, a toggle switch in the title row, its
 * description, a status chip and a last-changed line. There is no save button: the flip
 * **is** the save, same as `ValidationSettingsTab`.
 *
 * The [FeatureFlag.EMAIL_SEND] card is the one piece of state on this page the flag itself doesn't
 * own: whether the mail component is deployed at all comes from [MailAvailability]
 * (`mail.enabled`). With the component not deployed the card is not rendered at all — the app
 * shows nothing about e-mail. The persisted flag value is untouched, so a later
 * `MAIL_ENABLED=true` finds the administrator's standing decision in place.
 *
 * Access is `SYSTEM_VIEW` via `RouteAccess` (the drawer/breadcrumb render through
 * [MainLayout.visibleNavItems], the hard guard through
 * [de.littletrotter.ui.security.NavigationAccessGuard]) — the route needs no
 * [de.littletrotter.application.security.RequiresPermission] annotation of its own; that
 * convention here belongs to business services, not views.
 */
@Route(value = "system/einstellungen", layout = MainLayout::class)
@PageTitle("Einstellungen")
class SettingsView @Inject constructor(
    private val gate: FeatureSettingsGate,
    mailAvailability: MailAvailability,
    private val actorProvider: AuditActorProvider,
    private val wordingGate: WordingSettingsGate,
    quoteCards: QuoteSettingsCardFactory,
    archiveHandoverCards: ArchiveHandoverSettingsCardFactory,
) : VerticalLayout(), TranslationProvider {

    override fun getComponent(): Component = this

    // internal, not private: Karibu tests drive the wording switch by its real generic type;
    // it lives by id in the rendered tree, but finding it that way would still need an unchecked
    // cast back to RadioButtonGroup<WordingProfile> to undo the erasure. Set once, by wordingCard.
    internal lateinit var wordingSwitch: RadioButtonGroup<WordingProfile>

    init {
        addClassName("settings-view")
        isPadding = false
        isSpacing = false
        addClassName("lt-page-centered")

        val page = PageShell(tr("settings.title"), tr("settings.subtitle")).narrow()
        page.content(sectionLabel(tr("settings.section.funktionen")))
        page.content(card(FeatureFlag.PER_DIEM_UI))
        if (mailAvailability.enabled) page.content(card(FeatureFlag.EMAIL_SEND))
        page.content(card(FeatureFlag.EXTENDED_PROFILE))
        quoteCards.create { instant -> lastChangeMetaText(instant?.atZone(GermanTime.ZONE)?.toOffsetDateTime(), null) }
            ?.let {
                page.content(sectionLabel(tr("settings.section.angebot")))
                page.content(it)
            }
        archiveHandoverCards.create { instant, name -> lastChangeMetaText(instant?.atZone(GermanTime.ZONE)?.toOffsetDateTime(), name) }
            ?.let {
                page.content(sectionLabel(tr("settings.section.mailArchive")))
                page.content(it)
            }
        page.content(sectionLabel(tr("settings.section.wording")))
        page.content(wordingCard())
        add(page)
    }

    /** A section eyebrow, semantically a real heading (h2) — was a plain styled `Span` before,
     *  invisible to a screen reader's heading navigation despite looking like one. */
    private fun sectionLabel(text: String): H2 = H2(text).apply { addClassName("settings-section-label") }

    /** A card's own title, semantically h3 (page h1 -> section h2 -> card h3) — same reasoning
     *  as [sectionLabel]. */
    private fun cardTitle(text: String): H3 = H3(text).apply { addClassName("settings-card-title") }

    /** One Bauhaus card for [flag]; rail/title/description/chip/meta plus the toggling listener.
     *  Chip and meta-lines are kept as locals so the success path updates exactly this card. */
    /** A card's assembled interactive parts, bundled so [cardContent] stays under
     *  detekt's `LongParameterList` threshold. */
    internal data class CardWidgets(val toggle: Checkbox, val chip: Span, val meta: Div)

    private fun card(flag: FeatureFlag): Div {
        val suffix = idSuffix(flag)
        val enabled = gate.isEnabled(flag)
        val widgets = CardWidgets(
            toggle = cardToggle(flag, suffix, enabled),
            chip = cardChip(suffix, enabled),
            meta = cardMeta(flag, suffix),
        )

        return Div().apply {
            setId("settings-card-$suffix")
            addClassName("settings-card")
            add(cardRail(flag))
            add(cardContent(flag, widgets))
        }.also {
            widgets.toggle.addValueChangeListener { event ->
                // Programmatic writes (init, revert) must not re-enter the persistence path —
                // the same isFromClient rule ValidationSettingsTab learned the hard way.
                if (!event.isFromClient) return@addValueChangeListener
                onSwitchChange(flag, event.value, widgets)
            }
        }
    }

    private fun cardChip(suffix: String, enabled: Boolean): Span = Span().apply {
        setId("settings-status-$suffix")
        addClassName("settings-chip")
        showSwitchState(enabled)
    }

    private fun cardMeta(flag: FeatureFlag, suffix: String): Div = Div().apply {
        addClassName("settings-meta")
        setId("settings-meta-$suffix")
        add(lastChangeText(flag))
    }

    private fun cardToggle(flag: FeatureFlag, suffix: String, enabled: Boolean): Checkbox = Checkbox().apply {
        // Lumo renders the Checkbox as a toggle switch for theme="toggle" — the same
        // attribute the CSS block expects under `.settings-switch`.
        element.themeList.add("toggle")
        addClassName("settings-switch")
        setId("settings-switch-$suffix")
        value = enabled
        element.setAttribute("aria-label", tr(flag.labelKey))
    }

    private fun cardRail(flag: FeatureFlag): Div = Div().apply {
        addClassName("settings-rail")
        addClassName(
            when (flag) {
                FeatureFlag.PER_DIEM_UI -> "settings-rail-perdiem"
                FeatureFlag.EMAIL_SEND -> "settings-rail-email"
                FeatureFlag.QUOTE -> "settings-rail-quote"
                FeatureFlag.EXTENDED_PROFILE -> "settings-rail-extendedprofile"
            },
        )
    }

    private fun cardContent(flag: FeatureFlag, widgets: CardWidgets): VerticalLayout =
        VerticalLayout(
            HorizontalLayout(
                cardTitle(tr(flag.labelKey)),
                widgets.toggle,
            ).apply {
                setWidthFull()
                justifyContentMode = JustifyContentMode.BETWEEN
                defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
                isPadding = false
                isSpacing = false
            },
            Paragraph(tr(flag.descriptionKey)).apply { addClassName(SETTINGS_CARD_DESCRIPTION) },
            HorizontalLayout(widgets.chip).apply {
                isPadding = false
                addClassName("lt-gap-050")
            },
            widgets.meta,
        ).apply { isPadding = false; isSpacing = false }

    /**
     * SFS — the listener's body, extracted: Vaadin's `isFromClient` guard makes a pure unit test
     * of the flip impossible (a programmatic `value =` never looks client-initiated), so this is
     * the seam the Karibu test drives directly — mirroring the decision
     * `ValidationSettingsTabTest.applyEngineActivation` documents for the same problem.
     */
    internal fun onSwitchChange(flag: FeatureFlag, newValue: Boolean, widgets: CardWidgets) {
        runCatching { gate.setEnabled(flag, newValue, actorProvider.currentActor()) }
            .onSuccess {
                widgets.chip.showSwitchState(newValue)
                widgets.meta.removeAll()
                widgets.meta.add(lastChangeText(flag))
            }
            .onFailure { ex ->
                widgets.toggle.value = !newValue
                UiNotifications.error(tr("settings.saveFailed") + " " + (ex.message ?: ex.javaClass.simpleName))
            }
    }

    private fun lastChangeText(flag: FeatureFlag): Component {
        val row = gate.lastChanged(flag)
        return lastChangeMetaText(row?.updatedAt, row?.updatedBy)
    }

    /**
     * The wording-profile card: a [RadioButtonGroup] painted as a two-segment switch
     * (`lt-segmented`, same idiom `InvoiceListExportDialog`'s export-format switch established),
     * not a [Checkbox] — the setting has two named options, not an on/off state. Live: every
     * client-initiated selection writes straight through [WordingSettingsGate.setProfile] and
     * shows a toast, reverting the selection on failure — the same isFromClient/revert contract
     * [onSwitchChange] uses for the feature flags above.
     */
    private fun wordingCard(): Div {
        val meta = Div().apply {
            addClassName("settings-meta")
            setId("settings-meta-wording")
            add(wordingLastChangeText())
        }
        val preview = Paragraph().apply {
            setId(WORDING_PREVIEW_ID)
            addClassName(SETTINGS_CARD_DESCRIPTION)
            text = wordingPreviewText(wordingGate.currentProfile())
        }
        val switch = RadioButtonGroup<WordingProfile>().apply {
            setId(WORDING_SWITCH_ID)
            setItems(WordingProfile.HANDWERK, WordingProfile.HANDEL, WordingProfile.BERATUNG)
            setItemLabelGenerator { profile -> tr(wordingOptionKey(profile)) }
            value = wordingGate.currentProfile()
            addThemeName("lt-segmented")
            addClassName("settings-wording-switch")
            element.setAttribute("aria-label", tr("settings.wording.title"))
        }
        wordingSwitch = switch
        // A two-way NAMED choice reads better on its own row than squeezed next to the title
        // (unlike the plain on/off cards above): the title carries a small leading icon (a
        // Material habit for a settings section that changes VOCABULARY, not a feature) and the
        // segmented switch gets a full row of its own, wide enough for a comfortable tap target.
        val titleRow = HorizontalLayout(
            VaadinIcon.TAGS.create().apply { addClassName("settings-card-title-icon") },
            cardTitle(tr("settings.wording.title")),
        ).apply {
            isPadding = false
            addClassName("lt-gap-060")
            defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
        }
        return Div().apply {
            setId("settings-card-wording")
            addClassName("settings-card")
            add(
                Div().apply {
                    addClassName("settings-rail")
                    addClassName("settings-rail-wording")
                },
            )
            add(
                VerticalLayout(
                    titleRow,
                    Paragraph(tr("settings.wording.description")).apply { addClassName(SETTINGS_CARD_DESCRIPTION) },
                    switch,
                    preview,
                    meta,
                ).apply { isPadding = false; isSpacing = false },
            )
        }.also {
            switch.addValueChangeListener { event ->
                if (!event.isFromClient) return@addValueChangeListener
                onWordingChange(event.value, switch, meta)
                preview.text = wordingPreviewText(wordingGate.currentProfile())
            }
        }
    }

    /** The term the chosen profile actually renders for a sellable position, so the switch shows
     *  its effect instead of an abstract profile name. A business trip is called "Dienstreise" in
     *  every profile (PO decision 13.09.2026), so this preview has only the one noun left to show. */
    internal fun wordingPreviewText(profile: WordingProfile): String {
        val termLocale = if (locale.language == "en") TranslationService.ENGLISH else TranslationService.GERMAN
        val goods = TermResolver.resolve(profile, termLocale, Term.PRODUCT_PLURAL).orEmpty()
        return tr("settings.wording.preview", goods)
    }

    /** [wordingCard]'s listener body, extracted for the same Karibu-testability reason
     *  [onSwitchChange] documents: a programmatic `value =` never looks client-initiated. */
    internal fun onWordingChange(newProfile: WordingProfile, switch: RadioButtonGroup<WordingProfile>, meta: Div) {
        val previous = wordingGate.currentProfile()
        runCatching { wordingGate.setProfile(newProfile, actorProvider.currentActor()) }
            .onSuccess {
                meta.removeAll()
                meta.add(wordingLastChangeText())
            }
            .onFailure { ex ->
                switch.value = previous
                UiNotifications.error(tr("settings.saveFailed") + " " + (ex.message ?: ex.javaClass.simpleName))
            }
    }

    private fun wordingLastChangeText(): Component {
        return lastChangeMetaText(wordingGate.lastChanged()?.updatedAt, wordingGate.lastChanged()?.updatedBy)
    }

    /** Shared meta-line for a feature-flag or wording-profile last-change — the same
     *  Paragraph + [settings-meta-text] shape both [lastChangeText] and [wordingLastChangeText]
     *  used to write independently. */
    private fun lastChangeMetaText(updatedAt: OffsetDateTime?, updatedBy: String?): Component {
        return if (updatedAt == null) {
            Paragraph(tr("settings.meta.nie")).apply { addClassName("settings-meta-text") }
        } else {
            val text = if (updatedBy.isNullOrBlank()) {
                tr("settings.meta.zuletztOhneName", TIMESTAMP.format(updatedAt))
            } else {
                tr("settings.meta.zuletzt", TIMESTAMP.format(updatedAt), updatedBy)
            }
            Paragraph(text).apply { addClassName("settings-meta-text") }
        }
    }

    /** The card-anchored id suffix + toast wording — one spelling that Playwright and the Karibu
     *  tests both key on. Pure, mirror-able in the settings view tests. */
    internal companion object {
        private val TIMESTAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(GermanTime.ZONE)

        internal const val WORDING_SWITCH_ID = "settings-wording-switch"
        internal const val WORDING_PREVIEW_ID = "settings-wording-preview"

        internal fun idSuffix(flag: FeatureFlag): String = when (flag) {
            FeatureFlag.PER_DIEM_UI -> "perdiem"
            FeatureFlag.EMAIL_SEND -> "email"
            FeatureFlag.QUOTE -> "quote"
            FeatureFlag.EXTENDED_PROFILE -> "extendedProfile"
        }

        internal fun wordingOptionKey(profile: WordingProfile): String = when (profile) {
            WordingProfile.HANDWERK -> "settings.wording.option.handwerk"
            WordingProfile.HANDEL -> "settings.wording.option.handel"
            WordingProfile.BERATUNG -> "settings.wording.option.beratung"
        }
    }
}
