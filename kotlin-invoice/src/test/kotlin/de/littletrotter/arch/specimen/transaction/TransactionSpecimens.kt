package de.littletrotter.arch.specimen.transaction

import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Event
import jakarta.transaction.Transactional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future

/** Stands for a client that leaves the process (object store, broker, HTTP). */
interface SpecimenIoPort {
    fun call()
}

// ── transaction outside the application layer ──

/** Negative: a repository-like class that starts the transaction itself. */
@ApplicationScoped
open class TransactionInAdapterSpecimen {
    @Transactional
    open fun save() = Unit
}

/** Negative: the annotation on the class counts as well. */
@ApplicationScoped
@Transactional
open class ClassLevelTransactionInAdapterSpecimen {
    open fun save() = Unit
}

/** Positive look-alike: same shape without a transaction annotation. */
@ApplicationScoped
open class PlainAdapterSpecimen {
    open fun save() = Unit
}

// ── I/O inside a transaction ──

/** Negative: the port is reached through a private helper and through a lambda. */
@ApplicationScoped
open class IoViaHelperInTransactionSpecimen(private val port: SpecimenIoPort) {
    @Transactional
    open fun direct() = helper()

    private fun helper() = port.call()

    @Transactional
    open fun viaLambda() {
        listOf(1).forEach { _ -> port.call() }
    }
}

/** Positive look-alike: the call happens before and after the transaction, not inside it. */
@ApplicationScoped
open class IoOutsideTransactionSpecimen(private val port: SpecimenIoPort) {
    fun outside() {
        port.call()
        inside()
        port.call()
    }

    @Transactional
    open fun inside() = Unit
}

// ── events inside a transaction ──

/** Negative: fires through a helper of the same class. */
@ApplicationScoped
open class EventInTransactionSpecimen(private val events: Event<String>) {
    @Transactional
    open fun change() = announce()

    private fun announce() = events.fireAsync("changed").let { }
}

/** Positive look-alike: fires from a method that has no transaction. */
@ApplicationScoped
open class EventOutsideTransactionSpecimen(private val events: Event<String>) {
    fun announce() = events.fire("changed")

    @Transactional
    open fun change() = Unit
}

// ── blocking inside a transaction ──

/** Negative: sleeps and waits for futures while the transaction is open. */
@ApplicationScoped
open class BlockingInTransactionSpecimen(private val future: CompletableFuture<String>, private val plain: Future<String>) {
    @Transactional
    open fun sleeps() = Thread.sleep(1)

    @Transactional
    open fun joins(): String = future.join()

    @Transactional
    open fun gets(): String = plain.get()
}

/** Positive look-alike: waits outside the transaction. */
@ApplicationScoped
open class BlockingOutsideTransactionSpecimen(private val future: CompletableFuture<String>) {
    fun waits(): String = future.join()

    @Transactional
    open fun change() = Unit
}

// ── independent transactions ──

/** Negative: REQUIRES_NEW on a method outside the application layer. */
@ApplicationScoped
open class RequiresNewInAdapterSpecimen {
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    open fun save() = Unit
}

/** Negative: programmatic independent transaction outside the application layer. */
@ApplicationScoped
open class QuarkusTransactionInAdapterSpecimen {
    fun save() = QuarkusTransaction.requiringNew().run { }
}

/** Positive look-alike: joins the caller's transaction instead of opening a new one. */
@ApplicationScoped
open class JoiningAdapterSpecimen {
    @Transactional(Transactional.TxType.MANDATORY)
    open fun save() = Unit
}

// ── interception ──

/** Negative: a private transactional method is never intercepted. */
@ApplicationScoped
open class PrivateTransactionalSpecimen {
    @Transactional
    private fun hidden() = Unit

    fun visible() = hidden()
}

/** Negative: `internal` compiles to a module-mangled public name that is not intercepted on a self-call. */
@ApplicationScoped
open class InternalTransactionalSpecimen {
    @Transactional
    internal open fun mangled() = Unit

    fun caller() = mangled()
}

/** Negative: a final class without a scope cannot be proxied. */
@Transactional
class FinalUnscopedTransactionalSpecimen {
    fun work() = Unit
}

/** Positive look-alike: public method in a scoped bean. */
@ApplicationScoped
open class InterceptableTransactionalSpecimen {
    @Transactional
    open fun work() = Unit
}
