package de.littletrotter.xpdf.impl

import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.xpdf.impl.mustang.MustangExportModelMapper
import de.littletrotter.xpdf.impl.mustang.MustangTransactionBuilder
import de.littletrotter.xpdf.impl.mustang.WellFormedPullProvider
import org.mustangproject.XMLTools
import org.mustangproject.ZUGFeRD.IExportableTransaction
import org.mustangproject.ZUGFeRD.Profiles
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * [documentStore] is nullable only so the many existing no-arg test call sites
 * (`XmlExportServiceImpl()`) keep compiling; CDI always injects the real bean in production.
 * `null` (or a load failure) just means receipts are skipped from the embedded-attachment
 * group rather than failing the whole export.
 *
 * The primary constructor deliberately has no default value: a default turns into a second,
 * synthetic no-arg constructor at the bytecode level, which Quarkus ArC then rejects as
 * "multiple @Inject constructors". The no-arg test call site is covered by the secondary
 * constructor below instead (same pattern as [PdfExportServiceImpl]).
 */
@ApplicationScoped
class XmlExportServiceImpl @Inject constructor(
  private val documentStore: DocumentStore?,
) {
  /** Secondary constructor for the many existing no-arg test call sites. */
  internal constructor() : this(null)

  fun generate(invoice: InvoiceDto): ByteArray {
    val provider = WellFormedPullProvider("Rechnung ${invoice.invoiceNumber.value.ifBlank { "ohne Nummer" }}")
    provider.profile = Profiles.getByName(resolveProfile(invoice))
    provider.generateXML(buildTransaction(invoice))
    val xmlBytes = provider.xml
    // The previous try/catch here (log-and-fall-back-to-xmlBytes) wrapped only total,
    // non-throwing string/regex operations (enrichInvoiceXml, fixXmlDeclaration) over an
    // already-successfully-generated xmlStr and Kotlin's non-null InvoiceDto fields — every
    // field either helper reads was already dereferenced earlier in this same call
    // (buildTransaction/resolveProfile, both before this point), so no new NPE surface exists,
    // and none of Regex.find/replace/replaceFirst/substring/indexOf throws for arbitrary
    // well-formed String input. Provably unreachable under the current implementation; deleted
    // per the dead-code rule rather than faked with a coverage-only test.
    val xmlStr = String(xmlBytes, Charsets.UTF_8)
    val enriched = enrichInvoiceXml(xmlStr, invoice)
    val fixed = fixXmlDeclaration(enriched)
    return fixed.toByteArray(Charsets.UTF_8)
  }

  /**
   * Prefers the profile id kotlin-invoice already resolved for this invoice
   * (`resolveExportProfileId`, read by both the finalization
   * gate and `DocumentJobPublisher`) so the gate and the actual export can never disagree. Only
   * a legacy message with no `profileId` (predating this resolution) falls back to re-deriving it locally
   * here — that local `hasVatId → "XRechnung"/"EN16931"` decision has no dependency edge onto
   * kotlin-invoice, so it stays a duplicate of `resolveExportProfileId`, kept only for backward
   * compatibility. Fixing the profile *selection logic* itself (it's fachlich fragwürdig — see
   * Mustang-Gap-Analyse) is a separate concern from this fallback.
   */
  fun resolveProfile(invoice: InvoiceDto): String {
    invoice.meta.profileId?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
    val hasLeitweg = !invoice.recipient?.vatId.isNullOrBlank() || !invoice.senderProfile?.vatId.isNullOrBlank()
    return if (hasLeitweg) "XRechnung" else "EN16931"
  }

  // The only place this class still names a Mustang type; everything that used to be
  // inline here (TradeParty/Item/Product/…) now lives behind MustangExportModel:
  // MustangExportModelMapper translates InvoiceDto -> the model (no Mustang import),
  // MustangTransactionBuilder translates the model -> IExportableTransaction (all the Mustang
  // construction imports live there, not here). Kept as a named method (not inlined into
  // [generate]) because existing tests reach it via reflection to inspect the built
  // transaction directly — a thin delegation, not real Mustang-building logic.
  private fun buildTransaction(invoice: InvoiceDto): IExportableTransaction =
    MustangTransactionBuilder(documentStore).build(MustangExportModelMapper.toModel(invoice, resolveProfile(invoice)))

  private fun fixXmlDeclaration(xml: String): String {
    val declRegex = Regex("(?s)<\\?xml\\s+([^?>]*)\\?>")
    val m = declRegex.find(xml) ?: return xml
    var attrs = m.groupValues[1]
    val versionUnquoted = Regex("version\\s*=\\s*([^\"'\\s]+)")
    attrs = versionUnquoted.replace(attrs) { mr -> "version=\"${mr.groupValues[1]}\"" }
    val encodingUnquoted = Regex("encoding\\s*=\\s*([^\"'\\s]+)")
    attrs = encodingUnquoted.replace(attrs) { mr -> "encoding=\"${mr.groupValues[1]}\"" }
    val fixedDecl = "<?xml $attrs?>"
    return xml.replaceRange(m.range, fixedDecl)
  }

  private fun enrichInvoiceXml(xml: String, invoice: InvoiceDto): String {
    var result = xml
    invoice.meta.languageId?.trim()?.takeIf { it.isNotBlank() }?.let { languageId ->
      if (!result.contains("<ram:LanguageID>")) {
        result = insertIntoExchangedDocument(result, "<ram:LanguageID>${XMLTools.encodeXML(languageId)}</ram:LanguageID>")
      }
    }
    invoice.meta.documentStatusReasonCode?.trim()?.takeIf { it.isNotBlank() }?.let { reasonCode ->
      if (!result.contains("<ram:DocumentStatusReasonCode>")) {
        result = insertIntoExchangedDocument(result, "<ram:DocumentStatusReasonCode>${XMLTools.encodeXML(reasonCode)}</ram:DocumentStatusReasonCode>")
      }
    }
    // Mustang has no header-level BT-19 setter (see Bt19HeaderInjector's own KDoc and
    // MustangExportModel's class KDoc); injected here as the last fixup, same seam as the two
    // above.
    result = Bt19HeaderInjector.inject(result, invoice)
    result = stripEmptyPaymentTermsWrapper(result)
    return result
  }

  /**
   * PO decision 2 (2026-09-02) — a cancellation document carries no payment-terms text at all. Mustang
   * 2.25.0's writer (`ZUGFeRD2PullProvider.generateXML`) always emits a `SpecifiedTradePaymentTerms`
   * wrapper whenever `getPaymentTerms()` is null AND `getTradeSettlement()` is non-null — and the
   * latter can never be null here, since BR-DE-1 requires BG-16 payment means on every document
   * (`MustangTransactionBuilder`'s `getTradeSettlement()` always returns real bank details or the
   * TypeCode-42 fallback). With no description, no due date and no debit-type settlement to write
   * inside it (bank-transfer/credit-transfer payment means is written separately at the header
   * level, not inside this wrapper), the result is a syntactically empty
   * `<ram:SpecifiedTradePaymentTerms></ram:SpecifiedTradePaymentTerms>` — confirmed with a real
   * KoSIT run to violate PEPPOL-EN16931-R008 ("Document MUST not contain empty elements"). There
   * is no lever on the [de.littletrotter.xpdf.impl.mustang.MustangExportModel]/transaction-builder
   * side that avoids this without also dropping the mandatory BG-16 data, so the empty wrapper is
   * stripped here instead — the same fixup seam [Bt19HeaderInjector] uses for a different Mustang
   * gap. Safe unconditionally, not just for a cancellation: the wrapper can only ever be genuinely empty
   * (no Description, no DueDateDateTime, no discount terms) when there was truly nothing to say,
   * and KoSIT never accepts an empty element regardless of which document produced it.
   *
   * The regex matches both spellings of "empty". `ZUGFeRD2PullProvider.generateXML` itself only
   * ever writes the open/close pair (plain `StringBuilder` concatenation), but `getXML()` (what
   * `provider.xml`/[generate] actually reads, not the raw concatenated string) re-parses that
   * string with dom4j and re-serializes it through `XMLWriter`/`OutputFormat.createPrettyPrint()`
   * — confirmed live (a throwaway XML dump of a generated cancellation document) that this step
   * collapses a genuinely childless element to the self-closing `<ram:SpecifiedTradePaymentTerms/>`
   * — the only spelling this pipeline actually produces for this element, but matching the
   * open/close pair too costs nothing and guards against a future Mustang version change.
   */
  private fun stripEmptyPaymentTermsWrapper(xml: String): String =
    xml.replace(
      Regex("<ram:SpecifiedTradePaymentTerms\\s*/>|<ram:SpecifiedTradePaymentTerms>\\s*</ram:SpecifiedTradePaymentTerms>"),
      "",
    )

  private fun insertIntoExchangedDocument(xml: String, fragment: String): String {
    val pattern = Regex("(<ram:ExchangedDocument>.*?</ram:TypeCode>)", RegexOption.DOT_MATCHES_ALL)
    val match = pattern.find(xml)
    return if (match != null) {
      xml.replaceFirst(match.value, "${match.value}$fragment")
    } else {
      xml.replaceFirst("<ram:ExchangedDocument>", "<ram:ExchangedDocument>$fragment")
    }
  }
}
