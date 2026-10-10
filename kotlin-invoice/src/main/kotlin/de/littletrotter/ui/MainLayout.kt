package de.littletrotter.ui

import com.vaadin.flow.component.AttachEvent
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.DetachEvent
import com.vaadin.flow.component.Key
import com.vaadin.flow.component.KeyModifier
import com.vaadin.flow.component.Shortcuts
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.applayout.AppLayout
import com.vaadin.flow.component.applayout.DrawerToggle
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.sidenav.SideNav
import com.vaadin.flow.component.sidenav.SideNavItem
import com.vaadin.flow.router.AccessDeniedException
import com.vaadin.flow.router.AfterNavigationEvent
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.BeforeEnterObserver
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.AfterNavigationObserver
import com.vaadin.flow.server.Command
import de.littletrotter.application.ApplicationIdentity
import de.littletrotter.application.mail.MailAvailability
import de.littletrotter.application.quote.QuoteAvailability
import de.littletrotter.application.message.MessageCenterService
import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.AppRoleProvider
import de.littletrotter.application.security.permissions
import de.littletrotter.application.settings.FeatureOffers
import de.littletrotter.application.settings.FeatureSettingsGate
import de.littletrotter.application.shared.AuditActorProvider
import de.littletrotter.domain.settings.FeatureFlag
import de.littletrotter.ui.component.ActionIcons
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.i18n.TranslationProvider
import de.littletrotter.ui.i18n.TranslationService
import de.littletrotter.ui.navigation.buildCommandPaletteIndex
import de.littletrotter.ui.view.referencedata.hiddenReferenceDataTabs
import de.littletrotter.ui.navigation.CommandPaletteDialog
import de.littletrotter.ui.navigation.NavBreadcrumb
import de.littletrotter.ui.navigation.NavItem
import de.littletrotter.ui.navigation.NavItem.Companion.childItemsOf
import de.littletrotter.ui.navigation.NavItem.Companion.drawerParent
import de.littletrotter.ui.navigation.NavSection
import de.littletrotter.ui.navigation.ReferenceDataSelectionEvent
import de.littletrotter.ui.security.NavigationAccessGuard
import de.littletrotter.ui.security.RouteAccess
import de.littletrotter.ui.view.invoice.InvoiceListView
import de.littletrotter.ui.view.message.MessageCenterView
import de.littletrotter.ui.view.referencedata.ReferenceDataGroup
import de.littletrotter.ui.view.referencedata.ReferenceDataTab
import de.littletrotter.ui.view.referencedata.referenceDataRoute
import jakarta.annotation.PostConstruct
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject

/**
 * TS-8 — every routed view uses this as its parent layout (`layout = MainLayout::class`,
 * including [de.littletrotter.ui.view.system.SbomView] since TS-8 gave it one so its `sbom`
 * route is guarded too — see that class's KDoc). Vaadin calls [BeforeEnterObserver.beforeEnter]
 * on every element of a navigation's route-target chain, parent layouts included, so
 * implementing it here is a single enforcement point for the whole app instead of one guard
 * per view.
 *
 * MEN-2/TS-8 — the drawer, the breadcrumb ([NavBreadcrumb]) and the command palette
 * ([CommandPaletteDialog]) all render the same `NavigationModel`
 * tree, filtered through the same permission rule ([visibleNavItems]): an entry the caller lacks
 * permission for is hidden everywhere at once — never visible in one surface and hidden in
 * another.
 */
