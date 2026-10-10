package de.littletrotter.application.masterdata

import de.littletrotter.application.reference.NumberRangeService
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.PaymentTerm
import de.littletrotter.domain.masterdata.SellerVatRegistrationCommand
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.Language
import de.littletrotter.domain.repository.ArticleRepository
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.repository.LanguageRepository
import de.littletrotter.domain.repository.PaymentTermRepository
import de.littletrotter.domain.repository.RecipientRepository
import de.littletrotter.domain.repository.SenderProfileRepository
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.test.FIXED_TIME_SOURCE
import de.littletrotter.test.FIXED_TODAY

/**
 * Tests for SellerProfileService with a Fake SenderProfileRepository and mocked number ranges
 * (no active range, the default case). Focus: complete field coverage on updates, default
 * profile exclusivity, and independent service-level validation.
 */
class SellerProfileServiceTest {

    private val repository = FakeSenderProfileRepository()
    private val addressManagementService = mock<AddressManagementService>()
    private val numberRanges = mock<NumberRangeService>().also {
        // No active range configured: the default case this service handles.
        whenever(it.hasActiveRangeFor(any())).thenReturn(false)
    }
    private val masterDataNumberService = MasterDataNumberService(
        numberRanges, mock<RecipientRepository>(), mock<ArticleRepository>(), mock<SenderProfileRepository>(),
        de.littletrotter.shared.time.ClockTimeSource.fixedAt(java.time.Instant.parse("2026-06-15T10:00:00Z")),
    )
    private val invoiceRepository = mock<InvoiceRepository>()
    private val paymentTermRepository = mock<PaymentTermRepository>()
    private val languageRepository = mock<LanguageRepository>().also {
        whenever(it.findByCode("de")).thenReturn(germanLanguage())
        whenever(it.findByCode("en")).thenReturn(englishLanguage())
    }
    private val service = SellerProfileService(
        senderProfileRepository = repository,
        addressManagementService = addressManagementService,
        masterDataNumberService = masterDataNumberService,
        invoiceRepository = invoiceRepository,
        defaultReferences = SellerDefaultReferences(paymentTermRepository),
        languageRepository = languageRepository, time = FIXED_TIME_SOURCE,
    )

    private fun validProfile(id: Long? = null) = SenderProfile().apply {
        this.id = id
        name = "ACME GmbH"
        invoicePrefix = id?.let { "ACME$it" } ?: "ACME"
        email = Email("billing@acme.example")
        taxNumber = TaxNumber("12/345/67890")
        vatId = VatId("DE123456789")
        iban = Iban("DE89370400440532013000")
    }

    // save() — new profile

    @Test
    fun `save vergibt einem neuen Profil ohne explizite Standardmarkierung den Standard wenn noch keiner existiert`() {
        val profile = validProfile()

        val saved = service.save(profile, defaults = SellerDefaultSelections())

        assertTrue(saved.isDefault, "first profile must auto-become default or none exists")
    }

    @Test
    fun `save laesst ein neues nicht-Standard-Profil unveraendert wenn bereits ein Standard existiert`() {
        repository.defaultProfile = validProfile(id = 1L).apply { isDefault = true }
        val profile = validProfile()

        val saved = service.save(profile, defaults = SellerDefaultSelections())

        assertFalse(saved.isDefault, "second profile must not displace existing default without explicit request")
    }

