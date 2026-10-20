-- =============================================================================
-- Little Trotter Invoice — seed baseline (single seed migration)
-- =============================================================================
--
-- Reference data every fresh installation needs at runtime. Rows that only tests or
-- `datagen/generate.py` need (its superseded INVOICE_DEFAULT number range) are not seeded here;
-- datagen creates them itself. `DevDataFixtures` (dev-mode invoice/party fixtures only, no
-- reference data) covers the rest, so nothing is duplicated.
-- Nothing here corrects anything else here: every row stands with the values it carries.
--
-- Insertion order determines the generated ids and is part of the contract, not cosmetic.
--
-- The number_range block stays idempotent (ON CONFLICT DO NOTHING): a standalone
-- re-run leaves the default number ranges untouched.
--
-- created_at carries the fixed baseline moment 2026-01-01 00:00 UTC, not the database clock: the
-- database never invents a time, and a shipped reference row is part of the baseline, not
-- something that happened when a particular installation ran its migrations.

-- ── 0. Languages (language) ──────────────────────────────────────────────────────────────────
--
-- German is the fallback language (is_fallback = TRUE): a document must never be half-translated.
-- The id values are stable and never re-used. The language rows themselves are created by the
-- DDL baseline.

-- ── 1. Tax rules (tax_rate) ──────────────────────────────────────────────────────────────────
--
-- The two selectable German domestic rules. Every rule carries an EN 16931 category.
INSERT INTO tax_rate (
    name, rate, country, valid_from, is_default, is_hotel_rate, created_at, version,
    code, category, exemption_reason_code, exemption_reason_text, legal_basis,
    is_active, is_selectable, is_reference
) VALUES
    ('USt 19%',     19.00, 276, '2007-01-01', TRUE,  FALSE, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, 'DE_STANDARD_19', 'S', NULL, NULL, NULL, TRUE, TRUE, TRUE),
    ('USt 7%',       7.00, 276, '2007-01-01', FALSE, FALSE, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, 'DE_REDUCED_7',   'S', NULL, NULL, NULL, TRUE, TRUE, FALSE);

-- The four MVP rules. `country` stays NOT NULL and carries the placeholder DE for the three
-- country-independent rules (AE/E/O): 276 here means "rule issued by the German company", not a
-- country restriction. Applicability depends on `category`, not on `country`.
INSERT INTO tax_rate (
    name, rate, country, valid_from, is_default, is_hotel_rate, created_at, version,
    code, category, exemption_reason_code, exemption_reason_text, legal_basis,
    is_active, is_selectable
) VALUES
    (
        'EU-B2B Reverse Charge (Dienstleistung)', 0.00, 276, '2026-01-01', FALSE, FALSE, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0,
        'EU_B2B_RC_SERVICE', 'AE', 'VATEX-EU-AE',
        'Reverse Charge: Der Leistungsempfänger schuldet die Umsatzsteuer gemäß Art. 196 MwStSystRL / § 13b UStG.',
        '§ 3a Abs. 2 UStG',
        TRUE, TRUE
    ),
    (
        'EU-B2C Dienstleistung (deutscher Satz)', 19.00, 276, '2026-01-01', FALSE, FALSE, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0,
        'EU_B2C_DE_STANDARD_19', 'S', NULL, NULL,
        '§ 3a Abs. 1 UStG',
        TRUE, TRUE
    ),
    (
        'Steuerbefreite Dienstleistung', 0.00, 276, '2026-01-01', FALSE, FALSE, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0,
        'DE_EXEMPT_SERVICE', 'E', NULL,
        'Steuerbefreite Leistung — Rechtsgrundlage ist je Einzelfall zu bestimmen (z. B. § 4 UStG).',
        NULL,
        TRUE, TRUE
    ),
    (
        'Nicht steuerbare Leistung (Leistungsort im Ausland)', 0.00, 276, '2026-01-01', FALSE, FALSE, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0,
        'OUTSIDE_SCOPE_SERVICE', 'O', NULL,
        'Nicht steuerbarer Umsatz — der Leistungsort liegt außerhalb des Inlands.',
        '§ 3a UStG',
        TRUE, TRUE
    );

