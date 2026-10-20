package de.littletrotter.infrastructure.persistence

import de.littletrotter.fixture.seededStandardTaxRate
import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.invoice.B2bProfileRuleSet
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.edit
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.B2bProfile
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.shared.b2b.B2bTermBinding
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceException
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies the B2B catalog/profile schema plus its seed
 * data end to end against a real Postgres (Testcontainers, full Flyway chain via
 * [PostgresTestResource]): the catalog's FREE terms do not overlap the already-exported business
 * terms, every BOUND term names an existing property, the table-level uniqueness/CHECK
 * constraints reject the mis-shaped rows the domain layer is meant to make impossible, and
 * `recipient.b2b_profile_id` / `invoice.b2b_profile_id` are nullable FKs onto `b2b_profile`
 * (`sender_profile.b2b_profile_id` does not exist). Also pins
 * BT-11's `projectNumber` resolution and the field catalog, both carried by the shipped profile
 * "Neckar".
 *
 * The profile "B2B-Standard" does not exist. "Neckar" is the only profile this seed ships.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class B2bProfileMigrationIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var b2bProfileRuleSet: B2bProfileRuleSet

    @Inject
    lateinit var b2bProfileLifecycleService: de.littletrotter.application.reference.B2bProfileLifecycleService

    @Inject
    lateinit var b2bProfileFieldDefinitionService: de.littletrotter.application.reference.B2bProfileFieldDefinitionService

    /**
     * Business terms already carried by an existing field/column — a FREE catalog term must
     * never appear here, or two sources would exist for the same term.
     */
    private val alreadyExportedBusinessTerms = setOf(
        "BT-3", "BT-9", "BT-10", "BT-15", "BT-17", "BT-20", "BT-22", "BT-29", "BT-30", "BT-31",
        "BT-32", "BT-33", "BT-34", "BT-40", "BT-46", "BT-47", "BT-48", "BT-49", "BT-55",
        "BT-115", "BT-120", "BT-121", "BT-125",
        // Also exported from their own columns: BT-25/BT-26 (credit note reference), BT-72
        // (delivery date), BT-129/BT-130 (line quantity and unit), BT-153/BT-154 (line name and
        // description). They were never in the catalog; this entry keeps the guard current
        // instead of only catching it once someone accidentally re-adds them as FREE.
        "BT-25", "BT-26", "BT-72", "BT-129", "BT-130", "BT-153", "BT-154",
    )

    private fun rowCount(sql: String): Long = (em.createNativeQuery(sql).singleResult as Number).toLong()

    // The catalog moved off `b2b_field_catalog` (dropped, V1__baseline_schema.sql) into
    // B2bTerm itself; these two helpers read the in-code catalog instead of a DB row scan. The
    // tests below stay meaningful: they pin *this task's* business-knowledge invariants (no FREE
    // term duplicates an already-exported column, every BOUND term names a real property), which
    // are distinct from B2bTermTest's structural ones (FREE has an xpath, BOUND has a
    // boundProperty).
    private fun freeBtCodes(): List<String> =
        B2bTerm.entries.filter { it.binding == B2bTermBinding.FREE }.map { it.officialCode }

    private fun boundProperties(): Map<String, String?> =
        B2bTerm.entries.filter { it.binding == B2bTermBinding.BOUND }.associate { it.officialCode to it.boundProperty }

    @Test
    @Transactional
    fun `no FREE catalog term overlaps an already-exported business term`() {
        val overlap = freeBtCodes().intersect(alreadyExportedBusinessTerms)
        assertTrue(overlap.isEmpty(), "FREE terms must not duplicate an already-exported term, found: $overlap")
    }

    @Test
    @Transactional
    fun `every BOUND catalog term names a non-blank bound property`() {
        val bound = boundProperties()
        assertTrue(bound.isNotEmpty(), "expected at least one seeded BOUND term")
        bound.forEach { (btCode, property) ->
            assertTrue(!property.isNullOrBlank(), "BOUND term $btCode must name a bound_property")
        }
    }

    @Test
    @Transactional
    fun `seeded BOUND terms reference the expected existing properties`() {
        val bound = boundProperties()
        // BT-13 is BOUND to its own column `purchaseOrderReference`, distinct from BT-10
        // (`buyerReference`). This value is not cosmetic: `B2bProfileRuleSet` reads it via a
        // JavaBean getter, so a wrong name silently makes the term act "never set".
        assertEquals("purchaseOrderReference", bound["BT-13"])
        assertEquals("servicePeriodFrom", bound["BT-73"])
        assertEquals("servicePeriodTo", bound["BT-74"])
        assertEquals("iban", bound["BT-84"])
        // BT-134/BT-135 are BOUND to their own line service-period columns; BT-72 is BOUND to
        // `deliveryDate`.
        assertEquals("deliveryDate", bound["BT-72"])
        assertEquals("servicePeriodFrom", bound["BT-134"])
        assertEquals("servicePeriodTo", bound["BT-135"])
    }

    @Test
    fun `catalog never contains a tax-determining term - T3 point 5`() {
        // BT-151/BT-152 (Invoiced item VAT category/rate) would let a B2B
        // profile influence the tax outcome, which stays exclusively bound to Article.defaultTaxRateRef
        // / TaxRuleResolver. BT-151 exists only as a reference term (binding = null, never
        // catalog-eligible); BT-152 is not a B2bTerm constant at all. Guards the catalog — now
        // B2bTerm.entries — against either ever becoming catalog-eligible.
        assertFalse(B2bTerm.BT_151.catalogEligible, "BT-151 must never become catalog-eligible")
        assertNull(B2bTerm.fromOfficialCodeOrNull("BT-152"), "BT-152 must not exist as a B2bTerm")
    }

    // ── "B2B-Standard" was removed without replacement ───────────────────────

    @Test
    @Transactional
    fun `the B2B-Standard profile no longer exists - only Neckar ships`() {
        assertEquals(
            0L,
            rowCount("SELECT COUNT(*) FROM b2b_profile WHERE name = 'B2B-Standard'"),
            "ARC-12 removed the shipped 'B2B-Standard' profile without replacement",
        )
    }

    // ── PROFIL-1 — BT-155/BT-156 under the *shipped* profile (CONDITIONAL BT-13) ─────────────

    /**
     * The shipped profile "Neckar" makes BT-155/BT-156 `CONDITIONAL BT-13`.
     *
     * The **real** rule set runs here against the **real, seeded** profile, twice, over the same
     * invoice — once with a purchase order reference set, once without. `B2bProfileRuleSetTest`
     * checks the same claim quickly with fakes; without this case it would only run against a
     * self-built catalog, not the one actually shipped.
     */
    private fun invoiceOnNeckarProfile(purchaseOrderReference: String?): Invoice =
        Invoice().edit {
            id = 4711L
            senderProfile = aSenderProfile {
                id = 1L
                name = "IT-Anbieter"
                invoicePrefix = "IT-"
            }
            // The rule set resolves the invoice's own b2bProfile, not the seller's.
            b2bProfile = em.find(B2bProfile::class.java, neckarProfileId())
            references.buyerReference = "04011000-1234512345-06"
            this.references.purchaseOrderReference = purchaseOrderReference
            addItem(InvoiceItem().edit { taxRateRef = em.seededStandardTaxRate(); positionId = "1"; description = "Beratung"; id = 8815L })
        }

    private fun mandatoryLineTerms(purchaseOrderReference: String?): List<String> =
        b2bProfileRuleSet.evaluate(invoiceOnNeckarProfile(purchaseOrderReference))
            .flatMap { it.businessTerms }
            .filter { it == B2bTerm.BT_155 || it == B2bTerm.BT_156 }
            .map { it.officialCode }
            .sorted()

    @Test
    @Transactional
    fun `PROFIL-1 - the shipped Neckar profile demands BT-155 and BT-156 once a purchase order reference is set`() {
        assertEquals(listOf("BT-155", "BT-156"), mandatoryLineTerms("BST-2026-0042"))
    }

    @Test
    @Transactional
    fun `PROFIL-1 - and does not demand them without any purchase order reference (BT-13-conditional)`() {
        assertEquals(
            emptyList<String>(),
            mandatoryLineTerms(null),
            "das ausgelieferte Profil 'Neckar' knüpft die Pflicht weiterhin an BT-13",
        )
    }

    @Test
    @Transactional
    fun `a term can be used at most once per profile`() {
        em.createNativeQuery("INSERT INTO b2b_profile (name, active, version) VALUES ('IT-Duplicate-Test', TRUE, 0)").executeUpdate()
        val profileId = (em.createNativeQuery("SELECT id FROM b2b_profile WHERE name = 'IT-Duplicate-Test'").singleResult as Number).toLong()
        em.createNativeQuery(
            """INSERT INTO b2b_profile_field (profile_id, bt_code, business_definition, requirement, placement, sort_order, version)
               VALUES ($profileId, ${B2bTerm.BT_19.code}, 'Erste', 0, 0, 0, 0)"""
        ).executeUpdate()

        assertThrows(PersistenceException::class.java) {
            em.createNativeQuery(
                """INSERT INTO b2b_profile_field (profile_id, bt_code, business_definition, requirement, placement, sort_order, version)
                   VALUES ($profileId, ${B2bTerm.BT_19.code}, 'Zweite', 0, 0, 1, 0)"""
            ).executeUpdate()
            em.flush()
        }
    }

    @Test
    @Transactional
    fun `a conditional field without a condition term is accepted at the DB, rejected only by the domain layer`() {
        // condition_bt_code carries only a range CHECK, deliberately no coupling CHECK to
        // requirement (its own `COMMENT ON COLUMN` in V1__baseline_schema.sql) — this row is
        // accepted at the DB; B2bProfileField.validate() is what actually rejects it
        // (B2bProfileTest's "conditional field without a condition term is rejected").
        em.createNativeQuery("INSERT INTO b2b_profile (name, active, version) VALUES ('IT-Conditional-Test', TRUE, 0)").executeUpdate()
        val profileId = (em.createNativeQuery("SELECT id FROM b2b_profile WHERE name = 'IT-Conditional-Test'").singleResult as Number).toLong()

        // requirement = 2 (CONDITIONAL), placement = 0 (INVOICE) — b2b_profile_field.requirement
        // and .placement are ordinal SMALLINT columns (V1__baseline_schema.sql), like bt_code.
        assertDoesNotThrow {
            em.createNativeQuery(
                """INSERT INTO b2b_profile_field (profile_id, bt_code, business_definition, requirement, condition_bt_code, placement, sort_order, version)
                   VALUES ($profileId, ${B2bTerm.BT_19.code}, 'Ohne Bedingung', 2, NULL, 0, 0, 0)"""
            ).executeUpdate()
            em.flush()
        }
    }

    // ── bt_code range CHECKs: 0 <= code <= greatest B2bTerm.code ──────────
    // Membership stays enum-enforced (B2bTermConverter/domain layer); the DB only rejects values
    // outside 0..max. B2bTermSchemaRangeTest pins the SQL literal to B2bTerm.entries.maxOf —
    // here the constraints are proven to actually fire against real Postgres.

    private val maxBtCode = B2bTerm.entries.maxOf { it.code }

    private fun freshProfileId(name: String): Long {
        em.createNativeQuery("INSERT INTO b2b_profile (name, active, version) VALUES ('$name', TRUE, 0)").executeUpdate()
        return (em.createNativeQuery("SELECT id FROM b2b_profile WHERE name = '$name'").singleResult as Number).toLong()
    }

    private fun insertProfileField(profileId: Long, btCode: Int, conditionBtCode: Int? = null) {
        val requirement = if (conditionBtCode == null) 0 else 2
        em.createNativeQuery(
            """INSERT INTO b2b_profile_field (profile_id, bt_code, business_definition, requirement, condition_bt_code, placement, sort_order, version)
               VALUES ($profileId, $btCode, 'Range-Test', $requirement, ${conditionBtCode ?: "NULL"}, 0, 0, 0)"""
        ).executeUpdate()
        em.flush()
    }

    @Test
    @Transactional
    fun `b2b_profile_field rejects a negative bt_code at the DB`() {
        val profileId = freshProfileId("IT-Range-Negative")
        assertThrows(PersistenceException::class.java) { insertProfileField(profileId, -1) }
    }

    @Test
    @Transactional
    fun `b2b_profile_field rejects a bt_code above the greatest B2bTerm code`() {
        val profileId = freshProfileId("IT-Range-Above-Max")
        assertThrows(PersistenceException::class.java) { insertProfileField(profileId, maxBtCode + 1) }
    }

    @Test
    @Transactional
    fun `b2b_profile_field accepts the greatest B2bTerm code itself`() {
        val profileId = freshProfileId("IT-Range-Boundary")
        assertDoesNotThrow { insertProfileField(profileId, maxBtCode) }
    }

    @Test
    @Transactional
    fun `condition_bt_code is range-guarded the same way`() {
        val profileId = freshProfileId("IT-Range-Condition")
        assertThrows(PersistenceException::class.java) {
            insertProfileField(profileId, B2bTerm.BT_19.code, conditionBtCode = maxBtCode + 1)
        }
    }

    @Test
    @Transactional
    fun `b2b_field_value rejects bt_codes outside the range`() {
        assertThrows(PersistenceException::class.java) {
            em.createNativeQuery(
                "INSERT INTO b2b_field_value (owner_type, owner_id, bt_code, value, version) VALUES (1, 4711, -1, 'x', 0)"
            ).executeUpdate()
            em.flush()
        }
    }

    @Test
    @Transactional
    fun `profile names are unique`() {
        em.createNativeQuery("INSERT INTO b2b_profile (name, active, version) VALUES ('IT-Unique-Name-Test', TRUE, 0)").executeUpdate()

        assertThrows(PersistenceException::class.java) {
            em.createNativeQuery("INSERT INTO b2b_profile (name, active, version) VALUES ('IT-Unique-Name-Test', TRUE, 0)").executeUpdate()
            em.flush()
        }
    }

    // ── recipient_b2b_field_value — DB mechanics (field just needs a real seeded FK target) ────
    //
    // This test checks only the DB unique constraint on (recipient_id, b2b_profile_field_id) and
    // needs any real seeded field for that — BT-11 (Neckar profile) stands in for it here.

    @Test
    @Transactional
    fun `recipient_b2b_field_value enforces one row per (recipient, profile field) pair`() {
        val recipientId = (
            em.createNativeQuery(
                "INSERT INTO recipient (customer_number, name, version, document_language) VALUES ('IT-RECIPIENT-B2B-1', 'ARC-23 IT Recipient', 0, 'de') RETURNING id"
            ).singleResult as Number
        ).toLong()
        val profileFieldId = (
            em.createNativeQuery(
                "SELECT id FROM b2b_profile_field WHERE profile_id = ${neckarProfileId()} AND bt_code = ${B2bTerm.BT_11.code}"
            ).singleResult as Number
        ).toLong()

        em.createNativeQuery(
            "INSERT INTO recipient_b2b_field_value (recipient_id, b2b_profile_field_id, value, version) " +
                "VALUES ($recipientId, $profileFieldId, 'kunde@example.com', 0)"
        ).executeUpdate()
        em.flush()

        assertThrows(PersistenceException::class.java) {
            em.createNativeQuery(
                "INSERT INTO recipient_b2b_field_value (recipient_id, b2b_profile_field_id, value, version) " +
                    "VALUES ($recipientId, $profileFieldId, 'andere@example.com', 0)"
            ).executeUpdate()
            em.flush()
        }
    }

    // ── Profile "Neckar" — the only shipped profile ───────────────────────────
    // A generic full-field profile (15 fields) for buyers that require project references
    // (BT-11), cost centers (BT-19) and customer-specific article numbers (BT-155/BT-156) on
    // invoices.

    private fun neckarProfileId(): Long =
        (em.createNativeQuery("SELECT id FROM b2b_profile WHERE name = 'Neckar'").singleResult as Number).toLong()

    private fun neckarFields(): Map<String, Triple<String, String?, String>> =
        em.createNativeQuery(
            "SELECT bt_code, requirement, condition_bt_code, placement FROM b2b_profile_field WHERE profile_id = ${neckarProfileId()}"
        ).resultList.associate {
            val row = it as Array<*>
            val btCode = B2bTerm.fromCode((row[0] as Number).toInt()).officialCode
            val requirement = when ((row[1] as Number).toInt()) {
                0 -> "OPTIONAL"; 1 -> "MANDATORY"; 2 -> "CONDITIONAL"; 3 -> "CONDITIONAL_IF_EMPTY"; else -> "UNKNOWN"
            }
            val conditionBtCode = (row[2] as Number?)?.let { code -> B2bTerm.fromCode(code.toInt()).officialCode }
            val placement = when ((row[3] as Number).toInt()) {
                0 -> "INVOICE"; 1 -> "RECIPIENT"; 2 -> "SELLER"; 3 -> "BOTH"; else -> "UNKNOWN"
            }
            btCode to Triple(requirement, conditionBtCode, placement)
        }

    @Test
    @Transactional
    fun `the Neckar profile carries the full former Nekar field division`() {
        val fields = neckarFields()
        assertEquals(15, fields.size, "Neckar should carry the full 15-field former Nekar set")
        assertEquals("MANDATORY" to (null to "INVOICE"), fields["BT-19"]?.let { it.first to (it.second to it.third) })
        assertEquals("MANDATORY" to (null to "SELLER"), fields["BT-84"]?.let { it.first to (it.second to it.third) })
        assertEquals("MANDATORY" to (null to "RECIPIENT"), fields["BT-58"]?.let { it.first to (it.second to it.third) })
        // The either-or pair and the BT-13-conditional line terms survive unchanged.
        assertEquals("CONDITIONAL_IF_EMPTY" to "BT-134", fields["BT-72"]?.let { it.first to it.second })
        assertEquals("CONDITIONAL_IF_EMPTY" to "BT-72", fields["BT-134"]?.let { it.first to it.second })
        assertEquals("CONDITIONAL_IF_EMPTY" to "BT-72", fields["BT-135"]?.let { it.first to it.second })
        assertEquals("CONDITIONAL" to "BT-13", fields["BT-155"]?.let { it.first to it.second })
        assertEquals("CONDITIONAL" to "BT-13", fields["BT-156"]?.let { it.first to it.second })
        listOf("BT-73", "BT-74", "BT-56", "BT-13", "BT-12", "BT-18", "BT-11").forEach {
            assertEquals("OPTIONAL", fields[it]?.first, "Neckar profile: $it should be OPTIONAL")
        }
    }

    @Test
    @Transactional
    fun `the Neckar profile's BT-58 has no fixed default, RECIPIENT-placed`() {
        // The field stays RECIPIENT-placed (per-customer maintainable) but carries no
        // fixed default_value.
        val default = em.createNativeQuery(
            "SELECT default_value FROM b2b_profile_field WHERE profile_id = ${neckarProfileId()} AND bt_code = ${B2bTerm.BT_58.code}"
        ).singleResult as String?
        assertNull(default)
    }

    @Test
    @Transactional
    fun `BT-11 is in the catalog and appears in the Neckar profile - NEKAR-T2 resolved the clarification point`() {
        // BT-11's catalog membership and its seeded-profile field row both hold — the catalog
        // itself moved into B2bTerm, so there is no DB row left to query for the first half.
        assertTrue(B2bTerm.BT_11.catalogEligible, "BT-11 should be catalog-eligible")
        assertTrue(neckarFields().containsKey("BT-11"), "the seed should carry BT-11 in the Neckar profile")
    }

    // ── b2b_profile_field.default_value ───────────────────────────────────────────────────────

    @Test
    @Transactional
    fun `b2b_profile_field carries a default_value column`() {
        assertEquals(
            1L,
            rowCount(
                "SELECT COUNT(*) FROM information_schema.columns " +
                    "WHERE table_name = 'b2b_profile_field' AND column_name = 'default_value'"
            ),
        )
    }

    // BT-58's own non-null-default mirror test lives above as
    // `the Neckar profile's BT-58 has no fixed default, RECIPIENT-placed` — no field in the
    // shipped profile carries a non-null default_value today; `b2b_profile_field carries a
    // default_value column` above still pins the column's existence at the schema level.

    // ── recipient.b2b_profile_id / invoice.b2b_profile_id (V1__baseline_schema.sql) ──────────
    // The assignment moved off the seller entirely — sender_profile carries no b2b_profile_id
    // column anymore (proven below), while recipient (standing default) and invoice (frozen
    // choice) each carry their own nullable FK onto b2b_profile.

    @Test
    @Transactional
    fun `sender_profile no longer carries a b2b_profile_id column`() {
        assertEquals(
            0L,
            rowCount(
                "SELECT COUNT(*) FROM information_schema.columns " +
                    "WHERE table_name = 'sender_profile' AND column_name = 'b2b_profile_id'"
            ),
            "decision 12 — SenderProfile.b2bProfile was removed without replacement, not moved to a shim",
        )
    }

    @Test
    @Transactional
    fun `recipient b2b_profile_id column is nullable and references b2b_profile`() {
        assertEquals(
            0L,
            rowCount(
                "SELECT COUNT(*) FROM information_schema.columns " +
                    "WHERE table_name = 'recipient' AND column_name = 'b2b_profile_id' AND is_nullable = 'NO'"
            ),
        )
        assertThrows(PersistenceException::class.java) {
            em.createNativeQuery(
                "INSERT INTO recipient (customer_number, name, b2b_profile_id, version, document_language) " +
                    "VALUES ('IT-FK-TEST-R', 'FK Test', 999999, 0, 'de')"
            ).executeUpdate()
        }
    }

    @Test
    @Transactional
    fun `invoice b2b_profile_id column is nullable and references b2b_profile`() {
        assertEquals(
            0L,
            rowCount(
                "SELECT COUNT(*) FROM information_schema.columns " +
                    "WHERE table_name = 'invoice' AND column_name = 'b2b_profile_id' AND is_nullable = 'NO'"
            ),
        )
        assertThrows(PersistenceException::class.java) {
            em.createNativeQuery(
                "INSERT INTO invoice (invoice_number, invoice_date, b2b_profile_id, version) " +
                    "VALUES ('', '2026-06-15', 999999, 0)"
            ).executeUpdate()
        }
    }

    // ── B2bProfile.countries round-trip ───────────────────────────────────────

    @Test
    @Transactional
    fun `a profile with an empty country scope persists and reloads as unscoped`() {
        val profile = B2bProfile().edit { name = "IT-Unscoped-${System.nanoTime()}" }
        em.persist(profile)
        em.flush()
        em.clear()

        val reloaded = em.find(B2bProfile::class.java, profile.id)
        assertTrue(reloaded.countries.isEmpty(), "no rows in b2b_profile_country = unscoped")
    }

    @Test
    @Transactional
    fun `a profile's country scope persists and reloads exactly`() {
        val profile = B2bProfile().edit {
            name = "IT-Scoped-${System.nanoTime()}"
            countries = mutableSetOf(Country.FR, Country.LU)
        }
        em.persist(profile)
        em.flush()
        em.clear()

        val reloaded = em.find(B2bProfile::class.java, profile.id)
        assertEquals(setOf(Country.FR, Country.LU), reloaded.countries)
    }

    @Test
    @Transactional
    fun `updating a profile's country scope replaces the join-table rows, not appends to them`() {
        val profile = B2bProfile().edit {
            name = "IT-Rescope-${System.nanoTime()}"
            countries = mutableSetOf(Country.FR)
        }
        em.persist(profile)
        em.flush()
        em.clear()

        val reloaded = em.find(B2bProfile::class.java, profile.id)
        reloaded.countries.clear()
        reloaded.countries.addAll(setOf(Country.LU, Country.BE))
        em.flush()
        em.clear()

        val reloadedAgain = em.find(B2bProfile::class.java, profile.id)
        assertEquals(setOf(Country.LU, Country.BE), reloadedAgain.countries)
    }

    // ── Regression test: LazyInitializationException when accessing countries ───────────────────

    @Test
    fun `findProfileById initializes countries collection to prevent LazyInitializationException`() {
        // Create a profile with a country scope and persist it
        val testName = "IT-LazyInit-findById-${System.nanoTime()}"
        persistProfile(testName, countries = setOf(Country.FR))

        // Call findProfileById outside any explicit transaction (mimics the editor's behavior)
        // This would throw LazyInitializationException if countries is not initialized
        val loaded = b2bProfileLifecycleService.findProfileById(findProfileIdByName(testName))
        assertNotNull(loaded)
        assertEquals(testName, loaded!!.name)
        // Access countries outside transaction — this is the regression test
        val countriesSet = loaded.countries.toSet()
        assertEquals(setOf(Country.FR), countriesSet)
    }

    @Test
    fun `findAllActiveProfiles initializes countries collection to prevent LazyInitializationException`() {
        // Create an active profile with country scope
        val testName = "IT-LazyInit-findAllActive-${System.nanoTime()}"
        persistProfile(testName, active = true, countries = setOf(Country.LU))

        // Call findAllActiveProfiles outside any explicit transaction
        // This would throw LazyInitializationException if countries is not initialized
        val profiles = b2bProfileLifecycleService.findAllActiveProfiles()
        val found = profiles.find { it.name == testName }
        assertNotNull(found)
        // Access countries outside transaction — this is the regression test
        val countriesSet = found!!.countries.toSet()
        assertEquals(setOf(Country.LU), countriesSet)
    }

    @Test
    fun `findSellerFieldsUnion accesses profile countries without LazyInitializationException`() {
        // Create an active profile with country scope
        val testName = "IT-LazyInit-union-${System.nanoTime()}"
        persistProfile(testName, active = true, countries = setOf(Country.BE))

        // Call findSellerFieldsUnion which internally calls findAllActive() and accesses countries
        // This would throw LazyInitializationException if countries is not initialized
        val union = b2bProfileFieldDefinitionService.findSellerFieldsUnion()
        // If we reach here without exception, the regression test passes
        assertNotNull(union)
    }

    @Transactional
    fun persistProfile(name: String, active: Boolean = true, countries: Set<Country> = emptySet()) {
        val profile = B2bProfile().edit {
            this.name = name
            this.active = active
            this.countries = countries.toMutableSet()
        }
        em.persist(profile)
        em.flush()
    }

    @Transactional
    fun findProfileIdByName(name: String): Long {
        return (em.createNativeQuery("SELECT id FROM b2b_profile WHERE name = :name")
            .setParameter("name", name)
            .singleResult as Number).toLong()
    }
}
