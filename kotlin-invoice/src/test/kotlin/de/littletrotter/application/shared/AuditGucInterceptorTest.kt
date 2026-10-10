package de.littletrotter.application.shared

import de.littletrotter.domain.compliance.InvoiceAuditAction
import jakarta.interceptor.InvocationContext
import jakarta.persistence.EntityManager
import jakarta.persistence.Query
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.InOrder
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * [AuditGucInterceptor.setGucs] against a mocked [EntityManager]/[InvocationContext],
 * the same "test the interceptor's own logic without a CDI container" idiom
 * [de.littletrotter.application.security.PermissionInterceptorTest] uses: the real CDI
 * interception (an `@SetsAuditGuc`-annotated bean method actually going through the interceptor
 * chain, with the GUC visible to a real trigger) needs a live CDI container and a database —
 * that's [de.littletrotter.infrastructure.persistence.InvoiceAuditJournalCaptureTriggerIT]'s
 * job. This class covers the interceptor's own SQL/param wiring and its "always call proceed()"
 * contract.
 */
class AuditGucInterceptorTest {

    companion object {
        // Mirrors AuditGucInterceptor's own two private query constants — kept literal here
        // (rather than referencing the private constants) since a test asserting the exact SQL
        // the interceptor issues must not silently track a source change to what it verifies.
        private const val GUC_QUERY =
            "SELECT set_config('app.actor', :actor, true), " +
                "set_config('app.actor_name', :actorName, true), " +
                "CASE WHEN COALESCE(current_setting('app.audit_action', true), '') = '' " +
                "THEN set_config('app.audit_action', :action, true) " +
                "ELSE current_setting('app.audit_action', true) END"
        private const val LOCK_QUERY = "SELECT pg_advisory_xact_lock(:lockKey)"
    }

    /** Reflection target: the interceptor reads the action off the intercepted method. */
    private class AnnotatedTarget {
        @SetsAuditGuc(action = InvoiceAuditAction.ITEM_ADDED)
        fun addItem() = Unit
    }

    private fun mockQuery(): Query {
        val query = mock<Query>()
        whenever(query.setParameter(any<String>(), anyOrNull())).thenReturn(query)
        whenever(query.singleResult).thenReturn(arrayOf<Any?>(null, null))
        return query
    }

    /** Two distinct query mocks, one per statement [AuditGucInterceptor.setGucs] issues, wired to
     *  the exact SQL each one is created with — so a test asserting GUC-query behavior never
     *  double-counts an invocation the lock query made on a shared mock, and vice versa. */
    private fun mockEm(gucQuery: Query, lockQuery: Query): EntityManager {
        val em = mock<EntityManager>()
        whenever(em.createNativeQuery(GUC_QUERY)).thenReturn(gucQuery)
        whenever(em.createNativeQuery(LOCK_QUERY)).thenReturn(lockQuery)
        return em
    }

    private fun mockContext(): InvocationContext {
        val context = mock<InvocationContext>()
        whenever(context.method).thenReturn(AnnotatedTarget::class.java.getDeclaredMethod("addItem"))
        return context
    }

    @Test
    fun `RS-8 - sets app_actor and app_actor_name via set_config before proceeding`() {
        val gucQuery = mockQuery()
        val em = mockEm(gucQuery, mockQuery())
        val auditActorProvider = mock<AuditActorProvider>()
        whenever(auditActorProvider.currentActorId()).thenReturn("oidc-sub-123")
        whenever(auditActorProvider.currentActorDisplayName()).thenReturn("alice")
        val context = mockContext()
        whenever(context.proceed()).thenReturn("ok")

        val result = AuditGucInterceptor(em, auditActorProvider).setGucs(context)

        assertEquals("ok", result)
        verify(em).createNativeQuery(GUC_QUERY)
        verify(gucQuery).setParameter("actor", "oidc-sub-123")
        verify(gucQuery).setParameter("actorName", "alice")
        verify(gucQuery).setParameter("action", InvoiceAuditAction.ITEM_ADDED.ordinal.toString())
        verify(gucQuery).singleResult
        verify(context).proceed()
    }

    @Test
    fun `ST-A2 - acquires pg_advisory_xact_lock(1308) right after the GUCs and before proceed`() {
        val gucQuery = mockQuery()
        val lockQuery = mockQuery()
        val em = mockEm(gucQuery, lockQuery)
        val auditActorProvider = mock<AuditActorProvider>()
        whenever(auditActorProvider.currentActorId()).thenReturn("oidc-sub-123")
        whenever(auditActorProvider.currentActorDisplayName()).thenReturn("alice")
        val context = mockContext()
        whenever(context.proceed()).thenReturn("ok")

        AuditGucInterceptor(em, auditActorProvider).setGucs(context)

        verify(em).createNativeQuery(LOCK_QUERY)
        verify(lockQuery).setParameter("lockKey", 1308L)
        verify(lockQuery).singleResult
        val order: InOrder = inOrder(gucQuery, lockQuery, context)
        order.verify(gucQuery).setParameter("action", InvoiceAuditAction.ITEM_ADDED.ordinal.toString())
        order.verify(gucQuery).singleResult
        order.verify(lockQuery).singleResult
        order.verify(context).proceed()
    }

    @Test
    fun `RS-8 - a null display name (background job, no identity) is passed through as SQL NULL`() {
        val gucQuery = mockQuery()
        val em = mockEm(gucQuery, mockQuery())
        val auditActorProvider = mock<AuditActorProvider>()
        whenever(auditActorProvider.currentActorId()).thenReturn("system")
        whenever(auditActorProvider.currentActorDisplayName()).thenReturn(null)
        val context = mockContext()
        whenever(context.proceed()).thenReturn(Unit)

        AuditGucInterceptor(em, auditActorProvider).setGucs(context)

        verify(gucQuery).setParameter("actor", "system")
        verify(gucQuery).setParameter(eq("actorName"), isNull())
    }

    @Test
    fun `RS-8 - proceed's own return value is passed through unchanged`() {
        val gucQuery = mockQuery()
        val em = mockEm(gucQuery, mockQuery())
        val auditActorProvider = mock<AuditActorProvider>()
        whenever(auditActorProvider.currentActorId()).thenReturn("system")
        whenever(auditActorProvider.currentActorDisplayName()).thenReturn(null)
        val context = mockContext()
        val sentinel = Any()
        whenever(context.proceed()).thenReturn(sentinel)

        val result = AuditGucInterceptor(em, auditActorProvider).setGucs(context)

        assertEquals(sentinel, result)
    }

    @Test
    fun `II4-A - a method with no SetsAuditGuc action falls back to UNSPECIFIED`() {
        val gucQuery = mockQuery()
        val em = mockEm(gucQuery, mockQuery())
        val auditActorProvider = mock<AuditActorProvider>()
        whenever(auditActorProvider.currentActorId()).thenReturn("system")
        whenever(auditActorProvider.currentActorDisplayName()).thenReturn(null)
        val context = mock<InvocationContext>()
        whenever(context.method).thenReturn(this.javaClass.getDeclaredMethod("mockQuery"))
        whenever(context.proceed()).thenReturn(Unit)

        AuditGucInterceptor(em, auditActorProvider).setGucs(context)

        verify(gucQuery).setParameter("action", InvoiceAuditAction.UNSPECIFIED.ordinal.toString())
    }
}
