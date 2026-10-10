-- Synthetic canonical number ranges after V9 catalogue wording migration. DatagenComplianceIT re-applies this standalone, idempotent fragment after its
-- TRUNCATE to prove the canonical number ranges survive a repeat run, without re-running the rest
-- of the seed baseline (which has INSERTs that are not all individually idempotent).
--
-- subject: INVOICE=0, CUSTOMER=1, CATALOGUE=2, SELLER=3
-- prefix_source: NONE=0, FIXED=1, SELLER_PROFILE=2, FROM_DATA=3
-- prefix_data_field: NAME_INITIALS=0, COUNTRY_CODE=1, CATALOGUE_CATEGORY=2
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
    ('CATALOGUE_DEFAULT',  'Katalog',   2,  1, 3, NULL, 2,
     0, 5, 0, 0, 1, TRUE, TRUE),
    ('SELLER_DEFAULT',   'Anbieternummer',  3,   1, 3, NULL, 0,
     0, 5, 0, 0, 1, TRUE, TRUE),
    ('INVOICE_GAPLESS',  'Rechnungsnummer', 0,  1, 2, NULL, NULL,
     3, 5, 1, 0, 0, TRUE, TRUE)
ON CONFLICT (code) DO NOTHING;
