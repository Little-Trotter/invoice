-- =============================================================================
-- V1__baseline_schema.sql — Little Trotter Invoice, greenfield schema baseline (H27-29)
-- =============================================================================
--
-- Replaces the grown Flyway ddl history V001-V014 (H27-29 squash, 28.08.2026): since
-- 22.08.2026 the project is a single open-source component with no external installations
-- whose migration history must be preserved (greenfield rule — delete legacy, don't curate
-- it). This file describes the schema as it IS, not how it came to be: every column,
-- constraint, index and comment appears once, in its final state. Nothing here corrects
-- anything else here.
--
-- Rules that hold for the whole file:
--
--   * Enum-valued columns are stored as SMALLINT/INTEGER (enum ordinal via
--     OrdinalEnumConverter), a deliberate, project-wide convention: no enum is ever
--     persisted as a VARCHAR code. String representations such as ISO 3166-1 country codes
--     are used only for display and XRechnung/ZUGFeRD export, never in the DB. All FK
--     columns are constrained to positive integers.
--   * Two enum-CHECK styles coexist by design. Older columns (invoice.status,
--     validation_status, currency, type_code, article.default_unit, invoice_item.unit,
--     trip_expense.kind/unit, address.address_type, generation_job.status, invoice_document.
--     generation_status/validation_status, invoice_llm_analysis.status,
--     database_maintenance_event.type/status) constrain the value to a contiguous ordinal
--     range (0 <= value <= max), so appending a new enum constant at the end of the entries
--     list never requires a schema change. Columns that were later converted from VARCHAR to
--     SMALLINT (system_message.severity/source, recipient.customer_type/
--     vat_id_verification_status, b2b_field_value.owner_type,
--     b2b_profile_field.requirement/placement, mileage_rate.vehicle_type,
--     invoice_party_snapshot.party_role, number_range.subject/
--     prefix_source/prefix_data_field/date_part/reset_cycle/scope/continuity,
--     sender_profile.legal_form), plus article.category/invoice_item.category (RK-1: the code
--     sequence has a deliberate gap left by the deleted TRAVEL_EXPENSE/EXPENSE/PER_DIEM
--     categories, so a contiguous range would wrongly accept those retired values) use an
--     explicit IN-list instead: an IN-list names every valid value and fails loudly
--     (DatagenComplianceIT) the moment the Kotlin enum and the database constraint disagree,
--     rather than silently accepting an out-of-range value.
--     CONTRACT (holds for every enum CHECK in this file, range-style or IN-list-style):
--     ordinal literals mirror today's Kotlin enum definitions (OrdinalEnumConverter). When
--     adding a new enum constant, extend the corresponding CHECK constraint in the SAME
--     commit as the enum change — never leave a drift where the enum and the database
--     constraint disagree.
--   * A few columns still carry a VARCHAR with an explicit IN-list of string values
--     (validation_profile.state) on purpose: those rows are read and written by humans in seed
--     scripts/config, where a reordered enum must not silently swap meanings.
--     invoice.document_language (IN-1) used to be one of these; it now keeps its human-readable
--     VARCHAR code but is constrained by a real foreign key to the `language` table instead of an
--     IN-list — a new document language is a data row, not a schema change. The reason for the
--     readable code (seed scripts/config stay legible) still holds; the reason for the IN-list
--     (a static allowed set) is gone.
--   * Two columns are deliberately NOT enum-CHECKed for *membership*: b2b_profile_country.country
--     (see its own comment below) and the business-term-code columns b2b_profile_field.bt_code /
--     b2b_profile_field.condition_bt_code / b2b_field_value.bt_code (BTE-2) —
--     the allowed set of business-term codes grows with every release
--     (a new EN 16931 term can be added to de.littletrotter.shared.b2b.B2bTerm without a
--     migration), so membership enforcement lives in that domain enum instead of a static SQL
--     IN-list. The bt_code columns DO carry a *range* CHECK (BTE-8, PO 30.08.2026):
--     0 <= code <= 10616 — the upper bound is the greatest existing B2bTerm.code (BT-X-616,
--     BT-X block = 10000 + nn), a stable ceiling that rejects garbage/negative values without
--     freezing the term set. B2bTermSchemaRangeTest fails the build as soon as B2bTerm outgrows
--     the literal, forcing a baseline bump alongside the enum change.
--   * Column order inside a table that had a VARCHAR-to-SMALLINT conversion reflects the
--     physical order Postgres produced by the historical ADD COLUMN / DROP COLUMN / RENAME
--     COLUMN sequence: the converted column sits at the end of the table, not in its
--     original thematic position. This is cosmetic, not a contract — Kotlin entities map by
--     name, never by position.
--   * `recipient.routing_id` keeps its name although it is fachlich the default buyer
--     reference (BT-10, at B2G usually the Leitweg-ID). Renaming it would touch the
--     positional JPQL projection in InvoiceReadRepositoryImpl.findInvoiceDetailDto plus
--     RecipientView, SenderProfileView, InvoiceDetailView and SellerProfileService — documented
--     as follow-up work instead of silently risked. The counterpart at the seller
--     (`sender_profile.routing_id`) does not exist: a Leitweg-ID identifies the public
--     *recipient* of an invoice, never the seller, and the column was never wired into any
--     e-invoice output.
--
-- Sections:
--   1. Reference data (system-supplied rate catalogs)
--   2. B2B business-term profiles
--   3. Master data (user-managed catalog)
--   4. Invoice aggregate
--   5. Generated documents, LLM analyses and jobs
--   6. Validation / compliance
--   7. Number ranges, counters and journal
--   8. System / maintenance
--   9. Messaging (MC-1/ARC-9)
--   10. Statistics views
--   11. goauthentik role/schema bootstrap
--   12. RS-8 invoice audit journal (hash-chained, trigger-based)
--   13. RS-7 role separation: lt_migrator (DDL/owner) vs. lt_app (runtime DML)
--
-- Prerequisite: the pg_trgm and pgcrypto extensions must be installable by the migrating role.
-- Both are PostgreSQL-bundled "trusted" extensions (installable by a non-superuser with plain
-- CREATE privilege on the schema since PG13), so lt_migrator needs no elevated grant for either.

CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS pgcrypto; -- RS-8 — digest()/encode() for invoice_audit_journal's hash chain

-- =============================================================================
-- 1. Reference data (system-supplied rate catalogs)
-- =============================================================================

-- IN-1 — the language set a document can be written in. A third document language is
-- one row here plus localized_text rows, never a schema change.
CREATE TABLE language (
    id          SMALLINT     PRIMARY KEY,
    code        VARCHAR(5)   NOT NULL UNIQUE,
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    is_fallback BOOLEAN      NOT NULL DEFAULT FALSE,
    sort_order  SMALLINT     NOT NULL,
    CONSTRAINT chk_language_code_format CHECK (code ~ '^[a-z]{2}(-[A-Z]{2})?$')
);

COMMENT ON TABLE language IS 'IN-1 — system-supplied set of document languages (ISO 639-1 / BCP-47 code). id is stable and never re-used.';
COMMENT ON COLUMN language.is_fallback IS 'Exactly one row is TRUE (uq_language_single_fallback). A document must never be half-translated: the resolution function falls back to this language whenever a text is missing in the document language.';

CREATE UNIQUE INDEX uq_language_single_fallback ON language ((TRUE)) WHERE is_fallback;

-- The two rows below are the only data in this DDL file, and they are here on purpose: they are
-- schema, not seed. `invoice.document_language` is NOT NULL DEFAULT 'de' and references
-- `language.code` -- so without the 'de' row no invoice row can be inserted at all, and the
-- invoice table is unusable. The allowed set previously lived in this same file as
-- `chk_invoice_document_language`; replacing that CHECK with a foreign key moved the values from
-- a constraint into rows, and they stay where the constraint was. V2 therefore does not seed
-- them, and TestSchema (DDL only, no seed) keeps working for every IT that inserts invoices.
INSERT INTO language (id, code, is_active, is_fallback, sort_order) VALUES
    (1, 'de', TRUE, TRUE,  10),
    (2, 'en', TRUE, FALSE, 20);

-- IN-1 — a slot for a piece of document text that may exist in more than one language.
-- purpose follows the enum-as-smallint convention this file's header documents: ordinal
-- literals mirror LocalizedTextPurpose, and the CHECK IN-list is extended in the same
-- commit as any new constant.
CREATE TABLE localized_text_group (
    id      BIGSERIAL PRIMARY KEY,
    purpose SMALLINT  NOT NULL,
    CONSTRAINT chk_localized_text_group_purpose CHECK (purpose IN (0, 1, 2)) -- 0=SELLER_INVOICE_NOTE,1=PAYMENT_TERM_NAME,2=SELLER_CONSUMER_NOTE
);

COMMENT ON TABLE localized_text_group IS 'IN-1 — one text slot, resolved per document language; owned by exactly one master-data record (e.g. sender_profile.invoice_note_group_id), never the other way round.';

CREATE TABLE localized_text (
    group_id    BIGINT   NOT NULL REFERENCES localized_text_group (id) ON DELETE CASCADE,
    language_id SMALLINT NOT NULL REFERENCES language (id),
    value       TEXT     NOT NULL,
    CONSTRAINT pk_localized_text PRIMARY KEY (group_id, language_id),
    CONSTRAINT chk_localized_text_value_not_blank CHECK (LENGTH(TRIM(value)) > 0)
);

COMMENT ON TABLE localized_text IS 'IN-1 — at most one entry per language per group (primary key); a group is worthless without its texts, so ON DELETE CASCADE removes them with the group.';
COMMENT ON COLUMN localized_text.value IS 'Never blank (chk_localized_text_value_not_blank) — a missing translation is the absence of a row, never an empty string.';

-- IN-4 — name/english_name VARCHAR(150) replaced by name_group_id: one LocalizedTextGroup
-- (LocalizedTextPurpose.PAYMENT_TERM_NAME) per term, resolved per document language
-- (PaymentTerm.nameFor) instead of two fixed columns. NOT NULL: a payment term without a name in
-- at least the fallback language is not valid (PaymentTerm.validate()).
CREATE TABLE payment_term (
    id               BIGSERIAL     PRIMARY KEY,
    name_group_id    BIGINT        NOT NULL REFERENCES localized_text_group (id),
    description      TEXT,
    due_in_days      INT           NOT NULL DEFAULT 30,
    discount_percent NUMERIC(5,2)  NOT NULL DEFAULT 0.00,
    discount_days    INT,
    is_default       BOOLEAN       NOT NULL DEFAULT FALSE,
    version          BIGINT        DEFAULT 0,
    created_at       TIMESTAMPTZ,
    is_active        BOOLEAN       NOT NULL DEFAULT TRUE,
    CONSTRAINT chk_payment_term_due_in_days_non_negative   CHECK (due_in_days >= 0),
    CONSTRAINT chk_payment_term_discount_percent_range     CHECK (discount_percent >= 0 AND discount_percent <= 100),
    CONSTRAINT chk_payment_term_discount_days_non_negative CHECK (discount_days IS NULL OR discount_days >= 0),
    CONSTRAINT chk_payment_term_discount_combination
        CHECK ((discount_percent = 0 AND discount_days IS NULL) OR (discount_percent > 0 AND discount_days IS NOT NULL))
);

COMMENT ON COLUMN payment_term.name_group_id IS 'IN-4 — the term''s display name, per language (PaymentTerm.nameFor/setName). Replaces the former name/english_name VARCHAR(150) columns.';
COMMENT ON COLUMN payment_term.is_active IS 'R9 — nur aktive Bedingungen stehen bei neuen Rechnungen zur Auswahl; Altzeilen bleiben für bestehende Rechnungen erhalten.';

CREATE UNIQUE INDEX uq_payment_term_single_default   ON payment_term ((is_default)) WHERE is_default = TRUE;
CREATE INDEX        idx_payment_term_sort_due_in_days ON payment_term (due_in_days);
CREATE INDEX        idx_payment_term_name_group_id    ON payment_term (name_group_id);

-- tax_rate carries a full tax *rule* (R1), not just a percentage: two fachlich different 0 %
-- cases (reverse charge / exempt / outside scope) must be distinguishable in the data.
CREATE TABLE tax_rate (
    id                    BIGSERIAL    PRIMARY KEY,
    name                  VARCHAR(255) NOT NULL,
    rate                  NUMERIC(5,2) NOT NULL DEFAULT 0.00,
    country               INTEGER      NOT NULL DEFAULT 276,
    valid_from            DATE,
    valid_to              DATE,
    is_hotel_rate         BOOLEAN      NOT NULL DEFAULT FALSE,
    is_default            BOOLEAN      NOT NULL DEFAULT FALSE,
    is_reference          BOOLEAN      NOT NULL DEFAULT FALSE,
    version               BIGINT       DEFAULT 0,
    created_at            TIMESTAMPTZ,
    code                  VARCHAR(64)  NOT NULL,
    category              VARCHAR(4),   -- UNTDID 5305 / EN-16931: S, AE, E, O, Z, G, K
    exemption_reason_code VARCHAR(32),  -- z. B. VATEX-EU-AE
    exemption_reason_text TEXT,
    legal_basis           TEXT,
    is_active             BOOLEAN      NOT NULL DEFAULT TRUE,
    is_selectable         BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_tax_rate_code UNIQUE (code),
    CONSTRAINT chk_tax_rate_country_positive CHECK (country > 0),
    CONSTRAINT chk_tax_rate_rate_range       CHECK (rate >= 0 AND rate <= 100),
    CONSTRAINT chk_tax_rate_valid_date_order CHECK (valid_from IS NULL OR valid_to IS NULL OR valid_from <= valid_to),
    CONSTRAINT chk_tax_rate_code_not_blank   CHECK (LENGTH(TRIM(code)) > 0),
    -- AE/E/O sind nach Definition Nullsätze (R1, Umsetzung §1).
    CONSTRAINT chk_tax_rate_zero_rate_categories
        CHECK (category NOT IN ('AE', 'E', 'O') OR rate = 0.00),
    -- Reverse Charge trägt immer denselben, eindeutigen Befreiungsgrund. IS NOT DISTINCT FROM
    -- statt "=": ein NULL exemption_reason_code würde "category <> 'AE' OR code = 'VATEX-EU-AE'"
    -- zu NULL auswerten (SQL-Dreiwertlogik), und Postgres lässt eine CHECK-Verletzung nur bei
    -- FALSE greifen, nicht bei NULL — mit "=" hätte die Regel NULL-Befreiungsgründe stillschweigend
    -- durchgelassen.
    CONSTRAINT chk_tax_rate_ae_reason
        CHECK (category <> 'AE' OR exemption_reason_code IS NOT DISTINCT FROM 'VATEX-EU-AE'),
    -- Steuerbefreit / nicht steuerbar müssen ihren Grund nennen — ein Nullsatz ohne Begründung
    -- ist in einer Rechnung nicht rechtssicher (R1, Umsetzung §1).
    CONSTRAINT chk_tax_rate_e_o_reason_text
        CHECK (category NOT IN ('E', 'O') OR (exemption_reason_text IS NOT NULL AND LENGTH(TRIM(exemption_reason_text)) > 0)),
    -- Eine auswählbare Regel muss klassifiziert sein; nur stillgelegte Legacy-Zeilen dürfen
    -- ohne Kategorie stehen.
    CONSTRAINT chk_tax_rate_selectable_needs_category
        CHECK (NOT is_selectable OR category IS NOT NULL),
    CONSTRAINT chk_tax_rate_reference_eligible
        CHECK (NOT is_reference OR (is_active AND is_selectable AND category = 'S' AND NOT is_hotel_rate AND rate > 0)),
    -- Monkey finding (2026-08-30) — VatCategory.kt already models all 7 UNTDID 5305 codes the
    -- domain needs (S/Z/E/AE/O/G/K); this CHECK used to only accept S/AE/E/O, so a ZERO_RATED
    -- ('Z'), FREE_EXPORT_ITEM ('G') or INTRA_COMMUNITY_SUPPLY ('K') tax_rate row could never
    -- actually be persisted. Baseline edited in place per project convention (no live instance
    -- yet) — widened to the full enum instead of adding a migration.
    CONSTRAINT chk_tax_rate_category_values
        CHECK (category IS NULL OR category IN ('S', 'AE', 'E', 'O', 'Z', 'G', 'K'))
);

COMMENT ON COLUMN tax_rate.code IS 'Stabiler, eindeutiger Regel-Code (R1) — Anzeige/Export referenzieren den Code, nicht name/rate.';
COMMENT ON COLUMN tax_rate.category IS 'UNTDID 5305 / EN-16931 Duty-and-Tax-Category-Code: S, AE, E, O, Z, G, K. NULL nur bei stillgelegten Legacy-Zeilen.';
COMMENT ON COLUMN tax_rate.is_selectable IS 'FALSE = historischer/ausländischer Satz, bleibt für Bestandsrechnungen gültig, ist aber in neuen Positionen nicht mehr wählbar.';
COMMENT ON COLUMN tax_rate.is_reference IS 'Länderbezogener Referenzsteuersatz; unabhängig vom Bedienoberflächen-Standard is_default.';

CREATE UNIQUE INDEX uq_tax_rate_single_default_per_country ON tax_rate (country) WHERE is_default = TRUE;
CREATE UNIQUE INDEX uq_tax_rate_single_reference_per_country ON tax_rate (country) WHERE is_reference = TRUE;
CREATE INDEX        idx_tax_rate_country_lookup             ON tax_rate (country);

CREATE TABLE per_diem_rate (
    id                     BIGSERIAL     PRIMARY KEY,
    short_name             VARCHAR(255),
    country                INTEGER       NOT NULL DEFAULT 276,
    region                 VARCHAR(255),
    full_day_rate          NUMERIC(10,2) NOT NULL DEFAULT 0.00,
    arrival_departure_rate NUMERIC(10,2) NOT NULL DEFAULT 0.00,
    inland_rules           BOOLEAN       NOT NULL DEFAULT FALSE,
    is_default             BOOLEAN       NOT NULL DEFAULT FALSE,
    valid_from             DATE,
    valid_to               DATE,
    version                BIGINT        DEFAULT 0,
    created_at             TIMESTAMPTZ,
    is_active              BOOLEAN       NOT NULL DEFAULT TRUE,
    source                 TEXT,
    CONSTRAINT chk_per_diem_country_positive                    CHECK (country > 0),
    CONSTRAINT chk_per_diem_full_day_rate_non_negative          CHECK (full_day_rate >= 0),
    CONSTRAINT chk_per_diem_arrival_departure_rate_non_negative CHECK (arrival_departure_rate >= 0),
    CONSTRAINT chk_per_diem_valid_date_order                    CHECK (valid_from IS NULL OR valid_to IS NULL OR valid_from <= valid_to),
    CONSTRAINT chk_per_diem_rate_active_needs_source
        CHECK (NOT is_active OR (source IS NOT NULL AND LENGTH(TRIM(source)) > 0))
);

COMMENT ON COLUMN per_diem_rate.is_active IS 'FALSE = durch eine neuere Jahres-/BMF-Fassung abgelöste Zeile, bleibt für Bestandsrechnungen gültig.';
COMMENT ON COLUMN per_diem_rate.source IS 'Fundstelle, z. B. "BMF-Schreiben vom 05.12.2025, IV C 5 - S 2353/00094/007/012" oder "§ 9 Abs. 4a Satz 3 EStG" (Inland).';

-- Die deutsche Kilometerpauschale (§ 9 Abs. 1 Nr. 4a EStG i.V.m. § 5 BRKG) ist unabhängig vom
-- Reiseland und hängt stattdessen vom Fahrzeugtyp ab (R2). `country` bleibt im Schema, weil
-- historische Rechnungen weiterhin auf länderabhängige Altzeilen zeigen.
CREATE TABLE mileage_rate (
    id           BIGSERIAL    PRIMARY KEY,
    country      INTEGER      NOT NULL,
    rate_per_km  NUMERIC(6,3) NOT NULL DEFAULT 0.000,
    valid_from   DATE,
    valid_to     DATE,
    version      BIGINT       DEFAULT 0,
    created_at   TIMESTAMPTZ,
    is_active    BOOLEAN      NOT NULL DEFAULT TRUE,
    source       TEXT,
    vehicle_type SMALLINT,  -- CAR=0, OTHER_MOTOR_VEHICLE=1
    CONSTRAINT chk_mileage_country_positive CHECK (country > 0),
    CONSTRAINT chk_mileage_rate_vehicle_type
        CHECK (vehicle_type IS NULL OR vehicle_type IN (0, 1)),
    -- Eine auswählbare Zeile muss einen Fahrzeugtyp tragen; nur stillgelegte Altzeilen (Land
    -- statt Fahrzeugtyp) dürfen ohne Fahrzeugtyp stehen.
    CONSTRAINT chk_mileage_rate_active_needs_vehicle_type
        CHECK (NOT is_active OR vehicle_type IS NOT NULL)
);

COMMENT ON COLUMN mileage_rate.vehicle_type IS 'R2: löst die bisherige länderabhängige Auflösung ab — der deutsche Kilometersatz hängt vom Fahrzeugtyp ab, nicht vom Reiseland (§ 9 Abs. 1 Nr. 4a EStG / § 5 BRKG). country bleibt im Schema, weil Altdaten und historische Rechnungen weiterhin darauf zeigen.';
COMMENT ON COLUMN mileage_rate.is_active IS 'FALSE = historische/länderspezifische Altzeile, bleibt für Bestandsrechnungen gültig, ist aber in neuen Positionen nicht mehr wählbar. Dieselbe Regel wie tax_rate.is_selectable (R1).';
COMMENT ON COLUMN mileage_rate.source IS 'Rechtsgrundlage/Fundstelle der Zeile, z. B. "§ 5 Abs. 2 BRKG".';

CREATE INDEX idx_mileage_rate_sort_country     ON mileage_rate (country);
CREATE INDEX idx_mileage_rate_sort_rate_per_km ON mileage_rate (rate_per_km);
CREATE INDEX idx_mileage_rate_sort_valid_from  ON mileage_rate (valid_from);

