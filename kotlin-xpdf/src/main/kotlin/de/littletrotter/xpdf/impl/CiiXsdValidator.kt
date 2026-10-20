package de.littletrotter.xpdf.impl

import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.ValidationMessage
import io.quarkus.runtime.Startup
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXParseException
import java.io.ByteArrayInputStream
import java.io.File
import javax.xml.XMLConstants
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.Schema
import javax.xml.validation.SchemaFactory

/**
 * Well-formedness + the official UN/CEFACT CII XSD, run *before* KoSIT so a plain
 * syntax error is named as one instead of surfacing as a confusing Schematron follow-on error.
 * The four CII XSD files are the exact ones already bundled inside
 * [EmbeddedKositValidator]'s scenario ZIP (`resources/cii/16b/xsd/` — four XSD files —
 * `xrechnung-3.0.2-validator-configuration-2026-01-31.zip`), copied out to their own versioned
 * resource path (`cii-xsd/`) so this stage doesn't depend on KoSIT's ZIP-extraction lifecycle —
 * same UN/CEFACT CII D16B schema, no new external fetch (no network access at
 * runtime, no `latest` artifacts).
 *
 * XXE hardening mirrors [de.littletrotter.xpdf.LlmCorrectionSupport.tryParseXml]: external
 * entities and DTD loading are disabled on the underlying JAXP `SchemaFactory`.
 */
@Startup
@ApplicationScoped
class CiiXsdValidator {

    private lateinit var schema: Schema

    @PostConstruct
    fun init() = initSchema()

    /** Test seam — [init] always delegates here with the default (real classloader)
     *  [resourceLookup]; a test can hand in a failing one to exercise this method's own
     *  "engine setup failed" wrapping without touching the real bundled XSD files, the same
     *  pattern [extractXsdResources] documents for [MustangVersion.resolve]'s equivalent
     *  problem. */
    internal fun initSchema(
        resourceLookup: (String) -> java.io.InputStream? = { path -> javaClass.classLoader.getResourceAsStream(path) },
    ) {
        try {
            val dir = extractXsdResources(resourceLookup)
            try {
                schema = compileSchema(dir)
            } finally {
                // The compiled Schema is fully in memory; the unpacked files are dead weight.
                ScratchDirectories.deleteRecursively(dir.toPath())
            }
            // IllegalStateException from extractXsdResources (missing bundled resource) is
            // already the right shape for an engine-setup failure — it propagates unwrapped.
        } catch (e: java.io.IOException) {
            throw IllegalStateException("Failed to initialize CII XSD validator", e)
        } catch (e: org.xml.sax.SAXException) {
            throw IllegalStateException("Failed to initialize CII XSD validator", e)
        }
    }