-- VAT (MWST) standard rates FR/BE/NL/LU, plus the FR/BE/NL reduced rates. All category = 'S'
-- (standard rate in the UNTDID 5305 sense). `is_default = FALSE`: the German rate stays the only
-- global default. Luxembourg's four-tier system is created completely because the LU article
-- variants need it; FR/BE/NL get the standard rate plus their respective reduced rate (LU has
-- all of them).
--
-- Quellen (Stand 2026-08):
--   FR: Code général des impôts Art. 278 (20 %), Art. 278-0 bis (10 %), Art. 278-0 bis A (5.5 %),
--       Art. 298 octies (2.1 %, presse).
--   BE: Code de la TVA Art. 37 (21 %); Wetboek van de belasting over de toegevoegde waarde,
--       tabel A/B bij KB nr. 20 (12 %, 6 %).
--   NL: Wet op de omzetbelasting 1968 Art. 9 (21 %, algemeen), Art. 9 lid 2 jo. Tabel I (9 %,
--       since 01.01.2019).
--   LU: Loi TVA Art. 39 — 17/14/8/3 % seit 01.01.2024.
INSERT INTO tax_rate (
    name, rate, country, valid_from, is_default, is_hotel_rate, created_at, version,
    code, category, is_active, is_selectable, legal_basis, is_reference
) VALUES
    ('TVA France 20 % (taux normal)',            20.00, 250, '2014-01-01', FALSE, FALSE, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, 'FR_STANDARD_20',     'S', TRUE, TRUE, 'Code général des impôts, Art. 278', TRUE),
    ('TVA France 10 % (taux intermédiaire)',     10.00, 250, '2014-01-01', FALSE, FALSE, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, 'FR_REDUCED_10',      'S', TRUE, TRUE, 'Code général des impôts, Art. 278-0 bis', FALSE),
    ('TVA France 5,5 % (taux réduit)',            5.50, 250, '2014-01-01', FALSE, FALSE, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, 'FR_REDUCED_5_5',     'S', TRUE, TRUE, 'Code général des impôts, Art. 278-0 bis A', FALSE),
    ('TVA France 2,1 % (taux particulier)',       2.10, 250, '2014-01-01', FALSE, FALSE, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, 'FR_REDUCED_2_1',     'S', TRUE, TRUE, 'Code général des impôts, Art. 298 octies', FALSE),
    ('BTW België 21 % (normaal tarief)',         21.00,  56, '1996-01-01', FALSE, FALSE, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, 'BE_STANDARD_21',     'S', TRUE, TRUE, 'Wetboek van de belasting over de toegevoegde waarde, Art. 37', TRUE),
    ('BTW België 12 % (verlaagd tarief)',        12.00,  56, '1996-01-01', FALSE, FALSE, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, 'BE_REDUCED_12',      'S', TRUE, TRUE, 'Wetboek van de belasting over de toegevoegde waarde, tabel B bij KB nr. 20', FALSE),
    ('BTW België 6 % (verlaagd tarief)',          6.00,  56, '1996-01-01', FALSE, FALSE, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, 'BE_REDUCED_6',       'S', TRUE, TRUE, 'Wetboek van de belasting over de toegevoegde waarde, tabel A bij KB nr. 20', FALSE),
    ('BTW Nederland 21 % (algemeen tarief)',     21.00, 528, '2012-10-01', FALSE, FALSE, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, 'NL_STANDARD_21',     'S', TRUE, TRUE, 'Wet op de omzetbelasting 1968, Art. 9', TRUE),
    ('BTW Nederland 9 % (verlaagd tarief)',       9.00, 528, '2019-01-01', FALSE, FALSE, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, 'NL_REDUCED_9',       'S', TRUE, TRUE, 'Wet op de omzetbelasting 1968, Art. 9 lid 2 jo. Tabel I', FALSE),
    ('TVA Luxembourg 17 % (taux normal)',        17.00, 442, '2024-01-01', FALSE, FALSE, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, 'LU_STANDARD_17',     'S', TRUE, TRUE, 'Loi concernant la taxe sur la valeur ajoutée, Art. 39', TRUE),
    ('TVA Luxembourg 14 % (taux intermédiaire)', 14.00, 442, '2024-01-01', FALSE, FALSE, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, 'LU_INTERMEDIATE_14', 'S', TRUE, TRUE, 'Loi concernant la taxe sur la valeur ajoutée, Art. 39', FALSE),
    ('TVA Luxembourg 8 % (taux réduit)',          8.00, 442, '2024-01-01', FALSE, FALSE, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, 'LU_REDUCED_8',       'S', TRUE, TRUE, 'Loi concernant la taxe sur la valeur ajoutée, Art. 39', FALSE),
    ('TVA Luxembourg 3 % (taux super-réduit)',    3.00, 442, '2024-01-01', FALSE, FALSE, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, 'LU_SUPER_REDUCED_3', 'S', TRUE, TRUE, 'Loi concernant la taxe sur la valeur ajoutée, Art. 39', FALSE);

