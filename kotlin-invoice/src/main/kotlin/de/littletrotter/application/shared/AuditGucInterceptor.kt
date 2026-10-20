package de.littletrotter.application.shared

import de.littletrotter.domain.compliance.InvoiceAuditAction
import jakarta.annotation.Priority
import jakarta.inject.Inject
import jakarta.interceptor.AroundInvoke
import jakarta.interceptor.Interceptor
import jakarta.interceptor.InvocationContext
import jakarta.persistence.EntityManager
import de.littletrotter.application.shared.port.inbound.AuditActorQueryPort

/**
 * Sets the per-transaction `app.actor`/`app.actor_name`/`app.audit_action` Postgres
 * GUCs the `invoice_audit_journal` capture trigger (`capture_invoice_audit_event()`,
 * `V1__baseline_schema.sql` section 13) reads via `current_setting(..., true)`, so a journaled
 * write made through the application attributes to the logged-in OIDC user (instead of falling
 * back all the way to `session_user`) and carries the business action the caller declared.
 *
 * **Priority.** Quarkus wires its own `@Transactional` interceptor at
 * `PLATFORM_BEFORE + 200` (see [de.littletrotter.application.security.PermissionInterceptor]'s own
 * KDoc for that number). This interceptor uses `PLATFORM_BEFORE + 210` — a HIGHER number places
 * it INSIDE the transactional interceptor in the call chain, so by the time [setGucs] below runs,
 * a transaction (and therefore a bound JDBC connection) is already active, and `SET LOCAL`
 * semantics survive for the rest of that transaction — covering every statement the method body
 * itself issues, including ones several calls deep.
 *
 * **Why `set_config`, not `SET LOCAL`.** Postgres' `SET` statement does not accept bind
 * parameters; `set_config(name, value, is_local)` does, and `is_local = true` gives it exactly
 * `SET LOCAL`'s semantics (the value reverts at transaction end, commit or rollback). All three
 * calls go out as one round trip.
 *
 * **Outermost wins ([InvoiceAuditAction]).** `app.actor`/`app.actor_name` are overwritten on every
 * annotated call (the actor never changes mid-transaction, so re-setting it is harmless), but
 * `app.audit_action` must only ever be set ONCE per transaction — by the outermost annotated call
 * — so a method that recalculates internally (`InvoiceLifecycleService.addItem` calling
 * `recalculateTotals`) journals its own, more specific action (`ITEM_ADDED`), never a nested
 * collaborator's. The `CASE WHEN` below reads and conditionally writes
 * `app.audit_action` in the SAME statement as the other two `set_config` calls — no
 * read-then-write round trip, no race.
 *
 * **The lock-first ordering rule (arc42 08-crosscutting, Persistence/Locking).** Right
 * after the GUC statement, [setGucs] also acquires `pg_advisory_xact_lock(1308)` itself — the
 * same global lock `capture_invoice_audit_event()` (`V1__baseline_schema.sql` section 13) takes
 * from inside its trigger — BEFORE `context.proceed()` runs the intercepted method body. That
 * makes 1308 always the FIRST lock a journaled transaction takes, so it can never sit in a cycle
 * with a row lock the method body acquires afterwards (the deadlock the 2026-09-18 stress night
 * observed at scale: one transaction held a row and waited on 1308, another held 1308 mid-trigger
 * for a different row and waited on the first transaction's row). A nested annotated call
 * re-acquires the same lock its own transaction already holds, which Postgres grants immediately.
 * The trigger keeps its own `PERFORM pg_advisory_xact_lock(1308)` as the safety net for a write
 * that bypasses this interceptor entirely (direct SQL, a future caller that forgets
 * `@SetsAuditGuc`).
 */
@Interceptor
@SetsAuditGuc
@Priority(Interceptor.Priority.PLATFORM_BEFORE + 210)
class AuditGucInterceptor @Inject constructor(
    private val em: EntityManager,
    private val auditActorProvider: AuditActorQueryPort,
) {

    @AroundInvoke
    fun setGucs(context: InvocationContext): Any? {
        // The action declared on the intercepted method, falling back to the declaring
        // class (a bean still annotated at class level) — same reflection idiom
        // PermissionInterceptor.checkPermission uses for RequiresPermission.
        val action = context.method.getAnnotation(SetsAuditGuc::class.java)?.action
            ?: context.method.declaringClass.getAnnotation(SetsAuditGuc::class.java)?.action
            ?: InvoiceAuditAction.UNSPECIFIED
        em.createNativeQuery(GUC_QUERY)
            .setParameter("actor", auditActorProvider.currentActorId())
            .setParameter("actorName", auditActorProvider.currentActorDisplayName())
            .setParameter("action", action.ordinal.toString())
            .singleResult
        // The lock-first ordering rule (see the class KDoc above): acquired before
        // `proceed()` runs the method body, so it is always the FIRST lock this transaction takes.
        em.createNativeQuery(AUDIT_JOURNAL_LOCK_QUERY)
            .setParameter("lockKey", AUDIT_JOURNAL_LOCK_KEY)
            .singleResult
        return context.proceed()
    }

    companion object {
        private const val GUC_QUERY =
            "SELECT set_config('app.actor', :actor, true), " +
                "set_config('app.actor_name', :actorName, true), " +
                "CASE WHEN COALESCE(current_setting('app.audit_action', true), '') = '' " +
                "THEN set_config('app.audit_action', :action, true) " +
                "ELSE current_setting('app.audit_action', true) END"

        // 1308, the same dedicated advisory-lock namespace capture_invoice_audit_event()
        // uses (V1__baseline_schema.sql section 13), mirroring number_journal's own 1304.
        private const val AUDIT_JOURNAL_LOCK_KEY = 1308L
        private const val AUDIT_JOURNAL_LOCK_QUERY = "SELECT pg_advisory_xact_lock(:lockKey)"
    }
}
