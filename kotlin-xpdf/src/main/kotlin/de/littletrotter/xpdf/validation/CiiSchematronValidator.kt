package de.littletrotter.xpdf.validation

import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.ValidationMessage
import de.littletrotter.shared.ValidationStageResult
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.shared.extractGuidelineUrn
import net.sf.saxon.TransformerFactoryImpl
import org.xml.sax.SAXException
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.StringWriter
import javax.xml.transform.Source
import javax.xml.transform.Templates
import javax.xml.transform.TransformerException
import javax.xml.transform.URIResolver
import javax.xml.transform.stream.StreamResult
import javax.xml.transform.stream.StreamSource

/** Classpath root both profile directories share (`en16931/`, `extended/`), see
 *  `kotlin-xpdf/src/main/resources/schematron/facturx/README.md` for provenance. */
private const val SCHEMATRON_ROOT = "schematron/facturx"

private const val SEVERITY_ERROR = "error"

/**
 * Which official Factur-X Schematron profile to run. [EN16931] is our own export profile
 * (`XmlExportServiceImpl.resolveProfile` never produces anything else — "XRechnung" is a CIUS
 * *on top of* EN16931, checked separately by KoSIT) and is compiled eagerly at startup;
 * [EXTENDED] is the **lazy sub-layer**: nothing under `extended/` is ever opened unless a caller
 * actually requests it (same principle as `B2bCodeList`'s EXTENDED value file).
 */
enum class CiiSchematronProfile(
    internal val resourceDir: String,
    internal val stylesheetName: String,
    internal val codedbName: String,
) {
    EN16931("en16931", "FACTUR-X_EN16931.xslt", "FACTUR-X_EN16931_codedb.xml"),
    EXTENDED("extended", "FACTUR-X_EXTENDED.xslt", "FACTUR-X_EXTENDED_codedb.xml"),
}

/**
 * The guideline URN
 * (`rsm:ExchangedDocumentContext/ram:GuidelineSpecifiedDocumentContextParameter/ram:ID`) is the
 * document's own authoritative statement of which e-invoice standard/profile it was produced
 * under; [CiiSchematronValidator] routes on it rather than trusting a caller-supplied
 * default. Mustang's `Profiles` class (confirmed against the `org.mustangproject:library:2.25.0`
 * jar directly — no resource file ships these, they are compiled-in string constants) emits the
 * bare [EN16931] URN for our own "EN16931" export profile
 * (de.littletrotter.xpdf.impl.XmlExportServiceImpl.resolveProfile — no VAT id/Leitweg-ID) and
 * the XRechnung CIUS URN for our "XRechnung" export profile (VAT id/Leitweg-ID present) — **both
 * are real, current outputs of our own generation path**, not a hypothetical.
 *
 * [EXTENDED] carries the two guideline URNs the EXTENDED codedb's own `cl id="1"` enumeration
 * permits (`FACTUR-X_EXTENDED_codedb.xml`, verified byte-for-byte against the official ZUGFeRD
 * 2.5.2 package by `CiiSchematronResourceDriftTest`) — mirrored here as literal
 * constants rather than read at routing time, so that deciding *whether* a document is EXTENDED
 * never itself opens the EXTENDED codedb resource for an EN16931/XRechnung document (the lazy
 * sub-layer must stay untouched for those).
 */
private object GuidelineUrn {
    /** Bare EN16931, the only value the EN16931 codedb's own `cl id="1"` permits. */
    const val EN16931: String = "urn:cen.eu:en16931:2017"

    /** The EXTENDED codedb's own `cl id="1"` enumeration — Factur-X's and ZUGFeRD's own branding
     *  of the same profile. */
    val EXTENDED: Set<String> = setOf(
        "urn:cen.eu:en16931:2017#conformant#urn:factur-x.eu:1p0:extended",
        "urn:cen.eu:en16931:2017#conformant#urn:zugferd.de:2p0:extended",
    )