-- ── 2. Payment terms (payment_term) ──────────────────────────────────────────────────────────
--
-- The active standard terms. "14 Tage netto" (14 days net) is the default. The four historical
-- inactive rows (is_active = FALSE) are not seeded on a fresh install.
--
-- Each term gets one localized_text_group (purpose=1=PAYMENT_TERM_NAME) with a German and an
-- English localized_text row (language_id 1='de', 2='en', both created by the DDL baseline),
-- then the payment_term row itself.
WITH grp_immediate AS (
    INSERT INTO localized_text_group (purpose) VALUES (1) RETURNING id
), de_immediate AS (
    INSERT INTO localized_text (group_id, language_id, value) SELECT id, 1, 'Zahlbar sofort' FROM grp_immediate
), en_immediate AS (
    INSERT INTO localized_text (group_id, language_id, value) SELECT id, 2, 'Due immediately' FROM grp_immediate
)
INSERT INTO payment_term (name_group_id, description, due_in_days, discount_percent, discount_days, is_default, is_active, created_at, version)
SELECT id, 'Zahlbar sofort ohne Abzug.', 0, 0.00, NULL, FALSE, TRUE, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0 FROM grp_immediate;

WITH grp_net14 AS (
    INSERT INTO localized_text_group (purpose) VALUES (1) RETURNING id
), de_net14 AS (
    INSERT INTO localized_text (group_id, language_id, value) SELECT id, 1, '14 Tage netto' FROM grp_net14
), en_net14 AS (
    INSERT INTO localized_text (group_id, language_id, value) SELECT id, 2, 'Net 14 days' FROM grp_net14
)
INSERT INTO payment_term (name_group_id, description, due_in_days, discount_percent, discount_days, is_default, is_active, created_at, version)
SELECT id, 'Zahlbar innerhalb von 14 Tagen ohne Abzug.', 14, 0.00, NULL, TRUE, TRUE, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0 FROM grp_net14;

WITH grp_net30 AS (
    INSERT INTO localized_text_group (purpose) VALUES (1) RETURNING id
), de_net30 AS (
    INSERT INTO localized_text (group_id, language_id, value) SELECT id, 1, '30 Tage netto' FROM grp_net30
), en_net30 AS (
    INSERT INTO localized_text (group_id, language_id, value) SELECT id, 2, 'Net 30 days' FROM grp_net30
)
INSERT INTO payment_term (name_group_id, description, due_in_days, discount_percent, discount_days, is_default, is_active, created_at, version)
SELECT id, 'Zahlbar innerhalb von 30 Tagen ohne Abzug.', 30, 0.00, NULL, FALSE, TRUE, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0 FROM grp_net30;

WITH grp_net30_discount AS (
    INSERT INTO localized_text_group (purpose) VALUES (1) RETURNING id
), de_net30_discount AS (
    INSERT INTO localized_text (group_id, language_id, value) SELECT id, 1, '30 Tage netto, 2 % Skonto in 7 Tagen' FROM grp_net30_discount
), en_net30_discount AS (
    INSERT INTO localized_text (group_id, language_id, value) SELECT id, 2, 'Net 30 days, 2% discount within 7 days' FROM grp_net30_discount
)
INSERT INTO payment_term (name_group_id, description, due_in_days, discount_percent, discount_days, is_default, is_active, created_at, version)
SELECT id, 'Zahlbar innerhalb von 30 Tagen, 2% Skonto bei Zahlung innerhalb von 7 Tagen.', 30, 2.00, 7, FALSE, TRUE, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0 FROM grp_net30_discount;

