package de.littletrotter.application.security

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.invoice.InvoiceStornoService
import de.littletrotter.application.masterdata.CustomerManagementService
import de.littletrotter.application.system.DatabaseMaintenanceService
import de.littletrotter.domain.exception.InvoiceNotFoundException
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.Recipient
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * TS-8 acceptance: "Service-layer check: a mutating invoice call without the invoicing role is
 * rejected even when invoked outside the UI path" — exercised here through the REAL CDI proxy
 * ([PermissionInterceptor] only ever fires on a CDI-managed bean call, never on a plain
 * constructor-built instance like the many existing unit tests use), with no Vaadin view, UI, or
 * route in the call path at all.
 *
 * `@TestSecurity` installs a synthetic, non-anonymous [io.quarkus.security.identity.SecurityIdentity]
 * with exactly the given roles — independent of `quarkus.oidc.tenant-enabled`/`auth.enabled`, so
 * this runs under the %test profile's dev-bypass config unmodified (see
 * [SecurityIdentityAppRoleProvider]'s KDoc). A method call with a nonexistent id is enough to
 * prove the point: [PermissionInterceptor] runs before the method body (and before its
 * `@Transactional` even opens), so a denied call never gets far enough to look anything up.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class ServiceLayerPermissionIT {

    @Inject
    lateinit var invoiceLifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var invoiceStornoService: InvoiceStornoService

    @Inject
    lateinit var customerManagementService: CustomerManagementService

    @Inject
    lateinit var databaseMaintenanceService: DatabaseMaintenanceService

    @Test
    @TestSecurity(user = "tester", roles = ["admin"])
    fun `invoice transition is rejected for an admin-only caller before it ever looks up the invoice`() {
        assertThrows(AccessDeniedException::class.java) {
            invoiceLifecycleService.transition(Long.MAX_VALUE, InvoiceStatus.IN_REVIEW)
        }
    }

    @Test
    @TestSecurity(user = "tester", roles = ["invoicing"])
    fun `invoice transition is NOT rejected for an invoicing caller - it proceeds into business logic`() {
        // Proves the permission check passed: execution reaches the repository lookup and fails
        // there instead, on a business exception, not AccessDeniedException.
        assertThrows(InvoiceNotFoundException::class.java) {
            invoiceLifecycleService.transition(Long.MAX_VALUE, InvoiceStatus.IN_REVIEW)
        }
    }

    @Test
    @TestSecurity(user = "tester", roles = ["admin"])
    fun `invoice credit-note creation - the storno path - is rejected for an admin-only caller`() {
        assertThrows(AccessDeniedException::class.java) {
            invoiceStornoService.createCreditNote(Long.MAX_VALUE, reason = "Testgrund")
        }
    }

    @Test
    @TestSecurity(user = "tester", roles = ["admin"])
    fun `recipient save - master data edit - is rejected for an admin-only caller before validation ever runs`() {
        assertThrows(AccessDeniedException::class.java) {
            customerManagementService.save(Recipient())
        }
    }

    @Test
    @TestSecurity(user = "tester", roles = ["invoicing"])
    fun `recipient save is NOT rejected for an invoicing caller - it proceeds into business logic`() {
        // A blank Recipient() fails Recipient.validate()'s NotBlank name check — that's the proof:
        // the permission check let it through, and it failed on domain validation instead of
        // AccessDeniedException.
        assertThrows(InvoiceValidationException::class.java) {
            customerManagementService.save(Recipient())
        }
    }

    // performRecalculateTotals is the DATABASE_MAINTENANCE-guarded operation these three cases
    // exercise. They used to call performVacuum(); VACUUM/ANALYZE/REINDEX no longer exist in the
    // application (they moved to admin-tools/scripts/db/db-maintenance.sh via ADM-1), but the permission itself is
    // unchanged and still has to be enforced — so the cases moved to the remaining guarded method
    // rather than being deleted along with the operation.
    @Test
    @TestSecurity(user = "tester", roles = ["invoicing"])
    fun `recalculate totals - admin-only maintenance - is rejected for an invoicing-only caller`() {
        assertThrows(AccessDeniedException::class.java) {
            databaseMaintenanceService.performRecalculateTotals()
        }
    }

    @Test
    @TestSecurity(user = "tester", roles = ["admin"])
    fun `recalculate totals succeeds for an admin caller`() {
        databaseMaintenanceService.performRecalculateTotals()
    }

    @Test
    fun `recalculate totals succeeds under the dev-bypass anonymous identity - dual-role parity`() {
        // No @TestSecurity at all: the %test profile's real auth.enabled=false bypass, exercised
        // end to end — proves TS-8's dev-bypass-parity requirement at the service layer, not just
        // in SecurityIdentityAppRoleProviderTest's unit test.
        databaseMaintenanceService.performRecalculateTotals()
    }
}