    /** XRechnung is a CIUS *on top of* EN16931 (`#compliant#urn:xeinkauf.de:kosit:xrechnung_*`)
     *  — its own profile rules are the KoSIT stage's domain (the XRechnung 3.0.2 scenario), not
     *  this validator's; matched loosely (substring, not the exact `_3.0` suffix) so a future
     *  XRechnung minor-version bump still routes correctly without a code change here. */
    fun isXRechnungCius(urn: String): Boolean = urn.contains("xrechnung", ignoreCase = true)
}

/**
 * Runs the official Factur-X 1.09.2 Schematron (compiled to XSLT 2.0 from the official
 * ZUGFeRD 2.5.2 package, embedded byte-identical under
 * `kotlin-xpdf/src/main/resources/schematron/facturx/` — see that directory's own README.md for
 * provenance and `CiiSchematronResourceDriftTest` for the byte-equality guard). Closes the
 * gap that the generation-path validation chain (`CiiXsdValidator` → `EmbeddedKositValidator` →
 * veraPDF → LLM check) never ran the Factur-X profile rules we
 * actually generate under: the profile URN, the `FX-SCH-A-*` structural asserts, the code lists
 * in their Factur-X binding, attachment MIME rules.
 *
 * ## Precondition — why this runs right after the XSD stage (mirrors de.littletrotter.xpdf.impl.CiiXsdValidator's own KDoc)
 *
 * A Schematron pass over a document that is not even schema-valid CII produces meaningless
 * follow-on noise: missing elements the schema already flagged, XPath contexts that never fire
 * the way the rule set expects. de.littletrotter.xpdf.impl.PdfExportServiceImpl only ever calls
 * [validate] once stage 1 (`SYNTAX`/CII-XSD) has actually reported [KositOutcome.VALID] — chain
 * order is XSD → Factur-X Schematron → KoSIT (XRechnung CIUS context) → veraPDF → LLM, documented
 * at that call site.
 *
 * ## Saxon, explicitly — never the JAXP default factory
 *
 * The compiled Schematron is XSLT 2.0. [net.sf.saxon.TransformerFactoryImpl] — Saxon-HE's own
 * concrete `javax.xml.transform.TransformerFactory` implementation (there is no class literally
 * named `net.sf.saxon.TransformerFactory` in Saxon-HE 12.9; `TransformerFactoryImpl` is the real
 * one, registered as the JAXP SPI default too) — is **constructed directly** here, never via
 * `TransformerFactory.newInstance()` (a classpath/system-property lookup that could silently
 * resolve a different JAXP implementation if the classpath ever shifted). Saxon-HE 12.9 is
 * an explicit `kotlin-xpdf` dependency; it compiles and executes both stylesheets without any
 * XSLT 2.0 feature gap.
 *
 * ## Eager EN16931, lazy EXTENDED
 *
 * [en16931Templates] compiles at init time, like
 * de.littletrotter.xpdf.impl.EmbeddedKositValidator.init. [extendedTemplates] is a [Lazy] —
 * compiled on the first [CiiSchematronProfile.EXTENDED] validation only; an EN16931-only run
 * never opens the EXTENDED stylesheet or codedb resource at all. `internal` visibility (not
 * `private`) exists purely so a test can assert `extendedTemplates.isInitialized() == false`
 * without triggering it.
 *
 * ## The `URIResolver` — restricted to exactly one bundled sibling file
 *
 * Both stylesheets resolve their code list via a single relative
 * `document('FACTUR-X_<PROFILE>_codedb.xml')` call (confirmed: exactly one `document()` call per
 * file, no `xsl:import`/`xsl:include`). [BundledCodedbUriResolver] is installed on the
 * `Transformer` fresh for every [validate] call (never shared/mutable factory state — the
 * resolver differs per profile, and `Transformer` instances are not documented thread-safe): it
 * resolves *only* the exact codedb filename that ships as this profile's sibling resource, loaded
 * from the classpath. Anything else — a different filename, a network URI, a filesystem path —
 * is refused with a [TransformerException]. No runtime network access, no arbitrary external
 * resolution beyond the bundled files.
 *
 * ## Profile routing is URN-based, not a hardcoded default (PO correction, 2026-08-29)
 *
 * [validate] takes no profile parameter at all — it reads the document's own guideline URN
 * ([de.littletrotter.shared.extractGuidelineUrn]) and routes accordingly (see [GuidelineUrn]):
 * the bare EN16931 URN validates against [CiiSchematronProfile.EN16931]; either of the two
 * EXTENDED URNs against [CiiSchematronProfile.EXTENDED] (compiled lazily, first use only); an
 * XRechnung CIUS URN is **not this validator's domain at all** — XRechnung's own profile rules
 * are the KoSIT stage's job (the XRechnung 3.0.2 scenario already covers them) — and yields an
 * explicit, non-silent pass-through result (`skipped = true` with a stated reason, `VALID`, never
 * an error); a missing or unrecognized URN is a genuine document defect (an invoice with no
 * declared, or an unparseable, profile is not EN16931/XRechnung/EXTENDED-conformant by
 * definition) and yields an error-severity finding, same as any other failed assert. A
 * hardcoded EN16931-only default would misreport every XRechnung-profile invoice (our other,
 * equally real, generation-path output) as a Factur-X profile violation forever — this is why
 * routing, not a fixed default, is correct here.
 *
 * ## SVRL → structured findings
 *
 * Each `svrl:failed-assert` becomes one [ValidationMessage]: the assert `id` → [ValidationMessage.ruleId],
 * its own English assert text → [ValidationMessage.message] (verbatim — this module stays
 * language-neutral, no German text is produced here), the computed XPath →
 * [ValidationMessage.location]. Every `BT-nn` token in the text is looked up via
 * [B2bTerm.fromCodeOrNull] and carried in [ValidationMessage.businessTerms] as its canonical
 * `officialCode` when recognized, or the raw matched token when not (never dropped, see
 * [extractBusinessTerms]) — the display name resolves via
 * [de.littletrotter.shared.b2b.B2bTermTexts].
 *
 * ## The code-list-violation marker (PO 29.08.2026, "Relation to the upstream gates")
 *
 * A separate check enforces code-list membership at save time and in the `FinalizationGate` — an invoice
 * with a code-list violation should never reach this validator. If one still does, that is a
 * **gate gap**, not an anwender problem: [ValidationMessage.codeListViolation] marks exactly the
 * asserts whose own `test` XPath (the SVRL output carries the original Schematron test expression
 * verbatim as a `test` attribute) contains a `document('...codedb.xml')` lookup — not merely
 * whichever assert happens to carry an `FX-SCH-A-*` id, since that id prefix is also used by
 * plain structural asserts with nothing to do with a code list (e.g. `FX-SCH-A-000027`,
 * `"Element 'ram:SellerTradeParty' must occur exactly 1 times."`). Inspecting the assert's own
 * `test` attribute is the only reliable way to tell the two apart.
 *
 * CDI note — carries no `@Startup`/`@ApplicationScoped`/`@PostConstruct`: init is a plain
 * method, called directly by `de.littletrotter.xpdf.impl.CiiSchematronValidatorProducer` (the
 * small `@ApplicationScoped` CDI producer bean) instead of firing on `@PostConstruct`.
 */
