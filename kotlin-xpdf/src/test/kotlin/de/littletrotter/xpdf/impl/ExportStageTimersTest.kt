package de.littletrotter.xpdf.impl

import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.InvoiceNumberDto
import de.littletrotter.shared.ReceiptDto
import de.littletrotter.xpdf.ExportLane
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class ExportStageTimersTest {

    private val registry = SimpleMeterRegistry()
    private val timers = ExportStageTimers(registry)

    private fun invoice(receiptCount: Int) = InvoiceDto(
        invoiceNumber = InvoiceNumberDto("INV-1"),
        items = emptyList(),
        receipts = (1..receiptCount).map { ReceiptDto(fileName = "r$it.pdf", objectKey = "k$it") },
    )

    private fun stageTimer(stage: String, lane: String, receipts: String) =
        registry.find("xpdf.export.stage").tags("stage", stage, "lane", lane, "receipts", receipts).timer()

    @ParameterizedTest
    @CsvSource("0,0", "1,1-5", "5,1-5", "6,6-19", "19,6-19", "25,6-19")
    fun `receipt class boundaries`(count: Int, expected: String) {
        assertEquals(expected, ReceiptClass.of(count).tag)
        timers.job(invoice(minOf(count, 25))) { timers.record(ExportStage.RENDER) { } }
        assertNotNull(stageTimer("render", "unknown", expected))
    }

    @Test
    fun `negative count falls into the zero class`() {
        assertEquals(ReceiptClass.NONE, ReceiptClass.of(-1))
    }

    @Test
    fun `stage timer carries stage, lane and receipt class`() {
        ExportLane.within("bulk") {
            timers.job(invoice(3)) { timers.record(ExportStage.KOSIT) { Thread.sleep(2) } }
        }
        val timer = stageTimer("kosit", "bulk", "1-5")
        assertNotNull(timer)
        assertEquals(1, timer!!.count())
    }

    @Test
    fun `every stage has its lowercase tag`() {
        timers.job(invoice(0)) { ExportStage.entries.forEach { s -> timers.record(s) { } } }
        ExportStage.entries.forEach { assertNotNull(stageTimer(it.tag, "unknown", "0"), it.name) }
        assertEquals(10, ExportStage.entries.size)
    }

    @Test
    fun `a stage that is never recorded leaves no timer`() {
        timers.job(invoice(0)) { timers.record(ExportStage.RENDER) { } }
        assertNull(stageTimer("verapdf", "unknown", "0"))
    }

    @Test
    fun `stage outside a job scope records nothing but still runs`() {
        assertEquals(7, timers.record(ExportStage.RENDER) { 7 })
        assertNull(registry.find("xpdf.export.stage").timer())
    }

    @Test
    fun `successful job records outcome success`() {
        ExportLane.within("interactive") { timers.job(invoice(0)) { } }
        val timer = registry.find("xpdf.export.job").tags("lane", "interactive", "receipts", "0", "outcome", "success").timer()
        assertNotNull(timer)
        assertEquals(1, timer!!.count())
    }

    @Test
    fun `failing job records outcome failure and rethrows`() {
        assertThrows(IllegalStateException::class.java) {
            timers.job(invoice(0)) { error("boom") }
        }
        assertNotNull(registry.find("xpdf.export.job").tags("outcome", "failure").timer())
        assertNull(registry.find("xpdf.export.job").tags("outcome", "success").timer())
    }

    @Test
    fun `failing stage is still timed and the scope is restored afterwards`() {
        assertThrows(IllegalStateException::class.java) {
            timers.job(invoice(0)) { timers.record(ExportStage.PDFA) { error("boom") } }
        }
        assertNotNull(stageTimer("pdfa", "unknown", "0"))
        // Scope is gone: a later stage call outside a job adds no sample.
        timers.record(ExportStage.STORE) { }
        assertNull(stageTimer("store", "unknown", "0"))
    }

    @Test
    fun `lane scope is restored after nesting`() {
        ExportLane.within("bulk") {
            ExportLane.within("interactive") { assertEquals("interactive", ExportLane.current()) }
            assertEquals("bulk", ExportLane.current())
        }
        assertEquals(ExportLane.UNKNOWN, ExportLane.current())
    }

    @Test
    fun `nested job restores the outer scope`() {
        timers.job(invoice(0)) {
            timers.job(invoice(6)) { }
            timers.record(ExportStage.RENDER) { }
        }
        assertNotNull(stageTimer("render", "unknown", "0"))
    }
}
