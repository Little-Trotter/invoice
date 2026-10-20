package de.littletrotter.e2e.stress

import de.littletrotter.application.dto.StatisticsFilter
import de.littletrotter.application.invoice.InvoiceHeaderUpdateService
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.InvoiceItemMutationService
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.invoice.InvoiceOverviewQueryService
import de.littletrotter.application.invoice.UpdateInvoiceHeaderCommand
import de.littletrotter.application.invoice.port.inbound.InvoiceStatisticsQueryPort
import de.littletrotter.application.masterdata.ArticleManagementService
import de.littletrotter.application.masterdata.CustomerManagementService
import de.littletrotter.application.masterdata.SellerDefaultSelections
import de.littletrotter.application.masterdata.SellerProfileService
import de.littletrotter.domain.exception.InvoiceDomainException
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.masterdata.Article
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.NoActiveNumberRangeException
import de.littletrotter.domain.reference.NumberCollisionException
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.value.VatId
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.anArticle
import de.littletrotter.fixture.edit
import de.littletrotter.shared.Money
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.country.Country
import de.littletrotter.shared.sum
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// ── Tuning knobs ──────────────────────────────────────────────────────────────────────────────
private const val THREAD_COUNT = 100
private const val TOTAL_OPS = 2000
private const val DEFAULT_SEED = 424242L
private const val STORM_TIMEOUT_MINUTES = 6L
private const val POOL_SIZE = 10
private const val SEED_INVOICE_COUNT = 5
private const val SAMPLE_SIZE_CACHE = 50
private const val SAMPLE_SIZE_TOTALS = 30
private const val SAMPLE_SIZE_ITEMS = 200

// ── Weighted op names (invoice / recipient / sender / article / read — see OP_TABLE for the
//    exact weights; article.*/recipient.delete added for WP3, both hammering the shared POOL). ──
private const val OP_INVOICE_CREATE = "invoice.create"
private const val OP_INVOICE_ADD_ITEM = "invoice.addItem"
private const val OP_INVOICE_UPDATE_ITEM = "invoice.updateItem"
private const val OP_INVOICE_DELETE_ITEM = "invoice.deleteItem"
private const val OP_INVOICE_UPDATE_HEADER = "invoice.updateHeader"
private const val OP_INVOICE_VOID = "invoice.void"
private const val OP_INVOICE_STATUS_WALK = "invoice.statusWalk"
private const val OP_RECIPIENT_CREATE = "recipient.create"
private const val OP_RECIPIENT_UPDATE = "recipient.update"
private const val OP_RECIPIENT_DELETE = "recipient.delete"
private const val OP_RECIPIENT_READ = "recipient.read"
private const val OP_SENDER_CREATE = "sender.create"
private const val OP_SENDER_UPDATE = "sender.update"
private const val OP_ARTICLE_CREATE = "article.create"
private const val OP_ARTICLE_UPDATE = "article.update"
private const val OP_ARTICLE_DELETE = "article.delete"
private const val OP_READ_OVERVIEW_COLD = "read.overviewCold"
private const val OP_READ_OVERVIEW_REPEATED = "read.overviewRepeated"
private const val OP_READ_DETAIL = "read.detail"
private const val OP_READ_STATISTICS = "read.statistics"

private data class WeightedOp(val name: String, val weight: Int)

private val OP_TABLE = listOf(
    WeightedOp(OP_INVOICE_CREATE, 87),
    WeightedOp(OP_INVOICE_ADD_ITEM, 70),
    WeightedOp(OP_INVOICE_UPDATE_ITEM, 53),
    WeightedOp(OP_INVOICE_DELETE_ITEM, 35),
    WeightedOp(OP_INVOICE_UPDATE_HEADER, 52),
    WeightedOp(OP_INVOICE_VOID, 18),
    WeightedOp(OP_INVOICE_STATUS_WALK, 35),
    WeightedOp(OP_RECIPIENT_CREATE, 88),
    WeightedOp(OP_RECIPIENT_UPDATE, 75),
    WeightedOp(OP_RECIPIENT_DELETE, 20),
    WeightedOp(OP_RECIPIENT_READ, 87),
    WeightedOp(OP_SENDER_CREATE, 75),
    WeightedOp(OP_SENDER_UPDATE, 75),
    WeightedOp(OP_ARTICLE_CREATE, 60),
    WeightedOp(OP_ARTICLE_UPDATE, 60),
    WeightedOp(OP_ARTICLE_DELETE, 25),
    WeightedOp(OP_READ_OVERVIEW_COLD, 75),
    WeightedOp(OP_READ_OVERVIEW_REPEATED, 50),
    WeightedOp(OP_READ_DETAIL, 75),
    WeightedOp(OP_READ_STATISTICS, 50),
)
private val TOTAL_WEIGHT = OP_TABLE.sumOf { it.weight }

private fun pickOp(rnd: Random): String {
    var roll = rnd.nextInt(TOTAL_WEIGHT)
    for (op in OP_TABLE) {
        if (roll < op.weight) return op.name
        roll -= op.weight
    }
    return OP_TABLE.last().name
}

private fun <T> List<T>.pick(rnd: Random): T = this[rnd.nextInt(size)]

private fun <T> List<T>.pickOrNull(rnd: Random): T? = if (isEmpty()) null else this[rnd.nextInt(size)]

// ── Exception classification — walks the full cause chain, mirroring InvoiceVersionConflictIT's
//    own hasOptimisticLockInChain helper (Hibernate/Narayana can wrap the stale-version signal in
//    a RollbackException depending on whether the flush happens explicitly or at commit time). ──
private fun causeChain(t: Throwable): Sequence<Throwable> = generateSequence(t) { it.cause }.take(50)

private fun isOptimisticLock(t: Throwable): Boolean = causeChain(t).any {
    it is jakarta.persistence.OptimisticLockException || it is org.hibernate.StaleObjectStateException
}

/** The EXPECTED family: optimistic-lock conflicts plus every domain/validation
 *  rejection ([InvoiceDomainException] covers not-found/immutable/status-transition/validation) and
 *  the L4 number-range collision/exhaustion signals. Anything else fails the run.
 *
 *  WP3 note — [OP_RECIPIENT_DELETE]'s "still referenced by an invoice" rejection and
 *  [OP_ARTICLE_DELETE]'s "still in use" rejection both throw
 *  [de.littletrotter.domain.exception.InvalidParameterException], which is already a subtype of
 *  the sealed [InvoiceDomainException] above — no separate branch needed here, this is EXPECTED
 *  by construction. */
private fun isExpected(t: Throwable): Boolean = causeChain(t).any {
    it is InvoiceDomainException ||
        it is NumberCollisionException ||
        it is NoActiveNumberRangeException ||
        // The -> ISSUED finalization gate/compliance rejections; not part of the sealed
        // InvoiceDomainException hierarchy (see FinalizationGate.kt's own KDoc on why), but the
        // exact same "business rejection, not a bug" family as the rest of this list.
        it is de.littletrotter.domain.compliance.InvoiceFinalizationBlockedException ||
        it is de.littletrotter.domain.compliance.MixedTaxCategoryException ||
        it is jakarta.persistence.OptimisticLockException ||
        it is org.hibernate.StaleObjectStateException
}

private data class OpResult(val name: String, val ms: Double, val outcome: String)

