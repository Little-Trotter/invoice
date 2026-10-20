package de.littletrotter.e2e.settings

import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.invoice.InvoiceItemMutationService
import de.littletrotter.application.invoice.PerDiemEntryCommand
import de.littletrotter.application.invoice.TripCommand
import de.littletrotter.application.invoice.TripService
import de.littletrotter.application.settings.FeatureSettingsGate
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.exception.InvoiceValidationError
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.edit
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.PerDiemRate
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.settings.FeatureFlag
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.shared.Money
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/**
 * SFS E2E — the feature switches on real Postgres with real Flyway. This is the one suite that
 * pins the migrations themselves: V4 creates `feature_setting`, V5 seeds per-diem and e-mail TRUE (the EXTENDED-profile opt-in FALSE), and from
 * there the [FeatureSettingsGate] reads and writes through the production Panache repository.
 *
 * The unit-level behaviour matrix (fail-open, cache, the addItem guard in isolation) is
 * deliberately NOT re-asserted here per conventions §11 — this suite adds what only a real stack
 * can: seed presence, persistence across gate instances' caching, and the actual DB-backed update
 * path of an existing per-diem position while its feature is off (the data-integrity guarantee of
 * the Wirkmatrix).
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class FeatureSettingsE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var gate: FeatureSettingsGate

    @Inject
    lateinit var lifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var itemMutationService: InvoiceItemMutationService

    @Inject
    lateinit var tripService: TripService

    // The real Postgres-backed implementation — injected in order to build fresh gate instances
    // (no cache) that read through the same repository the application uses.
    @Inject
    lateinit var featureRepository: de.littletrotter.domain.repository.FeatureSettingRepository

    @AfterEach
    fun restoreFlags() {
        // The suite-state rule applies to JVM-embedded tests the same as to Playwright: leave what
        // you found. Both flags back to their seeded TRUE.
        QuarkusTransaction.requiringNew().run {
            gate.setEnabled(FeatureFlag.PER_DIEM_UI, true, "e2e-cleanup")
            gate.setEnabled(FeatureFlag.EMAIL_SEND, true, "e2e-cleanup")
        }
    }

    @Test
    fun `the seed enables per-diem, e-mail and receipt auto-reduce and leaves the EXTENDED profile opt-in off`() {
        val rows = QuarkusTransaction.requiringNew().call {
            em.createNativeQuery("SELECT key, enabled FROM feature_setting ORDER BY key").resultList
                .associate { (it as Array<*>)[0] as String to (it as Array<*>)[1] as Boolean }
        }
        assertEquals(
            mapOf(
                "feature.email.enabled" to true,
                "feature.extended-profile.enabled" to false,
                "feature.perdiem.enabled" to true,
                "feature.receipt-auto-reduce.enabled" to true,
            ),
            rows,
            "Pauschalen, E-Mail und das automatische Verkleinern grosser Belege sind gesedet und AN, das EXTENDED-Profil bleibt Opt-in und AUS",
        )
        assertTrue(gate.isEnabled(FeatureFlag.PER_DIEM_UI))
        assertTrue(gate.isEnabled(FeatureFlag.EMAIL_SEND))
        assertTrue(gate.isEnabled(FeatureFlag.RECEIPT_AUTO_REDUCE))
        assertFalse(gate.isEnabled(FeatureFlag.EXTENDED_PROFILE))
    }

    @Test
    fun `creating a per-diem position is rejected while the flag is off but service positions still save`() {
        val fixture = fixtureInvoiceAndRate()

        QuarkusTransaction.requiringNew().run { gate.setEnabled(FeatureFlag.PER_DIEM_UI, false, "e2e-test") }

        val failure = assertThrows(InvoiceValidationException::class.java) {
            QuarkusTransaction.requiringNew().run {
                tripService.addTrip(
                    fixture.invoiceId,
                    perDiemCommand("SFS-PerDiem-Tag", fixture.perDiemRateId),
                )
            }
        }
        assertEquals(InvoiceValidationError.PER_DIEM_FEATURE_DISABLED, failure.error)

        // The same invoice still accepts ordinary positions while per-diem is off — the guard is
        // per-category, not per-invoice.
        assertDoesNotThrow {
            QuarkusTransaction.requiringNew().run {
                itemMutationService.addItem(
                    fixture.invoiceId,
                    InvoiceItemCommand(
                        category = InvoiceItemCategory.SERVICE,
                        description = "SFS-Beratung",
                        quantity = BigDecimal.ONE,
                        unit = InvoiceItemUnit.HOUR,
                        unitPriceNet = Money.of("50.00"),
                        taxRateId = fixture.taxRateId,
                    ),
                )
            }
        }
        val positionsAfter = QuarkusTransaction.requiringNew().call {
            em.find(Invoice::class.java, fixture.invoiceId).itemList.size
        }
        assertEquals(1, positionsAfter, "die abgewiesene Pauschale wurde nicht halb geschrieben")
    }

    @Test
    fun `an existing per-diem position stays updatable while the flag is off`() {
        val fixture = fixtureInvoiceAndRate()
        // A per-diem-bearing trip inherits the invoice's main-service rate (TripCommand's
        // own KDoc) — so a SERVICE position must exist first, exactly like the watermark suite's
        // fixture builds it.
        QuarkusTransaction.requiringNew().run {
            itemMutationService.addItem(
                fixture.invoiceId,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.SERVICE,
                    description = "SFS-Hauptleistung",
                    quantity = BigDecimal.ONE,
                    unit = InvoiceItemUnit.HOUR,
                    unitPriceNet = Money.of("100.00"),
                    taxRateId = fixture.taxRateId,
                ),
            )
        }
        val positionId = QuarkusTransaction.requiringNew().call {
            tripService.addTrip(
                fixture.invoiceId,
                perDiemCommand("SFS-Bestaende-Pauschale", fixture.perDiemRateId),
            ).itemList.maxByOrNull { it.sortOrder }!!.positionId
        }

        QuarkusTransaction.requiringNew().run { gate.setEnabled(FeatureFlag.PER_DIEM_UI, false, "e2e-test") }

        assertDoesNotThrow {
            QuarkusTransaction.requiringNew().run {
                tripService.updateTrip(
                    fixture.invoiceId,
                    positionId,
                    perDiemCommand("SFS-Bestaende-Pauschale umtextiert", fixture.perDiemRateId),
                )
            }
        }
        val description = QuarkusTransaction.requiringNew().call {
            em.find(Invoice::class.java, fixture.invoiceId).itemList.first { it.positionId == positionId }.description
        }
        assertEquals("SFS-Bestaende-Pauschale umtextiert", description, "Bestandsdaten bleiben pflegbar")
    }

    @Test
    fun `gate write is visible on a freshly built gate and carries the actor stamp`() {
        QuarkusTransaction.requiringNew().run { gate.setEnabled(FeatureFlag.EMAIL_SEND, false, "stefan") }

        // A brand-new gate (fresh cache, same JVM, real repository) must see the flip on its
        // first read — the switch is persistent state, not session state.
        assertFalse(
            FeatureSettingsGate(featureRepository, de.littletrotter.shared.time.ClockTimeSource.fixedAt(java.time.Instant.parse("2026-06-15T10:00:00Z")))
                .isEnabled(FeatureFlag.EMAIL_SEND),
        )

        val changedBy = QuarkusTransaction.requiringNew().call {
            (em.createNativeQuery("SELECT updated_by FROM feature_setting WHERE key = 'feature.email.enabled'").singleResult) as String
        }
        assertNotNull(changedBy)
    }

    // ── fixtures ────────────────────────────────────────────────────────────────

    private data class Fixture(val invoiceId: Long, val perDiemRateId: Long, val taxRateId: Long)

    private fun fixtureInvoiceAndRate(): Fixture {
        val tag = "SFS${System.nanoTime() % 100_000}"
        val senderId = QuarkusTransaction.requiringNew().call {
            val sender = aSenderProfile {
                name = "$tag Sender GmbH"; invoicePrefix = "$tag-"; registerCourt = "AG Musterstadt"
                email = Email.ofNullable("billing@$tag.example"); taxNumber = TaxNumber("12/345/67890"); vatId = VatId("DE111111111")
                iban = Iban.ofNullable("DE89370400440532013000")
            }
            sender.addAddress(Address.forSender(sender, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true))
            em.persist(sender); em.flush(); sender.id!!
        }
        val recipientId = QuarkusTransaction.requiringNew().call {
            val recipient = aRecipient {
                customerNumber = CustomerNumber("$tag-100"); name = "$tag Empfänger AG"; email = Email.ofNullable("ap@$tag.example")
                vatId = VatId("DE222222222"); routingId = "PO-$tag"
            }
            recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Kundenallee 2", "20095", "Hamburg", Country.DE, true))
            em.persist(recipient); em.flush(); recipient.id!!
        }
        return QuarkusTransaction.requiringNew().call {
            val taxRate = TaxRate().edit {
                code = "${tag}_STD19"; category = "S"; name = "USt 19 %"; rate = TaxRatePercent(BigDecimal("19.00"))
                country = Country.DE; validFrom = LocalDate.of(2024, 1, 1); isSelectable = true; isActive = true
            }
            val rate = PerDiemRate().edit {
                country = Country.DE; fullDayRate = Money.of("28.00"); arrivalDepartureRate = Money.of("14.00")
                inlandRules = true; isActive = true; source = "SFS E2E fixture"; validFrom = LocalDate.of(2024, 1, 1)
            }
            em.persist(taxRate); em.persist(rate); em.flush()
            val invoice = lifecycleService.create(senderId, recipientId)
            Fixture(invoice.id!!, rate.id!!, taxRate.id!!)
        }
    }

    // A business trip goes through TripService.addTrip/updateTrip (per-diem
    // days only ever occur inside a trip); tax is never part of the command (a trip
    // always inherits the invoice's main-service rate, resolved server-side).
    private fun perDiemCommand(description: String, perDiemRateId: Long): TripCommand =
        TripCommand(
            title = description,
            perDiemDays = listOf(
                PerDiemEntryCommand(
                    date = LocalDate.of(2026, 3, 3),
                    isArrivalDeparture = false,
                    breakfast = false, lunch = false, dinner = false,
                    description = "Team-Reise Verpflegung", participant = "Kundentermin", employerContribution = Money.ZERO,
                    perDiemRateId = perDiemRateId,
                ),
            ),
        )
}
