package de.littletrotter.application.invoice

import de.littletrotter.application.dto.RecipientDraft
import de.littletrotter.application.masterdata.RecipientAddressInput
import de.littletrotter.application.masterdata.port.inbound.ManageRecipientUseCase
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.domain.repository.RecipientRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * CDI helper bean for [RecipientAddressSaveConcurrencyIT], which reproduces the lost-update
 * scenario through the REAL production entry point
 * ([ManageRecipientUseCase.saveRecipientWithAddresses], exactly what
 * [de.littletrotter.ui.view.masterdata.RecipientView.persistEntity] calls) instead of the
 * repository directly: two sessions open the same recipient and both change the SAME (billing)
 * street.
 *
 * Must be a top-level class — non-static inner classes cannot be CDI beans.
 */
@ApplicationScoped
class RecipientAddressSaveConcurrencyTestHelper {

    @Inject
    lateinit var manageRecipient: ManageRecipientUseCase

    @Inject
    lateinit var recipientQuery: RecipientQueryPort

    @Inject
    lateinit var recipientRepository: RecipientRepository

    /** Persists a minimal recipient with a billing address and returns its id. */
    @Transactional
    fun createRecipientWithBillingAddress(): Long {
        val recipient = RecipientDraft.blank().copy(
            customerNumber = "KD-ADDRCONC-${System.nanoTime()}",
            name = "Address Concurrency Test GmbH",
            customerType = CustomerType.CONSUMER,
            email = "addrconc-${System.nanoTime()}@example.invalid",
            addresses = billingAddress("Alte Straße 1"),
        )
        return manageRecipient.saveRecipientWithAddresses(recipient).id
    }

    /**
     * Loads [recipientId] (mirrors [de.littletrotter.ui.view.AbstractCrudView.saveCurrentForm]'s
     * form load — the detail read [de.littletrotter.ui.view.masterdata.RecipientView] fills its
     * draft from), signals [loadedLatch], waits on [startSignal], then calls the REAL
     * [ManageRecipientUseCase.saveRecipientWithAddresses] entry point with [newStreet] as the billing
     * street — the SAME field both concurrent sessions edit.
     *
     * [Transactional.TxType.REQUIRES_NEW] ensures each call from a different thread starts a
     * completely independent JTA transaction, mirroring two separate concurrent UI requests.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    fun loadEditBillingStreetAndSave(
        recipientId: Long,
        newStreet: String,
        loadedLatch: CountDownLatch,
        startSignal: CountDownLatch,
    ) {
        val loaded = RecipientDraft.from(recipientQuery.findRecipientDetail(recipientId)!!)
        loadedLatch.countDown()
        startSignal.await(10, TimeUnit.SECONDS)
        manageRecipient.saveRecipientWithAddresses(loaded.copy(addresses = billingAddress(newStreet)))
    }

    private fun billingAddress(street: String) = RecipientAddressInput(
        billingStreet = street,
        billingPostalCode = "10115",
        billingCity = "Berlin",
        billingCountry = "DE",
    )

    @Transactional
    fun currentBillingStreetAndVersion(recipientId: Long): Pair<String?, Long?> {
        val recipient = recipientRepository.findById(recipientId)!!
        return recipient.billingAddress?.street to recipient.version
    }
}
