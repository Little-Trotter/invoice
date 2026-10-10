package de.littletrotter.application.security

import jakarta.interceptor.InvocationContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

/**
 * TS-8 — [PermissionInterceptor.checkPermission] against a mocked [InvocationContext], the same
 * "test the companion, not the CDI-wired component" idiom used across this file's siblings: the
 * real CDI interception (a method call actually going through the interceptor chain) is exercised
 * by `InvoiceLifecycleServicePermissionIT` instead, which needs a live CDI container and a
 * database — this class covers the interceptor's own decision logic without either.
 */
class PermissionInterceptorTest {

    /** Fixture with one guarded and one unguarded method, resolved via plain
     *  [Class.getDeclaredMethod] rather than kotlin-reflect (not a project dependency). */
    private class Fixture {
        @RequiresPermission(AppPermission.SYSTEM_VIEW)
        fun guarded() = Unit

        fun unguarded() = Unit
    }

    /** Fixture for ANY-of semantics (PO 30.08.2026): a method listing two permissions, of which
     *  the caller needs only one — mirrors de.littletrotter.application.reference.port.inbound.ManageB2bFieldValueUseCase.saveB2bFieldValues. */
    private class AnyOfFixture {
        @RequiresPermission(AppPermission.INVOICE_FULL_EDIT, AppPermission.MASTER_DATA_EDIT)
        fun eitherContext() = Unit
    }

    /** A class-level [RequiresPermission] (RequiresPermission's own KDoc: "a whole bean" usage) —
     *  its method carries no annotation of its own, so [PermissionInterceptor] must fall back to
     *  the declaring class's annotation. */
    @RequiresPermission(AppPermission.DATABASE_MAINTENANCE)
    private class ClassLevelFixture {
        fun anyMethod() = Unit
    }

    private val guardedMethod = Fixture::class.java.getDeclaredMethod("guarded")
    private val unguardedMethod = Fixture::class.java.getDeclaredMethod("unguarded")
    private val classLevelGuardedMethod = ClassLevelFixture::class.java.getDeclaredMethod("anyMethod")
    private val anyOfGuardedMethod = AnyOfFixture::class.java.getDeclaredMethod("eitherContext")

    @Test
    fun `any-of - proceeds when the caller holds only the SECOND of two listed permissions`() {
        val roleProvider = mock<AppRoleProvider>()
        whenever(roleProvider.hasPermission(AppPermission.INVOICE_FULL_EDIT)).thenReturn(false)
        whenever(roleProvider.hasPermission(AppPermission.MASTER_DATA_EDIT)).thenReturn(true)
        val context = mock<InvocationContext>()
        whenever(context.method).thenReturn(anyOfGuardedMethod)
        whenever(context.proceed()).thenReturn("ok")

        assertEquals("ok", PermissionInterceptor(roleProvider).checkPermission(context))
        verify(context).proceed()
    }

    @Test
    fun `any-of - denies when the caller holds none of the listed permissions`() {
        val roleProvider = mock<AppRoleProvider>()
        whenever(roleProvider.hasPermission(AppPermission.INVOICE_FULL_EDIT)).thenReturn(false)
        whenever(roleProvider.hasPermission(AppPermission.MASTER_DATA_EDIT)).thenReturn(false)
        val context = mock<InvocationContext>()
        whenever(context.method).thenReturn(anyOfGuardedMethod)

        assertThrows(AccessDeniedException::class.java) {
            PermissionInterceptor(roleProvider).checkPermission(context)
        }
        verify(context, never()).proceed()
    }

    @Test
    fun `proceeds when the caller has the required permission`() {
        val roleProvider = mock<AppRoleProvider>()
        whenever(roleProvider.hasPermission(AppPermission.SYSTEM_VIEW)).thenReturn(true)
        val context = mock<InvocationContext>()
        whenever(context.method).thenReturn(guardedMethod)
        whenever(context.proceed()).thenReturn("ok")

        val result = PermissionInterceptor(roleProvider).checkPermission(context)

        assertEquals("ok", result)
        verify(context).proceed()
    }

    @Test
    fun `throws AccessDeniedException and never proceeds when the caller lacks the permission`() {
        val roleProvider = mock<AppRoleProvider>()
        whenever(roleProvider.hasPermission(AppPermission.SYSTEM_VIEW)).thenReturn(false)
        val context = mock<InvocationContext>()
        whenever(context.method).thenReturn(guardedMethod)

        assertThrows(AccessDeniedException::class.java) {
            PermissionInterceptor(roleProvider).checkPermission(context)
        }
        verify(context, never()).proceed()
    }

    @Test
    fun `falls back to the declaring class's RequiresPermission when the method itself carries none`() {
        val roleProvider = mock<AppRoleProvider>()
        whenever(roleProvider.hasPermission(AppPermission.DATABASE_MAINTENANCE)).thenReturn(false)
        val context = mock<InvocationContext>()
        whenever(context.method).thenReturn(classLevelGuardedMethod)

        assertThrows(AccessDeniedException::class.java) {
            PermissionInterceptor(roleProvider).checkPermission(context)
        }
        verify(context, never()).proceed()
    }

    @Test
    fun `proceeds without any permission check on a method carrying no RequiresPermission annotation`() {
        val roleProvider = mock<AppRoleProvider>()
        val context = mock<InvocationContext>()
        whenever(context.method).thenReturn(unguardedMethod)
        whenever(context.proceed()).thenReturn("ok")

        val result = PermissionInterceptor(roleProvider).checkPermission(context)

        assertEquals("ok", result)
        verifyNoInteractions(roleProvider)
    }
}
