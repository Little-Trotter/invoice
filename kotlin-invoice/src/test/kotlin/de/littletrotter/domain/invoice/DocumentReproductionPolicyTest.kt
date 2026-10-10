package de.littletrotter.domain.invoice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class DocumentReproductionPolicyTest {

    @ParameterizedTest(name = "{0} sent={1} rustfs={2} mail={3} -> {4}")
    @MethodSource("table")
    fun `every combination resolves deterministically`(
        state: OriginalState,
        sent: Boolean,
        rustfs: Boolean,
        mail: Boolean,
        expected: MissingOriginalResolution,
    ) {
        assertEquals(expected, DocumentReproductionPolicy.resolve(state, sent, rustfs, mail))
    }

    @ParameterizedTest
    @EnumSource(value = OriginalState::class, names = ["MISSING", "MISMATCH"])
    fun `rustfs wins over the mail journal`(state: OriginalState) {
        val r = DocumentReproductionPolicy.resolve(state, sent = true, rustfsRestorable = true, mailJournalRestorable = true)

        assertEquals(RestoreSource.RUSTFS, (r as MissingOriginalResolution.RestoreFrom).source)
    }

    @Test
    fun `present always serves whatever the other inputs say`() {
        for (sent in listOf(true, false)) {
            for (rustfs in listOf(true, false)) {
                for (mail in listOf(true, false)) {
                    assertEquals(
                        MissingOriginalResolution.ServeOriginal,
                        DocumentReproductionPolicy.resolve(OriginalState.PRESENT, sent, rustfs, mail),
                    )
                }
            }
        }
    }

    @Test
    fun `the loss reason is derived from the state`() {
        assertEquals(
            MissingOriginalResolution.Regenerate(DocumentLossReason.ORIGINAL_MISSING),
            DocumentReproductionPolicy.resolve(OriginalState.MISSING, false, false, false),
        )
        assertEquals(
            MissingOriginalResolution.Reproduce(DocumentLossReason.CHECKSUM_MISMATCH),
            DocumentReproductionPolicy.resolve(OriginalState.MISMATCH, true, false, false),
        )
    }

    companion object {
        @JvmStatic
        fun table(): Stream<Arguments> {
            val cases = mutableListOf<Arguments>()
            for (state in OriginalState.entries) {
                for (sent in listOf(false, true)) {
                    for (rustfs in listOf(false, true)) {
                        for (mail in listOf(false, true)) {
                            cases += Arguments.of(state, sent, rustfs, mail, expected(state, sent, rustfs, mail))
                        }
                    }
                }
            }
            return cases.stream()
        }

        // Written out independently of the policy so the table is a real oracle, not a mirror.
        private fun expected(
            state: OriginalState,
            sent: Boolean,
            rustfs: Boolean,
            mail: Boolean,
        ): MissingOriginalResolution {
            val reason = if (state == OriginalState.MISSING) {
                DocumentLossReason.ORIGINAL_MISSING
            } else {
                DocumentLossReason.CHECKSUM_MISMATCH
            }
            return when {
                state == OriginalState.PRESENT -> MissingOriginalResolution.ServeOriginal
                !sent -> MissingOriginalResolution.Regenerate(reason)
                rustfs -> MissingOriginalResolution.RestoreFrom(RestoreSource.RUSTFS, reason)
                mail -> MissingOriginalResolution.RestoreFrom(RestoreSource.MAIL_JOURNAL, reason)
                else -> MissingOriginalResolution.Reproduce(reason)
            }
        }
    }
}
