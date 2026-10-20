package de.littletrotter.application.invoice

import de.littletrotter.fixture.aGenerationJob
import  de.littletrotter.fixture.anInvoice
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.littletrotter.application.reference.validationSettingsGate
import de.littletrotter.application.settings.InMemorySystemSettingRepository
import de.littletrotter.application.settings.WordingSettingsGate
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.reference.ValidationCapabilityId
import de.littletrotter.fixture.anInvoice
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.InvoiceReferencesDto
import de.littletrotter.shared.wording.WordingProfile
import de.littletrotter.test.FIXED_TIME_SOURCE
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.nats.client.JetStream
import io.nats.client.PublishOptions
import io.nats.client.api.PublishAck
import io.nats.client.impl.Headers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.CompletableFuture

/**
 * Z4 — after extracting the `Invoice` → `InvoiceDto` mapping into [InvoiceDtoMapper], this class
 * only exercises what `DocumentJobPublisher` still owns: envelope building, `Nats-Msg-Id` dedup,
 * counters, the send path, and error handling. Every test uses a [fakeMapper] instead of re-proving mapping fidelity — that lives on
 * [InvoiceDtoMapperTest]/[InvoiceDtoMapperGoldenTest] now.
 */
class DocumentJobPublisherTest {

    private val mapper = jacksonObjectMapper()

    /** A minimal, always-mapping-successful stand-in: the tests here care about the envelope
     *  around `invoiceDto`, not about how it was produced. */
    private fun fakeMapper(dto: InvoiceDto = InvoiceDto()): InvoiceDtoMapper =
        object : InvoiceDtoMapper() {
            override fun mapToDto(inv: Invoice, wordingProfile: WordingProfile, issued: Boolean): InvoiceDto {
                return dto.copy(meta = dto.meta.copy(wordingProfile = wordingProfile.code))
            }
        }

    /** The all-defaults arm of the test-seam constructor (the
     *  messaging-core 100%-gate demands every default mask be exercised, not just the
     *  named-argument combinations the other tests use). */
    @Test
    fun `test-seam constructor is fully default-constructible without any collaborator`() {
        val publisher = DocumentJobPublisher()
        assertThrows(IllegalStateException::class.java) { publisher.publish(Invoice(), 42L) }
    }

    @Test
    fun `publish should send message and return the publishAsync future`() {
        val jetStream = mock<JetStream>()
        val future = CompletableFuture<PublishAck>()
        whenever(jetStream.publishAsync(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>())).thenReturn(future)

        val publisher = DocumentJobPublisher(jetStreamProvider = { jetStream }, mapper = fakeMapper())

        val result = publisher.publish(Invoice(), 42L)
        verify(jetStream).publishAsync(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>())
        assertEquals(0L, publisher.currentSentCount()) // counter increments on completion only

        // result is a dependent stage of the mocked future, not the same instance —
        // completing the source must still complete the returned stage.
        future.complete(mock())
        assertTrue(result.isDone)
    }

    @Test
    fun `sent counter increments when publishAsync completes successfully`() {
        val jetStream = mock<JetStream>()
        val future = CompletableFuture<PublishAck>()
        whenever(jetStream.publishAsync(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>())).thenReturn(future)

        val publisher = DocumentJobPublisher(jetStreamProvider = { jetStream }, mapper = fakeMapper())
        publisher.publish(Invoice(), 43L)
        future.complete(mock())

        assertEquals(1L, publisher.currentSentCount())
    }

    @Test
    fun `sent counter does not increment when publishAsync fails`() {
        val jetStream = mock<JetStream>()
        val future = CompletableFuture<PublishAck>()
        whenever(jetStream.publishAsync(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>())).thenReturn(future)

        val publisher = DocumentJobPublisher(jetStreamProvider = { jetStream }, mapper = fakeMapper())
        publisher.publish(Invoice(), 44L)
        future.completeExceptionally(RuntimeException("boom"))

        assertEquals(0L, publisher.currentSentCount())
    }

    // ── jobsSubjectOverride's own branch ────────────────────────────
    // jobsSubject falls back to the system property/env default when no override is configured
    // (every other test in this file); every one of those already covers that branch. What was
    // never exercised is the override actually being used *instead of* the fallback.