/** Invariant 6 evidence — one successful [OP_INVOICE_UPDATE_ITEM] write. [marker] is the unique
 *  description suffix that write wrote; [expectedVersionRead] is the owning invoice's
 *  [Invoice.version] the writing thread read BEFORE issuing the write, in its own already-
 *  committed transaction (see [opInvoiceUpdateItem]'s own KDoc on why the read must not share the
 *  write's transaction/persistence context); [returnedVersion] is the aggregate version
 *  [de.littletrotter.application.invoice.InvoiceItemMutationService.updateItem] returned for the
 *  write that actually committed. A successful write always bumps [Invoice.version] by exactly
 *  one, so [returnedVersion] must be strictly greater than [expectedVersionRead] — see
 *  [assertNoLostUpdatesInvariant]'s own KDoc for the invariant this evidence checks. */
private data class ItemWrite(
    val invoiceId: Long,
    val itemId: Long,
    val marker: String,
    val expectedVersionRead: Long,
    val returnedVersion: Long,
)

/** Read-before-write evidence for a single invoice item, captured in its OWN committed
 *  transaction before [opInvoiceUpdateItem]/[opInvoiceDeleteItem]'s actual write — see
 *  [opInvoiceUpdateItem]'s own KDoc for why the read and the write must never share one
 *  transaction/persistence context. */
private data class InvoiceItemReadSnapshot(
    val itemId: Long,
    val positionId: String,
    val category: InvoiceItemCategory,
    val unit: InvoiceItemUnit,
    val invoiceVersion: Long,
)

/** Records one op's outcome/latency and — for an optimistic-lock conflict specifically — runs
 *  [recovery] (a re-read) to prove the entity stayed readable/consistent (invariant 1's second
 *  half). A [recovery] failure itself counts as an unexpected failure. */
private class Recorder {
    val results = ConcurrentLinkedQueue<OpResult>()
    val unexpected = ConcurrentLinkedQueue<String>()

    /** Returns true only when [block] AND its transaction's commit succeeded — a conflict detected
     *  at commit (OPTIMISTIC_FORCE_INCREMENT, @Version) surfaces here, not inside [block]. */
    fun track(opName: String, recovery: (() -> Unit)? = null, block: () -> Unit): Boolean {
        val start = System.nanoTime()
        val outcome = try {
            // These 100 worker threads are plain ExecutorService threads, not request/UI threads —
            // neither a transaction nor a CDI request context is active on them by default, so
            // every op (reads included) needs its own explicit boundary.
            QuarkusTransaction.requiringNew().run { block() }
            "SUCCESS"
        } catch (t: Throwable) {
            if (isExpected(t)) {
                if (isOptimisticLock(t) && recovery != null) {
                    try {
                        QuarkusTransaction.requiringNew().run { recovery() }
                    } catch (re: Throwable) {
                        unexpected += "recovery-after-optlock[$opName]: ${re::class.qualifiedName}: ${re.message}"
                    }
                }
                "EXPECTED"
            } else {
                unexpected += "$opName: ${t::class.qualifiedName}: ${t.message}"
                "UNEXPECTED"
            }
        }
        results += OpResult(opName, (System.nanoTime() - start) / 1_000_000.0, outcome)
        return outcome == "SUCCESS"
    }
}

private data class Pools(
    val tag: String,
    val taxRateIds: List<Long>,
    val senderPool: List<Long>,
    val recipientPool: List<Long>,
    val articlePool: List<Long>,
    val createdInvoiceIds: CopyOnWriteArrayList<Long>,
    val allRecipientIds: CopyOnWriteArrayList<Long>,
    val allSenderIds: CopyOnWriteArrayList<Long>,
    val allArticleIds: CopyOnWriteArrayList<Long>,
    // Invariant 6 evidence, filled by every successful invoice.updateItem — see [ItemWrite].
    val itemWrites: ConcurrentLinkedQueue<ItemWrite> = ConcurrentLinkedQueue(),
    // Recipients a delete was started for. Registered BEFORE the delete transaction so a racing
    // update's recovery re-read can never run ahead of the registration of the delete that won.
    val recipientDeleteAttempts: MutableSet<Long> = ConcurrentHashMap.newKeySet(),
)

// A shared, thread-safe, monotonic counter — bounded to a few digits even under 100 threads
// hammering it, unlike System.nanoTime() (up to 19 digits): several NOT NULL varchar columns
// this storm writes into are as narrow as invoice_prefix's 10 chars, so every generated string
// needs a SHORT, not just unique, suffix.
private val uniqueSeq = AtomicInteger(0)

private fun buildStressRecipient(tag: String, rnd: Random): Recipient {
    val uniq = "${uniqueSeq.incrementAndGet()}${rnd.nextInt(1000)}"
    val recipient = aRecipient {
        name = "$tag Kunde $uniq"
        email = Email.ofNullable("kunde$uniq@$tag.example".lowercase())
        vatId = VatId("DE111111111")
        routingId = "PO-$tag-$uniq"
    }
    recipient.addAddress(
        Address.forRecipient(
            recipient, Address.AddressType.BILLING, "Stresspfad 1", "10115", "Berlin", Country.DE, true,
        ),
    )
    return recipient
}

private fun buildStressSender(tag: String, rnd: Random): SenderProfile {
    val uniq = "${uniqueSeq.incrementAndGet()}${rnd.nextInt(1000)}"
    val sender = aSenderProfile {
        name = "$tag Anbieter $uniq"
        invoicePrefix = "S$uniq-".take(10)
        registerCourt = "AG Musterstadt"
        email = Email.ofNullable("anbieter$uniq@$tag.example".lowercase())
        taxNumber = TaxNumber("12/345/67890")
        vatId = VatId("DE111111111")
        iban = Iban.ofNullable("DE89370400440532013000")
    }
    sender.addAddress(
        Address.forSender(sender, Address.AddressType.BILLING, "Stressweg 1", "10115", "Berlin", Country.DE, true),
    )
    return sender
}

/** article = Leistungsverzeichnis entry. SERVICE/HOUR mirrors the pairing this storm already uses
 *  for invoice items ([LeistungsverzeichnisRules][de.littletrotter.shared.catalogue.LeistungsverzeichnisRules]
 *  allows it); `defaultTaxRateRef` is left `null` — optional on [Article], and a stress fixture has
 *  no need to attach a managed [de.littletrotter.domain.reference.TaxRate] entity reference. */
private fun buildStressArticle(tag: String, rnd: Random): Article {
    val uniq = "${uniqueSeq.incrementAndGet()}${rnd.nextInt(1000)}"
    return anArticle {
        name = "$tag Artikel $uniq"
        category = InvoiceItemCategory.SERVICE
        defaultUnit = InvoiceItemUnit.HOUR
        defaultQuantity = BigDecimal.ONE
        defaultUnitPriceNet = Money.of("50.00")
    }
}

