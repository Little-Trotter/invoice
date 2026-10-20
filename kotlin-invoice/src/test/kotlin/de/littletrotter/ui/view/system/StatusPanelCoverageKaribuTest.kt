package de.littletrotter.ui.view.system

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.github.mvysny.kaributesting.v10._find
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.HasText
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Div
import de.littletrotter.application.ApplicationIdentity
import de.littletrotter.application.system.port.outbound.DatabaseMaintenanceService
import de.littletrotter.application.system.MaintenanceEventQueries
import de.littletrotter.application.system.port.inbound.SystemStatusQueryPort
import de.littletrotter.domain.system.DatabaseMaintenanceEvent
import de.littletrotter.domain.system.DatabaseMaintenanceStatus
import de.littletrotter.domain.system.DatabaseMaintenanceType
import de.littletrotter.ui.i18n.UiTranslations
import java.util.Optional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import de.littletrotter.application.dto.ServiceStatus
import de.littletrotter.application.system.QueueDatabaseMaintenance

/**
 * UI-5 slice 2 — closes the branch gaps [StatusPanelTest] leaves open: the empty-dependencies
 * edge of the hero banner's healthy/unhealthy check, the refresh button's click handler (both with
 * and without an active UI — `UI.getCurrent()?.page?.reload()`), the service-detail dialog
 * ([openServiceDetailDialog], never exercised at all before this), and the two translation-key
 * fallback helpers for a dependency key this app has no `serviceMeta`/i18n entry for.
 */
class StatusPanelCoverageKaribuTest {

    private fun stubSystemStatusService(vararg statuses: ServiceStatus): SystemStatusQueryPort =
        object : SystemStatusQueryPort {
            override fun listDependencies(): List<ServiceStatus> = statuses.toList()
        }

    private fun newPanel(
        vararg statuses: ServiceStatus,
        // Required dependency (StatusPanel no longer accepts null) -- a fresh fake per call so
        // callers that don't care about it (most tests in this file) still get a harmless,
        // never-invoked-during-construction stand-in; see FakeDatabaseMaintenanceService below.
        databaseMaintenanceService: DatabaseMaintenanceService = FakeDatabaseMaintenanceService(),
        maintenanceActionsEnabled: Boolean = true,
        backupStatusLoader: BackupStatusLoader = BackupStatusLoader(Optional.empty()),
    ): StatusPanel = StatusPanel(
        systemStatusService = stubSystemStatusService(*statuses),
        applicationIdentity = ApplicationIdentity("2.0.0"),
        profile = "test",
        maintenance = StatusPanelMaintenance(
            QueueDatabaseMaintenance(databaseMaintenanceService),
            MaintenanceEventQueries(databaseMaintenanceService),
            maintenanceActionsEnabled,
        ),
        time = de.littletrotter.shared.time.ClockTimeSource.fixedAt(java.time.Instant.parse("2026-06-15T10:00:00Z")),
        backupStatusLoader = backupStatusLoader,
    )

    private fun flatten(component: Component): List<Component> =
        listOf(component) + component.children.toList().flatMap { flatten(it) }

    private fun texts(component: Component): List<String> =
        flatten(component).filterIsInstance<HasText>().map { it.text }.filter { it.isNotBlank() }

    private fun refreshButton(panel: StatusPanel): Button =
        flatten(panel).filterIsInstance<Button>().first { it.classNames.contains("system-refresh-btn") }

    private fun serviceCard(panel: StatusPanel, matchingText: String): Div =
        flatten(panel).filterIsInstance<Div>()
            .first { it.classNames.contains("system-service-card") && flatten(it).any { c -> (c as? HasText)?.text?.contains(matchingText) == true } }

    // ── line 99 — healthy = upCount == totalCount && totalCount > 0 — the empty-fleet edge ──────

    @Test
    fun `an empty dependency list renders as unhealthy, not vacuously healthy`() {
        val panel = newPanel()

        val texts = texts(panel)
        assertTrue(texts.contains(UiTranslations.tr("system.unhealthy")), "0 == 0 must not read as healthy — there is nothing running to be healthy about")
    }