    @Test
    fun `an explicit jobsSubjectOverride is used instead of the system property fallback`() {
        val jetStream = mock<JetStream>()
        whenever(jetStream.publishAsync(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>()))
            .thenReturn(CompletableFuture())
        val subjectCaptor = argumentCaptor<String>()

        val publisher = DocumentJobPublisher(
            jetStreamProvider = { jetStream },
            jobsSubjectOverride = "custom-jobs-subject",
            mapper = fakeMapper(),
        )
        publisher.publish(Invoice(), 46L)

        verify(jetStream).publishAsync(subjectCaptor.capture(), any<Headers>(), any<ByteArray>(), any<PublishOptions>())
        assertEquals("custom-jobs-subject", subjectCaptor.firstValue, "the configured override must win over the document.jobs.subject system property")
    }

    // ── A null PublishAck on successful completion ──────────────────
    // The jnats client types publishAsync's success value as nullable; `ack?.seqno ?: -1` in the
    // whenComplete handler defends against that. Every other success-path test above completes
    // the future with a real mock() ack, so that defensive branch was never actually exercised.

    @Test
    fun `sent counter still increments and no exception escapes when publishAsync completes with a null ack`() {
        val jetStream = mock<JetStream>()
        val future = CompletableFuture<PublishAck>()
        whenever(jetStream.publishAsync(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>()))
            .thenReturn(future)

        val publisher = DocumentJobPublisher(jetStreamProvider = { jetStream }, mapper = fakeMapper())
        val result = publisher.publish(Invoice(), 47L)
        future.complete(null)

        assertTrue(result.isDone, "the returned stage must still complete even with a null ack")
        assertEquals(1L, publisher.currentSentCount(), "a null ack is still a successful completion (exception == null), so the sent counter must increment")
    }

    // ── concurrency-fix — publish's retry-once on an optimistic-lock conflict ───────────────
    // Without a CDI container, `self` falls back to `this`, so the retry re-enters
    // publishTransactional directly — exactly the seam these tests need: a synchronous throw
    // from publishAsync surfaces the conflict on the first attempt, and the second attempt's
    // invoice-reload fallbacks (no id / no repository wired) are observable via the call count.

    @Test
    fun `a bare OptimisticLockException triggers exactly one retry with the same instance when the invoice has no id`() {
        val jetStream = mock<JetStream>()
        val future = CompletableFuture<PublishAck>()
        whenever(jetStream.publishAsync(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>()))
            .thenThrow(jakarta.persistence.OptimisticLockException("stale on first attempt"))
            .thenReturn(future)

        val publisher = DocumentJobPublisher(jetStreamProvider = { jetStream }, mapper = fakeMapper())
        // id == null: the reload short-circuits and the retry must reuse the very same instance
        val result = publisher.publish(Invoice(), 48L)

        verify(jetStream, org.mockito.kotlin.times(2))
            .publishAsync(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>())
        future.complete(mock())
        assertTrue(result.isDone, "the retry's future must be the one handed back to the caller")
    }

    @Test
    fun `a wrapped StaleObjectStateException is recognized through the cause chain and retried without a repository`() {
        val jetStream = mock<JetStream>()
        whenever(jetStream.publishAsync(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>()))
            .thenThrow(RuntimeException(org.hibernate.StaleObjectStateException("Invoice", 49L)))
            .thenReturn(CompletableFuture())

        val publisher = DocumentJobPublisher(jetStreamProvider = { jetStream }, mapper = fakeMapper())
        // id set but no repository wired: the reload yields null and falls back to the instance
        publisher.publish(anInvoice(id = 49L), 49L)

        verify(jetStream, org.mockito.kotlin.times(2))
            .publishAsync(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>())
    }

    @Test
    fun `a second optimistic-lock failure propagates instead of retrying again`() {
        val jetStream = mock<JetStream>()
        whenever(jetStream.publishAsync(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>()))
            .thenThrow(jakarta.persistence.OptimisticLockException("first"))
            .thenThrow(jakarta.persistence.OptimisticLockException("second"))

        val publisher = DocumentJobPublisher(jetStreamProvider = { jetStream }, mapper = fakeMapper())
        assertThrows(jakarta.persistence.OptimisticLockException::class.java) {
            publisher.publish(Invoice(), 50L)
        }
        verify(jetStream, org.mockito.kotlin.times(2))
            .publishAsync(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>())
    }

