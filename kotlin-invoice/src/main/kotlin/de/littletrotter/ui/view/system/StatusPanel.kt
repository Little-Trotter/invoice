package de.littletrotter.ui.view.system

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.H3
import com.vaadin.flow.component.html.Paragraph
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.FlexLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import de.littletrotter.application.ApplicationIdentity
import de.littletrotter.application.system.DatabaseMaintenanceService
import de.littletrotter.application.system.SystemStatusService
import de.littletrotter.domain.system.DatabaseMaintenanceStatus
import de.littletrotter.domain.system.DatabaseMaintenanceType
import de.littletrotter.ui.component.ActionIcons
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.AppDialog
import de.littletrotter.ui.component.PageShell
import de.littletrotter.ui.i18n.TranslationProvider
import de.littletrotter.ui.notification.UiNotifications
import jakarta.enterprise.context.Dependent
import jakarta.inject.Inject
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.eclipse.microprofile.config.inject.ConfigProperty

private const val SYSTEM_REFRESH_KEY = "system.refresh"
private const val TITLE_ATTR = "title"

@Dependent
class StatusPanel @Inject constructor(
    systemStatusService: SystemStatusService,
    val applicationIdentity: ApplicationIdentity,
    @ConfigProperty(name = "quarkus.application.version")
    appVersion: String,
    @ConfigProperty(name = "quarkus.profile")
    profile: String,
    // N12/P1 — the System-tab button's own dependency. Required, not nullable/defaulted: a
    // missing service must never let the click handler silently no-op while still showing a
    // "queued" success notification (coordinator review, 2026-09-11) — every StatusPanel(...)
    // call site, test or production, now supplies a real one.
    private val databaseMaintenanceService: DatabaseMaintenanceService,
    // Operator-only: the two maintenance triggers are hidden unless the instance enables them
    // (env LITTLETROTTER_FEATURES_MAINTENANCE_ACTIONS_ENABLED); the status line stays visible.
    @ConfigProperty(name = "littletrotter.features.maintenance-actions.enabled", defaultValue = "false")
    private val maintenanceActionsEnabled: Boolean,
    private val time: de.littletrotter.shared.time.TimeSource,
) : VerticalLayout(), TranslationProvider {

    private val serviceMeta = linkedMapOf(
        "postgresql" to arrayOf("system.service.postgresql.name", "system.service.postgresql.desc"),
        "rustfs" to arrayOf("system.service.rustfs.name", "system.service.rustfs.desc"),
        "valkey" to arrayOf("system.service.valkey.name", "system.service.valkey.desc"),
        "omlx" to arrayOf("system.service.omlx.name", "system.service.omlx.desc"),
        // Only present in `dependencies` while
        // ValidationCapabilityId.VERAPDF_CHECK is on (see DockerStatusService), same reasoning
        // as omlx's own conditional entry above.
        "verapdf" to arrayOf("system.service.verapdf.name", "system.service.verapdf.desc"),
        // Same conditional-entry reasoning as verapdf above: only present while
        // ValidationCapabilityId.FACTURX_ENGINE is on (see DockerStatusService).
        "facturx" to arrayOf("system.service.facturx.name", "system.service.facturx.desc"),
        "nats" to arrayOf("system.service.nats.name", "system.service.nats.desc"),
        "worker" to arrayOf("system.service.worker.name", "system.service.worker.desc"),
        "xpdf" to arrayOf("system.service.xpdf.name", "system.service.xpdf.desc"),
        "invoice" to arrayOf("system.service.invoice.name", "system.service.invoice.desc"),
    )

    init {
        addClassName("system-status-panel")
        setWidthFull()
        isPadding = false
        isSpacing = true

        val dependencies = systemStatusService.listDependencies()

        add(
            PageShell(tr("system.title"), tr("system.subtitle"))
                .content(buildHeroBanner(dependencies), buildInfoBar(dependencies, appVersion, profile)),
        )
    }

    private fun buildHeroBanner(deps: List<SystemStatusService.ServiceStatus>): Div = Div().apply {
        addClassName("system-hero-banner")

        val upCount = deps.count { it.up }
        val totalCount = deps.size
        val healthy = upCount == totalCount && totalCount > 0

        val textContainer = VerticalLayout().apply {
            isPadding = false
            isSpacing = false
            val head = H3(tr("system.ready")).apply {
                addClassName("system-hero-title")
            }
            val status = Span(if (healthy) tr("system.healthy") else tr("system.unhealthy")).apply {
                addClassName(if (healthy) "system-health-ok" else "system-health-bad")
            }
            val summary = Span(tr("system.serviceSummary", upCount, totalCount)).apply {
                addClassName("system-hero-summary")
            }
            add(head, status, summary)
        }

        val refreshBtn = AppButtons.primary(tr(SYSTEM_REFRESH_KEY), ActionIcons.REFRESH) { UI.getCurrent()?.page?.reload() }.apply {
            addClassName("system-refresh-btn")
            element.setAttribute("aria-label", tr(SYSTEM_REFRESH_KEY))
            element.setProperty(TITLE_ATTR, tr(SYSTEM_REFRESH_KEY))
        }

        val rightRail = VerticalLayout(refreshBtn).apply {
            if (maintenanceActionsEnabled) {
                add(buildQueueIssuedGenerationJobsButton(), buildQueueRecalculationButton())
            }
            add(buildRecalculationStatusLine())
        }.apply {
            isPadding = false
            isSpacing = true
            alignItems = FlexComponent.Alignment.END
            addClassName("system-hero-rail")
        }

        add(textContainer, rightRail)
    }

    /**
     * N12/P1 — queues a [de.littletrotter.domain.system.DatabaseMaintenanceType
     * .QUEUE_ISSUED_GENERATION_JOBS] event for [de.littletrotter.application.invoice
     * .MaintenanceQueueProcessor] to pick up, so an admin can trigger the "generate missing
     * e-invoices" sweep sooner than its next scheduled run (useful right after a restore that lost
     * the object store). [de.littletrotter.application.security.RequiresPermission] on
     * [DatabaseMaintenanceService.queueIssuedGenerationJobsSweep] itself is the real gate — this
     * button has nothing extra to hide behind, since [SystemView]'s own route already requires
     * [de.littletrotter.application.security.AppPermission.SYSTEM_VIEW], which — same as
     * [de.littletrotter.application.security.AppPermission.DATABASE_MAINTENANCE] — only the ADMIN
     * role ever carries ([de.littletrotter.application.security.AppRole]).
     */
    private fun buildQueueIssuedGenerationJobsButton(): Button {
        lateinit var button: Button
        button = AppButtons.tertiary(tr("system.maintenance.queueIssuedGenerationJobs"), ActionIcons.MAINTENANCE) {
            // Required dependency (no null branch): the sweep is either actually queued, or
            // this throws and the click listener's own exception handling surfaces it --
            // there is no path left where "queued" is shown without anything having been
            // queued.
            databaseMaintenanceService.queueIssuedGenerationJobsSweep(
                "scope=ISSUED_WITHOUT_JOB, triggered from System tab",
            )
            UiNotifications.confirmOn(button)
        }.apply {
            addClassName("system-queue-issued-generation-jobs-btn")
            element.setAttribute(TITLE_ATTR, tr("system.maintenance.queueIssuedGenerationJobs.tooltip"))
        }
        return button
    }

    /**
     * N15 — sibling of [buildQueueIssuedGenerationJobsButton]: queues a
     * [de.littletrotter.domain.system.DatabaseMaintenanceType.RECALCULATE_TOTALS] event for
     * [de.littletrotter.application.invoice.MaintenanceQueueProcessor] to pick up, so an admin can
     * re-trigger the recalculation sweep on demand — e.g. right after fixing the data error a
     * previous run aborted on (see [buildRecalculationStatusLine]). Same permission idiom as its
     * sibling: [DatabaseMaintenanceService.queueRecalculation] itself carries the
     * [de.littletrotter.application.security.RequiresPermission] gate, this button has nothing
     * extra to hide behind.
     */
    private fun buildQueueRecalculationButton(): Button {
        lateinit var button: Button
        button = AppButtons.tertiary(tr("system.maintenance.queueRecalculation"), ActionIcons.RECALCULATE) {
            databaseMaintenanceService.queueRecalculation("triggered from System tab")
            UiNotifications.confirmOn(button)
        }.apply {
            addClassName("system-queue-recalculation-btn")
            element.setAttribute(TITLE_ATTR, tr("system.maintenance.queueRecalculation.tooltip"))
        }
        return button
    }

    /**
     * N15 — the latest [de.littletrotter.domain.system.DatabaseMaintenanceType.RECALCULATE_TOTALS]
     * event's status and the first line of its details, so a FAILED sweep's invoice reference
     * (see [de.littletrotter.infrastructure.persistence.PostgresDatabaseMaintenanceService
     * .performRecalculateTotals]'s own KDoc on the abort-on-first-failure contract) is visible
     * right here, not just in application logs.
     */
    private fun buildRecalculationStatusLine(): Span {
        val event = databaseMaintenanceService.latestMaintenanceEvent(DatabaseMaintenanceType.RECALCULATE_TOTALS)
        val text = if (event == null) {
            tr("system.maintenance.recalculationStatus.none")
        } else {
            val statusLabel = tr("system.maintenance.status.${event.status.name.lowercase()}")
            val firstLine = event.details?.lineSequence()?.firstOrNull().orEmpty()
            tr("system.maintenance.recalculationStatus", statusLabel, firstLine)
        }
        return Span(text).apply {
            addClassName("system-recalculation-status")
            if (event?.status == DatabaseMaintenanceStatus.FAILED) addClassName("system-recalculation-status-failed")
            element.setAttribute(TITLE_ATTR, text)
        }
    }

    private fun buildInfoBar(
        dependencies: List<SystemStatusService.ServiceStatus>,
        appVersion: String,
        profile: String,
    ): Div {
        val versionCard = SystemUiComponents.infoCard(tr("system.version"), appVersion)
        // The card is labeled "Edition" and shows the edition name (pure branding, no feature
        // gating). The version has its own card right next to it.
        val productCard = SystemUiComponents.infoCard(tr("system.edition"), "${applicationIdentity.displayName} ${applicationIdentity.edition}")
        val runtimeCard = SystemUiComponents.infoCard(tr("system.meta.java"), System.getProperty("java.version", "?"))
        val environmentCard = SystemUiComponents.infoCard(tr("system.environment"), profile)

        val infoCards = FlexLayout(productCard, versionCard, runtimeCard, environmentCard).also {
            it.setWidthFull()
            it.flexWrap = FlexLayout.FlexWrap.WRAP
            it.addClassName("system-meta-grid")
            it.addClassName("lt-system-meta-grid")
        }

        val servicesHeader = SystemUiComponents.sectionHeader("Operations Mesh")

        val servicesGroup = FlexLayout().also { layout ->
            layout.setWidthFull()
            layout.flexWrap = FlexLayout.FlexWrap.WRAP
            layout.addClassName("system-service-grid")
            layout.addClassName("lt-gap-100")
            dependencies
                .sortedBy { it.key }
                .forEach { dependency ->
                    layout.add(buildServiceCard(dependency))
                }
        }

        val summaryLine = Paragraph(tr("system.summary.line")).apply {
            addClassName("system-summary-line")
        }

        val content = VerticalLayout(infoCards, summaryLine, servicesHeader, servicesGroup).apply {
            isPadding = false
            isSpacing = false
            setWidthFull()
        }

        return Div(content)
    }

    private fun buildServiceCard(dependency: SystemStatusService.ServiceStatus): Div {
        val meta = serviceMeta[dependency.key] ?: arrayOf(dependency.key, "")
        val name = resolveServiceName(meta[0])
        val description = resolveServiceDescription(meta[1])
        val detail = buildServiceDescription(dependency)
        val statusLabel = if (dependency.up) tr("system.service.available") else tr("system.service.unavailable")
        return SystemUiComponents.serviceCard(
            SystemUiComponents.ServiceCardContent(name, description, detail),
            SystemUiComponents.ServiceCardStatus(statusLabel, dependency.up),
        ).apply {
            element.setAttribute("tabindex", "0")
            element.setAttribute(TITLE_ATTR, tr("system.service.dialog.hint"))
            addClickListener { openServiceDetailDialog(dependency, name, description, statusLabel) }
        }
    }

    /** Click-to-detail for a service tile — the tile itself only has room for the shortest
     *  summary line; this surfaces everything [SystemStatusService.ServiceStatus] actually
     *  carries (instance, full target, version where the backend reports one) plus the moment
     *  the check ran, since the tile grid otherwise gives no sense of freshness. */
    private fun openServiceDetailDialog(
        dependency: SystemStatusService.ServiceStatus,
        name: String,
        description: String,
        statusLabel: String,
    ) {
        val dialog = AppDialog(name, AppDialog.Size.S).apply {
            isResizable = false
            isDraggable = true
        }

        val intro = Paragraph(description).apply { addClassName("lt-dialog-intro") }

        val cards = mutableListOf(SystemUiComponents.infoCard(tr("system.service.dialog.status"), statusLabel))
        if (dependency.instanceName.isNotBlank()) {
            cards += SystemUiComponents.infoCard(tr("system.service.dialog.instance"), dependency.instanceName)
        }
        if (dependency.target.isNotBlank()) {
            cards += SystemUiComponents.infoCard(tr("system.service.dialog.target"), dependency.target)
        }
        if (dependency.version.isNotBlank()) {
            cards += SystemUiComponents.infoCard(tr("system.service.dialog.version"), "v${dependency.version}")
        }
        cards += SystemUiComponents.infoCard(
            tr("system.service.dialog.checked"),
            time.localNow().format(DateTimeFormatter.ofPattern("HH:mm:ss")),
        )

        val grid = FlexLayout(*cards.toTypedArray()).apply {
            setWidthFull()
            flexWrap = FlexLayout.FlexWrap.WRAP
            addClassName("lt-gap-075")
        }

        dialog.add(VerticalLayout(intro, grid).apply {
            isPadding = false
            isSpacing = false
            setWidthFull()
        })
        dialog.footer(AppButtons.tertiary(tr("app.close")) { dialog.close() })
        dialog.open()
    }

    private fun resolveServiceName(key: String): String {
        val translated = tr(key)
        val fallback = key.split('.', '_', '-').last().replaceFirstChar { it.uppercase() }
        return if (translated == key || translated == "!{$key}!") fallback else translated
    }

    private fun resolveServiceDescription(key: String): String {
        val translated = tr(key)
        return if (translated == key || translated == "!{$key}!") {
            key.replace('_', ' ')
                .replace('.', ' ')
                .replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase(Locale.GERMAN) else c.toString() }
        } else {
            translated
        }
    }

    private fun buildServiceDescription(dependency: SystemStatusService.ServiceStatus): String {
        val parts = mutableListOf<String>()
        if (dependency.instanceName.isNotBlank()) {
            parts += tr("system.service.instance", dependency.instanceName)
        }
        if (dependency.target.isNotBlank()) {
            parts += if (dependency.target.startsWith("http")) {
                tr("system.service.target.url", dependency.target)
            } else {
                dependency.target
            }
        }
        if (dependency.version.isNotBlank()) {
            parts += "v${dependency.version}"
        }
        return parts.joinToString(" • ")
    }

    override fun getComponent(): Component = this
}