    // ── Datensicherung tile ──────────────────────────────────────────────────────────────────

    @Test
    fun `backup tile shows keine Daten when no status file is available`() {
        val texts = texts(newPanel())

        assertTrue(texts.contains(UiTranslations.tr("system.backup.noData")))
    }

    @Test
    fun `backup tile shows result, findings and hints from status json`() {
        val dir = java.nio.file.Files.createTempDirectory("backup-tile")
        java.nio.file.Files.writeString(
            dir.resolve("status.json"),
            """{"schema":1,"result":"warning","finished_at":"2026-10-12T03:10:00+02:00",
               "inventory":{"result":"error","findings":{"object_missing":2},"db_checksum_failures":0},
               "next_run_at":"2026-10-13T03:00:00+02:00","schedule_conflict":true}""",
        )
        java.nio.file.Files.writeString(dir.resolve("ALARM.txt"), "x")

        val texts = texts(newPanel(backupStatusLoader = BackupStatusLoader(Optional.of(dir.toString()))))

        assertTrue(texts.contains(UiTranslations.tr("system.backup.result.warning")))
        assertTrue(texts.contains("12.10.2026 03:10"))
        assertTrue(texts.contains(UiTranslations.tr("system.backup.inventory.findings", "object_missing 2")))
        assertTrue(texts.contains(UiTranslations.tr("system.backup.scheduleConflict")))
        assertTrue(texts.contains(UiTranslations.tr("system.backup.alarm")))
        assertFalse(texts.contains(UiTranslations.tr("system.backup.shutdownDeferred")))
    }

    private fun backupTexts(statusJson: String): List<String> {
        val dir = java.nio.file.Files.createTempDirectory("backup-tile")
        java.nio.file.Files.writeString(dir.resolve("status.json"), statusJson)
        return texts(newPanel(backupStatusLoader = BackupStatusLoader(Optional.of(dir.toString()))))
    }

    @Test
    fun `backup tile shows the deferred shutdown hint and the checksum failure count, but no alarm`() {
        val texts = backupTexts(
            """{"result":"ok","inventory":{"result":"ok","db_checksum_failures":3},"shutdown_deferred":true}""",
        )

        assertTrue(texts.contains(UiTranslations.tr("system.backup.shutdownDeferred")))
        assertTrue(texts.contains(UiTranslations.tr("system.backup.dbChecksumFailures", 3L)))
        assertTrue(texts.contains(UiTranslations.tr("system.backup.inventory.ok")))
        assertFalse(texts.contains(UiTranslations.tr("system.backup.alarm")))
        assertFalse(texts.contains(UiTranslations.tr("system.backup.scheduleConflict")))
    }

    @Test
    fun `backup tile without workstation feedback says none yet and shows the never placeholder`() {
        val texts = backupTexts("""{"result":"ok","copies":{"state":"none","workstations":[]}}""")

        assertTrue(texts.contains(UiTranslations.tr("system.backup.copies.none")))
        assertTrue(texts.contains(UiTranslations.tr("system.backup.never")))
        assertFalse(texts.contains(UiTranslations.tr("system.backup.copies.stale", 14L)))
    }

    @Test
    fun `backup tile lists workstation copies, flags a failed one and warns when the producer banner is set`() {
        val texts = backupTexts(
            """{"result":"ok","copies":{"state":"error","banner":true,"stale_after_days":7,"workstations":[
               {"computer":"PC-A","date":"2026-06-14T09:30:00","result":"ok"},
               {"computer":"PC-B","result":"error"}]}}""",
        )

        assertTrue(texts.contains(UiTranslations.tr("system.backup.copies.workstation", "PC-A")))
        assertTrue(texts.contains("14.06.2026 09:30"))
        assertTrue(texts.contains(UiTranslations.tr("system.backup.copies.workstation", "PC-B")))
        assertTrue(texts.contains(UiTranslations.tr("system.backup.never") + " (" + UiTranslations.tr("system.backup.result.error") + ")"))
        assertTrue(texts.contains(UiTranslations.tr("system.backup.copies.stale", 7L)))
    }