class MainLayout @Inject constructor(
    /**
     * Whether the mail component is part of this instance, i.e. whether the EMAIL
     * drawer/palette entry exists at all.
     *
     * Deliberately a nullable constructor parameter rather than a required dependency: the many
     * existing component tests construct `MainLayout(null, null)` and reflection-inject only the
     * collaborators they exercise (see `MainLayoutDrawerKaribuTest`'s own KDoc). A non-nullable
     * parameter would break every one of them on first drawer build; `null` reads as "component
     * absent" ([mailAvailabilityPredicate]), which is the same thing an instance without the
     * component sees. Production CDI always injects the real bean — [MailAvailability] is
     * unconditionally an `@ApplicationScoped` bean and merely reports `enabled=false` when the
     * feature is off.
     *
     * No default value here (unlike a plain nullable field): Kotlin would otherwise also emit a
     * public no-arg constructor once every constructor parameter carries a default, and Quarkus
     * ArC then rejects the class outright ("Multiple @Inject constructors found") because it sees
     * that synthetic no-arg constructor alongside this `@Inject`-annotated one. Every caller,
     * production and test alike, passes both arguments explicitly instead.
     */
    private val mailAvailability: MailAvailability?,
    /**
     * SFS — the second, fachlich axis the EMAIL entry is asked about: even a deployed mail
     * component stays un-offered while an admin switched [FeatureFlag.EMAIL_SEND] off. Nullable
     * with the same reasoning as [mailAvailability] above (bare `MainLayout(null, null)` component
     * tests): `null` reads as "flag on", so those tests keep seeing the deployment state alone.
     */
    private val featureSettingsGate: FeatureSettingsGate?,

) : AppLayout(), BeforeEnterObserver, AfterNavigationObserver, TranslationProvider {

    @Inject
    private lateinit var applicationIdentity: ApplicationIdentity

    /**
     * Whether the offer component is part of this instance. A nullable field instead of a third
     * constructor parameter so the many component tests that build `MainLayout(null, null)` stay
     * valid; `null` reads as "component absent", like [mailAvailability].
     */
    @Inject
    internal var quoteAvailability: QuoteAvailability? = null

    // The bell's unread badge: refreshed at construction, on every navigation ([afterNavigation])
    // and live while attached. Background job outcomes no longer raise a toast, so the badge is the
    // only signal for a message that arrives while the user stays on one page; the event bus
    // reports it only after the message's transaction committed.
    @Inject
    private lateinit var messageCenterService: MessageCenterService

    @Inject
    private lateinit var actorProvider: AuditActorProvider

    /** `null` reads as "no live updates" for the many component tests that build the layout bare. */
    @Inject
    internal var uiEventBus: UiEventBus? = null

    private var messageRegistration: UiEventBus.Registration? = null

    @Inject
    private lateinit var appRoleProvider: AppRoleProvider

    private val bellBadge = Span().also {
        it.addClassName("bell-badge")
        it.isVisible = false
    }
    private lateinit var bellButton: Button

    // MEN-2 — the REFERENCE_DATA drawer node; kept as a field so afterNavigation can drive its
    // expanded state explicitly (see referenceDataExpandedFor). This is a belt-and-braces
    // server-side push on top of SideNavItem's own client-side auto-expand (which runs on every
    // browser navigation but is invisible to CDI-free/no-JS unit tests). Nullable: TS-8 hides
    // the node entirely for callers without REFERENCE_DATA_EDIT permission.
    private var referenceDataNavItem: SideNavItem? = null

    // SFS — the SYSTEM drawer node once SETTINGS was added as its child (see
    // createGroupedNavItem); kept as a field so afterNavigation can push its expanded state the
    // same way MEN-2 drives referenceDataNavItem. Nullable because TS-8 hides the whole node for
    // callers without SYSTEM_VIEW.
    private var systemNavItem: SideNavItem? = null

    // MEN-4 — the breadcrumb in the navbar, tracking current route and reference-data selection.
    private var navBreadcrumb: NavBreadcrumb? = null
    private var currentRefDataGroup: ReferenceDataGroup? = null
    // UI-1a — genuinely nullable selection state, not construction-order nullability: null until
    // onReferenceDataSelection first fires (e.g. any route outside referenzdaten/ never sets it).
    private var currentRefDataTab: ReferenceDataTab? = null
    private var currentPath: String = ""
    private lateinit var breadcrumbContainer: Div

    // MEN-5 — the "Gehe zu …" command palette; no CDI dependencies, so it's safe to construct
    // eagerly here. Its index is filtered per open through [visibleNavItems] (TS-8), wired in
    // [initHeader] once the injected role provider is available.
    private val commandPalette = CommandPaletteDialog(
        // SFS — the palette's per-reference-data-tab entries must follow the feature flags too
        // ("hidden, not disabled", everywhere at once). [indexProvider] is re-read on every
        // open (see CommandPaletteDialog KDoc), and the lambda only touches
        // [featureSettingsGate] at call time — so the property-initializer-before-injection
        // problem of @Inject fields does not apply here; `null` still just reads "nothing hidden".
        indexProvider = {
            buildCommandPaletteIndex(hiddenReferenceDataTabs = referenceDataHiddenTabs())
        },
    )

    init {
        ensureLocale()
        createSkipLink()
        registerCommandPaletteShortcut()
    }

    // TS-8 — createDrawer() runs here, not in init{}: it filters menu entries by permission
    // (RouteAccess.permissionFor), which needs appRoleProvider — CDI field injection runs after
    // the constructor/init block completes, so init{} is too early to read an @Inject field.
    // `internal`, not `private`: detekt's UnusedPrivateMember has no framework-lifecycle
    // exemption and cannot see the container's @PostConstruct invocation.
    @PostConstruct
    internal fun initHeader() {
        createHeader()
        createDrawer()
        commandPalette.itemFilter = { item -> item in visibleNavItems(item.section, grantedPermissions(), availabilityPredicate()) }
        refreshBellBadge()
    }

    private fun grantedPermissions(): Set<AppPermission> = appRoleProvider.currentRoles().permissions()

    /** This layout's availability axis for [visibleNavItems]; see
     *  [mailAvailabilityPredicate]. */
    /** SFS — the reference-data tabs hidden by the current feature state, shared rule with
     *  [de.littletrotter.ui.view.referencedata.ReferenceDataView]; palette entries only. */
    private fun referenceDataHiddenTabs(): Set<ReferenceDataTab> =
        hiddenReferenceDataTabs(
            perDiemUiEnabled = featureSettingsGate?.isEnabled(FeatureFlag.PER_DIEM_UI) ?: true,
        )

    private fun availabilityPredicate(): (NavItem) -> Boolean {
        val mail = mailAvailabilityPredicate(
            mailAvailability,
            // SFS — null gate (bare component test wiring) reads "flag on": deployment-only
            // availability, the pre-SFS behaviour those tests were written against.
            featureSettingsGate?.isEnabled(FeatureFlag.EMAIL_SEND) ?: true,
        )
        val quote = quoteAvailabilityPredicate(quoteAvailability)
        return { item -> mail(item) && quote(item) }
    }

    /** MEN-5 — Ctrl+K/Cmd+K opens the command palette from anywhere in the app; registered once
     *  here (not per-view) since [MainLayout] itself stays attached for the app's lifetime. Two
     *  separate shortcuts (not one call with both modifiers, which would require BOTH pressed at
     *  once) so either the Windows/Linux or the macOS chord works. */
    private fun registerCommandPaletteShortcut() {
        // Explicit `Command {}` disambiguates against the `ShortcutEventListener` overload
        // (same idiom as `InvoiceDetailViewEinvoiceTab`'s Enter shortcut) — a bare lambda is
        // ambiguous between the two single-abstract-method types.
        Shortcuts.addShortcutListener(this, Command { commandPalette.open() }, Key.KEY_K, KeyModifier.CONTROL)
        Shortcuts.addShortcutListener(this, Command { commandPalette.open() }, Key.KEY_K, KeyModifier.META)
    }

    private fun editionLabel(): String = applicationIdentity.displayName

    /**
     * TS-8 — the Vaadin-wiring half of the navigation guard: [NavigationAccessGuard.evaluate] (the
     * tested decision core) decides, this just applies the [NavigationAccessGuard.Decision] to the
     * event. [InvoiceListView] is the view bound to the root route (`""`), so it's the only
     * redirect target the root-redirect special case ever applies to.
     */
    override fun beforeEnter(event: BeforeEnterEvent) {
        when (val decision = NavigationAccessGuard.evaluate(event.navigationTarget, appRoleProvider.currentRoles(), InvoiceListView::class.java)) {
            is NavigationAccessGuard.Decision.Allow -> Unit
            is NavigationAccessGuard.Decision.RedirectTo -> event.forwardTo(decision.target)
            is NavigationAccessGuard.Decision.Deny ->
                event.rerouteToError(AccessDeniedException::class.java, decision.reason)
        }
    }

    override fun afterNavigation(event: AfterNavigationEvent) {
        content?.element?.setAttribute("id", "main-content")
        applyBrowserTitle(event)
        // Navigation refresh; messages arriving while the user stays on one view are handled by
        // the live registration in [onAttach].
        refreshBellBadge()
        // MEN-2 — keep the reference-data node's expanded state in sync with the current route
        // (the node is absent for callers without permission, TS-8).
        referenceDataNavItem?.isExpanded = referenceDataExpandedFor(event.location.path)
        // SFS — same for the SYSTEM node while the SETTINGS child is the active route.
        systemNavItem?.isExpanded = systemExpandedFor(event.location.path)
        // MEN-4 — rebuild the breadcrumb from the current route and reference-data selection.
        currentPath = event.location.path
        rebuildBreadcrumb(currentPath)
    }

    override fun onAttach(attachEvent: AttachEvent) {
        super.onAttach(attachEvent)
        val bus = uiEventBus ?: return
        messageRegistration?.unregister()
        // The actor is resolved here, on the request thread: the publishing thread has no identity
        // and would fall back to the system actor, i.e. count another user's messages.
        val actor = actorProvider.currentActor()
        val ui = attachEvent.ui
        messageRegistration = bus.registerMessageRecorded(ui) {
            val count = messageCenterService.unreadCountFor(actor)
            ui.access { if (isAttached) applyBellBadge(count) }
        }
    }

    override fun onDetach(detachEvent: DetachEvent) {
        messageRegistration?.unregister()
        messageRegistration = null
        super.onDetach(detachEvent)
    }

    /** [MessageCenterService.unreadCountFor] the current actor; hides the badge at 0. */
    private fun refreshBellBadge() {
        applyBellBadge(messageCenterService.unreadCountFor(actorProvider.currentActor()))
    }

    private fun applyBellBadge(count: Long) {
        val text = bellBadgeText(count)
        bellBadge.text = text ?: ""
        bellBadge.isVisible = text != null
        bellButton.element.setAttribute("aria-label", tr("messageCenter.title") + if (count > 0) " ($count)" else "")
    }

    /** Set the browser-tab title from the application identity plus the current view. */
    private fun applyBrowserTitle(event: AfterNavigationEvent) {
        val ui = UI.getCurrent() ?: return
        val viewTitle = event.locationChangeEvent.routeTargetChain.lastOrNull()
            ?.javaClass
            ?.getAnnotation(PageTitle::class.java)
            ?.value
        ui.page.setTitle(tabTitle(editionLabel(), viewTitle))
    }

    /** MEN-4 — rebuild the breadcrumb in the navbar from the current route and any cached
     *  reference-data group/tab selection. The breadcrumb is empty on unknown routes. */
    private fun rebuildBreadcrumb(path: String) {
        breadcrumbContainer.removeAll()
        // Only render if the path maps to a known route
        val segments = NavBreadcrumb.pathToSegments(path, currentRefDataGroup, currentRefDataTab)
        // UI-1a — navBreadcrumb itself stays nullable (MainLayoutFallbackBreadcrumbTest asserts
        // the empty-on-unknown-route case), but the `!!` right after constructing it here was
        // unreachable: build it into a local first so the non-null branch never needs asserting.
        navBreadcrumb = if (segments.isNotEmpty()) {
            NavBreadcrumb(path, currentRefDataGroup, currentRefDataTab).also { breadcrumbContainer.add(it) }
        } else {
            null
        }
    }

    /** MEN-4 — observe reference-data tab selection changes (which occur without a full router
     *  navigation event) and update the breadcrumb's terminal segment. The event is fired from
     *  `ReferenceDataView.applySelection` (private, so not linkable from here) whenever a tab
     *  or group is selected. */
    fun onReferenceDataSelection(@Observes event: ReferenceDataSelectionEvent) {
        currentRefDataGroup = event.group
        currentRefDataTab = event.tab
        // Rebuild breadcrumb with the new selection, using the last known path.
        rebuildBreadcrumb(currentPath)
    }

    companion object {
        /** Largest unread count the bell badge still shows verbatim; anything above renders as
         *  [MAX_BADGE_COUNT_LABEL] instead (see [bellBadgeText]). */
        private const val MAX_BADGE_COUNT = 99L
        private const val MAX_BADGE_COUNT_LABEL = "99+"

        /** `<display name> – <view title>`; the display name alone if no view title. */
        internal fun tabTitle(editionName: String, viewTitle: String?): String =
            if (viewTitle.isNullOrBlank()) editionName else "$editionName – $viewTitle"

        /** The bell badge's text, or `null` when it should be hidden: `null` at
         *  [unreadCount] `<= 0`, the count otherwise, capped at "99+" so a large backlog can't
         *  blow up the badge's width. Pure function, same "test the companion, not the CDI-wired
         *  component" idiom as [tabTitle]. */
        internal fun bellBadgeText(unreadCount: Long): String? = when {
            unreadCount <= 0L -> null
            unreadCount > MAX_BADGE_COUNT -> MAX_BADGE_COUNT_LABEL
            else -> unreadCount.toString()
        }

        /** MEN-2 — whether the drawer's REFERENCE_DATA node should render expanded for [path]:
         *  true whenever the current route lies inside the `referenzdaten/` tree, i.e. whenever
         *  it resolves to [NavItem.REFERENCE_DATA]. Delegates to [NavItem.activeItemFor] so the
         *  "which route belongs to reference data" rule stays defined once, in
         *  `NavigationModel`, not duplicated here. Pure function,
         *  same companion idiom as [bellBadgeText]. */
        internal fun referenceDataExpandedFor(path: String): Boolean =
            NavItem.activeItemFor(path) == NavItem.REFERENCE_DATA

        /** SFS — whether the SYSTEM drawer node renders expanded for [path]: true when the route
         *  resolves to the SETTINGS child (while /system itself the node is the current link and
         *  needs no expansion). Pure, same companion idiom as [referenceDataExpandedFor]. */
        internal fun systemExpandedFor(path: String): Boolean =
            NavItem.activeItemFor(path) == NavItem.SETTINGS

        /** MEN-2 — the `referenzdaten/<gruppe>` path a reference-data group's drawer child links
         *  to. Derived from [referenceDataRoute] (its first tab's route, group prefix only) so it
         *  can't drift from [de.littletrotter.ui.view.referencedata.REFERENCE_DATA_ROUTE_BASE]. */
        internal fun referenceDataGroupPath(group: ReferenceDataGroup): String =
            referenceDataRoute(group, ReferenceDataTab.forGroup(group).first()).substringBeforeLast('/')

        /**
         * TS-8/MEN-2 — "menu entries the user lacks permission for are hidden, not disabled",
         * applied at the navigation model instead of per rendered link: the [section]'s items
         * whose [NavItem.viewClass] the caller may reach per [RouteAccess.permissionFor]
         * ([RouteAccess]'s "no specific permission" `null` always passes). The single filter
         * point the drawer, the command palette and (transitively) every other model renderer
         * share. Pure function, same companion idiom as [bellBadgeText].
         *
         * The permission filter above, plus a second, orthogonal axis: **is this
         * entry's target part of this instance at all?**
         *
         * The concept's H-5 explicitly recommended extending *this* filter rather than adding a
         * second one, because the drawer, the command palette and the breadcrumb all read it — an
         * entry whose component is not installed then disappears from every surface at once,
         * which is the same "hidden, not disabled" rule permissions already follow. [isAvailable]
         * defaults to "everything is available" so every existing caller and test is unaffected;
         * only [MainLayout] itself passes the real predicate ([mailAvailabilityPredicate]).
         *
         */
        internal fun visibleNavItems(
            section: NavSection,
            granted: Set<AppPermission>,
            isAvailable: (NavItem) -> Boolean = { true },
        ): List<NavItem> =
            NavItem.itemsFor(section).filter { item ->
                val required = RouteAccess.permissionFor(item.viewClass)
                (required == null || required in granted) && isAvailable(item)
            }

        /**
         * The availability half of [visibleNavItems] for one [MailAvailability].
         *
         * A pure function next to [bellBadgeText], so "the EMAIL entry appears exactly when the
         * mail component is enabled" is testable without a Vaadin UI. `null` means the layout was
         * built outside CDI (component tests that never wire it) and is treated as "component
         * absent" — the conservative direction: a menu entry that leads nowhere is worse than a
         * missing one.
         *
         * SFS — [emailFlagEnabled] is the second, fachlich half of the AND ([FeatureOffers.mailOffered]):
         * an admin switch-off hides the entry on an instance where the component *is* deployed.
         * Defaults to `true` so every pre-SFS caller and test keeps seeing deployment-only
         * semantics; only [availabilityPredicate] passes the live flag.
         */
        internal fun mailAvailabilityPredicate(
            availability: MailAvailability?,
            emailFlagEnabled: Boolean = true,
        ): (NavItem) -> Boolean =
            { item ->
                (item != NavItem.MAIL_JOURNAL && item != NavItem.MAIL_TEMPLATES) ||
                    FeatureOffers.mailOffered(availability?.enabled == true, emailFlagEnabled)
            }

        /**
         * The availability half of [visibleNavItems] for the offer component: the offer entries
         * appear exactly when the component is deployed and not switched off in the settings.
         * `null` (a layout built outside CDI) reads as absent, the conservative direction, like
         * [mailAvailabilityPredicate].
         */
        private val QUOTE_ITEMS = setOf(NavItem.QUOTES, NavItem.QUOTE_RECEIPT_POOL, NavItem.QUOTE_STATISTICS)

        internal fun quoteAvailabilityPredicate(availability: QuoteAvailability?): (NavItem) -> Boolean =
            { item -> item !in QUOTE_ITEMS || availability?.offered == true }
    }

    private fun createSkipLink() {
        val skipLink = Anchor("#main-content", tr("nav.skipToMain"))
            .also {
                it.addClassName("skip-link")
                it.setId("skip-to-content")
            }

        addToNavbar(skipLink)
    }

    private fun ensureLocale() {
        val ui = UI.getCurrent() ?: return
        ui.locale = TranslationService.GERMAN
        ui.session.locale = TranslationService.GERMAN
    }

    override fun getComponent(): Component = this

    private fun createHeader() {
        val logoText = Span(editionLabel()).also {
            it.addClassName("header-logo-text")
        }

        val logo = HorizontalLayout(logoText).also {
            it.defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
            it.isPadding = false
            it.isSpacing = false
            it.addClassName("header-logo")
        }

        val toggle = DrawerToggle().also {
            it.addClassName("header-toggle")
        }

        // MEN-4 — breadcrumb container, initially empty; populated in afterNavigation.
        breadcrumbContainer = Div().apply {
            addClassName("breadcrumb-container")
        }

        val spacer = Span().also { it.addClassName("header-spacer") }
        val commandPaletteButton = createCommandPaletteButton()
        val bellWrapper = createBellWrapper()

        // PO decision (2026-08-25): no version display in the header — the version stays
        // visible in System → Status (StatusPanel's edition card), where it belongs.
        val header = HorizontalLayout(toggle, logo, breadcrumbContainer, spacer, commandPaletteButton, bellWrapper).also {
            it.defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
            it.width = "100%"
            it.addClassName("main-header")
            it.expand(spacer)
        }
        if (appRoleProvider.isAuthenticated()) {
            header.add(createLogoutLink())
        }
        addToNavbar(header)
    }

    /** MEN-5 — magnifier button opening the command palette; a mouse-driven equivalent of the
     *  Ctrl+K/Cmd+K shortcut ([registerCommandPaletteShortcut]). Focus returns here on close. */
    private fun createCommandPaletteButton(): Button =
        AppButtons.icon(ActionIcons.SEARCH, tr("nav.palette.open")) {}.also { button ->
            button.addClassName("command-palette-button")
            button.addClickListener { commandPalette.open(button) }
        }

    /** Ends the session through the OIDC provider's RP-initiated logout handler
     *  (`quarkus.oidc.logout.path=/logout`): the framework intercepts the request, closes the
     *  session and redirects to the provider's end-session endpoint. That endpoint exists only
     *  while the OIDC tenant is active. Under the anonymous dev bypass there is no session to
     *  end, so the caller only creates this button for authenticated users — no dead link. */
    private fun createLogoutLink(): Button =
        AppButtons.tertiary(tr("nav.logout"), ActionIcons.LOGOUT) { UI.getCurrent()?.page?.setLocation("/logout") }.also { button ->
            button.addClassName("header-logout")
        }

    /** Bell icon + unread badge; clicking navigates to [MessageCenterView]. See
     *  [refreshBellBadge] for the count/visibility logic. */
    private fun createBellWrapper(): Div {
        bellButton = AppButtons.icon(ActionIcons.NOTIFICATIONS, tr("messageCenter.title")) {
            UI.getCurrent()?.navigate(MessageCenterView::class.java)
        }.also { it.addClassName("bell-button") }
        return Div(bellButton, bellBadge).also { it.addClassName("bell-wrapper") }
    }

    /**
     * MEN-2 — one [SideNav] per [NavSection], built from [NavItem]/[NavSection] so the drawer
     * can't drift from the navigation model; TS-8 filters each section through [visibleNavItems]
     * first, and a section whose every item is filtered away is dropped entirely (no empty
     * headed block). REFERENCE_DATA renders as an expandable node ([createReferenceDataNavItem])
     * instead of a plain leaf.
     */
    private fun createDrawer() {
        val granted = grantedPermissions()
        val nav = VerticalLayout().also {
            it.isPadding = false
            it.isSpacing = false
        }
        NavSection.entries.forEach { section ->
            createSectionNav(section, granted)?.let { nav.add(it) }
        }
        addToDrawer(nav)
    }

    /** `null` when [visibleNavItems] leaves nothing to show for [section]. */
    private fun createSectionNav(section: NavSection, granted: Set<AppPermission>): SideNav? {
        val items = visibleNavItems(section, granted, availabilityPredicate())
        // SFS — a model child (NavItem.drawerParent() != null) never renders at section level;
        // it appears under its parent below. Section is dropped when no TOP-LEVEL item survives.
        val topLevel = items.filter { it.drawerParent() == null }
        if (topLevel.isEmpty()) return null
        return SideNav(sectionLabel(section)).also { sideNav ->
            topLevel.forEach { item -> sideNav.addItem(createNavItem(item, items)) }
        }
    }

    /** [visibleSectionItems] is the section's already-filtered list — the permission filter runs
     *  once ([createSectionNav]), and grouped items pick their visible children out of it, so a
     *  child the caller lacks permission for disappears together with the parent's filtering rule,
     *  not through a second code path that could drift (TS-8's "one filter" principle, extended). */
    private fun createNavItem(item: NavItem, visibleSectionItems: List<NavItem>): SideNavItem {
        val children = childItemsOf(item, visibleSectionItems)
        return when {
            item == NavItem.REFERENCE_DATA -> createReferenceDataNavItem(item)
            children.isNotEmpty() -> createGroupedNavItem(item, children)
            else -> leafNavItem(item)
        }
    }

    /** SFS — a grouped drawer node ([NavItem.drawerParent]-less parent, e.g. SYSTEM): SYSTEM with the SETTINGS child,
     *  rendered like [createReferenceDataNavItem] but with plain leaf children (each child is a
     *  real model item with its own route and permission, unlike reference-data groups, which
     *  are just path prefixes of one route). */
    private fun createGroupedNavItem(parent: NavItem, children: List<NavItem>): SideNavItem {
        val node = SideNavItem(navItemLabel(parent), parent.routePath, parent.icon.create())
        children.forEach { child ->
            node.addItem(
                SideNavItem(navItemLabel(child), child.routePath, child.icon.create()).also {
                    it.isMatchNested = true
                },
            )
        }
        if (parent == NavItem.SYSTEM) systemNavItem = node
        return node
    }

    private fun leafNavItem(item: NavItem): SideNavItem =
        SideNavItem(navItemLabel(item), item.routePath, item.icon.create())

    /**
     * MEN-2 — REFERENCE_DATA renders as an expandable [SideNavItem] with the three
     * [ReferenceDataGroup]s as children, deep-linking to `referenzdaten/<gruppe>`
     * ([referenceDataGroupPath]). Each child sets [SideNavItem.setMatchNested] so a deep link
     * like `referenzdaten/e-rechnung/b2b-profile` still highlights the "E-Rechnung" child; the
     * child's own current-state also cascades the expanded state up to this node client-side
     * (`SideNavItem`'s built-in behavior). [afterNavigation] additionally pushes the expanded
     * state explicitly via [referenceDataExpandedFor] so it's correct in tests that don't run
     * client-side JS, too.
     */
    private fun createReferenceDataNavItem(item: NavItem): SideNavItem {
        val node = SideNavItem(navItemLabel(item), item.routePath, item.icon.create())
        ReferenceDataGroup.entries.forEach { group ->
            node.addItem(
                SideNavItem(referenceDataGroupLabel(group), referenceDataGroupPath(group)).also {
                    it.isMatchNested = true
                },
            )
        }
        referenceDataNavItem = node
        return node
    }

    // ── i18n lookups ───────────────────────────────────────────────────────────
    // Explicit `when` blocks (literal keys per branch), same idiom as ReferenceDataView's
    // groupLabel/tabLabel — keeps every key a literal argument to tr() for translation audits.

    private fun sectionLabel(section: NavSection): String = when (section) {
        NavSection.INVOICING -> tr("nav.section.invoicing")
        NavSection.QUOTING -> tr("nav.section.quoting")
        NavSection.MAIL -> tr("nav.section.mail")
        NavSection.MASTER_DATA -> tr("nav.section.masterData")
        NavSection.ADMINISTRATION -> tr("nav.section.administration")
        NavSection.HELP -> tr("nav.section.help")
    }

    private fun navItemLabel(item: NavItem): String = when (item) {
        NavItem.INVOICES -> tr("nav.invoices")
        NavItem.RECEIPTS -> tr("nav.receipts")
        NavItem.PRINT_JOBS -> tr("nav.printJobs")
        NavItem.STATISTICS -> tr("nav.statistics")
        NavItem.MAIL_JOURNAL -> tr("nav.mailJournal")
        NavItem.MAIL_TEMPLATES -> tr("nav.mailTemplates")
        NavItem.QUOTES -> tr("nav.quotes")
        NavItem.QUOTE_RECEIPT_POOL -> tr("nav.quoteReceiptPool")
        NavItem.QUOTE_STATISTICS -> tr("nav.quoteStatistics")
        NavItem.SENDER_PROFILES -> tr("nav.senderProfiles")
        NavItem.RECIPIENTS -> tr("nav.recipients")
        NavItem.ARTICLES -> tr("nav.serviceCatalog")
        NavItem.REFERENCE_DATA -> tr("nav.referenceData")
        NavItem.SYSTEM -> tr("nav.system")
        NavItem.SETTINGS -> tr("nav.settings")
        NavItem.INFO -> tr("nav.disclaimer")
    }

    private fun referenceDataGroupLabel(group: ReferenceDataGroup): String = when (group) {
        ReferenceDataGroup.ABRECHNUNG -> tr("referenceData.group.abrechnung")
        ReferenceDataGroup.REISEKOSTEN -> tr("referenceData.group.reisekosten")
        ReferenceDataGroup.E_RECHNUNG -> tr("referenceData.group.eRechnung")
    }
}
