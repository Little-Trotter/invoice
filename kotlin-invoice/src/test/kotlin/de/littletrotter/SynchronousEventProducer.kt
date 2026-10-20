package de.littletrotter

import de.littletrotter.domain.event.DomainEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.enterprise.event.Event
import jakarta.enterprise.event.NotificationOptions
import jakarta.enterprise.util.TypeLiteral
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

@ApplicationScoped
class SynchronousEventProducer {

    @Produces
    fun produceDomainEvent(): Event<DomainEvent> {
        return object : Event<DomainEvent> {
            override fun fire(event: DomainEvent) {
                // Test-helper: synchronous event dispatch can be added here if needed.
                // Currently no-op, because Jakarta CDI 4.x removed BeanManager.fireEvent().
            }

            override fun <U : DomainEvent> fireAsync(event: U): CompletionStage<U> {
                // Fire synchronously and return completed future so tests observe events immediately
                // No-op: event dispatch by CDI is not needed in tests here.
                return CompletableFuture.completedFuture(event)
            }

            override fun <U : DomainEvent> fireAsync(event: U, options: NotificationOptions): CompletionStage<U> {
                // No-op for tests.
                return CompletableFuture.completedFuture(event)
            }

            override fun select(vararg qualifiers: kotlin.Annotation): Event<DomainEvent> = this

            override fun <U : DomainEvent> select(subtype: Class<U>, vararg qualifiers: kotlin.Annotation): Event<U> =
                throw UnsupportedOperationException()

            override fun <U : DomainEvent> select(subtype: TypeLiteral<U>, vararg qualifiers: kotlin.Annotation): Event<U> =
                throw UnsupportedOperationException()
        }
    }
}