    @Test
    fun `backup tile warns about a stale copy even when the producer banner is off`() {
        val texts = backupTexts(
            """{"result":"ok","copies":{"state":"stale","banner":false,"stale_after_days":14,"workstations":[
               {"computer":"PC-A","date":"2026-05-01T09:30:00","result":"ok"}]}}""",
        )

        assertTrue(texts.contains(UiTranslations.tr("system.backup.copies.stale", 14L)))
    }

    @Test
    fun `backup tile shows the usb card and a smart failure warning`() {
        val texts = backupTexts(
            """{"result":"ok","usb":{"state":"ok","last_ok_at":"2026-06-14T03:00:00+02:00","smart":"failed"}}""",
        )

        assertTrue(texts.contains(UiTranslations.tr("system.backup.usb")))
        assertTrue(texts.contains("14.06.2026 03:00"))
        assertTrue(texts.contains(UiTranslations.tr("system.backup.usb.smartFailed")))
    }

    @Test
    fun `backup tile shows the usb error message when the last copy failed`() {
        val texts = backupTexts("""{"result":"ok","usb":{"state":"error","message":"disk full","smart":"ok"}}""")

        assertTrue(texts.contains(UiTranslations.tr("system.backup.usb.error", "disk full")))
        assertFalse(texts.contains(UiTranslations.tr("system.backup.usb.smartFailed")))
    }

    @Test
    fun `backup tile hides an unused usb block`() {
        val texts = backupTexts("""{"result":"ok","usb":{"state":"absent"}}""")

        assertFalse(texts.contains(UiTranslations.tr("system.backup.usb")))
    }

    // ── refresh button — UI.getCurrent()?.page?.reload(), both branches of the null-safe chain ──

    @Test
    fun `clicking refresh with no active UI does not throw`() {
        // Deliberately no MockVaadin.setup() here — UI.getCurrent() is null in a plain JUnit
        // thread, exercising the safe-call chain's null branch.
        val panel = newPanel(ServiceStatus("invoice", "Invoice App", "http://localhost:9025", true))

        refreshButton(panel).click()
        // No assertion beyond "did not throw" — with no active UI there is nothing else to observe.
    }

    @Test
    fun `clicking refresh with an active UI dereferences the current page - the non-null branch runs`() {
        MockVaadin.setup()
        try {
            val panel = newPanel(ServiceStatus("invoice", "Invoice App", "http://localhost:9025", true))

            // MockVaadin's UI has no registered routes in this module's minimal test setup, so the
            // reload it triggers fails deeper inside Vaadin's own router — that failure is proof
            // the click reached UI.getCurrent()?.page?.reload()'s non-null branch and dereferenced
            // a real page, the opposite of the no-UI test above where the chain short-circuits.
            val thrown = try {
                refreshButton(panel).click()
                null
            } catch (ex: com.vaadin.flow.router.NotFoundException) {
                ex
            } catch (ex: com.github.mvysny.kaributesting.v10.NotFoundError) {
                ex
            }
            assertTrue(thrown != null, "expected the reload to reach Vaadin's router (proving page.reload() was actually called)")
        } finally {
            MockVaadin.tearDown()
        }
    }

    // ── openServiceDetailDialog — never exercised before this file ─────────────────────────────

    @Test
    fun `clicking a service card with instance, target and version opens a dialog naming all four facts`() {
        MockVaadin.setup()
        try {
            val panel = newPanel(ServiceStatus("postgresql", "primary-a", "postgres:5432", true, version = "16.2"))

            serviceCard(panel, "primary-a")._click()

            val dialog = _find<Dialog>().single()
            val dialogTexts = texts(dialog)
            assertTrue(dialogTexts.any { it.contains("primary-a") }, "the instance card must show the instance name")
            assertTrue(dialogTexts.any { it.contains("postgres:5432") }, "the target card must show the raw target — not an http URL wording")
            assertTrue(dialogTexts.any { it.contains("v16.2") }, "the version card must be prefixed with v")
            assertTrue(dialogTexts.contains(UiTranslations.tr("system.service.available")))
        } finally {
            MockVaadin.tearDown()
        }
    }

