package de.littletrotter.application.shared

import de.littletrotter.domain.compliance.InvoiceAuditAction
import jakarta.interceptor.InterceptorBinding
import kotlin.annotation.AnnotationRetention.RUNTIME
import kotlin.annotation.AnnotationTarget.CLASS
import kotlin.annotation.AnnotationTarget.FUNCTION

/**
 * Marks a `@Transactional` business method (or a whole bean, for one where every
 * `@Transactional` method's write path can reach `invoice`/`invoice_item`/`invoice_document`)
 * whose transaction should carry the `app.actor`/`app.actor_name`/`app.audit_action` Postgres
 * GUCs the `capture_invoice_audit_event()` DB trigger reads (`V1__baseline_schema.sql`,
 * section 13). [AuditGucInterceptor] does the actual `SELECT set_config(...)`.
 *
 * [action] is `@Nonbinding`, same shape as
 * [de.littletrotter.application.security.RequiresPermission.value]: CDI interceptor bindings
 * normally compare every member for equality when matching a target to an interceptor, which
 * would require one `@Interceptor` per [InvoiceAuditAction]. Marking it non-binding makes every
 * usage resolve to the same [AuditGucInterceptor], which reads the actual action back off the
 * intercepted method via reflection ([jakarta.interceptor.InvocationContext.getMethod]).
 *
 * Defaults to [InvoiceAuditAction.UNSPECIFIED] only because an interceptor-binding member needs a
 * default at all — every annotated method must declare a real action; a reflection guard test
 * (`InvoiceAuditGucMappingTest`) asserts no `@SetsAuditGuc` method is ever left at
 * [InvoiceAuditAction.UNSPECIFIED].
 */
@InterceptorBinding
@Target(FUNCTION, CLASS)
@Retention(RUNTIME)
annotation class SetsAuditGuc(
    @get:jakarta.enterprise.util.Nonbinding
    val action: InvoiceAuditAction = InvoiceAuditAction.UNSPECIFIED,
)
