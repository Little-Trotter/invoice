# Factur-X codedb test fixtures

`FACTUR-X_EN16931_codedb.xml` and `FACTUR-X_EXTENDED_codedb.xml` are verbatim copies of the
official ZUGFeRD/Factur-X release artifacts distributed for exactly this implementation use (the
same files the official Schematron loads via `document()`).

Origin paths (release 1.09.2, effective 2026-09-01):

- `docs/ZUGFeRD_2.5.2_EN/Schema/3_Factur-X_1.09.2_EN16931/FACTUR-X_EN16931_codedb.xml`
- `docs/ZUGFeRD_2.5.2_EN/Schema/4_Factur-X_1.09.2_EXTENDED/FACTUR-X_EXTENDED_codedb.xml`

`B2bCodeListDriftTest` parses both files independently of `scripts/codegen/facturx_codedb_gen.py` and
compares the result against the generated `B2bCodeListValuesEn16931`/`B2bCodeListValuesExtended`
constants — a ZUGFeRD release update copied here without re-running the generator fails that test.
