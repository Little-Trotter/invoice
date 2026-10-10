package de.littletrotter.application.reference

import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.B2bFieldOwnerType
import de.littletrotter.domain.reference.B2bFieldValue
import de.littletrotter.domain.reference.B2bPlacement
import de.littletrotter.domain.reference.B2bProfileField
import de.littletrotter.domain.reference.RecipientB2bFieldValue
import de.littletrotter.domain.repository.B2bFieldValueRepository
import de.littletrotter.domain.repository.RecipientB2bFieldValueRepository
import de.littletrotter.shared.b2b.B2bTerm
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

/** [B2bPartyFieldValues.partyValue]: the single lookup [B2bFieldValueService] (the form/export
 *  resolution chain) and [de.littletrotter.application.invoice.B2bProfileRuleSet] (the
 *  compliance-finding side) both delegate to, covering all three [B2bPlacement]s plus the
 *  missing-party-id/missing-field-id branches. */
class B2bPartyFieldValuesTest {

    private val fieldValueRepository = mock<B2bFieldValueRepository>()
    private val recipientFieldValueRepository = mock<RecipientB2bFieldValueRepository>()
    private val partyFieldValues = B2bPartyFieldValues(fieldValueRepository, recipientFieldValueRepository)

    private fun field(placement: B2bPlacement, id: Long? = 88L) = B2bProfileField().apply {
        this.placement = placement
        this.id = id
    }

    // ─── SELLER ──────────────────────────────────────────────────────────────

    @Test
    fun `SELLER-placed field reads the seller's SENDER_PROFILE row`() {
        val invoice = Invoice().apply { senderProfile = SenderProfile().apply { id = 1L } }
        whenever(fieldValueRepository.findByOwnerAndBtCode(B2bFieldOwnerType.SENDER_PROFILE, 1L, B2bTerm.BT_58)).thenReturn(
            B2bFieldValue().apply { value = "anbieter@example.com" },
        )

        val value = partyFieldValues.partyValue(invoice, field(B2bPlacement.SELLER), B2bTerm.BT_58)

        assertEquals("anbieter@example.com", value)
        verifyNoInteractions(recipientFieldValueRepository)
    }

    @Test
    fun `SELLER-placed field with no seller yet resolves to null without querying`() {
        val invoice = Invoice()

        val value = partyFieldValues.partyValue(invoice, field(B2bPlacement.SELLER), B2bTerm.BT_58)

        assertNull(value)
        verifyNoInteractions(fieldValueRepository)
    }

    // ─── RECIPIENT ───────────────────────────────────────────────────────────

    @Test
    fun `RECIPIENT-placed field reads the recipient's own recipient_b2b_field_value row`() {
        val invoice = Invoice().apply { recipient = Recipient().apply { id = 5L } }
        whenever(recipientFieldValueRepository.findByRecipientIdAndProfileFieldId(5L, 88L)).thenReturn(
            RecipientB2bFieldValue().apply { value = "kunde@example.com" },
        )

        val value = partyFieldValues.partyValue(invoice, field(B2bPlacement.RECIPIENT, id = 88L), B2bTerm.BT_58)

        assertEquals("kunde@example.com", value)
        verifyNoInteractions(fieldValueRepository)
    }

    @Test
    fun `RECIPIENT-placed field with no recipient yet resolves to null without querying`() {
        val invoice = Invoice()

        val value = partyFieldValues.partyValue(invoice, field(B2bPlacement.RECIPIENT, id = 88L), B2bTerm.BT_58)

        assertNull(value)
        verifyNoInteractions(recipientFieldValueRepository)
    }

    @Test
    fun `RECIPIENT-placed field with no field id yet resolves to null without querying`() {
        val invoice = Invoice().apply { recipient = Recipient().apply { id = 5L } }

        val value = partyFieldValues.partyValue(invoice, field(B2bPlacement.RECIPIENT, id = null), B2bTerm.BT_58)

        assertNull(value)
        verifyNoInteractions(recipientFieldValueRepository)
    }

    // ─── INVOICE ─────────────────────────────────────────────────────────────

    @Test
    fun `INVOICE-placed field has no party value at all`() {
        val invoice = Invoice().apply {
            senderProfile = SenderProfile().apply { id = 1L }
            recipient = Recipient().apply { id = 5L }
        }

        val value = partyFieldValues.partyValue(invoice, field(B2bPlacement.INVOICE), B2bTerm.BT_58)

        assertNull(value)
        verifyNoInteractions(fieldValueRepository)
        verifyNoInteractions(recipientFieldValueRepository)
    }
}
