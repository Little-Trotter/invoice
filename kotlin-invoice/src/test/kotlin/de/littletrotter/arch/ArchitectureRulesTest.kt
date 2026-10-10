
package de.littletrotter.arch

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import jakarta.persistence.Entity
import org.junit.jupiter.api.Test

/**
 * Enforces the hexagonal / clean-architecture layer contract:
 *
 *   domain  ←  application  ←  infrastructure
 *                          ←  ui
 *
 * Dependency arrows point inward; no inner layer may import an outer layer.
 *
 * The pairwise "X must not depend on Y" layer rules that used to live here were folded into
 * [DddModelArchitectureTest]'s single `onionArchitecture()` check, which proves the same thing
 * (domain/application never reach outward, and infrastructure/ui — as named adapters — never reach
 * sideways into each other) as a byproduct of the one comprehensive rule. What remains here are the
 * rules the onion does *not* subsume: stricter-than-layering constraints (ui bypassing application
 * straight to a repository interface, raw XML parsing, constructing domain entities from the UI),
 * cycle freedom, the mock/shared-package guards, and the SPI/transactional annotation rules.
 */
class ArchitectureRulesTest {

    private val classes = ClassFileImporter()
        .withImportOption(ImportOption.DoNotIncludeTests())
        .importPackages("de.littletrotter")

    @Test
    fun `ui must not depend on domain repository interfaces`() {
        noClasses()
            .that().resideInAPackage("..ui..")
            .should().dependOnClassesThat()
            .resideInAPackage("..domain.repository..")
            .because("UI must not access domain repositories directly; it goes through the input port of its use case in application.<context>.port.inbound")
            .check(classes)
    }

    /** The mail component (kotlin-email) is reached only via kotlin-shared's contract. */
    @Test
    fun `invoicing never depends on the mail module`() {
        noClasses()
            .that().resideInAPackage("de.littletrotter..")
            .should().dependOnClassesThat().resideInAPackage("de.littletrotter.email..")
            .because("kotlin-email is a separate self-contained system; integrate via de.littletrotter.shared.mail only")
            .check(classes)
    }

    /** The offer component (kotlin-quote) is reached only via kotlin-quote-contract. */
    @Test
    fun `invoicing never depends on the quote module`() {
        noClasses()
            .that().resideInAPackage("de.littletrotter..")
            .should().dependOnClassesThat().resideInAPackage("de.littletrotter.quote..")
            .because("kotlin-quote is a separate self-contained system; integrate via de.littletrotter.shared.quote only")
            .check(classes)
    }

    @Test
    fun `production code must not include mock worker package`() {
        noClasses()
            .that().resideInAnyPackage("de.littletrotter..")
            .should().resideInAPackage("..mock..")
            .because("Mock and simulator code must not be compiled into the production artifact")
            .check(classes)
    }

    @Test
    fun `kotlin-invoice must not add classes to the shared module package`() {
        noClasses()
            .that().resideInAPackage("de.littletrotter.shared..")
            .should().dependOnClassesThat().resideInAPackage("com.vaadin..")
            .because("de.littletrotter.shared belongs to the kotlin-shared module (message contract); a Vaadin UI helper here would create a split package")
            .check(classes)
    }

    @Test
    fun `spi section must not depend on ui views`() {
        noClasses()
            .that().resideInAPackage("de.littletrotter.spi..")
            .should().dependOnClassesThat().resideInAPackage("..ui.view..")
            .because("The SPI section defines the internal plugin contracts for form modules and " +
                "party extensions; it must stay free of view types")
            .check(classes)
    }

    /**
     * The UI layer no longer parses KoSIT report XML directly (that moved to
     * `KositReportReader` in the application layer); this rule keeps it that way.
     */
    @Test
    fun `ui must not depend on raw XML parsing types`() {
        noClasses()
            .that().resideInAPackage("..ui..")
            .should().dependOnClassesThat().resideInAnyPackage("org.w3c.dom..", "org.xml.sax..")
            .because("KoSIT report XML interpretation belongs to KositReportReader in the application layer")
            .check(classes)
    }

    /** Package cycles hide layering and make refactoring unsafe. */
    @Test
    fun `packages must be free of cycles`() {
        slices()
            .matching("de.littletrotter.(*)..")
            .should().beFreeOfCycles()
            .because("Package cycles hide layering and make refactoring unsafe")
            .check(classes)
    }

    /**
     * `dtoToInvoice` (the invoice detail view's own hand-reconstruction of a
     * detached `Invoice`, including fake `SenderProfile`/`Recipient` objects, from the
     * `InvoiceDetailDto` read model) is gone; the view now works on the DTO throughout. This locks
     * that in for the one package it was ever a problem in. `Invoice` itself is never legitimately
     * constructed anywhere in the UI at all (invoice creation always goes through
     * `InvoiceLifecycleService.create()` in the application layer), so that half of the rule
     * covers all of `..ui..`. `SenderProfile`/`Recipient`, by contrast, are legitimately
     * constructed elsewhere in the UI — `ui.view.masterdata`'s own `SenderProfileView`/
     * `RecipientView` "Anlegen" (create) dialogs build a genuinely new master-data record for
     * the master-data save, which is the correct and intended
     * pattern for a CRUD form, not the anti-pattern this rule targets — so that half stays scoped
     * to `ui.view.invoice`, the package `dtoToInvoice` actually lived in.
     */
    @Test
    fun `ui must never construct a domain Invoice`() {
        noClasses()
            .that().resideInAPackage("..ui..")
            .should().callConstructor(Invoice::class.java)
            .because("Invoice creation always goes through InvoiceLifecycleService.create() in the application layer; the UI only ever reads/writes it via InvoiceDetailDto and explicit commands")
            .check(classes)
    }

