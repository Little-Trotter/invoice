package de.littletrotter.architecture.rules

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaAnnotation
import com.tngtech.archunit.core.domain.JavaCall
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.JavaCodeUnit
import com.tngtech.archunit.core.domain.JavaEnumConstant
import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.core.domain.JavaModifier
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods
import java.util.concurrent.Future

/**
 * Where a database transaction may start and what may run inside it.
 *
 * A transaction holds a connection and row locks for as long as it is open. Starting one deep in
 * a repository makes the boundary invisible to the use case that needs it, and calling the network
 * or the object store from inside one stretches the lock time by the latency of a foreign system.
 * Publishing an event inside the transaction announces a change that a rollback can still revoke.
 *
 * Every module checks its own production classes and passes the few places that are allowed to
 * differ as a baseline. Baselines only shrink: [staleEntries] names every entry whose violation is
 * gone, so a fixed place has to leave the list. Class rules take fully qualified class names (a
 * nested class is covered by its outer class); method rules take `fully.qualified.Class.method`.
 */
object TransactionRules {

    private const val TRANSACTIONAL = "jakarta.transaction.Transactional"
    private const val QUARKUS_TRANSACTION = "io.quarkus.narayana.jta.QuarkusTransaction"
    private const val EVENT = "jakarta.enterprise.event.Event"
    private const val MAX_DEPTH = 4

    /** Targets that leave the process: a call to one of them waits for a foreign system. */
    class IoTargets(
        val classNames: Set<String> = emptySet(),
        val packages: List<String> = emptyList(),
        /** Packages whose types named `*Remote` are outbound ports to another service. */
        val remotePortPackages: List<String> = emptyList(),
    ) {
        fun plus(other: IoTargets) = IoTargets(
            classNames + other.classNames,
            packages + other.packages,
            remotePortPackages + other.remotePortPackages,
        )

        internal fun matches(target: JavaClass): Boolean =
            target.name in classNames ||
                packages.any { target.packageName.startsWith(it.removeSuffix("..")) } ||
                (target.simpleName.endsWith("Remote") && remotePortPackages.any { target.packageName.startsWith(it.removeSuffix("..")) })
    }

    /** The transports every service shares. */
    val COMMON_IO = IoTargets(
        classNames = setOf(
            "de.littletrotter.shared.DocumentStore",
            "io.nats.client.JetStream",
            "io.nats.client.Connection",
            "java.net.http.HttpClient",
        ),
        packages = listOf("jakarta.ws.rs.client", "org.eclipse.microprofile.rest.client"),
    )

    /** A transaction annotation outside [applicationPackages]: the use case owns the boundary. */
    fun transactionalOnlyInApplication(applicationPackages: Set<String>, allowedClasses: Set<String>): ArchRule =
        classes().should(object : ArchCondition<JavaClass>("not carry a transaction boundary outside ${applicationPackages.joinToString()}") {
            override fun check(item: JavaClass, events: ConditionEvents) {
                if (isInApplication(item, applicationPackages) || coveredBy(item, allowedClasses)) return
                val methods = item.methods.filter { it.isAnnotatedWith(TRANSACTIONAL) }
                if (item.isAnnotatedWith(TRANSACTIONAL) || methods.isNotEmpty()) {
                    events.add(SimpleConditionEvent.violated(item, "${item.name} declares @Transactional (${methods.size} methods) outside the application layer"))
                }
            }
        }).allowEmptyShould(true)
            .because("the use case in the application layer decides where a transaction starts; adapters join it")

    /** No call to [io] while the method's transaction is open. */
    fun noIoInTransaction(io: IoTargets, allowedMethods: Set<String>): ArchRule =
        transactionalMethodsShould(allowedMethods, "not reach an I/O port or client") { call -> io.matches(call.targetOwner) }
            .because("a transaction must not wait for the network or the object store while it holds locks")

    /** No [jakarta.enterprise.event.Event] fired while the method's transaction is open. */
    fun noEventFiredInTransaction(allowedMethods: Set<String>): ArchRule =
        transactionalMethodsShould(allowedMethods, "not fire a CDI event") { call ->
            call.targetOwner.name == EVENT && call.target.name in setOf("fire", "fireAsync")
        }.because("an event fired inside the transaction announces a change that a rollback can still revoke")

    /** No blocking wait on another thread while the method's transaction is open. */
    fun noBlockingInTransaction(allowedMethods: Set<String>): ArchRule =
        transactionalMethodsShould(allowedMethods, "not block on another thread") { call ->
            val owner = call.targetOwner
            val name = call.target.name
            (owner.name == "java.lang.Thread" && name == "sleep") ||
                (name in setOf("get", "join") && owner.isAssignableTo(Future::class.java))
        }.because("blocking inside a transaction holds the connection for as long as the other thread takes")

    /** `REQUIRES_NEW` and programmatic `QuarkusTransaction` only in the application layer. */
    fun noNestedTransactionOutsideApplication(applicationPackages: Set<String>, allowedClasses: Set<String>): ArchRule =
        classes().should(object : ArchCondition<JavaClass>("not open an independent transaction outside ${applicationPackages.joinToString()}") {
            override fun check(item: JavaClass, events: ConditionEvents) {
                if (isInApplication(item, applicationPackages) || coveredBy(item, allowedClasses)) return
                val annotated = (item.annotations + item.methods.flatMap { it.annotations }).count(::requiresNew)
                val programmatic = item.codeUnits.flatMap { it.callsFromSelf }
                    .count { it.targetOwner.name.startsWith(QUARKUS_TRANSACTION) }
                if (annotated + programmatic > 0) {
                    events.add(SimpleConditionEvent.violated(item, "${item.name} opens an independent transaction ($annotated REQUIRES_NEW, $programmatic QuarkusTransaction calls) outside the application layer"))
                }
            }
        }).allowEmptyShould(true)
            .because("an independent transaction is a decision of the use case, not of a repository or adapter")

