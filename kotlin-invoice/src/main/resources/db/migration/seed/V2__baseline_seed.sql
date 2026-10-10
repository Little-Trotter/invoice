-- =============================================================================
-- V2__baseline_seed.sql — Little Trotter Invoice, greenfield seed baseline (H27-29)
-- =============================================================================
--
-- Replaces the grown Flyway seed history V901-V915 (H27-29 squash, 28.08.2026): since
-- 22.08.2026 the project is a single open-source component with no external installations
-- whose migration history must be preserved (greenfield rule — delete legacy, don't curate
-- it). Every row below is data still needed at runtime on a fresh install — cross-checked
-- against `datagen/generate.py` (which seeds its own LEGACY_*/is_active=FALSE historical
-- rows and the superseded INVOICE_DEFAULT number range itself, see datagen's own comments)
-- and `DevDataFixtures` (dev-mode invoice/party fixtures only, no reference data) to avoid
-- duplication. Nothing here corrects anything else here — every row stands with the values
-- it carries at the end of the squashed history.
--
-- Insertion order mirrors the ID order of the previous, already-consolidated seed set where
-- a row still appears here; that is part of the contract, not cosmetic.
--
-- V904's number_range block stays idempotent (ON CONFLICT DO NOTHING): DatagenComplianceIT
-- re-applies it standalone to prove the default number ranges survive a repeat run.
--
-- created_at carries the fixed baseline moment 2026-01-01 00:00 UTC, not the database clock: the
-- database never invents a time, and a shipped reference row is part of the baseline, not
-- something that happened when a particular installation ran its migrations.

-- ── 0. Sprachen (language, IN-1) ─────────────────────────────────────────────────────────────
--
-- Deutsch ist die Rückfallsprache (is_fallback = TRUE) — ein Dokument darf nie halb übersetzt
-- bleiben. Die id-Werte sind stabil und werden nie neu vergeben.
-- ── 1. Steuerregeln (tax_rate) ───────────────────────────────────────────────────────────────
--
-- Die beiden auswählbaren deutschen Inlandsregeln. Die acht historischen LEGACY_*-Zeilen
-- (mehrdeutige/nicht mehr ausweisbare Altsätze) seedet eine frische Installation nicht — wer sie
-- braucht (Tests, datagen), legt sie sich selbst an (siehe TaxRuleMigrationIT, datagen/generate.py).
INSERT INTO tax_rate (
    name, rate, country, valid_from, is_default, is_hotel_rate, created_at, version,
    code, category, exemption_reason_code, exemption_reason_text, legal_basis,
    is_active, is_selectable, is_reference
) VALUES
    ('USt 19%',     19.00, 276, '2007-01-01', TRUE,  FALSE, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, 'DE_STANDARD_19', 'S', NULL, NULL, NULL, TRUE, TRUE, TRUE),
    ('USt 7%',       7.00, 276, '2007-01-01', FALSE, FALSE, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, 'DE_REDUCED_7',   'S', NULL, NULL, NULL, TRUE, TRUE, FALSE);

-- Die vier MVP-Regeln aus R1. `country` bleibt NOT NULL und trägt für die drei
-- länderunabhängigen Regeln (AE/E/O) den Platzhalter DE — die 276 steht hier für „vom deutschen
-- Unternehmen ausgestellte Regel", nicht für eine Länder-Einschränkung. Die fachliche
-- Anwendbarkeit richtet sich nach `category`, nicht nach `country`.
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

-- MWST-Regelsätze FR/BE/NL/LU (task11/V2), plus die FR/BE/NL-Ermäßigungssätze (task11/V8). Alle
-- category = 'S' (Regelsatz im UNTDID-5305-Sinn). `is_default = FALSE`: der deutsche Satz bleibt
-- der einzige globale Default. Luxemburgs vierstufiges System wird vollständig angelegt, weil
-- task11/V3 die LU-Artikel-Varianten fachlich braucht; FR/BE/NL bekommen den Regelsatz plus ihren
-- jeweiligen Ermäßigungssatz (LU hatte ihn schon vollständig).
--
-- Quellen (Stand 2026-08):
--   FR: Code général des impôts Art. 278 (20 %), Art. 278-0 bis (10 %), Art. 278-0 bis A (5.5 %),
--       Art. 298 octies (2.1 %, presse).
--   BE: Code de la TVA Art. 37 (21 %); Wetboek van de belasting over de toegevoegde waarde,
--       tabel A/B bij KB nr. 20 (12 %, 6 %).
--   NL: Wet op de omzetbelasting 1968 Art. 9 (21 %, algemeen), Art. 9 lid 2 jo. Tabel I (9 %,
--       seit 01.01.2019).
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

-- ── 2. Zahlungsbedingungen (payment_term) ────────────────────────────────────────────────────
--
-- Die aktiven Standardsätze (R9). „14 Tage netto" ist der Default. Die vier historischen
-- Altzeilen (is_active = FALSE) seedet eine frische Installation nicht.
--
-- IN-4 — name/english_name wurden durch name_group_id ersetzt: je Zahlungsbedingung eine
-- localized_text_group (purpose=1=PAYMENT_TERM_NAME) mit einer deutschen und einer englischen
-- localized_text-Zeile (language_id 1='de', 2='en', beide in V1 geseedet), erst danach die
-- payment_term-Zeile selbst.
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

-- ── 3. Verpflegungspauschalen (per_diem_rate) ────────────────────────────────────────────────
--
-- Inland — § 9 Abs. 4a Satz 3 EStG (direkt im Gesetz beziffert, nicht Teil des BMF-Schreibens).
-- Der historische siebenzeilige 2024er-Altbestand (is_active = FALSE) seedet eine frische
-- Installation nicht.
INSERT INTO per_diem_rate (
    country, region, full_day_rate, arrival_departure_rate, inland_rules, is_default,
    valid_from, valid_to, created_at, version, is_active, source
) VALUES (
    276, NULL, 28.00, 14.00, TRUE, TRUE,
    '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, TRUE,
    '§ 9 Abs. 4a Satz 3 EStG (Inland)'
);

-- Ausland — BMF-Schreiben vom 5. Dezember 2025, GZ IV C 5 - S 2353/00094/007/012
-- („Steuerliche Behandlung von Reisekosten … bei Auslandsreisen ab 1. Januar 2026"). Kein
-- Enddatum: valid_to bleibt NULL bis zur nächsten jährlichen Fassung.
INSERT INTO per_diem_rate (
    country, region, full_day_rate, arrival_departure_rate, inland_rules, is_default,
    valid_from, valid_to, created_at, version, is_active, source
) VALUES
    ( 40, NULL,      50.00, 33.00, FALSE, FALSE, '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, TRUE, 'BMF-Schreiben vom 05.12.2025, IV C 5 - S 2353/00094/007/012'), -- AT
    ( 56, NULL,      59.00, 40.00, FALSE, FALSE, '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, TRUE, 'BMF-Schreiben vom 05.12.2025, IV C 5 - S 2353/00094/007/012'), -- BE
    (203, NULL,      32.00, 21.00, FALSE, FALSE, '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, TRUE, 'BMF-Schreiben vom 05.12.2025, IV C 5 - S 2353/00094/007/012'), -- CZ
    (208, NULL,      75.00, 50.00, FALSE, FALSE, '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, TRUE, 'BMF-Schreiben vom 05.12.2025, IV C 5 - S 2353/00094/007/012'), -- DK
    (250, 'Paris',   58.00, 39.00, FALSE, FALSE, '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, TRUE, 'BMF-Schreiben vom 05.12.2025, IV C 5 - S 2353/00094/007/012'), -- FR — Paris + Departments 77/78/91-95
    (250, NULL,      53.00, 36.00, FALSE, FALSE, '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, TRUE, 'BMF-Schreiben vom 05.12.2025, IV C 5 - S 2353/00094/007/012'), -- FR — im Übrigen
    (442, NULL,      63.00, 42.00, FALSE, FALSE, '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, TRUE, 'BMF-Schreiben vom 05.12.2025, IV C 5 - S 2353/00094/007/012'), -- LU
    (528, NULL,      58.00, 39.00, FALSE, FALSE, '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, TRUE, 'BMF-Schreiben vom 05.12.2025, IV C 5 - S 2353/00094/007/012'), -- NL
    (616, 'Breslau', 34.00, 23.00, FALSE, FALSE, '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, TRUE, 'BMF-Schreiben vom 05.12.2025, IV C 5 - S 2353/00094/007/012'), -- PL — Breslau
    (616, 'Warschau',40.00, 27.00, FALSE, FALSE, '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, TRUE, 'BMF-Schreiben vom 05.12.2025, IV C 5 - S 2353/00094/007/012'), -- PL — Warschau
    (616, NULL,      34.00, 23.00, FALSE, FALSE, '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, TRUE, 'BMF-Schreiben vom 05.12.2025, IV C 5 - S 2353/00094/007/012'), -- PL — im Übrigen
    (372, NULL,      64.00, 43.00, FALSE, FALSE, '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, TRUE, 'BMF-Schreiben vom 05.12.2025, IV C 5 - S 2353/00094/007/012'), -- IE
    (826, 'London',  66.00, 44.00, FALSE, FALSE, '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, TRUE, 'BMF-Schreiben vom 05.12.2025, IV C 5 - S 2353/00094/007/012'), -- UK — London
    (826, NULL,      52.00, 35.00, FALSE, FALSE, '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, TRUE, 'BMF-Schreiben vom 05.12.2025, IV C 5 - S 2353/00094/007/012'), -- UK — im Übrigen
    (752, NULL,      66.00, 44.00, FALSE, FALSE, '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, TRUE, 'BMF-Schreiben vom 05.12.2025, IV C 5 - S 2353/00094/007/012'), -- SE
    (578, NULL,      75.00, 50.00, FALSE, FALSE, '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, TRUE, 'BMF-Schreiben vom 05.12.2025, IV C 5 - S 2353/00094/007/012'), -- NO
    (246, NULL,      54.00, 36.00, FALSE, FALSE, '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, TRUE, 'BMF-Schreiben vom 05.12.2025, IV C 5 - S 2353/00094/007/012'), -- FI
    (380, 'Mailand',  42.00, 28.00, FALSE, FALSE, '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, TRUE, 'BMF-Schreiben vom 05.12.2025, IV C 5 - S 2353/00094/007/012'), -- IT — Mailand
    (380, 'Rom',      48.00, 32.00, FALSE, FALSE, '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, TRUE, 'BMF-Schreiben vom 05.12.2025, IV C 5 - S 2353/00094/007/012'), -- IT — Rom
    (380, NULL,       42.00, 28.00, FALSE, FALSE, '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, TRUE, 'BMF-Schreiben vom 05.12.2025, IV C 5 - S 2353/00094/007/012'), -- IT — im Übrigen
    (642, NULL,       38.00, 25.00, FALSE, FALSE, '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, TRUE, 'BMF-Schreiben vom 05.12.2025, IV C 5 - S 2353/00094/007/012'); -- RO

-- ── 4. Kilometersätze (mileage_rate) ─────────────────────────────────────────────────────────
--
-- Fahrzeugtyp statt Zielland: die deutsche Kilometerpauschale ist landesunabhängig (§ 5 BRKG).
-- Vereinfachend als zwei Fahrzeugtyp-Zeilen modelliert statt der tatsächlich bedingten Regel
-- (§ 5 Abs. 1 BRKG: 0,20 €/km für jedes Kraftfahrzeug, gedeckelt auf 130 € je Reise;
-- § 5 Abs. 2 BRKG: 0,30 €/km nur für einen Pkw bei vor Reiseantritt schriftlich festgestelltem
-- erheblichen dienstlichen Interesse, ungedeckelt). Deckelung und Feststellungspflicht sind
-- NICHT durchgesetzt. Die fünf historischen, landesabhängigen Altzeilen (is_active = FALSE,
-- fachlich falsch) seedet eine frische Installation nicht. vehicle_type: CAR=0,
-- OTHER_MOTOR_VEHICLE=1 (siehe chk_mileage_rate_vehicle_type in V1).
INSERT INTO mileage_rate (country, rate_per_km, valid_from, created_at, version, vehicle_type, is_active, source)
VALUES
    (276, 0.300, '2024-01-01', TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, 0, TRUE,
     '§ 5 Abs. 2 BRKG (erhöhter Satz für einen Pkw bei erheblichem dienstlichen Interesse)'),
    (276, 0.200, '2024-01-01', TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, 1, TRUE,
     '§ 5 Abs. 1 BRKG (Standardsatz für andere motorbetriebene Fahrzeuge)');

-- ── 5. Übernachtungspauschalen (hotel_rate) ──────────────────────────────────────────────────
--
-- WP2a: nur AT, LU, BE, NL und eine zusammengeführte FR-Zeile bleiben aktiv. Keine DE-Zeile
-- (Inlandsübernachtungen werden zu tatsächlichen Kosten erstattet, keine Pauschale).
--
-- TODO(human, bevor eine CH-Zeile ergänzt wird): die Schweiz hatte nie eine aktuelle BMF-2026-
-- hotel_rate-Zeile. Keine CH-Zeile ergänzen, bevor ein Mensch die echte Zahl für 2026 in der
-- offiziellen BMF-Übersicht nachgeschlagen und sauber bequellt hat (Quellenangabe +
-- valid_from = '2026-01-01', Stil wie die übrigen Zeilen dieser Tabelle). Keinen stillgelegten
-- Altwert als Platzhalter übernehmen und keine Zahl schätzen.
INSERT INTO hotel_rate (country, rate_per_night, valid_from, valid_to, created_at, version, region, is_active, source)
VALUES
    ( 40, 117.00, '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, NULL, TRUE, 'BMF-Schreiben vom 05.12.2025, IV C 5 - S 2353/00094/007/012'), -- AT
    ( 56, 141.00, '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, NULL, TRUE, 'BMF-Schreiben vom 05.12.2025, IV C 5 - S 2353/00094/007/012'), -- BE
    (528, 167.00, '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, NULL, TRUE, 'BMF-Schreiben vom 05.12.2025, IV C 5 - S 2353/00094/007/012'), -- NL
    (442, 139.00, '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, NULL, TRUE, 'BMF-Schreiben vom 05.12.2025, IV C 5 - S 2353/00094/007/012'), -- LU
    (250, 105.00, '2026-01-01', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', 0, NULL, TRUE, 'BMF-Schreiben vom 05.12.2025, IV C 5 - S 2353/00094/007/012'); -- FR — im Übrigen

-- ── 6. Prüfprofile und Prüfschalter (F4/VALIDATION-01, T9) ───────────────────────────────────
--
-- Der tatsächlich verwendete, im Code nachweisbare Stand: XRechnung 3.0.2 + KoSIT-Konfiguration
-- 2026-01-31 aus EmbeddedKositValidator.SCENARIO_ZIP
-- ("xrechnung-3.0.2-validator-configuration-2026-01-31.zip"), Mustang 2.25.0 aus
-- kotlin-xpdf/pom.xml (org.mustangproject:validator).
--
-- "EN16931" fehlt hier bewusst: es gibt keinen im Code nachweisbaren, eigens geprüften Stand für
-- diesen Zweig von XmlExportServiceImpl.resolveProfile() — eine unbelegte Zeile wäre Rätselraten,
-- kein Registereintrag.
INSERT INTO validation_profile (profile_id, spec_version, syntax, codelist_version, validator_config_version, mustang_version, state, valid_from, created_at, version)
VALUES ('XRechnung', '3.0.2', 'UBL/CII', NULL, '2026-01-31', '2.25.0', 'ACTIVE', '2026-01-31', TIMESTAMPTZ '2026-01-01 00:00:00+00', 0);

-- PO-Entscheid 28.09.2026: EXTENDED (strukturierte Skontoterme, siehe
-- de.littletrotter.domain.invoice.resolveExportProfileId) bleibt EXPERIMENTAL, nicht ACTIVE —
-- ValidationProfileRegistry.evaluate lässt EXPERIMENTAL ohne Sperre zur Finalisierung zu, verlangt
-- aber keinen eigenen KoSIT-Nachweis: die bundle-Konfiguration XRechnung 3.0.2 kennt kein Szenario
-- für die EXTENDED-Leitlinie, die Exportprüfung überspringt die KoSIT-Stufe deshalb bewusst
-- (Stufe PROFILE trägt stattdessen die Empfängerwarnung). Nachweis ist die Factur-X-EXTENDED-
-- Schematron-Stufe (FACTUR-X_EXTENDED.xslt, Version 1.09.2), nicht KoSIT; Mustang 2.26.0 aus
-- kotlin-xpdf/pom.xml (org.mustangproject:validator) baut das EXTENDED-Dokument.
INSERT INTO validation_profile (profile_id, spec_version, syntax, codelist_version, validator_config_version, mustang_version, state, valid_from, created_at, version)
VALUES ('EXTENDED', 'Factur-X 1.09.2 EXTENDED', 'CII', NULL, '1.09.2', '2.26.0', 'EXPERIMENTAL', '2026-09-28', TIMESTAMPTZ '2026-09-28 00:00:00+00', 0);

-- VERAPDF_CHECK ist eingeschaltet (fail-open, wie die fehlende Zeile selbst) — es hängt an keiner
-- lokal laufenden KI. OMLX_LLM_CHECK startet seit PO-Entscheid 30.08.2026 EINGESCHALTET (die
-- KI-Prüfung soll beim Rechnungsdruck ohne manuelles Freischalten anlaufen); der Schalter
-- ersetzt keine Infrastrukturkonfiguration — ohne konfiguriertes/erreichbares oMLX läuft die
-- Prüfung auch bei enabled = TRUE nicht und der Beleg entsteht trotzdem. FACTURX_ENGINE ist der Laufzeit-Kill-Switch für Betreiber, die
-- Ardennes-X lizenziert haben, die Factur-X-Engine aber (noch) nicht anbieten wollen — strikt
-- Opt-in (PO-Entscheid 25.08.2026), der Standard-Stack startet den facturx-engine-api-Container
-- nicht mehr (siehe docker-compose.yml / docker/compose/facturx.yml — eigenes Compose-Profil
-- `facturx`, nicht `core`, plus Makefile-Guard).
INSERT INTO validation_setting (capability_id, enabled, created_by, updated_by)
VALUES ('OMLX_LLM_CHECK', TRUE, 'seed', 'seed'),
       ('VERAPDF_CHECK',  TRUE, 'seed', 'seed'),
       ('FACTURX_ENGINE', FALSE, 'seed', 'seed')
ON CONFLICT (capability_id) DO NOTHING;

-- ── 7. Nummernkreise (number_range, task13-N1/N3/N4) ─────────────────────────────────────────
--
-- Drei Standardkreise je Gegenstand plus INVOICE_GAPLESS: GLOBAL statt PER_SELLER (eine Folge
-- über alle Anbieter), GAPLESS statt UNIQUE_ONLY (die Zählung darf keine Lücke haben) und
-- YEAR_MONTH_DAY statt YEAR_MONTH (Tagesanteil in der Nummer). INVOICE_DEFAULT (abgelöst durch
-- INVOICE_GAPLESS) seedet eine frische Installation nicht — datagen legt es sich bei Bedarf
-- selbst an.
--
-- start_value bleibt überall 1 als Werkseinstellung — wer aus einem Vorsystem umsteigt, ändert
-- ihn in den Referenzdaten, solange der Kreis noch nichts vergeben hat (N1 Punkt 7). Bestand ohne
-- Nummer wird NICHT nachgezogen (N3 — „Kein Backfill von Nummern").
--
-- ON CONFLICT DO NOTHING hält diesen Block wiederholbar aufsetzbar (DatagenComplianceIT wendet
-- ihn ein zweites Mal an, um die Idempotenz zu beweisen).
--
-- subject: INVOICE=0, CUSTOMER=1, ARTICLE=2, SELLER=3, QUOTE=4 (the counter of QUOTE lives in the quote module)
-- prefix_source: NONE=0, FIXED=1, SELLER_PROFILE=2, FROM_DATA=3
-- prefix_data_field: NAME_INITIALS=0, COUNTRY_CODE=1, ARTICLE_CATEGORY=2
-- date_part: NONE=0, YEAR=1, YEAR_MONTH=2, YEAR_MONTH_DAY=3
-- reset_cycle: NEVER=0, YEARLY=1
-- scope: GLOBAL=0, PER_SELLER=1
-- continuity: GAPLESS=0, UNIQUE_ONLY=1
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

-- ── 8. B2B-Profil „Neckar" (generischer Vollprofil-Zuschnitt, PO 2026-08-31) ────────────────────
--
-- Nach der Entfernung von „Saar" (PO-Entscheid, Profil + Rechnungsvorlage ersatzlos gestrichen)
-- ist „Neckar" wieder das einzige ausgelieferte Profil (ARC-12) — kein Sonderfall im Code, ein
-- normales, bearbeitbares B2B-Profil; wer die Regeln ändern will, kopiert die Zeile statt den
-- Katalog zu ändern. bt_code/condition_bt_code sind ab BTE-2 die amtlichen EN-16931-BT-Nummern
-- (de.littletrotter.shared.b2b.B2bTerm.code), z. B. 155 für BT-155 — kein Katalog-FK mehr, die
-- Zulässigkeit/Bindung des Terms lebt in B2bTerm.
INSERT INTO b2b_profile (name, description, active, version)
VALUES (
    'Neckar',
    'Vollständiger B2B-Feldsatz für Käufer, die auf der Rechnung Projektreferenzen (BT-11), Kostenstellen (BT-19) und kundenspezifische Artikelnummern (BT-155/BT-156) verlangen. Kopie anpassen statt Katalog ändern.',
    TRUE,
    0
)
ON CONFLICT (name) DO NOTHING;

-- requirement: OPTIONAL=0, MANDATORY=1, CONDITIONAL=2, CONDITIONAL_IF_EMPTY=3
-- placement: INVOICE=0, RECIPIENT=1, SELLER=2, BOTH=3
INSERT INTO b2b_profile_field (profile_id, bt_code, business_definition, requirement, condition_bt_code, placement, sort_order, default_value, version)
SELECT p.id, v.bt_code, v.business_definition, v.requirement, v.condition_bt_code, v.placement, v.sort_order, v.default_value, 0
FROM (SELECT id FROM b2b_profile WHERE name = 'Neckar') p
CROSS JOIN (VALUES
    (19,  'Kostenstelle des Käufers (BT-19) — Pflichtangabe; ohne Kostenstelle wird die Rechnung abgelehnt.',                                         1,            NULL::smallint, 0, 1,  NULL::varchar),
    (72,  'Liefer-/Leistungsdatum (BT-72) — Pflicht, sofern nicht auf jeder Position beide Positions-Leistungszeiträume (BT-134/BT-135) befüllt sind (Entweder-oder-Paar mit den Positionszeiträumen).', 3, 134::smallint, 0, 2,  NULL::varchar),
    (73,  'Leistungszeitraum von (BT-73) — Alternative zum Liefer-/Leistungsdatum (BT-72).',                                                         0,              NULL::smallint, 0, 3,  NULL::varchar),
    (74,  'Leistungszeitraum bis (BT-74) — Alternative zum Liefer-/Leistungsdatum (BT-72).',                                                         0,              NULL::smallint, 0, 4,  NULL::varchar),
    (84,  'Bankverbindung IBAN des Verkäufers (BT-84) — Pflichtangabe.',                                                                              1,             NULL::smallint, 2,  5,  NULL::varchar),
    (58,  'E-Mail-Adresse der Käufer-Kontaktstelle (BT-58) — Pflichtangabe; Anzeigeort Kunde (ARC-23): je Kunde pflegbar, kein fester Standardwert am Profil.', 1, NULL::smallint, 1,  6,  NULL::varchar),
    (56,  'Ansprechpartner bzw. bestellende Abteilung des Käufers (BT-56) — sofern bei Bestellung mitgeteilt.',                                       0,              NULL::smallint, 0, 7,  NULL::varchar),
    (13,  'Bestellnummer des Käufers (BT-13) — sofern bei Bestellung mitgeteilt; sobald gesetzt, werden BT-155 und BT-156 Pflicht.',                  0,              NULL::smallint, 0, 8,  NULL::varchar),
    (12,  'Vertragsnummer (BT-12) — bei Mietsachverhalten, z. B. Mietrechnungen oder Nebenkostenabrechnungen.',                                      0,              NULL::smallint, 0, 9,  NULL::varchar),
    (18,  'Innenauftrag (BT-18, Kennungsschema „IL") — sofern bei Bestellung mitgeteilt.',                                                           0,              NULL::smallint, 0, 10, NULL::varchar),
    (134, 'Positions-Leistungszeitraum von (BT-134) — Pflicht auf jeder Position, sofern kein Liefer-/Leistungsdatum (BT-72) erfasst ist (Entweder-oder-Paar mit BT-72).', 3, 72::smallint, 0, 11, NULL::varchar),
    (135, 'Positions-Leistungszeitraum bis (BT-135) — Pflicht auf jeder Position, sofern kein Liefer-/Leistungsdatum (BT-72) erfasst ist (Entweder-oder-Paar mit BT-72).', 3, 72::smallint, 0, 12, NULL::varchar),
    (155, 'Artikelnummer des Verkäufers (BT-155) — verpflichtend, sobald eine Bestellnummer (BT-13) angegeben ist.',                                  2,           13::smallint, 0, 13, NULL::varchar),
    (156, 'Artikelnummer des Käufers (BT-156) — verpflichtend, sobald eine Bestellnummer (BT-13) angegeben ist.',                                     2,           13::smallint, 0, 14, NULL::varchar),
    (11,  'Projektreferenz / Auftragsnummer des Käufers (BT-11) — sofern bei Bestellung mitgeteilt.',                                                 0,              NULL::smallint, 0, 15, NULL::varchar)
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
    ('feature.extended-profile.enabled', FALSE, 'ZUGFeRD EXTENDED fuer Skonto-Rechnungen (Opt-in)')
ON CONFLICT (key) DO NOTHING;