CREATE TABLE hotel_rate (
    id             BIGSERIAL     PRIMARY KEY,
    country        INTEGER       NOT NULL DEFAULT 276,
    rate_per_night NUMERIC(10,2) NOT NULL DEFAULT 0.00,
    valid_from     DATE,
    valid_to       DATE,
    version        BIGINT        DEFAULT 0,
    created_at     TIMESTAMPTZ,
    region         VARCHAR(255),
    is_active      BOOLEAN       NOT NULL DEFAULT TRUE,
    source         TEXT,
    CONSTRAINT chk_hotel_rate_country_positive       CHECK (country > 0),
    CONSTRAINT chk_hotel_rate_per_night_non_negative CHECK (rate_per_night >= 0),
    CONSTRAINT chk_hotel_rate_valid_date_order       CHECK (valid_from IS NULL OR valid_to IS NULL OR valid_from <= valid_to),
    CONSTRAINT chk_hotel_rate_active_needs_source
        CHECK (NOT is_active OR (source IS NOT NULL AND LENGTH(TRIM(source)) > 0))
);

COMMENT ON COLUMN hotel_rate.region IS 'R2: Städte mit abweichender Übernachtungspauschale (z. B. Paris, London, Rom) — analog zu per_diem_rate.region. NULL = Landeszeile.';
COMMENT ON COLUMN hotel_rate.is_active IS 'FALSE = durch eine neuere Jahres-/BMF-Fassung abgelöste Zeile, bleibt für Bestandsrechnungen gültig.';
COMMENT ON COLUMN hotel_rate.source IS 'Fundstelle, siehe per_diem_rate.source.';

CREATE INDEX idx_hotel_rate_country ON hotel_rate (country);

-- =============================================================================
-- 2. B2B business-term profiles (task12-b2b/T1, BTE-2)
-- =============================================================================
-- The EN 16931 / ZUGFeRD business-term taxonomy itself (level, field kind, binding, xpath,
-- bound property, max length) is codified as the Kotlin enum de.littletrotter.shared.b2b.B2bTerm
-- (kotlin-shared), not seeded into a table (BTE-2) — the catalog was
-- always a curated, code-level constant with no application-level write path, so every new term
-- already required a release; the former b2b_field_catalog table added a whole persistence layer
-- for no real flexibility. bt_code columns below store B2bTerm.code (the official EN 16931 BT
-- number, e.g. 19 for BT-19), enforced in the domain model, not by a DB CHECK (see the file-level
-- comment above).

CREATE TABLE b2b_profile (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(150) NOT NULL,
    description TEXT,
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    version     BIGINT DEFAULT 0,
    CONSTRAINT uq_b2b_profile_name UNIQUE (name)
);

COMMENT ON TABLE b2b_profile IS 'task12-b2b/T1 — a named collection of B2bProfileFields. AP-9 — assigned 0..1 to a recipient (standing default) and, per invoice, to that invoice''s own frozen choice (recipient.b2b_profile_id / invoice.b2b_profile_id); no longer to a sender_profile.';

-- anbieter-einvoice-ergonomie §2 — country scope for B2B profiles. Empty scope (no row here for
-- a profile) = unscoped = visible for every seller, the behavior-preserving default.
-- Deliberately no CHECK on `country`: the allowed set (Country.SUPPORTED_SELLER_VAT_
-- REGISTRATION_COUNTRIES plus DE, see B2bProfile.ALLOWED_COUNTRY_SCOPE) is derived from EU
-- membership as of today, a moving target a static SQL CHECK cannot track — enforced
-- in the domain layer (B2bProfile.validate()) instead.
CREATE TABLE b2b_profile_country (
    profile_id BIGINT  NOT NULL REFERENCES b2b_profile (id),
    country    INTEGER NOT NULL,
    CONSTRAINT pk_b2b_profile_country PRIMARY KEY (profile_id, country)
);

COMMENT ON TABLE b2b_profile_country IS 'anbieter-einvoice-ergonomie §2 — country scope of a B2bProfile''s SELLER/BOTH fields. Empty (no rows for a profile) = unscoped = always visible, the pre-existing behavior.';
COMMENT ON COLUMN b2b_profile_country.country IS 'ISO 3166-1 numeric, same convention as tax_rate.country / seller_vat_registration.country.';

CREATE TABLE b2b_profile_field (
    id                  BIGSERIAL PRIMARY KEY,
    profile_id          BIGINT NOT NULL REFERENCES b2b_profile (id),
    bt_code             SMALLINT NOT NULL,
    business_definition TEXT NOT NULL,
    condition_bt_code   SMALLINT,
    sort_order          INTEGER NOT NULL DEFAULT 0,
    version             BIGINT DEFAULT 0,
    -- NEKAR-T5 — optional value that pre-fills this FREE field's widget on a new record with no
    -- stored b2b_field_value yet; NULL for a BOUND term (enforced by
    -- B2bProfileField.assertDefaultValueCompatibleWithTerm, not a DB constraint — the binding
    -- lives on the B2bTerm constant, not on this row). Generous VARCHAR(512) ceiling, not
    -- B2bTerm.maxLength: the actual per-term limit is validated at save time.
    default_value       VARCHAR(512),
    requirement         SMALLINT NOT NULL,  -- OPTIONAL=0, MANDATORY=1, CONDITIONAL=2, CONDITIONAL_IF_EMPTY=3
    placement           SMALLINT NOT NULL,  -- INVOICE=0, RECIPIENT=1, SELLER=2, BOTH=3
    CONSTRAINT uq_b2b_profile_field_profile_bt_code UNIQUE (profile_id, bt_code),
    -- BTE-8 — range guard only (0..greatest existing B2bTerm.code, BT-X-616 = 10616); term
    -- membership stays enum-enforced, see the file-level comment on enum-CHECK styles above.
    CONSTRAINT chk_b2b_profile_field_bt_code_range CHECK (bt_code BETWEEN 0 AND 10616),
    CONSTRAINT chk_b2b_profile_field_condition_bt_code_range CHECK (condition_bt_code BETWEEN 0 AND 10616),
    CONSTRAINT chk_b2b_profile_field_requirement CHECK (requirement IN (0, 1, 2, 3)),
    CONSTRAINT chk_b2b_profile_field_placement CHECK (placement IN (0, 1, 2, 3))
);

COMMENT ON TABLE b2b_profile_field IS 'task12-b2b/T1, BTE-2 — one B2bTerm chosen into a B2bProfile: the anwender''s own definition, requirement and placement. At most one row per (profile, bt_code). CONDITIONAL_IF_EMPTY (3) is the either-or/XOR counterpart to CONDITIONAL (2): a field becomes mandatory when another term of the same profile is *empty* (CONDITIONAL fires when the other term *has* a value instead) — see B2bFieldRequirement''s KDoc. placement RECIPIENT (1, ARC-23) means the field is maintained on the customer record (see recipient_b2b_field_value) instead of the invoice header.';
COMMENT ON COLUMN b2b_profile_field.bt_code IS 'BTE-2 — official EN 16931 BT number (de.littletrotter.shared.b2b.B2bTerm.code), e.g. 19 for BT-19. Range-CHECKed 0..10616 only (BTE-8); membership stays enum-enforced, see the file-level comment on enum-CHECK styles above.';
COMMENT ON COLUMN b2b_profile_field.condition_bt_code IS 'BTE-2 — B2bTerm.code of the sibling profile field this CONDITIONAL/CONDITIONAL_IF_EMPTY field''s requirement depends on; NULL unless requirement is one of those two. Same range CHECK as bt_code (BTE-8), no membership CHECK, and no coupling CHECK to requirement — that rule lives in B2bProfileField.validate().';

CREATE TABLE b2b_field_value (
    id         BIGSERIAL PRIMARY KEY,
    owner_id   BIGINT NOT NULL,
    bt_code    SMALLINT NOT NULL,
    value      TEXT,
    version    BIGINT DEFAULT 0,
    owner_type SMALLINT NOT NULL,  -- SENDER_PROFILE=0, INVOICE=1, INVOICE_ITEM=2, ARTICLE=3
    CONSTRAINT uq_b2b_field_value_owner_bt_code UNIQUE (owner_type, owner_id, bt_code),
    -- BTE-8 — same range guard as b2b_profile_field.bt_code above.
    CONSTRAINT chk_b2b_field_value_bt_code_range CHECK (bt_code BETWEEN 0 AND 10616),
    CONSTRAINT chk_b2b_field_value_owner_type CHECK (owner_type IN (0, 1, 2, 3))
);

COMMENT ON TABLE b2b_field_value IS 'task12-b2b/T1 — value of one FREE term (de.littletrotter.shared.b2b.B2bTerm) on one owning record (seller/invoice/position/article).';
COMMENT ON COLUMN b2b_field_value.bt_code IS 'BTE-2 — official EN 16931 BT number (B2bTerm.code). Range-CHECKed 0..10616 only (BTE-8); membership stays enum-enforced, see the file-level comment on enum-CHECK styles above.';

-- =============================================================================
-- 3. Master data (user-managed catalog)
-- =============================================================================

CREATE TABLE sender_profile (
    id                                   BIGSERIAL    PRIMARY KEY,
    name                                 VARCHAR(255) NOT NULL,
    country                              INTEGER      NOT NULL DEFAULT 276,
    tax_number                           VARCHAR(50),
    vat_id                               VARCHAR(64),
    iban                                 VARCHAR(34),
    bic                                  VARCHAR(11),
    bank_name                            VARCHAR(255),
    invoice_prefix                       VARCHAR(20)  NOT NULL DEFAULT 'RE-',
    is_default                           BOOLEAN      NOT NULL DEFAULT FALSE,
    phone                                VARCHAR(50),
    email                                VARCHAR(255),
    managing_director                    VARCHAR(255),
    court_of_jurisdiction                VARCHAR(255),
    commercial_register_number           VARCHAR(255),
    invoice_note_group_id                BIGINT       REFERENCES localized_text_group (id),
    consumer_note_group_id               BIGINT       REFERENCES localized_text_group (id),
    consumer_note_enabled                BOOLEAN      NOT NULL DEFAULT TRUE,
    version                              BIGINT       DEFAULT 0,
    created_at                           TIMESTAMPTZ,
    updated_at                           TIMESTAMPTZ,
    created_by                           VARCHAR(255),
    updated_by                           VARCHAR(255),
    -- Registergericht (Handelsregister-Eintrag), fachlich getrennt vom Gerichtsstand
    -- (court_of_jurisdiction) und Pflichtfeld — deshalb direkt NOT NULL mit leerem Default.
    register_court                       VARCHAR(255),
    seller_identifier_value              VARCHAR(255), -- BT-29
    seller_identifier_scheme_id          VARCHAR(10),
    legal_registration_scheme_id         VARCHAR(10),  -- Scheme zu commercial_register_number (BT-30)
    electronic_address_value             VARCHAR(255), -- BT-34
    electronic_address_scheme_id         VARCHAR(10),
    default_payment_term_id              BIGINT       REFERENCES payment_term(id),
    seller_number                        VARCHAR(60),
    seller_number_prefix                 VARCHAR(30),
    seller_number_sequence               BIGINT,
    short_hash                           VARCHAR(10)  UNIQUE,
    crafts_chamber_registered            BOOLEAN      NOT NULL DEFAULT FALSE,
    legal_form                           SMALLINT,     -- SOLE_PROPRIETOR=0, GMBH=1, GMBH_CO_KG=2, KG=3, OHG=4,
                                                       -- EG=5, AG=6, SE=7, VEREIN=8, GBR=9
    website                              VARCHAR(255),
    registered_seat                      VARCHAR(255), -- Sitz der Gesellschaft; leer = Ort der Anschrift (Renderer-Fallback)
    CONSTRAINT chk_sender_profile_country_positive CHECK (country > 0),
    CONSTRAINT chk_sender_profile_iban_format
        CHECK (iban IS NULL OR iban ~ '^([A-Z]{2}[0-9]{2}[A-Z0-9]{11,30})$'),
    CONSTRAINT chk_sender_profile_email_format
        CHECK (email IS NULL OR email ~* '^[^@\s]+@[^@\s]+\.[^@\s]+$'),
    CONSTRAINT chk_sender_seller_identifier_pair
        CHECK ((seller_identifier_value IS NULL) = (seller_identifier_scheme_id IS NULL)),
    CONSTRAINT chk_sender_electronic_address_pair
        CHECK ((electronic_address_value IS NULL) = (electronic_address_scheme_id IS NULL)),
    CONSTRAINT chk_sender_profile_default_payment_term_id_positive
        CHECK (default_payment_term_id IS NULL OR default_payment_term_id > 0),
    CONSTRAINT chk_sender_profile_legal_form CHECK (legal_form IS NULL OR legal_form IN (0, 1, 2, 3, 4, 5, 6, 7, 8, 9))
);

COMMENT ON COLUMN sender_profile.consumer_note_group_id IS 'Notice for private customers on craft-trade invoices, per language; NULL = the default wording from the language files applies.';
COMMENT ON COLUMN sender_profile.consumer_note_enabled IS 'FALSE suppresses the private-customer notice on all new invoices of this sender.';
COMMENT ON COLUMN sender_profile.invoice_note_group_id IS 'IN-2 — the seller''s own free-text notice box on the invoice ("HINWEIS"), per language; NOT the mandatory footer line (buildFooterCol1..3), which the renderer builds from name/address/register data and never reads this group. NULL = no note, which is normal.';
COMMENT ON COLUMN sender_profile.seller_identifier_value IS 'BT-29 — nur gemeinsam mit seller_identifier_scheme_id, nie als nackter Wert (Compliance-Matrix).';
COMMENT ON COLUMN sender_profile.electronic_address_value IS 'BT-34 — Wertepaar mit electronic_address_scheme_id; NICHT mit routing_id/BT-10 verwechseln.';
COMMENT ON COLUMN sender_profile.default_payment_term_id IS 'Absender-eigene Standard-Zahlungsbedingung, übernommen beim Anlegen einer neuen Rechnung (InvoiceLifecycleService.create()). NULL = fällt auf den globalen payment_term.is_default-Datensatz zurück.';
COMMENT ON COLUMN sender_profile.seller_number IS 'task13-N3 — Anbieternummer aus dem SELLER-Kreis; getrennt von invoicePrefix.';
COMMENT ON COLUMN sender_profile.short_hash IS 'task13-N3 — Kurzname, nie änderbar, nachgezogen beim Speichern.';
COMMENT ON COLUMN sender_profile.crafts_chamber_registered IS 'ARC-20 — Eintragung in einer Handwerkskammer. Zusammen mit legal_form = SOLE_PROPRIETOR (0) unterdrückt SenderProfile.showsCommercialRegister() Handelsregisternummer und Registergericht auf der Rechnung.';
COMMENT ON COLUMN sender_profile.legal_form IS 'ARC-20 — Rechtsform des Anbieters (LegalForm-Enum: SOLE_PROPRIETOR=0, GMBH=1, GMBH_CO_KG=2, KG=3, OHG=4). NULL = keine Angabe.';
COMMENT ON COLUMN sender_profile.registered_seat IS 'Sitz der Gesellschaft für die Rechnungsfußzeile. NULL/leer = der Renderer zeigt stattdessen den Ort der Rechnungsadresse.';

CREATE UNIQUE INDEX uq_sender_profile_single_default ON sender_profile ((is_default)) WHERE is_default = TRUE;
CREATE UNIQUE INDEX uq_sender_profile_invoice_prefix ON sender_profile (invoice_prefix);
CREATE UNIQUE INDEX uq_sender_profile_seller_number  ON sender_profile (seller_number) WHERE seller_number IS NOT NULL;
CREATE INDEX        idx_sender_profile_name                    ON sender_profile (name);
CREATE INDEX        idx_sender_profile_created_at              ON sender_profile (created_at);
CREATE INDEX        idx_sender_profile_default_payment_term_id ON sender_profile (default_payment_term_id);
CREATE INDEX        idx_sender_profile_name_trgm               ON sender_profile USING gin (LOWER(name) gin_trgm_ops);

CREATE TABLE recipient (
    id                            BIGSERIAL    PRIMARY KEY,
    customer_number               VARCHAR(50)  NOT NULL,
    name                          VARCHAR(255) NOT NULL,
    country                       INTEGER      NOT NULL DEFAULT 276,
    vat_id                        VARCHAR(64),
    routing_id                    VARCHAR(50),
    email                         VARCHAR(255),
    phone                         VARCHAR(50),
    contact_person                VARCHAR(255),
    iban                          VARCHAR(34),
    version                       BIGINT       DEFAULT 0,
    created_at                    TIMESTAMPTZ,
    updated_at                    TIMESTAMPTZ,
    created_by                    VARCHAR(255),
    updated_by                    VARCHAR(255),
    buyer_identifier_value        VARCHAR(255),  -- BT-46
    buyer_identifier_scheme_id    VARCHAR(10),
    legal_registration_identifier VARCHAR(255),  -- BT-47
    legal_registration_scheme_id  VARCHAR(10),
    electronic_address_value      VARCHAR(255),  -- BT-49
    electronic_address_scheme_id  VARCHAR(10),
    vat_id_verified_at            TIMESTAMPTZ,
    customer_number_prefix        VARCHAR(30),
    customer_number_sequence      BIGINT,
    short_hash                    VARCHAR(10)  UNIQUE,
    siret                         CHAR(14),
    naf_code                      VARCHAR(5),
    b2b_profile_id                BIGINT       REFERENCES b2b_profile (id),
    customer_type                 SMALLINT,  -- BUSINESS=0, CONSUMER=1
    invoice_format                SMALLINT     NOT NULL DEFAULT 0,  -- GERMAN=0, INTERNATIONAL=1
    vat_id_verification_status    SMALLINT,  -- UNVERIFIED=0, VERIFIED=1, FAILED=2
    vat_id_verification_note      VARCHAR(255),
    reverse_charge_preference     SMALLINT     NOT NULL DEFAULT 2,  -- NEVER=0, ALWAYS=1, ASK=2
    document_language             VARCHAR(5)   NOT NULL,
    CONSTRAINT fk_recipient_document_language FOREIGN KEY (document_language) REFERENCES language (code),
    CONSTRAINT chk_recipient_country_positive CHECK (country > 0),
    CONSTRAINT chk_recipient_customer_number_not_null CHECK (customer_number IS NOT NULL),
    CONSTRAINT chk_recipient_email_format
        CHECK (email IS NULL OR email ~* '^[^@\s]+@[^@\s]+\.[^@\s]+$'),
    CONSTRAINT chk_recipient_buyer_identifier_pair
        CHECK ((buyer_identifier_value IS NULL) = (buyer_identifier_scheme_id IS NULL)),
    CONSTRAINT chk_recipient_legal_registration_pair
        CHECK ((legal_registration_identifier IS NULL) = (legal_registration_scheme_id IS NULL)),
    CONSTRAINT chk_recipient_electronic_address_pair
        CHECK ((electronic_address_value IS NULL) = (electronic_address_scheme_id IS NULL)),
    CONSTRAINT chk_recipient_customer_type
        CHECK (customer_type IS NULL OR customer_type IN (0, 1)),
    CONSTRAINT chk_recipient_invoice_format
        CHECK (invoice_format IN (0, 1)),
    CONSTRAINT chk_recipient_vat_id_verification_status
        CHECK (vat_id_verification_status IS NULL OR vat_id_verification_status IN (0, 1, 2)),
    CONSTRAINT chk_recipient_reverse_charge_preference
        CHECK (reverse_charge_preference IN (0, 1, 2))
);

-- siret/naf_code tragen bewusst keine Format-CHECK-Constraint: die Prüfung (14 Ziffern bzw.
-- 4 Ziffern + Buchstabe) liegt in der Anwendung, damit eine später verschärfte Regel keine
-- Migration erzwingt, die Bestandsdaten nachträglich zurückweist.
COMMENT ON COLUMN recipient.routing_id IS 'BT-10 (Standard-Käuferreferenz, bei B2G meist die Leitweg-ID). Fachlich = defaultBuyerReference; Spaltenname bleibt aus Kompatibilitätsgründen (siehe Kopf dieser Datei).';
COMMENT ON COLUMN recipient.buyer_identifier_value IS 'BT-46 — nur gemeinsam mit buyer_identifier_scheme_id.';
COMMENT ON COLUMN recipient.electronic_address_value IS 'BT-49 — Wertepaar; NICHT routing_id/BT-10 hierher kopieren (Compliance-Matrix-Checkpoint 1/2).';
COMMENT ON COLUMN recipient.short_hash IS 'task13-N3 — Kurzname, nie änderbar, nachgezogen beim Speichern.';
COMMENT ON COLUMN recipient.siret IS 'task12-b2b/T8 — SIRET (Art. 289 CGI), 14 Ziffern, nur fuer franzoesische B2B-Kunden bei Factur-X-Rechnungen Pflicht (FinalizationGate/ArdennesXRules).';
COMMENT ON COLUMN recipient.naf_code IS 'task12-b2b/T8 — NAF-Code, 4 Ziffern + 1 Buchstabe, empfohlen aber nie blockierend.';
COMMENT ON COLUMN recipient.b2b_profile_id IS 'AP-9 — standing B2B profile default for this customer; NULL = no B2B profile (Community behavior). Moved here from sender_profile.b2b_profile_id (decision 12); invoice.b2b_profile_id is the invoice''s own derived-then-frozen choice.';
COMMENT ON COLUMN recipient.invoice_format IS 'PO 04.09.2026 — deutsche (0) oder internationale (1) Rechnung fuer diesen Kunden; steuert die Wahl der PDF-Vorlage. Vorbelegt aus dem Land (DE/AT/CH = 0), danach frei aenderbar. NICHT zu verwechseln mit invoice.invoice_format, dem Rechtsmerkmal fuer die auslaendische USt-Registrierung.';
COMMENT ON COLUMN recipient.vat_id_verification_note IS 'Free-text detail for the manual "USt-IdNr. gilt als validiert" checkbox (RecipientView) — independent of vat_id_verification_status, which the automated VIES check and the checkbox both set programmatically.';
COMMENT ON COLUMN recipient.document_language IS 'Eine Flagge fuer alle Dokumente dieses Empfaengers (Rechnung, Angebot, ...): de oder en (international). Beim Anlegen aus dem Land vorbelegt (Application-Command, kein DB-Default), danach frei aenderbar; eine Rechnung uebernimmt den Wert einmalig und friert ihn in invoice.document_language ein.';
COMMENT ON COLUMN recipient.reverse_charge_preference IS 'RC-1 — ordinal ReverseChargePreference: 0=NEVER, 1=ALWAYS, 2=ASK (default).';