    @Test
    fun `save rejects a prefix already used by another profile before reaching the database`() {
        repository.all = listOf(validProfile(id = 1L).apply { invoicePrefix = "TAKEN" })
        val profile = validProfile().apply { invoicePrefix = "TAKEN" }

        org.junit.jupiter.api.assertThrows<de.littletrotter.domain.exception.DuplicateInvoicePrefixException> {
            service.save(profile, defaults = SellerDefaultSelections())
        }
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun `save rejects hostile prefixes with a defined validation error`() {
        listOf("\${'$'}{7*7}-", "../../etc/passwd", "Feld 1").forEach { hostile ->
            val profile = validProfile().apply { invoicePrefix = hostile }
            assertThrows(InvalidParameterException::class.java) { service.save(profile, defaults = SellerDefaultSelections()) }
        }
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun `save mit leerem invoicePrefix setzt den Standardwert RE-`() {
        val profile = validProfile().apply { invoicePrefix = "" }

        val saved = service.save(profile, defaults = SellerDefaultSelections())

        assertEquals("RE-", saved.invoicePrefix)
    }

    @Test
    fun `save laesst ein gesetztes invoicePrefix unangetastet`() {
        val profile = validProfile().apply { invoicePrefix = "XY-" }

        val saved = service.save(profile, defaults = SellerDefaultSelections())

        assertEquals("XY-", saved.invoicePrefix)
    }

    @Test
    fun `save lehnt ein fachlich ungueltiges neues Profil ab bevor es gespeichert wird`() {
        val profile = validProfile().apply { name = "" }

        assertThrows(InvalidParameterException::class.java) { service.save(profile, defaults = SellerDefaultSelections()) }
        assertTrue(repository.saved.isEmpty(), "invalid input must not reach database")
    }

    @Test
    fun `save markiert ein explizit als Standard uebergebenes neues Profil und entzieht ihn allen anderen`() {
        val existingDefault = validProfile(id = 1L).apply { isDefault = true }
        repository.all = listOf(existingDefault)
        repository.defaultProfile = existingDefault
        val newDefault = validProfile().apply { isDefault = true }

        val saved = service.save(newDefault, defaults = SellerDefaultSelections())

        assertTrue(saved.isDefault)
        assertFalse(existingDefault.isDefault, "old default must be explicitly revoked when setting new")
    }

    @Test
    fun `save entzieht beim Setzen eines neuen Standards nicht sich selbst wenn es das aktualisierte Profil ist`() {
        // Self-exclusion filter (it.id != toSave.id) must not strip default from the profile
        // being saved when it's already the current default.
        val existing = validProfile(id = 1L).apply { isDefault = true }
        repository.all = listOf(existing)
        repository.defaultProfile = existing

        val update = validProfile(id = 1L).apply { isDefault = true }
        service.save(update, defaults = SellerDefaultSelections())

        assertTrue(existing.isDefault, "saved profile must keep its default status")
    }

    @Test
    fun `save entzieht den alten Standard bevor das gespeicherte Profil selbst als Standard markiert ist`() {
        // The defaults query runs against a live Hibernate session, which auto-flushes every
        // dirty managed entity first: were the profile being saved already marked default at
        // that point, two rows with is_default = TRUE would reach the partial unique index at
        // once (the "duplicate key ... uq_sender_profile_single_default" the PO reported when
        // switching the default to a second profile). So the old default must be revoked while
        // the managed entity is still untouched.
        val oldDefault = validProfile(id = 1L).apply { isDefault = true }
        val managed = validProfile(id = 2L)
        repository.all = listOf(oldDefault, managed)
        repository.defaultProfile = oldDefault
        var managedDefaultAtQuery: Boolean? = null
        repository.onFindDefaults = { managedDefaultAtQuery = managed.isDefault }

        service.save(validProfile(id = 2L).apply { isDefault = true }, defaults = SellerDefaultSelections())

        assertEquals(false, managedDefaultAtQuery, "defaults query must run before the managed entity is marked default")
        assertFalse(oldDefault.isDefault, "old default must be revoked")
        assertTrue(managed.isDefault, "saved profile must end up as the default")
    }

    // save() — existing profile: complete field coverage

    @Test
    fun `save uebernimmt beim Aktualisieren eines bestehenden Profils alle vom Formular editierbaren Felder`() {
        val managed = validProfile(id = 1L)
        repository.all = listOf(managed)
        repository.defaultProfile = managed

        val edited = validProfile(id = 1L).apply {
            name = "Neuer Name GmbH"
            registerCourt = "Amtsgericht Musterstadt"
            phone = "+49 30 1234567"
            managingDirector = "Jane Doe"
            courtOfJurisdiction = "Musterstadt"
            commercialRegisterNumber = "HRB 12345"
        }

        val saved = service.save(edited, defaults = SellerDefaultSelections())

        assertEquals("Neuer Name GmbH", saved.name)
        assertEquals("Amtsgericht Musterstadt", saved.registerCourt)
        assertEquals("+49 30 1234567", saved.phone)
        assertEquals("Jane Doe", saved.managingDirector)
        assertEquals("Musterstadt", saved.courtOfJurisdiction)
        assertEquals("HRB 12345", saved.commercialRegisterNumber)
        // Returned instance must be the managed one for optimistic locking to work.
        assertTrue(saved === managed, "returned instance is the managed one from DB")
    }

    @Test
    fun `save verwendet die vom Aufrufer uebergebene Instanz als Fallback wenn die referenzierte id in der DB nicht mehr existiert`() {
        // When findById returns null for an existing id (e.g., concurrent deletion), fallback
        // to the caller's instance without treating it as new profile—no new seller number assigned.
        val orphaned = validProfile(id = 999L)

        val saved = service.save(orphaned, defaults = SellerDefaultSelections())

        assertEquals(999L, saved.id)
        assertTrue(saved === orphaned)
        verify(numberRanges, never()).hasActiveRangeFor(any())
    }

    @Test
    fun `save fragt fuer ein bestehendes Profil nicht nach einer Anbieternummer`() {
        // Verify hasActiveRangeFor() is not called—assignSellerNumber() skipped for existing profiles.
        val managed = validProfile(id = 1L)
        repository.all = listOf(managed)
        repository.defaultProfile = managed

        service.save(validProfile(id = 1L), defaults = SellerDefaultSelections())

        verify(numberRanges, never()).hasActiveRangeFor(any())
    }

    @Test
    fun `save fragt fuer ein neues Profil nach einer Anbieternummer`() {
        service.save(validProfile(), defaults = SellerDefaultSelections())

        verify(numberRanges).hasActiveRangeFor(any())
    }

    // delete()

    @Test
    fun `delete lehnt einen noch von einer Rechnung verwendeten Anbieter mit lesbarem Fehler ab`() {
        whenever(invoiceRepository.findBySenderProfileId(2L, 0, 1)).thenReturn(listOf(mock<Invoice>()))
        repository.all = listOf(validProfile(id = 2L))

        val ex = assertThrows(InvalidParameterException::class.java) { service.delete(2L) }

        assertTrue(ex.message?.contains("Rechnung") == true, ex.message)
        assertTrue(repository.all.any { it.id == 2L }, "rejected deletion must not remove profile")
    }

    @Test
    fun `delete loescht ein unbekanntes Profil ohne Fehler und ohne Standard-Nachfolge`() {
        assertDoesNotThrow { service.delete(42L) }
        assertNull(repository.newDefault)
    }

    @Test
    fun `delete eines Nicht-Standard-Profils loest keine Standard-Nachfolge aus`() {
        val other = validProfile(id = 2L)
        repository.all = listOf(other)

        service.delete(2L)

        assertNull(repository.newDefault, "only deleting default profile triggers succession")
    }

    @Test
    fun `delete des Standard-Profils befoerdert das naechste verbliebene Profil zum neuen Standard`() {
        val deletedDefault = validProfile(id = 1L).apply { isDefault = true }
        val nextInLine = validProfile(id = 2L)
        repository.all = listOf(deletedDefault, nextInLine)
        repository.defaultProfile = deletedDefault
        // After deletion, findDefault() returns null; next remaining profile promoted.
        repository.onDelete = { repository.defaultProfile = null }

        service.delete(1L)

        assertEquals(2L, repository.newDefault?.id)
        assertTrue(repository.newDefault?.isDefault == true)
    }

    @Test
    fun `delete des letzten verbleibenden Standard-Profils laesst den Bestand ohne Standard zurueck statt abzustuerzen`() {
        val onlyProfile = validProfile(id = 1L).apply { isDefault = true }
        repository.all = listOf(onlyProfile)
        repository.defaultProfile = onlyProfile
        repository.onDelete = { repository.defaultProfile = null; repository.all = emptyList() }

        assertDoesNotThrow { service.delete(1L) }
        assertNull(repository.newDefault, "no profiles remain to promote")
    }

    // save() — additional VAT registration removal guard

    @Test
    fun `save laesst das Entfernen einer zusaetzlichen USt-Registrierung zu wenn keine offene Rechnung mehr darauf verweist`() {
        val managed = validProfile(id = 1L)
        managed.updateVatRegistrations(listOf(SellerVatRegistrationCommand(Country.AT, VatId("ATU12345678"))), today = FIXED_TODAY)
        repository.all = listOf(managed)
        repository.defaultProfile = managed
        whenever(invoiceRepository.countOpenInvoicesForSellerVatCountry(1L, Country.AT)).thenReturn(0L)

        val edited = validProfile(id = 1L)
        // Submitted with no additional VAT registrations — the removal this guard checks.

        assertDoesNotThrow { service.save(edited, defaults = SellerDefaultSelections()) }
    }

    @Test
    fun `save lehnt das Entfernen einer zusaetzlichen USt-Registrierung ab solange eine offene Rechnung noch auf das Land verweist`() {
        val managed = validProfile(id = 1L)
        managed.updateVatRegistrations(listOf(SellerVatRegistrationCommand(Country.AT, VatId("ATU12345678"))), today = FIXED_TODAY)
        repository.all = listOf(managed)
        repository.defaultProfile = managed
        whenever(invoiceRepository.countOpenInvoicesForSellerVatCountry(1L, Country.AT)).thenReturn(2L)

        val edited = validProfile(id = 1L)

        val ex = assertThrows(InvalidParameterException::class.java) { service.save(edited, defaults = SellerDefaultSelections()) }
        assertTrue(ex.message?.contains("AT") == true, ex.message)
        assertTrue(ex.message?.contains("2") == true, ex.message)
    }

    @Test
    fun `save prueft eine unveraenderte zusaetzliche USt-Registrierung nicht auf offene Rechnungen`() {
        val managed = validProfile(id = 1L)
        managed.updateVatRegistrations(listOf(SellerVatRegistrationCommand(Country.AT, VatId("ATU12345678"))), today = FIXED_TODAY)
        repository.all = listOf(managed)
        repository.defaultProfile = managed

        val edited = validProfile(id = 1L)
        edited.updateVatRegistrations(listOf(SellerVatRegistrationCommand(Country.AT, VatId("ATU12345678"))), today = FIXED_TODAY)

        assertDoesNotThrow { service.save(edited, defaults = SellerDefaultSelections()) }
        verify(invoiceRepository, never()).countOpenInvoicesForSellerVatCountry(any(), any())
    }

    @Test
    fun `save prueft ein neu hinzugefuegtes Land nicht als Entfernung`() {
        val managed = validProfile(id = 1L)
        repository.all = listOf(managed)
        repository.defaultProfile = managed

        val edited = validProfile(id = 1L)
        edited.updateVatRegistrations(listOf(SellerVatRegistrationCommand(Country.AT, VatId("ATU12345678"))), today = FIXED_TODAY)

        assertDoesNotThrow { service.save(edited, defaults = SellerDefaultSelections()) }
        verify(invoiceRepository, never()).countOpenInvoicesForSellerVatCountry(any(), any())
    }

    // saveAddress() / setDefaultSeller() — id resolution

    @Test
    fun `saveAddress wirft wenn der Anbieter nicht existiert statt eine NullPointerException durchschlagen zu lassen`() {
        assertThrows(IllegalArgumentException::class.java) {
            service.saveAddress(999L, Address.AddressType.BILLING, "Weg 1", "12345", "Berlin", "DE")
        }
    }

    @Test
    fun `setDefaultSeller wirft wenn der Anbieter nicht existiert`() {
        assertThrows(IllegalArgumentException::class.java) {
            service.setDefaultSeller(999L)
        }
    }

    @Test
    fun `setDefaultSeller delegiert an AddressManagementService wenn der Anbieter existiert`() {
        val existing = validProfile(id = 1L)
        repository.all = listOf(existing)
        repository.defaultProfile = existing

        service.setDefaultSeller(1L)

        verify(addressManagementService).setDefaultSeller(existing)
    }

    // validate() — service-level field and format validation

    @Test
    fun `validate akzeptiert ein vollstaendig gueltiges Profil`() {
        assertDoesNotThrow { service.validate(validProfile()) }
    }

    @Test
    fun `validate lehnt einen leeren Namen ab`() {
        assertThrows(InvalidParameterException::class.java) { service.validate(validProfile().apply { name = "" }) }
    }

    @Test
    fun `validate lehnt eine fehlende E-Mail-Adresse ab`() {
        assertThrows(InvalidParameterException::class.java) { service.validate(validProfile().apply { email = null }) }
    }

    @Test
    fun `validate lehnt eine fehlende Steuernummer ab`() {
        assertThrows(InvalidParameterException::class.java) { service.validate(validProfile().apply { taxNumber = null }) }
    }

    @Test
    fun `validate lehnt eine fehlende USt-IdNr ab`() {
        assertThrows(InvalidParameterException::class.java) { service.validate(validProfile().apply { vatId = null }) }
    }

    @Test
    fun `validate lehnt eine ungueltige IBAN ab`() {
        assertThrows(InvalidParameterException::class.java) {
            service.validate(validProfile().apply { iban = Iban("DE00370400440532013000") })
        }
    }

    @Test
    fun `validate lehnt eine formal ungueltige E-Mail-Adresse ab`() {
        assertThrows(InvalidParameterException::class.java) {
            service.validate(validProfile().apply { email = Email("not-an-email") })
        }
    }

    @Test
    fun `eine deutsche Adresse mit vierstelliger PLZ wird schon bei der Konstruktion abgelehnt, nicht erst bei validate()`() {
        // PostalCode.forCountry (called from Address.forSender/forRecipient) runs
        // PostalCodeValidator eagerly now (fail-fast); service.validate() used to be the only
        // place a PLZ ever got checked for a profile's billing address. A format-invalid PLZ can
        // no longer reach an Address at all.
        val profile = validProfile()
        assertThrows(InvalidParameterException::class.java) {
            Address.forSender(profile, Address.AddressType.BILLING, "Weg 1", "1234", "Berlin", Country.DE)
        }
    }

    @Test
    fun `validate ueberspringt die deutsche PLZ-Pruefung wenn die Rechnungsadresse im Ausland liegt`() {
        val profile = validProfile()
        // "1234" would be invalid as a German postal code (not 5 digits) -- with a Swiss
        // address, the German format check does not apply at all.
        profile.addresses.add(Address.forSender(profile, Address.AddressType.BILLING, "Weg 1", "1234", "Zuerich", Country.CH))

        assertDoesNotThrow { service.validate(profile) }
    }

    @Test
    fun `eine ueberlange auslaendische PLZ wird schon bei der Konstruktion abgelehnt, nicht erst bei validate()`() {
        // The lenient non-DE length cap now runs eagerly in PostalCode.forCountry too;
        // see the DE-address boundary test above for why this moved off service.validate().
        val profile = validProfile()
        assertThrows(InvalidParameterException::class.java) {
            Address.forSender(profile, Address.AddressType.BILLING, "Weg 1", "L-16110000000", "Luxembourg", Country.LU)
        }
    }

    @Test
    fun `validate akzeptiert eine echte luxemburgische PLZ`() {
        val profile = validProfile()
        profile.addresses.add(
            Address.forSender(profile, Address.AddressType.BILLING, "Weg 1", "L-1611", "Luxembourg", Country.LU),
        )

        assertDoesNotThrow { service.validate(profile) }
    }

    @Test
    fun `eine formal ungueltige Steuernummer wird schon bei der Konstruktion abgelehnt, nicht erst bei validate()`() {
        // TaxNumber's own init runs GermanTaxNumberValidator eagerly now (fail-fast);
        // service.validate() used to be the only place this ran, deferred, and only logged a
        // warning instead of throwing. A format-invalid tax number can no longer reach a profile.
        assertThrows(InvalidParameterException::class.java) { TaxNumber("123") }
    }

    @Test
    fun `eine formal ungueltige USt-IdNr wird schon bei der Konstruktion abgelehnt, nicht erst bei validate()`() {
        // VatId's own init runs VatIdValidator eagerly now (fail-fast); service.validate()
        // used to be the only place this ran, deferred, and only logged a warning instead of
        // throwing. A format-invalid VAT id can no longer reach a profile.
        assertThrows(InvalidParameterException::class.java) { VatId("NOTAVALIDVATID") }
    }

    @Test
    fun `validate lehnt eine leere aber nicht-null Steuernummer ab`() {
        assertThrows(InvalidParameterException::class.java) { service.validate(validProfile().apply { taxNumber = TaxNumber("") }) }
    }

    @Test
    fun `validate lehnt eine leere aber nicht-null USt-IdNr ab`() {
        assertThrows(InvalidParameterException::class.java) { service.validate(validProfile().apply { vatId = VatId("") }) }
    }

    @Test
    fun `validate ueberspringt die IBAN-Pruefung wenn gar keine IBAN erfasst ist`() {
        assertDoesNotThrow { service.validate(validProfile().apply { iban = null }) }
    }

    // save(): OptimisticLockException guard on update (line 89/90) — both existing
    // save()-on-update tests submit an edited profile whose version stays at SenderProfile's
    // default (0L), matching the freshly-loaded managed instance's own default: neither disjunct
    // of `profile.version == null || managed.version != profile.version` was ever true.

    @Test
    fun `save lehnt die Aktualisierung ab wenn die uebergebene Version null ist`() {
        val managed = validProfile(id = 1L)
        repository.all = listOf(managed)
        repository.defaultProfile = managed

        val edited = validProfile(id = 1L).apply { version = null }

        val ex = assertThrows(InvalidParameterException::class.java) { service.save(edited, defaults = SellerDefaultSelections()) }
        assertTrue(ex.message?.contains("geändert") == true, ex.message)
    }

    @Test
    fun `save lehnt die Aktualisierung ab wenn die Version nicht mit der gespeicherten uebereinstimmt`() {
        val managed = validProfile(id = 1L).apply { version = 5L }
        repository.all = listOf(managed)
        repository.defaultProfile = managed

        val edited = validProfile(id = 1L).apply { version = 3L }

        assertThrows(InvalidParameterException::class.java) { service.save(edited, defaults = SellerDefaultSelections()) }
    }

    // getDefaultSeller() / findAllSorted() — thin pass-throughs, never called elsewhere in this suite.

    @Test
    fun `getDefaultSeller delegiert an AddressManagementService`() {
        val expected = validProfile(id = 7L)
        whenever(addressManagementService.defaultSeller).thenReturn(java.util.Optional.of(expected))

        assertEquals(java.util.Optional.of(expected), service.getDefaultSeller())
    }

    @Test
    fun `findAllSorted delegiert mit allen Parametern an das Repository`() {
        val expected = listOf(validProfile(id = 1L))
        repository.all = expected

        val result = service.findAllSorted(0, 10, "acme", de.littletrotter.domain.shared.GridSort("name", de.littletrotter.domain.shared.SortDirection.ASC))

        assertEquals(expected, result)
    }

    // assertNoVatRegistrationRemovalWhileOpenInvoicesReference — existing.id == null short-circuit
    // (unreachable through the public save() path once a profile round-trips through findById,
    // but the private method itself still declares the guard defensively).

    @Test
    fun `boundary - die USt-Registrierungs-Entfernungspruefung ueberspringt ein Profil ohne id`() {
        val method = SellerProfileService::class.java.getDeclaredMethod(
            "assertNoVatRegistrationRemovalWhileOpenInvoicesReference", SenderProfile::class.java, SenderProfile::class.java,
        )
        method.isAccessible = true
        val existingWithoutId = validProfile(id = null)
        val incoming = validProfile(id = null)

        method.invoke(service, existingWithoutId, incoming)

        verify(invoiceRepository, never()).countOpenInvoicesForSellerVatCountry(any(), any())
    }

    // ── save()'s invoiceNotesByLanguage parameter: create/update/delete the seller's
    //    invoice-note group inside the same save transaction, orphanRemoval does the deletion ──

    private fun germanLanguage() = Language().apply { id = 1; code = "de"; isFallback = true }
    private fun englishLanguage() = Language().apply { id = 2; code = "en"; isFallback = false }

    @Test
    fun `save schreibt eine neue Rechnungsnotiz je Sprache auf ein neues Profil`() {
        val profile = validProfile()

        val saved = service.save(
            profile,
            invoiceNotesByLanguage = mapOf("de" to "Zahlbar ohne Abzug.", "en" to "Payable without deduction."),
            defaults = SellerDefaultSelections(),
        )

        assertEquals("Zahlbar ohne Abzug.", saved.invoiceNoteFor("de"))
        assertEquals("Payable without deduction.", saved.invoiceNoteFor("en"))
    }

    @Test
    fun `save aktualisiert eine bestehende Rechnungsnotiz auf dem verwalteten Profil, nicht auf der uebergebenen Instanz`() {
        val managed = validProfile(id = 1L).apply { setInvoiceNote(germanLanguage(), "Alter Hinweis") }
        repository.all = listOf(managed)
        repository.defaultProfile = managed

        val saved = service.save(validProfile(id = 1L), invoiceNotesByLanguage = mapOf("de" to "Neuer Hinweis"), defaults = SellerDefaultSelections())

        assertEquals("Neuer Hinweis", saved.invoiceNoteFor("de"))
        assertTrue(saved === managed, "the note must land on the managed instance, like every other field")
    }

    @Test
    fun `save mit leerem Text loescht nur den Eintrag der betroffenen Sprache, die andere Sprache bleibt`() {
        val managed = validProfile(id = 1L).apply {
            setInvoiceNote(germanLanguage(), "Deutscher Hinweis")
            setInvoiceNote(englishLanguage(), "English note")
        }
        repository.all = listOf(managed)
        repository.defaultProfile = managed

        val saved = service.save(validProfile(id = 1L), invoiceNotesByLanguage = mapOf("en" to ""), defaults = SellerDefaultSelections())

        assertEquals("Deutscher Hinweis", saved.invoiceNoteFor("de"))
        assertNull(saved.invoiceNote?.entries?.firstOrNull { it.language?.code == "en" }, "the blanked English entry must be gone")
    }

    @Test
    fun `save loescht die ganze Notizgruppe wenn der letzte Eintrag geleert wird (keine leere Gruppe)`() {
        val managed = validProfile(id = 1L).apply { setInvoiceNote(germanLanguage(), "Einziger Hinweis") }
        repository.all = listOf(managed)
        repository.defaultProfile = managed

        val saved = service.save(validProfile(id = 1L), invoiceNotesByLanguage = mapOf("de" to ""), defaults = SellerDefaultSelections())

        assertNull(saved.invoiceNote, "no empty group must survive clearing the last entry")
    }

    @Test
    fun `save laesst eine nicht uebergebene Sprache unangetastet (z B durch das Feature-Flag ausgeblendetes Feld)`() {
        val managed = validProfile(id = 1L).apply {
            setInvoiceNote(germanLanguage(), "Deutscher Hinweis")
            setInvoiceNote(englishLanguage(), "English note")
        }
        repository.all = listOf(managed)
        repository.defaultProfile = managed

        // No invoiceNotesByLanguage entry for English at all — as if its field was hidden by the
        // multilingual-texts feature flag; the previously captured text must survive untouched.
        val saved = service.save(validProfile(id = 1L), invoiceNotesByLanguage = mapOf("de" to "Deutscher Hinweis"), defaults = SellerDefaultSelections())

        assertEquals("English note", saved.invoiceNoteFor("en"))
    }

    @Test
    fun `save lehnt eine Rechnungsnotiz ueber der Hoechstlaenge ab`() {
        val profile = validProfile()

        assertThrows(InvalidParameterException::class.java) {
            service.save(profile, invoiceNotesByLanguage = mapOf("de" to "x".repeat(201)), defaults = SellerDefaultSelections())
        }
    }

    @Test
    fun `save laesst einen Sprachcode unangetastet den languageRepository nicht aufloesen kann`() {
        val profile = validProfile()

        val saved = service.save(profile, invoiceNotesByLanguage = mapOf("xx" to "Unbekannte Sprache"), defaults = SellerDefaultSelections())

        assertNull(saved.invoiceNote, "an unresolvable language code must not create a note entry")
    }

    // ── save()'s default selections: the dialog only ever hands over ids, never the real
    //    PaymentTerm relation, so save() resolves and overwrites it itself ──

    @Test
    fun `save loest eine uebergebene defaultPaymentTermId gegen das Repository auf und setzt sie auf dem gespeicherten Profil`() {
        val term = PaymentTerm().apply { id = 7L }
        whenever(paymentTermRepository.findById(7L)).thenReturn(term)
        val profile = validProfile()

        val saved = service.save(profile, defaults = SellerDefaultSelections(paymentTermId = 7L))

        assertEquals(term, saved.defaultPaymentTerm)
    }

    @Test
    fun `save wirft wenn die uebergebene defaultPaymentTermId nicht mehr existiert`() {
        whenever(paymentTermRepository.findById(999L)).thenReturn(null)
        val profile = validProfile()

        val ex = assertThrows(InvalidParameterException::class.java) { service.save(profile, defaults = SellerDefaultSelections(paymentTermId = 999L)) }
        assertTrue(ex.message?.contains("999") == true, ex.message)
    }

    @Test
    fun `save loescht die bestehende defaultPaymentTerm eines Profils wenn keine id mehr uebergeben wird`() {
        val managed = validProfile(id = 1L).apply { defaultPaymentTerm = PaymentTerm().apply { id = 3L } }
        repository.all = listOf(managed)
        repository.defaultProfile = managed

        val saved = service.save(validProfile(id = 1L), defaults = SellerDefaultSelections())

        assertNull(saved.defaultPaymentTerm, "clearing the combo must clear the managed entity's relation too")
    }

    private class FakeSenderProfileRepository : SenderProfileRepository {
        var all: List<SenderProfile> = emptyList()
        var defaultProfile: SenderProfile? = null
        val saved = mutableListOf<SenderProfile>()
        var newDefault: SenderProfile? = null
        var onDelete: (() -> Unit)? = null
        var onFindDefaults: (() -> Unit)? = null

        override fun findById(id: Long): SenderProfile? = all.firstOrNull { it.id == id }
        override fun findByIdForEdit(id: Long): SenderProfile? = findById(id)
        override fun findDefault(): SenderProfile? = defaultProfile
        override fun findDefaults(): List<SenderProfile> {
            onFindDefaults?.invoke()
            return listOfNotNull(defaultProfile)
        }
        override fun findAll(pageIndex: Int, pageSize: Int, filterText: String?): List<SenderProfile> = all
        override fun count(filterText: String?): Long = all.size.toLong()
        override fun save(profile: SenderProfile): SenderProfile {
            saved.add(profile)
            if (profile.isDefault) newDefault = profile
            return profile
        }
        override fun forceVersionIncrement(profile: SenderProfile) {}
        override fun delete(id: Long) {
            all = all.filter { it.id != id }
            onDelete?.invoke()
        }
    }
}
