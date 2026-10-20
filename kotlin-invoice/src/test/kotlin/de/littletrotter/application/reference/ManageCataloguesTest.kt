package de.littletrotter.application.reference

import de.littletrotter.application.dto.HotelRateDto
import de.littletrotter.application.dto.MileageRateDto
import de.littletrotter.application.dto.PaymentTermDto
import de.littletrotter.application.dto.PerDiemRateDto
import de.littletrotter.application.dto.TaxRateDto
import de.littletrotter.application.dto.ArticleDraft
import de.littletrotter.application.dto.ArticleRecipientRefDto
import de.littletrotter.application.dto.ArticleTaxVariantDto
import de.littletrotter.application.masterdata.ArticleManagementService
import de.littletrotter.application.masterdata.CustomerManagementService
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.masterdata.ArticleRecipientRef
import de.littletrotter.domain.masterdata.ArticleTaxVariant
import de.littletrotter.domain.repository.TaxRateRepository
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.fixture.aPaymentTerm
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.anArticle
import de.littletrotter.fixture.edit
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import de.littletrotter.application.reference.port.inbound.ManageArticleUseCase
import de.littletrotter.domain.masterdata.Article
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.PaymentTerm
import de.littletrotter.domain.reference.HotelRate
import de.littletrotter.domain.reference.Language
import de.littletrotter.domain.reference.MileageRate
import de.littletrotter.domain.reference.PerDiemRate
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.reference.VehicleType
import de.littletrotter.domain.repository.LanguageRepository
import de.littletrotter.shared.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import de.littletrotter.test.FIXED_TIME_SOURCE
import de.littletrotter.test.FIXED_TODAY

/** The catalogue write use cases hand every save/delete to their service. */
class ManageCataloguesTest {

    @Test
    fun `ManageTaxRate saves a new row and deletes through the service`() {
        val service = mock<TaxRateService>()
        whenever(service.saveTaxRate(any())).thenAnswer { (it.arguments[0] as TaxRate).edit { id = 4L } }
        val manage = ManageTaxRate(service)

        val saved = manage.saveTaxRate(
            TaxRateDto(
                name = "Regelsatz", rate = TaxRatePercent(BigDecimal("19")), code = "DE_STANDARD_19", category = "S",
                exemptionReasonCode = "X", exemptionReasonText = "Grund", legalBasis = "§ 12 UStG", isDefault = true,
            ),
        )

        assertEquals(4L, saved.id)
        assertEquals("DE_STANDARD_19", saved.code)
        assertEquals("Grund", saved.exemptionReasonText)
        assertEquals("§ 12 UStG", saved.legalBasis)
        assertTrue(saved.isDefault)
        manage.deleteTaxRate(4L)
        verify(service).deleteTaxRate(4L)
    }

    @Test
    fun `ManageTaxRate writes an edit onto the stored row`() {
        val service = mock<TaxRateService>()
        val stored = TaxRate().edit { id = 9L; version = 2L; name = "Alt" }
        whenever(service.findTaxRateById(9L)).thenReturn(stored)
        whenever(service.saveTaxRate(stored)).thenReturn(stored)

        val saved = ManageTaxRate(service).saveTaxRate(
            TaxRateDto(id = 9L, version = 2L, name = "Neu", country = Country.LU, isHotelRate = true, isSelectable = false, category = "S"),
        )

        assertEquals("Neu", stored.name)
        assertEquals(Country.LU, stored.country)
        assertTrue(stored.isHotelRate)
        assertEquals(9L, saved.id)
        assertEquals(false, saved.isSelectable)
    }

    @Test
    fun `ManageTaxRate rejects an edit of a row changed in the meantime`() {
        val service = mock<TaxRateService>()
        whenever(service.findTaxRateById(9L)).thenReturn(TaxRate().edit { id = 9L; version = 5L })

        assertThrows(jakarta.persistence.OptimisticLockException::class.java) {
            ManageTaxRate(service).saveTaxRate(TaxRateDto(id = 9L, version = 2L, name = "Neu", category = "S"))
        }
    }

    @Test
    fun `ManagePaymentTerm saves a new row, resolves per-language names by code, and deletes through the service`() {
        val service = mock<PaymentTermService>()
        val languageRepository = mock<LanguageRepository>()
        val german = Language().edit { id = 1; code = "de"; isFallback = true }
        whenever(languageRepository.findByCode("de")).thenReturn(german)
        whenever(service.savePaymentTerm(any())).thenAnswer { (it.arguments[0] as PaymentTerm).edit { id = 5L } }
        val manage = ManagePaymentTerm(service, languageRepository)

        val saved = manage.savePaymentTerm(PaymentTermDto(namesByLanguageCode = mapOf("de" to "Sofort"), dueInDays = 0))

        assertEquals(5L, saved.id)
        assertEquals("Sofort", saved.nameFor("de"))
        manage.deletePaymentTerm(5L)
        verify(service).deletePaymentTerm(5L)
    }

