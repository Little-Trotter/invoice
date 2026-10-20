package de.littletrotter.architecture.rules.txspecimen

import de.littletrotter.architecture.rules.txspecimen.io.Outbound
import de.littletrotter.architecture.rules.txspecimen.ports.PaymentLocal
import de.littletrotter.architecture.rules.txspecimen.ports.PaymentRemote
import de.littletrotter.shared.DocumentStore
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.event.Event
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import jakarta.transaction.TxType
import java.util.concurrent.CompletableFuture

/** Not an application class: any transaction boundary here is misplaced. */
class ViolatingRepository {
    @Transactional
    fun save(): Int = 1
}

@Transactional
class ViolatingClassLevel

/** The baseline names this class; its nested class is covered with it. */
class BaselinedRepository {
    @Transactional
    fun save(): Int = 1

    @Transactional(TxType.REQUIRES_NEW)
    class Inner {
        fun open(): Int = QuarkusTransaction.requiringNew()
    }
}

/** A repository without a boundary is what the rule wants. */
class PlainRepository {
    fun load(): Int = 1
}

class ViolatingRequiresNew {
    @Transactional(TxType.REQUIRES_NEW)
    fun save(): Int = 1
}

@Transactional(TxType.REQUIRES_NEW)
class ViolatingRequiresNewClass

class ViolatingProgrammatic {
    fun save(): Int = QuarkusTransaction.requiringNew()
}

class ViolatingIo(private val out: Outbound) {
    @Transactional
    fun send() = out.call()
}

class ViolatingIoViaHelper(private val out: Outbound) {
    @Transactional
    fun send() = helper()

    private fun helper() = out.call()
}

class ViolatingIoViaLambda(private val out: Outbound) {
    @Transactional
    fun send() {
        val later: () -> Int = { out.call() }
        later()
    }
}

class ViolatingIoViaChain(private val out: Outbound) {
    @Transactional
    fun send() = first()

    private fun first() = second()

    private fun second() = out.call()
}

class ViolatingRemotePort(private val port: PaymentRemote) {
    @Transactional
    fun pay() = port.charge()
}

class ViolatingSharedStore(private val store: DocumentStore) {
    @Transactional
    fun archive() = store.put("k")
}

class BaselinedIo(private val out: Outbound) {
    @Transactional
    fun send() = out.call()
}

class CleanTransactional(private val local: PaymentLocal, private val out: Outbound) {
    @Transactional
    fun pay() = local.charge()

    /** Reaches I/O, but no transaction is open. */
    fun notify() = out.call()
}

class ViolatingFire(private val events: Event<String>) {
    @Transactional
    fun publish() = events.fire("x")
}

class ViolatingFireAsync(private val events: Event<String>) {
    @Transactional
    fun publish() = events.fireAsync("x")
}

class BaselinedFire(private val events: Event<String>) {
    @Transactional
    fun publish() = events.fire("x")
}

class CleanEvents(private val events: Event<String>) {
    @Transactional
    fun narrow() = events.select()

    fun publish() = events.fire("x")
}

class ViolatingSleep {
    @Transactional
    fun block() = Thread.sleep(1)
}

class ViolatingFutureGet(private val future: CompletableFuture<Int>) {
    @Transactional
    fun block(): Int = future.get()
}

class ViolatingFutureJoin(private val future: CompletableFuture<Int>) {
    @Transactional
    fun block(): Int = future.join()
}

class BaselinedSleep {
    @Transactional
    fun block() = Thread.sleep(1)
}

class CleanBlocking(private val values: Map<String, Int>) {
    @Transactional
    fun read(): Int? = values["k"]

    fun pause() = Thread.sleep(1)
}

class ViolatingPrivateMethod {
    @Transactional
    private fun hidden(): Int = 1

    fun visible() = hidden()
}

class ViolatingInternalMethod {
    @Transactional
    internal open fun mangled(): Int = 1
}

/** Final without a scope: the container cannot subclass it to add the interceptor. */
class ViolatingFinalUnscoped {
    @Transactional
    fun save(): Int = 1
}

@Transactional
class ViolatingFinalUnscopedClassLevel

@Singleton
class InterceptableScoped {
    @Transactional
    fun save(): Int = 1
}

open class InterceptableOpen {
    @Transactional
    open fun save(): Int = 1
}
