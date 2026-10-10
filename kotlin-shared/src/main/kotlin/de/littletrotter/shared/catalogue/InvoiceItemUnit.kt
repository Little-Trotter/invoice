package de.littletrotter.shared.catalogue

/**
 * Groups [InvoiceItemUnit] values by physical dimension for display (e.g. the reference-data
 * "Einheiten" tab). Unlike an industry/trade grouping, this is unambiguous — a unit measures
 * exactly one kind of quantity regardless of who bills it.
 *
 * [printsSymbolOnInvoice] marks the physical measures (length/area/volume/weight): invoices
 * conventionally abbreviate these to their short symbol (m², ha, m³, kg, …) regardless of
 * document language, since both German and English readers recognise the symbol. Time, count
 * and currency units are not physical measures in this sense and keep their language-dependent
 * word form (Stunden/hours, Tage, Stück, Pauschale, Anfahrt, Euro).
 */
enum class InvoiceItemUnitDomain(val labelKey: String, val printsSymbolOnInvoice: Boolean = false) {
    TIME("unit.domain.time"),
    LENGTH("unit.domain.length", printsSymbolOnInvoice = true),
    AREA("unit.domain.area", printsSymbolOnInvoice = true),
    VOLUME("unit.domain.volume", printsSymbolOnInvoice = true),
    WEIGHT("unit.domain.weight", printsSymbolOnInvoice = true),
    /** Not a physical measure: counted pieces, lump sums, trip charges. */
    COUNT("unit.domain.count"),
    CURRENCY("unit.domain.currency"),
}

/**
 * [uneceCode] is the UN/ECE Recommendation 20 (+ Rec 21 extension) unit code required by
 * ZUGFeRD/XRechnung's BR-CL-23 (Task F). Persisted by ordinal via [de.littletrotter.domain.shared.converters.InvoiceItemUnitConverter] —
 * **only append new constants at the end, never reorder or remove existing ones** (ordinal
 * persistence means reordering silently corrupts every existing row).
 *
 * FLAT_RATE/APPROACH/currency units have no direct Rec 20 unit-of-measure equivalent (a lump
 * sum, a trip, or a currency amount is not a measurable quantity); C62 ("one") is the
 * standard, unambiguously valid fallback for countable non-measures, deliberately not Rec
 * 21's "LS" (lump sum) since it is unproven that the XRechnung code list accepts it here.
 *
 * LINEAR_METER exists alongside METER for Handwerk trades (Schreiner/Dachdecker/Maler) that
 * conventionally invoice lengths as "lfm" (laufender Meter) distinctly from area/volume —
 * same physical unit and the same UN/ECE code (MTR), different display label only.
 */
/** One language's (singular, plural) display form — bundled so [InvoiceItemUnit]'s constructor
 *  takes one argument per language instead of two, keeping it under detekt's
 *  `LongParameterList` `constructorThreshold`. */
private data class UnitLabel(val singular: String, val plural: String) {
    fun form(plural: Boolean): String = if (plural) this.plural else singular
}