    @Test
    fun `publish embeds the mapper output under payload invoice`() {
        val jetStream = mock<JetStream>()
        whenever(jetStream.publishAsync(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>()))
            .thenReturn(CompletableFuture())
        val dto = InvoiceDto(id = 999L, references = InvoiceReferencesDto(buyerReference = "PO-FAKE"))

        val publisher = DocumentJobPublisher(jetStreamProvider = { jetStream }, mapper = fakeMapper(dto))
        publisher.publish(Invoice(), 45L)

        val bodyCaptor = argumentCaptor<ByteArray>()
        verify(jetStream).publishAsync(any<String>(), any<Headers>(), bodyCaptor.capture(), any<PublishOptions>())
        val root = mapper.readTree(String(bodyCaptor.firstValue, Charsets.UTF_8))
        assertEquals(999, root.path("payload").path("invoice").path("id").asInt())
        assertEquals("PO-FAKE", root.path("payload").path("invoice").path("references").path("buyerReference").asText())
    }

    // ── The active wording profile travels in the job payload ────────────────────────────────

    @Test
    fun `RK-7 publish stamps wordingProfile HANDWERK code 0 when no gate is wired`() {
        val jetStream = mock<JetStream>()
        whenever(jetStream.publishAsync(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>()))
            .thenReturn(CompletableFuture())

        val publisher = DocumentJobPublisher(jetStreamProvider = { jetStream }, mapper = fakeMapper())
        publisher.publish(Invoice(), 46L)

        val bodyCaptor = argumentCaptor<ByteArray>()
        verify(jetStream).publishAsync(any<String>(), any<Headers>(), bodyCaptor.capture(), any<PublishOptions>())
        val root = mapper.readTree(String(bodyCaptor.firstValue, Charsets.UTF_8))
        assertEquals(0, root.path("payload").path("invoice").path("meta").path("wordingProfile").asInt())
    }

    @Test
    fun `RK-7 publish stamps the gate's current profile code when one is wired`() {
        val jetStream = mock<JetStream>()
        whenever(jetStream.publishAsync(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>()))
            .thenReturn(CompletableFuture())
        val gate = WordingSettingsGate(InMemorySystemSettingRepository(), "BERATUNG", time = FIXED_TIME_SOURCE)

        val publisher = DocumentJobPublisher(jetStreamProvider = { jetStream }, mapper = fakeMapper())
        publisher.wireWordingSettingsGateForTest(gate)
        publisher.publish(Invoice(), 47L)

        val bodyCaptor = argumentCaptor<ByteArray>()
        verify(jetStream).publishAsync(any<String>(), any<Headers>(), bodyCaptor.capture(), any<PublishOptions>())
        val root = mapper.readTree(String(bodyCaptor.firstValue, Charsets.UTF_8))
        assertEquals(WordingProfile.BERATUNG.code, root.path("payload").path("invoice").path("meta").path("wordingProfile").asInt())
    }

    // ── Engine on the NATS payload ───────────────────────

    @Test
    fun `publish sends the constant MUSTANG engine on the payload`() {
        val jetStream = mock<JetStream>()
        whenever(jetStream.publishAsync(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>()))
            .thenReturn(CompletableFuture.completedFuture(mock()))
        val bodyCaptor = argumentCaptor<ByteArray>()

        val publisher = DocumentJobPublisher(jetStreamProvider = { jetStream }, mapper = fakeMapper())
        publisher.publish(Invoice(), 42L)

        verify(jetStream).publishAsync(any<String>(), any<Headers>(), bodyCaptor.capture(), any<PublishOptions>())
        val root = mapper.readTree(String(bodyCaptor.firstValue, Charsets.UTF_8))
        assertEquals("MUSTANG", root.path("engine").asText())
    }

    // ── T9 — user request AND global switch, ANDed together ────────────────────────────
    // The combination sits in the publisher because that is the one place every job must pass
    // through. A visibility rule in the UI can be bypassed (a direct call, a second caller, a
    // future API); this point cannot.

    @Test
    fun `T9-02 - abgeschalteter Schalter ueberstimmt den Anwenderwunsch`() {
        val options = publishAndCaptureOptions(
            llmOptimize = true,
            gate = validationSettingsGate(ValidationCapabilityId.OMLX_LLM_CHECK),
        )
        assertFalse(options.has("llmOptimize"), "abgeschaltet heisst: die Option steht gar nicht erst im Auftrag")
    }

