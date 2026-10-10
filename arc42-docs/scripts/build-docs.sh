#!/bin/bash
# Project: Little Trotter X-Invoice
# Copyright: Stefan Zils 2026
#
# Disclaimer: This software is provided "as is", without warranty of any kind,
# express or implied, including but not limited to the warranties of
# merchantability, fitness for a particular purpose, and noninfringement.

set -euo pipefail

# Builds the arc42 documentation with docToolchain and assembles the complete static site
# in html/. Runs inside the builder container (compose via `make build`, or the Dagger
# DocsGate); on the host, use `make build`.
#
# build/      docToolchain intermediate output (build/html5)
# html/       the finished site: nginx serves it, GitHub Pages publishes it

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
readonly BUILD_DIR="$PROJECT_ROOT/build"
readonly HTML_DIR="$PROJECT_ROOT/html"

# Colors for output
readonly RED='\033[0;31m'
readonly GREEN='\033[0;32m'
readonly YELLOW='\033[1;33m'
readonly BLUE='\033[0;34m'
readonly NC='\033[0m' # No Color

log_info() {
    echo -e "${BLUE}[info] $1${NC}"
}

log_success() {
    echo -e "${GREEN}[ok] $1${NC}"
}

log_warning() {
    echo -e "${YELLOW}[warn] $1${NC}"
}

log_error() {
    echo -e "${RED}[error] $1${NC}"
}