    private fun compileSchema(dir: File): Schema {
        val factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).apply {
            // XXE hardening (same features LlmCorrectionSupport.tryParseXml disables) —
            // a SchemaFactory additionally needs the ACCESS_EXTERNAL_* properties, since
            // W3C XML Schema import/include resolution is a separate attack surface from
            // DOM/SAX external-entity expansion.
            setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "file")
        }
        val root = File(dir, ROOT_XSD)
        return factory.newSchema(StreamSource(root))
    }

    /** Validates [xml] against the CII D16B schema. Never throws. An *engine* failure (schema
     *  never loaded) is `ERROR`, matching [EmbeddedKositValidator]'s contract — never
     *  `VALID`/`INVALID`, since that would misreport an infrastructure problem as a fact about
     *  the document. A malformed *document* (not well-formed, or a schema violation the
     *  [ErrorHandler] never got to report because the parser aborted first) is `INVALID` — a
     *  document problem is exactly what stage 1 exists to name. */
    fun validate(xml: String): CiiXsdValidationResult {
        if (!this::schema.isInitialized) {
            return CiiXsdValidationResult(
                outcome = KositOutcome.ERROR,
                findings = listOf(
                    ValidationMessage(
                        severity = "ERROR",
                        message = "CII XSD validator engine not initialized",
                        stage = "SYNTAX",
                        validator = "xsd",
                    )
                ),
            )
        }
        // javax.xml.validation.Validator.validate(Source) declares both SAXException
        // and IOException, but the [Source] here always wraps a fresh in-memory
        // ByteArrayInputStream over [xml]: its read() never throws IOException (JDK contract —
        // an in-memory buffer has no I/O to fail), so only the SAXException branch below is ever
        // actually reachable from this call site. No `catch (e: java.io.IOException)` here any
        // more — jacoco-verified dead code, unlike [initSchema]'s own IOException catch,
        // which real bundled-resource-extraction I/O failures do reach (see that method's tests).
        return try {
            val findings = mutableListOf<ValidationMessage>()
            val validator = schema.newValidator().apply {
                setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
                errorHandler = CollectingErrorHandler(findings)
            }
            validator.validate(StreamSource(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8))))
            val outcome = if (findings.any { it.severity == "ERROR" }) KositOutcome.INVALID else KositOutcome.VALID
            CiiXsdValidationResult(outcome, findings)
        } catch (e: org.xml.sax.SAXException) {
            // A parse failure so severe the ErrorHandler never even ran (e.g. not well-formed
            // XML at all) still needs to surface as a finding, not silently become ERROR with
            // an empty list.
            CiiXsdValidationResult(
                outcome = KositOutcome.INVALID,
                findings = listOf(
                    ValidationMessage(
                        severity = "ERROR",
                        message = "Not well-formed / schema violation: ${e.message}",
                        stage = "SYNTAX",
                        validator = "xsd",
                    )
                ),
            )
        }
    }

    /** Test seam — [init] always calls the default (real classloader) overload; a test can hand
     *  in [resourceLookup] to exercise the missing-classpath-resource fallback below without
     *  touching the real bundled XSD files, the same pattern [MustangVersion.resolve]'s own KDoc
     *  documents for the equivalent problem. */
    internal fun extractXsdResources(
        resourceLookup: (String) -> java.io.InputStream? = { path -> javaClass.classLoader.getResourceAsStream(path) },
    ): File {
        val target = ScratchDirectories.create("cii-xsd").toFile()
        for (name in XSD_RESOURCES) {
            val resource = resourceLookup("$RESOURCE_DIR/$name")
                ?: error("CII XSD resource $RESOURCE_DIR/$name not found on classpath")
            resource.use { input -> File(target, name).outputStream().use { input.copyTo(it) } }
        }
        return target
    }

    companion object {
        private const val RESOURCE_DIR = "cii-xsd"
        private const val ROOT_XSD = "CrossIndustryInvoice_100pD16B.xsd"
        private val XSD_RESOURCES = listOf(
            ROOT_XSD,
            "CrossIndustryInvoice_QualifiedDataType_100pD16B.xsd",
            "CrossIndustryInvoice_ReusableAggregateBusinessInformationEntity_100pD16B.xsd",
            "CrossIndustryInvoice_UnqualifiedDataType_100pD16B.xsd",
        )
    }
}

data class CiiXsdValidationResult(
    val outcome: KositOutcome,
    val findings: List<ValidationMessage>,
)

/** Named (rather than anonymous) so a unit test can drive `warning`/`error`/`fatalError` directly —
 *  a real XSD validation run essentially never reaches the `warning` callback (Xerces schema
 *  validation reports schema violations as errors, not warnings), so this is the only way to test
 *  it without depending on that engine-specific, otherwise-unreachable-in-practice behavior. */
internal class CollectingErrorHandler(private val findings: MutableList<ValidationMessage>) : ErrorHandler {
    override fun warning(e: SAXParseException) = record("WARNING", e)

    override fun error(e: SAXParseException) = record("ERROR", e)

    override fun fatalError(e: SAXParseException) = record("ERROR", e)

    private fun record(severity: String, e: SAXParseException) {
        findings.add(
            ValidationMessage(
                severity = severity,
                message = e.message ?: "XSD violation",
                location = "row ${e.lineNumber}, col ${e.columnNumber}",
                stage = "SYNTAX",
                validator = "xsd",
            )
        )
    }
}