    @Test
    fun `T9-03 - eingeschalteter Schalter macht aus einem Nein kein Ja`() {
        val options = publishAndCaptureOptions(llmOptimize = false, gate = validationSettingsGate())
        assertFalse(options.has("llmOptimize"), "der Schalter erlaubt die Pruefung, er fordert sie nicht an")
    }

    @Test
    fun `T9 - beides an ergibt die Option im Auftrag`() {
        val options = publishAndCaptureOptions(llmOptimize = true, gate = validationSettingsGate())
        assertTrue(options.path("llmOptimize").asBoolean(), "Anwenderwunsch UND Schalter: die Pruefung wird angefordert")
    }

    @Test
    fun `T9 - ohne Schalter bleibt das Verhalten wie vor T9`() {
        val options = publishAndCaptureOptions(llmOptimize = true, gate = null)
        assertTrue(options.path("llmOptimize").asBoolean())
    }

    /**
     * The explicit
     * four-combination table for `llmRequested = llmOptimize && gate.isEnabled(OMLX_LLM_CHECK)`.
     * Three of the four cells already exist above (as separate tests); this is the
     * one PO explicitly named as missing: both off. All four are restated together here so the
     * full truth table is visible in one place, not scattered across differently-named tests.
     */
    @Test
    fun `AP-M10 - vier Kombinationen aus Anwenderwunsch und Systemschalter fuer llmRequested`() {
        // wish=true,  switch=on  -> requested
        assertTrue(
            publishAndCaptureOptions(llmOptimize = true, gate = validationSettingsGate())
                .path("llmOptimize").asBoolean(),
        )
        // wish=true,  switch=off -> not requested
        assertFalse(
            publishAndCaptureOptions(llmOptimize = true, gate = validationSettingsGate(ValidationCapabilityId.OMLX_LLM_CHECK))
                .has("llmOptimize"),
        )
        // wish=false, switch=on  -> not requested (the switch permits, it does not demand)
        assertFalse(
            publishAndCaptureOptions(llmOptimize = false, gate = validationSettingsGate())
                .has("llmOptimize"),
        )
        // wish=false, switch=off -> not requested (the fourth, previously untested cell)
        assertFalse(
            publishAndCaptureOptions(llmOptimize = false, gate = validationSettingsGate(ValidationCapabilityId.OMLX_LLM_CHECK))
                .has("llmOptimize"),
            "beides aus muss ebenso wenig anfordern wie jede andere Kombination ohne beidseitiges Ja",
        )
    }

    // ── veraPDF has no user-facing request of its own ────────────────────────────
    // Unlike oMLX, there is no checkbox for this in the print dialog: the switch itself decides
    // whether the option ends up in the job.

    @Test
    fun `T9b-04 - abgeschalteter veraPDF-Schalter laesst die Option ganz weg`() {
        val options = publishAndCaptureOptions(
            llmOptimize = false,
            gate = validationSettingsGate(ValidationCapabilityId.VERAPDF_CHECK),
        )
        assertFalse(options.has("veraPdfCheck"), "abgeschaltet heisst: keine veraPDF-Stufe im Exportjob")
    }

    @Test
    fun `T9b - eingeschalteter veraPDF-Schalter setzt die Option`() {
        val options = publishAndCaptureOptions(llmOptimize = false, gate = validationSettingsGate())
        assertTrue(options.path("veraPdfCheck").asBoolean(), "eingeschaltet heisst: die optionale Stufe wird angefordert")
    }

    @Test
    fun `T9b - ohne Gate bleibt veraPDF wie fail-open vorgesehen an`() {
        val options = publishAndCaptureOptions(llmOptimize = false, gate = null)
        assertTrue(options.path("veraPdfCheck").asBoolean(), "kein Gate verdrahtet (reiner Unit-Test) heisst fail-open, nicht aus")
    }

    // ── KoSIT switch: only the off state travels ─────────────────────────────────

    @Test
    fun `abgeschalteter KoSIT-Schalter wird als kositCheck false mitgegeben`() {
        val options = publishAndCaptureOptions(
            llmOptimize = false,
            gate = validationSettingsGate(ValidationCapabilityId.KOSIT_CHECK),
        )
        assertFalse(options.path("kositCheck").asBoolean(true), "abgeschaltet heisst: kositCheck=false im Exportjob")
        assertTrue(options.has("kositCheck"))
    }

