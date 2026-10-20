# Factur-X 1.09.2 official Schematron (compiled XSLT 2.0)

BTE-5 — closes the gap that the generation-path validation chain never ran the official
Factur-X profile rules (profile URN, `FX-SCH-A-*` structural asserts, code-list membership,
attachment MIME rules) even though the compiled Schematron ships in-repo already.

## Provenance

| | |
|---|---|
| Source | `docs/ZUGFeRD_2.5.2_EN/Schema/3_Factur-X_1.09.2_EN16931/_XSLT_EN16931/` and `.../4_Factur-X_1.09.2_EXTENDED/_XSLT_EXTENDED/` |
| Release | `1.09.2` |
| Effective date | `2026-09-01` (per the XSLT files' own release-metadata comment) |
| Copied on | 2026-08-29, byte-for-byte (`cmp` verified; `FacturXSchematronResourceDriftTest` re-verifies on every build) |

## Layout

Each profile directory keeps its two files as siblings, exactly as they ship upstream:

- `en16931/FACTUR-X_EN16931.xslt` + `en16931/FACTUR-X_EN16931_codedb.xml`
- `extended/FACTUR-X_EXTENDED.xslt` + `extended/FACTUR-X_EXTENDED_codedb.xml`

The stylesheet resolves its codedb via a single relative `document('FACTUR-X_<PROFILE>_codedb.xml')`
call (confirmed: exactly one `document()` call per file, no `xsl:import`/`xsl:include`) — the pair
must stay siblings. [`CiiSchematronValidator`](../../../kotlin/de/littletrotter/xpdf/validation/CiiSchematronValidator.kt)
runs each stylesheet via `net.sf.saxon.TransformerFactoryImpl` (Saxon-HE, declared
explicitly in `kotlin-xpdf/pom.xml`) with a `URIResolver` restricted to
exactly that one bundled codedb file — no other document(), no network, no filesystem access
beyond the classpath.

## Eager vs. lazy

EN16931 is the internal export profile (`XmlExportServiceImpl.resolveProfile`) and is compiled once at
application startup, like `EmbeddedKositValidator.init`. EXTENDED is not an export profile
generated today — its `Templates` are compiled lazily, on the first EXTENDED-profile validation
call only, so an EN16931-only run never touches the EXTENDED resources at all (same principle as
`B2bCodeList`'s EXTENDED value file, BTE-3).
