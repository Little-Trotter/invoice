package de.littletrotter.xpdf.impl

import de.littletrotter.shared.BasePdfRendererPort
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositValidationResult
import de.littletrotter.shared.KositValidatorPort
import de.littletrotter.shared.time.ClockTimeSource
import de.littletrotter.shared.time.TimeSource
import de.littletrotter.xpdf.testsupport.FixtureBaseRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.time.Instant

/**
 * [RenderWarmup] never touches S3/NATS/the database (see its class KDoc) — it is a pure
 * in-process pipeline run, so its logic (the enabled switch, the per-shape best-effort loop)
 * is directly testable with real [XmlExportServiceImpl]/[de.littletrotter.xpdf.testsupport.FixtureBaseRenderer] instances and
 * only [KositValidatorPort] mocked (a real KoSIT run needs no proof here — its own tests cover
 * that; this class only cares that it gets called and a failure from it doesn't abort the run).
 *
 * The async [Thread] launch in `scheduleWarmUp()` is intentionally bypassed via reflection into
 * the private `warmUp()` method, so the assertions below run synchronously instead of racing a
 * daemon thread.
 */
class RenderWarmupTest {

    private val fixedTime: TimeSource = ClockTimeSource.fixedAt(Instant.parse("2026-01-15T10:30:00Z"))

    private fun warmUpMethod() = RenderWarmup::class.java.getDeclaredMethod("warmUp").apply { isAccessible = true }

    @Test
    fun `scheduleWarmUp does nothing and touches no dependency when disabled`() {
        val xmlExportService: XmlExportServiceImpl = mock()
        val reportRenderer: BasePdfRendererPort = mock()
        val kositValidator: KositValidatorPort = mock()
        val warmup = RenderWarmup(xmlExportService, reportRenderer, kositValidator, fixedTime, enabled = false)

        warmup.scheduleWarmUp()

        verifyNoInteractions(xmlExportService)
        verifyNoInteractions(reportRenderer)
        verifyNoInteractions(kositValidator)
    }

    // Every other test either disables the warm-up (verifying it does nothing) or
    // bypasses scheduleWarmUp() entirely via warmUpMethod() reflection, so the actual
    // `Thread(::warmUp, ...).start()` call — and the constructor's own default `enabled = true`,
    // since every other construction here passes it explicitly — had no real test. This is the
    // one place startup's async contract (class KDoc: "startup stays fast, ... nothing keeps the
    // service from coming up") is actually exercised end to end.
    @Test
    fun `scheduleWarmUp actually starts the warm-up on a background thread when enabled by default`() {
        val kositValidator: KositValidatorPort = mock()
        whenever(kositValidator.validate(any(), any())).thenReturn(KositValidationResult(status = KositOutcome.VALID))
        // `enabled` omitted on purpose — exercises its default value, not an explicit true/false.
        val warmup = RenderWarmup(
            xmlExportService = XmlExportServiceImpl(),
            reportRenderer = FixtureBaseRenderer(),
            kositValidator = kositValidator,
            time = fixedTime,
        )

        warmup.scheduleWarmUp()

        // The daemon thread runs asynchronously; poll via Mockito's own timeout rather than a
        // fixed sleep — both dummy shapes must reach the (mocked) validator once the thread runs.
        org.mockito.Mockito.verify(kositValidator, org.mockito.Mockito.timeout(20_000).times(2)).validate(any(), any())
    }

    @Test
    fun `warmUp runs every dummy shape through the real export pipeline without throwing`() {
        val kositValidator: KositValidatorPort = mock()
        whenever(kositValidator.validate(any(), any())).thenReturn(KositValidationResult(status = KositOutcome.VALID))
        val warmup = RenderWarmup(
            xmlExportService = XmlExportServiceImpl(),
            reportRenderer = FixtureBaseRenderer(),
            kositValidator = kositValidator,
            time = fixedTime,
            enabled = true,
        )

        // Best-effort by design (class KDoc): must complete without propagating, whatever the
        // per-shape outcome — this is the meaningful assertion for a warm-up path.
        warmUpMethod().invoke(warmup)

        org.mockito.kotlin.verify(kositValidator, org.mockito.kotlin.times(2)).validate(any(), any())
    }

    @Test
    fun `warmUp survives a validator that throws on every shape, one failure at a time`() {
        val kositValidator: KositValidatorPort = mock()
        whenever(kositValidator.validate(any(), any())).thenThrow(RuntimeException("kosit unavailable"))
        val warmup = RenderWarmup(
            xmlExportService = XmlExportServiceImpl(),
            reportRenderer = FixtureBaseRenderer(),
            kositValidator = kositValidator,
            time = fixedTime,
            enabled = true,
        )

        // Must not throw out of warmUp() even though every single shape fails.
        warmUpMethod().invoke(warmup)

        org.mockito.kotlin.verify(kositValidator, org.mockito.kotlin.times(2)).validate(any(), any())
    }

    @Test
    fun `dummyInvoices covers both the single-rate and two-rate tax shapes`() {
        val method = RenderWarmup::class.java.getDeclaredMethod("dummyInvoices").apply { isAccessible = true }
        val warmup = RenderWarmup(mock(), mock(), mock(), fixedTime, enabled = true)

        val result = method.invoke(warmup)
        check(result is List<*>) { "expected dummyInvoices to return a List, got ${result?.javaClass}" }
        val invoices = result.filterIsInstance<InvoiceDto>()
        check(invoices.size == result.size) { "dummyInvoices returned elements that are not InvoiceDto" }

        assertEquals(2, invoices.size)
        assertEquals(1, invoices[0].items.size)
        assertEquals(2, invoices[1].items.size)
    }
}