-- ── 3. Per diem rates (per_diem_rate) ────────────────────────────────────────────────────────
--
-- Domestic: § 9 Abs. 4a Satz 3 EStG (quantified directly in the statute, not part of the BMF
-- letter). The historical seven-row 2024 set (is_active = FALSE) is not seeded on a fresh
-- install.
INSERT INTO per_diem_rate (
    country, region, full_day_rate, arrival_departure_rate, inland_rules, is_default,
    valid_from, valid_to, created_at, version, is_active, source
) VALUES (
    276, NULL, 28.00, 14.00, TRUE, TRUE,
    '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, TRUE,
    '§ 9 Abs. 4a Satz 3 EStG (Inland)'
);

-- Abroad: BMF-Schreiben vom 5. Dezember 2025, GZ IV C 5 - S 2353/00094/007/012
-- ("Steuerliche Behandlung von Reisekosten … bei Auslandsreisen ab 1. Januar 2026"). No end
-- date: valid_to stays NULL until the next annual edition.
INSERT INTO per_diem_rate (
    country, region, full_day_rate, arrival_departure_rate, inland_rules, is_default,
    valid_from, valid_to, created_at, version, is_active, source
) VALUES
    ( 40, NULL,      50.00, 33.00, FALSE, FALSE, '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, TRUE, 'BMF-Schreiben vom 05.12.2025, IV C 5 - S 2353/00094/007/012'), -- AT
    ( 56, NULL,      59.00, 40.00, FALSE, FALSE, '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, TRUE, 'BMF-Schreiben vom 05.12.2025, IV C 5 - S 2353/00094/007/012'), -- BE
    (203, NULL,      32.00, 21.00, FALSE, FALSE, '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, TRUE, 'BMF-Schreiben vom 05.12.2025, IV C 5 - S 2353/00094/007/012'), -- CZ
    (208, NULL,      75.00, 50.00, FALSE, FALSE, '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, TRUE, 'BMF-Schreiben vom 05.12.2025, IV C 5 - S 2353/00094/007/012'), -- DK
    (250, 'Paris',   58.00, 39.00, FALSE, FALSE, '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, TRUE, 'BMF-Schreiben vom 05.12.2025, IV C 5 - S 2353/00094/007/012'), -- FR — Paris + Departments 77/78/91-95
    (250, NULL,      53.00, 36.00, FALSE, FALSE, '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, TRUE, 'BMF-Schreiben vom 05.12.2025, IV C 5 - S 2353/00094/007/012'), -- FR — rest of country
    (442, NULL,      63.00, 42.00, FALSE, FALSE, '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, TRUE, 'BMF-Schreiben vom 05.12.2025, IV C 5 - S 2353/00094/007/012'), -- LU
    (528, NULL,      58.00, 39.00, FALSE, FALSE, '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, TRUE, 'BMF-Schreiben vom 05.12.2025, IV C 5 - S 2353/00094/007/012'), -- NL
    (616, 'Breslau', 34.00, 23.00, FALSE, FALSE, '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, TRUE, 'BMF-Schreiben vom 05.12.2025, IV C 5 - S 2353/00094/007/012'), -- PL — Wroclaw
    (616, 'Warschau',40.00, 27.00, FALSE, FALSE, '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, TRUE, 'BMF-Schreiben vom 05.12.2025, IV C 5 - S 2353/00094/007/012'), -- PL — Warsaw
    (616, NULL,      34.00, 23.00, FALSE, FALSE, '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, TRUE, 'BMF-Schreiben vom 05.12.2025, IV C 5 - S 2353/00094/007/012'), -- PL — rest of country
    (372, NULL,      64.00, 43.00, FALSE, FALSE, '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, TRUE, 'BMF-Schreiben vom 05.12.2025, IV C 5 - S 2353/00094/007/012'), -- IE
    (826, 'London',  66.00, 44.00, FALSE, FALSE, '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, TRUE, 'BMF-Schreiben vom 05.12.2025, IV C 5 - S 2353/00094/007/012'), -- UK — London
    (826, NULL,      52.00, 35.00, FALSE, FALSE, '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, TRUE, 'BMF-Schreiben vom 05.12.2025, IV C 5 - S 2353/00094/007/012'), -- UK — rest of country
    (752, NULL,      66.00, 44.00, FALSE, FALSE, '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, TRUE, 'BMF-Schreiben vom 05.12.2025, IV C 5 - S 2353/00094/007/012'), -- SE
    (578, NULL,      75.00, 50.00, FALSE, FALSE, '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, TRUE, 'BMF-Schreiben vom 05.12.2025, IV C 5 - S 2353/00094/007/012'), -- NO
    (246, NULL,      54.00, 36.00, FALSE, FALSE, '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, TRUE, 'BMF-Schreiben vom 05.12.2025, IV C 5 - S 2353/00094/007/012'), -- FI
    (380, 'Mailand',  42.00, 28.00, FALSE, FALSE, '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, TRUE, 'BMF-Schreiben vom 05.12.2025, IV C 5 - S 2353/00094/007/012'), -- IT — Milan
    (380, 'Rom',      48.00, 32.00, FALSE, FALSE, '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, TRUE, 'BMF-Schreiben vom 05.12.2025, IV C 5 - S 2353/00094/007/012'), -- IT — Rome
    (380, NULL,       42.00, 28.00, FALSE, FALSE, '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, TRUE, 'BMF-Schreiben vom 05.12.2025, IV C 5 - S 2353/00094/007/012'), -- IT — rest of country
    (642, NULL,       38.00, 25.00, FALSE, FALSE, '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, TRUE, 'BMF-Schreiben vom 05.12.2025, IV C 5 - S 2353/00094/007/012'); -- RO

-- ── 4. Mileage rates (mileage_rate) ──────────────────────────────────────────────────────────
--
-- Vehicle type instead of destination country: the German mileage allowance does not depend on
-- the country (§ 5 BRKG). Simplified as two vehicle-type rows instead of the actual conditional
-- rule (§ 5 Abs. 1 BRKG: 0.20 EUR/km for any motor vehicle, capped at 130 EUR per trip;
-- § 5 Abs. 2 BRKG: 0.30 EUR/km only for a car where a considerable official interest was
-- established in writing before the trip, uncapped). The cap and the establishment requirement
-- are NOT enforced. The five historical, country-dependent rows (is_active = FALSE, wrong in
-- substance) are not seeded on a fresh install. vehicle_type is the ordinal VehicleType:
-- 0=CAR, 1=OTHER_MOTOR_VEHICLE (see chk_mileage_rate_vehicle_type in the DDL baseline).
INSERT INTO mileage_rate (country, rate_per_km, valid_from, created_at, version, vehicle_type, is_active, source)
VALUES
    (276, 0.300, '2024-01-01', TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, 0, TRUE,
     '§ 5 Abs. 2 BRKG (erhöhter Satz für einen Pkw bei erheblichem dienstlichen Interesse)'),
    (276, 0.200, '2024-01-01', TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, 1, TRUE,
     '§ 5 Abs. 1 BRKG (Standardsatz für andere motorbetriebene Fahrzeuge)');

-- ── 5. Hotel rates (hotel_rate) ──────────────────────────────────────────────────────────────
--
-- Only AT, LU, BE, NL and one merged FR row stay active. No DE row (domestic overnight stays
-- are reimbursed at actual cost, no flat rate).
--
-- TODO(human, before a CH row is added): Switzerland never had a current BMF 2026 hotel_rate
-- row. Do not add a CH row before a human has looked up the real 2026 figure in the official
-- BMF overview and sourced it properly (source reference + valid_from = '2026-01-01', same
-- style as the other rows of this table). Do not carry over a retired value as a placeholder
-- and do not estimate a figure.
INSERT INTO hotel_rate (country, rate_per_night, valid_from, valid_to, created_at, version, region, is_active, source)
VALUES
    ( 40, 117.00, '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, NULL, TRUE, 'BMF-Schreiben vom 05.12.2025, IV C 5 - S 2353/00094/007/012'), -- AT
    ( 56, 141.00, '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, NULL, TRUE, 'BMF-Schreiben vom 05.12.2025, IV C 5 - S 2353/00094/007/012'), -- BE
    (528, 167.00, '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, NULL, TRUE, 'BMF-Schreiben vom 05.12.2025, IV C 5 - S 2353/00094/007/012'), -- NL
    (442, 139.00, '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, NULL, TRUE, 'BMF-Schreiben vom 05.12.2025, IV C 5 - S 2353/00094/007/012'), -- LU
    (250, 105.00, '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, NULL, TRUE, 'BMF-Schreiben vom 05.12.2025, IV C 5 - S 2353/00094/007/012'); -- FR — rest of country

-- ── 6. Validation profiles and validation switches ───────────────────────────────────────────
--
-- The state actually in use and traceable in code: XRechnung 3.0.2 + KoSIT configuration
-- 2026-01-31 from EmbeddedKositValidator.SCENARIO_ZIP
-- ("xrechnung-3.0.2-validator-configuration-2026-01-31.zip"), Mustang 2.25.0 from
-- kotlin-xpdf/pom.xml (org.mustangproject:validator).
--
-- "EN16931" is deliberately missing: there is no traceable, separately validated state for that
-- branch of XmlExportServiceImpl.resolveProfile(); an unsubstantiated row would be guesswork,
-- not a register entry.
INSERT INTO validation_profile (profile_id, spec_version, syntax, codelist_version, validator_config_version, mustang_version, state, valid_from, created_at, version)
VALUES ('XRechnung', '3.0.2', 'UBL/CII', NULL, '2026-01-31', '2.25.0', 'ACTIVE', '2026-01-31', TIMESTAMPTZ '2026-01-01 00:00:00+00', 0);

-- EXTENDED (structured discount terms, see de.littletrotter.domain.invoice.resolveExportProfileId)
-- stays EXPERIMENTAL, not ACTIVE: ValidationProfileRegistry.evaluate lets EXPERIMENTAL pass
-- finalization without a block but requires no KoSIT evidence of its own. The bundled XRechnung
-- 3.0.2 configuration has no scenario for the EXTENDED guideline, so the export validation
-- deliberately skips the KoSIT stage (the PROFILE stage carries the recipient warning instead).
-- The evidence is the Factur-X EXTENDED Schematron stage (FACTUR-X_EXTENDED.xslt, version
-- 1.09.2), not KoSIT; Mustang 2.26.0 from kotlin-xpdf/pom.xml (org.mustangproject:validator)
-- builds the EXTENDED document.
INSERT INTO validation_profile (profile_id, spec_version, syntax, codelist_version, validator_config_version, mustang_version, state, valid_from, created_at, version)
VALUES ('EXTENDED', 'Factur-X 1.09.2 EXTENDED', 'CII', NULL, '1.09.2', '2.26.0', 'EXPERIMENTAL', '2026-09-28', TIMESTAMPTZ '2026-09-28 00:00:00+00', 0);

-- KOSIT_CHECK and VERAPDF_CHECK are enabled (fail-open, like a missing row itself); neither
-- depends on a locally running AI. OMLX_LLM_CHECK starts ENABLED (the AI check should run on
-- invoice printing without manual activation); the switch does not replace infrastructure
-- configuration: without a configured, reachable oMLX the check does not run even with
-- enabled = TRUE, and the document is still produced.
INSERT INTO validation_setting (capability_id, enabled, created_by, updated_by)
VALUES ('OMLX_LLM_CHECK', TRUE, 'seed', 'seed'),
       ('VERAPDF_CHECK',  TRUE, 'seed', 'seed'),
       ('KOSIT_CHECK',    TRUE, 'seed', 'seed')
ON CONFLICT (capability_id) DO NOTHING;

-- ── 7. Number ranges (number_range) ──────────────────────────────────────────────────────────
--
-- Three default ranges per subject plus INVOICE_GAPLESS: GLOBAL instead of PER_SELLER (one
-- sequence across all sellers), GAPLESS instead of UNIQUE_ONLY (the count must have no gap) and
-- YEAR_MONTH_DAY instead of YEAR_MONTH (day part in the number). INVOICE_DEFAULT (superseded by
-- INVOICE_GAPLESS) is not seeded on a fresh install; datagen creates it when needed.
--
-- start_value is 1 everywhere as the factory setting; whoever migrates from a predecessor
-- system changes it in the reference data while the range has not issued anything yet.
--
-- ON CONFLICT DO NOTHING keeps this block re-appliable (a repeat run adds nothing).
--
-- ordinal NumberRangeSubject: 0=INVOICE, 1=CUSTOMER, 2=CATALOGUE, 3=SELLER, 4=QUOTE (the QUOTE counter lives in the quote module)
-- ordinal PrefixSource: 0=NONE, 1=FIXED, 2=SELLER_PROFILE, 3=FROM_DATA
-- ordinal PrefixDataField: 0=NAME_INITIALS, 1=COUNTRY_CODE, 2=CATALOGUE_CATEGORY
-- ordinal DatePart: 0=NONE, 1=YEAR, 2=YEAR_MONTH, 3=YEAR_MONTH_DAY
-- ordinal ResetCycle: 0=NEVER, 1=YEARLY
-- ordinal CounterScope (column scope): 0=GLOBAL, 1=PER_SELLER
-- ordinal Continuity: 0=GAPLESS, 1=UNIQUE_ONLY
INSERT INTO number_range
    (code, display_name, subject, start_value, prefix_source, fixed_prefix, prefix_data_field,
     date_part, counter_width, reset_cycle, scope, continuity, is_active, is_default)
VALUES
    ('CUSTOMER_DEFAULT', 'Kundennummer',    1, 1, 3, NULL, 0,
     0, 5, 0, 0, 1, TRUE, TRUE),
    ('CATALOGUE_DEFAULT', 'Katalog',   2,  1, 3, NULL, 2,
     0, 5, 0, 0, 1, TRUE, TRUE),
    ('SELLER_DEFAULT',   'Anbieternummer',  3,   1, 3, NULL, 0,
     0, 5, 0, 0, 1, TRUE, TRUE),
    ('INVOICE_GAPLESS',  'Rechnungsnummer', 0,  1, 2, NULL, NULL,
     3, 5, 1, 0, 0, TRUE, TRUE),
    ('QUOTE_DEFAULT',    'Angebotsnummer',  4,  1, 1, 'AN', NULL,
     1, 4, 1, 0, 0, TRUE, TRUE)
ON CONFLICT (code) DO NOTHING;

-- ── 8. B2B profile "Neckar" (generic full-profile cut) ───────────────────────────────────────
--
-- "Neckar" is the only shipped profile: no special case in code, an ordinary, editable B2B
-- profile; whoever wants different rules copies the row instead of changing the catalog.
-- bt_code/condition_bt_code are the official EN 16931 BT numbers
-- (de.littletrotter.shared.b2b.B2bTerm.code), e.g. 155 for BT-155; the validity/binding of a
-- term lives in B2bTerm.
INSERT INTO b2b_profile (name, description, active, version)
VALUES (
    'Neckar',
    'Vollständiger B2B-Feldsatz für Rechnungsempfänger, die auf der Rechnung Projektreferenzen (BT-11), Kostenstellen (BT-19) und empfängerspezifische Artikelnummern (BT-155/BT-156) verlangen. Kopie anpassen statt Katalog ändern.',
    TRUE,
    0
)
ON CONFLICT (name) DO NOTHING;

-- ordinal B2bFieldRequirement: 0=OPTIONAL, 1=MANDATORY, 2=CONDITIONAL, 3=CONDITIONAL_IF_EMPTY
-- ordinal B2bPlacement: 0=INVOICE, 1=RECIPIENT, 2=SELLER
INSERT INTO b2b_profile_field (profile_id, bt_code, business_definition, requirement, condition_bt_code, placement, sort_order, default_value, version)
SELECT p.id, v.bt_code, v.business_definition, v.requirement, v.condition_bt_code, v.placement, v.sort_order, v.default_value, 0
FROM (SELECT id FROM b2b_profile WHERE name = 'Neckar') p
CROSS JOIN (VALUES
    (19,  'Kostenstelle des Rechnungsempfängers (BT-19) — Pflichtangabe; ohne Kostenstelle wird die Rechnung abgelehnt.',                                         1,            NULL::smallint, 0, 1,  NULL::varchar),
    (72,  'Liefer-/Leistungsdatum (BT-72) — Pflicht, sofern nicht auf jeder Position beide Positions-Leistungszeiträume (BT-134/BT-135) befüllt sind (Entweder-oder-Paar mit den Positionszeiträumen).', 3, 134::smallint, 0, 2,  NULL::varchar),
    (73,  'Leistungszeitraum von (BT-73) — Alternative zum Liefer-/Leistungsdatum (BT-72).',                                                         0,              NULL::smallint, 0, 3,  NULL::varchar),
    (74,  'Leistungszeitraum bis (BT-74) — Alternative zum Liefer-/Leistungsdatum (BT-72).',                                                         0,              NULL::smallint, 0, 4,  NULL::varchar),
    (84,  'Bankverbindung IBAN des Rechnungsausstellers (BT-84) — Pflichtangabe.',                                                                              1,             NULL::smallint, 2,  5,  NULL::varchar),
    (58,  'E-Mail-Adresse der Kontaktstelle des Rechnungsempfängers (BT-58) — Pflichtangabe; je Rechnungsempfänger pflegbar, kein fester Standardwert am Profil.', 1, NULL::smallint, 1,  6,  NULL::varchar),
    (56,  'Ansprechpartner bzw. bestellende Abteilung des Rechnungsempfängers (BT-56) — sofern bei Bestellung mitgeteilt.',                                       0,              NULL::smallint, 0, 7,  NULL::varchar),
    (13,  'Bestellnummer des Rechnungsempfängers (BT-13) — sofern bei Bestellung mitgeteilt; sobald gesetzt, werden BT-155 und BT-156 Pflicht.',                  0,              NULL::smallint, 0, 8,  NULL::varchar),
    (12,  'Vertragsnummer (BT-12) — bei Mietsachverhalten, z. B. Mietrechnungen oder Nebenkostenabrechnungen.',                                      0,              NULL::smallint, 0, 9,  NULL::varchar),
    (18,  'Innenauftrag (BT-18, Kennungsschema „IL") — sofern bei Bestellung mitgeteilt.',                                                           0,              NULL::smallint, 0, 10, NULL::varchar),
    (134, 'Positions-Leistungszeitraum von (BT-134) — Pflicht auf jeder Position, sofern kein Liefer-/Leistungsdatum (BT-72) erfasst ist (Entweder-oder-Paar mit BT-72).', 3, 72::smallint, 0, 11, NULL::varchar),
    (135, 'Positions-Leistungszeitraum bis (BT-135) — Pflicht auf jeder Position, sofern kein Liefer-/Leistungsdatum (BT-72) erfasst ist (Entweder-oder-Paar mit BT-72).', 3, 72::smallint, 0, 12, NULL::varchar),
    (155, 'Artikelnummer des Rechnungsausstellers (BT-155) — verpflichtend, sobald eine Bestellnummer (BT-13) angegeben ist.',                                  2,           13::smallint, 0, 13, NULL::varchar),
    (156, 'Artikelnummer des Rechnungsempfängers (BT-156) — verpflichtend, sobald eine Bestellnummer (BT-13) angegeben ist.',                                     2,           13::smallint, 0, 14, NULL::varchar),
    (11,  'Projektreferenz / Auftragsnummer des Rechnungsempfängers (BT-11) — sofern bei Bestellung mitgeteilt.',                                                 0,              NULL::smallint, 0, 15, NULL::varchar)
) AS v(bt_code, business_definition, requirement, condition_bt_code, placement, sort_order, default_value)
WHERE NOT EXISTS (
    SELECT 1 FROM b2b_profile_field f
    WHERE f.profile_id = p.id AND f.bt_code = v.bt_code
);

-- Runtime feature defaults for a fresh installation.
INSERT INTO feature_setting (key, enabled, description)
VALUES
    ('feature.perdiem.enabled', TRUE, 'Sichtbarkeit der Verpflegungspauschalen-Erfassung'),
    ('feature.email.enabled',   TRUE, 'E-Mailversand-Angebot und -Ausfuehrung'),
    ('feature.receipt-auto-reduce.enabled', TRUE, 'Grosse Belege beim Hochladen ohne Rueckfrage verkleinern'),
    ('feature.extended-profile.enabled', FALSE, 'ZUGFeRD EXTENDED fuer Skonto-Rechnungen (Opt-in)')
ON CONFLICT (key) DO NOTHING;