/**
 * The JVM concurrency harness: 100 real
 * threads run [TOTAL_OPS] weighted-random CRUD operations through the REAL, CDI-injected
 * application facades ([InvoiceLifecycleService], [CustomerManagementService],
 * [SellerProfileService], [InvoiceOverviewQueryService], [InvoiceStatisticsQueryPort]) against
 * the module's real Postgres (Testcontainers). Every worker thread carries its own seeded
 * [Random] (base seed from `-Dstress.seed`, default [DEFAULT_SEED]) for a reproducible run.
 *
 * Excluded from the default failsafe run via the `stress` JUnit tag (see kotlin-e2e-tests/pom.xml)
 * — run explicitly with `make test IT_TESTS=ConcurrentCrudStressE2EIT MODULE=all`. Named
 * `*E2EIT` (not a plain `*IT`) to satisfy this module's own structural guard,
 * [E2eModuleStructureTest][de.littletrotter.e2e.E2eModuleStructureTest].
 *
 * Seven invariants, checked in a settle phase after the storm:
 * 1. no exception outside the EXPECTED optimistic-lock/domain-validation family — see [isExpected]
 *    — and every optimistic-lock conflict leaves its entity readable afterwards ([Recorder.track]).
 *    Covers [OP_RECIPIENT_DELETE]/[OP_ARTICLE_DELETE]'s "still referenced/in use" domain rejection
 *    too (an [de.littletrotter.domain.exception.InvalidParameterException], EXPECTED by design).
 * 2. the GAPLESS invoice number range has no holes/duplicates among ISSUED invoices; customer/seller
 *    numbers stay globally unique despite [POOL_SIZE]-thread contention on their shared counters
 *    (the L4 regression the spec calls out deliberately).
 * 3. the overview's cache-served read equals a direct repository read (amounts/status), and page 0
 *    stays sorted `invoiceDate DESC, id DESC` under load.
 * 4. every sampled invoice's totals equal the sum of its own items after the storm.
 * 5. the storm completes within [STORM_TIMEOUT_MINUTES] — a timeout here is exactly what
 *    connection-pool exhaustion or a real deadlock would look like.
 * 6. no lost update on invoice items: every successful [OP_INVOICE_UPDATE_ITEM] write records
 *    (invoiceId, expectedVersionRead, returnedVersion) ([ItemWrite]). Grouped by INVOICE (not
 *    item — the version guard is checked one level up, on the owning invoice): no two successful
 *    writes to the same invoice may share the same `expectedVersionRead`, and every write's
 *    `returnedVersion` must exceed the version it read — either violation is a lost update.
 *    A cheap secondary check (the highest-`returnedVersion` write per item is what is actually
 *    persisted) is kept alongside it, sampled to at most [SAMPLE_SIZE_ITEMS] pairs. See
 *    [assertNoLostUpdatesInvariant]'s own KDoc.
 * 7. zero deadlocks: `pg_stat_database.deadlocks` for this database must not increase across the
 *    storm (H7 / the spec's deadlock-delta requirement).
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
@Tag("stress")
class ConcurrentCrudStressE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var lifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var headerUpdateService: InvoiceHeaderUpdateService

    @Inject
    lateinit var itemMutationService: InvoiceItemMutationService

    @Inject
    lateinit var customerService: CustomerManagementService

    @Inject
    lateinit var sellerService: SellerProfileService

    @Inject
    lateinit var articleService: ArticleManagementService

    @Inject
    lateinit var overviewQueryService: InvoiceOverviewQueryService

    @Inject
    lateinit var statisticsQuery: InvoiceStatisticsQueryPort

    @Inject
    lateinit var invoiceRepository: InvoiceRepository

    @Test
    fun `100 threads of weighted random CRUD settle into five consistent invariants`() {
        // Short on purpose (see uniqueSeq's own KDoc): this tag is embedded into several NOT
        // NULL varchar columns as narrow as invoice_prefix's 10 chars.
        val tag = "STR${System.nanoTime() % 100000}"
        val baseSeed = System.getProperty("stress.seed")?.toLongOrNull() ?: DEFAULT_SEED
        println("[stress] base seed=$baseSeed tag=$tag threads=$THREAD_COUNT ops=$TOTAL_OPS")

        // Baseline BEFORE buildPools: its SEED_INVOICE_COUNT invoices draw numbers and are part of
        // pools.createdInvoiceIds, which invariant 2 compares the counter delta against.
        val numberCounterBaseline = invoiceNumberCounterTotal()
        val pools = buildPools(tag)
        val deadlocksBefore = deadlockCountInDb()

        val recorder = runStorm(pools, baseSeed)

        val deadlocksAfter = deadlockCountInDb()

        printReport(recorder.results.toList())
        println("[stress] unexpected failures: ${recorder.unexpected.size}")
        println("[stress] invariant 7 — deadlocks before=$deadlocksBefore after=$deadlocksAfter")

        assertNoUnexpectedFailures(recorder.unexpected.toList())
        assertNumberRangeInvariant(numberCounterBaseline, pools)
        assertCacheTruthInvariant(pools)
        assertTotalsInvariant(pools)
        assertNoLostUpdatesInvariant(pools)
        assertNoDeadlocksInvariant(deadlocksBefore, deadlocksAfter)
    }

    // ── Fixture setup — pre-seeded pools, built directly like buildParties in the sibling suites;
    //    the REAL facades are exercised by the storm itself, not by fixture setup. ────────────────

    private fun buildPools(tag: String): Pools {
        val taxRateId = QuarkusTransaction.requiringNew().call<Long> {
            val taxRate = TaxRate().edit {
                code = "${tag}_STD19"; category = "S"; name = "USt 19 %"; rate = TaxRatePercent(BigDecimal("19.00"))
                country = Country.DE; validFrom = LocalDate.of(2024, 1, 1); isSelectable = true; isActive = true
            }
            em.persist(taxRate)
            em.flush()
            taxRate.id!!
        }

        val senderPool = (1..POOL_SIZE).map { i ->
            QuarkusTransaction.requiringNew().call<Long> {
                val sender = aSenderProfile {
                    name = "$tag Pool-Anbieter $i"
                    invoicePrefix = "SP$i-"
                    registerCourt = "AG Musterstadt"
                    email = Email.ofNullable("pool-anbieter-$i@$tag.example".lowercase())
                    taxNumber = TaxNumber("12/345/67890")
                    vatId = VatId("DE111111111")
                    iban = Iban.ofNullable("DE89370400440532013000")
                }
                sender.addAddress(
                    Address.forSender(
                        sender, Address.AddressType.BILLING, "Anbieterweg $i", "10115", "Berlin", Country.DE, true,
                    ),
                )
                em.persist(sender)
                em.flush()
                sender.id!!
            }
        }

        val recipientPool = (1..POOL_SIZE).map { i ->
            QuarkusTransaction.requiringNew().call<Long> {
                val recipient = aRecipient {
                    customerNumber = CustomerNumber("$tag-RP$i")
                    name = "$tag Pool-Kunde $i"
                    email = Email.ofNullable("pool-kunde-$i@$tag.example".lowercase())
                    vatId = VatId("DE222222222")
                    routingId = "PO-$tag-P$i"
                }
                recipient.addAddress(
                    Address.forRecipient(
                        recipient, Address.AddressType.BILLING, "Kundenallee $i", "20095", "Hamburg", Country.DE, true,
                    ),
                )
                em.persist(recipient)
                em.flush()
                recipient.id!!
            }
        }

        val articlePool = (1..POOL_SIZE).map { i ->
            QuarkusTransaction.requiringNew().call<Long> {
                val article = anArticle {
                    name = "$tag Pool-Artikel $i"
                    category = InvoiceItemCategory.SERVICE
                    defaultUnit = InvoiceItemUnit.HOUR
                    defaultQuantity = BigDecimal.ONE
                    defaultUnitPriceNet = Money.of("50.00")
                }
                em.persist(article)
                em.flush()
                article.id!!
            }
        }

        val createdInvoiceIds = CopyOnWriteArrayList<Long>()
        repeat(SEED_INVOICE_COUNT) { i ->
            val invoiceId = QuarkusTransaction.requiringNew().call<Long> {
                lifecycleService.create(senderPool[i % senderPool.size], recipientPool[i % recipientPool.size]).id!!
            }
            QuarkusTransaction.requiringNew().run {
                itemMutationService.addItem(
                    invoiceId,
                    InvoiceItemCommand(
                        category = InvoiceItemCategory.SERVICE,
                        description = "$tag Startposition",
                        quantity = BigDecimal("1"),
                        unit = InvoiceItemUnit.HOUR,
                        unitPriceNet = Money.of("100.00"),
                        taxRateId = taxRateId,
                    ),
                )
            }
            createdInvoiceIds += invoiceId
        }

        return Pools(
            tag = tag,
            taxRateIds = listOf(taxRateId),
            senderPool = senderPool,
            recipientPool = recipientPool,
            articlePool = articlePool,
            createdInvoiceIds = createdInvoiceIds,
            allRecipientIds = CopyOnWriteArrayList(recipientPool),
            allSenderIds = CopyOnWriteArrayList(senderPool),
            allArticleIds = CopyOnWriteArrayList(articlePool),
        )
    }

    // ── The storm ────────────────────────────────────────────────────────────────────────────────

    private fun runStorm(pools: Pools, baseSeed: Long): Recorder {
        val recorder = Recorder()
        val opCounter = AtomicInteger(TOTAL_OPS)
        val executor = Executors.newFixedThreadPool(THREAD_COUNT)

        val tasks = (0 until THREAD_COUNT).map { threadIndex ->
            Runnable {
                val seed = baseSeed + threadIndex
                println("[stress] worker $threadIndex seed=$seed")
                val rnd = Random(seed)
                while (opCounter.getAndDecrement() > 0) {
                    executeOp(pickOp(rnd), rnd, pools, recorder)
                }
            }
        }
        tasks.forEach { executor.submit(it) }
        executor.shutdown()

        val completed = executor.awaitTermination(STORM_TIMEOUT_MINUTES, TimeUnit.MINUTES)
        if (!completed) {
            executor.shutdownNow()
            recorder.unexpected += "Invariant 5 — storm did not complete within $STORM_TIMEOUT_MINUTES minutes " +
                "(possible connection-pool exhaustion or deadlock)"
        }
        return recorder
    }

    private fun executeOp(opName: String, rnd: Random, pools: Pools, rec: Recorder) {
        when (opName) {
            OP_INVOICE_CREATE -> opInvoiceCreate(rnd, pools, rec)
            OP_INVOICE_ADD_ITEM -> opInvoiceAddItem(rnd, pools, rec)
            OP_INVOICE_UPDATE_ITEM -> opInvoiceUpdateItem(rnd, pools, rec)
            OP_INVOICE_DELETE_ITEM -> opInvoiceDeleteItem(rnd, pools, rec)
            OP_INVOICE_UPDATE_HEADER -> opInvoiceUpdateHeader(rnd, pools, rec)
            OP_INVOICE_VOID -> opInvoiceVoid(rnd, pools, rec)
            OP_INVOICE_STATUS_WALK -> opInvoiceStatusWalk(rnd, pools, rec)
            OP_RECIPIENT_CREATE -> opRecipientCreate(rnd, pools, rec)
            OP_RECIPIENT_UPDATE -> opRecipientUpdate(rnd, pools, rec)
            OP_RECIPIENT_DELETE -> opRecipientDelete(rnd, pools, rec)
            OP_RECIPIENT_READ -> opRecipientRead(pools, rec)
            OP_SENDER_CREATE -> opSenderCreate(rnd, pools, rec)
            OP_SENDER_UPDATE -> opSenderUpdate(rnd, pools, rec)
            OP_ARTICLE_CREATE -> opArticleCreate(rnd, pools, rec)
            OP_ARTICLE_UPDATE -> opArticleUpdate(rnd, pools, rec)
            OP_ARTICLE_DELETE -> opArticleDelete(rnd, pools, rec)
            OP_READ_OVERVIEW_COLD -> opReadOverviewCold(rnd, rec)
            OP_READ_OVERVIEW_REPEATED -> opReadOverviewRepeated(rec)
            OP_READ_DETAIL -> opReadDetail(rnd, pools, rec)
            OP_READ_STATISTICS -> opReadStatistics(rec)
        }
    }

    // ── Invoice ops ──────────────────────────────────────────────────────────────────────────────

    private fun opInvoiceCreate(rnd: Random, pools: Pools, rec: Recorder) = rec.track(OP_INVOICE_CREATE) {
        val invoice = lifecycleService.create(pools.senderPool.pick(rnd), pools.recipientPool.pick(rnd))
        pools.createdInvoiceIds += invoice.id!!
    }

    /** Reads the invoice's CURRENT version in its own, already-committed transaction BEFORE the
     *  write — see [opInvoiceUpdateItem]'s own KDoc on why the read must not share the write's
     *  transaction (a same-transaction read+write would trivially "agree" with itself and never
     *  surface a real conflict). */
    private fun opInvoiceAddItem(rnd: Random, pools: Pools, rec: Recorder) {
        val id = pools.createdInvoiceIds.pick(rnd)
        val versionRead = QuarkusTransaction.requiringNew().call<Long?> { lifecycleService.findById(id)?.version }
        rec.track(OP_INVOICE_ADD_ITEM, recovery = { assertInvoiceReadable(id) }) {
            itemMutationService.addItem(
                id,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.SERVICE,
                    description = "Stress add ${rnd.nextInt(100000)}",
                    quantity = BigDecimal(1 + rnd.nextInt(5)),
                    unit = InvoiceItemUnit.HOUR,
                    unitPriceNet = Money.of(BigDecimal(10 + rnd.nextInt(500))),
                    taxRateId = pools.taxRateIds.pick(rnd),
                    expectedVersion = versionRead,
                ),
            )
        }
    }

    /** Reads (item + owning invoice version) in its OWN, already-committed transaction, strictly
     *  BEFORE [rec]'s write transaction opens. This must NOT be one `QuarkusTransaction
     *  .requiringNew()` block for read+write together: within a single transaction every read
     *  shares the SAME persistence context, so [InvoiceItemMutationService.updateItem]'s own
     *  reload of the invoice would return the IDENTICAL managed instance this read already holds —
     *  [de.littletrotter.domain.invoice.requireExpectedVersion] would then always compare an object against itself and
     *  never see a concurrent commit, no matter how many threads race on it. Splitting the read
     *  into its own committed transaction closes that gap: a concurrent write that commits in
     *  between is now visible to [updateItemCall]'s fresh reload and genuinely conflicts —
     *  surfacing as the EXPECTED [jakarta.persistence.OptimisticLockException], never silently
     *  last-writer-wins. Invariant 6 evidence ([ItemWrite]: marker, the version read here, and the
     *  committed version re-read after commit) is recorded via [recordItemWrite] on every committed success. */
    private fun opInvoiceUpdateItem(rnd: Random, pools: Pools, rec: Recorder) {
        val id = pools.createdInvoiceIds.pick(rnd)
        val snapshot = QuarkusTransaction.requiringNew().call<InvoiceItemReadSnapshot?> {
            val invoice = lifecycleService.findById(id)
            val item = invoice?.itemList?.pickOrNull(rnd)
            if (invoice != null && item != null) {
                InvoiceItemReadSnapshot(item.id!!, item.positionId, item.category, item.unit, invoice.version ?: 0L)
            } else {
                null
            }
        }
        var written: String? = null
        val committed = rec.track(OP_INVOICE_UPDATE_ITEM, recovery = { assertInvoiceReadable(id) }) {
            if (snapshot != null) {
                // Unique via the shared uniqueSeq (not rnd.nextInt) — invariant 6 needs a marker
                // that can never collide across threads, or a real lost update could hide behind
                // two different writes coincidentally producing the same description text.
                val marker = "U${uniqueSeq.incrementAndGet()}"
                val updated = updateItemCall(
                    id,
                    InvoiceItemCommand(
                        itemId = snapshot.itemId,
                        category = snapshot.category,
                        description = "Stress upd $marker",
                        quantity = BigDecimal(1 + rnd.nextInt(5)),
                        unit = snapshot.unit,
                        unitPriceNet = Money.of(BigDecimal(10 + rnd.nextInt(500))),
                        taxRateId = pools.taxRateIds.pick(rnd),
                        expectedVersion = snapshot.invoiceVersion,
                    ),
                )
                written = marker
                check(updated.id == id)
            }
        }
        // Record only after the write's transaction COMMITTED (track returned true): the version is
        // bumped at commit (OPTIMISTIC_FORCE_INCREMENT), so the entity returned inside the
        // transaction still carries the old value, and a commit-time conflict must not count as a
        // successful write. The committed version is re-read in a fresh transaction.
        val marker = written
        if (committed && marker != null && snapshot != null) {
            val committedVersion = QuarkusTransaction.requiringNew().call<Long?> { lifecycleService.findById(id)?.version }
            recordItemWrite(pools, id, snapshot.itemId, marker, snapshot.invoiceVersion, committedVersion)
        }
    }

    /** Isolates the [InvoiceItemMutationService.updateItem] call site — [command].expectedVersion
     *  carries the invoice version [opInvoiceUpdateItem] read before this call, in a separate,
     *  already-committed transaction (WP1, 0.9.10), so a genuinely concurrent commit surfaces here
     *  as [jakarta.persistence.OptimisticLockException] instead of silently last-writer-wins. */
    private fun updateItemCall(invoiceId: Long, command: InvoiceItemCommand): Invoice =
        itemMutationService.updateItem(invoiceId, command)

    private fun recordItemWrite(
        pools: Pools,
        invoiceId: Long,
        itemId: Long,
        marker: String,
        expectedVersionRead: Long,
        returnedVersion: Long?,
    ) {
        pools.itemWrites += ItemWrite(invoiceId, itemId, marker, expectedVersionRead, returnedVersion ?: 0L)
    }

    /** Same read-before-write split as [opInvoiceUpdateItem] — see that function's own KDoc on
     *  why the read must run in its own committed transaction, separate from the delete write. */
    private fun opInvoiceDeleteItem(rnd: Random, pools: Pools, rec: Recorder) {
        val id = pools.createdInvoiceIds.pick(rnd)
        val snapshot = QuarkusTransaction.requiringNew().call<InvoiceItemReadSnapshot?> {
            val invoice = lifecycleService.findById(id)
            val item = invoice?.itemList?.pickOrNull(rnd)
            if (invoice != null && item != null) {
                InvoiceItemReadSnapshot(item.id!!, item.positionId, item.category, item.unit, invoice.version ?: 0L)
            } else {
                null
            }
        }
        rec.track(OP_INVOICE_DELETE_ITEM, recovery = { assertInvoiceReadable(id) }) {
            if (snapshot != null) itemMutationService.deleteItemByPosition(id, snapshot.positionId, snapshot.invoiceVersion)
        }
    }

    private fun opInvoiceUpdateHeader(rnd: Random, pools: Pools, rec: Recorder) {
        val id = pools.createdInvoiceIds.pick(rnd)
        rec.track(OP_INVOICE_UPDATE_HEADER, recovery = { assertInvoiceReadable(id) }) {
            val invoice = lifecycleService.findById(id)
            if (invoice != null) {
                headerUpdateService.updateHeader(
                    UpdateInvoiceHeaderCommand(
                        invoiceId = id,
                        expectedVersion = null,
                        invoiceDate = null,
                        servicePeriodFrom = invoice.servicePeriodFrom,
                        servicePeriodTo = invoice.servicePeriodTo,
                        dueDate = invoice.dueDate,
                        freeText = "Stress note ${rnd.nextInt(1000000)}",
                        buyerReference = invoice.references.buyerReference,
                        orderReference = invoice.references.orderReference,
                        projectNumber = invoice.references.projectNumber,
                        orderReferenceDate = invoice.references.orderReferenceDate,
                        currency = invoice.currency,
                        recipientId = invoice.recipient?.id,
                        senderProfileId = invoice.senderProfile?.id,
                        paymentTermId = invoice.paymentTerm?.id,
                        purchaseOrderReference = invoice.references.purchaseOrderReference,
                    ),
                )
            }
        }
    }

    private fun opInvoiceVoid(rnd: Random, pools: Pools, rec: Recorder) {
        val id = pools.createdInvoiceIds.pick(rnd)
        rec.track(OP_INVOICE_VOID, recovery = { assertInvoiceReadable(id) }) {
            lifecycleService.transition(id, InvoiceStatus.VOIDED)
        }
    }

    /** Walks DRAFT -> IN_REVIEW -> (DRAFT or ISSUED), matching the spec's "occasional status walk to
     *  review/back". Since N5, the ISSUED branch no longer draws the invoice number (that now
     *  happens at `opInvoiceCreate`, see `assertNumberRangeInvariant`'s own KDoc) — it still
     *  exercises the party-snapshot freeze and the terminal-status transition under contention. */
    private fun opInvoiceStatusWalk(rnd: Random, pools: Pools, rec: Recorder) {
        val id = pools.createdInvoiceIds.pick(rnd)
        rec.track(OP_INVOICE_STATUS_WALK, recovery = { assertInvoiceReadable(id) }) {
            val invoice = lifecycleService.findById(id)
            if (invoice != null) {
                val target = when (invoice.status) {
                    InvoiceStatus.DRAFT -> InvoiceStatus.IN_REVIEW
                    InvoiceStatus.IN_REVIEW -> if (rnd.nextBoolean()) InvoiceStatus.DRAFT else InvoiceStatus.ISSUED
                    else -> InvoiceStatus.IN_REVIEW
                }
                lifecycleService.transition(id, target)
            }
        }
    }

    // ── Recipient / sender ops ───────────────────────────────────────────────────────────────────

    private fun opRecipientCreate(rnd: Random, pools: Pools, rec: Recorder) = rec.track(OP_RECIPIENT_CREATE) {
        pools.allRecipientIds += customerService.save(buildStressRecipient(pools.tag, rnd)).id!!
    }

    /** Reads in its OWN, already-committed transaction before the write — see
     *  [opInvoiceUpdateItem]'s own KDoc: reading and writing inside the SAME transaction shares one
     *  persistence context, so [CustomerManagementService.save]'s own reload would return the
     *  IDENTICAL instance this read already holds and its version compare could never fail, no
     *  matter how many threads race on this row. */
    private fun opRecipientUpdate(rnd: Random, pools: Pools, rec: Recorder) {
        val id = pools.allRecipientIds.pick(rnd)
        val existing = QuarkusTransaction.requiringNew().call<Recipient?> { customerService.findById(id) }
        rec.track(OP_RECIPIENT_UPDATE, recovery = { assertRecipientReadable(id, pools) }) {
            if (existing != null) {
                existing.edit { vatId = VatId("DE${100000000 + rnd.nextInt(800000000)}") }
                customerService.save(existing)
            }
        }
    }

    /** Targets [Pools.allRecipientIds] — the same shared list [opRecipientUpdate] draws from — on
     *  purpose: pool recipients are also wired into most seeded/created invoices
     *  ([Pools.recipientPool] is what [opInvoiceCreate] draws from), so this collides both with
     *  concurrent recipient updates on the same row (optimistic lock) AND, far more often, with
     *  [CustomerManagementService.delete]'s own "still referenced by an invoice" domain rejection
     *  — expected by design (see [isExpected]'s WP3 note). A handful of never-invoiced, freshly
     *  created recipients CAN delete successfully; both outcomes are correct here. */
    /** Reads the recipient's CURRENT version in its own committed transaction before the delete —
     *  see [opInvoiceUpdateItem]'s own KDoc on why the read must not share the write's
     *  transaction/persistence context. */
    private fun opRecipientDelete(rnd: Random, pools: Pools, rec: Recorder) {
        val id = pools.allRecipientIds.pick(rnd)
        pools.recipientDeleteAttempts += id
        val versionRead = QuarkusTransaction.requiringNew().call<Long?> { customerService.findById(id)?.version }
        rec.track(OP_RECIPIENT_DELETE) { deleteRecipientCall(id, versionRead) }
    }

    /** Isolates the [CustomerManagementService.delete] call site — [expectedVersion] is the
     *  version [opRecipientDelete] read before this call (WP2, 0.9.10), so a delete racing a
     *  concurrent update/delete surfaces here as [jakarta.persistence.OptimisticLockException]
     *  instead of silently discarding the other session's edit. */
    private fun deleteRecipientCall(id: Long, expectedVersion: Long?) = customerService.delete(id, expectedVersion)

    private fun opRecipientRead(pools: Pools, rec: Recorder) = rec.track(OP_RECIPIENT_READ) {
        customerService.findAll(0, 20, pools.tag)
    }

    private fun opSenderCreate(rnd: Random, pools: Pools, rec: Recorder) = rec.track(OP_SENDER_CREATE) {
        pools.allSenderIds += sellerService.save(buildStressSender(pools.tag, rnd), defaults = SellerDefaultSelections()).id!!
    }

    /** Reads in its OWN, already-committed transaction before the write — see
     *  [opInvoiceUpdateItem]'s own KDoc: reading and writing inside the SAME transaction shares one
     *  persistence context, so [SellerProfileService.save]'s own reload would return the IDENTICAL
     *  instance this read already holds and its version compare could never fail, no matter how
     *  many threads race on this row. */
    private fun opSenderUpdate(rnd: Random, pools: Pools, rec: Recorder) {
        val id = pools.allSenderIds.pick(rnd)
        val existing = QuarkusTransaction.requiringNew().call<SenderProfile?> { sellerService.findById(id) }
        rec.track(OP_SENDER_UPDATE, recovery = { assertSenderReadable(id) }) {
            if (existing != null) {
                existing.edit { taxNumber = TaxNumber("12/${100 + rnd.nextInt(800)}/%05d".format(rnd.nextInt(99999))) }
                sellerService.save(existing, defaults = SellerDefaultSelections())
            }
        }
    }

    // ── Article ops (article = Leistungsverzeichnis; ArticleManagementService) ──────────────────

    private fun opArticleCreate(rnd: Random, pools: Pools, rec: Recorder) = rec.track(OP_ARTICLE_CREATE) {
        pools.allArticleIds += saveArticleCall(buildStressArticle(pools.tag, rnd)).id!!
    }

    /** Targets [Pools.allArticleIds] (seeded from the fixed [Pools.articlePool], same shape as
     *  [opRecipientUpdate]/[opSenderUpdate]) — contention on purpose: with only [POOL_SIZE] pool
     *  rows and this op's weight, several threads collide on the same article row throughout the
     *  storm, producing genuine update/update optimistic-lock conflicts. */
    /** Reads in its OWN, already-committed transaction before the write — see
     *  [opInvoiceUpdateItem]'s own KDoc: reading and writing inside the SAME transaction shares one
     *  persistence context, so [ArticleManagementService.save]'s own reload would return the
     *  IDENTICAL instance this read already holds and its version compare could never fail, no
     *  matter how many threads race on this row. */
    private fun opArticleUpdate(rnd: Random, pools: Pools, rec: Recorder) {
        val id = pools.allArticleIds.pick(rnd)
        val existing = QuarkusTransaction.requiringNew().call<Article?> { articleService.findById(id) }
        rec.track(OP_ARTICLE_UPDATE, recovery = { assertArticleReadable(id) }) {
            if (existing != null) {
                existing.edit { defaultUnitPriceNet = Money.of(BigDecimal(10 + rnd.nextInt(500))) }
                saveArticleCall(existing)
            }
        }
    }

    /** Same shared-POOL targeting as [opArticleUpdate] — collides with concurrent article updates
     *  (optimistic lock) and, once an article is actually referenced by an invoice item, with
     *  [ArticleManagementService.delete]'s own "in use" domain rejection (EXPECTED, see
     *  [isExpected]'s WP3 note). */
    /** Reads the article's CURRENT version in its own committed transaction before the delete —
     *  see [opArticleUpdate]'s own KDoc on why the read must not share the write's transaction. */
    private fun opArticleDelete(rnd: Random, pools: Pools, rec: Recorder) {
        val id = pools.allArticleIds.pick(rnd)
        val versionRead = QuarkusTransaction.requiringNew().call<Long?> { articleService.findById(id)?.version }
        rec.track(OP_ARTICLE_DELETE) { deleteArticleCall(id, versionRead) }
    }

    /** Isolates the [ArticleManagementService.save] call site. */
    private fun saveArticleCall(article: Article): Article = articleService.save(article)

    /** Isolates the [ArticleManagementService.delete] call site — [expectedVersion] is the
     *  version [opArticleDelete] read before this call (WP2, 0.9.10), so a delete racing a
     *  concurrent update/delete surfaces here as [jakarta.persistence.OptimisticLockException]
     *  instead of silently discarding the other session's edit. */
    private fun deleteArticleCall(id: Long, expectedVersion: Long?) = articleService.delete(id, expectedVersion)

    private fun assertArticleReadable(id: Long) {
        check(articleService.findById(id) != null) { "article $id unreadable after an optimistic-lock conflict" }
    }

    // ── Read ops ─────────────────────────────────────────────────────────────────────────────────

    /** Cold: pageIndex varies (0-2), so the Valkey cache key varies too. */
    private fun opReadOverviewCold(rnd: Random, rec: Recorder) = rec.track(OP_READ_OVERVIEW_COLD) {
        overviewQueryService.findOverview(rnd.nextInt(3), 20, null, null)
    }

    /** Repeated: always the same page/filter, so the SAME Valkey cache key is hit on every call. */
    private fun opReadOverviewRepeated(rec: Recorder) = rec.track(OP_READ_OVERVIEW_REPEATED) {
        overviewQueryService.findOverview(0, 20, null, null)
    }

    private fun opReadDetail(rnd: Random, pools: Pools, rec: Recorder) = rec.track(OP_READ_DETAIL) {
        lifecycleService.findById(pools.createdInvoiceIds.pick(rnd))
    }

    private fun opReadStatistics(rec: Recorder) = rec.track(OP_READ_STATISTICS) {
        statisticsQuery.getStatusDistribution(StatisticsFilter.EMPTY)
    }

    // ── Optimistic-lock recovery re-reads (invariant 1's "still readable" half) ─────────────────

    private fun assertInvoiceReadable(id: Long) {
        check(invoiceRepository.findById(id) != null) { "invoice $id unreadable after an optimistic-lock conflict" }
    }

    /** A never-invoiced recipient can legitimately be deleted by a racing delete: the update then
     *  loses with an optimistic-lock conflict (zero rows updated at commit) and the row is gone.
     *  That is a correct outcome; a recipient nobody tried to delete must still be readable. */
    private fun assertRecipientReadable(id: Long, pools: Pools) {
        check(customerService.findById(id) != null || id in pools.recipientDeleteAttempts) { "recipient $id unreadable after an optimistic-lock conflict" }
    }

    private fun assertSenderReadable(id: Long) {
        check(sellerService.findById(id) != null) { "sender $id unreadable after an optimistic-lock conflict" }
    }

    // ── Report ───────────────────────────────────────────────────────────────────────────────────

    private fun printReport(results: List<OpResult>) {
        println("=== ST-1 Concurrent CRUD Stress Report ===")
        println("total ops recorded: ${results.size}")
        results.groupBy { it.name }.toSortedMap().forEach { (name, opResults) ->
            val success = opResults.count { it.outcome == "SUCCESS" }
            val expected = opResults.count { it.outcome == "EXPECTED" }
            val unexpected = opResults.count { it.outcome == "UNEXPECTED" }
            val sorted = opResults.map { it.ms }.sorted()
            val p95Index = ((sorted.size * 95 / 100)).coerceIn(0, sorted.size - 1)
            val p95 = if (sorted.isEmpty()) 0.0 else sorted[p95Index]
            println(
                "%-24s n=%-5d success=%-5d expected=%-5d unexpected=%-5d p95=%.2fms".format(
                    name, opResults.size, success, expected, unexpected, p95,
                ),
            )
        }
        println("===========================================")
    }

    // ── Invariants ───────────────────────────────────────────────────────────────────────────────

    private fun assertNoUnexpectedFailures(unexpected: List<String>) {
        assertTrue(
            unexpected.isEmpty(),
            "Invariant 1 violated — ${unexpected.size} unexpected exception(s), first up to 20:\n" +
                unexpected.take(20).joinToString("\n"),
        )
    }

    private fun invoiceNumberCounterTotal(): Long = QuarkusTransaction.requiringNew().call<Long> {
        (
            em.createNativeQuery(
                "SELECT COALESCE(SUM(nc.last_value), 0) FROM number_counter nc " +
                    "JOIN number_range nr ON nr.id = nc.number_range_id WHERE nr.subject = 0",
            ).singleResult as Number
            ).toLong()
    }

    /** Invariant 2 — the GAPLESS invoice range: exactly one draw per CREATED invoice (not per
     *  ISSUED transition — N5 moved the draw to `InvoiceLifecycleService.create`'s
     *  `support.numberAssigner.assignIfNeeded(invoice)` call, see that KDoc and
     *  `InvoiceNumberAssignment`'s own: the `→ ISSUED` edge's `assignIfNeeded` call is now only an
     *  idempotent no-op safety net for pre-N5 rows, so it draws nothing new). The drawn sequences
     *  form a contiguous block with no holes/duplicates. Plus a defense-in-depth uniqueness check
     *  on the UNIQUE_ONLY customer/seller ranges the storm hammered concurrently (the L4
     *  regression the spec calls out on purpose). */
    private fun assertNumberRangeInvariant(baseline: Long, pools: Pools) {
        val after = invoiceNumberCounterTotal()
        assertEquals(
            pools.createdInvoiceIds.size.toLong(), after - baseline,
            "Invariant 2 — every CREATED invoice must draw exactly one invoice number, no more, no less",
        )

        val sequences = QuarkusTransaction.requiringNew().call<List<Long>> {
            em.createQuery("SELECT i.invoiceNumberSequence FROM Invoice i WHERE i.invoiceNumberSequence > :baseline", Long::class.java)
                .setParameter("baseline", baseline)
                .resultList
                .filterNotNull()
                .sorted()
        }
        assertEquals(
            sequences.size, sequences.toSet().size,
            "Invariant 2 — no duplicate invoice number sequence among invoices drawn during this run",
        )
        assertEquals(
            ((baseline + 1)..after).toList(), sequences,
            "Invariant 2 — the GAPLESS invoice range must have no holes among invoices drawn during this run",
        )

        assertEquals(0L, duplicateCount("recipient", "customer_number"), "Invariant 2 — no duplicate customer numbers")
        assertEquals(0L, duplicateCount("sender_profile", "seller_number"), "Invariant 2 — no duplicate seller numbers")
    }

    private fun duplicateCount(table: String, column: String): Long = QuarkusTransaction.requiringNew().call<Long> {
        (
            em.createNativeQuery(
                "SELECT COUNT(*) - COUNT(DISTINCT $column) FROM $table WHERE $column IS NOT NULL AND $column <> ''",
            ).singleResult as Number
            ).toLong()
    }

    /** Invariant 3 — cache-served overview read equals a direct repository read, and page 0 stays
     *  sorted `invoiceDate DESC, id DESC` (the PI-9 rule, pinned at the service level). Holds
     *  regardless of whether the wired [de.littletrotter.application.cache.port.outbound.InvoiceCachePort] is a
     *  real Valkey or the test profile's Noop — this compares the query service's own two read
     *  paths, not the cache implementation. */
    private fun assertCacheTruthInvariant(pools: Pools) {
        val allIds = pools.createdInvoiceIds.toList()
        val sampleIds = if (allIds.size <= SAMPLE_SIZE_CACHE) allIds else allIds.shuffled().take(SAMPLE_SIZE_CACHE)
        val overviewPage = QuarkusTransaction.requiringNew()
            .call<List<de.littletrotter.application.dto.InvoiceOverviewDto>> { overviewQueryService.findOverview(0, allIds.size + 100, null, null) }
        val byId = overviewPage.associateBy { it.id }

        sampleIds.forEach { id ->
            val direct = QuarkusTransaction.requiringNew().call<Invoice?> { invoiceRepository.findById(id) }
            assertTrue(direct != null, "Invariant 3 — invoice $id must still be readable directly from the repository")
            val cached = byId[id]
            assertTrue(cached != null, "Invariant 3 — invoice $id must appear in the cache-path overview")
            assertEquals(
                direct!!.totals.grossAmount, cached!!.grossAmount,
                "Invariant 3 — cache path grossAmount must equal direct repository read for invoice $id",
            )
            assertEquals(
                direct.status, cached.status,
                "Invariant 3 — cache path status must equal direct repository read for invoice $id",
            )
        }

        overviewPage.zipWithNext().forEach { (prev, cur) ->
            val prevDate = prev.invoiceDate
            val curDate = cur.invoiceDate
            val orderMsg = "Invariant 3 — page 0 must sort invoice_date/id DESC (${prev.id} vs ${cur.id})"
            assertTrue(prevDate != null && curDate != null && prevDate >= curDate, orderMsg)
            if (prevDate == curDate) {
                assertTrue((prev.id ?: 0) >= (cur.id ?: 0), orderMsg)
            }
        }
    }

    /**
     * Invariant 6 — no lost update on invoice items.
     *
     * The OLD design here checked only "the write with the highest returned invoice.version is
     * what's actually persisted" — that check also holds trivially under plain last-writer-wins
     * with NO optimistic locking at all (the highest-version write, by definition, is whatever
     * committed last, whether or not it ever actually saw a conflict), so it proved nothing about
     * lost updates on its own. Kept below only as a cheap secondary/advisory check.
     *
     * The CORRECT primary check, grouped by INVOICE (not item): [InvoiceItemMutationService
     * .updateItem]'s version guard ([de.littletrotter.domain.invoice.requireExpectedVersion]) is checked against the
     * OWNING INVOICE's version, not a per-item one, so two concurrent writes to two DIFFERENT
     * items of the same invoice still race on that one shared version — first-committed-writer-
     * wins means at most one write per invoice may ever successfully read (and act on) a given
     * `expectedVersionRead`. A lost update is therefore either:
     *  - two successful writes to the same invoice recording the SAME `expectedVersionRead`
     *    ([ItemWrite]) — both thought they were writing against that exact version, but the
     *    guard should have rejected the second once the first committed; or
     *  - any write whose `returnedVersion` did not strictly exceed the `expectedVersionRead` it
     *    read — every successful write bumps [Invoice.version] by exactly one.
     */
    private fun assertNoLostUpdatesInvariant(pools: Pools) {
        val writes = pools.itemWrites.toList()

        // Primary check.
        val byInvoice = writes.groupBy { it.invoiceId }
        val invoiceSampleKeys =
            if (byInvoice.size <= SAMPLE_SIZE_ITEMS) byInvoice.keys.toList() else byInvoice.keys.shuffled().take(SAMPLE_SIZE_ITEMS)
        invoiceSampleKeys.forEach { invoiceId ->
            val invoiceWrites = byInvoice.getValue(invoiceId)
            invoiceWrites.forEach { w ->
                assertTrue(
                    w.returnedVersion > w.expectedVersionRead,
                    "Invariant 6 — lost update on invoice $invoiceId: write marker=${w.marker} returned version " +
                        "${w.returnedVersion}, which is not greater than the version it read (${w.expectedVersionRead}) " +
                        "— every successful write must bump Invoice.version",
                )
            }
            invoiceWrites.groupBy { it.expectedVersionRead }.forEach { (expectedVersion, group) ->
                assertEquals(
                    1, group.size,
                    "Invariant 6 — lost update on invoice $invoiceId: ${group.size} successful writes all read the " +
                        "same expectedVersionRead=$expectedVersion (markers=${group.map { it.marker }}) — first-" +
                        "committed-writer-wins means at most one write may ever successfully read a given version",
                )
            }
        }

        // Secondary, cheap check (kept from the old design, correctly scoped per item this time):
        // an item deleted by a later OP_INVOICE_DELETE_ITEM is skipped (correctly gone).
        val byItem = writes.groupBy { it.invoiceId to it.itemId }
        val itemSampleKeys = if (byItem.size <= SAMPLE_SIZE_ITEMS) byItem.keys.toList() else byItem.keys.shuffled().take(SAMPLE_SIZE_ITEMS)
        var itemsChecked = 0
        itemSampleKeys.forEach { (invoiceId, itemId) ->
            val lastWrite = byItem.getValue(invoiceId to itemId).maxByOrNull { it.returnedVersion } ?: return@forEach
            val invoice = QuarkusTransaction.requiringNew().call<Invoice?> { lifecycleService.findById(invoiceId) }
            val currentDescription = invoice?.itemList?.firstOrNull { it.id == itemId }?.description ?: return@forEach
            itemsChecked++
            assertEquals(
                "Stress upd ${lastWrite.marker}", currentDescription,
                "Invariant 6 (secondary) — invoice $invoiceId item $itemId: the write with the highest returned " +
                    "version (marker=${lastWrite.marker}, returnedVersion=${lastWrite.returnedVersion}) did not end " +
                    "up as the persisted description ('$currentDescription' found instead)",
            )
        }
        println(
            "[stress] invariant 6 — lost-update check: ${byInvoice.size} invoice(s)/${writes.size} write(s) " +
                "primary-checked, $itemsChecked item pair(s) secondary-sampled",
        )
    }

    /** Invariant 7 — zero deadlocks: `pg_stat_database.deadlocks` for this database must not
     *  increase across the storm, mirroring [de.littletrotter.application.invoice
     *  .GenerationJobDeadlockIT]'s/[de.littletrotter.application.invoice
     *  .AuditJournalLockOrderingDeadlockIT]'s own `deadlockCountInDb` pattern. */
    private fun deadlockCountInDb(): Long = QuarkusTransaction.requiringNew().call<Long> {
        (
            em.createNativeQuery("SELECT deadlocks FROM pg_stat_database WHERE datname = current_database()")
                .singleResult as Number
            ).toLong()
    }

    private fun assertNoDeadlocksInvariant(before: Long, after: Long) {
        assertEquals(before, after, "Invariant 7 — pg_stat_database.deadlocks must not increase (before=$before, after=$after)")
    }

    /** Invariant 4 — every sampled invoice's totals equal the sum of its own items after the storm. */
    private fun assertTotalsInvariant(pools: Pools) {
        val allIds = pools.createdInvoiceIds.toList()
        val sampleIds = if (allIds.size <= SAMPLE_SIZE_TOTALS) allIds else allIds.shuffled().take(SAMPLE_SIZE_TOTALS)

        sampleIds.forEach { id ->
            val invoice = QuarkusTransaction.requiringNew().call<Invoice?> { lifecycleService.findById(id) }
            assertTrue(invoice != null, "Invariant 4 — invoice $id must still be readable")
            val items = invoice!!.itemList
            val msg = "Invariant 4 — invoice $id"
            assertEquals(items.map { it.netAmount }.sum(), invoice.totals.netAmount, "$msg net")
            assertEquals(items.map { it.vatAmount }.sum(), invoice.totals.vatAmount, "$msg vat")
            assertEquals(items.map { it.grossAmount }.sum(), invoice.totals.grossAmount, "$msg gross")
        }
    }
}
