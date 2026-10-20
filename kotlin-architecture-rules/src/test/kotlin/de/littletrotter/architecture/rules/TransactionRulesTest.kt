package de.littletrotter.architecture.rules

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.ArchRule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Each transaction rule must reject every forbidden form (negative specimens) and leave the
 * look-alike code alone (positive specimens), and a baseline entry must stay only while its
 * violation exists.
 */
class TransactionRulesTest {

    private val base = "de.littletrotter.architecture.rules.txspecimen"
    private val specimens: JavaClasses = ClassFileImporter().importPackages(base)
    private val application = setOf("$base.app..")
    private val io = TransactionRules.IoTargets(
        packages = listOf("$base.io.."),
        remotePortPackages = listOf("$base.ports.."),
    ).plus(TransactionRules.COMMON_IO)

    private fun report(rule: ArchRule): List<String> = rule.evaluate(specimens).failureReport.details

    /** Method lines read `<package.Class.method()>`; the trailing dot keeps a class apart from its look-alikes. */
    private fun List<String>.mentions(simpleName: String) = any { it.contains("$base.$simpleName.") }

    private fun List<String>.mentionsNested(name: String) = any { it.contains(name) }

    @Test
    fun `transaction annotations outside the application layer are rejected`() {
        val details = report(TransactionRules.transactionalOnlyInApplication(application, emptySet()))
        listOf("ViolatingRepository", "ViolatingClassLevel", "BaselinedRepository\$Inner").forEach {
            assertTrue(details.mentionsNested("$base.$it"), "$it must be rejected")
        }
        assertFalse(details.mentionsNested("PlainRepository"), "no annotation, no boundary")
        assertFalse(details.mentionsNested("txspecimen.app."), "the application layer owns the boundary")
    }

    @Test
    fun `an allowed class covers its nested classes`() {
        val details = report(TransactionRules.transactionalOnlyInApplication(application, setOf("$base.BaselinedRepository")))
        assertFalse(details.mentionsNested("BaselinedRepository"))
        assertTrue(details.mentionsNested("ViolatingRepository"))
    }

    @Test
    fun `I O reached from a transaction is rejected through helpers lambdas and shared targets`() {
        val details = report(TransactionRules.noIoInTransaction(io, emptySet()))
        listOf(
            "ViolatingIo", "ViolatingIoViaHelper", "ViolatingIoViaLambda", "ViolatingIoViaChain",
            "ViolatingRemotePort", "ViolatingSharedStore", "BaselinedIo",
        ).forEach { assertTrue(details.mentions(it), "$it must be rejected") }
        assertFalse(details.mentions("CleanTransactional"), "a local port and a call outside the transaction are fine")
    }

    @Test
    fun `I O in a baselined method is tolerated`() {
        val details = report(TransactionRules.noIoInTransaction(io, setOf("$base.BaselinedIo.send")))
        assertFalse(details.mentions("BaselinedIo"))
        assertTrue(details.mentions("ViolatingIo"))
    }

    @Test
    fun `fired events are rejected`() {
        val details = report(TransactionRules.noEventFiredInTransaction(setOf("$base.BaselinedFire.publish")))
        listOf("ViolatingFire", "ViolatingFireAsync").forEach { assertTrue(details.mentions(it), "$it must be rejected") }
        assertFalse(details.mentions("BaselinedFire"))
        assertFalse(details.mentions("CleanEvents"), "selecting an event type or firing outside a transaction is fine")
    }

    @Test
    fun `blocking waits are rejected`() {
        val details = report(TransactionRules.noBlockingInTransaction(setOf("$base.BaselinedSleep.block")))
        listOf("ViolatingSleep", "ViolatingFutureGet", "ViolatingFutureJoin").forEach { assertTrue(details.mentions(it), "$it must be rejected") }
        assertFalse(details.mentions("BaselinedSleep"))
        assertFalse(details.mentions("CleanBlocking"), "a map lookup is not a wait, and sleeping outside a transaction is out of scope")
    }

    @Test
    fun `independent transactions outside the application layer are rejected`() {
        val details = report(TransactionRules.noNestedTransactionOutsideApplication(application, setOf("$base.BaselinedRepository")))
        listOf("ViolatingRequiresNew", "ViolatingRequiresNewClass", "ViolatingProgrammatic").forEach {
            assertTrue(details.mentionsNested("$base.$it"), "$it must be rejected")
        }
        assertFalse(details.mentionsNested("BaselinedRepository"), "allowed class and its nested class")
        assertFalse(details.mentionsNested("txspecimen.app."), "the application layer may open them")
        assertFalse(details.mentionsNested("ViolatingRepository"), "plain REQUIRED joins the caller")
    }

    @Test
    fun `transactional methods the container cannot intercept are rejected`() {
        val details = report(TransactionRules.transactionalMethodsAreInterceptable())
        assertTrue(details.mentionsNested("ViolatingPrivateMethod.hidden"))
        assertTrue(details.mentionsNested("ViolatingInternalMethod.mangled"))
        assertTrue(details.mentionsNested("$base.ViolatingFinalUnscoped is final"))
        assertTrue(details.mentionsNested("$base.ViolatingFinalUnscopedClassLevel is final"))
        assertFalse(details.mentionsNested("InterceptableScoped"), "a scope makes the class proxyable")
        assertFalse(details.mentionsNested("InterceptableOpen"), "an open class can be subclassed")
    }

    @Test
    fun `a baseline entry is stale once its violation is gone`() {
        val baseline = setOf("$base.BaselinedIo.send", "$base.CleanTransactional.pay")
        val stale = TransactionRules.staleEntries(baseline, specimens) { allowed -> TransactionRules.noIoInTransaction(io, allowed) }
        assertEquals(setOf("$base.CleanTransactional.pay"), stale)
    }

    @Test
    fun `a class baseline entry is stale once its violation is gone`() {
        val baseline = setOf("$base.ViolatingRepository", "$base.PlainRepository")
        val stale = TransactionRules.staleEntries(baseline, specimens) { allowed ->
            TransactionRules.transactionalOnlyInApplication(application, allowed)
        }
        assertEquals(setOf("$base.PlainRepository"), stale)
    }

    @Test
    fun `I O targets combine and match by class name package and remote suffix`() {
        val combined = TransactionRules.IoTargets(classNames = setOf("a.B")).plus(TransactionRules.IoTargets(packages = listOf("x.y..")))
        assertEquals(setOf("a.B"), combined.classNames)
        assertEquals(listOf("x.y.."), combined.packages)
        assertTrue(TransactionRules.COMMON_IO.classNames.contains("java.net.http.HttpClient"))
    }
}