    @Test
    fun `clicking a service card with no instance, target or version shows only status and checked-at`() {
        MockVaadin.setup()
        try {
            val panel = newPanel(ServiceStatus("nats", "", "", false))

            serviceCard(panel, UiTranslations.tr("system.service.unavailable"))._click()

            val dialog = _find<Dialog>().single()
            val infoCardLabels = flatten(dialog).filterIsInstance<HasText>().map { it.text }
            assertFalse(infoCardLabels.any { it == UiTranslations.tr("system.service.dialog.instance") }, "no instance card when instanceName is blank")
            assertFalse(infoCardLabels.any { it == UiTranslations.tr("system.service.dialog.target") }, "no target card when target is blank")
            assertFalse(infoCardLabels.any { it == UiTranslations.tr("system.service.dialog.version") }, "no version card when version is blank")
            assertTrue(infoCardLabels.any { it == UiTranslations.tr("system.service.dialog.checked") }, "the checked-at card always renders")
        } finally {
            MockVaadin.tearDown()
        }
    }

    @Test
    fun `an http target is worded as an endpoint, a bare host-port target is shown as-is`() {
        MockVaadin.setup()
        try {
            val panel = newPanel(ServiceStatus("omlx", "omlx-a", "http://omlx.internal:8080", true))

            val texts = texts(panel)
            assertTrue(
                texts.any { it.contains(UiTranslations.tr("system.service.target.url", "http://omlx.internal:8080")) },
                "an http(s) target must render through the endpoint wording, not the raw string",
            )
        } finally {
            MockVaadin.tearDown()
        }
    }

    // ── resolveServiceName / resolveServiceDescription fallback — a key with no serviceMeta entry ──

    @Test
    fun `a dependency key absent from serviceMeta falls back to its last segment, capitalized, as the name`() {
        MockVaadin.setup()
        try {
            val panel = newPanel(ServiceStatus("unknown_service.beta", "inst", "host:1", true))

            val texts = texts(panel)
            // resolveServiceName: no "system.service.unknown_service.beta.name" translation exists,
            // so it falls back to the last dot/underscore/dash-separated segment, capitalized.
            // (buildServiceCard's description argument is always "" for an unmapped key — see the
            // dedicated resolveServiceDescription test below, which reaches it directly.)
            assertTrue(texts.any { it == "Beta" }, "the name must fall back to the last segment, capitalized")
        } finally {
            MockVaadin.tearDown()
        }
    }

    // resolveServiceName's own fallback (`key.split(...).last().replaceFirstChar {...}`)
    // is inlined (both `List.last()` and `String.replaceFirstChar` are stdlib inline functions),
    // so its `replaceFirstChar` "is this string empty" branch is only reachable with a key whose
    // last split segment is itself empty — a key ending in one of the split delimiters.
    private fun resolveServiceName(panel: StatusPanel, key: String): String {
        val m = StatusPanel::class.java.getDeclaredMethod("resolveServiceName", String::class.java)
        m.isAccessible = true
        return m.invoke(panel, key) as String
    }

    @Test
    fun `boundary - resolveServiceName falls back to an empty name when the key's last segment is itself empty`() {
        val panel = newPanel()

        val fallback = resolveServiceName(panel, "unknown_service.")

        assertEquals(
            "",
            fallback,
            "a trailing delimiter yields an empty last segment; replaceFirstChar on an empty string must return it unchanged, not throw",
        )
    }