enum class InvoiceItemUnit(
    val labelKey: String,
    val symbol: String,
    val uneceCode: String,
    val domain: InvoiceItemUnitDomain,
    private val german: UnitLabel,
    private val english: UnitLabel,
) {
    // Original 14 values — declaration order is load-bearing (ordinal persistence), do not
    // reorder. Grouped below by domain via comments/spacing for readability only; the
    // reference-data "Einheiten" tab (UnitsTab.kt) additionally sorts by domain for display.

    // — TIME —
    HOUR("unit.hour", "h", "HUR", InvoiceItemUnitDomain.TIME, UnitLabel("Stunde", "Stunden"), UnitLabel("hour", "hours")),
    DAY("unit.day", "d", "DAY", InvoiceItemUnitDomain.TIME, UnitLabel("Tag", "Tage"), UnitLabel("day", "days")),
    WEEK("unit.week", "Wo", "WEE", InvoiceItemUnitDomain.TIME, UnitLabel("Woche", "Wochen"), UnitLabel("week", "weeks")),
    MONTH("unit.month", "Mon", "MON", InvoiceItemUnitDomain.TIME, UnitLabel("Monat", "Monate"), UnitLabel("month", "months")),

    // — COUNT (not a physical measure) —
    PIECE("unit.piece", "Stk", "H87", InvoiceItemUnitDomain.COUNT, UnitLabel("Stück", "Stück"), UnitLabel("piece", "pieces")),

    // — LENGTH —
    METER("unit.meter", "m", "MTR", InvoiceItemUnitDomain.LENGTH, UnitLabel("Meter", "Meter"), UnitLabel("meter", "meters")),

    // — AREA —
    SQUARE_METER("unit.squareMeter", "m²", "MTK", InvoiceItemUnitDomain.AREA, UnitLabel("Quadratmeter", "Quadratmeter"), UnitLabel("square meter", "square meters")),

    // — VOLUME —
    CUBIC_METER("unit.cubicMeter", "m³", "MTQ", InvoiceItemUnitDomain.VOLUME, UnitLabel("Kubikmeter", "Kubikmeter"), UnitLabel("cubic meter", "cubic meters")),

    // — LENGTH —
    KILOMETER("unit.kilometer", "km", "KMT", InvoiceItemUnitDomain.LENGTH, UnitLabel("Kilometer", "Kilometer"), UnitLabel("kilometer", "kilometers")),

    // — VOLUME —
    LITER("unit.liter", "l", "LTR", InvoiceItemUnitDomain.VOLUME, UnitLabel("Liter", "Liter"), UnitLabel("liter", "liters")),

    // — WEIGHT —
    KILOGRAM("unit.kilogram", "kg", "KGM", InvoiceItemUnitDomain.WEIGHT, UnitLabel("Kilogramm", "Kilogramm"), UnitLabel("kilogram", "kilograms")),

    // — COUNT (not a physical measure) —
    FLAT_RATE("unit.flatRate", "Pausch", "C62", InvoiceItemUnitDomain.COUNT, UnitLabel("Pauschale", "Pauschalen"), UnitLabel("flat rate", "flat rates")),
    APPROACH("unit.approach", "Anf", "C62", InvoiceItemUnitDomain.COUNT, UnitLabel("Anfahrt", "Anfahrten"), UnitLabel("approach", "approaches")),

    // — CURRENCY —
    EURO("unit.euro", "€", "C62", InvoiceItemUnitDomain.CURRENCY, UnitLabel("Euro", "Euro"), UnitLabel("Euro", "Euro")),

    // Appended (Task F, extended scope): Lebensmittel/Handwerk/Landwirtschaft.

    // — WEIGHT —
    GRAM("unit.gram", "g", "GRM", InvoiceItemUnitDomain.WEIGHT, UnitLabel("Gramm", "Gramm"), UnitLabel("gram", "grams")),

    // — LENGTH —
    LINEAR_METER("unit.linearMeter", "lfm", "MTR", InvoiceItemUnitDomain.LENGTH, UnitLabel("laufender Meter", "laufende Meter"), UnitLabel("linear meter", "linear meters")),

    // — AREA —
    // Pinning finding: "HAR" is not an official UN/ECE Rec 20 unit code at all (absent from
    // the codedb) — the correct code is "HA". Safe to correct: InvoiceItemUnitConverter persists
    // by ordinal, never by uneceCode, so no stored invoice_item/article row is affected; only the
    // Factur-X export literal changes (from a silently invalid code to a valid one).
    HECTARE("unit.hectare", "ha", "HA", InvoiceItemUnitDomain.AREA, UnitLabel("Hektar", "Hektar"), UnitLabel("hectare", "hectares")),

    // — WEIGHT —
    TONNE("unit.tonne", "t", "TNE", InvoiceItemUnitDomain.WEIGHT, UnitLabel("Tonne", "Tonnen"), UnitLabel("tonne", "tonnes")),

    // — VOLUME —
    // "milliliter"/"milliliters" (American spelling), not the British "millilitre" — matching
    // the existing liter/meter/kilometer English labels above, all American in this enum.
    MILLILITER("unit.milliliter", "ml", "MLT", InvoiceItemUnitDomain.VOLUME, UnitLabel("Milliliter", "Milliliter"), UnitLabel("milliliter", "milliliters")),

    // CHF/GBP/USD deliberately NOT added here: Money/totals/ZUGFeRD CurrencyCode are EUR-only
    // today, so a currency "unit" would be a cosmetic label while the invoice still bills in
    // EUR underneath — actively misleading. Real multi-currency support is its own task; see
    // .
    ;

    /**
     * Plain German label for human-facing display (PDF Positionstabelle — the UN/ECE
     * [uneceCode] is machine-readable, e.g. "H87"/"C62"/"MTR", and ambiguous besides: several
     * units share the same code). Deliberately NOT resolved via the UI-layer
     * `de.littletrotter.ui.i18n.TranslationService` — the application layer must not depend on
     * the UI layer (see `Repositories.kt`'s hexagonal-architecture note), and the PDF template
     * is hardcoded German throughout regardless of `invoice.languageId`. Kept in sync by hand
     * with `unit.*` in messages.properties. Per-constant fields instead of a `when` — one
     * [UnitLabel] travels with each value, no branch per unit.
     */
    fun germanLabel(plural: Boolean): String = german.form(plural)

    /**
     * English label for human-facing display (international/reverse charge PDF).
     */
    fun englishLabel(plural: Boolean): String = english.form(plural)

    /**
     * Label for the invoice PDF's Positionstabelle/workbook export: physical measures
     * ([InvoiceItemUnitDomain.printsSymbolOnInvoice]) print as their short symbol, invariant
     * of [english] and [plural] — invoices conventionally abbreviate measures, and the symbol
     * reads the same in either language. Every other unit (time, count, currency) falls back
     * to the existing language word form via [englishLabel]/[germanLabel].
     */
    fun invoiceLabel(english: Boolean, plural: Boolean): String =
        if (domain.printsSymbolOnInvoice) symbol else if (english) englishLabel(plural) else germanLabel(plural)
}
