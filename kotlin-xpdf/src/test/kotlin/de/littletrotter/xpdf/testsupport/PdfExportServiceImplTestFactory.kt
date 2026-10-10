package de.littletrotter.xpdf.testsupport

import de.littletrotter.facturx.BrFrCtcValidator
import de.littletrotter.facturx.FacturXEngine
import de.littletrotter.facturx.FacturXSchematronValidator
import de.littletrotter.shared.BasePdfRendererPort
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.KositValidatorPort
import de.littletrotter.shared.archive.ArchiveSigner
import de.littletrotter.shared.time.ClockTimeSource
import de.littletrotter.shared.time.TimeSource
import de.littletrotter.xpdf.impl.BasePdfStage
import de.littletrotter.xpdf.impl.CiiXsdValidator
import de.littletrotter.xpdf.impl.ExportCompletionStage
import de.littletrotter.xpdf.impl.OutputIntentVerifier
import de.littletrotter.xpdf.impl.ExportValidationStages
import de.littletrotter.xpdf.impl.FacturXExportPipeline
import de.littletrotter.xpdf.impl.LlmAnalysisStage
import de.littletrotter.xpdf.impl.MustangPdfRenderer
import de.littletrotter.xpdf.impl.PdfA3MinimalCheck
import de.littletrotter.xpdf.impl.PdfExportServiceImpl
import de.littletrotter.xpdf.impl.VeraPdfCliValidator
import de.littletrotter.xpdf.impl.XmlExportServiceImpl
import org.mockito.kotlin.mock
import java.time.Instant

/**
 * [PdfExportServiceImpl]'s production constructor no longer default-constructs its
 * collaborators (BasePdfStage, CiiXsdValidator, FacturXSchematronValidator, FacturXEngine,
 * BrFrCtcValidator, ExportValidationStages, ExportCompletionStage, FacturXExportPipeline,
 * MustangPdfRenderer): every one of those already has its own `@ApplicationScoped @Inject
 * constructor` CDI bean (or, for the two kotlin-facturx types carrying no CDI scope of their own
 * — see [FacturXSchematronValidator]/[BrFrCtcValidator]'s own CDI note — a small CDI
 * producer in `de.littletrotter.xpdf.impl`), so a class-level Kotlin default on
 * [PdfExportServiceImpl] itself never actually served production; it only let a direct Kotlin
 * construction (i.e. every test) skip naming a real dependency it never exercised.
 *
 * This factory is the single place that composes the exact same chain the removed defaults used
 * to build (kept byte-identical), for the many tests that just want "a working service" without
 * caring about any one collaborator. A test that DOES care about a specific collaborator still
 * calls [create] and names only the parameter(s) it overrides — never [PdfExportServiceImpl]'s
 * own constructor directly with the rest guessed at, since there is no default left to guess.
 *
 * Lives in kotlin-xpdf's own test sources (never `main` — this is test wiring, never shipped) and
 * is pulled into kotlin-e2e-tests as an extra test source via `build-helper-maven-plugin`, the
 * same "single-file directory, no test-jar" idiom `kotlin-e2e-tests/pom.xml` already uses for
 * kotlin-invoice's RG-2 invariants and kotlin-facturx's `FacturXEngineTestResource`.
 */
object PdfExportServiceImplTestFactory {

    /** A fixed "now" for tests that only want a working service and never assert on timing —
     *  see [PdfExportServiceImplTestFactory.create]'s own [TimeSource] parameter for the
     *  overridable case. */
    private val FIXED_TIME: TimeSource = ClockTimeSource.fixedAt(Instant.parse("2026-01-15T10:30:00Z"))

    fun create(
        xmlExportService: XmlExportServiceImpl,
        reportRenderer: BasePdfRendererPort,
        documentStore: DocumentStore,
        kositValidator: KositValidatorPort,
        // Not BasePdfStage's own `internal` 1-arg convenience constructor: this factory is also
        // compiled as an extra test source inside kotlin-e2e-tests (a separate module), which has
        // no visibility into kotlin-xpdf's `internal` declarations — same reasoning as
        // [facturXEngine] below.
        basePdfStage: BasePdfStage = BasePdfStage(reportRenderer, PdfA3MinimalCheck(), VeraPdfCliValidator()),
        ciiXsdValidator: CiiXsdValidator = CiiXsdValidator().apply { init() },
        facturXSchematronValidator: FacturXSchematronValidator = FacturXSchematronValidator().apply { init() },
        // A plain mock, not the internal `UnconfiguredFacturXEngine` null-object: this factory
        // is also compiled as an extra test source inside kotlin-e2e-tests (a separate module),
        // which has no visibility into kotlin-xpdf's `internal` declarations.
        facturXEngine: FacturXEngine = mock(),
        brFrCtcValidator: BrFrCtcValidator = BrFrCtcValidator(),
        llmAnalysisStage: LlmAnalysisStage? = null,
        outputIntentVerifier: OutputIntentVerifier = OutputIntentVerifier(),
        archiveSigner: ArchiveSigner? = null,
        // Not a real production default (this factory is test-only wiring, never `main`) — a
        // fixed clock so tests that just want "a working service" stay deterministic; a test
        // that cares about "now" passes its own TimeSource (mock or a differently-fixed clock).
        time: TimeSource = FIXED_TIME,
    ): PdfExportServiceImpl {
        val exportValidationStages = ExportValidationStages(
            kositValidator, facturXSchematronValidator, brFrCtcValidator, facturXEngine, basePdfStage, ciiXsdValidator, time,
        )
        val exportCompletionStage = ExportCompletionStage(
            documentStore, archiveSigner, llmAnalysisStage, outputIntentVerifier, exportValidationStages, time,
        )
        val facturXExportPipeline = FacturXExportPipeline(facturXEngine, basePdfStage, exportCompletionStage, time)
        val mustangPdfRenderer = MustangPdfRenderer(basePdfStage, xmlExportService)
        // PdfExportServiceImpl itself no longer holds reportRenderer/
        // kositValidator/facturXSchematronValidator/facturXEngine/brFrCtcValidator/ciiXsdValidator/
        // llmAnalysisStage/outputIntentVerifier/archiveSigner as its own fields: those were only
        // ever used to build the composed stages above (the removed default-construction chain);
        // this factory still takes them as its own parameters purely to build those stages.
        return PdfExportServiceImpl(
            xmlExportService = xmlExportService,
            documentStore = documentStore,
            basePdfStage = basePdfStage,
            exportValidationStages = exportValidationStages,
            exportCompletionStage = exportCompletionStage,
            facturXExportPipeline = facturXExportPipeline,
            mustangPdfRenderer = mustangPdfRenderer,
        )
    }
}