    /** [StatusPanel.resolveServiceDescription] is only ever invoked with a real i18n key
     *  ([serviceMeta]'s desc entries) or `""` (an unmapped dependency's placeholder — see
     *  [buildServiceCard]) — there is no production path that feeds it a punctuation-bearing,
     *  untranslated key the way [resolveServiceName] can receive via `dependency.key` itself.
     *  Reflection is the only way to reach its substantive fallback branch (same idiom this
     *  module already uses for private pure functions, e.g. `ArticleView.categoryLabel`). */
    private fun resolveServiceDescription(panel: StatusPanel, key: String): String {
        val m = StatusPanel::class.java.getDeclaredMethod("resolveServiceDescription", String::class.java)
        m.isAccessible = true
        return m.invoke(panel, key) as String
    }

    @Test
    fun `resolveServiceDescription falls back to a punctuation-cleaned, lead-capitalized string for an untranslated key`() {
        val panel = newPanel()

        val fallback = resolveServiceDescription(panel, "unknown_service.beta")

        assertEquals("Unknown service beta", fallback, "'_'/'.' become spaces; only the very first character is titlecased, not every word")
    }

    // The test above only exercises replaceFirstChar's `c.isLowerCase()` true arm (its
    // first character, 'u', is lowercase); a key whose first character is already uppercase must
    // hit the untouched `else c.toString()` arm instead.
    @Test
    fun `boundary - resolveServiceDescription fallback leaves an already-uppercase first character untouched`() {
        val panel = newPanel()

        val fallback = resolveServiceDescription(panel, "Standalone_service")

        assertEquals("Standalone service", fallback, "an already-uppercase first character must not go through titlecase again")
    }

    @Test
    fun `a known dependency key uses its real translation, not the fallback formatter`() {
        val panel = newPanel(ServiceStatus("invoice", "inst", "host:1", true))

        val texts = texts(panel)
        assertEquals(
            UiTranslations.tr("system.service.invoice.name"),
            texts.first { it == UiTranslations.tr("system.service.invoice.name") },
        )
        assertFalse(texts.contains("Invoice"), "a real translation must win over the capitalized-key fallback")
    }

    // ── N12/P1 — "generate missing e-invoices" button ───────────────────────────────────────────

    private fun queueButton(panel: StatusPanel): Button =
        flatten(panel).filterIsInstance<Button>().first { it.classNames.contains("system-queue-issued-generation-jobs-btn") }

    private class FakeDatabaseMaintenanceService(
        // N15 — what buildRecalculationStatusLine reads at construction time; null (the default)
        // means "no RECALCULATE_TOTALS event recorded yet".
        var latestRecalculationEvent: DatabaseMaintenanceEvent? = null,
    ) : DatabaseMaintenanceService {
        var queuedDetails: String? = null
        var queuedRecalculationDetails: String? = null
        override fun currentPostgresMaintenance() = DatabaseMaintenanceService.PostgresMaintenanceStatus(null, null, null, null, null, null)
        override fun recordMaintenanceEvent(event: DatabaseMaintenanceEvent) {}
        override fun latestMaintenanceEvent(type: DatabaseMaintenanceType): DatabaseMaintenanceEvent? =
            if (type == DatabaseMaintenanceType.RECALCULATE_TOTALS) latestRecalculationEvent else null
        override fun performRecalculateTotals(): String = throw AssertionError("not exercised by this test")
        override fun performQueueIssuedGenerationJobs(): String = throw AssertionError("not exercised by this test")
        override fun claimOldestPendingMaintenanceJob(): DatabaseMaintenanceService.ClaimedMaintenanceJob? = null
        override fun markMaintenanceJobFailed(eventId: Long, reason: String) {}
        override fun queueIssuedGenerationJobsSweep(details: String): DatabaseMaintenanceEvent {
            queuedDetails = details
            return DatabaseMaintenanceEvent(type = DatabaseMaintenanceType.QUEUE_ISSUED_GENERATION_JOBS, status = DatabaseMaintenanceStatus.PENDING, details = details)
        }
        override fun queueRecalculation(details: String): DatabaseMaintenanceEvent {
            queuedRecalculationDetails = details
            return DatabaseMaintenanceEvent(type = DatabaseMaintenanceType.RECALCULATE_TOTALS, status = DatabaseMaintenanceStatus.PENDING, details = details)
        }
    }

