# syntax=docker/dockerfile:1.7

FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-25-alpine AS build
WORKDIR /workspace

COPY pom.xml ./
COPY kotlin-shared/pom.xml kotlin-shared/
COPY kotlin-architecture-rules/pom.xml kotlin-architecture-rules/
COPY kotlin-mail-contract/pom.xml kotlin-mail-contract/
COPY kotlin-document-job-contract/pom.xml kotlin-document-job-contract/
COPY kotlin-masterdata-contract/pom.xml kotlin-masterdata-contract/
COPY kotlin-quote-contract/pom.xml kotlin-quote-contract/
COPY kotlin-edge-identity/pom.xml kotlin-edge-identity/
COPY kotlin-quotation-xml/pom.xml kotlin-quotation-xml/
COPY kotlin-invoice/pom.xml kotlin-invoice/
COPY kotlin-xpdf/pom.xml kotlin-xpdf/
# Reactor module since 09/2026: the parent lists it, so its POM must exist for `-pl kotlin-xpdf -am`.
COPY kotlin-ui-components/pom.xml kotlin-ui-components/
# POM only: the parent reactor lists the module; its tests never run in image builds.
COPY kotlin-e2e-tests/pom.xml kotlin-e2e-tests/
# Reactor-only module: Maven parses every <module> of the parent even for `-pl <one>`,
# so the directory has to exist. Nothing is built from it here.
# Repo split 05.09.2026 — the coverage aggregator moved to ci/coverage-report/. The
# reactor still lists it, so its pom must be in the image for the dependency-warmup
# layer below to resolve the build; a stale path here fails the image build outright.
COPY ci/coverage-report/pom.xml ci/coverage-report/
# BSI TR-03183-2 / SBOM spec — kotlin-xpdf's own pom.xml pulls sbom-postprocess in TWO ways: a
# test-scope dependency (SbomSchemaValidityIT, tests skipped here but the reactor still resolves
# the dependency) and an exec-maven-plugin PLUGIN dependency that actually runs during `package` —
# unlike ci/coverage-report (pom-only), this one needs its compiled classes present.
COPY ci/sbom-postprocess/pom.xml ci/sbom-postprocess/
COPY ci/sbom-postprocess/src ci/sbom-postprocess/src

COPY kotlin-shared/src kotlin-shared/src
COPY kotlin-architecture-rules/src kotlin-architecture-rules/src
COPY kotlin-document-job-contract/src kotlin-document-job-contract/src
COPY kotlin-masterdata-contract/src kotlin-masterdata-contract/src
COPY kotlin-quote-contract/src kotlin-quote-contract/src
COPY kotlin-xpdf/src kotlin-xpdf/src

# BuildKit cache mount for Maven dependencies — ensures repeatable, fast builds
# (second build skips dependency re-downloads if pom.xml hasn't changed).
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn -B -DskipEmail -DskipQuote -Dmaven.test.skip=true -pl kotlin-xpdf -am package

FROM eclipse-temurin:25-jre-alpine

# OCI image labels (STAMMBAUM S4). revision/created are injected by the build step
# (--build-arg REVISION=$(git rev-parse HEAD) --build-arg CREATED=$(date -u +%Y-%m-%dT%H:%M:%SZ)); empty default = unknown.
ARG IMAGE_VERSION=dev
ARG REVISION=
ARG CREATED=
ARG IMAGE_SOURCE=https://github.com/Little-Trotter/invoice
LABEL org.opencontainers.image.title="little-trotter-xpdf" \
      org.opencontainers.image.version="${IMAGE_VERSION}" \
      org.opencontainers.image.licenses="Apache-2.0 AND EUPL-1.2 AND OFL-1.1 AND LicenseRef-FeRD-ZUGFeRD-right-of-use" \
      org.opencontainers.image.source="${IMAGE_SOURCE}" \
      org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.created="${CREATED}" \
      org.opencontainers.image.vendor="Little Trotter" \
      org.opencontainers.image.documentation="https://github.com/Little-Trotter/invoice#readme"
