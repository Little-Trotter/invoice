# syntax=docker/dockerfile:1.7

FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-25-alpine AS build
WORKDIR /workspace

RUN apk add --no-cache nodejs npm

COPY pom.xml ./
COPY kotlin-shared/pom.xml kotlin-shared/
COPY kotlin-architecture-rules/pom.xml kotlin-architecture-rules/
COPY kotlin-mail-contract/pom.xml kotlin-mail-contract/
COPY kotlin-document-job-contract/pom.xml kotlin-document-job-contract/
COPY kotlin-masterdata-contract/pom.xml kotlin-masterdata-contract/
COPY kotlin-quote-contract/pom.xml kotlin-quote-contract/
COPY kotlin-edge-identity/pom.xml kotlin-edge-identity/
# POM only: kotlin-invoice does not depend on kotlin-xpdf directly, but the
# parent reactor lists every module (see kotlin-e2e-tests/ci/coverage-report below) and Maven
# validates all of them exist before applying -pl/-am — a missing directory fails the whole
# reactor, not just the modules that are actually unreachable from kotlin-invoice.
COPY kotlin-quotation-xml/pom.xml kotlin-quotation-xml/
COPY kotlin-xpdf/pom.xml kotlin-xpdf/
# kotlin-invoice depends on the shared Vaadin components (rich-text editor), so its classes are built here.
COPY kotlin-ui-components/pom.xml kotlin-ui-components/
COPY kotlin-invoice/pom.xml kotlin-invoice/
# POM only: the parent reactor lists the module; its tests never run in image builds.
COPY kotlin-e2e-tests/pom.xml kotlin-e2e-tests/
# Reactor-only module: Maven parses every <module> of the parent even for `-pl <one>`,
# so the directory has to exist. Nothing is built from it here.
# Repo split 05.09.2026 — the coverage aggregator moved to ci/coverage-report/. The
# reactor still lists it, so its pom must be in the image for the dependency-warmup
# layer below to resolve the build; a stale path here fails the image build outright.
COPY ci/coverage-report/pom.xml ci/coverage-report/
# BSI TR-03183-2 / SBOM spec — kotlin-invoice's own pom.xml pulls sbom-postprocess in TWO ways: a
# test-scope dependency (SbomSchemaValidityIT, its tests are skipped here but the reactor still
# resolves the dependency) and an exec-maven-plugin PLUGIN dependency that actually runs during
# `package` — unlike ci/coverage-report (pom-only, nothing here builds against its classes), this
# one needs its compiled classes present, so its src is copied too, same as kotlin-shared/kotlin-invoice.
COPY ci/sbom-postprocess/pom.xml ci/sbom-postprocess/
COPY ci/sbom-postprocess/src ci/sbom-postprocess/src

COPY kotlin-shared/src kotlin-shared/src
COPY kotlin-architecture-rules/src kotlin-architecture-rules/src
COPY kotlin-mail-contract/src kotlin-mail-contract/src
COPY kotlin-document-job-contract/src kotlin-document-job-contract/src
COPY kotlin-masterdata-contract/src kotlin-masterdata-contract/src
COPY kotlin-quote-contract/src kotlin-quote-contract/src
COPY kotlin-ui-components/src kotlin-ui-components/src
COPY kotlin-invoice/src kotlin-invoice/src
# SBOM spec S1 — package.json is tracked in git (package-lock.json is not: Vaadin regenerates it
# during prepare-frontend). Without this COPY, Vaadin finds no existing package.json here and
# writes a fresh one containing only ITS OWN required frontend deps (vite, typescript, etc. — why
# build-frontend "worked" without this line), silently dropping every author-added devDependency,
# including cyclonedx-npm, which is what npm-sbom-generate below needs on node_modules/.bin.
COPY kotlin-invoice/package.json kotlin-invoice/
COPY kotlin-invoice/tsconfig.json kotlin-invoice/
COPY kotlin-invoice/types.d.ts kotlin-invoice/

# Skip all test compilation/execution for container image builds.
# BuildKit cache mounts for Maven and npm dependencies — ensures repeatable, fast builds
# (second build skips dependency re-downloads if pom.xml/package-lock.json haven't changed).
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    --mount=type=cache,target=/root/.npm,sharing=locked \
    mvn -B -DskipEmail -DskipQuote -Dmaven.test.skip=true -Pproduction -pl kotlin-invoice -am package

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