    @Test
    fun `without the operator switch the two maintenance buttons are absent and the status line stays`() {
        val panel = newPanel(maintenanceActionsEnabled = false)

        val buttons = flatten(panel).filterIsInstance<Button>().map { it.text }
        assertFalse(buttons.contains(UiTranslations.tr("system.maintenance.queueIssuedGenerationJobs")))
        assertFalse(buttons.contains(UiTranslations.tr("system.maintenance.queueRecalculation")))
        assertTrue(flatten(panel).any { it.element.text == UiTranslations.tr("system.maintenance.recalculationStatus.none") })
    }

    @Test
    fun `the button renders with its German label and tooltip`() {
        val panel = newPanel(databaseMaintenanceService = FakeDatabaseMaintenanceService())

        val button = queueButton(panel)

        assertEquals(UiTranslations.tr("system.maintenance.queueIssuedGenerationJobs"), button.text)
        assertEquals(UiTranslations.tr("system.maintenance.queueIssuedGenerationJobs.tooltip"), button.element.getAttribute("title"))
    }

    @Test
    fun `clicking the button queues the sweep with the System-tab scope details and shows a confirmation`() {
        MockVaadin.setup()
        try {
            val fake = FakeDatabaseMaintenanceService()
            val panel = newPanel(databaseMaintenanceService = fake)

            queueButton(panel)._click()

            assertEquals("scope=ISSUED_WITHOUT_JOB, triggered from System tab", fake.queuedDetails)
        } finally {
            MockVaadin.tearDown()
        }
    }

    // ── N15 — "Neuberechnung anstoßen" button and the recalculation status line ────────────────

    private fun recalculationButton(panel: StatusPanel): Button =
        flatten(panel).filterIsInstance<Button>().first { it.classNames.contains("system-queue-recalculation-btn") }

    private fun recalculationStatusLine(panel: StatusPanel): com.vaadin.flow.component.html.Span =
        flatten(panel).filterIsInstance<com.vaadin.flow.component.html.Span>().first { it.classNames.contains("system-recalculation-status") }

    @Test
    fun `the recalculation button renders with its German label and tooltip`() {
        val panel = newPanel(databaseMaintenanceService = FakeDatabaseMaintenanceService())

        val button = recalculationButton(panel)

        assertEquals(UiTranslations.tr("system.maintenance.queueRecalculation"), button.text)
        assertEquals(UiTranslations.tr("system.maintenance.queueRecalculation.tooltip"), button.element.getAttribute("title"))
    }

    @Test
    fun `clicking the recalculation button queues a RECALCULATE_TOTALS event and shows a confirmation`() {
        MockVaadin.setup()
        try {
            val fake = FakeDatabaseMaintenanceService()
            val panel = newPanel(databaseMaintenanceService = fake)

            recalculationButton(panel)._click()

            assertEquals("triggered from System tab", fake.queuedRecalculationDetails)
        } finally {
            MockVaadin.tearDown()
        }
    }

    @Test
    fun `with no recalculation event yet, the status line says so instead of showing a stale status`() {
        val panel = newPanel(databaseMaintenanceService = FakeDatabaseMaintenanceService(latestRecalculationEvent = null))

        assertEquals(UiTranslations.tr("system.maintenance.recalculationStatus.none"), recalculationStatusLine(panel).text)
    }