    @Test
    fun `invoice detail view must not construct SenderProfile or Recipient`() {
        noClasses()
            .that().resideInAPackage("..ui.view.invoice..")
            .should().callConstructor(SenderProfile::class.java)
            .orShould().callConstructor(Recipient::class.java)
            .because("dtoToInvoice's hand-reconstruction of these parties from the InvoiceDetailDto read model was removed; the invoice detail view resolves real parties through RecipientQueryPort/SenderQueryPort instead of building its own")
            .check(classes)
    }

    /**
     * The statistics read model (`v_stat_*` JPA projections, formerly
     * `domain.invoice.stats`) moved to `infrastructure.persistence.stats`, where the onion rule in
     * [DddModelArchitectureTest] already forbids domain from depending on any adapter as a general
     * matter of layering. This names that specific regression directly, and guards the second half
     * the onion check cannot see on its own: no `@Entity` may ever live under `domain.invoice.stats`
     * again, even if that package is recreated empty.
     */
    @Test
    fun `domain must not contain the statistics read model`() {
        noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure.persistence.stats..")
            .because(
                "the v_stat_* JPA projections are a read model, not domain; they live in " +
                    "infrastructure.persistence.stats"
            )
            .check(classes)
        noClasses()
            .that().resideInAPackage("..domain.invoice.stats..")
            .should().beAnnotatedWith(Entity::class.java)
            .allowEmptyShould(true)
            .because(
                "no JPA entity may live under domain.invoice.stats any more; " +
                    "allowEmptyShould(true) because that package no longer exists at all, which is " +
                    "the point of this guard"
            )
            .check(classes)
    }

    /** Transaction boundaries belong to application services. */
    @Test
    fun `transactional annotation only in the application layer`() {
        noClasses()
            .that().areAnnotatedWith("jakarta.transaction.Transactional")
            .should().resideOutsideOfPackage("..application..")
            .because("Transaction boundaries belong to application services, not ui or domain")
            .check(classes)
    }

    /**
     * Each side of the invoicing/mail-dispatch boundary maps the published mail
     * contract (`de.littletrotter.shared.mail`) into its own local model *at its own adapter*.
     * `domain` and `application` therefore never depend on the contract directly; only the
     * translator classes in `infrastructure.mail.acl`/`infrastructure.messaging.acl` (and the
     * adapters that call them) may.
     */
    private val mailContractOnlyInInfrastructure = noClasses()
        .that().resideInAnyPackage("de.littletrotter.domain..", "de.littletrotter.application..")
        .should().dependOnClassesThat().resideInAPackage("de.littletrotter.shared.mail..")
        .because(
            "ADR-016 — the mail contract is used only in infrastructure.mail and infrastructure.messaging " +
                "translators; domain and application map to their own local model (InvoiceMailDraft, " +
                "MailDeliveryReport)",
        )

    @Test
    fun `domain and application never depend on the mail contract`() {
        mailContractOnlyInInfrastructure.check(classes)
    }

    @Test
    fun `negative specimen depending on the mail contract from application is rejected`() {
        val specimens = ClassFileImporter().importPackages("de.littletrotter.application.mail.specimen")
        val result = mailContractOnlyInInfrastructure.evaluate(specimens)
        org.junit.jupiter.api.Assertions.assertTrue(
            result.hasViolation(),
            "expected the mail-contract rule to reject ViolatingApplicationDependsOnMailContract",
        )
    }

    /** Same boundary as the mail contract: the offer contract is mapped in `infrastructure.quote.acl` only. */
    private val quoteContractOnlyInInfrastructure = noClasses()
        .that().resideInAnyPackage("de.littletrotter.domain..", "de.littletrotter.application..")
        .should().dependOnClassesThat().resideInAPackage("de.littletrotter.shared.quote..")
        .because(
            "ADR-016 — the offer contract is used only in infrastructure.quote translators; domain and " +
                "application map to their own local model (QuoteDetail, QuoteSummary)",
        )

    @Test
    fun `domain and application never depend on the quote contract`() {
        quoteContractOnlyInInfrastructure.check(classes)
    }

    @Test
    fun `negative specimen depending on the quote contract from application is rejected`() {
        val specimens = ClassFileImporter().importPackages("de.littletrotter.application.quote.specimen")
        val result = quoteContractOnlyInInfrastructure.evaluate(specimens)
        org.junit.jupiter.api.Assertions.assertTrue(
            result.hasViolation(),
            "expected the quote-contract rule to reject ViolatingApplicationDependsOnQuoteContract",
        )
    }
}