# Exports the C4 model to SVG (Structurizr CLI + PlantUML). Must write into doc/images/ before
# generateHTML runs: :imagesdir: resolves relative to the doc/ input at conversion time.
# Build-time artifacts, not checked in.
export_c4_diagrams() {
    local dsl_file="doc/c4/workspace.dsl"
    local images_dir="doc/images"
    local structurizr_bin="/opt/structurizr/structurizr.sh"
    local plantuml_jar="/usr/local/bin/plantuml.jar"

    if [ ! -f "$dsl_file" ]; then
        log_warning "No Structurizr workspace found at $dsl_file — skipping C4 export"
        return 0
    fi

    if [ ! -x "$structurizr_bin" ] || [ ! -f "$plantuml_jar" ]; then
        log_error "Structurizr CLI ($structurizr_bin) or PlantUML ($plantuml_jar) not found — cannot export C4 diagrams"
        return 1
    fi

    log_info "Exporting C4 diagrams from $dsl_file..."
    mkdir -p "$images_dir"

    local tmp_dir
    tmp_dir="$(mktemp -d)"

    "$structurizr_bin" export -workspace "$dsl_file" -format plantuml -output "$tmp_dir"

    local puml_files=("$tmp_dir"/*.puml)
    if [ ! -e "${puml_files[0]}" ]; then
        log_warning "Structurizr export produced no PlantUML files — nothing to render"
        rm -rf "$tmp_dir"
        return 0
    fi

    # Only the views the documentation shows are rendered: the hand-written c4-*.puml diagrams
    # supersede the Structurizr container and deployment views, whose auto-layout cannot keep
    # relation labels off the boxes, so an unreferenced export never reaches the published site.
    local referenced=()
    local puml name
    for puml in "${puml_files[@]}"; do
        name="$(basename "$puml" .puml)"
        if grep -rqF "image::${name}.svg" doc --include='*.adoc'; then
            referenced+=("$puml")
        fi
    done
    if [ "${#referenced[@]}" -eq 0 ]; then
        log_warning "No Structurizr export is referenced by the documentation — nothing to render"
        rm -rf "$tmp_dir"
        return 0
    fi

    java -jar "$plantuml_jar" -tsvg -o "$(cd "$images_dir" && pwd)" "${referenced[@]}"

    rm -rf "$tmp_dir"

    log_success "Exported C4 diagrams to $images_dir"
}

# Renders every hand-written PlantUML source in doc/plantuml/ to SVG with the same pinned
# PlantUML jar and Graphviz build the Structurizr exports go through, so all diagrams come out
# of one controlled toolchain instead of the PlantUML bundled inside docToolchain. The chapters
# embed the result with image::<name>.svg[]; the <name> is the file's basename.
render_plantuml_sources() {
    local puml_dir="doc/plantuml"
    local images_dir="doc/images"
    local plantuml_jar="/usr/local/bin/plantuml.jar"

    if [ ! -f "$plantuml_jar" ]; then
        log_error "PlantUML ($plantuml_jar) not found — cannot render $puml_dir"
        return 1
    fi

    local puml_files=("$puml_dir"/*.puml)
    if [ ! -e "${puml_files[0]}" ]; then
        log_warning "No PlantUML sources in $puml_dir — nothing to render"
        return 0
    fi

    log_info "Rendering ${#puml_files[@]} PlantUML sources from $puml_dir..."
    mkdir -p "$images_dir"
    java -Djava.awt.headless=true -jar "$plantuml_jar" -tsvg -failfast2 \
        -o "$(cd "$images_dir" && pwd)" "${puml_files[@]}"
    log_success "Rendered PlantUML sources to $images_dir"
}

# Regenerates the draw.io canvas sources from the canvas .adoc files. Runs BEFORE the HTML
# generation so both renderings of a canvas always come out of the same source text; see
# generate-canvas-drawio.py for why the layout stays hand-defined while only the text is derived.
generate_canvas_drawio() {
    local generator="$SCRIPT_DIR/generate-canvas-drawio.py"

    if [ ! -f "$generator" ]; then
        log_warning "Canvas generator not found at $generator — keeping the existing .drawio files"
        return 0
    fi
    if ! command -v python3 >/dev/null 2>&1; then
        log_warning "python3 not available — keeping the existing .drawio files"
        return 0
    fi

    log_info "Generating draw.io canvas sources from the canvas .adoc files..."
    python3 "$generator"
}

# The draw.io canvases are also published as self-contained HTML mirrors, generated
# from the same .adoc + layout data — draw.io is the reference rendering, and the
# mirror reproduces its geometry, labels and embedded diagram without needing the
# draw.io CLI in the builder. Runs AFTER assemble_site (which wipes html/ and copies
# the docToolchain pages) and overwrites the canvas pages with the mirror output.
generate_canvas_html_mirrors() {
    local generator="$SCRIPT_DIR/generate-canvas-drawio.py"

    if [ ! -f "$generator" ] || [ ! -d "$HTML_DIR" ]; then
        log_warning "Canvas generator or html/ not found — skipping HTML mirrors"
        return 0
    fi
    if ! command -v python3 >/dev/null 2>&1; then
        log_warning "python3 not available — skipping HTML mirrors"
        return 0
    fi

    log_info "Writing draw.io canvas HTML mirrors into html/..."
    python3 "$generator" --html-mirror
}

generate_html() {
    if ! command -v doctoolchain >/dev/null 2>&1; then
        log_error "doctoolchain not found — this script runs inside the builder container; use 'make build' on the host"
        return 1
    fi

    # docToolchain expects Config.groovy in the working directory
    export DTC_CONFIG_FILE="config/Config.groovy"
    if [ ! -f "Config.groovy" ]; then
        ln -s config/Config.groovy Config.groovy
    fi

    log_info "Generating HTML with docToolchain (output -> $BUILD_DIR/html5)"
    doctoolchain . generateHTML
    log_success "Finished docToolchain HTML generation"
}

# Asciidoctor does not propagate :docrole: to <body>. The canvas CSS selects
# on body.aic-page / body.acc-page instead of body:has(...), so older
# browsers without :has() (and managed Chrome installs) render the canvas
# layout correctly.
inject_canvas_body_class() {
    local targets=(
        "$HTML_DIR/architecture-inception-canvas.html:aic-page"
        "$HTML_DIR/architecture-communication-canvas.html:acc-page"
    )

    for target in "${targets[@]}"; do
        local file="${target%:*}"
        local class="${target##*:}"
        [ -f "$file" ] || continue
        if grep -q "class=\"[^\"]* ${class}\"" "$file"; then
            continue
        fi
        sed -i.bak "s|<body class=\"\\([^\"]*\\)\">|<body class=\"\\1 ${class}\">|" "$file"
        rm -f "${file}.bak"
    done

    log_success "Injected aic-page / acc-page body classes into canvas HTML"
}

# Assembles the finished site in html/ from the docToolchain output plus the assets
# docToolchain does not ship. html/ is emptied, never deleted: a running nginx bind-mounts
# the directory and would otherwise keep serving a stale, unlinked inode.
assemble_site() {
    mkdir -p "$HTML_DIR"
    find "$HTML_DIR" -mindepth 1 -delete
    cp -a "$BUILD_DIR/html5/." "$HTML_DIR/"
    rm -rf "$HTML_DIR/.asciidoctor"

    inject_canvas_body_class

    if [ -f "$PROJECT_ROOT/doc/index.html" ]; then
        cp "$PROJECT_ROOT/doc/index.html" "$HTML_DIR/index.html"
        log_success "Copied entry page to html/index.html"
    else
        log_warning "Entry page not found at $PROJECT_ROOT/doc/index.html"
    fi

    # LICENSE/NOTICE, so the canvases' footer license line (link:LICENSE[...] / link:NOTICE[...])
    # jumps straight to the project's actual license files instead of a fixed external link.
    # These are repo-root files, one level above arc42-docs/; DocsGate (ci/main.go) stages them
    # here (at $PROJECT_ROOT/LICENSE, $PROJECT_ROOT/NOTICE) before this script runs, because the
    # Dagger build mounts only the arc42-docs subtree as /workspace, not the whole repo.
    for legal_file in LICENSE NOTICE; do
        if [ -f "$PROJECT_ROOT/$legal_file" ]; then
            cp "$PROJECT_ROOT/$legal_file" "$HTML_DIR/$legal_file"
        else
            log_warning "$legal_file not found at $PROJECT_ROOT/$legal_file"
        fi
    done
    log_success "Copied LICENSE/NOTICE to html/"

    # Stylesheets. The .adoc headers set :stylesdir: css together with :linkcss:, so the pages
    # carry <link rel="stylesheet" href="css/arc42-*.css"> and the files have to be under
    # html/css/. docToolchain copies imageDirs, never the stylesheet directory, so this step
    # is what actually ships them — without it every page 404s its stylesheet, falls back to
    # Asciidoctor's defaults and loses the `img { height: auto }` /
    # `.imageblock img[src$=".svg"]` rules that keep the diagrams from rendering squashed.
    # arc42-common.css is pulled in by an @import inside the linked stylesheets and therefore
    # has to sit right next to them, which copying the whole directory takes care of.
    if [ -d "$PROJECT_ROOT/doc/css" ]; then
        mkdir -p "$HTML_DIR/css"
        cp "$PROJECT_ROOT/doc/css"/*.css "$HTML_DIR/css/"
        log_success "Copied stylesheets to html/css/"
    else
        log_warning "Stylesheet directory not found at $PROJECT_ROOT/doc/css"
    fi

    # draw.io canvas sources go next to the HTML so the footer links resolve.
    for drawio_file in architecture-inception-canvas.drawio architecture-communication-canvas.drawio; do
        if [ -f "$PROJECT_ROOT/doc/$drawio_file" ]; then
            cp "$PROJECT_ROOT/doc/$drawio_file" "$HTML_DIR/$drawio_file"
        else
            log_warning "draw.io canvas source not found at $PROJECT_ROOT/doc/$drawio_file"
        fi
    done

    # Normalize trailing newlines so regenerated files don't produce phantom diffs
    find "$HTML_DIR" -type f \( -name '*.html' -o -name '*.svg' \) -print0 | while IFS= read -r -d '' f; do
        if [ -s "$f" ] && [ -n "$(tail -c 1 "$f")" ]; then
            printf '\n' >> "$f"
        fi
    done

    # GitHub Pages runs the tree through Jekyll, which drops paths with a leading
    # underscore; the .nojekyll marker disables that.
    touch "$HTML_DIR/.nojekyll"
}

main() {
    cd "$PROJECT_ROOT"
    log_info "Starting documentation build in $PROJECT_ROOT..."

    rm -rf "$BUILD_DIR"
    mkdir -p "$BUILD_DIR"

    generate_canvas_drawio
    export_c4_diagrams
    render_plantuml_sources
    generate_html
    assemble_site
    generate_canvas_html_mirrors

    log_success "Documentation build completed: $HTML_DIR"
    log_success "HTML: file://$HTML_DIR/arc42-docs.html"
}

main "$@"