    @Test
    fun `ManagePaymentTerm ignores a language code the repository cannot resolve`() {
        val service = mock<PaymentTermService>()
        val languageRepository = mock<LanguageRepository>()
        whenever(languageRepository.findByCode("xx")).thenReturn(null)
        whenever(service.savePaymentTerm(any())).thenAnswer { it.arguments[0] }
        val manage = ManagePaymentTerm(service, languageRepository)

        val saved = manage.savePaymentTerm(PaymentTermDto(namesByLanguageCode = mapOf("xx" to "Unknown")))

        assertNull(saved.nameFor("xx"))
    }

    @Test
    fun `ManagePaymentTerm writes an edit onto the stored row`() {
        val service = mock<PaymentTermService>()
        val languageRepository = mock<LanguageRepository>()
        val stored = aPaymentTerm { id = 6L; version = 4L }
        whenever(service.findPaymentTermById(6L)).thenReturn(stored)
        whenever(service.savePaymentTerm(stored)).thenReturn(stored)

        val saved = ManagePaymentTerm(service, languageRepository).savePaymentTerm(PaymentTermDto(id = 6L, version = 4L, dueInDays = 21))

        assertEquals(21, stored.dueInDays)
        assertEquals(6L, saved.id)
    }

    private val germanRate = TaxRate().edit {
        id = 1L; country = Country.DE; code = "DE_S"; category = "S"; rate = TaxRatePercent(BigDecimal("19"))
    }
    private val luxRate = TaxRate().edit {
        id = 2L; country = Country.LU; code = "LU_S"; category = "S"; rate = TaxRatePercent(BigDecimal("17"))
    }
    private val nekar = aRecipient { id = 5L; name = "Nekar Supermarkt" }

    private fun manageArticle(service: ArticleManagementService): ManageArticleUseCase {
        val taxRates = mock<TaxRateRepository>()
        whenever(taxRates.findById(1L)).thenReturn(germanRate)
        whenever(taxRates.findById(2L)).thenReturn(luxRate)
        val customers = mock<CustomerManagementService>()
        whenever(customers.findById(5L)).thenReturn(nekar)
        return ManageArticle(service, taxRates, customers, time = FIXED_TIME_SOURCE)
    }

    @Test
    fun `ManageArticle saves a new article with its rows resolved by id and deletes through the service`() {
        val service = mock<ArticleManagementService>()
        whenever(service.save(any(), any())).thenAnswer { (it.arguments[0] as Article).edit { id = 9L } }
        val manage = manageArticle(service)
        val draft = ArticleDraft(
            name = "Beratung",
            defaultTaxRateId = 1L,
            articleNumber = "ART-9",
            taxVariants = listOf(ArticleTaxVariantDto(Country.LU, 2L)),
            recipientRefs = listOf(ArticleRecipientRefDto(5L, "243538", null)),
            proposedArticleNumber = "ART-9",
        )

        val saved = manage.saveArticle(draft)

        val sent = argumentCaptor<Article>().also { verify(service).save(it.capture(), org.mockito.kotlin.eq("ART-9")) }.firstValue
        assertSame(germanRate, sent.defaultTaxRateRef)
        assertEquals("ART-9", sent.articleNumber?.value)
        assertEquals(9L, saved.id)
        assertEquals(1L, saved.defaultTaxRateId)
        assertEquals(listOf(ArticleTaxVariantDto(Country.LU, 2L)), saved.taxVariants)
        assertEquals(listOf(ArticleRecipientRefDto(5L, "243538", null)), saved.recipientRefs)
        manage.deleteArticle(9L)
        verify(service).delete(9L)
    }

    @Test
    fun `WP2 - ManageArticle deleteArticle forwards the caller's expectedVersion to the service`() {
        val service = mock<ArticleManagementService>()
        val manage = manageArticle(service)

        manage.deleteArticle(9L, expectedVersion = 3L)

        verify(service).delete(9L, 3L)
    }

    @Test
    fun `ManageArticle edits keep the default flag and carry stored rows over when the draft has none`() {
        val service = mock<ArticleManagementService>()
        val stored = anArticle { id = 4L; version = 3L; name = "Alt"; isDefault = true }
        stored.replaceTaxVariants(listOf(ArticleTaxVariant.of(stored, Country.LU, luxRate, today = FIXED_TODAY)), today = FIXED_TODAY)
        stored.replaceRecipientRefs(listOf(ArticleRecipientRef.of(stored, nekar, null, "Nekar-Name")))
        whenever(service.findById(4L)).thenReturn(stored)
        whenever(service.save(any(), org.mockito.kotlin.isNull())).thenAnswer { it.arguments[0] as Article }
        val manage = manageArticle(service)

        val saved = manage.saveArticle(ArticleDraft(id = 4L, name = "Neu"))

        assertEquals(4L, saved.id)
        assertEquals(3L, saved.version, "without a revision in the draft the stored one is checked")
        assertTrue(saved.isDefault)
        assertEquals("Neu", saved.name)
        assertNull(saved.defaultTaxRateId)
        assertEquals(listOf(ArticleTaxVariantDto(Country.LU, 2L)), saved.taxVariants)
        assertEquals(listOf(ArticleRecipientRefDto(5L, null, "Nekar-Name")), saved.recipientRefs)
        assertEquals(1L, manage.saveArticle(ArticleDraft(id = 4L, version = 1L, name = "Neu")).version)
    }

