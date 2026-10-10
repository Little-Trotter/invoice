# Little Trotter X-Invoice - Architecture Documentation

> **Documentation language: US English (en-US)** — a deliberate exception to the
> repo's German docs/ convention, since these are development documents (PO
> decision, 2026-09-02).

This documentation is built with [docToolchain](https://doctoolchain.org/) using
the [arc42](https://arc42.org/) template. It is independent of the Maven build
(`mvnw`) and of the root `docker-compose.yml`.

## Quick Start

```bash
make build   # build the site into html/ (via Dagger)
make build DAGGER=container   # no local Dagger CLI needed, only Docker
make serve   # build, then serve html/ via nginx on http://localhost:9325/
make stop    # stop the web server
make clean   # remove html/ and the compose containers/images
```

`make image` / `make serve-image` build and run the published container image instead of the
bind-mounted `serve` — see "Container image" below.

`make build` runs the whole build inside Dagger (`ci/main.go`'s `DocsGate`) —
nothing needs to be installed on the host to build. The three codified PO
checks (dev-key/watermark/wasserzeichen ban, CDN ban, "Factor-X" misspelling
ban) are part of every build: a violating build fails with that check's
message and produces no site. Requires Docker; the Dagger CLI is either
installed locally, or, with `DAGGER=container`, pulled and run as a
container.

`html/` is the deliverable: the complete static site, self-contained and
relative-linked. The same directory is what nginx serves locally and what gets
published to GitHub Pages.

`make serve` builds first and then starts an nginx container that serves
`html/` read-only. `http://localhost:9325/` opens the entry page;
`http://localhost:9325/arc42-docs.html` opens the full document directly. The
host port is overridable:

```bash
make serve PORT=9326
```

The local Dagger CLI (`DAGGER=local`) must match `DAGGER_VERSION` in the Makefile; `brew
upgrade dagger` keeps it current, and `make build` warns (without failing) if the two drift
apart.

The web server is never started implicitly — `make build` builds and nothing
else.

Output:
- `html/arc42-docs.html` — the full arc42 documentation
- `html/architecture-inception-canvas.html` — one-page inception canvas
- `html/architecture-communication-canvas.html` — one-page communication canvas
- `html/index.html` — entry page

`html/` is generated and gitignored; it is not committed.

## Build and checks

`make build` (from this directory) is the only build path, in one of two
modes selected by the `DAGGER` parameter: `DAGGER=local` (the default) runs
the `dagger` binary already on the host's PATH; `DAGGER=container` builds a
small throwaway image (`containers/Dockerfile.dagger-cli`, pinned to
`DAGGER_VERSION`) and runs the Dagger CLI inside it instead, for a machine
that has Docker but not the Dagger CLI installed. Both modes call the same
underlying command:

```bash
dagger call docs-gate --source=.. export --path=../arc42-docs/html --wipe
```

(from the `ci/` directory). `ci/main.go`'s `DocsGate` function (A42-13)
builds the documentation inside Dagger from
`containers/Dockerfile.docs-builder`, then enforces the codified PO checks
(2026-09-02) against the sources and the built HTML — the
dev-key/watermark/wasserzeichen ban, the CDN ban, and the "Factor-X"
misspelling ban — before returning the built site; only a passing build
produces a `Directory` to export. `DocsGate` is not wired into the Maven
`FullPipeline` (see its doc comment); call it standalone as shown above, or
via `make build`/`make docs` (from the repo root).

## Directory Structure

```
arc42-docs/
├── Makefile                        # build / serve / stop / clean / image / serve-image / help
├── config/
│   ├── Config.groovy                # docToolchain configuration (HTML5 only)
│   └── gradle.properties            # Gradle settings for the Docker build
├── containers/
│   ├── Dockerfile.docs-builder      # docToolchain + Structurizr + PlantUML image (built by Dagger)
│   ├── Dockerfile.dagger-cli        # Dagger CLI image for DAGGER=container (built on demand by make build)
│   ├── Dockerfile.docs              # published docs image (bakes html/ in — see "Container image")
│   ├── docker-compose.docs.yml      # nginx (serve) service only — the build runs in Dagger
│   ├── nginx-docs.conf              # static read-only server config for html/ (bind-mount serving)
│   └── nginx-docs-image.conf        # same, rooted for the baked-in image (Dockerfile.docs)
├── scripts/
│   ├── build-docs.sh                 # build entrypoint (runs inside the Dagger build container)
│   └── generate-canvas-drawio.py     # regenerates the canvas .drawio files — runs on the host too, see below
├── doc/
│   ├── arc42-docs.adoc               # master document (chapter includes)
│   ├── architecture-communication-canvas.adoc
│   ├── architecture-inception-canvas.adoc
│   ├── architecture-*-canvas.drawio  # draw.io companions (A42-20), generated — see generate-canvas-drawio.py
│   ├── arc42/                        # chapters 01-12 + appendices
│   ├── c4/workspace.dsl              # Structurizr C4 model (context/container/component)
│   ├── css/                          # arc42-docs.css / arc42-canvas.css (no min/modules split)
│   ├── docinfo/docinfo.html          # shared HTML head/footer injected into every page
│   └── plantuml/c4-styling.puml.inc      # PlantUML C4 color/notation styling
└── html/                             # generated, gitignored — the built site (see Quick Start)
```

## Configuration

- **`config/Config.groovy`**: docToolchain configuration — input files (master
  document plus both canvases), HTML5-only output, image/attribute settings.
- **`config/gradle.properties`**: Gradle settings tuned for a single-shot,
  non-daemon build inside Docker.

## Adding Content

### New arc42 chapters

The chapter files under `doc/arc42/` are placeholders (`// TODO A42-<n>`
markers) until the owning work package fills them in — see
`docs/build-arc42/README.md` for the package plan. To extend a chapter,
edit the corresponding `doc/arc42/NN-*.adoc` file directly; it is already
wired into `doc/arc42-docs.adoc` via an `include::`.

### C4 diagrams

Edit `doc/c4/workspace.dsl` (Structurizr DSL). `scripts/build-docs.sh` exports
its views with the Structurizr CLI and renders them to `doc/images/` as
`structurizr-<View>.svg` (plus a `-key.svg` legend per view) before the HTML
generation runs; chapters embed them with `image::structurizr-<View>.svg[]`.

### PlantUML diagrams

Add sources under `doc/plantuml/` and embed the result with
`image::<basename>.svg[]`. The build script renders every `.puml` there with
the pinned PlantUML jar and the Graphviz built into the builder image, the same
toolchain the Structurizr exports go through, so no diagram depends on the
PlantUML bundled inside docToolchain. `c4-styling.puml.inc` documents the shared
C4 color tokens the hand-written diagrams apply.

The rendered `doc/images/` directory is a build artifact that only exists
inside the build container; nothing under `doc/images/` is checked in.

### Editing markers — what AI agents must respect

Two comment conventions protect content from being rewritten or regenerated by mistake:

- **`// PO-TEXT (YYYY-MM-DD): …`** in front of a section or paragraph marks prose the PO wrote
  or approved by hand. Agents must not rephrase, restructure, translate or "improve" it; only
  syntax, broken links and factual errors may be touched, and a factual change is announced
  to the PO first. The marker stays with the text when it moves. Example:

  ```asciidoc
  // PO-TEXT (2026-09-12): wording is the PO's, keep it verbatim.
  == Business Case
  ```

- **`GENERATED FILE - DO NOT EDIT`** at the top of a file means the file is build output
  checked in for convenience (today: the two `doc/*.drawio` canvases). Edit the source named
  in the banner and rerun the generator; a manual edit is lost with the next build. The
  canvas `.adoc` files carry the matching `SOURCE OF TRUTH` header.

Everything without a marker is normal documentation: agents may edit it, following the usual
review and commit rules.

### Canvas diagrams (draw.io)

`scripts/generate-canvas-drawio.py` regenerates the committed
`doc/architecture-*-canvas.drawio` files from the canvas `.adoc` sources
(the `.adoc` is authoritative). The Dagger build runs it automatically, but
only inside the build container — it never writes back into the checked-out
sources. After editing a canvas `.adoc`, run it locally to refresh the
committed `.drawio` file and include the change in the same commit:

```bash
python3 scripts/generate-canvas-drawio.py
```

(from this directory; stdlib only, no extra packages needed).

## Publishing

Published at **https://little-trotter.github.io/** (PO decision, 2026-09-13):
the org's `Little-Trotter/little-trotter.github.io` repo, not a branch of this
one — that way the root URL serves the docs directly, with no `/invoice/`
path segment and no duplicate copy living in this repo's own Pages config.
**No GitHub Actions workflow exists or is added by this**: this repo's
workflow runs are deliberately disabled, and the sync stays a manual,
human-run step deliberately — one `make build` + one push, nothing to
automate yet.

What is already prepared:

- `scripts/build-docs.sh` writes a `.nojekyll` marker into the build output,
  so GitHub Pages' default Jekyll processing (which drops paths starting
  with `_`) does not touch the generated tree.
- Every asset reference in the built HTML is relative (stylesheets, images,
  inter-page links, the `index.html` entry point) — the site works both at a
  domain root and under a project sub-path (`/<repo>/`). No CDN references
  exist in the output (PO ban, checked by `DocsGate`).
- `index.html` is the entry point at the published root.

Manual publish sequence (from this directory):

```bash
make build

rm -rf html/.git
git -C html init -q
git -C html branch -m main
git -C html add -A
git -C html commit -q -m "Publish arc42 docs"
git -C html remote add origin git@github.com:Little-Trotter/little-trotter.github.io.git
git -C html push -f origin main
```

Pages on `Little-Trotter/little-trotter.github.io` is already configured
(branch `main`, root) and picks this up automatically — nothing to
re-enable. Run this after every arc42 content change that should go live;
there is no CI wiring, on purpose (see above).

## Container image

Besides the local bind-mount serving (`make serve`), the built site is also published as a
standalone image, `ghcr.io/eifel42/little-trotter-invoice/docs` — alongside this repo's `app`
and `xpdf` images, by the same `.github/workflows/publish-images.yml` (job `publish-docs`).
Unlike `make serve`, the image bakes `html/` in at build time (`containers/Dockerfile.docs`),
so the machine running it does not need Docker Buildx, Dagger, or anything else beyond
`docker run` — just a `docker pull`.

Tag strategy is the same as `app`/`xpdf`: `latest` on `main`, the short commit SHA, and the
pushed `v*` git tag, all produced by `docker/metadata-action` in the same workflow run.

Run it locally:

```bash
docker run --rm -p 9325:80 ghcr.io/eifel42/little-trotter-invoice/docs:latest
```

or build and run the image from source, without pulling:

```bash
make image         # build ghcr.io/eifel42/little-trotter-invoice/docs:local (override with IMAGE=/TAG=)
make serve-image   # build and run it on http://localhost:9325/
```

The admin tooling repo (`little-trotter-admin-tools`) consumes this image through its opt-in
`docs` compose profile — it pulls and runs it on the dev server, without building anything
there.

## Constraints

- Content is US English; German legal/tax terms stay untranslated where they
  appear (e.g. `USt.`, `X-Rechnung`, `Factur-X`).
- Certain internal, deliberately undocumented licensing and branding topics
  are out of scope for this documentation entirely (binding PO constraint,
  checked by the arc42 program's review step).

## License and attribution

Same terms as the repository (root `README.md`, `LICENSE`, `NOTICE`): the software is
Apache License 2.0; the concept, design and this documentation are CC BY 4.0 with the
attribution "Core idea derived from the LittleTrotter X-Rechnung project
(https://github.com/little-trotter/invoice) under CC BY 4.0." The built site states
this in its "License and Attribution" section.