-- MD3-F3 (PO 30.08.2026) — customer numbers are a fachlich identifier: two recipients differing
-- only in case ("KD-001" vs "kd-001") used to both be creatable, a confusion risk with no upside.
-- Functional index on UPPER(customer_number) instead of the plain column enforces case-insensitive
-- uniqueness at the DB, the last line of defense behind CustomerManagementService's own
-- case-insensitive pre-check (MasterDataNumberService.assertCustomerNumberAvailable). Name kept
-- unchanged (`uq_recipient_customer_number`) — MasterDataNumberService.UQ_CUSTOMER_NUMBER matches
-- constraint-violation exceptions by this exact name (task13-N3-10..13), and Postgres reports a
-- functional unique index's name unchanged regardless of the expression it indexes.
CREATE UNIQUE INDEX uq_recipient_customer_number ON recipient (UPPER(customer_number)) WHERE customer_number <> '';
CREATE INDEX        idx_recipient_name                ON recipient (name);
CREATE INDEX        idx_recipient_created_at          ON recipient (created_at);
CREATE INDEX        idx_recipient_customer_number     ON recipient (customer_number);
CREATE INDEX        idx_recipient_vat_id              ON recipient (vat_id);
CREATE INDEX        idx_recipient_sort_contact_person ON recipient (contact_person);
CREATE INDEX        idx_recipient_name_trgm            ON recipient USING gin (LOWER(name)            gin_trgm_ops);
CREATE INDEX        idx_recipient_customer_number_trgm ON recipient USING gin (LOWER(customer_number) gin_trgm_ops);

-- ARC-23 — recipient-scoped B2B field values ("Anzeigeort Kunde"): a B2bProfileField placed
-- RECIPIENT (placement=1, see chk_b2b_profile_field_placement above) is maintained on the
-- customer record instead of the invoice header. The value flows into that customer's invoices
-- as a customer-specific default (resolution order: invoice value > recipient value > profile
-- default value, see B2bProfileRuleSet). Own table rather than a fourth b2b_field_value owner
-- type: the same bt_code can be RECIPIENT-placed in one profile and INVOICE-placed in another,
-- so a row here references the concrete profile field by its id, not by bt_code. Both FKs ON
-- DELETE CASCADE (same reasoning as article_recipient_ref below): a customer-value row is
-- meaningless without the recipient or the profile field it references.
CREATE TABLE recipient_b2b_field_value (
    id                   BIGSERIAL PRIMARY KEY,
    recipient_id         BIGINT NOT NULL REFERENCES recipient (id) ON DELETE CASCADE,
    b2b_profile_field_id BIGINT NOT NULL REFERENCES b2b_profile_field (id) ON DELETE CASCADE,
    value                TEXT,
    version              BIGINT DEFAULT 0,
    created_at           TIMESTAMPTZ,
    updated_at           TIMESTAMPTZ,
    created_by           VARCHAR(255),
    updated_by           VARCHAR(255),
    CONSTRAINT uq_recipient_b2b_field_value_recipient_field UNIQUE (recipient_id, b2b_profile_field_id)
);

COMMENT ON TABLE recipient_b2b_field_value IS 'ARC-23 — per-customer value of one RECIPIENT-placed B2bProfileField, flowing into that customer''s invoices as a customer-specific default (invoice value > recipient value > profile default value).';

CREATE TABLE address (
    id                BIGSERIAL    PRIMARY KEY,
    recipient_id      BIGINT       REFERENCES recipient(id),
    sender_profile_id BIGINT       REFERENCES sender_profile(id),
    address_type      INTEGER      NOT NULL DEFAULT 0,
    street            VARCHAR(255) NOT NULL,
    postal_code       VARCHAR(20)  NOT NULL,
    city              VARCHAR(255) NOT NULL,
    country           INTEGER      NOT NULL DEFAULT 276,
    is_default        BOOLEAN      NOT NULL DEFAULT FALSE,
    version           BIGINT       DEFAULT 0,
    created_at        TIMESTAMPTZ,
    updated_at        TIMESTAMPTZ,
    created_by        VARCHAR(255),
    updated_by        VARCHAR(255),
    CONSTRAINT chk_address_recipient_id_positive CHECK (recipient_id IS NULL OR recipient_id > 0),
    CONSTRAINT chk_address_sender_profile_id_positive CHECK (sender_profile_id IS NULL OR sender_profile_id > 0),
    CONSTRAINT chk_address_country_positive CHECK (country > 0),
    CONSTRAINT chk_address_type CHECK (address_type >= 0 AND address_type <= 1), -- 0=BILLING, 1=DELIVERY
    CONSTRAINT chk_address_owner
        CHECK ((recipient_id IS NOT NULL)::int + (sender_profile_id IS NOT NULL)::int = 1)
);

CREATE UNIQUE INDEX uq_address_single_default_by_type ON address (recipient_id, address_type) WHERE is_default = TRUE;
CREATE INDEX        idx_address_recipient         ON address (recipient_id);
CREATE INDEX        idx_address_sender_profile    ON address (sender_profile_id);
CREATE INDEX        idx_address_country           ON address (country);
CREATE INDEX        idx_address_city              ON address (city);
CREATE INDEX        idx_address_postal_code       ON address (postal_code);
CREATE INDEX        idx_address_country_city      ON address (country, city);
CREATE INDEX        idx_address_is_default        ON address (is_default) WHERE is_default = TRUE;
CREATE INDEX        idx_address_sort_billing_city ON address (city) WHERE address_type = 0;

-- article: uses default_tax_rate_ref_id (FK into tax_rate above) — no legacy numeric column.
CREATE TABLE article (
    id                      BIGSERIAL     PRIMARY KEY,
    name                    VARCHAR(255)  NOT NULL,
    description             TEXT,
    category                INTEGER       NOT NULL DEFAULT 0,
    default_unit            INTEGER       NOT NULL DEFAULT 0,
    default_quantity        NUMERIC(14,4) NOT NULL DEFAULT 1,
    default_unit_price_net  NUMERIC(14,2) NOT NULL DEFAULT 0.00,
    default_tax_rate_ref_id BIGINT        REFERENCES tax_rate(id),
    is_default              BOOLEAN       NOT NULL DEFAULT FALSE,
    version                 BIGINT        DEFAULT 0,
    article_number          VARCHAR(60),
    article_number_prefix   VARCHAR(30),
    article_number_sequence BIGINT,
    short_hash              VARCHAR(10)   UNIQUE,
    CONSTRAINT chk_article_tax_rate_ref_id_positive CHECK (default_tax_rate_ref_id IS NULL OR default_tax_rate_ref_id > 0),
    -- RK-1 (P-RK-1): 2=TRAVEL_EXPENSE/3=EXPENSE/4=PER_DIEM retired (greenfield); TRIP's code (5)
    -- deliberately continues after the gap rather than reusing 2, so a stale row never misreads.
    CONSTRAINT chk_article_category      CHECK (category IN (0, 1, 5, 6)), -- 0=SERVICE,1=PRODUCT,5=TRIP,6=UNIT_PRICE
    CONSTRAINT chk_article_default_unit   CHECK (default_unit >= 0 AND default_unit <= 17),
    CONSTRAINT chk_article_default_quantity_positive       CHECK (default_quantity > 0),
    CONSTRAINT chk_article_default_unit_price_non_negative CHECK (default_unit_price_net >= 0)
);

COMMENT ON COLUMN article.article_number IS 'task13-N3 — Artikelnummer aus dem ARTICLE-Kreis; NULL = unnummerierter Bestand.';
COMMENT ON COLUMN article.short_hash IS 'task13-N3 — Kurzname, nie änderbar, nachgezogen beim Speichern.';

CREATE UNIQUE INDEX uq_article_default        ON article (is_default) WHERE is_default;
CREATE UNIQUE INDEX uq_article_article_number ON article (article_number) WHERE article_number IS NOT NULL;
CREATE INDEX        idx_article_default_tax_rate  ON article (default_tax_rate_ref_id);
CREATE INDEX        idx_article_is_default        ON article (is_default);
CREATE INDEX        idx_article_sort_name         ON article (name);
CREATE INDEX        idx_article_sort_category     ON article (category);
CREATE INDEX        idx_article_sort_default_unit ON article (default_unit);

-- task11/V3 — per-country MWST variants of an article. Curated country set (FR/BE/NL/LU) enforced
-- both in the domain (Country.SUPPORTED_SELLER_VAT_REGISTRATION_COUNTRIES) and here.
CREATE TABLE article_tax_variant (
    id          BIGSERIAL PRIMARY KEY,
    article_id  BIGINT NOT NULL REFERENCES article(id),
    country     INTEGER NOT NULL,
    tax_rate_id BIGINT NOT NULL REFERENCES tax_rate(id),
    version     BIGINT DEFAULT 0,
    CONSTRAINT uq_article_tax_variant_article_country UNIQUE (article_id, country),
    CONSTRAINT chk_article_tax_variant_country CHECK (country IN (250, 56, 528, 442))
);

COMMENT ON TABLE article_tax_variant IS 'task11/V3 — 0..n per-country MWST variants of an article, one at most per country. Generic across the curated country set (FR/BE/NL/LU); v1 only actually maintains Luxembourg variants (fachlich, not code-restricted).';
COMMENT ON COLUMN article_tax_variant.country IS 'ISO 3166-1 numeric, same convention as tax_rate.country / seller_vat_registration.country. Curated to FR(250)/BE(56)/NL(528)/LU(442).';
COMMENT ON COLUMN article_tax_variant.tax_rate_id IS 'Must reference a tax_rate row whose own country matches this variant''s country — enforced in ArticleTaxVariant.validate(), not at the DB.';

-- NEKAR-T11 — an article can carry a different buyer-side identifier per recipient: the buyer's
-- own article number (buyer_article_number -> BT-156) and, optionally, the buyer's own article
-- designation (buyer_article_name -> BT-153 prefill for a new position). Keyed by exactly the
-- dimension neither `article` (seller-side, shared across every recipient) nor a B2B profile
-- default (shared across every recipient of that profile) carries: (article, recipient).
-- ON DELETE CASCADE on recipient_id: a mapping row is meaningless without the recipient it maps
-- to. article_id deliberately has no such DB-level cascade, mirroring article_tax_variant: an
-- article delete goes through JPA (Article.recipientRefs, cascade = ALL, orphanRemoval = true).
CREATE TABLE article_recipient_ref (
    id                   BIGSERIAL PRIMARY KEY,
    article_id           BIGINT NOT NULL REFERENCES article(id),
    recipient_id         BIGINT NOT NULL REFERENCES recipient(id) ON DELETE CASCADE,
    buyer_article_number VARCHAR(50),
    buyer_article_name   VARCHAR(255),
    version              BIGINT DEFAULT 0,
    CONSTRAINT uq_article_recipient_ref_article_recipient UNIQUE (article_id, recipient_id),
    CONSTRAINT chk_article_recipient_ref_not_both_blank CHECK (
        (buyer_article_number IS NOT NULL AND LENGTH(TRIM(buyer_article_number)) > 0) OR
        (buyer_article_name IS NOT NULL AND LENGTH(TRIM(buyer_article_name)) > 0)
    )
);

COMMENT ON TABLE article_recipient_ref IS 'NEKAR-T11 — 0..n per-recipient identifier mappings of an article, one at most per recipient. buyer_article_number feeds BT-156, buyer_article_name prefills BT-153/description for a new position.';
COMMENT ON COLUMN article_recipient_ref.buyer_article_number IS 'The buyer''s own article number for this (article, recipient) pair -> BT-156, prefilled into the item dialog''s buyer-item-number field, never overwriting a value already typed.';
COMMENT ON COLUMN article_recipient_ref.buyer_article_name IS 'The buyer''s own article designation for this (article, recipient) pair -> BT-153, used as the description of a *new* invoice position only.';

-- task11/V1 — additional, non-domestic seller VAT registrations.
CREATE TABLE seller_vat_registration (
    id                BIGSERIAL    PRIMARY KEY,
    sender_profile_id BIGINT       NOT NULL REFERENCES sender_profile(id),
    country           INTEGER      NOT NULL,
    vat_id            VARCHAR(64)  NOT NULL,
    version           BIGINT       DEFAULT 0,
    CONSTRAINT uq_seller_vat_registration_profile_country UNIQUE (sender_profile_id, country),
    CONSTRAINT chk_seller_vat_registration_vat_id_not_blank CHECK (LENGTH(TRIM(vat_id)) > 0),
    CONSTRAINT chk_seller_vat_registration_country CHECK (country IN (40, 56, 100, 191, 196, 203, 208, 233, 246, 250, 300, 348, 372, 380, 428, 440, 442, 470, 528, 616, 620, 642, 703, 705, 724, 752))
);

COMMENT ON TABLE seller_vat_registration IS 'task11/V1 — 0..n additional, non-German VAT registrations of a sender, one at most per country. Curated country set (FR/BE/NL/LU) enforced both in the domain (Country.SUPPORTED_SELLER_VAT_REGISTRATION_COUNTRIES) and via chk_seller_vat_registration_country.';
COMMENT ON COLUMN seller_vat_registration.country IS 'ISO 3166-1 numeric, same convention as tax_rate.country. Curated to FR(250)/BE(56)/NL(528)/LU(442).';
COMMENT ON COLUMN seller_vat_registration.vat_id IS 'Local VAT id issued by country, prefix must match (e.g. LU12345678 for country=LU) — enforced in SellerVatRegistration.validate(), not at the DB.';

-- =============================================================================
-- 4. Invoice aggregate
-- =============================================================================

CREATE TABLE invoice (
    id                              BIGSERIAL     PRIMARY KEY,
    invoice_number                  VARCHAR(255)  NOT NULL DEFAULT '',
    status                          INTEGER       NOT NULL DEFAULT 0,
    currency                        INTEGER       NOT NULL DEFAULT 0,
    sender_profile_id               BIGINT        REFERENCES sender_profile(id),
    recipient_id                    BIGINT        REFERENCES recipient(id),
    billing_address_id              BIGINT        REFERENCES address(id),
    delivery_address_id             BIGINT        REFERENCES address(id),
    selected_seller_id              BIGINT        REFERENCES sender_profile(id),
    payment_term_id                 BIGINT        REFERENCES payment_term(id),
    invoice_date                    DATE          NOT NULL,
    service_period_from             DATE,
    service_period_to               DATE,
    due_date                        DATE,
    payment_terms_days              INT           NOT NULL DEFAULT 14,
    free_text                       TEXT,
    invoice_note                    TEXT,
    invoice_note_origin             VARCHAR(20)   NOT NULL DEFAULT 'SELLER_DEFAULT',
    invoice_note_source_sender_id   BIGINT,
    invoice_note_source_language    VARCHAR(5)    NOT NULL DEFAULT 'de',
    invoice_note_source_default     TEXT,
    consumer_note                   TEXT,
    consumer_note_origin            VARCHAR(20)   NOT NULL DEFAULT 'SELLER_DEFAULT',
    consumer_note_source_sender_id  BIGINT,
    consumer_note_source_language   VARCHAR(5)    NOT NULL DEFAULT 'de',
    consumer_note_source_context    VARCHAR(20)   NOT NULL DEFAULT '',
    consumer_note_source_default    TEXT,
    b2b_free_text_imported_codes    TEXT,
    type_code                       INTEGER       NOT NULL DEFAULT 0,
    reverse_charge                  BOOLEAN       NOT NULL DEFAULT FALSE,
    original_invoice_number         VARCHAR(255),
    buyer_reference                 VARCHAR(100),
    order_reference                 VARCHAR(100),
    order_reference_date            DATE,
    net_amount                      NUMERIC(14,2) NOT NULL DEFAULT 0.00,
    vat_amount                      NUMERIC(14,2) NOT NULL DEFAULT 0.00,
    gross_amount                    NUMERIC(14,2) NOT NULL DEFAULT 0.00,
    -- Truthful invoice validation status: NOT_VALIDATED (4) is the born-with default, "never
    -- near an e-invoice run"; PENDING (0) means only "a job is genuinely in flight" (set by
    -- DocumentJobPublisher.publish, cleared to UNAVAILABLE by GenerationJobService.markFailed).
    validation_status               INTEGER       NOT NULL DEFAULT 4,
    -- Whether totals were last set by Invoice.recalculateTotals() itself. TRUE by default so
    -- rows written around the domain layer (the demo-data bulk SQL insert, which bakes
    -- net/vat/gross in directly) are picked up once by the background recalculation job;
    -- recalculateTotals() clears it, so normal app writes — which always recalc in the same
    -- transaction as any item/header change — settle back to FALSE immediately and are never
    -- reprocessed for nothing.
    needs_recalculation             BOOLEAN       NOT NULL DEFAULT TRUE,
    version                         BIGINT        DEFAULT 0,
    created_at                      TIMESTAMPTZ,
    updated_at                      TIMESTAMPTZ,
    created_by                      VARCHAR(255),
    source_quote_id                 UUID,          -- accepted offer this invoice was created from; one invoice per offer
    updated_by                      VARCHAR(255),
    offer_reference                 VARCHAR(100),  -- BT-17 (Tender or lot reference)
    offer_reference_date            DATE,
    document_language               VARCHAR(5)    NOT NULL DEFAULT 'de',
    project_number                  VARCHAR(100),
    cost_center                     VARCHAR(100),
    seller_vat_registration_country INTEGER,
    invoice_format                  INTEGER       NOT NULL DEFAULT 0,
    delivery_date                   DATE,          -- BT-72
    original_invoice_date           DATE,          -- BT-26
    invoice_number_prefix           VARCHAR(30),
    invoice_number_sequence         BIGINT,
    document_engine                 INTEGER       NOT NULL DEFAULT 0,
    b2b_profile_id                  BIGINT        REFERENCES b2b_profile (id),
    -- BT-13 (Bestellreferenz des Käufers) hat eine eigene Spalte statt Dublette mit
    -- buyer_reference: ein öffentlicher Käufer landete seine Leitweg-ID sonst versehentlich auch
    -- in BT-13. Diese Spalte trägt BT-13 allein; BT-10 bleibt auf buyer_reference, unverändert
    -- (BR-DE-15 verlangt es auf jeder deutschen Rechnung, Invoice.ensureBuyerReferenceForFinalization()
    -- setzt es durch). Nicht zu verwechseln mit order_reference (die *eigene* Auftrags-/
    -- Bestellnummer des Verkäufers).
    purchase_order_reference        VARCHAR(100),
    -- Wie die P2-Party-Snapshots muss eine finalisierte Rechnung mit einer Zahlungsbedingung auch
    -- Jahre später identisch dargestellt/exportiert werden, selbst wenn der payment_term-
    -- Stammdatensatz später geändert wird. Invoice.freezePaymentTermSnapshot() schreibt diese
    -- Spalten einmalig beim Übergang zu SENT (InvoiceLifecycleService.transition) — nie
    -- danach neu abgeleitet. Durchgehend nullable: ein Entwurf hat noch keine, und eine Rechnung
    -- ohne gewählte Zahlungsbedingung bekommt nie eine.
    -- IN-4 (PO decision 2 of 06.09.2026) — the text as printed: resolved in the invoice's own
    -- document_language at the moment of freezing, not a raw German value. Replaces the former
    -- payment_term_snapshot_english_name column — there is exactly one printed name per invoice.
    payment_term_snapshot_name             VARCHAR(150),
    payment_term_snapshot_due_in_days        INT,
    payment_term_snapshot_discount_percent  NUMERIC(5, 2),
    payment_term_snapshot_discount_days      INT,
    -- Event-fed mail projection; NULL means no delivery observation.
    mail_sent                       BOOLEAN,
    mail_sent_at                    TIMESTAMPTZ,
    CONSTRAINT chk_invoice_sender_profile_id_positive CHECK (sender_profile_id IS NULL OR sender_profile_id > 0),
    CONSTRAINT chk_invoice_recipient_id_positive CHECK (recipient_id IS NULL OR recipient_id > 0),
    CONSTRAINT chk_invoice_billing_address_id_positive CHECK (billing_address_id IS NULL OR billing_address_id > 0),
    CONSTRAINT chk_invoice_delivery_address_id_positive CHECK (delivery_address_id IS NULL OR delivery_address_id > 0),
    CONSTRAINT chk_invoice_selected_seller_id_positive CHECK (selected_seller_id IS NULL OR selected_seller_id > 0),
    CONSTRAINT chk_invoice_payment_term_id_positive CHECK (payment_term_id IS NULL OR payment_term_id > 0),
    CONSTRAINT chk_invoice_status                     CHECK (status >= 0 AND status <= 4), -- 0=DRAFT,1=IN_REVIEW,2=SENT,3=VOIDED,4=CANCELLED
    CONSTRAINT chk_invoice_validation_status          CHECK (validation_status >= 0 AND validation_status <= 4), -- 0=PENDING,1=VALID,2=INVALID,3=UNAVAILABLE,4=NOT_VALIDATED
    CONSTRAINT chk_invoice_currency                   CHECK (currency >= 0 AND currency <= 5), -- 0=EUR,1=CHF,2=GBP,3=USD,4=NOK,5=SEK
    -- PO-Entscheidung 1 (2026-09-02, Storno-384-Paket, Baseline direkt geaendert) — 0..2 statt
    -- 0..1: type_code 2 = CORRECTED_INVOICE (BT-3 = 384), der Storno-Typ seither.
    CONSTRAINT chk_invoice_type_code                  CHECK (type_code >= 0 AND type_code <= 2), -- 0=COMMERCIAL_INVOICE,1=CREDIT_NOTE,2=CORRECTED_INVOICE
    CONSTRAINT chk_invoice_payment_terms_days_non_neg CHECK (payment_terms_days >= 0),
    CONSTRAINT chk_invoice_note_origin CHECK (invoice_note_origin IN ('SELLER_DEFAULT', 'MANUAL')),
    CONSTRAINT chk_invoice_consumer_note_origin CHECK (consumer_note_origin IN ('SELLER_DEFAULT', 'MANUAL')),
    -- Einzelne Positionen dürfen negativ sein (Rabatt/Abzug), die Rechnung als Ganzes nicht —
    -- ausser bei einer Stornorechnung (type_code 2 = CORRECTED_INVOICE, PO-Entscheidung 2,
    -- 2026-09-02, Vorzeichenlogik): sie ist das exakte Negativ des Originals (BR-CO-25 verlangt
    -- das, sobald keine Zahlungsbedingungen mehr vorhanden sind — siehe InvoiceStornoService).
    CONSTRAINT chk_invoice_net_amount_non_neg         CHECK (net_amount >= 0 OR type_code = 2),
    CONSTRAINT chk_invoice_vat_amount_non_neg         CHECK (vat_amount >= 0 OR type_code = 2),
    CONSTRAINT chk_invoice_gross_amount_non_neg       CHECK (gross_amount >= 0 OR type_code = 2),
    CONSTRAINT chk_invoice_service_period_order
        CHECK (service_period_from IS NULL OR service_period_to IS NULL OR service_period_from <= service_period_to),
    CONSTRAINT fk_invoice_document_language FOREIGN KEY (document_language) REFERENCES language (code),
    CONSTRAINT chk_invoice_seller_vat_registration_country
        CHECK (seller_vat_registration_country IS NULL OR seller_vat_registration_country IN (40, 56, 100, 191, 196, 203, 208, 233, 246, 250, 300, 348, 372, 380, 428, 440, 442, 470, 528, 616, 620, 642, 703, 705, 724, 752)),
    CONSTRAINT chk_invoice_format_values CHECK (invoice_format IN (0, 1)),
    -- task11/V5 Vorgehen §1 — only meaningful while a seller VAT registration is chosen; enforced
    -- again in InvoiceLifecycleService.updateHeader (forces back to 0/GERMAN on a switch to NULL),
    -- this CHECK is the DB-level defense-in-depth twin of that rule.
    CONSTRAINT chk_invoice_format_requires_seller_vat_registration
        CHECK (invoice_format = 0 OR seller_vat_registration_country IS NOT NULL)
);

