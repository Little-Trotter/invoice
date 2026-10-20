package de.littletrotter.arch

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaMethodCall
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import org.junit.jupiter.api.Test

/**
 * Acceptance criterion: "Exactly one NATS
 * connection per `kotlin-xpdf` instance ... by construction: no `Nats.connect` outside
 * `NatsConnectionProducer`." Previously,
 * [de.littletrotter.xpdf.infrastructure.messaging.ResultPublisher]'s own
 * no-arg constructor opened a second connection; that constructor is gone, and this test turns
 * "nobody else calls `Nats.connect` again" into a build failure instead of a comment someone can
 * silently violate later (same reasoning as [MoneyArchitectureTest]).
 *
 * The actual `Nats.connect` call moved out of `NatsConnectionProducer` and into
 * `de.littletrotter.shared.NatsConnections` (shared with little-trotter-invoice's own producer,
 * which no longer duplicates this sequence byte-for-byte). `NatsConnections` is on the classpath
 * here as `kotlin-xpdf`'s own `kotlin-shared` dependency, and `ClassFileImporter` walks the whole
 * `de.littletrotter` prefix including it — so it is now the one allowed caller instead of the
 * producer itself.
 */
class SingleNatsConnectionArchitectureTest {

    private val classes = ClassFileImporter()
        .withImportOption(ImportOption.DoNotIncludeTests())
        .importPackages("de.littletrotter")

    private val allowedCaller = "de.littletrotter.shared.NatsConnections"

    @Test
    fun `only NatsConnectionProducer calls Nats connect`() {
        val violations = mutableListOf<String>()
        classes.forEach { clazz: JavaClass ->
            clazz.methodCallsFromSelf.forEach { call: JavaMethodCall ->
                val target = call.target
                if (target.owner.fullName == "io.nats.client.Nats" && target.name == "connect") {
                    val caller = call.originOwner.fullName
                    if (caller != allowedCaller) violations += "$caller -> Nats.connect(...)"
                }
            }
        }
        if (violations.isNotEmpty()) {
            throw AssertionError(
                "Only $allowedCaller may call Nats.connect(...) — every other caller means more " +
                    "than one NATS connection per instance:\n" +
                    violations.joinToString("\n") { "  - $it" }
            )
        }
    }
}
