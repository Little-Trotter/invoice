# Kotlin Invoice Module - KOSIT Integration

The KoSIT validator is embedded in the `kotlin-xpdf` service (Task M). No separate
validator container or HTTP endpoint is required anymore.

Configuration:
- The XRechnung scenario configuration is shipped as a versioned ZIP in the
  `kotlin-xpdf` classpath (`xrechnung/xrechnung-3.0.2-validator-configuration-2026-01-31.zip`)
  and extracted to a temp directory at startup.
- The validator verdict comes typed from the KoSIT API (`AcceptRecommendation`).
  An engine failure yields `ERROR`, never `VALID`.

Notes:
- The `kosit` container was removed from `docker-compose.yml`; `KOSIT_URL`/`KOSIT_IMAGE`
  are obsolete.
- The validation report is still stored on the generation job (`kosit_report`) and
  rendered in the invoice detail view.
