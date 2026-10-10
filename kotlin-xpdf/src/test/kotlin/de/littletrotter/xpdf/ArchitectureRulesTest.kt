package de.littletrotter.xpdf

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.Architectures.layeredArchitecture
import org.junit.jupiter.api.Test

/**
 * Architecture lock-ins for the kotlin-xpdf module: it is a standalone
 * document engine (NATS consumer + PDF/Factur-X producers) and must stay free of
 * the invoice domain model and any Vaadin UI dependency.
 *
 * ## The model in prose (kotlin-xpdf's smaller mirror of the kotlin-invoice onion)
 *
 * kotlin-xpdf has no application/domain split of its own — it is a thin document-engine service,
 * not a DDD module — but it still has a real, verified inward-only shape:
 * - **Api** (`de.littletrotter.xpdf`, the top-level package only) is the public surface: the
 *   Quarkus HTTP entry point (`DocumentController`) plus shared config/client helpers
 *   (`EnvConfig`, `S3Clients`, `JobRequest`) that both the entry points and the layers below
 *   configure/deserialize themselves from.
 * - **Messaging** (`de.littletrotter.xpdf.infrastructure.messaging`, exact package) is
 *   the NATS boundary: every class that talks to the broker (`JobConsumer`, `ResultPublisher`,
 *   `NatsConnectionProducer`) lives here, mirroring `kotlin-invoice`'s own
 *   `de.littletrotter.infrastructure.messaging` package so a later broker swap touches one
 *   package per module. It reads `JobRequest` from Api to deserialize an incoming job.
 * - **Impl** (`de.littletrotter.xpdf.impl`, exact package, not its sub-packages) is the
 *   orchestration layer: it composes the two named library adapters below to actually render PDFs
 *   and export XML (`PdfExportServiceImpl`, `XmlExportServiceImpl`). The one
 *   cross-cutting helper both adapters used to call back into (`resolveActiveSellerVatId`/
 *   `extractGuidelineUrn`) moved into `kotlin-shared`, since both were pure
 *   functions over shared DTOs with no real xpdf-specific dependency; that closed FacturX's only
 *   call-back into bare Impl. Mustang still calls back into Impl for real (`Labels`,
 *   `resolveDocumentRenderLanguage`, `composeTaxNote`), so the dependency between Impl and Mustang
 *   still runs both ways — Impl calls Mustang to do the work, Mustang calls back into Impl for
 *   those helpers. Impl also reaches into Messaging: `LlmAnalysisStage`'s deferred oMLX
 *   result is published through the same `ResultPublisher` `JobConsumer` uses for the job-level
 *   result.
 * - **Mustang** (`de.littletrotter.xpdf.impl.mustang..`) is the one remaining named
 *   library-integration adapter inside this module — the Mustang CII exporter. Its Factur-X-engine
 *   counterpart (formerly the sibling package under `impl`, the "shared-DTO boundary" adapter this
 *   class doc used to describe alongside Mustang) moved out into its own Maven module,
 *   `kotlin-facturx` (package `de.littletrotter.facturx`): Impl now depends on it as an
 *   ordinary compile dependency (the way
 *   it already depends on `kotlin-shared`), the same relationship it has with Mustang, just across
 *   a module boundary instead of a package boundary. [`facturx and mustang never depend on each
 *   other`][de.littletrotter.arch.FacturXMustangIndependenceTest] proves the one guarantee that
 *   used to live in this class's own layered-architecture check still holds now that FacturX is no
 *   longer part of the `de.littletrotter.xpdf..` tree at all — see that test's own class doc.
 *
 * `withOptionalLayers(false)` — the layers above are the entire `de.littletrotter.xpdf..`
 * tree (confirmed: `impl` has no sub-package besides `mustang` now that `facturx` moved out), so
 * every imported class must land in one of them.
 */
class ArchitectureRulesTest {

    private val classes = ClassFileImporter()
        .withImportOption(ImportOption.DoNotIncludeTests())
        .importPackages("de.littletrotter.xpdf")

    @Test
    fun `xpdf must not depend on the invoice domain`() {
        noClasses()
            .that().resideInAPackage("de.littletrotter.xpdf..")
            .should().dependOnClassesThat()
            .resideInAPackage("de.littletrotter.domain..")
            .because("xpdf is a standalone document engine; the invoice domain model stays in kotlin-invoice")
            .check(classes)
    }

    @Test
    fun `xpdf must not depend on JasperReports`() {
        noClasses()
            .that().resideInAPackage("de.littletrotter.xpdf..")
            .should().dependOnClassesThat()
            .resideInAPackage("net.sf.jasperreports..")
            .because(
                "JasperReports is retired (ADR-030); Typst is the only layout engine, so " +
                    "the dependency must never return to this module",
            )
            .check(classes)
    }

    @Test
    fun `xpdf must not depend on Vaadin`() {
        noClasses()
            .that().resideInAPackage("de.littletrotter.xpdf..")
            .should().dependOnClassesThat()
            .resideInAPackage("com.vaadin..")
            .because("xpdf runs headless (NATS consumer); no UI dependency allowed")
            .check(classes)
    }

    /**
     * The comprehensive layered check described in the class KDoc above. Subsumes the
     * former, narrower "public xpdf api must not depend on impl details" rule: that is
     * now exactly what `Api.mayNotAccessAnyLayer()` proves, as a byproduct of the whole model
     * holding together.
     *
     * The former "FacturX" layer was dropped from this check entirely: FacturX no longer lives
     * under `de.littletrotter.xpdf..`, so `layeredArchitecture()`'s own package-membership model
     * has nothing left here to assign it to. `Impl` depending on the real
     * `de.littletrotter.facturx..` classes (an ordinary cross-module Maven dependency, invisible to
     * this single-package `classes` import) is simply not a dependency this check can or needs to
     * see — the "FacturX and Mustang never depend on each other" guarantee this class doc used to
     * fold into the layered check moved to its own dedicated cross-module test,
     * [de.littletrotter.arch.FacturXMustangIndependenceTest], which is the only test that can see
     * both packages at once.
     */
    @Test
    fun `xpdf conforms to its api-impl-adapter model`() {
        layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Api").definedBy("de.littletrotter.xpdf")
            .layer("Messaging").definedBy("de.littletrotter.xpdf.infrastructure.messaging")
            .layer("Impl").definedBy("de.littletrotter.xpdf.impl")
            .layer("Mustang").definedBy("de.littletrotter.xpdf.impl.mustang..")
            .whereLayer("Api").mayNotAccessAnyLayer()
            .whereLayer("Messaging").mayOnlyAccessLayers("Api")
            .whereLayer("Messaging").mayOnlyBeAccessedByLayers("Impl")
            .whereLayer("Impl").mayOnlyAccessLayers("Api", "Messaging", "Mustang")
            .whereLayer("Impl").mayOnlyBeAccessedByLayers("Mustang")
            .whereLayer("Mustang").mayOnlyAccessLayers("Api", "Impl")
            .whereLayer("Mustang").mayOnlyBeAccessedByLayers("Impl")
            .withOptionalLayers(false)
            .because(
                "The public Api surface never reaches into impl, the " +
                    "NATS boundary (Messaging) only reads Api's job DTO and is only ever driven " +
                    "by Impl, orchestration composes Mustang (which still calls back for Impl " +
                    "helpers); FacturX moved out of this module's own tree entirely and is no longer part " +
                    "of this layered check"
            )
            .check(classes)
    }
}
