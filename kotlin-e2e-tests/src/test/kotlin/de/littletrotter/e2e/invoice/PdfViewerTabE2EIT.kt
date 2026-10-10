package de.littletrotter.e2e.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import de.littletrotter.application.dto.GenerationJobFilter
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.invoice.PrintJobOverviewQueryService
import de.littletrotter.application.dto.PrintJobOverviewRow
import de.littletrotter.domain.invoice.DocumentEngine
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.value.Email
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.shared.DocumentStore
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber

/**
 * PDF-tab-fix — PO report: opening a generated PDF opens a new browser tab in Firefox, but not in
 * Chrome (silent download / no visible action there). Root cause (reproduced in Chromium against
 * the dev stack, before the download-anchor rewrite): the PDF anchor built by
 * [de.littletrotter.ui.view.invoice.buildDownloadAction] carried an attachment-mode Vaadin
 * `DownloadHandler` plus an explicit `download` attribute — Chrome honors that strictly (silent
 * save, no tab); only Firefox still opened its own PDF viewer despite it.
 *
 * Every such per-cell `DownloadHandler` anchor was later replaced with a plain `Anchor(String)`
 * pointing at [de.littletrotter.ui.documents.DocumentDownloadResource]'s stable, authenticated
 * route (`GET /documents/jobs/{jobId}/pdf|xml`) — `Content-Disposition: inline` vs `attachment` is
 * now decided server-side by that resource, not by a client-side handler object. This suite's
 * assertions follow that: they pin the anchor's `href`/`target`/`download` HTML attributes, the
 * contract a browser actually acts on, rather than unwrapping a handler object that no longer
 * exists. Real `Content-Disposition` behaviour is covered at the HTTP level by
 * [de.littletrotter.ui.documents.DocumentDownloadResourceIT] (kotlin-invoice) — not duplicated
 * here.
 *
 * Unlike `GenerationJobFormattingTest` (kotlin-invoice, pure-mock `DocumentStore`, hand-built
 * [PrintJobOverviewRow]), this suite drives the real, CDI-injected
 * [PrintJobOverviewQueryService] against a real Postgres-backed [GenerationJob] row, reflecting
 * into [de.littletrotter.ui.view.invoice.buildDownloadAction] the same way
 * [de.littletrotter.e2e.nats.PrintJobListViewLiveUpdateE2EIT] already reaches into
 * `PrintJobListView`'s own `internal` state: Kotlin's `internal` is a compile-time check only, so
 * reflection crosses the kotlin-invoice/kotlin-e2e-tests module boundary cleanly.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class PdfViewerTabE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var lifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var printJobOverviewQueryService: PrintJobOverviewQueryService

    @Inject
    lateinit var documentStore: DocumentStore

    /**
     * A bare draft invoice (no items, no status transition — neither is needed: the job below is
     * persisted directly as COMPLETED, the same "direct EntityManager persistence for fixture
     * parts that don't themselves exercise business logic" convention
     * [de.littletrotter.e2e.profile.NewFieldEndToEndE2EIT]'s own fixture KDoc documents) plus one
     * COMPLETED [GenerationJob] carrying both a PDF and an e-invoice XML object key.
     */
    private fun buildCompletedJob(tag: String): Long {
        val (senderId, recipientId) = QuarkusTransaction.requiringNew().call {
            val sender = SenderProfile().apply {
                name = "$tag Sender GmbH"
                invoicePrefix = "$tag-"
                registerCourt = "AG Musterstadt"
                email = Email.ofNullable("billing@$tag.example")
                taxNumber = TaxNumber("12/345/67890")
            }
            sender.addAddress(Address.forSender(sender, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true))
            val recipient = Recipient().apply {
                customerNumber = CustomerNumber("$tag-100")
                name = "$tag Empfaenger AG"
                email = Email.ofNullable("ap@$tag.example")
            }
            recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Kundenallee 2", "20095", "Hamburg", Country.DE, true))
            em.persist(sender)
            em.persist(recipient)
            em.flush()
            sender.id!! to recipient.id!!
        }

        val invoiceId = QuarkusTransaction.requiringNew().call { lifecycleService.create(senderId, recipientId).id!! }

        return QuarkusTransaction.requiringNew().call {
            val invoice = em.find(Invoice::class.java, invoiceId)!!
            val job = GenerationJob().apply {
                this.invoice = invoice
                this.jobType = "PDF_EXPORT"
                this.status = GenerationJobStatus.COMPLETED
                this.engine = DocumentEngine.MUSTANG
                this.createdAt = OffsetDateTime.now()
                this.finishedAt = OffsetDateTime.now()
                this.resultRefs = """{"objectKey":"invoices/$tag.pdf","zugferdObjectKey":"invoices/$tag.xml"}"""
            }
            em.persist(job)
            em.flush()
            job.id!!
        }
    }

    private fun rowFor(jobId: Long): PrintJobOverviewRow =
        printJobOverviewQueryService.findPaged(GenerationJobFilter(), 0, 200)
            .first { it.jobId == jobId }

    /** Crosses the kotlin-invoice `internal` boundary — see the class KDoc above. */
    private fun invokeBuildDownloadAction(row: PrintJobOverviewRow, documentStore: DocumentStore): Component {
        val facade = Class.forName("de.littletrotter.ui.view.invoice.GenerationJobFormattingKt")
        val method = facade.declaredMethods.first {
            it.name == "buildDownloadAction" && it.parameterTypes.getOrNull(0) == PrintJobOverviewRow::class.java
        }
        method.isAccessible = true
        val tr: (String) -> String = { key -> key }
        // buildDownloadAction dropped its checksumVerifier parameter entirely (the
        // checksum-verified download path moved to DocumentDownloadResource's own stable route);
        // this reflection call now matches its real 3-argument signature.
        return method.invoke(null, row, documentStore, tr) as Component
    }

    private fun anchorLabeled(component: Component, buttonText: String): Anchor {
        val row = component as HorizontalLayout
        return row.children.toList().filterIsInstance<Anchor>()
            .first { it.children.toList().filterIsInstance<Button>().first().text == buttonText }
    }

    @Test
    fun `PDF-tab-fix - the real print-job row's PDF anchor links the stable route with no download attribute`() {
        val jobId = buildCompletedJob("E2EPDFTAB")

        MockVaadin.setup()
        try {
            val row = rowFor(jobId)
            assertEquals("invoices/E2EPDFTAB.pdf", row.pdfObjectKey)

            val component = invokeBuildDownloadAction(row, documentStore)
            val pdfAnchor = anchorLabeled(component, "PDF")

            assertEquals("_blank", pdfAnchor.target.orElse(null), "the PDF anchor must open a new tab, matching Firefox's already-correct behavior")
            assertFalse(pdfAnchor.isDownload, "the PDF anchor must not force a silent download -- that is Chrome's current bug")
            assertEquals("documents/jobs/$jobId/pdf", pdfAnchor.href, "ST-A5 -- the stable route, not a Vaadin dynamic resource; inline vs attachment is now decided server-side")
        } finally {
            MockVaadin.tearDown()
        }
    }

    @Test
    fun `PDF-tab-fix - the XML anchor on the same real row stays a plain attachment download`() {
        val jobId = buildCompletedJob("E2EXMLTAB")

        MockVaadin.setup()
        try {
            val row = rowFor(jobId)
            assertEquals("invoices/E2EXMLTAB.xml", row.xmlObjectKey)

            val component = invokeBuildDownloadAction(row, documentStore)
            val xmlAnchor = anchorLabeled(component, "XML")

            assertTrue(xmlAnchor.isDownload, "the XML artifact is not part of this bug's PDF-viewer-tab fix and must stay a download")
            assertEquals("documents/jobs/$jobId/xml", xmlAnchor.href, "ST-A5 -- the stable route, not a Vaadin dynamic resource")
        } finally {
            MockVaadin.tearDown()
        }
    }
}
