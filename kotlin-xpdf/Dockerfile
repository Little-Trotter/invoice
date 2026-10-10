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
COPY kotlin-facturx/pom.xml kotlin-facturx/
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
COPY kotlin-facturx/src kotlin-facturx/src
COPY kotlin-xpdf/src kotlin-xpdf/src

# BuildKit cache mount for Maven dependencies — ensures repeatable, fast builds
# (second build skips dependency re-downloads if pom.xml hasn't changed).
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn -B -DskipEmail -DskipQuote -Dmaven.test.skip=true -pl kotlin-xpdf -am package

FROM eclipse-temurin:25-jre-alpine
# H27-36 Run #2 — fixable HIGH findings in base alpine packages (openssl)
# curl for health check probes in docker-compose.yml.
RUN apk upgrade --no-cache && apk add --no-cache curl
WORKDIR /app

# task12-b2b/T9b — the veraPDF CLI (V2/VeraPdfCliValidator), OFF by default.
# 1.28.2 -> 1.30.2 (PO 11.09.2026): the Trivy image gate (make publish, publish-images.yml)
# flagged the bundled greenfield-apps jar (Jackson 2.15.0, validation-model 1.28.2, fixable
# HIGH findings); 1.30.2 is the smallest release that ships the fixes. Same installer layout,
# same IzPack pack names — the answer file below is unchanged and verified against 1.30.2. Building with
# `--build-arg INSTALL_VERAPDF=true` (see docker/compose/verapdf.yml) installs the same pinned
# veraPDF release (1.30.2) that ci/main.go's PdfaGate already provisions and exercises in CI —
# same download URL, same IzPack answer file (kotlin-xpdf/docker/verapdf-auto-install.xml, copied
# verbatim from PdfaGate). Verified in this change with a real `docker build` + `docker run` of
# this exact image (both with and without the build arg) against a real NATS instance: the
# installed CLI reports "veraPDF 1.30.2" and the new `/status/verapdf` endpoint answers
# `{"available":true,...}` when installed and `{"available":false,...}` on the default (no-arg)
# image — see docs/task12-b2b/B2B-T9-konfigurations.md for the full verification note. Not
# verified: the actual docker-compose.yml/.verapdf.yml stack end-to-end (RustFS wiring,
# the System-Status HTTP check reaching this endpoint through kotlin-invoice) — only this image
# in isolation.
#
# Without this build arg, VERAPDF_CLI/VERAPDF_VERSION below simply point at a binary that isn't
# there — BasePdfStage/VeraPdfCliValidator already handle that gracefully (ERROR, not a crash),
# exactly the "switch on, component absent" case the System-Status hint (Punkt 8) surfaces.
ARG INSTALL_VERAPDF=false
COPY kotlin-xpdf/docker/verapdf-auto-install.xml /tmp/verapdf-auto-install.xml
RUN if [ "$INSTALL_VERAPDF" = "true" ]; then \
      apk add --no-cache curl unzip && \
      curl -fsSL -o /tmp/verapdf.zip https://software.verapdf.org/releases/1.30/verapdf-greenfield-1.30.2-installer.zip && \
      unzip -q /tmp/verapdf.zip -d /tmp/verapdf-pkg && \
      java -jar /tmp/verapdf-pkg/verapdf-greenfield-1.30.2/verapdf-izpack-installer-1.30.2.jar /tmp/verapdf-auto-install.xml && \
      /opt/verapdf/verapdf --version && \
      chmod -R a+rX /opt/verapdf && \
      # unzip was install-only; curl must SURVIVE this cleanup — the runtime healthcheck
      # (docker-compose.yml, /q/health/live) depends on the curl installed further up.
      apk del unzip && \
      rm -rf /tmp/verapdf* /var/cache/apk/*; \
    else \
      rm -f /tmp/verapdf-auto-install.xml; \
    fi
ENV VERAPDF_CLI=/opt/verapdf/verapdf
ENV VERAPDF_VERSION=1.30.2

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
      rm -f /tmp/typst.tar.xz && \
      apk del xz && \
      /opt/typst/typst --version; \
    fi
# The fonts Typst may use: Liberation Sans, the face the PDF layouts are
# designed around. Copied unconditionally (a few hundred KB) — they are inert
# without the binary and keep the two build-arg variants' layouts identical.
COPY kotlin-xpdf/src/main/resources/fonts /opt/typst/fonts
ENV TYPST_CLI=/opt/typst/typst
ENV TYPST_VERSION=${TYPST_VERSION}
ENV TYPST_FONT_PATH=/opt/typst/fonts

COPY --from=build /workspace/kotlin-xpdf/target/quarkus-app/lib/ /app/lib/
COPY --from=build /workspace/kotlin-xpdf/target/quarkus-app/app/ /app/app/
COPY --from=build /workspace/kotlin-xpdf/target/quarkus-app/quarkus/ /app/quarkus/
COPY --from=build /workspace/kotlin-xpdf/target/quarkus-app/quarkus-run.jar /app/quarkus-run.jar
# BSI TR-03183-2 / SBOM spec S4 — kotlin-xpdf is its own delivered product (a separately deployed
# renderer service), not merely an input folded into kotlin-invoice's aggregate document. Its
# CycloneDX document (built by this module's own cyclonedx-maven-plugin execution) therefore ships
# with ITS OWN image, exactly one document as required by R2/R3 — not with kotlin-invoice's.
COPY --from=build /workspace/kotlin-xpdf/target/classes/bom.json /app/sbom/bom.json

# Container hardening — fixed UID/GID 10001. This image writes nothing at runtime outside of
# /tmp (already world-writable, sticky bit, in the base alpine image): EmbeddedKositValidator,
# CiiXsdValidator and VeraPdfCliValidator all extract scenario/schema files and the input PDF via
# Files.createTempDirectory/createTempFile, both defaulting to java.io.tmpdir. /app (the
# quarkus-app layout) and the optional /opt/verapdf install are read-only at runtime; chowned so
# the non-root user can still read them if the base image's default permissions ever change.
RUN addgroup -g 10001 -S littletrotter \
    && adduser -S -D -H -u 10001 -G littletrotter littletrotter \
    && chown -R littletrotter:littletrotter /app

EXPOSE 8081
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
ENV JAVA_OPTS_APPEND="-Dquarkus.http.host=0.0.0.0"

USER littletrotter:littletrotter

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS $JAVA_OPTS_APPEND -jar /app/quarkus-run.jar"]
