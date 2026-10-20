package de.littletrotter.arch

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.Architectures.onionArchitecture
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The closing architecture lock-in: ONE comprehensive rule that codifies the
 * entire DDD model of kotlin-invoice, so that any future violation of the architecture — a new
 * package that skips a layer, a helper that reaches sideways between two infrastructure adapters,
 * a sub-context edge drawn the wrong way — fails the build as a single named check instead of
 * waiting to be noticed in review.
 *
 * ## The model in prose
 *
 * kotlin-invoice is a hexagonal application (ports & adapters). Dependencies point inward, and the
 * inner layers never know the outer ones exist:
 *
 * - **Domain** (`de.littletrotter.domain..`) is the innermost tier: entities, value objects and
 *   domain services for the sub-contexts `invoice`, `compliance`, `masterdata`, `reference`,
 *   `message` and `system`, plus the cross-cutting `value`/`exception`/`event` types and the
 *   `repository` port interfaces that outer adapters implement. `domainModels` and
 *   `domainServices` are both mapped to `..domain..` — this module does not physically separate
 *   "pure model" from "domain service" into different packages, so both onion tiers cover the same
 *   package tree; ArchUnit still enforces that neither may reach into application or an adapter.
 * - **Application** (`de.littletrotter.application..`) orchestrates use cases against the domain
 *   and defines the ports adapters plug into. `de.littletrotter.spi..` — the plugin contracts for
 *   dynamic invoice form fields and party/article extensions (`InvoiceFormModule`,
 *   `PartyFormExtension`, `ArticleRecipientRefsExtension`, ...) — is folded into this same tier:
 *   its own dependency graph touches only `domain.masterdata.Country` (nothing from application,
 *   infrastructure or ui), and it is implemented by application-layer registries
 *   (`InvoiceFormModuleRegistry`, `PartyFormExtensionRegistry`, ...) and rendered by ui components
 *   (`ModuleFieldSection`, `PartyExtensionsSection`, ...) exactly the way an application port is
 *   meant to be used from both sides — so it belongs with applicationServices, not as a layer of
 *   its own.
 * - **Adapters** are named and mutually isolated — each may depend inward on application and
 *   domain, but never sideways on another adapter, which is the part plain layer rules cannot
 *   express: `persistence` (JPA repositories), `messaging` (NATS), `storage` (S3/local document
 *   store), `cache` (Valkey), `docker` (engine-activation/status), `classpath` (bundled
 *   third-party-library metadata), `vies` (EU VAT-ID validation HTTP client), `dev` (dev-profile
 *   fixture seeding), `export` (Excel workbook writer, `de.littletrotter.infrastructure
 *   .export..` — the sheet writers are domain-aware by necessity, mapping invoice-shaped rows
 *   onto POI cells, so this tier needs the same inward-only allowance every other adapter gets) —
 *   all under `de.littletrotter.infrastructure.<name>..` — and `ui`
 *   (`de.littletrotter.ui..`, the Vaadin views).
 *
 * `withOptionalLayers(false)` means every class this rule imports must land in exactly one of
 * these tiers; an unassignable class is a violation in its own right, not something the rule stays
 * silent about.
 *
 * ## What is deliberately out of scope
 *
 * The class-import set below is scoped to `domain`/`application`/`infrastructure`/`ui`/`spi` only
 * — it does **not** import the bare `de.littletrotter` root. Two things live there and are
 * deliberately excluded, not force-fitted into a layer:
 * - `de.littletrotter.shared..` is the *kotlin-shared* module's message-contract package (DTOs,
 *   `DocumentStore`/`KositValidatorPort`-style ports shared with kotlin-xpdf); it is a dependency
 *   jar on this module's classpath, not part of kotlin-invoice's own DDD model, and it has its own
 *   ArchUnit coverage in the kotlin-shared module.
 * - `de.littletrotter.Main` is the `@QuarkusMain` bootstrap object — one line that calls
 *   `Quarkus.run(...)`, no business logic, not part of the model being locked in here.
 *
 * Excluding them from the *import*, rather than adding an `ignoreDependency`/exemption to the
 * rule, keeps the rule itself at full strength: every class it does see must fit, no exceptions.
 *
 * ## Sub-context edges inside the domain tier
 *
 * The onion check above treats all of `..domain..` as one tier — it cannot see the sub-context
 * boundaries *inside* domain. Those are encoded here as companion rules so the whole model reads
 * in one file:
 * - `compliance` → `invoice` / `masterdata` is allowed (the rule engine reads the aggregates it
 *   checks); the reverse is not — an aggregate must never reach into the rules that judge it.
 * - `masterdata` → `invoice` is not allowed — `InvoiceFormat` lives in `domain.shared`
 *   for exactly this reason.
 * - `masterdata` → `compliance` is not allowed either, for the same reason as the invoice edge:
 *   master data must stay ignorant of the compliance rule engine that later judges invoices built
 *   from it.
 */
class DddModelArchitectureTest {

    /**
     * Scoped to this module's own architecture — see "What is deliberately out of scope" above for
     * why `de.littletrotter.shared` (kotlin-shared dependency) and `de.littletrotter.Main`
     * (bootstrap object) are not part of this import.
     */
    private val classes = ClassFileImporter()
        .withImportOption(ImportOption.DoNotIncludeTests())
        .importPackages(
            "de.littletrotter.domain",
            "de.littletrotter.application",
            "de.littletrotter.infrastructure",
            "de.littletrotter.ui",
            "de.littletrotter.spi",
        )

