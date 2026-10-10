package de.littletrotter.application.invoice

import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.enterprise.inject.spi.CDI
import jakarta.inject.Qualifier
import jakarta.transaction.TransactionSynchronizationRegistry

/**
 * Per the CDI project rule (never exclude/suppress CDI findings; solve architecturally):
 * [GenerationJobService]'s `transactionSynchronizationRegistry` injection point is ambiguous —
 * Quarkus/Narayana exposes more than one bean matching the plain, unqualified
 * [TransactionSynchronizationRegistry] type. [Optional] disambiguates that injection point to
 * exactly this producer's output.
 *
 * The producer itself must not re-declare an injection point of the same ambiguous type (that
 * would just relocate the ambiguity into the producer and additionally is invalid CDI form —
 * a `@Produces` method must not also be `@Inject`ed). Instead it resolves the candidate
 * programmatically via [CDI.current], which returns a deferred [jakarta.enterprise.inject.Instance]
 * handle rather than triggering the container's build-time single-bean validation — exactly the
 * CDI-documented way to pick any one candidate out of an ambiguous set at runtime.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FUNCTION)
annotation class Optional

@ApplicationScoped
class TransactionSynchronizationRegistryProducer {

    @Produces
    @Optional
    fun transactionSynchronizationRegistry(): TransactionSynchronizationRegistry? {
        val instance = CDI.current().select(TransactionSynchronizationRegistry::class.java)
        return when {
            instance.isUnsatisfied -> null
            instance.isAmbiguous -> instance.iterator().next()
            else -> instance.get()
        }
    }
}