class CiiSchematronValidator(
    // The classpath lookup [compile] uses to load a stylesheet resource, injected with a
    // real-classloader default so every existing no-arg call site (`CiiSchematronValidator()`,
    // the CDI producer, `PdfExportServiceImpl`'s default param) is unaffected. A test can pass a
    // lookup that returns null to make [init]'s `catch` arm reachable without corrupting the real
    // bundled classpath resource.
    private val stylesheetLookup: (String) -> java.io.InputStream? =
        { resource -> CiiSchematronValidator::class.java.classLoader.getResourceAsStream(resource) },
) {

    private val transformerFactory: TransformerFactoryImpl = TransformerFactoryImpl()

    private lateinit var en16931Templates: Templates

    internal val extendedTemplates: Lazy<Templates> = lazy { compile(CiiSchematronProfile.EXTENDED) }

    fun init() {
        try {
            en16931Templates = compile(CiiSchematronProfile.EN16931)
        } catch (e: TransformerException) {
            throw initFailure(e)
        } catch (e: IllegalStateException) {
            throw initFailure(e)
        }
    }

    private fun initFailure(cause: Exception): IllegalStateException =
        IllegalStateException("Failed to initialize Factur-X Schematron validator (EN16931)", cause)

    /**
     * Validates [ciiXml] against the official Factur-X Schematron — the profile is never a
     * parameter, it is read from the document's own guideline URN (see the class doc's "Profile
     * routing" section). Never throws: an engine failure (stylesheet never compiled, or the
     * transform itself blows up) is [KositOutcome.ERROR], matching every other stage in this
     * pipeline's contract — an infrastructure problem must never be misreported as a fact about
     * the document.
     */
    fun validate(ciiXml: ByteArray): ValidationStageResult {
        val guidelineUrn = extractGuidelineUrn(ciiXml)
        return when {
            guidelineUrn == null -> missingGuidelineResult()
            guidelineUrn == GuidelineUrn.EN16931 -> runProfile(CiiSchematronProfile.EN16931, ciiXml)
            guidelineUrn in GuidelineUrn.EXTENDED -> runProfile(CiiSchematronProfile.EXTENDED, ciiXml)
            GuidelineUrn.isXRechnungCius(guidelineUrn) -> xrechnungPassThroughResult()
            else -> unknownGuidelineResult(guidelineUrn)
        }
    }

    /** No `ram:GuidelineSpecifiedDocumentContextParameter/ram:ID` at all (or not extractable) —
     *  a document with no declared profile cannot be EN16931/XRechnung/EXTENDED-conformant by
     *  definition, so this is a genuine finding, not an engine problem. */
    private fun missingGuidelineResult(): ValidationStageResult = ValidationStageResult(
        STAGE,
        KositOutcome.INVALID,
        findings = listOf(
            ValidationMessage(
                severity = SEVERITY_ERROR,
                message = "No GuidelineSpecifiedDocumentContextParameter/ID found — the document " +
                    "declares no e-invoice guideline/profile at all.",
                ruleId = "FACTURX-SCHEMATRON-NO-GUIDELINE",
                stage = STAGE,
                validator = VALIDATOR_ID,
            ),
        ),
    )

    /** A guideline URN that is neither the bare EN16931 URN, an EXTENDED URN, nor an XRechnung
     *  CIUS URN — an unrecognized profile declaration, again a genuine document-level finding. */
    private fun unknownGuidelineResult(urn: String): ValidationStageResult = ValidationStageResult(
        STAGE,
        KositOutcome.INVALID,
        findings = listOf(
            ValidationMessage(
                severity = SEVERITY_ERROR,
                message = "Unrecognized guideline URN '$urn' — neither the Factur-X EN16931/EXTENDED " +
                    "guideline nor a known XRechnung CIUS URN.",
                ruleId = "FACTURX-SCHEMATRON-UNKNOWN-GUIDELINE",
                stage = STAGE,
                validator = VALIDATOR_ID,
            ),
        ),
    )

    /** An XRechnung CIUS document — not this validator's domain (see class doc). Explicit and
     *  visible (a stated [ValidationStageResult.skipReason]), never a bare/ambiguous skip.
     *  PO 29.08.2026: the reason travels as the stable token `xrechnungCius` (same convention as
     *  `skipValidation`), which the report UI renders as a short German sentence — the earlier
     *  full English explanation with the guideline URN confused anwenders in the validation
     *  report. The URN detail stays available in this KDoc and the routing logic above. */
    private fun xrechnungPassThroughResult(): ValidationStageResult =
        ValidationStageResult(
            STAGE,
            KositOutcome.VALID,
            skipped = true,
            skipReason = SKIP_REASON_XRECHNUNG_CIUS,
        )

    private fun runProfile(profile: CiiSchematronProfile, ciiXml: ByteArray): ValidationStageResult {
        if (profile == CiiSchematronProfile.EN16931 && !this::en16931Templates.isInitialized) {
            return ValidationStageResult(
                STAGE,
                KositOutcome.ERROR,
                findings = listOf(
                    ValidationMessage(
                        severity = SEVERITY_ERROR,
                        message = "Factur-X Schematron validator engine not initialized",
                        stage = STAGE,
                        validator = VALIDATOR_ID,
                    ),
                ),
            )
        }
        return try {
            // An if/else, not a `when` expression over this two-constant enum: an
            // exhaustive `when`-as-expression always compiles a synthetic
            // `else -> throw NoWhenBranchMatchedException()` fallback the JVM verifier requires but
            // no caller can ever reach (both real CiiSchematronProfile constants are handled),
            // which JaCoCo then counts as a permanently missed branch. if/else has no such arm.
            val templates = if (profile == CiiSchematronProfile.EN16931) en16931Templates else extendedTemplates.value
            val svrl = transform(templates, ciiXml, profile)
            val findings = parseSvrlFailedAsserts(svrl).map { toValidationMessage(it) }
            val outcome = if (findings.any { it.severity == SEVERITY_ERROR }) KositOutcome.INVALID else KositOutcome.VALID
            ValidationStageResult(STAGE, outcome, findings)
        } catch (e: TransformerException) {
            engineErrorResult(e)
        } catch (e: SAXException) {
            engineErrorResult(e)
        } catch (e: IOException) {
            engineErrorResult(e)
        } catch (e: IllegalStateException) {
            engineErrorResult(e)
        }
    }

    // Every failure the stylesheet compile, the transform or the SVRL parse can raise ends up here:
    // an engine problem is reported as ERROR, never as a finding about the document.
    private fun engineErrorResult(e: Exception): ValidationStageResult =
        ValidationStageResult(
            STAGE,
            KositOutcome.ERROR,
            findings = listOf(
                ValidationMessage(
                    severity = SEVERITY_ERROR,
                    message = "Factur-X Schematron engine error: ${e.message}",
                    stage = STAGE,
                    validator = VALIDATOR_ID,
                ),
            ),
            engineError = e.message,
        )

    private fun compile(profile: CiiSchematronProfile): Templates {
        val resource = "$SCHEMATRON_ROOT/${profile.resourceDir}/${profile.stylesheetName}"
        val stream = requireStylesheet(stylesheetLookup, resource)
        return stream.use { transformerFactory.newTemplates(StreamSource(it, resource)) }
    }

    private fun transform(templates: Templates, ciiXml: ByteArray, profile: CiiSchematronProfile): String {
        val transformer = templates.newTransformer()
        transformer.uriResolver = BundledCodedbUriResolver(profile)
        val out = StringWriter()
        transformer.transform(StreamSource(ByteArrayInputStream(ciiXml)), StreamResult(out))
        return out.toString()
    }

    /** `internal`, not `private`, so `CiiSvrlParsingTest` can construct edge cases directly
     *  (a missing `test`/`id`/`location`, an assert with no `svrl:text` at all) without needing a
     *  real Schematron mutation to reach every branch of [toValidationMessage] — see that test's
     *  own class doc. */
    /** Maps one parsed [SvrlFailedAssert] (shared parser, see `SvrlFailedAsserts.kt`) onto this
     *  stage's [ValidationMessage]. The `test` attribute is the original Schematron assert
     *  expression, copied verbatim into the report by the compiled stylesheet's own skeleton —
     *  that is what makes the code-list-violation marker possible without re-parsing the
     *  stylesheet itself. `internal`, not `private`, so `CiiSvrlParsingTest` can feed
     *  hand-crafted asserts directly. */
    internal fun toValidationMessage(fa: SvrlFailedAssert): ValidationMessage {
        val businessTerms = extractBusinessTerms(fa.text)
        return ValidationMessage(
            severity = mapSeverityFlag(fa.flag),
            message = fa.text,
            location = fa.location,
            ruleId = fa.id,
            stage = STAGE,
            validator = VALIDATOR_ID,
            businessTerms = businessTerms.takeIf { it.isNotEmpty() },
            // See the class doc's "code-list-violation marker" section — the id prefix alone
            // is not sufficient, only the assert's own test expression is.
            codeListViolation = fa.test?.contains(CODEDB_LOOKUP_MARKER) == true,
        )
    }

    companion object {
        /** The bundled Schematron stylesheet [resource] via [lookup]; a null result is a packaging defect. */
        internal fun requireStylesheet(lookup: (String) -> java.io.InputStream?, resource: String): java.io.InputStream =
            lookup(resource) ?: error("Factur-X Schematron stylesheet not found on classpath: $resource")

        /** The bundled codedb sibling as a [StreamSource]; a missing resource is a [TransformerException] for the resolver. */
        internal fun bundledCodedb(lookup: (String) -> java.io.InputStream?, resourcePath: String): StreamSource =
            StreamSource(lookup(resourcePath) ?: throw TransformerException("Bundled codedb resource not found on classpath: $resourcePath"), resourcePath)

        const val STAGE = "FACTURX_SCHEMATRON"

        /** Stable skip-reason token for the XRechnung pass-through (translated by the report UI). */
        const val SKIP_REASON_XRECHNUNG_CIUS = "xrechnungCius"
        const val VALIDATOR_ID = "facturx-schematron-1.09.2"
        private const val CODEDB_LOOKUP_MARKER = "codedb.xml"
    }
}