    @Test
    fun `kotlin-invoice conforms to its hexagonal DDD model`() {
        onionArchitecture()
            .domainModels("..domain..")
            .domainServices("..domain..")
            .applicationServices("..application..", "de.littletrotter.spi..")
            .adapter("persistence", "..infrastructure.persistence..")
            .adapter("messaging", "..infrastructure.messaging..")
            .adapter("storage", "..infrastructure.storage..")
            .adapter("receipt", "..infrastructure.receipt..")
            .adapter("cache", "..infrastructure.cache..")
            .adapter("docker", "..infrastructure.docker..")
            .adapter("classpath", "..infrastructure.classpath..")
            .adapter("vies", "..infrastructure.vies..")
            .adapter("dev", "..infrastructure.dev..")
            .adapter("export", "..infrastructure.export..")
            // The mail-dispatch outbound adapter (HttpMailDispatchAdapter): the host's one
            // outbound call into the mail bounded context, inward-only like every other adapter.
            .adapter("mail", "..infrastructure.mail..")
            // The offer-component outbound adapters (Http*Adapter, OidcQuoteCallerProvider): the
            // host's calls into the quote bounded context, reached only through the outbound ports
            // of application.quote and inward-only like every other adapter.
            .adapter("quote", "..infrastructure.quote..")
            // PO 2026-09-07 — the inbound authentication adapter (InternalAuthVerifyResource):
            // Caddy's forward_auth target that gates the mail component. An adapter of its own
            // rather than part of `ui`, because nothing about it is Vaadin: it speaks HTTP to the
            // reverse proxy and reads only application-layer ports (AppRoleQueryPort), inward-only
            // like every other adapter.
            .adapter("auth", "..infrastructure.auth..")
            .adapter("ui", "..ui..")
            .withOptionalLayers(false)
            .because(
                "Program-closing architecture lock-in (H27-21c): domain is innermost, " +
                    "application (incl. the spi plugin contracts) orchestrates it, and every " +
                    "infrastructure/ui adapter depends inward only, never on another adapter"
            )
            .check(classes)
    }

    /**
     * The compliance sub-context reads the invoice aggregate to judge it; the aggregate must
     * never read the rules that judge it, or the first "just this once" call back would dissolve
     * the sub-context boundary.
     */
    @Test
    fun `invoice domain must not depend on the compliance sub-context`() {
        noClasses()
            .that().resideInAPackage("..domain.invoice..")
            .should().dependOnClassesThat()
            .resideInAPackage("..domain.compliance..")
            .because("Die Regelauswertung kennt das Rechnungsaggregat, nicht andersherum (T5a)")
            .check(classes)
    }

    /**
     * Mirror of the masterdata→compliance edge for the masterdata/invoice pair. Enabled by
     * moving `InvoiceFormat` to `domain.shared`, which removed the only
     * masterdata→invoice dependency.
     */
    @Test
    fun `masterdata domain must not depend on the invoice sub-context`() {
        noClasses()
            .that().resideInAPackage("..domain.masterdata..")
            .should().dependOnClassesThat()
            .resideInAPackage("..domain.invoice..")
            .because("Sub-contexts must stay one-directional, same as the T5a compliance edge")
            .check(classes)
    }

    /**
     * Same reasoning as the masterdata→compliance edge, for the other sub-context that compliance reads:
     * master data must stay ignorant of the compliance rule engine that later judges invoices built
     * from it.
     */
    @Test
    fun `masterdata domain must not depend on the compliance sub-context`() {
        noClasses()
            .that().resideInAPackage("..domain.masterdata..")
            .should().dependOnClassesThat()
            .resideInAPackage("..domain.compliance..")
            .because("Master data must stay ignorant of the compliance rule engine, mirroring T5a")
            .check(classes)
    }

    /**
     * A repository belongs to an aggregate root, never to a child entity: `Address`, the invoice
     * documents and analyses and the profile field values are changed through their root and its
     * repository. The root names are the ones arc42 section 5 ("Aggregates at a Glance") lists.
     */
    private val repositoryRule: ArchRule = classes()
        .that(
            DescribedPredicate.describe("domain repository interfaces") { type: JavaClass ->
                type.isInterface && type.packageName.endsWith(".repository") && type.simpleName.endsWith("Repository")
            },
        )
        .should(
            object : ArchCondition<JavaClass>("belong to an aggregate root named in arc42") {
                override fun check(item: JavaClass, events: ConditionEvents) {
                    val root = item.simpleName.removeSuffix("Repository")
                    if (root !in AGGREGATE_ROOTS) {
                        events.add(SimpleConditionEvent.violated(item, "${item.name}: $root is not an aggregate root"))
                    }
                }
            },
        )
        .allowEmptyShould(true)

    private val repositorySpecimens = ClassFileImporter().importPackages("de.littletrotter.arch.specimen.repository")

    @Test
    fun `domain repositories exist only for aggregate roots`() {
        repositoryRule.check(classes)
    }

    @Test
    fun `the repository rule reports a child entity repository and stays silent about root look-alikes`() {
        val report = repositoryRule.evaluate(repositorySpecimens).failureReport.details.joinToString("\n")

        assertTrue(report.contains("InvoiceItemRepository"))
        assertFalse(report.contains("specimen.repository.InvoiceRepository"))
        assertFalse(report.contains("InvoiceItemLookup"))
    }

    private companion object {
        /** The roots of arc42 section 5, plus the stand-alone reference data and journals listed there. */
        val AGGREGATE_ROOTS = setOf(
            "Invoice", "Recipient", "SenderProfile", "Article", "Receipt", "GenerationJob", "B2bProfile",
            "TaxRate", "NumberRange", "NumberCounter", "NumberJournal", "PaymentTerm", "Language",
            "PerDiemRate", "MileageRate", "HotelRate",
            "FeatureSetting", "SystemSetting", "SystemMessage",
            "ValidationProfile", "ValidationSetting", "ValidationFinding", "InvoiceAuditJournal",
        )
    }
}
