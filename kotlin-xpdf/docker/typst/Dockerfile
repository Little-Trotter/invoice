# The pinned Typst CLI on a minimal Alpine base — the image the integration test
# (TypstCompileIT via TypstTestResource) compiles against, so the proof never depends on what
# happens to be installed on the host. The version and checksums are the same ones the runtime
# image (kotlin-xpdf/Dockerfile, INSTALL_TYPST=true) installs; TypstToolchainPinTest keeps the
# two files and TypstCompiler.DEFAULT_VERSION in lockstep.
#
# Build context: this directory plus a `fonts/` entry the test supplies from
# kotlin-xpdf/src/main/resources/fonts — the Liberation Sans files the layouts are designed around.
FROM alpine:3.22

ARG TYPST_VERSION=0.15.1
ARG TYPST_SHA256_X86_64=a6d077d0a95eed5a2eba715b2dae06be954f624ccbf85758a03f389ded33118c
ARG TYPST_SHA256_AARCH64=5aa8d74a3d906e60ea12a66ac2f37f8eef1b14cbad7182a745e393a10c23dcee

RUN apk add --no-cache curl xz && \
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
    /opt/typst/typst --version

COPY fonts /opt/typst/fonts

ENV TYPST_CLI=/opt/typst/typst
ENV TYPST_VERSION=${TYPST_VERSION}
ENV TYPST_FONT_PATH=/opt/typst/fonts