COMMENT ON COLUMN invoice.source_quote_id IS 'Identifier of the accepted offer this invoice was drafted from; the unique index makes a repeated acceptance event idempotent. NULL for every invoice not created from an offer.';
COMMENT ON COLUMN invoice.mail_sent IS 'WP-14/H-6 — event-fed read model, not a second truth: set by MailSendResultProjection from the mail component''s mail.send.result event. NULL = never observed (also the state of an instance without the mail component). The component''s own journal stays the single source of truth.';
COMMENT ON COLUMN invoice.mail_sent_at IS 'WP-14/H-6 — UTC timestamp the mail component recorded for the terminal SENT transition; see invoice.mail_sent.';

COMMENT ON COLUMN invoice.document_language IS 'R3 — bei Erstellung ermittelt und eingefroren; nie automatisch neu aus Stammdaten hergeleitet. IN-1: das erlaubte Set lebt jetzt in language, nicht mehr in einem CHECK (fk_invoice_document_language).';
COMMENT ON COLUMN invoice.invoice_note IS 'INV-B: editable invoice-owned language/seller note snapshot; export never resolves mutable seller master data.';
COMMENT ON COLUMN invoice.consumer_note IS 'Editable invoice-owned copy of the private-customer notice; set only for craft-trade invoices to private customers. Export never resolves mutable seller master data.';
COMMENT ON COLUMN invoice.consumer_note_origin IS 'SELLER_DEFAULT follows sender, language and recipient-type changes while untouched; MANUAL includes deliberate clearing.';
COMMENT ON COLUMN invoice.consumer_note_source_context IS 'Recipient type the default was resolved for; a change recomputes an untouched default.';
COMMENT ON COLUMN invoice.invoice_note_origin IS 'INV-B: SELLER_DEFAULT follows sender/language changes; MANUAL includes deliberate clearing.';
COMMENT ON COLUMN invoice.b2b_free_text_imported_codes IS 'INV-J: official BT codes copied once into editable free_text.';
COMMENT ON COLUMN invoice.project_number IS 'Optional: interne Projekt-/Referenznummer der Rechnung.';
COMMENT ON COLUMN invoice.cost_center IS 'Optional: Kostenstelle auf Rechnungsebene.';
COMMENT ON COLUMN invoice.seller_vat_registration_country IS 'task11/V4 — NULL = German default (SenderProfile.vatId, unchanged R5/TaxRuleResolver path). Non-null selects one of the seller''s additional VAT registrations (task11/V1, seller_vat_registration); curated to FR(250)/BE(56)/NL(528)/LU(442), same as seller_vat_registration.country / article_tax_variant.country.';
COMMENT ON COLUMN invoice.invoice_format IS 'task11/V5 — ordinal InvoiceFormat: 0=GERMAN (default), 1=INTERNATIONAL. Only 1 while seller_vat_registration_country is non-null (chk_invoice_format_requires_seller_vat_registration).';
COMMENT ON COLUMN invoice.invoice_date IS 'BT-2 — date invariants (PO 2026-09-02): every invoice carries an invoice date from the moment it exists, set to today by every domain creation path and never NULL; backdating afterward stays legal. No column default: the database never invents a date, so every writer, a bulk loader included, supplies the business date itself.';
COMMENT ON COLUMN invoice.delivery_date IS 'BT-72 (optional): tatsächliches Liefer-/Leistungsdatum. NULL = Rückfall auf BT-74 (service_period_to), dann auf invoice_date.';
COMMENT ON COLUMN invoice.original_invoice_date IS 'BT-26 — Ausstellungsdatum des Vorbelegs (BG-3), gesetzt beim Storno aus der Originalrechnung. NULL = nicht erfasst, das Element entfällt im Export.';
COMMENT ON COLUMN invoice.invoice_number_prefix IS 'task13-N2 — Präfix-Anteil von invoice_number, getrennt gespeichert. NULL an Bestandsbelegen und unnummerierten Entwürfen.';
COMMENT ON COLUMN invoice.invoice_number_sequence IS 'task13-N2 — Zähler-Anteil von invoice_number als Zahl. NULL an Bestandsbelegen und unnummerierten Entwürfen.';
COMMENT ON COLUMN invoice.document_engine IS 'task12-b2b/T8 — ordinal DocumentEngine: 0=MUSTANG (Default), 1=FACTURX. Steuert u. a. die ARDENNES_X.SIRET_REQUIRED-Regel (ArdennesXRules).';
COMMENT ON COLUMN invoice.b2b_profile_id IS 'AP-9 — this invoice''s own, frozen B2B profile choice. Derived from recipient.b2b_profile_id on assignment/change of recipient, then editable and snapshot-stable while DRAFT (never re-derived once SENT). NULL = no B2B profile (Community behavior, wire unchanged).';
COMMENT ON COLUMN invoice.purchase_order_reference IS 'BT-13 — Bestellreferenz des Käufers (ram:BuyerOrderReferencedDocument/ram:IssuerAssignedID), optional nach EN 16931. Weder buyer_reference (BT-10, Käuferreferenz/Leitweg-ID) noch order_reference (Bestell-/Auftragsnummer des Verkäufers).';
COMMENT ON COLUMN invoice.payment_term_snapshot_name IS 'IN-4 — the payment term''s name as it was printed: resolved in document_language at SENT (PaymentTerm.nameFor), never re-derived. Null for a draft or an invoice with no payment term.';

CREATE UNIQUE INDEX uq_invoice_number_nonempty ON invoice (invoice_number) WHERE invoice_number <> '';
CREATE UNIQUE INDEX uq_invoice_source_quote ON invoice (source_quote_id) WHERE source_quote_id IS NOT NULL;
-- IMP-6 — genau eine Stornorechnung je Originalrechnung. Der Anwendungscode kann das
-- prinzipiell nicht garantieren (check-then-act zwischen zwei gleichzeitigen Storno-Aufrufen);
-- nur ein Index kann es. ST-2 — keine Statusklausel mehr: eine Stornorechnung entsteht (ST-3)
-- direkt als CANCELLED und ist sofort terminal, sie kann nie verworfen werden, also gibt es
-- keinen "verworfenen zweiten Versuch" mehr auszunehmen.
--
-- PO-Entscheidung 1 (2026-09-02, Storno-384-Paket, Baseline direkt geaendert) — type_code 2
-- statt 1: die Stornorechnung traegt seither ausschliesslich CORRECTED_INVOICE (BT-3 = 384),
-- nicht mehr CREDIT_NOTE (BT-3 = 381; bleibt fuer eine echte, von diesem Index nicht erfasste
-- Gutschrift reserviert, die aktuell kein Codepfad erzeugt).
CREATE UNIQUE INDEX uq_invoice_single_credit_note
    ON invoice (original_invoice_number)
    WHERE type_code = 2 AND original_invoice_number IS NOT NULL;

COMMENT ON INDEX uq_invoice_single_credit_note IS
    'IMP-6 — je Originalrechnung hoechstens eine Stornorechnung (type_code 2 = CORRECTED_INVOICE, seit PO-Entscheidung 2026-09-02; vormals type_code 1 = CREDIT_NOTE).';

CREATE INDEX idx_invoice_date_id_desc                ON invoice (invoice_date DESC, id DESC);
CREATE INDEX idx_invoice_status_date_id_desc         ON invoice (status, invoice_date DESC, id DESC);
CREATE INDEX idx_invoice_recipient_date_desc         ON invoice (recipient_id, invoice_date DESC);
CREATE INDEX idx_invoice_sender_date_desc            ON invoice (sender_profile_id, invoice_date DESC);
CREATE INDEX idx_invoice_validation_status_date_desc ON invoice (validation_status, invoice_date DESC);
CREATE INDEX idx_invoice_status                      ON invoice (status);
CREATE INDEX idx_invoice_invoice_date                ON invoice (invoice_date);
CREATE INDEX idx_invoice_service_period              ON invoice (service_period_from, service_period_to);
CREATE INDEX idx_invoice_invoice_number              ON invoice (invoice_number);
CREATE INDEX idx_invoice_original_invoice_number     ON invoice (original_invoice_number);
CREATE INDEX idx_invoice_payment_term_id             ON invoice (payment_term_id);
CREATE INDEX idx_invoice_selected_seller_id          ON invoice (selected_seller_id);
CREATE INDEX idx_invoice_sort_gross_amount           ON invoice (gross_amount);
CREATE INDEX idx_invoice_number_trgm                 ON invoice USING gin (LOWER(invoice_number) gin_trgm_ops);