/** The `BT-nn` extraction regex — matched against an assert's own English text (e.g.
 *  `"[BR-06]-An Invoice shall contain the Seller name (BT-27)."`). `internal`, not `private`, so
 *  `CiiBusinessTermExtractionTest` can exercise it directly for full branch coverage without
 *  needing a fresh Schematron fixture for every edge case (known code, unknown code, duplicate
 *  mention, no mention at all). */
internal val businessTermPattern: Regex = Regex("BT-(\\d+)")

/** Extracts every `BT-nn` token in [text] as its canonical [B2bTerm.officialCode] when
 *  [B2bTerm.fromCodeOrNull] recognizes the number, or the raw matched token when it does not —
 *  an unrecognized code is still evidence and is never dropped. Order of first appearance,
 *  de-duplicated (a single assert text can reference the same BT twice). */
internal fun extractBusinessTerms(text: String): List<String> =
    businessTermPattern.findAll(text)
        .map { match -> B2bTerm.fromCodeOrNull(match.groupValues[1].toInt())?.officialCode ?: match.value }
        .distinct()
        .toList()

/**
 * Restricts Schematron `document()` resolution during a transform to the single bundled
 * codedb sibling file for [profile]. Never resolves anything else — no other filename, no
 * network URI, no filesystem path outside the classpath resource this profile ships with (see
 * [CiiSchematronValidator]'s own class doc, "The `URIResolver`" section). `internal`, not
 * `private`, purely so `CiiSchematronUriResolverTest` can exercise the refusal branch
 * directly without engineering a Schematron fixture that calls `document()` with a foreign URI.
 */
internal class BundledCodedbUriResolver(private val profile: CiiSchematronProfile) : URIResolver {
    override fun resolve(href: String, base: String?): Source {
        val requestedName = href.substringAfterLast('/')
        if (requestedName != profile.codedbName) {
            throw TransformerException(
                "Refusing to resolve document('$href') (base '$base') for the Factur-X " +
                    "${profile.name} Schematron — only the bundled '${profile.codedbName}' sibling " +
                    "is allowed here (no external document() access).",
            )
        }
        val resourcePath = "$SCHEMATRON_ROOT/${profile.resourceDir}/${profile.codedbName}"
        return CiiSchematronValidator.bundledCodedb(javaClass.classLoader::getResourceAsStream, resourcePath)
    }
}
