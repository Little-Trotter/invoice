package de.littletrotter.application.shared

import com.fasterxml.jackson.databind.ObjectMapper
import de.littletrotter.domain.compliance.InvoiceAuditAction
import jakarta.persistence.EntityManager
import jakarta.persistence.Query
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class AuditActionOverrideTest {

    private val events = mutableListOf<String>()

    private fun em(previousAction: String?, previousContext: String?): EntityManager {
        val em = mock<EntityManager>()
        val read = mock<Query>()
        var readName = ""
        whenever(read.setParameter(eq("name"), anyOrNull())).thenAnswer {
            readName = it.getArgument<String>(1)
            read
        }
        whenever(read.singleResult).thenAnswer {
            if (readName == AuditActionOverride.ACTION_GUC) previousAction else previousContext
        }
        val set = mock<Query>()
        var setName = ""
        var setValue = ""
        whenever(set.setParameter(eq("name"), anyOrNull())).thenAnswer {
            setName = it.getArgument<String>(1)
            set
        }
        whenever(set.setParameter(eq("value"), anyOrNull())).thenAnswer {
            setValue = it.getArgument<String>(1)
            events += "$setName=$setValue"
            set
        }
        whenever(set.singleResult).thenReturn("x")
        whenever(em.createNativeQuery("SELECT current_setting(:name, true)")).thenReturn(read)
        whenever(em.createNativeQuery("SELECT set_config(:name, :value, true)")).thenReturn(set)
        return em
    }

    @Test
    fun `sets action and context, runs the block and restores the previous values`() {
        val sut = AuditActionOverride(em("7", """{"a":1}"""), ObjectMapper())

        val result = sut.withAction(InvoiceAuditAction.STORNO, mapOf("reason" to "X", "dropped" to null)) {
            events += "block"
            42
        }

        assertEquals(42, result)
        assertEquals(
            listOf(
                "app.audit_action=${InvoiceAuditAction.STORNO.ordinal}",
                """app.audit_context={"reason":"X"}""",
                "block",
                "app.audit_action=7",
                """app.audit_context={"a":1}""",
            ),
            events,
        )
    }

    @Test
    fun `a previously empty or unset setting is restored to the empty string`() {
        val sut = AuditActionOverride(em(null, ""), ObjectMapper())

        sut.withAction(InvoiceAuditAction.STORNO, emptyMap()) { }

        assertEquals(listOf("app.audit_action=", "app.audit_context="), events.takeLast(2))
    }

    @Test
    fun `the previous values are restored when the block throws`() {
        val sut = AuditActionOverride(em("3", null), ObjectMapper())

        assertThrows(IllegalStateException::class.java) {
            sut.withAction(InvoiceAuditAction.STORNO, mapOf("k" to "v")) { error("boom") }
        }

        assertEquals(listOf("app.audit_action=3", "app.audit_context="), events.takeLast(2))
    }
}