-- RS-11 (M3, beratung-manipulationsschutz.md) — defense-in-depth UNDER the domain guard, not a
-- restatement of it: Invoice.isReadOnly()/InvoiceLifecycleService.updateHeader (and every other
-- header/item-mutating method there) already reject ANY edit to a SENT/CANCELLED invoice at the
-- application layer, so no normal code path can ever reach this trigger with a rejected write.
-- What this catches is a write that bypasses the domain layer entirely — a direct SQL UPDATE,
-- an admin script, a future regression — against the handful of columns a finalized document
-- must never show differently years later: its number (task13-N2), its issue date (BT-2, stamped
-- once at the -> SENT transition), its type code, its monetary totals (recalculateTotals() is
-- itself already gated on !isReadOnly() in GenerationJobService/InvoiceLifecycleService.save/...),
-- the frozen payment-term snapshot (freezePaymentTermSnapshot(), also written once at -> SENT)
-- and the two party-snapshot references (sender_profile_id/recipient_id) that mirror
-- invoice_party_snapshot's SELLER/BUYER rows (P1/GoBD).
--
-- Deliberately NOT every column updateHeader guards — freezing e.g. free_text, the addresses or
-- billing/delivery FKs, seller_vat_registration_country or invoice_format here as well would add
-- no real safety margin (nothing in this codebase ever writes them post-issue; updateHeader's own
-- blanket isReadOnly() throw already stops that) while risking exactly the "block a legal
-- operation" outcome RS-11's own DoD forbids, should a legitimate write turn up later. Two
-- concrete ones already exist and MUST stay writable:
--   - status: the one legal SENT -> CANCELLED edge (InvoiceStornoService.createCreditNote) —
--     changes nothing else on the original row (see that class's own KDoc: "keeps everything
--     else it had").
--   - validation_status: PanacheInvoiceRepository.updateValidationStatus's bulk UPDATE carries no
--     status guard at all and legitimately flips this on an issued invoice every time its
--     (single, RS-1-gated) e-invoice/PDF job runs.
--   - document_engine: GenerationJobCreationService.createJob writes this on the managed invoice even for
--     a SENT/CANCELLED invoice's first (RS-1) generation job — the engine choice for the document
--     that is actually about to be produced.
--   - needs_recalculation, updated_at/updated_by, version: internal bookkeeping/JPA audit columns
--     written on every save regardless of status; freezing them would break the one legal
--     operation (the status transition above) that always touches updated_at/updated_by/version.
-- The lint gate's capitalisation.functions rule (upper) does not distinguish a user-defined
-- function's own name from a built-in function call site (COUNT()/SUM()/...); enforcing it here
-- would mean SCREAMING_CASE identifiers, which contradicts this file's own snake_case/lowercase
-- identifier convention (capitalisation.identifiers). Block-disabled below for the same reason
-- the CREATE RULE exclusion this section replaced used to be needed (a line-level noqa comment
-- does not survive the multi-line dollar-quoted plpgsql body between CREATE and the matching
-- EXECUTE FUNCTION call, so this uses the disable/enable block form instead).
-- noqa: disable=CP03
CREATE OR REPLACE FUNCTION guard_invoice_freeze() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.status IN (2, 4) THEN -- SENT=2, CANCELLED=4 (chk_invoice_status)
        -- invoice_number/_prefix/_sequence get their real value in a SEPARATE, LATER flush
        -- than the one that first persists status=SENT/CANCELLED: InvoiceLifecycleService's
        -- SENT transition and InvoiceStornoService.createCreditNote both call
        -- numberAssigner.assignIfNeeded() *after* status is already dirty/flushed — its own
        -- native counter query forces an intermediate auto-flush (see both classes' own KDoc
        -- on this ordering; it is established, working behavior this trigger must not fight).
        -- So the row's FIRST UPDATE while OLD.status IN (2,4) legitimately still carries an
        -- unassigned number (invoice_number = '', the established NULLIF(...,'')-sentinel
        -- convention; prefix/sequence NULL) — that is not tampering, it is the number being
        -- assigned for the first time to an invoice that just became issued. Once a number has
        -- actually been assigned (OLD value no longer empty/NULL), it is frozen for real: found
        -- and fixed via a live-Postgres test run (2026-09-04) that the naive
        -- "any change while issued" version of this guard broke that established flush.
        IF (OLD.invoice_number <> '' AND NEW.invoice_number IS DISTINCT FROM OLD.invoice_number)
            OR (OLD.invoice_number_prefix IS NOT NULL AND NEW.invoice_number_prefix IS DISTINCT FROM OLD.invoice_number_prefix)
            OR (OLD.invoice_number_sequence IS NOT NULL AND NEW.invoice_number_sequence IS DISTINCT FROM OLD.invoice_number_sequence)
            OR NEW.invoice_date IS DISTINCT FROM OLD.invoice_date
            OR NEW.type_code IS DISTINCT FROM OLD.type_code
            OR NEW.net_amount IS DISTINCT FROM OLD.net_amount
            OR NEW.vat_amount IS DISTINCT FROM OLD.vat_amount
            OR NEW.gross_amount IS DISTINCT FROM OLD.gross_amount
            OR NEW.payment_term_snapshot_name IS DISTINCT FROM OLD.payment_term_snapshot_name
            OR NEW.payment_term_snapshot_due_in_days IS DISTINCT FROM OLD.payment_term_snapshot_due_in_days
            OR NEW.payment_term_snapshot_discount_percent IS DISTINCT FROM OLD.payment_term_snapshot_discount_percent
            OR NEW.payment_term_snapshot_discount_days IS DISTINCT FROM OLD.payment_term_snapshot_discount_days
            OR NEW.sender_profile_id IS DISTINCT FROM OLD.sender_profile_id
            OR NEW.recipient_id IS DISTINCT FROM OLD.recipient_id
        THEN
            RAISE EXCEPTION 'invoice % is issued (status %) and its frozen columns cannot be changed', OLD.id, OLD.status
                USING ERRCODE = '23001'; -- restrict_violation, the same class a FK RESTRICT rejection uses
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_invoice_freeze_issued
    BEFORE UPDATE ON invoice
    FOR EACH ROW
    EXECUTE FUNCTION guard_invoice_freeze();
-- noqa: enable=CP03

-- invoice_item: uses tax_rate_id (FK) only — no legacy numeric tax_rate column.
CREATE TABLE invoice_item (
    id                      BIGSERIAL     PRIMARY KEY,
    invoice_id              BIGINT        NOT NULL REFERENCES invoice(id) ON DELETE CASCADE,
    position_id             VARCHAR(255)  NOT NULL DEFAULT '',
    category                INTEGER       NOT NULL DEFAULT 0,
    description             TEXT          NOT NULL DEFAULT '',
    quantity                NUMERIC(14,4) NOT NULL DEFAULT 1,
    unit                    INTEGER       NOT NULL DEFAULT 0,
    unit_price_net          NUMERIC(14,2) NOT NULL DEFAULT 0.00,
    tax_rate_id             BIGINT        REFERENCES tax_rate(id),
    net_amount              NUMERIC(14,2) NOT NULL DEFAULT 0.00,
    vat_amount              NUMERIC(14,2) NOT NULL DEFAULT 0.00,
    gross_amount            NUMERIC(14,2) NOT NULL DEFAULT 0.00,
    sort_order              INT           NOT NULL DEFAULT 0,
    delivery_note_reference VARCHAR(100),
    version                 BIGINT        DEFAULT 0,
    -- R1/GoBD: unveränderlicher Steuer-Snapshot an der Position.
    tax_rule_code           VARCHAR(64),
    tax_category            VARCHAR(4),
    tax_rate_snapshot       NUMERIC(5,2),
    exemption_reason_code   VARCHAR(32),
    exemption_reason_text   TEXT,
    tax_legal_basis         TEXT,
    cost_center             VARCHAR(100),
    buyer_item_description  TEXT,         -- Recipient's item description
    article_id              BIGINT        REFERENCES article(id),
    service_period_from     DATE,          -- BT-134
    service_period_to       DATE,          -- BT-135
    item_description        TEXT,          -- BT-154
    -- RK-1 — trip fields (only meaningful for category=TRIP, InvoiceItem.validate()).
    trip_detailed_on_invoice BOOLEAN       NOT NULL DEFAULT FALSE,
    trip_fixed_net_price    NUMERIC(14,2),
    CONSTRAINT chk_invoice_item_invoice_id_positive CHECK (invoice_id > 0),
    CONSTRAINT chk_invoice_item_tax_rate_id_positive CHECK (tax_rate_id IS NULL OR tax_rate_id > 0),
    -- RK-1 (P-RK-1): 2=TRAVEL_EXPENSE/3=EXPENSE/4=PER_DIEM retired (greenfield); TRIP's code (5)
    -- deliberately continues after the gap rather than reusing 2, so a stale row never misreads.
    CONSTRAINT chk_invoice_item_category              CHECK (category IN (0, 1, 5, 6)), -- 0=SERVICE,1=PRODUCT,5=TRIP,6=UNIT_PRICE
    CONSTRAINT chk_invoice_item_unit                  CHECK (unit >= 0 AND unit <= 17),
    -- Die Menge ist nie negativ — das Vorzeichen sitzt im Preis (UnitConsistencyRules LT-UNIT-04).
    -- Preis- und Betragsspalten tragen bewusst KEINE Nichtnegativitäts-Constraints: eine
    -- Rabatt-/Abzugsposition ist fachlich erlaubt (InvoiceItem.validate()), nur die
    -- Rechnungssummen auf `invoice` müssen nicht-negativ bleiben.
    -- RK-2 (N15 finding) — no non-negativity CHECK on trip_fixed_net_price: like unit_price_net
    -- above, this column must accept a negative value for a storno/credit-note copy
    -- (InvoiceItem.negateForStorno negates a trip's fixed price the same way it negates
    -- unit_price_net) — a CHECK here rejected exactly that row (datagen storno document #206).
    -- The domain rule "a user-entered fixed price is >= 0" lives at creation time in
    -- TripCommandValidator (application layer, never reached by the storno copy path), not here.
    CONSTRAINT chk_invoice_item_quantity_non_negative CHECK (quantity >= 0)
);

COMMENT ON COLUMN invoice_item.trip_detailed_on_invoice IS 'RK-1/P-RK-5 — TRIP only: false (default) prints one summary line (net/tax/gross), true prints indented 1.1/1.2… sub-lines with the trip''s expense lines (net only). Ignored for SERVICE/PRODUCT.';
COMMENT ON COLUMN invoice_item.trip_fixed_net_price IS 'RK-1/P-RK-6 — TRIP only: a fixed workshop/trip price that replaces the sum of trip_expense/travel_expense_entry rows on the invoice; the delta to the calculated sum is Excel-only (RK-5), never printed. NULL = price the item from its parts (InvoiceItem.calculatedTripNet()).';

COMMENT ON COLUMN invoice_item.unit_price_net IS 'Einheitspreis netto. Darf negativ sein (Rabatt-/Abzugsposition); die Rechnungssummen bleiben es nicht.';
COMMENT ON COLUMN invoice_item.tax_rate_snapshot IS 'R1/GoBD: der Satz zum Zeitpunkt der Zuweisung, unabhängig von einer späteren Änderung an tax_rate.rate. NULL = noch nicht snapshotted (R4).';
COMMENT ON COLUMN invoice_item.cost_center IS 'Optional: Kostenstelle auf Positionsebene.';
COMMENT ON COLUMN invoice_item.article_id IS 'task11/V4 — which Article this position was created from, if any (free-text positions stay NULL). Durable, unlike the other article-derived fields (unitPriceNet etc., copied once at entry time): needed so InvoiceItem.applySellerCountryTaxVariant can re-derive the position''s tax whenever invoice.seller_vat_registration_country changes after the position already exists. The R1 tax snapshot columns (tax_rule_code etc.) remain the frozen source of truth once applied — this column only feeds the re-derivation, it never overrides an existing snapshot on its own.';
COMMENT ON COLUMN invoice_item.service_period_from IS 'BT-134 (optional): Beginn des Positions-Leistungszeitraums (BG-26). Muss im Kopfzeitraum BT-73/BT-74 liegen (PeriodConsistencyRules).';
COMMENT ON COLUMN invoice_item.service_period_to IS 'BT-135 (optional): Ende des Positions-Leistungszeitraums (BG-26). Muss im Kopfzeitraum BT-73/BT-74 liegen (PeriodConsistencyRules).';
COMMENT ON COLUMN invoice_item.item_description IS 'BT-154 (optional): ausführliche Leistungsbeschreibung. BT-153 (Bezeichnung) bleibt invoice_item.description und ist unverändert Pflicht.';

CREATE INDEX idx_invoice_item_invoice     ON invoice_item (invoice_id);
CREATE INDEX idx_invoiceitem_tax_rate     ON invoice_item (tax_rate_id);
CREATE INDEX idx_invoice_item_position_id ON invoice_item (position_id);

-- RK-1 — extends the RS-11 freeze guard (defense-in-depth UNDER InvoiceItem.assertSnapshotMutable
-- et al., never a replacement) to invoice_item's two new trip columns: unlike guard_invoice_freeze
-- above (which guards `invoice`'s OWN columns), invoice_item carried no DB-level freeze guard at
-- all before RK-1 (freezing lived only in the domain/application layer); this is deliberately
-- narrow — only the trip columns RK-1 introduces — rather than a general invoice_item freeze,
-- which is a larger change this package does not make.
-- noqa: disable=CP03
CREATE OR REPLACE FUNCTION guard_invoice_item_trip_freeze() RETURNS TRIGGER AS $$
DECLARE
    v_status INTEGER;
BEGIN
    SELECT status INTO v_status FROM invoice WHERE id = NEW.invoice_id;
    IF v_status IN (2, 4) THEN -- SENT=2, CANCELLED=4 (chk_invoice_status)
        IF NEW.trip_detailed_on_invoice IS DISTINCT FROM OLD.trip_detailed_on_invoice
            OR NEW.trip_fixed_net_price IS DISTINCT FROM OLD.trip_fixed_net_price
        THEN
            RAISE EXCEPTION 'invoice_item % belongs to an issued invoice (status %) and its trip fields cannot be changed', OLD.id, v_status
                USING ERRCODE = '23001';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_invoice_item_trip_freeze
    BEFORE UPDATE ON invoice_item
    FOR EACH ROW
    EXECUTE FUNCTION guard_invoice_item_trip_freeze();
-- noqa: enable=CP03

CREATE TABLE receipt (
    id                   BIGSERIAL    PRIMARY KEY,
    file_name            VARCHAR(255) NOT NULL DEFAULT '',
    description          TEXT         NOT NULL,
    object_key           VARCHAR(512) NOT NULL,
    size_bytes           BIGINT,
    sha256_checksum      VARCHAR(64),
    uploaded_at          TIMESTAMPTZ,
    document_date        DATE         NOT NULL,
    version              BIGINT       DEFAULT 0,
    -- receipt-size-normalisation: how far the stored bytes were reduced from the upload (NONE
    -- when they already met the target) and the uploaded size itself — the pool keeps only the
    -- derivative, so these two columns are the only record of what the upload originally was.
    normalisation_stage  VARCHAR(32)  NOT NULL DEFAULT 'NONE',
    original_size_bytes  BIGINT,
    CONSTRAINT uq_receipt_object_key UNIQUE (object_key),
    -- Dedup by checksum must hold under concurrent uploads; the check-then-act in
    -- ReceiptService alone races. Postgres UNIQUE permits multiple NULLs, so rows without a
    -- checksum remain valid.
    CONSTRAINT uq_receipt_sha256_checksum UNIQUE (sha256_checksum)
);

COMMENT ON COLUMN receipt.document_date IS 'Belegdatum: das Datum, das der Beleg selbst trägt (nicht der Upload-Zeitpunkt). Pflichtfeld — jeder Beleg braucht Beschreibung und Belegdatum (PO 2026-09-04).';

-- Positionsbelege (n:m).
CREATE TABLE invoice_item_receipt (
    invoice_item_id BIGINT NOT NULL REFERENCES invoice_item(id),
    receipt_id      BIGINT NOT NULL REFERENCES receipt(id),
    PRIMARY KEY (invoice_item_id, receipt_id),
    CONSTRAINT chk_iir_invoice_item_id_positive CHECK (invoice_item_id > 0),
    CONSTRAINT chk_iir_receipt_id_positive CHECK (receipt_id > 0)
);

-- Rechnungsbelege (n:m), parallel zu invoice_item_receipt. Ein Beleg darf beides sein.
CREATE TABLE invoice_receipt (
    invoice_id BIGINT NOT NULL REFERENCES invoice(id),
    receipt_id BIGINT NOT NULL REFERENCES receipt(id),
    PRIMARY KEY (invoice_id, receipt_id),
    CONSTRAINT chk_ir_invoice_id_positive CHECK (invoice_id > 0),
    CONSTRAINT chk_ir_receipt_id_positive CHECK (receipt_id > 0)
);

COMMENT ON TABLE invoice_receipt IS 'Rechnungsbelege (A4): Beleg, der der Rechnung als Ganzes zugeordnet ist, parallel zu invoice_item_receipt. Ein Beleg darf zusätzlich an Positionen hängen.';

-- Für die Usage-/Löschschutz-Abfrage (findReceiptUsage beginnt beim Beleg, nicht bei der Rechnung).
CREATE INDEX idx_invoice_receipt_receipt_id ON invoice_receipt (receipt_id);

-- fix-receipt-editable-after-invoice-issued — defense in depth behind ReceiptService's own
-- guard: every column freezes once any linked invoice is issued (status 2/4), mirroring
-- trip_expense_before_write below. Unassigned/draft-linked receipts stay fully editable.
-- noqa: disable=CP03
CREATE OR REPLACE FUNCTION receipt_freeze_when_issued() RETURNS TRIGGER AS $$
DECLARE
    v_issued BOOLEAN;
BEGIN
    IF NEW.file_name IS DISTINCT FROM OLD.file_name
        OR NEW.object_key IS DISTINCT FROM OLD.object_key
        OR NEW.sha256_checksum IS DISTINCT FROM OLD.sha256_checksum
        OR NEW.size_bytes IS DISTINCT FROM OLD.size_bytes
        OR NEW.uploaded_at IS DISTINCT FROM OLD.uploaded_at
        OR NEW.description IS DISTINCT FROM OLD.description
        OR NEW.document_date IS DISTINCT FROM OLD.document_date THEN
        SELECT EXISTS (
            SELECT 1 FROM invoice_item_receipt iir
            JOIN invoice_item ii ON ii.id = iir.invoice_item_id
            JOIN invoice i ON i.id = ii.invoice_id
            WHERE iir.receipt_id = OLD.id AND i.status IN (2, 4)
            UNION ALL
            SELECT 1 FROM invoice_receipt ir
            JOIN invoice i ON i.id = ir.invoice_id
            WHERE ir.receipt_id = OLD.id AND i.status IN (2, 4)
        ) INTO v_issued;
        IF v_issued THEN
            RAISE EXCEPTION 'receipt % is attached to an issued invoice and cannot be changed', OLD.id
                USING ERRCODE = '23001';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_receipt_freeze_when_issued
    BEFORE UPDATE ON receipt
    FOR EACH ROW
    EXECUTE FUNCTION receipt_freeze_when_issued();
-- noqa: enable=CP03

CREATE TABLE travel_expense_entry (
    id                              BIGSERIAL     PRIMARY KEY,
    short_name                      VARCHAR(255)  NOT NULL,
    invoice_item_id                 BIGINT        NOT NULL REFERENCES invoice_item(id) ON DELETE CASCADE,
    per_diem_rate_id                BIGINT        REFERENCES per_diem_rate(id),
    country                         INTEGER       NOT NULL DEFAULT 276,
    participant                     VARCHAR(255)  NOT NULL,
    description                     VARCHAR(255)  NOT NULL,
    travel_date                     DATE          NOT NULL,
    is_arrival_departure            BOOLEAN       NOT NULL DEFAULT FALSE,
    breakfast_deduction             BOOLEAN       NOT NULL DEFAULT FALSE,
    lunch_deduction                 BOOLEAN       NOT NULL DEFAULT FALSE,
    dinner_deduction                BOOLEAN       NOT NULL DEFAULT FALSE,
    full_day_rate_snapshot          NUMERIC(10,2) NOT NULL DEFAULT 0.00,
    arrival_departure_rate_snapshot NUMERIC(10,2) NOT NULL DEFAULT 0.00,
    calculated_amount               NUMERIC(10,2) NOT NULL DEFAULT 0.00,
    employer_contribution           NUMERIC(10,2) NOT NULL DEFAULT 0.00,
    version                         BIGINT        DEFAULT 0,
    CONSTRAINT chk_travel_invoice_item_id_positive CHECK (invoice_item_id > 0),
    CONSTRAINT chk_travel_per_diem_rate_id_positive CHECK (per_diem_rate_id IS NULL OR per_diem_rate_id > 0),
    CONSTRAINT chk_travel_country_positive CHECK (country > 0),
    CONSTRAINT chk_travel_full_day_rate_non_neg          CHECK (full_day_rate_snapshot >= 0),
    CONSTRAINT chk_travel_arrival_departure_rate_non_neg CHECK (arrival_departure_rate_snapshot >= 0),
    CONSTRAINT chk_travel_calculated_amount_non_neg      CHECK (calculated_amount >= 0),
    CONSTRAINT chk_travel_employer_contribution_non_neg  CHECK (employer_contribution >= 0)
);

CREATE INDEX idx_travel_expense_item ON travel_expense_entry (invoice_item_id);
CREATE INDEX idx_travel_perdiem_rate ON travel_expense_entry (per_diem_rate_id);

-- RK-1/P-RK-2 — one expense line (hotel, taxi, mileage, …) of a TRIP invoice_item; child of the
-- item, no repository of its own (DDD). unit_price_net/net_amount deliberately carry NO
-- non-negativity CHECK, same reasoning as invoice_item's own price/amount columns (a
-- credit/discount line within a trip is legitimate).
CREATE TABLE trip_expense (
    id              BIGSERIAL     PRIMARY KEY,
    invoice_item_id BIGINT        NOT NULL REFERENCES invoice_item(id) ON DELETE CASCADE,
    -- RK-1/RS-8 — denormalized, NEVER set by the application/JPA mapping (trigger-populated
    -- below): invoice_item's own row is already gone by the time a native `ON DELETE CASCADE`
    -- from `invoice`/`invoice_item` reaches this table (same ordering PanacheInvoiceRepository
    -- .delete()'s bulk delete exercises for invoice_item's own invoice_number lookup, section
    -- 13's capture_invoice_audit_event() comment), so a join to invoice_item at DELETE time
    -- cannot be relied on to resolve invoice_id for the audit journal. Carrying the value
    -- directly (set once at INSERT, when invoice_item definitely still exists) sidesteps that
    -- ordering problem entirely, the same way invoice_item/invoice_document carry their own
    -- invoice_id directly instead of deriving it from a parent row at journal time.
    invoice_id      BIGINT        NOT NULL,
    kind            INTEGER       NOT NULL DEFAULT 3, -- 0=HOTEL,1=TRANSPORT,2=MILEAGE,3=OTHER,4=TRAVEL_TIME
    description     TEXT          NOT NULL DEFAULT '',
    travel_date     DATE,
    quantity        NUMERIC(14,4) NOT NULL DEFAULT 1,
    unit            INTEGER       NOT NULL DEFAULT 0,
    unit_price_net  NUMERIC(14,2) NOT NULL DEFAULT 0.00,
    rate_provided   BOOLEAN       NOT NULL DEFAULT TRUE,
    net_amount      NUMERIC(14,2) NOT NULL DEFAULT 0.00,
    receipt_id      BIGINT        REFERENCES receipt(id),
    sort_order      INT           NOT NULL DEFAULT 0,
    version         BIGINT        DEFAULT 0,
    CONSTRAINT chk_trip_expense_invoice_item_id_positive CHECK (invoice_item_id > 0),
    CONSTRAINT chk_trip_expense_invoice_id_positive CHECK (invoice_id > 0),
    CONSTRAINT chk_trip_expense_receipt_id_positive CHECK (receipt_id IS NULL OR receipt_id > 0),
    CONSTRAINT chk_trip_expense_kind CHECK (kind >= 0 AND kind <= 4),
    CONSTRAINT chk_trip_expense_unit CHECK (unit >= 0 AND unit <= 17),
    CONSTRAINT chk_trip_expense_quantity_non_negative CHECK (quantity >= 0)
);

COMMENT ON COLUMN trip_expense.invoice_id IS 'RK-1/RS-8 — denormalized copy of invoice_item.invoice_id, populated by trg_trip_expense_before_write (BEFORE INSERT) below; not part of the JPA mapping (TripExpense entity), read only by capture_invoice_audit_event() to resolve the owning invoice without depending on invoice_item still existing at cascade-delete time.';
COMMENT ON COLUMN trip_expense.unit_price_net IS 'Einzelpreis netto. Darf negativ sein (Rabatt-/Abzugszeile innerhalb der Reise), wie invoice_item.unit_price_net.';
COMMENT ON COLUMN trip_expense.receipt_id IS 'P-RK-7 — optional, ein Beleg je Posten (zusaetzlich zum Reise-Item-Beleg invoice_item_receipt); muss bereits am Reise-Item haengen (Anwendungsschicht, RK-2).';

CREATE INDEX idx_trip_expense_item    ON trip_expense (invoice_item_id);
CREATE INDEX idx_trip_expense_invoice ON trip_expense (invoice_id);
CREATE INDEX idx_trip_expense_receipt ON trip_expense (receipt_id);

-- RK-1/RS-11 — one BEFORE trigger covers both concerns for trip_expense, deliberately merged
-- rather than split into two triggers: Postgres fires same-event BEFORE triggers on one table in
-- trigger-NAME alphabetical order, not declaration order, so a separate "populate invoice_id"
-- INSERT trigger and a separate "reject if issued" trigger would race on naming for which runs
-- first on INSERT — merging them removes that hazard entirely (populate always happens before
-- the freeze check within the same function call).
-- INSERT: populates NEW.invoice_id from invoice_item (still guaranteed to exist — you cannot
--   INSERT a trip_expense row for a non-existent invoice_item_id, FK-enforced) and always
--   succeeds — see the RK-2 finding below for why INSERT is never blocked here.
-- UPDATE/DELETE: reads invoice_id directly off OLD (already durable from a prior INSERT) rather
--   than joining through invoice_item, so the check keeps working even once invoice_item's own
--   row is already gone by the time a native `ON DELETE CASCADE` reaches this table (same
--   ordering trip_expense.invoice_id's own column comment documents for
--   capture_invoice_audit_event()). Rejected outright once the owning invoice is issued — unlike
--   invoice_item, which only freezes specific columns, a trip's expense lines have no legitimate
--   reason to change at all once the trip position is frozen.
-- RK-2 (live-Postgres finding, InvoiceStornoServiceTripIT) — INSERT used to run the same status
-- check as UPDATE/DELETE, which made it impossible to ever storno a trip with expense lines:
-- InvoiceStornoService.createCreditNote() builds the credit note already CANCELLED ("born
-- terminal", see that class's own KDoc) and only THEN cascades its copied TripExpense rows —
-- the very first INSERT for those brand-new rows hit "invoice is issued (status 4)" before the
-- document had ever had a chance to exist with any rows at all. invoice_item's own DB-level trip
-- guard (guard_invoice_item_trip_freeze below) never blocked INSERT for exactly this reason — it
-- is `BEFORE UPDATE` only, trusting the application layer (TripService.addTrip/updateTrip's
-- isReadOnly() check) to gate creation; trip_expense now follows the same split: INSERT is
-- always allowed at the DB level (the application layer is the only gate for adding a brand-new
-- row), UPDATE/DELETE of an already-persisted row stays rejected once issued.
-- noqa: disable=CP03
CREATE OR REPLACE FUNCTION trip_expense_before_write() RETURNS TRIGGER AS $$
DECLARE
    v_status INTEGER;
BEGIN
    IF TG_OP = 'INSERT' THEN
        SELECT invoice_id INTO NEW.invoice_id FROM invoice_item WHERE id = NEW.invoice_item_id;
        RETURN NEW;
    END IF;
    SELECT status INTO v_status FROM invoice WHERE id = OLD.invoice_id;
    IF v_status IN (2, 4) THEN -- SENT=2, CANCELLED=4 (chk_invoice_status)
        RAISE EXCEPTION 'trip_expense belongs to invoice % which is issued (status %) and cannot be changed', OLD.invoice_id, v_status
            USING ERRCODE = '23001';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_trip_expense_before_write
    BEFORE INSERT OR UPDATE OR DELETE ON trip_expense
    FOR EACH ROW
    EXECUTE FUNCTION trip_expense_before_write();
-- noqa: enable=CP03

CREATE TABLE invoice_sequence (
    id                BIGSERIAL PRIMARY KEY,
    short_name        VARCHAR(255),
    sender_profile_id BIGINT    NOT NULL,
    year              INT       NOT NULL,
    month             INT       NOT NULL DEFAULT 0,
    last_value        BIGINT    NOT NULL DEFAULT 0,
    CONSTRAINT chk_invoice_sequence_sender_profile_id_positive CHECK (sender_profile_id > 0),
    CONSTRAINT uq_invoice_sequence_profile_year_month UNIQUE (sender_profile_id, year, month)
);

CREATE INDEX idx_invoice_sequence_lookup ON invoice_sequence (sender_profile_id, year, month);

-- P1/GoBD — unveränderlicher Party-Snapshot je Rechnung: eine Zeile je Rolle (SELLER/BUYER)
-- statt zwei Dutzend Spalten auf invoice.
CREATE TABLE invoice_party_snapshot (
    id                            BIGSERIAL     PRIMARY KEY,
    invoice_id                    BIGINT        NOT NULL REFERENCES invoice(id) ON DELETE CASCADE,
    name                          VARCHAR(255)  NOT NULL,
    identifier_value              VARCHAR(255),
    identifier_scheme_id          VARCHAR(10),
    legal_registration_identifier VARCHAR(255),
    legal_registration_scheme_id  VARCHAR(10),
    vat_id                        VARCHAR(64),
    tax_number                    VARCHAR(50),
    electronic_address_value      VARCHAR(255),
    electronic_address_scheme_id  VARCHAR(10),
    buyer_reference               VARCHAR(100),  -- BT-10, nur auf der BUYER-Zeile befüllt
    created_at                    TIMESTAMPTZ   NOT NULL,
    party_role                    SMALLINT      NOT NULL,  -- SELLER=0, BUYER=1
    CONSTRAINT uq_invoice_party_snapshot UNIQUE (invoice_id, party_role),
    CONSTRAINT chk_invoice_party_snapshot_role CHECK (party_role IN (0, 1)),
    CONSTRAINT chk_invoice_party_snapshot_identifier_pair
        CHECK ((identifier_value IS NULL) = (identifier_scheme_id IS NULL)),
    CONSTRAINT chk_invoice_party_snapshot_legal_reg_pair
        CHECK ((legal_registration_identifier IS NULL) = (legal_registration_scheme_id IS NULL)),
    CONSTRAINT chk_invoice_party_snapshot_electronic_address_pair
        CHECK ((electronic_address_value IS NULL) = (electronic_address_scheme_id IS NULL)),
    CONSTRAINT chk_invoice_party_snapshot_buyer_reference_role
        CHECK (buyer_reference IS NULL OR party_role = 1)
);

COMMENT ON TABLE invoice_party_snapshot IS 'P1/GoBD: fixiert Verkäufer-/Käuferidentität zum Rechnungszeitpunkt. Eine finalisierte Rechnung liest ausschließlich hieraus, nie die mutable sender_profile/recipient-Referenz (Compliance-Matrix-Checkpoint 4).';

-- =============================================================================
-- 5. Generated documents, LLM analyses and jobs
-- =============================================================================

CREATE TABLE invoice_document (
    id                  BIGSERIAL    PRIMARY KEY,
    short_name          VARCHAR(255) NOT NULL,
    invoice_id          BIGINT       NOT NULL REFERENCES invoice(id) ON DELETE CASCADE,
    document_type       VARCHAR(50)  NOT NULL DEFAULT '',
    object_key          VARCHAR(512) NOT NULL DEFAULT '',
    file_name           VARCHAR(255) NOT NULL DEFAULT '',
    mime_type           VARCHAR(100) NOT NULL DEFAULT '',
    size_bytes          BIGINT,
    -- RS-10 — NOT NULL: the sole writer (DocumentGeneratedReactionHandler
    -- .onDocumentGeneratedPersistChecksums) only ever inserts a row once the worker-computed
    -- checksum is actually on hand; an artifact without one simply gets no row at all, never a
    -- row with a null checksum (greenfield — no pre-RS-10 invoice_document data exists to violate
    -- this).
    sha256_checksum     VARCHAR(64)  NOT NULL,
    generated_at        TIMESTAMPTZ,
    generated_by        VARCHAR(255),
    generation_status   INTEGER      NOT NULL DEFAULT 0,
    -- Same truthful-default treatment as invoice.validation_status: NOT_VALIDATED (4) is the
    -- born-with default, PENDING (0) means only "a job is genuinely in flight".
    validation_status   INTEGER      NOT NULL DEFAULT 4,
    validation_messages TEXT,
    kosit_report        TEXT,
    -- Internal "Kopie Versand" tag: the document was re-rendered because the original object was
    -- gone. Never visible in the document bytes; reproduction_of_sha256 is the lost original's
    -- checksum, kept for the journal trail.
    reproduced          BOOLEAN      NOT NULL DEFAULT FALSE,
    reproduced_at       TIMESTAMPTZ,
    reproduction_of_sha256 VARCHAR(64),
    version             BIGINT       DEFAULT 0,
    CONSTRAINT chk_invoice_document_invoice_id_positive CHECK (invoice_id > 0),
    CONSTRAINT chk_invoice_document_gen_status CHECK (generation_status >= 0 AND generation_status <= 3), -- 0=PENDING,1=COMPLETED,2=FAILED,3=LOST
    CONSTRAINT chk_invoice_document_val_status CHECK (validation_status  >= 0 AND validation_status <= 4) -- 0=PENDING,1=VALID,2=INVALID,3=UNAVAILABLE,4=NOT_VALIDATED
);

CREATE INDEX idx_invoice_document_invoice ON invoice_document (invoice_id);

-- 1:n (task H2): an invoice may accumulate one analysis per model run, not a single slot that
-- gets overwritten — hence no UNIQUE on invoice_id.
CREATE TABLE invoice_llm_analysis (
    id           BIGSERIAL    PRIMARY KEY,
    short_name   VARCHAR(255),
    invoice_id   BIGINT       NOT NULL REFERENCES invoice(id) ON DELETE CASCADE,
    model_name   VARCHAR(100),
    status       INTEGER      NOT NULL DEFAULT 0,
    rating       INT,
    summary      TEXT,
    issues       TEXT,
    suggestions  TEXT,
    improved_xml TEXT,
    analyzed_at  TIMESTAMPTZ,
    version      BIGINT       DEFAULT 0,
    CONSTRAINT chk_llm_invoice_id_positive CHECK (invoice_id > 0),
    CONSTRAINT chk_invoice_llm_status CHECK (status >= 0 AND status <= 3) -- 0=PENDING,1=COMPLETED,2=FAILED,3=DISABLED
);

CREATE INDEX idx_invoice_llm_invoice ON invoice_llm_analysis (invoice_id);

CREATE TABLE generation_job (
    id             BIGSERIAL   PRIMARY KEY,
    invoice_id     BIGINT      NOT NULL,
    job_type       VARCHAR(50) NOT NULL,
    status         INTEGER     NOT NULL DEFAULT 0,
    result_refs    JSONB,
    kosit_report   TEXT,
    correlation_id VARCHAR(100),
    created_at     TIMESTAMPTZ NOT NULL,
    started_at     TIMESTAMPTZ,
    finished_at    TIMESTAMPTZ,
    engine         VARCHAR(20) NOT NULL DEFAULT 'MUSTANG',
    -- H27-3 — the AI-check choice, made at job creation time and persisted so every publish path
    -- (UI, fast path, poller) sends the same value; no caller-supplied override remains, so there
    -- is no payload difference left to race over.
    llm_optimize   BOOLEAN     NOT NULL DEFAULT FALSE,
    -- N13/RS-1 (P4, PO 2026-09-10) — whether this job was created for an already-issued invoice
    -- (SENT/CANCELLED, including the storno document 384), set exactly once at job creation and
    -- never changed afterward. RS-1's single-generation gate locks on a COMPLETED job with this
    -- flag true, not on ANY completed job -- a review-time draft job (issued = FALSE) must never
    -- lock generation once the invoice is later sent.
    issued         BOOLEAN     NOT NULL DEFAULT FALSE,
    -- ARC-28 — explicit job lifecycle state, tracked alongside `status`.
    attempts       INTEGER     NOT NULL DEFAULT 0,
    last_error     TEXT,
    llm_status     VARCHAR(20),
    -- D4 (PO decision 2026-09-13) — why this job exists: MANUAL (print/export button and the
    -- System-tab backfill sweep), SEND (the "Senden" action), MAINTENANCE (reserved, not yet
    -- distinguished from MANUAL). Named trigger_type, not trigger: TRIGGER is a SQL keyword.
    trigger_type   SMALLINT    NOT NULL DEFAULT 0, -- 0=MANUAL,1=SEND,2=MAINTENANCE,3=REGENERATION,4=REPRODUCTION
    -- ST-A6 — where the job's own DELEGATED publish landed in the `document-jobs` JetStream
    -- stream: set together with the PENDING->DELEGATED transition, nulled again on every
    -- transition back to PENDING. Replaces created_at as the stale-DELEGATED candidate clock and
    -- lets the recovery scan check the message's real existence instead of re-publishing every
    -- queued job on every tick.
    delegated_at        TIMESTAMPTZ,
    delegated_stream_seq BIGINT,
    job_lane       SMALLINT    NOT NULL, -- 0=BULK,1=INTERACTIVE
    CONSTRAINT fk_generation_job_invoice FOREIGN KEY (invoice_id) REFERENCES invoice(id) ON DELETE CASCADE,
    CONSTRAINT chk_generation_job_invoice_id_positive CHECK (invoice_id > 0),
    CONSTRAINT chk_generation_job_status CHECK (status >= 0 AND status <= 4), -- 0=PENDING,1=DELEGATED,2=RUNNING,3=COMPLETED,4=FAILED
    CONSTRAINT chk_generation_job_trigger_type CHECK (trigger_type >= 0 AND trigger_type <= 4),
    CONSTRAINT chk_generation_job_job_lane CHECK (job_lane >= 0 AND job_lane <= 1)
);

-- Transactional outbox: a producer inserts its event row in the same transaction as the domain
-- change, the relay publishes it to JetStream afterwards. `id` doubles as the JetStream
-- de-duplication id, so a publish that is repeated after a crash between ack and mark is dropped
-- by the stream. created_at/published_at come from the application's TimeSource, never from the
-- database clock.
CREATE TABLE outbox (
    id             BIGSERIAL    PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id   VARCHAR(100) NOT NULL,
    subject        VARCHAR(255) NOT NULL,
    payload        TEXT         NOT NULL,
    status         SMALLINT     NOT NULL,  -- 0=PENDING,1=PUBLISHED,2=FAILED
    attempts       INTEGER      NOT NULL,
    last_error     TEXT,
    created_at     TIMESTAMPTZ  NOT NULL,
    published_at   TIMESTAMPTZ,
    CONSTRAINT chk_outbox_status CHECK (status IN (0, 1, 2)),
    CONSTRAINT chk_outbox_attempts CHECK (attempts >= 0),
    CONSTRAINT chk_outbox_published_at CHECK ((status = 1) = (published_at IS NOT NULL))
);

CREATE INDEX idx_outbox_pending ON outbox (id) WHERE status = 0;

COMMENT ON COLUMN generation_job.created_at IS 'date invariants (PO 2026-09-02) — every print/generation job carries a print date; never NULL.';
COMMENT ON COLUMN generation_job.engine IS 'task12-b2b/T8 — welche Erzeugungs-Engine dieser Erzeugungsauftrag verwendet: MUSTANG (Default) oder FACTURX.';
COMMENT ON COLUMN generation_job.llm_optimize IS 'H27-3 — the AI-check choice made at job creation time, persisted so every publish path (UI, fast path, poller) sends the same value.';
COMMENT ON COLUMN generation_job.attempts IS 'ARC-28 — number of stale resets (DELEGATED->PENDING) of this job; see invoice.jobs.max-attempts.';
COMMENT ON COLUMN generation_job.last_error IS 'ARC-28 — last plain-text failure reason (attempt-budget exhaustion or a DLQ decision by the result consumer).';
COMMENT ON COLUMN generation_job.llm_status IS 'ARC-28 — LLM sub-state (PENDING/COMPLETED/FAILED/DISABLED/NOT_REQUESTED), persisted in addition to the existing result_refs JSON.';
COMMENT ON COLUMN generation_job.issued IS 'N13/RS-1 (P4) — TRUE when this job was created for an already-issued invoice (SENT/CANCELLED); RS-1''s single-generation gate locks on a COMPLETED job with issued = TRUE, never on a review-time draft job.';
COMMENT ON COLUMN generation_job.job_lane IS 'Queue lane the job is published on: 0=BULK (imports, regeneration, reproduction, sweeps, admin maintenance), 1=INTERACTIVE (a user in the UI waits for the document). Fixed at creation. trigger_type cannot express it: MANUAL covers both the UI print button and the scheduled/admin sweeps.';
COMMENT ON COLUMN generation_job.trigger_type IS 'D4 (PO decision 2026-09-13) — why this job exists: 0=MANUAL (print/export, and the backfill sweep until distinguished), 1=SEND (the "Senden" action -- always issued = TRUE, see generation_job.issued), 2=MAINTENANCE (reserved), 3=REGENERATION (re-render of a lost original of an unsent invoice), 4=REPRODUCTION (re-render of a lost original of a sent invoice; the result is tagged and never mailed).';
COMMENT ON COLUMN generation_job.delegated_at IS 'ST-A6 — set together with the PENDING->DELEGATED transition, NULL again on every transition back to PENDING; the stale-DELEGATED candidate clock.';
COMMENT ON COLUMN generation_job.delegated_stream_seq IS 'ST-A6 — this job''s own document-jobs publish sequence (PublishAck.seqno), recorded once the publish ack arrives; NULL means no ack was ever recorded (legacy row / lost ack), which the stale-DELEGATED reset treats as evidence-free and resets outright.';

CREATE INDEX idx_generation_job_invoice_id        ON generation_job (invoice_id);
CREATE INDEX idx_generation_job_status            ON generation_job (status);
CREATE INDEX idx_generation_job_correlation_id    ON generation_job (correlation_id);
CREATE INDEX idx_generation_job_result_refs_gin   ON generation_job USING gin (result_refs);
CREATE INDEX idx_generation_job_created_at        ON generation_job (created_at);
CREATE INDEX idx_generation_job_delegated_at      ON generation_job (delegated_at) WHERE status = 1;
CREATE INDEX idx_generation_job_active_created_at ON generation_job (created_at) WHERE status IN (0, 1, 2);
-- At most one active (PENDING/DELEGATED/RUNNING) print job per invoice and variant (engine, LLM
-- option): the database-level guard behind the application's idempotency check, closing the race
-- between two concurrent print requests.
CREATE UNIQUE INDEX uq_generation_job_active_print ON generation_job (invoice_id, engine, llm_optimize)
    WHERE job_type = 'PRINT_JOB' AND status IN (0, 1, 2);

-- =============================================================================
-- 6. Validation / compliance
-- =============================================================================

-- F4/VALIDATION-01 — Profilregister: welche Profil-/Guideline-Kombination als geprüft (ACTIVE)
-- gilt, welche veraltet (DEPRECATED) und welche gesperrt (BLOCKED) ist.
CREATE TABLE validation_profile (
    id                       BIGSERIAL     PRIMARY KEY,
    profile_id               VARCHAR(100)  NOT NULL,
    spec_version             VARCHAR(50)   NOT NULL,
    syntax                   VARCHAR(50),
    codelist_version         VARCHAR(50),
    validator_config_version VARCHAR(50),
    mustang_version          VARCHAR(50),
    state                    VARCHAR(20)   NOT NULL,
    valid_from               DATE,
    valid_to                 DATE,
    created_at               TIMESTAMPTZ   NOT NULL,
    version                  BIGINT        DEFAULT 0,
    CONSTRAINT uq_validation_profile_id_version UNIQUE (profile_id, spec_version),
    CONSTRAINT chk_validation_profile_state
        CHECK (state IN ('EXPERIMENTAL', 'ACTIVE', 'DEPRECATED', 'BLOCKED')),
    CONSTRAINT chk_validation_profile_validity
        CHECK (valid_from IS NULL OR valid_to IS NULL OR valid_from <= valid_to)
);

COMMENT ON TABLE validation_profile IS 'F4/VALIDATION-01 — welche Profil-/Guideline-Version als geprüft gilt; Grundlage für das Finalisierungs-Gate.';
COMMENT ON COLUMN validation_profile.profile_id IS 'Der von XmlExportServiceImpl.resolveProfile() gelieferte Wert (z.B. "XRechnung").';

-- F5 — Befunde je Job/Rechnung. Der Blob in generation_job.kosit_report bleibt die Wahrheit für
-- die Anzeige alter Jobs, die diese Tabelle nicht kennen.
CREATE TABLE validation_finding (
    id                BIGSERIAL     PRIMARY KEY,
    generation_job_id BIGINT        NOT NULL,
    invoice_id        BIGINT        NOT NULL,
    stage             VARCHAR(20),
    validator         VARCHAR(50),
    rule_id           VARCHAR(50),
    severity          VARCHAR(20)   NOT NULL,
    location          TEXT,
    message           TEXT          NOT NULL,
    validator_version VARCHAR(100),
    created_at        TIMESTAMPTZ   NOT NULL,
    CONSTRAINT fk_validation_finding_generation_job FOREIGN KEY (generation_job_id) REFERENCES generation_job(id) ON DELETE CASCADE,
    CONSTRAINT fk_validation_finding_invoice FOREIGN KEY (invoice_id) REFERENCES invoice(id) ON DELETE CASCADE
);

COMMENT ON TABLE validation_finding IS 'F5 — Auswertungssicht je Befund; der Blob in generation_job.kosit_report bleibt die Wahrheit für die Anzeige alter Jobs.';
COMMENT ON COLUMN validation_finding.stage IS 'SYNTAX | EN16931 | CIUS | KOSIT | PDFA | BUSINESS — siehe ValidationMessage.stage in kotlin-shared. NULL bei Altbefunden ohne Stufe.';
COMMENT ON COLUMN validation_finding.rule_id IS 'z.B. BR-CO-25, BR-DE-15, oder ein XSD-Fehlercode. NULL wenn der Validator keine Regel-ID liefert (F5-T03: kein Grund, die Zeile zu verwerfen).';

CREATE INDEX idx_validation_finding_invoice    ON validation_finding (invoice_id);
CREATE INDEX idx_validation_finding_job        ON validation_finding (generation_job_id);
CREATE INDEX idx_validation_finding_stage_rule ON validation_finding (stage, rule_id);

-- Runtime feature switches; a missing row leaves the feature enabled.
CREATE TABLE feature_setting (
    key         VARCHAR(64) PRIMARY KEY,
    enabled     BOOLEAN     NOT NULL DEFAULT TRUE,
    description VARCHAR(255),
    version     BIGINT      NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ,
    updated_at  TIMESTAMPTZ,
    created_by  VARCHAR(255),
    updated_by  VARCHAR(255)
);

COMMENT ON TABLE feature_setting IS 'SFS — fachlicher Ein/Aus-Schalter je Funktion. Fehlende Zeile = eingeschaltet (fail-open), wie validation_setting.';
COMMENT ON COLUMN feature_setting.key IS 'Schluessel des Features (z. B. ''feature.perdiem.enabled''), nicht seine Ordnungszahl.';
COMMENT ON COLUMN feature_setting.enabled IS 'Ist das Feature aktiviert (Standard: true). Abgeschaltet heisst: Erfassungs-/Versandangebot entfaellt, Bestandsdaten bleiben.';
COMMENT ON COLUMN feature_setting.description IS 'Freitext-Anmerkung fuer den Betrieb, fachlich wirkungslos.';
COMMENT ON COLUMN feature_setting.version IS 'Version fuer Optimistic Locking (JPA @Version).';

-- T9 — Ein/Aus-Schalter je Prüfung, gepflegt in den Referenzdaten. Der Schalter kann eine
-- technisch verfügbare Prüfung fachlich stummschalten, aber keinen fehlenden Endpunkt ersetzen.
CREATE TABLE validation_setting (
    capability_id VARCHAR(64) PRIMARY KEY,
    enabled       BOOLEAN     NOT NULL DEFAULT TRUE,
    version       BIGINT      NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ,
    updated_at    TIMESTAMPTZ,
    created_by    VARCHAR(255),
    updated_by    VARCHAR(255)
);

COMMENT ON TABLE validation_setting IS 'T9 — fachlicher Ein/Aus-Schalter je Prüfung. Fehlende Zeile = eingeschaltet (fail-open).';
COMMENT ON COLUMN validation_setting.capability_id IS 'Name der ValidationCapabilityId-Konstante, nicht ihre Ordnungszahl.';

-- RK-7 — kleine generische Schlüssel/Wert-Tabelle für Einstellungen, die keinen einfachen Ein/Aus-
-- Schalter darstellen (feature_setting bleibt boolean-only). Heute genau eine Zeile:
-- 'wording.profile' (WordingSettingsGate) — der Wert ist der WordingProfile-Enum-Name
-- ('HANDWERK'/'DIENSTLEISTUNG'), keine Ordnungszahl.
CREATE TABLE system_setting (
    key         VARCHAR(64) PRIMARY KEY,
    value       VARCHAR(255) NOT NULL,
    version     BIGINT      NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ,
    updated_at  TIMESTAMPTZ,
    created_by  VARCHAR(255),
    updated_by  VARCHAR(255)
);

COMMENT ON TABLE system_setting IS 'RK-7 — generische Schlüssel/Wert-Einstellungen jenseits reiner Ein/Aus-Schalter. Fehlende Zeile = Installationsvorgabe (Property/Env) gilt.';
COMMENT ON COLUMN system_setting.key IS 'z. B. ''wording.profile''.';
COMMENT ON COLUMN system_setting.value IS 'Freitext-Wert, fachlich durch den jeweiligen Gate interpretiert (z. B. der WordingProfile-Enum-Name).';

-- =============================================================================
-- 7. Number ranges, counters and journal (task13-N1/N3/N4)
-- =============================================================================

CREATE TABLE number_range (
    id                BIGSERIAL PRIMARY KEY,
    code              VARCHAR(50)  NOT NULL UNIQUE,
    display_name      VARCHAR(100) NOT NULL,
    start_value       BIGINT       NOT NULL DEFAULT 1,
    fixed_prefix      VARCHAR(30),
    counter_width     INTEGER      NOT NULL DEFAULT 5,
    is_active         BOOLEAN      NOT NULL DEFAULT TRUE,
    is_default        BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMPTZ,
    version           BIGINT       NOT NULL DEFAULT 0,
    subject           SMALLINT     NOT NULL,  -- INVOICE=0, CUSTOMER=1, ARTICLE=2, SELLER=3
    prefix_source     SMALLINT     NOT NULL,  -- NONE=0, FIXED=1, SELLER_PROFILE=2, FROM_DATA=3
    prefix_data_field SMALLINT,               -- NAME_INITIALS=0, COUNTRY_CODE=1, ARTICLE_CATEGORY=2
    date_part         SMALLINT     NOT NULL,  -- NONE=0, YEAR=1, YEAR_MONTH=2, YEAR_MONTH_DAY=3
    reset_cycle       SMALLINT     NOT NULL,  -- NEVER=0, YEARLY=1
    scope             SMALLINT     NOT NULL,  -- GLOBAL=0, PER_SELLER=1
    continuity        SMALLINT     NOT NULL,  -- GAPLESS=0, UNIQUE_ONLY=1
    CONSTRAINT chk_counter_width CHECK (counter_width >= 1),
    CONSTRAINT chk_number_range_subject CHECK (subject IN (0, 1, 2, 3, 4)),
    CONSTRAINT chk_number_range_prefix_source CHECK (prefix_source IN (0, 1, 2, 3)),
    CONSTRAINT chk_number_range_prefix_data_field CHECK (prefix_data_field IS NULL OR prefix_data_field IN (0, 1, 2)),
    CONSTRAINT chk_number_range_date_part CHECK (date_part IN (0, 1, 2, 3)),
    CONSTRAINT chk_number_range_reset_cycle CHECK (reset_cycle IN (0, 1)),
    CONSTRAINT chk_number_range_scope CHECK (scope IN (0, 1)),
    CONSTRAINT chk_number_range_continuity CHECK (continuity IN (0, 1))
);

CREATE TABLE number_counter (
    id              BIGSERIAL PRIMARY KEY,
    number_range_id BIGINT      NOT NULL REFERENCES number_range (id),
    scope_key       VARCHAR(40) NOT NULL,
    period_key      VARCHAR(20) NOT NULL,
    last_value      BIGINT      NOT NULL DEFAULT 1,
    CONSTRAINT uq_number_counter UNIQUE (number_range_id, scope_key, period_key)
);

COMMENT ON COLUMN number_counter.last_value IS
    'task13-N1/6 — der ZULETZT vergebene Zaehlerstand; last_value + 1 ist die naechste Nummer.';

CREATE INDEX idx_number_counter_subject_scope ON number_counter (number_range_id, scope_key, period_key);

-- `seq` ist ein eigenes SEQUENCE-Objekt statt eines gewoehnlichen BIGSERIAL/IDENTITY: Der
-- entry_hash braucht seq als Eingabe, seq steht aber erst nach dem Einfuegen fest, wenn eine
-- Spalte ihn implizit vergibt -- ein nachtraegliches UPDATE des Hashes waere selbst schon der
-- Verstoss gegen "ausschliesslich Anhaengen". Die Anwendung zieht den Wert deshalb VOR dem
-- Einfuegen per `nextval(...)`, berechnet den Hash damit, und fuegt beides in einem Schritt ein.
CREATE SEQUENCE number_journal_seq;

CREATE TABLE number_journal (
    seq                 BIGINT PRIMARY KEY DEFAULT NEXTVAL('number_journal_seq'),
    number_range_id     BIGINT       NOT NULL REFERENCES number_range (id),
    event               VARCHAR(20)  NOT NULL,
    period_key          VARCHAR(20)  NOT NULL,
    number_prefix       VARCHAR(30),
    number_sequence     BIGINT,
    number              VARCHAR(80),
    entity_type         VARCHAR(40),
    entity_id           BIGINT,
    reason              TEXT,
    actor               VARCHAR(255) NOT NULL,
    occurred_at         TIMESTAMPTZ  NOT NULL,
    prev_hash           VARCHAR(64)  NOT NULL,
    entry_hash          VARCHAR(64)  NOT NULL,
    -- task13-N4 §4 — nur bei event = PERIOD_SEALED gesetzt.
    sealed_start_value  BIGINT,
    sealed_count        BIGINT,
    sealed_first_number VARCHAR(80),
    sealed_last_number  VARCHAR(80)
);

CREATE INDEX idx_number_journal_range ON number_journal (number_range_id, seq);

-- Ausschliesslich-Anhaengen an der Datenbank selbst erzwungen, nicht nur per Konvention im
-- Anwendungscode: ein Trigger greift unabhaengig davon, welche Rolle das UPDATE/DELETE versucht.
--
-- RS-11 (M3, beratung-manipulationsschutz.md) — replaces the two CREATE RULE ... DO INSTEAD
-- NOTHING statements this section used to carry. A RULE silently swallows the write: the
-- UPDATE/DELETE reports success (0 rows visibly changed, no error) with nothing to tell the
-- caller its statement had no effect — exactly the kind of silent behavior this program removes.
-- reject_append_only_mutation() RAISEs instead, so a direct SQL UPDATE/DELETE against this table
-- now fails loudly. Written generically (TG_TABLE_NAME/TG_OP read off the firing trigger, no
-- number_journal-specific text) so RS-8 can attach the SAME function to invoice_audit_journal
-- (also append-only, also hash-chained) without a second copy.
-- The definition and its EXECUTE FUNCTION call below are block-excluded from CP03 — see
-- guard_invoice_freeze()'s own comment above for why (capitalisation.functions rule vs. this
-- file's snake_case identifier convention; a line-level noqa does not survive the multi-line
-- dollar-quoted plpgsql body).
-- noqa: disable=CP03
CREATE OR REPLACE FUNCTION reject_append_only_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION '% is append-only; % is not permitted', TG_TABLE_NAME, TG_OP
        USING ERRCODE = '23001'; -- restrict_violation, the same class a FK RESTRICT rejection uses
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_number_journal_append_only
    BEFORE UPDATE OR DELETE ON number_journal
    FOR EACH ROW
    EXECUTE FUNCTION reject_append_only_mutation();
-- noqa: enable=CP03

COMMENT ON TABLE number_journal IS 'task13-N4 — append-only Journal jeder GAPLESS-Nummernvergabe, hashverkettet (prev_hash/entry_hash). UPDATE/DELETE werden per Trigger (RS-11, trg_number_journal_append_only) mit RAISE EXCEPTION abgelehnt.';
COMMENT ON COLUMN number_journal.seq IS 'Reihenfolge der Journaleintraege selbst, NICHT die vergebene Rechnungsnummer (die steht in number_sequence).';
COMMENT ON COLUMN number_journal.prev_hash IS 'entry_hash des vorherigen Eintrags desselben Kreises, oder der Genesis-Hash (Kreis-Code + Startwert) fuer den ersten.';
COMMENT ON COLUMN number_journal.sealed_start_value IS 'nur bei event = PERIOD_SEALED gesetzt: Startwert des Kreises zum Zeitpunkt des Abschlusses.';
COMMENT ON COLUMN number_journal.sealed_count IS 'nur bei event = PERIOD_SEALED gesetzt: Anzahl der Vergaben in der abgeschlossenen Periode.';

-- =============================================================================
-- 8. System / maintenance
-- =============================================================================

CREATE TABLE database_maintenance_event (
    id         BIGSERIAL   PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    type       INTEGER     NOT NULL DEFAULT 0,
    status     INTEGER     NOT NULL DEFAULT 0,
    details    TEXT,
    CONSTRAINT chk_dbm_type   CHECK (type >= 0 AND type <= 9), -- 0=VACUUM,1=AUTO_VACUUM,2=ANALYZE,3=AUTO_ANALYZE,4=REINDEX,5=BACKUP,6=RECALCULATE_TOTALS,7=CHAIN_VERIFICATION (RS-12),8=QUEUE_ISSUED_GENERATION_JOBS (N12),9=SYSTEM_MESSAGE_RETENTION (ARC91-04)
    CONSTRAINT chk_dbm_status CHECK (status >= 0 AND status <= 4) -- 0=SUCCESS,1=FAILED,2=UNKNOWN,3=PENDING,4=RUNNING
);

CREATE INDEX idx_database_maintenance_event_type_created_at ON database_maintenance_event (type, created_at DESC);

-- Versioned, fenced ownership record for maintenance claims. Its presence distinguishes new
-- executions from unclaimed RUNNING rows. An interrupted claim is not automatically replayed:
-- its partial effects remain ambiguous and recovery terminalizes the request as UNKNOWN.
CREATE SEQUENCE maintenance_queue_claim_token_seq;

CREATE TABLE maintenance_queue_execution (
    event_id          BIGINT      PRIMARY KEY
        REFERENCES database_maintenance_event(id) ON DELETE CASCADE,
    execution_version INTEGER     NOT NULL,
    claim_token       BIGINT      NOT NULL,
    claimed_at        TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_maintenance_queue_execution_version CHECK (execution_version > 0)
);

-- =============================================================================
-- 9. Messaging (MC-1/ARC-9)
-- =============================================================================
-- Two tables: system_message (the persistent, timestamped notification itself) and
-- system_message_receipt (per-user read state, user-ready from day one even though only the
-- actor "system" writes any today — see AuditActorProvider).
--
-- system_message.recipient_user_id is nullable BY DESIGN: NULL means "visible to every user"
-- (broadcast), a non-null value scopes the message to one user. No FK on ref_type/ref_id: a
-- message must stay readable after the entity it refers to (e.g. an invoice) is gone.
--
-- system_message_receipt.message_id carries ON DELETE CASCADE: the retention cleanup
-- (MessageCenterService.cleanup, littletrotter.message-center.retention-days) deletes old
-- messages with a plain bulk DELETE and relies on Postgres to remove their receipts, not on the
-- application loading and cascading them.
CREATE TABLE system_message (
    id                 BIGSERIAL PRIMARY KEY,
    occurred_at        TIMESTAMPTZ NOT NULL,
    message_key        VARCHAR(255) NOT NULL,
    message_args       TEXT        NOT NULL DEFAULT '[]',
    resolved_text      TEXT        NOT NULL,
    ref_type           VARCHAR(50),
    ref_id             VARCHAR(50),
    actor              VARCHAR(255) NOT NULL,
    recipient_user_id  VARCHAR(255),
    version            BIGINT      NOT NULL DEFAULT 0,
    severity           SMALLINT    NOT NULL,  -- INFO=0, WARNING=1, ERROR=2
    source             SMALLINT    NOT NULL,  -- EXPORT=0, VALIDATION=1, GENERATION=2, INVOICE=3, SYSTEM=4, UI=5
    CONSTRAINT chk_system_message_severity CHECK (severity IN (0, 1, 2)),
    CONSTRAINT chk_system_message_source CHECK (source IN (0, 1, 2, 3, 4, 5))
);

CREATE INDEX idx_system_message_occurred_at ON system_message (occurred_at DESC);
CREATE INDEX idx_system_message_recipient_occurred ON system_message (recipient_user_id, occurred_at DESC);

COMMENT ON TABLE system_message IS 'ARC-9/MC-1 — persistent, timestamped system notification (severity/source/i18n key + resolved German fallback text + optional entity reference). recipient_user_id NULL = broadcast to every user.';
COMMENT ON COLUMN system_message.message_args IS 'JSON array of positional i18n args for language-neutral re-rendering; may be an empty array.';
COMMENT ON COLUMN system_message.resolved_text IS 'German fallback rendered at creation time — stays readable even after message_key is renamed or removed.';
COMMENT ON COLUMN system_message.ref_type IS 'Open string (e.g. INVOICE), deliberately no FK — the message must survive deletion of the entity it refers to.';
COMMENT ON COLUMN system_message.recipient_user_id IS 'NULL = visible to every user (broadcast). With Authentik, targeted messages set this to the subject claim.';

CREATE TABLE system_message_receipt (
    id         BIGSERIAL PRIMARY KEY,
    message_id BIGINT      NOT NULL REFERENCES system_message (id) ON DELETE CASCADE,
    user_id    VARCHAR(255) NOT NULL,
    read_at    TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_system_message_receipt_message_user UNIQUE (message_id, user_id)
);

COMMENT ON TABLE system_message_receipt IS 'ARC-9/MC-1 — per-user read receipt for a system_message. Today only user_id = ''system'' rows appear (AuditActorProvider has no auth yet); with Authentik the same structure carries n users without a schema change.';

-- =============================================================================
-- 10. Statistics views
-- =============================================================================
--
-- Two layers, nothing else:
--
--   1. Base layer — one row per invoice / per invoice item, carrying every dimension the UI can
--      filter by (year/quarter/month/day, sender, recipient, status, reverse_charge). Rankings
--      and the status distribution read these directly.
--   2. Aggregation layer — pre-summed per period, keeping all filter dimensions in the GROUP BY.
--      The application filters these views and rolls the sub-groups up with a final GROUP BY
--      (e.g. GROUP BY year). That is a correct two-level aggregation, not a double count:
--      invoice_count is summed.
--
-- Invoice number is deliberately NOT a dimension of the aggregated views: it is unique per
-- invoice, so grouping by it is meaningless. Text search on individual invoice numbers was
-- removed from the statistics UI; only name-based filtering (sender_name / recipient_name)
-- remains.
--
-- Amounts stay NUMERIC so the Kotlin layer reads BigDecimal directly and bypasses the Hibernate
-- MoneyConverter path, which is ignored for scalar SUM/GROUP BY projections.

-- ── 1. Base layer ────────────────────────────────────────────────────────────────────────────

CREATE VIEW v_stat_available_years AS
SELECT DISTINCT EXTRACT(YEAR FROM invoice_date)::INT AS year
FROM invoice
WHERE invoice_date IS NOT NULL;

CREATE VIEW v_stat_invoice_base AS
SELECT
    i.id                             AS invoice_id,
    i.invoice_number                 AS invoice_number,
    i.status                         AS status,
    i.reverse_charge                 AS reverse_charge,
    i.currency                       AS currency,
    i.net_amount                     AS net_amount,
    i.vat_amount                     AS vat_amount,
    i.gross_amount                   AS gross_amount,
    i.invoice_date                   AS invoice_date,
    EXTRACT(YEAR FROM i.invoice_date)::INT AS year,
    EXTRACT(MONTH FROM i.invoice_date)::INT AS month,
    EXTRACT(DAY FROM i.invoice_date)::INT AS day,
    CASE
        WHEN EXTRACT(MONTH FROM i.invoice_date) BETWEEN 1 AND 3  THEN 1
        WHEN EXTRACT(MONTH FROM i.invoice_date) BETWEEN 4 AND 6  THEN 2
        WHEN EXTRACT(MONTH FROM i.invoice_date) BETWEEN 7 AND 9  THEN 3
        ELSE 4
    END                              AS quarter,
    COALESCE(i.selected_seller_id, i.sender_profile_id) AS sender_id,
    COALESCE(s.name, sp.name)        AS sender_name,
    i.recipient_id                   AS recipient_id,
    r.name                           AS recipient_name
FROM invoice i
LEFT JOIN recipient r ON r.id = i.recipient_id
LEFT JOIN sender_profile s ON s.id = i.selected_seller_id
LEFT JOIN sender_profile sp ON sp.id = i.sender_profile_id
WHERE i.invoice_date IS NOT NULL;

-- month/quarter derived exactly as in v_stat_invoice_base, so the item rankings can honour the
-- same sub-period filter the invoice rankings do.
CREATE VIEW v_stat_item_base AS
SELECT
    it.id                            AS item_id,
    it.description                   AS description,
    it.net_amount                    AS net_amount,
    it.quantity                      AS quantity,
    i.id                             AS invoice_id,
    i.invoice_number                 AS invoice_number,
    i.status                         AS status,
    i.reverse_charge                 AS reverse_charge,
    i.currency                       AS currency,
    i.invoice_date                   AS invoice_date,
    EXTRACT(YEAR FROM i.invoice_date)::INT AS year,
    EXTRACT(MONTH FROM i.invoice_date)::INT AS month,
    CASE
        WHEN EXTRACT(MONTH FROM i.invoice_date) BETWEEN 1 AND 3  THEN 1
        WHEN EXTRACT(MONTH FROM i.invoice_date) BETWEEN 4 AND 6  THEN 2
        WHEN EXTRACT(MONTH FROM i.invoice_date) BETWEEN 7 AND 9  THEN 3
        ELSE 4
    END                              AS quarter,
    COALESCE(i.selected_seller_id, i.sender_profile_id) AS sender_id,
    COALESCE(s.name, sp.name)        AS sender_name,
    i.recipient_id                   AS recipient_id,
    r.name                           AS recipient_name
FROM invoice_item it
JOIN invoice i ON i.id = it.invoice_id
LEFT JOIN recipient r ON r.id = i.recipient_id
LEFT JOIN sender_profile s ON s.id = i.selected_seller_id
LEFT JOIN sender_profile sp ON sp.id = i.sender_profile_id
WHERE i.invoice_date IS NOT NULL;

-- ── 2. Aggregation layer: invoice totals per period ──────────────────────────────────────────

CREATE VIEW v_stat_yearly AS
SELECT
    year,
    sender_id, sender_name, recipient_id, recipient_name,
    status, reverse_charge, currency,
    SUM(net_amount)   AS net_amount,
    SUM(vat_amount)   AS vat_amount,
    SUM(gross_amount) AS gross_amount,
    COUNT(*)          AS invoice_count
FROM v_stat_invoice_base
GROUP BY year, sender_id, sender_name, recipient_id, recipient_name, status, reverse_charge, currency;

CREATE VIEW v_stat_quarterly AS
SELECT
    year, quarter,
    sender_id, sender_name, recipient_id, recipient_name,
    status, reverse_charge, currency,
    SUM(net_amount)   AS net_amount,
    SUM(vat_amount)   AS vat_amount,
    SUM(gross_amount) AS gross_amount,
    COUNT(*)          AS invoice_count
FROM v_stat_invoice_base
GROUP BY year, quarter, sender_id, sender_name, recipient_id, recipient_name, status, reverse_charge, currency;

CREATE VIEW v_stat_monthly AS
SELECT
    year, month,
    sender_id, sender_name, recipient_id, recipient_name,
    status, reverse_charge, currency,
    SUM(net_amount)   AS net_amount,
    SUM(vat_amount)   AS vat_amount,
    SUM(gross_amount) AS gross_amount,
    COUNT(*)          AS invoice_count
FROM v_stat_invoice_base
GROUP BY year, month, sender_id, sender_name, recipient_id, recipient_name, status, reverse_charge, currency;

CREATE VIEW v_stat_daily AS
SELECT
    year, month, day,
    sender_id, sender_name, recipient_id, recipient_name,
    status, reverse_charge, currency,
    SUM(net_amount)   AS net_amount,
    SUM(vat_amount)   AS vat_amount,
    SUM(gross_amount) AS gross_amount,
    COUNT(*)          AS invoice_count
FROM v_stat_invoice_base
GROUP BY year, month, day, sender_id, sender_name, recipient_id, recipient_name, status, reverse_charge, currency;

-- ── 3. Aggregation layer: item totals per period (Top-Artikel ranking) ───────────────────────

CREATE VIEW v_stat_item_yearly AS
SELECT
    description, year,
    sender_id, sender_name, recipient_id, recipient_name,
    status, reverse_charge, currency,
    SUM(net_amount) AS net_amount,
    SUM(quantity)   AS quantity
FROM v_stat_item_base
GROUP BY description, year, sender_id, sender_name, recipient_id, recipient_name, status, reverse_charge, currency;

CREATE VIEW v_stat_item_quarterly AS
SELECT
    description, year, quarter,
    sender_id, sender_name, recipient_id, recipient_name,
    status, reverse_charge, currency,
    SUM(net_amount) AS net_amount,
    SUM(quantity)   AS quantity
FROM v_stat_item_base
GROUP BY description, year, quarter, sender_id, sender_name, recipient_id, recipient_name, status, reverse_charge, currency;

CREATE VIEW v_stat_item_monthly AS
SELECT
    description, year, month,
    sender_id, sender_name, recipient_id, recipient_name,
    status, reverse_charge, currency,
    SUM(net_amount) AS net_amount,
    SUM(quantity)   AS quantity
FROM v_stat_item_base
GROUP BY description, year, month, sender_id, sender_name, recipient_id, recipient_name, status, reverse_charge, currency;

-- =============================================================================
-- 11. goauthentik role/schema bootstrap
-- =============================================================================
--
-- Not application schema: this only makes sure the goauthentik role can use its own schema when
-- it shares the invoice Postgres instance. The primary bootstrap runs in
-- docker/postgres/init-authentik.sh; this is the idempotent safety net for setups where that
-- script did not run. On a core-only start (no authentik_user role) it does nothing.

DO $$
BEGIN
  IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'authentik_user') THEN
    EXECUTE 'CREATE SCHEMA IF NOT EXISTS authentik AUTHORIZATION authentik_user';
    EXECUTE FORMAT('GRANT CONNECT ON DATABASE %I TO authentik_user', CURRENT_DATABASE());
    EXECUTE 'GRANT USAGE, CREATE ON SCHEMA authentik TO authentik_user';
    EXECUTE 'ALTER ROLE authentik_user SET search_path TO authentik';
  ELSE
    RAISE NOTICE 'authentik_user missing; skipping auth schema grants for core-only startup';
  END IF;
END
$$;

-- =============================================================================
-- 12. RS-8 invoice audit journal (hash-chained, trigger-based)
-- =============================================================================
--
-- konzept-aenderungsjournal.md (binding, J1-J3, PO 2026-09-03) — a single, append-only,
-- hash-chained journal of every INSERT/UPDATE/DELETE against invoice, invoice_item and
-- invoice_document, attributed to the OIDC identity that caused it. Same append-only shape as
-- number_journal (section 7): its own SEQUENCE so entry_hash can be computed BEFORE the INSERT
-- (a post-insert UPDATE would itself already violate "append only"; the table sits behind the
-- same reject_append_only_mutation() guard trigger number_journal uses, section 7/RS-11, written
-- generically there for exactly this reuse), prev_hash/entry_hash chaining. Deliberate
-- differences from number_journal:
--
--   * ONE chain for the whole table, not one chain per number_range: invoice_audit_journal has
--     no partition key the way number_journal has number_range_id (J2 — the concept spans three
--     tables, not one counter circuit each), so capture_invoice_audit_event() below serializes
--     every append across every invoice/invoice_item/invoice_document write in the database with
--     a single pg_advisory_xact_lock — the same technique
--     PostgresNumberJournalRepository.lockChain (Kotlin) uses to serialize each number_range's
--     own chain; there is no per-range equivalent to lock on here, so the lock covers the table.
--   * computed entirely IN THE TRIGGER via pgcrypto's digest(), not in application code:
--     number_journal is only ever written through PostgresNumberJournalRepository, but J1
--     (PO 2026-09-03) specifically wants this journal to also catch a write that bypasses the
--     application entirely (a direct psql UPDATE, an admin script) — only a trigger sees that.
--   * invoice_id deliberately carries no FK/CASCADE: the journal must outlive the invoice it
--     describes (e.g. a hard-deleted DRAFT still leaves its creation/edit trail behind).
--
-- capture_invoice_audit_event() reads the actor from two session GUCs the application sets once
-- per transaction (AuditGucInterceptor, kotlin-invoice application/shared/AuditActorProvider.kt):
-- app.actor (OIDC sub — stable across username/display-name changes) and app.actor_name (OIDC
-- preferred_username/email at write time). Every app-managed, journaled transaction sets
-- app.actor explicitly (to a real sub, or to 'system' for a background job / a request with no
-- identity — AuditActorProvider's own fallback) — so current_setting('app.actor', true) coming
-- back completely unset (NULL, distinct from the empty string an explicitly-set-to-'system' GUC
-- never produces) means the write reached these tables OUTSIDE the application altogether, and
-- session_user (the actual connecting DB role) is the truthful actor for that case.
--
-- occurred_at comes from the app.now GUC, never from the database clock: the application's time
-- source is the one notion of "now", so a test that moves it across a year boundary moves the
-- journal with it. The application sets app.now on every connection it takes from the pool; any
-- other writer (an admin script, a bulk loader) must set it too, and a write without it is
-- rejected rather than stamped with an invented time.
CREATE SEQUENCE invoice_audit_journal_seq;

CREATE TABLE invoice_audit_journal (
    seq             BIGINT PRIMARY KEY DEFAULT NEXTVAL('invoice_audit_journal_seq'),
    -- Deliberately NOT a FK/CASCADE — see the section comment above.
    invoice_id      BIGINT       NOT NULL,
    invoice_number  VARCHAR(255),                 -- NULL while the invoice is still an unnumbered draft
    actor           VARCHAR(128) NOT NULL,        -- OIDC sub, or session_user for a non-app write
    actor_name      VARCHAR(128),                 -- OIDC preferred_username/email at write time
    occurred_at     TIMESTAMPTZ  NOT NULL,
    event           SMALLINT     NOT NULL,        -- InvoiceAuditEvent ordinal, see column comment below
    -- II4-A — InvoiceAuditAction ordinal, see column comment below. 0 (UNSPECIFIED) is the
    -- trigger's own default when app.audit_action was never set (a write outside the application) —
    -- no column DEFAULT here on purpose: every writer (the trigger, every test fixture) must be
    -- explicit, never rely on an implicit fallback for a NOT NULL, tamper-evident column.
    action          SMALLINT     NOT NULL,
    -- II4-A — txid_current() of the writing transaction: every row of one business action
    -- (e.g. addItem's header + item rows) shares the same value, see column comment below.
    business_tx     BIGINT       NOT NULL,
    detail          JSONB        NOT NULL,        -- changed columns old/new (UPDATE) or a compact row snapshot (INSERT/DELETE)
    document_sha256 VARCHAR(64),                  -- only set for INVOICE_DOCUMENT_* events
    prev_hash       VARCHAR(64)  NOT NULL,
    entry_hash      VARCHAR(64)  NOT NULL,        -- sha256 hex of prev_hash + seq + ... + detail + action + business_tx, see capture_invoice_audit_event()
    CONSTRAINT chk_invoice_audit_journal_invoice_id_positive CHECK (invoice_id > 0),
    -- 0=INVOICE_CREATED,1=INVOICE_UPDATED,2=INVOICE_DELETED,
    -- 3=INVOICE_ITEM_CREATED,4=INVOICE_ITEM_UPDATED,5=INVOICE_ITEM_DELETED,
    -- 6=INVOICE_DOCUMENT_CREATED,7=INVOICE_DOCUMENT_UPDATED,8=INVOICE_DOCUMENT_DELETED,
    -- 9=TRIP_EXPENSE_CREATED,10=TRIP_EXPENSE_UPDATED,11=TRIP_EXPENSE_DELETED (RK-1)
    -- 12=INVOICE_RECEIPT_ASSIGNED,13=INVOICE_RECEIPT_UNASSIGNED,
    -- 14=ITEM_RECEIPT_ASSIGNED,15=ITEM_RECEIPT_UNASSIGNED (INV-C)
    -- Mirrored in de.littletrotter.domain.compliance.InvoiceAuditEvent (OrdinalEnumConverter
    -- convention) — keep both in sync, same discipline as every other ordinal CHECK in this file
    -- (see the file-level comment on enum-CHECK styles).
    CONSTRAINT chk_invoice_audit_journal_event CHECK (event >= 0 AND event <= 15),
    -- II4-A — 0=UNSPECIFIED,1=INVOICE_CREATED,2=HEADER_CHANGED,3=RECIPIENT_CHANGED,4=ITEM_ADDED,
    -- 5=ITEM_CHANGED,6=ITEM_DELETED,7=RECALCULATED,8=STATUS_CHANGED,9=SEND_REQUESTED,
    -- 10=DOCUMENT_GENERATED,11=VALIDATION_RECORDED,12=STORNO,13=RECEIPT_ASSIGNED,
    -- 14=RECEIPT_DETACHED,15=TRIP_SAVED,16=REVERSE_CHARGE_DECIDED,17=INVOICE_DELETED,
    -- 18=DOCUMENT_REGENERATED,19=DOCUMENT_RESTORED,20=DOCUMENT_REPRODUCED,21=DOCUMENT_REPRODUCTION_SEND_BLOCKED,
    -- 22=CREATED_FROM_QUOTE.
    -- Mirrored in de.littletrotter.domain.compliance.InvoiceAuditAction — keep both in sync, same
    -- discipline as chk_invoice_audit_journal_event above.
    CONSTRAINT chk_invoice_audit_journal_action CHECK (action >= 0 AND action <= 22)
);

COMMENT ON TABLE invoice_audit_journal IS 'RS-8/J1-J3/II4-A (konzept-aenderungsjournal.md) — append-only, hash-chained record of every INSERT/UPDATE/DELETE against invoice, invoice_item, invoice_document and trip_expense, plus explicit assignment/unassignment events for invoice_receipt and invoice_item_receipt (INV-C), written exclusively by capture_invoice_audit_event() below (never by application code). UPDATE/DELETE rejected by trg_invoice_audit_journal_append_only (reject_append_only_mutation(), section 7/RS-11); UPDATE/DELETE/TRUNCATE additionally revoked from lt_app (section 13/RS-7). Retention unbegrenzt (J3, PO 2026-09-03) — no purge job exists or is planned.';
COMMENT ON COLUMN invoice_audit_journal.invoice_id IS 'RS-8 — deliberately no FK/CASCADE: the journal must outlive the invoice it describes (e.g. a hard-deleted DRAFT still leaves its creation/edit trail behind).';
COMMENT ON COLUMN invoice_audit_journal.actor IS 'RS-8/konzept-aenderungsjournal.md — OIDC sub claim (stable, survives username/display-name changes) when the write came through the application (even a background job sets this, to ''system''); session_user (the connecting DB role) only when app.actor was never set at all, i.e. a write that reached these tables outside the application.';
COMMENT ON COLUMN invoice_audit_journal.event IS 'de.littletrotter.domain.compliance.InvoiceAuditEvent ordinal — 0..15: CREATED/UPDATED/DELETED triples for invoice, invoice_item, invoice_document and trip_expense, followed by explicit ASSIGNED/UNASSIGNED events for invoice- and item-level receipt links. See chk_invoice_audit_journal_event above for the literal mapping.';
COMMENT ON COLUMN invoice_audit_journal.action IS 'II4-A/de.littletrotter.domain.compliance.InvoiceAuditAction ordinal — 0..22: the business action the application was performing (app.audit_action GUC, outermost-wins), or 0 (UNSPECIFIED) when the write reached this table outside the application (no GUC set — same signal as actor falling back to session_user). See chk_invoice_audit_journal_action above for the literal mapping.';
COMMENT ON COLUMN invoice_audit_journal.business_tx IS 'II4-A — txid_current() of the writing transaction: every row captured within one business action (e.g. addItem''s header row plus its item row) shares this value, letting a reader group row-level events back into one auditor-facing line.';
COMMENT ON COLUMN invoice_audit_journal.detail IS 'RS-8/J3/II4-B — kept compact on purpose: UPDATE stores only columns where OLD IS DISTINCT FROM NEW as {"column": {"old":..,"new":..}} pairs, PLUS a reserved "_row" key (never itself a changed-column diff) carrying the row''s own id and, where the table has one, a human-meaningful business key (invoice_item.position_id, trip_expense.invoice_item_id) — an UPDATE diff that never touched those columns would otherwise leave a reader unable to tell WHICH row changed. INSERT/DELETE store the affected row''s own columns as a flat snapshot (id/position_id already included there, no "_row" needed). Both shapes are minus version/updated_at/updated_by (bookkeeping every write touches, no audit meaning of its own — occurred_at/actor already cover "when"/"who" at the journal-row level). Every reader of this column must treat a "_"-prefixed key as metadata, never a changed field. A further optional "_context" key carries application-supplied metadata of the write (e.g. reason, source and hashes of a document recovery), taken from the transaction-local GUC app.audit_context.';
COMMENT ON COLUMN invoice_audit_journal.prev_hash IS 'entry_hash of the row immediately before this one (ORDER BY seq), or the fixed genesis hash for the very first row ever written — same shape as number_journal.prev_hash (section 7), but ONE chain for the whole table, not one per number_range.';

CREATE INDEX idx_invoice_audit_journal_invoice      ON invoice_audit_journal (invoice_id, seq);
CREATE INDEX idx_invoice_audit_journal_occurred_at  ON invoice_audit_journal (occurred_at);
-- II4-A — B7's own paging-by-action shape (GROUP BY business_tx) and the ii4-B action-grouped
-- panel both key off business_tx, ordered by seq within it.
CREATE INDEX idx_invoice_audit_journal_business_tx  ON invoice_audit_journal (business_tx, seq);

-- The definition and its EXECUTE FUNCTION call sites below are block-excluded from CP03 — see
-- guard_invoice_freeze()'s own comment (section 4) for why (capitalisation.functions rule vs.
-- this file's snake_case identifier convention; a line-level noqa does not survive the
-- multi-line dollar-quoted plpgsql body between CREATE and the matching EXECUTE FUNCTION call).
-- noqa: disable=CP03
CREATE OR REPLACE FUNCTION capture_invoice_audit_event() RETURNS TRIGGER AS $$
DECLARE
    v_invoice_id      BIGINT;
    v_invoice_number  VARCHAR(255);
    v_event           SMALLINT;
    v_document_sha256 VARCHAR(64);
    -- Bookkeeping columns every write touches, stripped from `detail` (J3) — see the column
    -- comment on invoice_audit_journal.detail above.
    v_bookkeeping     TEXT[] := ARRAY['version', 'updated_at', 'updated_by'];
    v_row_new         JSONB;
    v_row_old         JSONB;
    v_detail          JSONB;
    v_context         JSONB;
    v_diff            JSONB;
    v_key             TEXT;
    v_actor           VARCHAR(128);
    v_actor_name      VARCHAR(128);
    v_seq             BIGINT;
    v_prev_hash       VARCHAR(64);
    v_entry_hash      VARCHAR(64);
    -- II4-A — the business action/transaction reference (see column comments above).
    v_action          SMALLINT;
    v_business_tx     BIGINT;
    v_occurred_at     TIMESTAMPTZ;
BEGIN
    -- Resolve invoice_id / invoice_number / event code / document_sha256 per source table. The
    -- invoice_number lookup for invoice_item/invoice_document is best-effort: on a cascaded
    -- delete of the invoice itself, whether the invoice row is already gone by the time this
    -- fires depends on trigger firing order between the cascade and this trigger — invoice_number
    -- is nullable precisely so that ordering can never make this INSERT fail.
    IF TG_TABLE_NAME = 'invoice' THEN
        v_invoice_id := COALESCE(NEW.id, OLD.id);
        v_invoice_number := NULLIF(COALESCE(NEW.invoice_number, OLD.invoice_number), '');
        v_event := CASE TG_OP WHEN 'INSERT' THEN 0 WHEN 'UPDATE' THEN 1 ELSE 2 END;
    ELSIF TG_TABLE_NAME = 'invoice_item' THEN
        v_invoice_id := COALESCE(NEW.invoice_id, OLD.invoice_id);
        SELECT NULLIF(invoice_number, '') INTO v_invoice_number FROM invoice WHERE id = v_invoice_id;
        v_event := CASE TG_OP WHEN 'INSERT' THEN 3 WHEN 'UPDATE' THEN 4 ELSE 5 END;
    ELSIF TG_TABLE_NAME = 'invoice_document' THEN
        v_invoice_id := COALESCE(NEW.invoice_id, OLD.invoice_id);
        SELECT NULLIF(invoice_number, '') INTO v_invoice_number FROM invoice WHERE id = v_invoice_id;
        v_event := CASE TG_OP WHEN 'INSERT' THEN 6 WHEN 'UPDATE' THEN 7 ELSE 8 END;
        v_document_sha256 := COALESCE(NEW.sha256_checksum, OLD.sha256_checksum);
    ELSIF TG_TABLE_NAME = 'trip_expense' THEN
        -- RK-1 — trip_expense.invoice_id is its own column (trigger-populated at INSERT, see
        -- trg_trip_expense_before_write above), read directly like invoice_item.invoice_id
        -- rather than joined through invoice_item: that join would fail to resolve anything once
        -- invoice_item's own row is already gone, which it is by the time a native `ON DELETE
        -- CASCADE` reaches this table (see trip_expense.invoice_id's own column comment).
        v_invoice_id := COALESCE(NEW.invoice_id, OLD.invoice_id);
        SELECT NULLIF(invoice_number, '') INTO v_invoice_number FROM invoice WHERE id = v_invoice_id;
        v_event := CASE TG_OP WHEN 'INSERT' THEN 9 WHEN 'UPDATE' THEN 10 ELSE 11 END;
    ELSIF TG_TABLE_NAME = 'invoice_receipt' THEN
        v_invoice_id := COALESCE(NEW.invoice_id, OLD.invoice_id);
        SELECT NULLIF(invoice_number, '') INTO v_invoice_number FROM invoice WHERE id = v_invoice_id;
        v_event := CASE TG_OP WHEN 'INSERT' THEN 12 ELSE 13 END;
    ELSIF TG_TABLE_NAME = 'invoice_item_receipt' THEN
        SELECT invoice_id INTO v_invoice_id FROM invoice_item
            WHERE id = COALESCE(NEW.invoice_item_id, OLD.invoice_item_id);
        SELECT NULLIF(invoice_number, '') INTO v_invoice_number FROM invoice WHERE id = v_invoice_id;
        v_event := CASE TG_OP WHEN 'INSERT' THEN 14 ELSE 15 END;
    ELSE
        RAISE EXCEPTION 'capture_invoice_audit_event() attached to unexpected table %', TG_TABLE_NAME;
    END IF;

    IF TG_OP = 'UPDATE' THEN
        v_row_new := to_jsonb(NEW) - v_bookkeeping;
        v_row_old := to_jsonb(OLD) - v_bookkeeping;
        v_diff := '{}'::jsonb;
        FOR v_key IN SELECT jsonb_object_keys(v_row_new) LOOP
            IF v_row_new -> v_key IS DISTINCT FROM v_row_old -> v_key THEN
                v_diff := v_diff || jsonb_build_object(v_key, jsonb_build_object('old', v_row_old -> v_key, 'new', v_row_new -> v_key));
            END IF;
        END LOOP;
        -- II4-B — row identity: an UPDATE diff only ever lists the CHANGED columns, so a row whose
        -- own identifying columns did not change (the common case — editing a position's price
        -- never touches its position_id) leaves a reader with no way to tell WHICH row changed.
        -- `_row` carries that identity unconditionally, `_`-prefixed so every `detail` consumer
        -- (JournalDetailRenderer and its callers in ui.view.journal) treats it as metadata, never a
        -- changed field. Plus one human-meaningful business key where the table has one worth
        -- naming (invoice_item's own position number; trip_expense's owning item).
        IF TG_TABLE_NAME = 'invoice_item' THEN
            v_diff := v_diff || jsonb_build_object('_row', jsonb_build_object('id', NEW.id, 'position_id', NEW.position_id));
        ELSIF TG_TABLE_NAME = 'trip_expense' THEN
            v_diff := v_diff || jsonb_build_object('_row', jsonb_build_object('id', NEW.id, 'invoice_item_id', NEW.invoice_item_id));
        ELSE
            v_diff := v_diff || jsonb_build_object('_row', jsonb_build_object('id', NEW.id));
        END IF;
        v_detail := v_diff;
    ELSIF TG_OP = 'INSERT' THEN
        v_detail := to_jsonb(NEW) - v_bookkeeping;
    ELSE
        v_detail := to_jsonb(OLD) - v_bookkeeping;
    END IF;

    -- Optional application-supplied metadata (reason/source/hashes of a document recovery); set
    -- before the entry hash below is computed so it is part of the chain.
    v_context := NULLIF(current_setting('app.audit_context', true), '')::JSONB;
    IF v_context IS NOT NULL THEN
        v_detail := v_detail || jsonb_build_object('_context', v_context);
    END IF;

    -- See the section comment above for why the fallback chain is 'system' (an app-managed
    -- transaction that deliberately left the GUC at its default) then session_user (a write that
    -- never went through the application's GUC-setting interceptor at all).
    v_actor := COALESCE(NULLIF(current_setting('app.actor', true), ''), session_user);
    v_actor_name := NULLIF(current_setting('app.actor_name', true), '');
    -- II4-A — app.audit_action is only ever set by AuditGucInterceptor (outermost-wins, see that
    -- class's own KDoc); a write with no GUC set (outside the application, or a plain-SQL write by
    -- the connecting DB role) journals UNSPECIFIED (0), the same "outside the application" signal
    -- actor's own session_user fallback gives.
    v_action := COALESCE(NULLIF(current_setting('app.audit_action', true), ''), '0')::SMALLINT;
    v_business_tx := txid_current();
    v_occurred_at := NULLIF(current_setting('app.now', true), '')::TIMESTAMPTZ;
    IF v_occurred_at IS NULL THEN
        RAISE EXCEPTION 'app.now is not set: a write to % must carry the application time', TG_TABLE_NAME
            USING ERRCODE = 'not_null_violation',
                  HINT = 'SELECT set_config(''app.now'', <timestamptz>, true) at the start of the transaction';
    END IF;

    -- ONE chain for the whole table (see section comment) — serialize appends the same way
    -- PostgresNumberJournalRepository.lockChain serializes each number_range's own chain. Held
    -- for the rest of the caller's transaction (pg_advisory_XACT_lock), so a second capture
    -- within the same transaction (e.g. one invoice UPDATE plus several invoice_item UPDATEs)
    -- re-acquires the same lock instantly instead of contending with itself.
    -- ST-A2 — the lock-first ordering rule (arc42 08-crosscutting, Persistence/Locking), the same
    -- ordering rule generation_job's stale-sweep already uses (Fix U,
    -- PanacheGenerationJobRepository.resetStaleDelegated): AuditGucInterceptor already acquires
    -- this same lock, right after setting the audit GUCs, before its intercepted method body
    -- touches any row, so 1308 is always the FIRST lock a journaled transaction takes and can
    -- never sit in a cycle with a row lock taken afterwards. The PERFORM below is the safety net
    -- for a write that bypasses that interceptor (a direct SQL write, a future caller that
    -- forgets @SetsAuditGuc) — re-acquiring a lock the transaction already holds is free, so a
    -- normal journaled call pays nothing extra here.
    PERFORM pg_advisory_xact_lock(1308); -- 1308 = RS-8, dedicated namespace, mirrors number_journal's own 1304 (task13-N4)

    v_seq := NEXTVAL('invoice_audit_journal_seq');
    SELECT entry_hash INTO v_prev_hash FROM invoice_audit_journal ORDER BY seq DESC LIMIT 1;
    IF v_prev_hash IS NULL THEN
        v_prev_hash := ENCODE(DIGEST('invoice_audit_journal-genesis', 'sha256'), 'hex');
    END IF;

    -- CHR(1) (SOH), not CHR(0): PostgreSQL's text type flatly rejects a NUL byte in any
    -- expression, including a transient one only ever fed to DIGEST() — CHR(0) itself raises
    -- "ERROR: null character not permitted" the moment it is evaluated, before COALESCE even
    -- runs. Found via a live-Postgres test run (2026-09-04): this trigger errored on every
    -- INSERT/UPDATE with a NULL invoice_number/actor_name/document_sha256 — i.e. on every
    -- unnumbered draft, background-job write and non-document-journal event, which is most of
    -- them. CHR(1) is a legal, still-non-printable, still-unambiguous NULL-value sentinel.
    -- II4-A — action/business_tx appended after document_sha256, same CHR(31) separator: an
    -- action label an auditor relies on must be as tamper-evident as every other field.
    v_entry_hash := ENCODE(
        DIGEST(
            v_prev_hash || CHR(31) || v_seq::TEXT || CHR(31) || v_invoice_id::TEXT || CHR(31) ||
            COALESCE(v_invoice_number, CHR(1)) || CHR(31) || v_actor || CHR(31) ||
            COALESCE(v_actor_name, CHR(1)) || CHR(31) || v_event::TEXT || CHR(31) ||
            v_detail::TEXT || CHR(31) || COALESCE(v_document_sha256, CHR(1)) || CHR(31) ||
            v_action::TEXT || CHR(31) || v_business_tx::TEXT,
            'sha256'
        ),
        'hex'
    );

    INSERT INTO invoice_audit_journal
        (seq, invoice_id, invoice_number, actor, actor_name, occurred_at, event, action, business_tx, detail, document_sha256, prev_hash, entry_hash)
    VALUES
        (v_seq, v_invoice_id, v_invoice_number, v_actor, v_actor_name, v_occurred_at, v_event, v_action, v_business_tx, v_detail, v_document_sha256, v_prev_hash, v_entry_hash);

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_invoice_audit_capture
    AFTER INSERT OR UPDATE OR DELETE ON invoice
    FOR EACH ROW
    EXECUTE FUNCTION capture_invoice_audit_event();

CREATE TRIGGER trg_invoice_item_audit_capture
    AFTER INSERT OR UPDATE OR DELETE ON invoice_item
    FOR EACH ROW
    EXECUTE FUNCTION capture_invoice_audit_event();

CREATE TRIGGER trg_invoice_document_audit_capture
    AFTER INSERT OR UPDATE OR DELETE ON invoice_document
    FOR EACH ROW
    EXECUTE FUNCTION capture_invoice_audit_event();

-- RK-1 — trip_expense joins the same journal, one release after invoice/invoice_item/
-- invoice_document (section 12's own comment/InvoiceAuditEvent's KDoc).
CREATE TRIGGER trg_trip_expense_audit_capture
    AFTER INSERT OR UPDATE OR DELETE ON trip_expense
    FOR EACH ROW
    EXECUTE FUNCTION capture_invoice_audit_event();

-- INV-C — assignment changes happen in join tables and therefore do not dirty the invoice row.
-- Capture them explicitly so assigning or resolving a receipt is visible as a meaningful journal
-- event rather than disappearing behind a generic relation-table mutation.
CREATE TRIGGER trg_invoice_receipt_audit_capture
    AFTER INSERT OR DELETE ON invoice_receipt
    FOR EACH ROW
    EXECUTE FUNCTION capture_invoice_audit_event();

CREATE TRIGGER trg_invoice_item_receipt_audit_capture
    AFTER INSERT OR DELETE ON invoice_item_receipt
    FOR EACH ROW
    EXECUTE FUNCTION capture_invoice_audit_event();

-- Same guard number_journal uses (section 7, RS-11) — reject_append_only_mutation() is written
-- generically for exactly this reuse.
CREATE TRIGGER trg_invoice_audit_journal_append_only
    BEFORE UPDATE OR DELETE ON invoice_audit_journal
    FOR EACH ROW
    EXECUTE FUNCTION reject_append_only_mutation();
-- noqa: enable=CP03

-- =============================================================================
-- 13. RS-7 role separation: lt_migrator (DDL/owner) vs. lt_app (runtime DML)
-- =============================================================================
--
-- Every table/sequence/view above is created by lt_migrator (Flyway's own connection role
-- — see quarkus.flyway.username and docker/postgres/init-roles.sh) and therefore owned by
-- it. lt_app is the role every application/job-worker/datagen connection uses instead of
-- the bootstrap superuser; it gets ordinary DML rights on today's objects and — via ALTER
-- DEFAULT PRIVILEGES — the same rights on whatever a later lt_migrator-run migration
-- creates, without needing another baseline edit.
--
-- number_journal and invoice_audit_journal (RS-8, section 12) are both append-only,
-- hash-chained: UPDATE/DELETE/TRUNCATE are revoked from lt_app on top of their own
-- trg_..._append_only trigger (RS-11, section 7 — reject_append_only_mutation() RAISEs on
-- UPDATE/DELETE). The trigger alone only stops the statement from having an effect once it
-- reaches the table; the revoke additionally stops lt_app from ever being ABLE to issue a
-- statement that could reach it at all (e.g. were the trigger ever dropped by mistake) — the
-- grant is what actually makes the protection binding once the app no longer connects as
-- superuser, per M2 in beratung-manipulationsschutz.md.
--
-- Conditional on lt_app/lt_migrator existing so a plain Flyway run against a database that
-- never ran docker/postgres/init-roles.sh (e.g. an ad-hoc IT test database using its own
-- bootstrap-only Testcontainers user) keeps migrating cleanly — same idempotent-safety-net
-- pattern as the authentik block above.

DO $$
BEGIN
  IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'lt_app') THEN
    EXECUTE 'GRANT USAGE ON SCHEMA public TO lt_app';
    EXECUTE 'GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO lt_app';
    EXECUTE 'GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO lt_app';
    EXECUTE 'REVOKE UPDATE, DELETE, TRUNCATE ON number_journal FROM lt_app';
    EXECUTE 'REVOKE UPDATE, DELETE, TRUNCATE ON invoice_audit_journal FROM lt_app';

    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'lt_migrator') THEN
      EXECUTE 'ALTER DEFAULT PRIVILEGES FOR ROLE lt_migrator IN SCHEMA public '
        || 'GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO lt_app';
      EXECUTE 'ALTER DEFAULT PRIVILEGES FOR ROLE lt_migrator IN SCHEMA public '
        || 'GRANT USAGE, SELECT ON SEQUENCES TO lt_app';
    END IF;
  ELSE
    RAISE NOTICE 'lt_app missing; skipping RS-7 role-separation grants for this startup';
  END IF;
END
$$;