# OCI image labels (STAMMBAUM S4). revision/created are injected by the build step
# (--build-arg REVISION=$(git rev-parse HEAD) --build-arg CREATED=$(date -u +%Y-%m-%dT%H:%M:%SZ)); empty default = unknown.
ARG IMAGE_VERSION=dev
ARG REVISION=
ARG CREATED=
ARG IMAGE_SOURCE=https://github.com/Little-Trotter/invoice
LABEL org.opencontainers.image.title="little-trotter-invoice" \
      org.opencontainers.image.version="${IMAGE_VERSION}" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.source="${IMAGE_SOURCE}" \
      org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.created="${CREATED}" \
      org.opencontainers.image.vendor="Little Trotter" \
      org.opencontainers.image.documentation="https://github.com/Little-Trotter/invoice#readme"

# BackupService (application/system) shells out to pg_dump for the nightly database backup.
# postgresql18-client, not a lower/unversioned postgresql-client: pg_dump's major version must
# be >= the server's (docker/postgres/Dockerfile is `FROM postgres:18`), and pg_dump < 18 refuses
# to dump a newer server's custom-format output.
# curl for health check probes in docker-compose.yml.
# apk writes timestamps to /var/log/apk.log; drop it (and the cache) in the same layer so clean builds stay bit-identical (PACKSATTEL P2).
RUN apk upgrade --no-cache && apk add --no-cache postgresql18-client curl \
    && rm -rf /var/log/apk.log /var/cache/apk/*

COPY --chown=10001:10001 --from=build /workspace/kotlin-invoice/target/quarkus-app/lib/ /app/lib/
COPY --chown=10001:10001 --from=build /workspace/kotlin-invoice/target/quarkus-app/app/ /app/app/
COPY --chown=10001:10001 --from=build /workspace/kotlin-invoice/target/quarkus-app/quarkus/ /app/quarkus/
COPY --chown=10001:10001 --from=build /workspace/kotlin-invoice/target/quarkus-app/quarkus-run.jar /app/quarkus-run.jar
# BSI TR-03183-2 / SBOM spec S2 — the image carries exactly one document for this product: the
# aggregate produced by kotlin-invoice's own cyclonedx-maven-plugin execution, which already
# includes kotlin-shared (a compile-scope dependency, not a separately delivered product). It does
# NOT carry kotlin-xpdf's document — that ships with kotlin-xpdf's own image (see S4).
COPY --chown=10001:10001 --from=build /workspace/kotlin-invoice/target/classes/bom.json /app/sbom/bom.json
# Licence texts (Apache-2.0 s4(a)/(d): LICENSE + NOTICE travel with the binary). The Factur-X/KoSIT/EN16931
# artefacts and Liberation fonts live only in the kotlin-xpdf image, so their texts ship there.
COPY LICENSE NOTICE /licenses/

# Container hardening — fixed UID/GID 10001 so bind-mount/volume ownership is stable across
# rebuilds. BackupService (System > Backup tab) is the only thing this image writes at runtime
# outside of /tmp (already world-writable, sticky bit, in the base alpine image): it creates
# per-run directories under /backups (docker-compose.yml mounts backups-data there) via
# BackupPanel's "/backups/little-trotter" path. Everything under /app itself (the quarkus-app
# layout, the sbom/ files) is read-only at runtime; it is owned by 10001:10001 via COPY --chown (no separate chown layer
# that would duplicate the files).
RUN addgroup -g 10001 -S littletrotter \
    && adduser -S -D -H -u 10001 -G littletrotter littletrotter \
    && mkdir -p /backups \
    && chown littletrotter:littletrotter /backups

EXPOSE 8080
# Heap and GC come exclusively from JAVA_TOOL_OPTIONS of the compose file; the image carries no heap flags.
ENV JAVA_OPTS_APPEND="-Dquarkus.http.host=0.0.0.0"

USER littletrotter:littletrotter

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS_APPEND -jar /app/quarkus-run.jar"]