    /** Transactional methods are interceptable: not private, not `internal` (mangled name), and not in a final class without a scope. */
    fun transactionalMethodsAreInterceptable(): ArchRule =
        classes().should(object : ArchCondition<JavaClass>("declare only interceptable @Transactional methods") {
            override fun check(item: JavaClass, events: ConditionEvents) {
                val methods = item.methods.filter { it.isAnnotatedWith(TRANSACTIONAL) }
                methods.filter { it.modifiers.contains(JavaModifier.PRIVATE) }.forEach {
                    events.add(SimpleConditionEvent.violated(it, "${it.fullName} is private, so the container cannot intercept it"))
                }
                // Kotlin `internal` compiles to a public method named `name$module`; the container does not
                // intercept it when it is called on `this`, so the transaction is silently missing.
                methods.filter { it.name.contains('$') }.forEach {
                    events.add(SimpleConditionEvent.violated(it, "${it.fullName} has a module-mangled name (Kotlin internal), so a call on this is not intercepted"))
                }
                val transactional = item.isAnnotatedWith(TRANSACTIONAL) || methods.isNotEmpty()
                if (transactional && item.modifiers.contains(JavaModifier.FINAL) && !hasScope(item)) {
                    events.add(SimpleConditionEvent.violated(item, "${item.name} is final and has no CDI scope, so its @Transactional cannot be intercepted"))
                }
            }
        }).allowEmptyShould(true)
            .because("a private method or a final unscoped class silently runs without its transaction")

    /**
     * Entries of [baseline] whose violation no longer exists: with the entry removed [rule] reports
     * nothing for it. [rule] builds the rule for a given allowed set.
     */
    fun staleEntries(baseline: Set<String>, classes: JavaClasses, rule: (Set<String>) -> ArchRule): Set<String> =
        baseline.filterTo(sortedSetOf()) { entry ->
            val result = rule(baseline - entry).evaluate(classes)
            !(result.hasViolation() && result.failureReport.details.any { it.startsWith(entry) })
        }

    private fun transactionalMethodsShould(
        allowedMethods: Set<String>,
        description: String,
        forbidden: (JavaCall<*>) -> Boolean,
    ): ArchRule = methods()
        .that(object : DescribedPredicate<JavaMethod>("are @Transactional and not in the baseline") {
            override fun test(method: JavaMethod) = method.isAnnotatedWith(TRANSACTIONAL) && methodKey(method) !in allowedMethods
        })
        .should(object : ArchCondition<JavaMethod>(description) {
            override fun check(item: JavaMethod, events: ConditionEvents) {
                reachableCalls(item).firstOrNull(forbidden)?.let { call ->
                    events.add(SimpleConditionEvent.violated(item, "${item.fullName} reaches ${call.target.fullName}"))
                }
            }
        })
        .allowEmptyShould(true)

    /** Calls of [start] and of the helpers of its own class (including its lambdas) up to [MAX_DEPTH] levels deep. */
    private fun reachableCalls(start: JavaMethod): List<JavaCall<*>> {
        val owner = start.owner
        val seen = mutableSetOf<JavaCodeUnit>()
        var frontier: Set<JavaCodeUnit> = setOf(start)
        val calls = mutableListOf<JavaCall<*>>()
        repeat(MAX_DEPTH) {
            val next = mutableSetOf<JavaCodeUnit>()
            for (unit in frontier) {
                if (!seen.add(unit)) continue
                calls += unit.callsFromSelf
                next += owner.methods.filter { it.name.startsWith("${unit.name}$") }
                unit.callsFromSelf.filter { it.targetOwner == owner }
                    .forEach { call -> next += owner.methods.filter { it.name == call.target.name } }
            }
            frontier = next - seen
        }
        return calls
    }

    private fun methodKey(method: JavaMethod) = "${method.owner.name}.${method.name}"

    private fun isInApplication(item: JavaClass, applicationPackages: Set<String>) =
        applicationPackages.any { item.packageName.startsWith(it.removeSuffix("..")) }

    private fun coveredBy(item: JavaClass, allowed: Set<String>) =
        allowed.any { item.name == it || item.name.startsWith("$it$") }

    private fun requiresNew(annotation: JavaAnnotation<*>): Boolean {
        if (annotation.rawType.name != TRANSACTIONAL) return false
        val value = annotation.get("value").orElse(null)
        return value is JavaEnumConstant && value.name() == "REQUIRES_NEW"
    }

    private fun hasScope(item: JavaClass) = item.annotations.any { annotation ->
        val type = annotation.rawType
        type.isMetaAnnotatedWith("jakarta.inject.Scope") ||
            type.isMetaAnnotatedWith("jakarta.enterprise.context.NormalScope") ||
            type.isMetaAnnotatedWith("jakarta.enterprise.inject.Stereotype") ||
            type.name == "jakarta.inject.Singleton"
    }
}