# H27-36 Run #2 — fixable HIGH findings in base alpine packages (openssl)
# curl for health check probes in docker-compose.yml.
# apk writes timestamps to /var/log/apk.log; drop it (and the cache) in the same layer so clean builds stay bit-identical (PACKSATTEL P2).
RUN apk upgrade --no-cache && apk add --no-cache curl \
    && rm -rf /var/log/apk.log /var/cache/apk/*
WORKDIR /app

# veraPDF is not part of this image: PDF conformance runs in its own service (see the `verapdf`
# service in docker-compose.yml) so its memory is accounted separately from the render worker's.

# Typst CLI (TypstCompiler) — Typst is this product's only layout engine.
# Pinned release, checksum-verified per architecture; version and sums are mirrored in
# kotlin-xpdf/docker/typst/Dockerfile (the integration-test image) and guarded by
# TypstToolchainPinTest against TypstCompiler.DEFAULT_VERSION. With INSTALL_TYPST=false the
# TYPST_* variables point at a binary that is not there — TypstCompiler reports that as
# "not available" (and /status/typst shows it), never as a crash.
#
# Runtime environment variables (case-insensitive, whitespace tolerated):
# - XPDF_PAYMENT_QR: `on` (default) or `off` — when enabled, embeds a GiroCode (EPC QR) in the
#   payment block for invoices with EUR currency, valid IBAN, and gross amount 0.01–999,999,999.99.
ARG INSTALL_TYPST=true
ARG TYPST_VERSION=0.15.1
ARG TYPST_SHA256_X86_64=a6d077d0a95eed5a2eba715b2dae06be954f624ccbf85758a03f389ded33118c
ARG TYPST_SHA256_AARCH64=5aa8d74a3d906e60ea12a66ac2f37f8eef1b14cbad7182a745e393a10c23dcee
RUN if [ "$INSTALL_TYPST" = "true" ]; then \
      apk add --no-cache xz && \
      arch="$(uname -m)" && \
      case "$arch" in \
        x86_64)  sum="$TYPST_SHA256_X86_64" ;; \
        aarch64) sum="$TYPST_SHA256_AARCH64" ;; \
        *) echo "unsupported architecture: $arch" >&2; exit 1 ;; \
      esac && \
      curl -fsSL -o /tmp/typst.tar.xz \
        "https://github.com/typst/typst/releases/download/v${TYPST_VERSION}/typst-${arch}-unknown-linux-musl.tar.xz" && \
      echo "${sum}  /tmp/typst.tar.xz" | sha256sum -c - && \
      mkdir -p /opt/typst && \
      tar -xJf /tmp/typst.tar.xz -C /opt/typst --strip-components=1 "typst-${arch}-unknown-linux-musl/typst" && \
      mkdir -p /licenses/typst && \
      tar -xJf /tmp/typst.tar.xz -C /licenses/typst --strip-components=1 \
        "typst-${arch}-unknown-linux-musl/LICENSE" "typst-${arch}-unknown-linux-musl/NOTICE" && \
      rm -f /tmp/typst.tar.xz && \
      apk del xz && \
      /opt/typst/typst --version; \
    fi && rm -rf /var/log/apk.log /var/cache/apk/*
# The fonts Typst may use: Liberation Sans, the face the PDF layouts are
# designed around. Copied unconditionally (a few hundred KB) — they are inert
# without the binary and keep the two build-arg variants' layouts identical.
COPY kotlin-xpdf/src/main/resources/fonts /opt/typst/fonts
# Licence texts (Apache-2.0 s4, EUPL-1.2, OFL-1.1 cl.2, FeRD right of use). /licenses/typst/{LICENSE,NOTICE}
# come from the checksum-pinned Typst tarball above (INSTALL_TYPST=true); the rest is verbatim from licenses/.
COPY LICENSE NOTICE /licenses/
COPY licenses/ /licenses/
ENV TYPST_CLI=/opt/typst/typst
ENV TYPST_VERSION=${TYPST_VERSION}
ENV TYPST_FONT_PATH=/opt/typst/fonts

COPY --chown=10001:10001 --from=build /workspace/kotlin-xpdf/target/quarkus-app/lib/ /app/lib/
COPY --chown=10001:10001 --from=build /workspace/kotlin-xpdf/target/quarkus-app/app/ /app/app/
COPY --chown=10001:10001 --from=build /workspace/kotlin-xpdf/target/quarkus-app/quarkus/ /app/quarkus/
COPY --chown=10001:10001 --from=build /workspace/kotlin-xpdf/target/quarkus-app/quarkus-run.jar /app/quarkus-run.jar
# BSI TR-03183-2 / SBOM spec S4 — kotlin-xpdf is its own delivered product (a separately deployed
# renderer service), not merely an input folded into kotlin-invoice's aggregate document. Its
# CycloneDX document (built by this module's own cyclonedx-maven-plugin execution) therefore ships
# with ITS OWN image, exactly one document as required by R2/R3 — not with kotlin-invoice's.
COPY --chown=10001:10001 --from=build /workspace/kotlin-xpdf/target/classes/bom.json /app/sbom/bom.json

# Container hardening — fixed UID/GID 10001. This image writes nothing at runtime outside of
# /tmp (already world-writable, sticky bit, in the base alpine image): EmbeddedKositValidator,
# CiiXsdValidator all extract scenario/schema files via
# Files.createTempDirectory/createTempFile, both defaulting to java.io.tmpdir. /app (the
# quarkus-app layout) is read-only at runtime; it is owned by 10001:10001 via COPY --chown (no separate chown layer
# that would duplicate the files).
RUN addgroup -g 10001 -S littletrotter \
    && adduser -S -D -H -u 10001 -G littletrotter littletrotter

EXPOSE 8081
ENV JAVA_OPTS_APPEND="-Dquarkus.http.host=0.0.0.0"

USER littletrotter:littletrotter

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS_APPEND -jar /app/quarkus-run.jar"]