    @Test
    fun `eingeschalteter oder fehlender KoSIT-Schalter laesst die Option weg - der Worker liest das als an`() {
        assertFalse(publishAndCaptureOptions(llmOptimize = false, gate = validationSettingsGate()).has("kositCheck"))
        assertFalse(publishAndCaptureOptions(llmOptimize = false, gate = null).has("kositCheck"))
    }

    /** [de.littletrotter.domain.invoice.GenerationJob.llmOptimize] is what [publish]
     *  now reads instead of a caller-supplied argument, and [publish] also reads the
     *  invoice to map off [de.littletrotter.domain.invoice.GenerationJob.invoice] rather than a
     *  separate parameter; this builds the minimal job carrying both. */
    private fun jobWith(
        id: Long,
        llmOptimize: Boolean = false,
        invoice: Invoice = Invoice(),
    ): de.littletrotter.domain.invoice.GenerationJob =
        aGenerationJob {
            this.id = id
            this.llmOptimize = llmOptimize
            this.invoice = invoice
        }

    // ── publish() reads the invoice off the job, not a separate parameter ──────────

    @Test
    fun `publish throws when the job has no associated invoice`() {
        val jetStream = mock<JetStream>()
        val publisher = DocumentJobPublisher(jetStreamProvider = { jetStream }, mapper = fakeMapper())
        val job = aGenerationJob { id = 999L } // invoice stays null

        val ex = assertThrows(IllegalArgumentException::class.java) { publisher.publish(job) }
        assertTrue(
            ex.message.orEmpty().contains("999"),
            "the failure must name the job so an operator can tell which job had no invoice",
        )
        verify(jetStream, never()).publishAsync(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>())
    }

    @Test
    fun `granted output intent is passed verbatim as job option`() {
        val options = publishAndCaptureOptions(
            llmOptimize = false,
            gate = null,
            outputIntentGate = de.littletrotter.application.settings.OutputIntentSettingGate(
                de.littletrotter.application.settings.InMemorySystemSettingRepository(
                    mapOf(de.littletrotter.application.settings.OutputIntentSettingGate.KEY to " grant.token "),
                ),
            ),
        )
        assertEquals("grant.token", options.path("outputIntent").asText())
    }

    @Test
    fun `absent output intent leaves the option out`() {
        val options = publishAndCaptureOptions(
            llmOptimize = false,
            gate = null,
            outputIntentGate = de.littletrotter.application.settings.OutputIntentSettingGate(
                de.littletrotter.application.settings.InMemorySystemSettingRepository(),
            ),
        )
        assertFalse(options.has("outputIntent"))
    }

    @Test
    fun `no output intent gate wired leaves the option out`() {
        assertFalse(publishAndCaptureOptions(llmOptimize = false, gate = null).has("outputIntent"))
    }

    private fun publishAndCaptureOptions(
        llmOptimize: Boolean,
        gate: de.littletrotter.application.reference.ValidationSettingsGate?,
        outputIntentGate: de.littletrotter.application.settings.OutputIntentSettingGate? = null,
    ): com.fasterxml.jackson.databind.JsonNode {
        val jetStream = mock<JetStream>()
        whenever(jetStream.publishAsync(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>()))
            .thenReturn(CompletableFuture())
        DocumentJobPublisher(jetStreamProvider = { jetStream }, validationSettingsGate = gate, mapper = fakeMapper())
            .apply { wireOutputIntentSettingGateForTest(outputIntentGate) }
            .publish(jobWith(900L, llmOptimize = llmOptimize))

        val bodyCaptor = argumentCaptor<ByteArray>()
        verify(jetStream).publishAsync(any<String>(), any<Headers>(), bodyCaptor.capture(), any<PublishOptions>())
        return mapper.readTree(String(bodyCaptor.firstValue, Charsets.UTF_8)).path("options")
    }

    // ── metricsRegistered — only reachable via the CDI constructor ────────────
    // The plain unit-test constructor calls above never supply a MeterRegistry (the parameter
    // only exists on the @Inject constructor); without a call like this, the
    // "meterRegistry != null" branch stays permanently untested.

