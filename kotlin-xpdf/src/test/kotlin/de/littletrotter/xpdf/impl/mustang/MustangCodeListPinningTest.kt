package de.littletrotter.xpdf.impl.mustang

import de.littletrotter.shared.b2b.codes.B2bCodeList
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pinning tests for the Factur-X code-list literals hardcoded in the Mustang
 * mapper/builder (audit targets: `MustangExportModelMapper:190`'s "0088" ICD fallback,
 * `MustangTransactionBuilder`'s "IL"/"130"). Deliberately test-only: `kotlin-xpdf`'s validator
 * files are out of scope here, and these two mapper/
 * builder files are the one touchpoint this pinning covers — pinning them here
 * without renaming the literals into named constants avoids any risk of a concurrent edit
 * collision.
 */
class MustangCodeListPinningTest {

    /** `MustangExportModelMapper.toParty`: `globalIdSchemeId = ... ?: "0088"` — the deliberate GS1
     *  GLN fallback for a legacy bare `globalId` with no `PartyIdentifier` of its own. Pinned as a
     *  party identifier scheme (BT-29/46/71/60) — kotlin-invoice's `SchemeIdComplianceRules`
     *  now also enforces this same ICD list at the compliance gate. */
    @Test
    fun `the ICD '0088' GS1 GLN fallback in MustangExportModelMapper is a valid party identifier scheme`() {
        assertTrue(
            B2bCodeList.PARTY_ID_SCHEME_ICD.contains("0088"),
            "MustangExportModelMapper.toParty's bare-globalId ICD fallback \"0088\" (GS1 GLN) must stay a " +
                "member of B2bCodeList.PARTY_ID_SCHEME_ICD — a codedb update dropping it would silently " +
                "break the fallback",
        )
    }

    /** `MustangTransactionBuilder.build`: `objectIdentifierDoc...setReferenceTypeCode("IL")` — the
     *  only UNTDID 1153 reference qualifier this product currently emits for BT-18-1. */
    @Test
    fun `the reference qualifier 'IL' in MustangTransactionBuilder is a valid UNTDID 1153 code`() {
        assertTrue(
            B2bCodeList.REFERENCE_QUALIFIER_1153.contains("IL"),
            "MustangTransactionBuilder's objectIdentifierDoc.setReferenceTypeCode(\"IL\") must stay a " +
                "member of B2bCodeList.REFERENCE_QUALIFIER_1153",
        )
    }

    /** `MustangTransactionBuilder.build`'s KDoc: Mustang's own writer calls
     *  `setTypeCode("130")` on the object identifier document internally
     *  (`ZUGFeRD2PullProvider.java`) — not a literal our code assigns, but still an assumption
     *  this integration depends on staying true for BT-18 (header AdditionalReferencedDocument). */
    @Test
    fun `the document type '130' Mustang sets on the object identifier document is a valid header reference document type`() {
        assertTrue(
            B2bCodeList.INVOICE_DATA_SHEET_DOC_TYPE.contains("130"),
            "the header AdditionalReferencedDocument TypeCode \"130\" Mustang sets on the BT-18 object " +
                "identifier document must stay a member of B2bCodeList.INVOICE_DATA_SHEET_DOC_TYPE",
        )
    }
}
