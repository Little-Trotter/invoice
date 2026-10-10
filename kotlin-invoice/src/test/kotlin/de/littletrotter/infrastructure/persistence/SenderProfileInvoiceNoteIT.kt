package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.Language
import io.quarkus.test.TestTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * `sender_profile.invoice_note_group_id` end to end against a real Postgres (Testcontainers,
 * full Flyway chain via [PostgresTestResource]): the `@OneToOne(cascade = ALL, orphanRemoval =
 * true)` on [SenderProfile.invoiceNote] persists a new group with the profile, survives a reload,
 * and — the part a static check cannot see — cleans up correctly both when the note is cleared and
 * when the whole profile is deleted. [LanguageMigrationIT] already covers the shared
 * `localized_text_group`/`localized_text` schema itself; this file is specific to the
 * `sender_profile` side of the relation.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class SenderProfileInvoiceNoteIT {

    @Inject
    lateinit var em: EntityManager

    private fun rowCount(sql: String): Long = (em.createNativeQuery(sql).singleResult as Number).toLong()

    private fun germanLanguage(): Language = em.find(Language::class.java, 1)
    private fun englishLanguage(): Language = em.find(Language::class.java, 2)

    private fun persistedProfile(prefix: String): SenderProfile {
        val profile = SenderProfile().apply {
            name = "Testanbieter $prefix"
            invoicePrefix = prefix
        }
        em.persist(profile)
        em.flush()
        return profile
    }

    @Test
    @TestTransaction
    fun `a new invoice note group and its entries persist with the sender profile and survive a reload`() {
        val profile = persistedProfile("IN2-1")
        profile.setInvoiceNote(germanLanguage(), "Zahlbar ohne Abzug.")
        profile.setInvoiceNote(englishLanguage(), "Payable without deduction.")
        em.flush()
        val profileId = profile.id!!
        val groupId = profile.invoiceNote!!.id!!
        em.clear()

        val reloaded = em.find(SenderProfile::class.java, profileId)
        assertEquals(groupId, reloaded.invoiceNote?.id)
        assertEquals("Zahlbar ohne Abzug.", reloaded.invoiceNoteFor("de"))
        assertEquals("Payable without deduction.", reloaded.invoiceNoteFor("en"))
        assertEquals(2L, rowCount("SELECT COUNT(*) FROM localized_text WHERE group_id = $groupId"))
    }

    @Test
    @TestTransaction
    fun `a sender profile with no note at all keeps invoice_note_group_id null`() {
        val profile = persistedProfile("IN2-2")
        em.clear()

        val reloaded = em.find(SenderProfile::class.java, profile.id)
        assertNull(reloaded.invoiceNote)
        assertEquals(
            0L,
            rowCount("SELECT COUNT(*) FROM sender_profile WHERE id = ${profile.id} AND invoice_note_group_id IS NOT NULL"),
        )
    }

    @Test
    @TestTransaction
    fun `clearing the last entry deletes the group row itself - orphanRemoval, no empty group left behind`() {
        val profile = persistedProfile("IN2-3")
        profile.setInvoiceNote(germanLanguage(), "Einziger Hinweis")
        em.flush()
        val groupId = profile.invoiceNote!!.id!!

        profile.setInvoiceNote(germanLanguage(), "")
        em.flush()
        em.clear()

        assertEquals(0L, rowCount("SELECT COUNT(*) FROM localized_text_group WHERE id = $groupId"))
        val reloaded = em.find(SenderProfile::class.java, profile.id)
        assertNull(reloaded.invoiceNote)
    }

    @Test
    @TestTransaction
    fun `deleting the sender profile cascades the group and its entries - no orphan rows`() {
        val profile = persistedProfile("IN2-4")
        profile.setInvoiceNote(germanLanguage(), "Zahlbar ohne Abzug.")
        profile.setInvoiceNote(englishLanguage(), "Payable without deduction.")
        em.flush()
        val groupId = profile.invoiceNote!!.id!!
        val profileId = profile.id!!

        em.remove(profile)
        em.flush()

        assertEquals(0L, rowCount("SELECT COUNT(*) FROM sender_profile WHERE id = $profileId"))
        assertEquals(0L, rowCount("SELECT COUNT(*) FROM localized_text_group WHERE id = $groupId"))
        assertEquals(0L, rowCount("SELECT COUNT(*) FROM localized_text WHERE group_id = $groupId"))
    }
}