    @Test
    fun `publish registriert die Sende- und Fehlschlag-Gauges wenn eine MeterRegistry verdrahtet ist`() {
        val jetStream = mock<JetStream>()
        whenever(jetStream.publishAsync(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>()))
            .thenReturn(CompletableFuture.completedFuture(mock()))
        val registry = SimpleMeterRegistry()
        // No Provider<Self>: the CDI-injected DocumentJobPublishTransaction bean is what actually owns
        // the metrics/JetStream machinery, wired here via its own @Inject constructor exactly as
        // CDI would, then handed to DocumentJobPublisher's own @Inject constructor the same way.
        val publishTransaction = DocumentJobPublishTransaction(
            jetStream = jetStream,
            meterRegistry = registry,
            validationSettingsGate = mock(),
            mapper = fakeMapper(),
            invoiceRepository = mock(),
            wordingSettingsGate = mock(),
            // Omitted deliberately (unlike the other collaborators): exercises the @Inject
            // constructor's own defaulted-parameter path (see its KDoc — CDI itself never uses
            // this default, only a direct Kotlin call site can), and this test's own assertions
            // never touch generationJobRepository/template-fingerprint recording anyway.
        )
        val publisher = DocumentJobPublisher(publishTransaction, DocumentJobDispatchGate(publishTransaction), mock())

        publisher.publish(Invoice(), 1L)

        assertTrue(
            registry.meters.any { it.id.name == "nats.document_jobs.sent_total" },
            "die CDI-verdrahtete MeterRegistry muss die Sende-Gauge tatsaechlich registrieren",
        )
    }

    // ── skipValidation option ────────────────────────────────────────────────────

    @Test
    fun `publish setzt die skipValidation-Option im Auftrag wenn der Aufrufer sie anfordert`() {
        val jetStream = mock<JetStream>()
        whenever(jetStream.publishAsync(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>()))
            .thenReturn(CompletableFuture())
        val bodyCaptor = argumentCaptor<ByteArray>()

        DocumentJobPublisher(jetStreamProvider = { jetStream }, mapper = fakeMapper()).publish(Invoice(), 1L, skipValidation = true)

        verify(jetStream).publishAsync(any<String>(), any<Headers>(), bodyCaptor.capture(), any<PublishOptions>())
        val options = mapper.readTree(String(bodyCaptor.firstValue, Charsets.UTF_8)).path("options")
        assertTrue(options.path("skipValidation").asBoolean())
    }

    // ── R3/R7 — publish() flips the invoice to PENDING ──

    @Test
    fun `publish sets the invoice's validationStatus to PENDING before sending`() {
        val jetStream = mock<JetStream>()
        whenever(jetStream.publishAsync(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>()))
            .thenReturn(CompletableFuture())
        val invoice = anInvoice(validationStatus = de.littletrotter.domain.shared.ValidationStatus.NOT_VALIDATED)

        DocumentJobPublisher(jetStreamProvider = { jetStream }, mapper = fakeMapper())
            .publish(invoice, 10L)

        assertEquals(de.littletrotter.domain.shared.ValidationStatus.PENDING, invoice.validationStatus)
    }

    @Test
    fun `publish persists the PENDING flip through the invoice repository when one is wired`() {
        val jetStream = mock<JetStream>()
        whenever(jetStream.publishAsync(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>()))
            .thenReturn(CompletableFuture())
        val invoiceRepository = mock<de.littletrotter.domain.repository.InvoiceRepository>()
        val invoice = anInvoice(id = 55L)

        DocumentJobPublisher(jetStreamProvider = { jetStream }, mapper = fakeMapper(), invoiceRepository = invoiceRepository)
            .publish(invoice, 11L)

        // concurrency-fix — no longer a plain save()/merge on the managed-or-detached `invoice`
        // instance (that em.merge() is exactly what threw OptimisticLockException/
        // StaleObjectStateException under a concurrent writer or a stale UI-held copy — see
        // DocumentJobPublisher.publishTransactional's own KDoc). The PENDING flip now goes through
        // a targeted, version-unchecked bulk update instead.
        verify(invoiceRepository).updateValidationStatus(55L, de.littletrotter.domain.shared.ValidationStatus.PENDING)
        verify(invoiceRepository).detachIfManaged(invoice)
    }

    // ── concurrency-fix — the resilience layer: publish() retries once, against a freshly
    // reloaded invoice, if an optimistic-lock conflict happens anyway (a write this method
    // doesn't control, or a future regression). See DocumentJobPublisher.publish's own KDoc.