    @Test
    fun `ManageArticle rejects a tax rate or recipient id that no longer resolves`() {
        val manage = manageArticle(mock())

        val rate = assertThrows(InvalidParameterException::class.java) {
            manage.saveArticle(ArticleDraft(name = "Beratung", defaultTaxRateId = 99L))
        }
        val recipient = assertThrows(InvalidParameterException::class.java) {
            manage.saveArticle(ArticleDraft(name = "Beratung", recipientRefs = listOf(ArticleRecipientRefDto(98L, "1", null))))
        }

        assertEquals("Unbekannter Steuersatz (id=99).", rate.message)
        assertEquals("Unbekannter Kunde (id=98).", recipient.message)
    }

    @Test
    fun `ManageHotelRate saves a new row and deletes through the service`() {
        val service = mock<HotelRateService>()
        whenever(service.saveHotelRate(any())).thenAnswer { (it.arguments[0] as HotelRate).edit { id = 1L } }
        val manage = ManageHotelRate(service)

        val saved = manage.saveHotelRate(HotelRateDto(country = Country.AT, ratePerNight = Money.of(BigDecimal("80")), source = "BMF"))

        assertEquals(1L, saved.id)
        assertEquals(Country.AT, saved.country)
        assertEquals("BMF", saved.source)
        manage.deleteHotelRate(1L)
        verify(service).deleteHotelRate(1L)
    }

    @Test
    fun `ManageHotelRate writes an edit onto the stored row`() {
        val service = mock<HotelRateService>()
        val stored = HotelRate().edit { id = 7L; version = 3L }
        whenever(service.findHotelRateById(7L)).thenReturn(stored)
        whenever(service.saveHotelRate(stored)).thenReturn(stored)

        val saved = ManageHotelRate(service).saveHotelRate(HotelRateDto(id = 7L, version = 3L, region = "Wien"))

        assertEquals("Wien", stored.region)
        assertEquals(7L, saved.id)
    }

    @Test
    fun `ManageMileageRate saves a new row and deletes through the service`() {
        val service = mock<MileageRateService>()
        whenever(service.saveMileageRate(any())).thenAnswer { (it.arguments[0] as MileageRate).edit { id = 2L } }
        val manage = ManageMileageRate(service)

        val saved = manage.saveMileageRate(MileageRateDto(country = Country.DE, vehicleType = VehicleType.CAR, ratePerKm = BigDecimal("0.30")))

        assertEquals(2L, saved.id)
        assertEquals(VehicleType.CAR, saved.vehicleType)
        manage.deleteMileageRate(2L)
        verify(service).deleteMileageRate(2L)
    }

    @Test
    fun `ManageMileageRate writes an edit onto the stored row`() {
        val service = mock<MileageRateService>()
        val stored = MileageRate().edit { id = 8L; version = 1L }
        whenever(service.findMileageRateById(8L)).thenReturn(stored)
        whenever(service.saveMileageRate(stored)).thenReturn(stored)

        ManageMileageRate(service).saveMileageRate(MileageRateDto(id = 8L, version = 1L, ratePerKm = BigDecimal("0.38")))

        assertEquals(BigDecimal("0.38"), stored.ratePerKm)
    }

    @Test
    fun `ManagePerDiemRate saves a new row and deletes through the service`() {
        val service = mock<PerDiemRateService>()
        whenever(service.savePerDiemRate(any())).thenAnswer { (it.arguments[0] as PerDiemRate).edit { id = 3L } }
        val manage = ManagePerDiemRate(service)

        val saved = manage.savePerDiemRate(PerDiemRateDto(country = Country.DE, fullDayRate = Money.of("28.00")))

        assertEquals(3L, saved.id)
        assertEquals(Country.DE, saved.country)
        manage.deletePerDiemRate(3L)
        verify(service).deletePerDiemRate(3L)
    }

    @Test
    fun `ManagePerDiemRate writes an edit onto the stored row`() {
        val service = mock<PerDiemRateService>()
        val stored = PerDiemRate().edit { id = 9L; version = 2L }
        whenever(service.findPerDiemRateById(9L)).thenReturn(stored)
        whenever(service.savePerDiemRate(stored)).thenReturn(stored)

        val saved = ManagePerDiemRate(service).savePerDiemRate(PerDiemRateDto(id = 9L, version = 2L, region = "Berlin"))

        assertEquals("Berlin", stored.region)
        assertEquals(9L, saved.id)
    }
}
