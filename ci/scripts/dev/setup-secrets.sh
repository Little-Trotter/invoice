#!/usr/bin/env bash
#
# Project: Little Trotter X-Invoice
# Copyright (c) 2026 Eifel42 Stefan Zils
# See LICENSE and README.md for license terms and documentation.
#
# Disclaimer: This software is provided as-is, without warranty of any kind. Use at your own risk.

# setup-secrets.sh - Generate a .env file with cryptographically strong credentials.
#
# Usage (`make dev` runs the first two itself: generate when .env is missing, then check):
#   ./ci/scripts/dev/setup-secrets.sh          # generate .env (skips if already exists)
#   ./ci/scripts/dev/setup-secrets.sh --check  # verify all required variables are set
#   ./ci/scripts/dev/setup-secrets.sh --force  # overwrite existing .env
#   ./ci/scripts/dev/setup-secrets.sh --repair # add missing generatable keys to an existing .env
#
# Requirements: openssl (standard on macOS and Linux)

set -euo pipefail

# Three levels up, not two: this script lives at ci/scripts/dev/. The repo split of
# 05.09.2026 moved it from scripts/dev/ (two levels) to here without adjusting the
# path, so REPO_ROOT resolved to ci/ — `make dev` would have written .env and
# archive-signing-public-key.pem inside ci/ instead of the repository root, which is
# where docker-compose.yml reads them from.
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
ENV_FILE="${REPO_ROOT}/.env"

# ── Helpers ───────────────────────────────────────────────────────────────────

green()  { printf '\033[0;32m%s\033[0m\n' "$*"; }
yellow() { printf '\033[0;33m%s\033[0m\n' "$*"; }
red()    { printf '\033[0;31m%s\033[0m\n' "$*"; }
bold()   { printf '\033[1m%s\033[0m\n' "$*"; }

# The Ed25519 keypair (RS-9) needs an OpenSSL that actually implements the algorithm. macOS ships
# LibreSSL as /usr/bin/openssl, which does not: `genpkey -algorithm ED25519` fails there with
# "Algorithm ED25519 not found" — found live on a macOS host whose .env consequently carried no
# archive signing key at all, which is what makes every `docker compose` call warn about the two
# unset variables. Resolve a capable binary once here (PATH first, then the usual Homebrew
# location) and fail with an actionable message rather than writing blank keys.
OPENSSL_BIN=""
resolve_openssl() {
    [[ -n "${OPENSSL_BIN}" ]] && return 0
    local candidate
    for candidate in "${OPENSSL:-}" openssl /opt/homebrew/opt/openssl@3/bin/openssl \
                     /usr/local/opt/openssl@3/bin/openssl; do
        [[ -z "${candidate}" ]] && continue
        command -v "${candidate}" >/dev/null 2>&1 || continue
        if "${candidate}" genpkey -algorithm ED25519 -out /dev/null >/dev/null 2>&1; then
            OPENSSL_BIN="${candidate}"
            return 0
        fi
    done
    red "ERROR: no OpenSSL with Ed25519 support found (RS-9 archive signing key)."
    red "  macOS ships LibreSSL as /usr/bin/openssl, which cannot generate Ed25519 keys."
    red "  Install one and re-run:  brew install openssl@3"
    red "  Or point this script at an existing build:  OPENSSL=/path/to/openssl $0 ${1:-}"
    return 1
}

# Generate a random hex string of the given byte length (default 32 → 64 hex chars).
# Hex encoding is used for simplicity and broad compatibility – no encoding concerns.
gen_secret() {
    local bytes="${1:-32}"
    openssl rand -hex "$bytes"
}

# ── Check mode ────────────────────────────────────────────────────────────────