    @Test
    fun `publish retries once after an optimistic lock conflict, against a freshly reloaded invoice`() {
        val jetStream = mock<JetStream>()
        whenever(jetStream.publishAsync(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>()))
            .thenReturn(CompletableFuture())
        val staleInvoice = anInvoice(id = 900L)
        val freshInvoice = anInvoice(id = 900L)
        val invoiceRepository = mock<de.littletrotter.domain.repository.InvoiceRepository>()
        whenever(invoiceRepository.findByIdWithItems(900L)).thenReturn(freshInvoice)

        val seenInvoices = mutableListOf<Invoice>()
        var attempt = 0
        val mapper = object : InvoiceDtoMapper() {
            override fun mapToDto(inv: Invoice, wordingProfile: WordingProfile, issued: Boolean): InvoiceDto {
                attempt++
                seenInvoices.add(inv)
                // The first attempt reproduces the production incident: a version-checked flush
                // failing mid-mapToDto (PanacheReceiptRepository.findForInvoice's own
                // auto-flush-before-query tripping over a dirty invoice — see
                // DocumentJobPublisher.publishTransactional's own KDoc for the confirmed call
                // chain). Narayana/Arjuna wraps this as RollbackException -> PersistenceException
                // -> OptimisticLockException/StaleObjectStateException when it happens at commit
                // instead, but a bare OptimisticLockException here exercises the same catch
                // without needing a real JTA transaction in this plain unit test.
                if (attempt == 1) {
                    throw jakarta.persistence.OptimisticLockException(
                        "Row was already updated or deleted by another transaction for entity Invoice",
                    )
                }
                return InvoiceDto()
            }
        }

        val publisher = DocumentJobPublisher(jetStreamProvider = { jetStream }, mapper = mapper, invoiceRepository = invoiceRepository)
        val result = publisher.publish(staleInvoice, 901L)

        assertEquals(2, attempt, "the mapper must run twice: the doomed first attempt, then the retry")
        assertEquals(staleInvoice, seenInvoices[0], "the first attempt uses the caller's own (possibly stale) invoice")
        assertEquals(freshInvoice, seenInvoices[1], "the retry uses a freshly reloaded invoice, not the doomed stale instance")
        verify(jetStream).publishAsync(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>())
        assertFalse(result.isCompletedExceptionally, "a successful retry must not leave the caller with a failed future")
    }

    @Test
    fun `publish propagates a second consecutive optimistic lock conflict rather than retrying forever`() {
        val jetStream = mock<JetStream>()
        whenever(jetStream.publishAsync(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>()))
            .thenReturn(CompletableFuture())
        val invoice = anInvoice(id = 900L)
        val invoiceRepository = mock<de.littletrotter.domain.repository.InvoiceRepository>()
        whenever(invoiceRepository.findByIdWithItems(900L)).thenReturn(invoice)

        val mapper = object : InvoiceDtoMapper() {
            override fun mapToDto(inv: Invoice, wordingProfile: WordingProfile, issued: Boolean): InvoiceDto {
                throw jakarta.persistence.OptimisticLockException("still stale")
            }
        }
        val publisher = DocumentJobPublisher(jetStreamProvider = { jetStream }, mapper = mapper, invoiceRepository = invoiceRepository)

        val ex = assertThrows(jakarta.persistence.OptimisticLockException::class.java) {
            publisher.publish(invoice, 902L)
        }
        assertEquals("still stale", ex.message)
        verify(jetStream, never()).publishAsync(any<String>(), any<Headers>(), any<ByteArray>(), any<PublishOptions>())
    }

    @Test
    fun `publish never retries a non-lock failure`() {
        val jetStream = mock<JetStream>()
        val mapper = object : InvoiceDtoMapper() {
            var calls = 0
            override fun mapToDto(inv: Invoice, wordingProfile: WordingProfile, issued: Boolean): InvoiceDto {
                calls++
                error("unrelated mapping bug")
            }
        }
        val publisher = DocumentJobPublisher(jetStreamProvider = { jetStream }, mapper = mapper)

        assertThrows(IllegalStateException::class.java) { publisher.publish(Invoice(), 903L) }
        assertEquals(1, mapper.calls, "a non-lock exception must not trigger the retry path")
    }
}