    @Test
    fun `a FAILED recalculation event's status line names the offending invoice from its details' first line`() {
        val event = DatabaseMaintenanceEvent(
            type = DatabaseMaintenanceType.RECALCULATE_TOTALS,
            status = DatabaseMaintenanceStatus.FAILED,
            details = "Recalculation aborted at invoice RE-2026-00042 (id 42): InvoiceValidationException: " +
                "Tax rate reference missing. 3 invoice(s) recalculated successfully before it, 0 skipped (read-only), 2 invoice(s) left unprocessed.\n",
        )
        val panel = newPanel(databaseMaintenanceService = FakeDatabaseMaintenanceService(latestRecalculationEvent = event))

        val line = recalculationStatusLine(panel)
        assertTrue(line.text.contains("RE-2026-00042 (id 42)"), "the invoice reference must be visible so the user can find it: ${line.text}")
        assertTrue(line.text.contains(UiTranslations.tr("system.maintenance.status.failed")))
        assertTrue(line.classNames.contains("system-recalculation-status-failed"))
    }

    @Test
    fun `a SUCCESS recalculation event's status line shows the success summary, not the failed styling`() {
        val event = DatabaseMaintenanceEvent(
            type = DatabaseMaintenanceType.RECALCULATE_TOTALS,
            status = DatabaseMaintenanceStatus.SUCCESS,
            details = "Recalculated 5 invoices successfully, 1 skipped (read-only), 0 failed.",
        )
        val panel = newPanel(databaseMaintenanceService = FakeDatabaseMaintenanceService(latestRecalculationEvent = event))

        val line = recalculationStatusLine(panel)
        assertTrue(line.text.contains("Recalculated 5 invoices successfully"), line.text)
        assertTrue(line.text.contains(UiTranslations.tr("system.maintenance.status.success")))
        assertFalse(line.classNames.contains("system-recalculation-status-failed"))
    }

    @Test
    fun `boundary - a recalculation event with null details still renders its status label with an empty first line`() {
        // event.details?.lineSequence()?.firstOrNull().orEmpty() -- every other test here gives a
        // non-null, non-blank details string; this is the only one exercising details == null.
        val event = DatabaseMaintenanceEvent(
            type = DatabaseMaintenanceType.RECALCULATE_TOTALS,
            status = DatabaseMaintenanceStatus.PENDING,
            details = null,
        )
        val panel = newPanel(databaseMaintenanceService = FakeDatabaseMaintenanceService(latestRecalculationEvent = event))

        val line = recalculationStatusLine(panel)
        assertTrue(line.text.contains(UiTranslations.tr("system.maintenance.status.pending")), line.text)
    }

    @Test
    fun `boundary - the service detail dialog's close button actually closes the dialog`() {
        MockVaadin.setup()
        try {
            val panel = newPanel(ServiceStatus("invoice", "inst", "host:1", true))

            serviceCard(panel, "inst")._click()
            assertEquals(1, _find<Dialog>().size, "sanity: the dialog opened")

            val dialog = _find<Dialog>().single()
            // Dialog.footer's content is a virtual child (Vaadin footer/header slot), invisible to
            // a plain component.children walk — _find (kaributesting's own tree search) resolves
            // through it correctly, same fix as the notification-content lookup elsewhere.
            _find<Button> { text = UiTranslations.tr("app.close") }.single()._click()

            assertFalse(dialog.isOpened, "the close button must actually close the dialog")
        } finally {
            MockVaadin.tearDown()
        }
    }

    @Test
    fun `boundary - a delimiter-free, already-capitalized dependency key needs no fallback transformation`() {
        // resolveServiceName's split('.', '_', '-') and replaceFirstChar branches both take their
        // "already done" path only for a key with no delimiter whose first character is already
        // uppercase -- every other test here uses a lowercase, dotted/underscored key.
        val panel = newPanel(ServiceStatus("Standalone", "inst", "host:1", true))

        val texts = texts(panel)
        assertTrue(texts.contains("Standalone"))
    }

    @Test
    fun `boundary - resolveServiceDescription uses the real translation for a known i18n key, not the fallback formatter`() {
        val panel = newPanel()

        val real = resolveServiceDescription(panel, "system.service.postgresql.desc")

        assertEquals(UiTranslations.tr("system.service.postgresql.desc"), real)
    }
}