if [[ "${1:-}" == "--check" ]]; then
    bold "Checking required secrets in ${ENV_FILE} ..."
    if [[ ! -f "${ENV_FILE}" ]]; then
        red "ERROR: .env not found. Run 'make dev' or '$0' to generate it."
        exit 1
    fi

    set -a
    # shellcheck disable=SC1090  # runtime path, deliberately not resolvable at lint time
    source "${ENV_FILE}"
    set +a

    REQUIRED_VARS=(
        POSTGRES_USER
        POSTGRES_DB
        POSTGRES_PASSWORD
        LT_MIGRATOR_DB_PASSWORD
        LT_APP_DB_PASSWORD
        RUSTFS_ACCESS_KEY
        RUSTFS_SECRET_KEY
        INVOICE_DOCUMENT_BUCKET
        POSTGRES_PORT
        VALKEY_PASSWORD
        GRAFANA_ADMIN_PASSWORD
        # RS-9 — the xpdf worker's archive-package signing keypair (kotlin-xpdf's
        # ArchiveSigningKeyProvider, ARC-3 §2 fail-fast in %prod). Two base64 DER values, not one
        # blob: PKCS8 (private) and X.509 SubjectPublicKeyInfo (public), the exact two encodings
        # java.security.KeyFactory expects — see that class's own KDoc for why they travel
        # independently rather than one being derived from the other at runtime.
        ARCHIVE_ED25519_PRIVATE_KEY
        ARCHIVE_ED25519_PUBLIC_KEY
    )
    # Password/secret variables that must not contain known weak values.
    SECRET_VARS=(
        POSTGRES_PASSWORD
        LT_MIGRATOR_DB_PASSWORD
        LT_APP_DB_PASSWORD
        RUSTFS_SECRET_KEY
        VALKEY_PASSWORD
        GRAFANA_ADMIN_PASSWORD
        ARCHIVE_ED25519_PRIVATE_KEY
    )
    # Optional — only used if you opt into the Authentik overlay (docker/compose/auth.yml,
    # ). Checked for weak values when present, but not required.
    OPTIONAL_SECRET_VARS=(
        AUTHENTIK_SECRET_KEY
        AUTHENTIK_DB_PASSWORD
        AUTHENTIK_BOOTSTRAP_PASSWORD
        AUTHENTIK_OIDC_CLIENT_SECRET
    )
    MISSING=()
    WEAK=()
    for var in "${REQUIRED_VARS[@]}"; do
        val="${!var:-}"
        if [[ -z "$val" ]]; then
            MISSING+=("$var")
        fi
    done
    for var in "${SECRET_VARS[@]}"; do
        val="${!var:-}"
        if [[ "$val" == "CHANGE_ME" || "$val" == "admin" || "$val" == "rechnung" || "$val" == "rustfsadmin" ]]; then
            WEAK+=("$var")
        fi
    done
    for var in "${OPTIONAL_SECRET_VARS[@]}"; do
        val="${!var:-}"
        if [[ -n "$val" && "$val" == "CHANGE_ME" ]]; then
            WEAK+=("$var")
        fi
    done

    if [[ ${#MISSING[@]} -gt 0 ]]; then
        red "Missing required variables:"
        for v in "${MISSING[@]}"; do red "  - $v"; done
        # The archive keypair is the one gap an existing .env can close without rewriting every
        # other credential — point at that instead of at --force.
        for v in "${MISSING[@]}"; do
            if [[ "$v" == ARCHIVE_ED25519_* ]]; then
                yellow "  Add the missing archive signing key without touching anything else: $0 --repair"
                break
            fi
        done
    fi
    if [[ ${#WEAK[@]} -gt 0 ]]; then
        yellow "Insecure placeholder values detected:"
        for v in "${WEAK[@]}"; do yellow "  - $v"; done
    fi

    # LT_BACKUP_AGE_RECIPIENT (ADM-6): warn-only, never auto-generated here or
    # anywhere else — the matching private key must never exist on this host.
    # It is generated once by an admin on their own machine (age-keygen) and
    # its public key pasted into .env by hand.
    if [[ -z "${LT_BACKUP_AGE_RECIPIENT:-}" ]]; then
        yellow "LT_BACKUP_AGE_RECIPIENT is not set — admin-tools backup CMD=run will refuse to run"
        yellow "  until it is. Generate a keypair on your ADMIN machine (never on this server):"
        yellow "    age-keygen -o little-trotter-backup-key.txt"
        yellow "  then put the printed 'Public key: age1...' value into .env as LT_BACKUP_AGE_RECIPIENT."
        yellow "  This script will not generate it for you — the private key must never touch this host."
    fi

    if [[ ${#MISSING[@]} -eq 0 && ${#WEAK[@]} -eq 0 ]]; then
        green "All required secrets are present and non-default. ✓"
        exit 0
    fi
    exit 1
fi

# ── Repair mode ───────────────────────────────────────────────────────────────
#
# An .env written before a later release does not carry the variables that release added — the
# RS-9 archive signing keypair is the case that actually happens: every `docker compose` call
# then warns "variable is not set. Defaulting to a blank string", the xpdf worker starts without
# a signing key, and `make dev` produces the synthetic world whose archive packages
# cannot be signed.
#
# --force is not the answer for that: it rewrites every credential, including the database
# passwords a running volume was initialised with. This mode adds ONLY what is missing and never
# touches a value that is already there.
#
# Deliberately narrow: it generates the archive keypair (freely regenerable — already-issued
# packages keep verifying against their own manifest's keyFingerprint) and derives the public
# half from an existing private one when only the public half is absent. Every other missing
# required variable is reported, not invented — silently minting e.g. a new POSTGRES_PASSWORD
# for an already-initialised volume would lock the stack out of its own database.

if [[ "${1:-}" == "--repair" ]]; then
    if [[ ! -f "${ENV_FILE}" ]]; then
        red "ERROR: .env not found. Run 'make dev' or '$0' to generate it."
        exit 1
    fi

    set -a
    # shellcheck disable=SC1090  # runtime path, deliberately not resolvable at lint time
    source "${ENV_FILE}"
    set +a

    ADDED=()
    ARCHIVE_ED25519_PUBLIC_KEY_PEM=""

    if [[ -z "${ARCHIVE_ED25519_PRIVATE_KEY:-}" || "${ARCHIVE_ED25519_PRIVATE_KEY}" == "CHANGE_ME" ]]; then
        resolve_openssl --repair
        REPAIR_KEY_TMP="$(mktemp)"
        trap 'rm -f "${REPAIR_KEY_TMP}"' EXIT
        "${OPENSSL_BIN}" genpkey -algorithm ED25519 -out "${REPAIR_KEY_TMP}"
        ARCHIVE_ED25519_PRIVATE_KEY="$("${OPENSSL_BIN}" pkey -in "${REPAIR_KEY_TMP}" -outform DER | base64 | tr -d '\n')"
        ARCHIVE_ED25519_PUBLIC_KEY="$("${OPENSSL_BIN}" pkey -in "${REPAIR_KEY_TMP}" -pubout -outform DER | base64 | tr -d '\n')"
        ARCHIVE_ED25519_PUBLIC_KEY_PEM="$("${OPENSSL_BIN}" pkey -in "${REPAIR_KEY_TMP}" -pubout)"
        rm -f "${REPAIR_KEY_TMP}"
        trap - EXIT
        ADDED+=(ARCHIVE_ED25519_PRIVATE_KEY ARCHIVE_ED25519_PUBLIC_KEY)
    elif [[ -z "${ARCHIVE_ED25519_PUBLIC_KEY:-}" || "${ARCHIVE_ED25519_PUBLIC_KEY}" == "CHANGE_ME" ]]; then
        # Half a pair: the public half is derivable, so derive it rather than replacing the
        # private one — a package already signed with it must keep verifying.
        resolve_openssl --repair
        REPAIR_KEY_TMP="$(mktemp)"
        trap 'rm -f "${REPAIR_KEY_TMP}"' EXIT
        printf '%s' "${ARCHIVE_ED25519_PRIVATE_KEY}" | base64 -d > "${REPAIR_KEY_TMP}" 2>/dev/null || {
            red "ERROR: ARCHIVE_ED25519_PRIVATE_KEY is not valid base64 — regenerate the pair with '$0 --force'."
            exit 1
        }
        ARCHIVE_ED25519_PUBLIC_KEY="$("${OPENSSL_BIN}" pkey -inform DER -in "${REPAIR_KEY_TMP}" -pubout -outform DER | base64 | tr -d '\n')"
        ARCHIVE_ED25519_PUBLIC_KEY_PEM="$("${OPENSSL_BIN}" pkey -inform DER -in "${REPAIR_KEY_TMP}" -pubout)"
        rm -f "${REPAIR_KEY_TMP}"
        trap - EXIT
        ADDED+=(ARCHIVE_ED25519_PUBLIC_KEY)
    fi

    if [[ ${#ADDED[@]} -gt 0 ]]; then
        {
            printf '\n'
            printf '# ── RS-9: Archive-package signing key (added by setup-secrets.sh --repair on %s) ──\n' \
                "$(date -u '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null || date -u)"
            printf '# Ed25519 keypair, base64 DER (PKCS8 private / X.509 SubjectPublicKeyInfo public).\n'
            for var in "${ADDED[@]}"; do
                printf '%s=%s\n' "$var" "${!var}"
            done
        } >> "${ENV_FILE}"
        chmod 600 "${ENV_FILE}"

        if [[ -n "${ARCHIVE_ED25519_PUBLIC_KEY_PEM}" ]]; then
            ARCHIVE_PUBKEY_FILE="${REPO_ROOT}/archive-signing-public-key.pem"
            printf '%s\n' "${ARCHIVE_ED25519_PUBLIC_KEY_PEM}" > "${ARCHIVE_PUBKEY_FILE}"
            green "Added to .env: ${ADDED[*]} (public key also written to ${ARCHIVE_PUBKEY_FILE}) ✓"
        else
            green "Added to .env: ${ADDED[*]} ✓"
        fi
    fi

    # Anything else that is missing is reported here and left alone — see this block's own
    # comment on why they are never invented.
    STILL_MISSING=()
    for var in POSTGRES_USER POSTGRES_DB POSTGRES_PASSWORD LT_MIGRATOR_DB_PASSWORD \
               LT_APP_DB_PASSWORD RUSTFS_ACCESS_KEY RUSTFS_SECRET_KEY INVOICE_DOCUMENT_BUCKET \
               POSTGRES_PORT VALKEY_PASSWORD GRAFANA_ADMIN_PASSWORD; do
        [[ -z "${!var:-}" ]] && STILL_MISSING+=("$var")
    done
    if [[ ${#STILL_MISSING[@]} -gt 0 ]]; then
        yellow "Still missing (not auto-generated — a running stack's volumes were initialised with the old values):"
        for v in "${STILL_MISSING[@]}"; do yellow "  - $v"; done
        yellow "  Regenerate the whole file only if no stack data has to survive: $0 --force"
        exit 1
    fi

    if [[ ${#ADDED[@]} -eq 0 ]]; then
        green "Nothing to repair — every generatable secret is already present. ✓"
    fi
    exit 0
fi

# ── Generate mode ─────────────────────────────────────────────────────────────

if [[ -f "${ENV_FILE}" && "${1:-}" != "--force" ]]; then
    yellow ".env already exists. Use --force to overwrite, or --check to validate it."
    exit 0
fi

bold "Generating cryptographically strong credentials in ${ENV_FILE} ..."

POSTGRES_PASSWORD="$(gen_secret 24)"
LT_MIGRATOR_DB_PASSWORD="$(gen_secret 24)"
LT_APP_DB_PASSWORD="$(gen_secret 24)"
RUSTFS_SECRET_KEY="$(gen_secret 24)"
AUTHENTIK_SECRET_KEY="$(gen_secret 32)"
AUTHENTIK_DB_PASSWORD="$(gen_secret 24)"
AUTHENTIK_POSTGRES_DB="little-trotter-auth"
AUTHENTIK_BOOTSTRAP_PASSWORD="$(gen_secret 20)"
AUTHENTIK_BOOTSTRAP_TOKEN="$(gen_secret 32)"
AUTHENTIK_OIDC_CLIENT_SECRET="$(gen_secret 32)"
VALKEY_PASSWORD="$(gen_secret 24)"
GRAFANA_ADMIN_PASSWORD="$(gen_secret 20)"

# RS-9 — Ed25519 keypair for the xpdf worker's signed archive package. Generated together (the
# only point in this pipeline where both halves ever exist side by side) via openssl, then
# base64-encoded in the exact DER encodings java.security.KeyFactory expects (PKCS8 private,
# X.509 SubjectPublicKeyInfo public) — see kotlin-shared's Ed25519Keys/ArchiveSigningKeyProvider
# KDoc. The PEM intermediate lives only in a mode-600 temp file for the few lines below.
resolve_openssl
ARCHIVE_ED25519_KEY_TMP="$(mktemp)"
trap 'rm -f "${ARCHIVE_ED25519_KEY_TMP}"' EXIT
"${OPENSSL_BIN}" genpkey -algorithm ED25519 -out "${ARCHIVE_ED25519_KEY_TMP}"
ARCHIVE_ED25519_PRIVATE_KEY="$("${OPENSSL_BIN}" pkey -in "${ARCHIVE_ED25519_KEY_TMP}" -outform DER | base64 | tr -d '\n')"
ARCHIVE_ED25519_PUBLIC_KEY="$("${OPENSSL_BIN}" pkey -in "${ARCHIVE_ED25519_KEY_TMP}" -pubout -outform DER | base64 | tr -d '\n')"
ARCHIVE_ED25519_PUBLIC_KEY_PEM="$("${OPENSSL_BIN}" pkey -in "${ARCHIVE_ED25519_KEY_TMP}" -pubout)"
rm -f "${ARCHIVE_ED25519_KEY_TMP}"
trap - EXIT

cat > "${ENV_FILE}" <<EOF
# ─────────────────────────────────────────────────────────────────────────────
# little-trotter-invoice - Runtime Secrets
#
# Generated by ci/scripts/dev/setup-secrets.sh on $(date -u '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null || date -u)
# DO NOT commit this file – it is excluded by .gitignore.
# Regenerate at any time with: ci/scripts/dev/setup-secrets.sh --force
# ─────────────────────────────────────────────────────────────────────────────

# ── Deployment Settings (Production Only) ────────────────────────────────────
# LT_DATA_DIR only matters for production deployments on Linux (ADM-10).
# Dev environments ignore this and keep named Docker volumes.
LT_DATA_DIR=/srv/little-trotter

# ── PostgreSQL ────────────────────────────────────────────────────────────────
POSTGRES_USER=little_trotter_user
POSTGRES_PASSWORD=${POSTGRES_PASSWORD}
POSTGRES_DB=little-trotter-db
POSTGRES_PORT=5432
# RS-7 (M2) — POSTGRES_USER above is bootstrap/provisioning only; docker/postgres/
# init-roles.sh creates lt_migrator (Flyway) and lt_app (app/job-worker/datagen runtime)
# from these two on first container start.
LT_MIGRATOR_DB_PASSWORD=${LT_MIGRATOR_DB_PASSWORD}
LT_APP_DB_PASSWORD=${LT_APP_DB_PASSWORD}

# ── RustFS Object Storage (S3-compatible) ──────────────────────────────────────
RUSTFS_ACCESS_KEY=little-trotter-rustfs
RUSTFS_SECRET_KEY=${RUSTFS_SECRET_KEY}
INVOICE_DOCUMENT_BUCKET=little-trotter-documents

# ── NATS JetStream (internal Docker network) ───────────────────────────────────
NATS_URL=nats://nats:4222

# ── Valkey (Redis-protocol-compatible, invoice PDF/query cache)
VALKEY_PASSWORD=${VALKEY_PASSWORD}

# ── RS-9: Archive-package signing key (xpdf worker only) ──────────────────────
# Ed25519 keypair, base64 DER (PKCS8 private / X.509 SubjectPublicKeyInfo public). Only the
# xpdf worker ever reads the private half (ArchiveSigningKeyProvider, ARC-3 §2 fail-fast in
# %prod). The public half is also written out as archive-signing-public-key.pem next to this
# file — hand THAT (never this .env) to an auditor who needs to verify a package's signatures.
# Key rotation: generate a fresh pair and re-run with --force; already-issued packages keep
# verifying against their own manifest's keyFingerprint — archive the old public key PEM rather
# than discarding it.
ARCHIVE_ED25519_PRIVATE_KEY=${ARCHIVE_ED25519_PRIVATE_KEY}
ARCHIVE_ED25519_PUBLIC_KEY=${ARCHIVE_ED25519_PUBLIC_KEY}

# ── Optional: goauthentik (TS-2 — only needed if you opt into docker/compose/auth.yml) ─
# The app's OIDC login is wired against this instance once the overlay is applied — see
# AUTH setup configuration. No host port: reached at https://\${PUBLIC_DOMAIN}/auth/ through Caddy.
# Admin-Login after bootstrap: https://\${PUBLIC_DOMAIN}/auth/if/flow/initial-setup/ (User: akadmin, Password: s.u.)
AUTHENTIK_SECRET_KEY=${AUTHENTIK_SECRET_KEY}
AUTHENTIK_DB_PASSWORD=${AUTHENTIK_DB_PASSWORD}
AUTHENTIK_POSTGRES_DB=${AUTHENTIK_POSTGRES_DB}
AUTHENTIK_BOOTSTRAP_EMAIL=admin@little-trotter.local
AUTHENTIK_BOOTSTRAP_PASSWORD=${AUTHENTIK_BOOTSTRAP_PASSWORD}
AUTHENTIK_BOOTSTRAP_TOKEN=${AUTHENTIK_BOOTSTRAP_TOKEN}
# The app's own OIDC client registered in Authentik (docker/authentik/blueprints/
# little-trotter-invoice.yaml provisions it non-interactively with these exact values).
AUTHENTIK_OIDC_CLIENT_ID=little-trotter-invoice
AUTHENTIK_OIDC_CLIENT_SECRET=${AUTHENTIK_OIDC_CLIENT_SECRET}

# ── Authentication dev bypass (TS-2) ────────────────────────────────────────────────────────────
# Left unset on purpose — %dev/%test in application.properties already default it to false for
# \`make dev\`/tests; a real deployment sets AUTH_ENABLED=true (or applies docker/compose/deploy.yml,
# which activates the same default via QUARKUS_PROFILE=prod). Uncomment to force a value.
# AUTH_ENABLED=true

# ── Monitoring (Grafana/Prometheus, ADM-11 — admin-tools/monitoring/) ──────────
# Read by admin-tools/monitoring/docker-compose.yml (Grafana admin login); anonymous access is
# disabled, so this is the only way in besides the SSH tunnel — see admin-tools/README.md.
GRAFANA_ADMIN_PASSWORD=${GRAFANA_ADMIN_PASSWORD}

# ── Caddy TLS Reverse Proxy (TS-1) ────────────────────────────────────────────
# Certificate issuer mode: internal (Caddy's built-in CA) | acme (Let's Encrypt).
# With enforced login (auth.yml overlay) PUBLIC_DOMAIN must be a REAL hostname,
# never \`localhost\` (the container-internal alias cannot shadow localhost).
TLS_MODE=internal
PUBLIC_DOMAIN=little-trotter.local
# ACME account contact, only used with TLS_MODE=acme; may stay empty.
ACME_EMAIL=

EOF

chmod 600 "${ENV_FILE}"

# RS-9 — the exportable half (PO decision A3: "public key exportable"). Safe to keep around /
# hand to an auditor; not a secret, unlike .env itself.
ARCHIVE_PUBKEY_FILE="${REPO_ROOT}/archive-signing-public-key.pem"
printf '%s\n' "${ARCHIVE_ED25519_PUBLIC_KEY_PEM}" > "${ARCHIVE_PUBKEY_FILE}"
ARCHIVE_KEY_FINGERPRINT="$("${OPENSSL_BIN}" pkey -pubin -in "${ARCHIVE_PUBKEY_FILE}" -outform DER | "${OPENSSL_BIN}" dgst -sha256 -hex | awk '{print $NF}')"

green ".env written with strong random credentials (chmod 600). ✓"
echo ""
bold "RS-9 archive-package signing key:"
echo "  Public key written to: ${ARCHIVE_PUBKEY_FILE}"
echo "  SHA-256 fingerprint:   ${ARCHIVE_KEY_FINGERPRINT}"
echo "  This fingerprint appears in every signed archive package's manifest.json — hand this"
echo "  PEM file (never .env) to anyone who needs to verify a package independently."
echo ""
echo "Next steps:"
echo "  1. Review ${ENV_FILE} and adjust values if needed."
echo "  2. Start the stack:             make dev"
echo "  3. Verify secrets:              $0 --check"
