# arc42 documentation build. Independent from the Maven build and the runtime stack: the
# build itself runs in Dagger (ci/main.go's DocsGate), and this compose file only serves
# the result via nginx.
#
# Output is the html/ directory. It is the complete static site: nginx serves it, and
# it is what gets published to GitHub Pages.

.PHONY: build serve screenshots stop clean image serve-image help

# Host port of the documentation web server
PORT ?= 9325

# Published docs image (ghcr.io/eifel42/little-trotter-invoice/docs, alongside app/xpdf — see
# ../.github/workflows/publish-images.yml). IMAGE/TAG are overridable for a local build.
IMAGE ?= ghcr.io/eifel42/little-trotter-invoice/docs
TAG ?= local

# DAGGER selects how the Dagger CLI is invoked:
#   local     - use the 'dagger' binary on PATH (default)
#   container - run the CLI in a throwaway Docker container; for a machine with Docker but
#               no locally installed Dagger CLI (make build DAGGER=container)
# DAGGER_VERSION pins the container image only; the local CLI is never pinned/downgraded.
DAGGER ?= local
DAGGER_VERSION ?= 0.21.9
DAGGER_CLI_IMAGE = arc42-docs-dagger-cli:$(DAGGER_VERSION)

COMPOSE = docker compose -f containers/docker-compose.docs.yml

ifeq ($(DAGGER),container)
# Mounts the Docker socket so the CLI (inside the container) can provision/connect to the
# engine, and mounts the repo root at the SAME absolute path inside the container so that
# --source=.. and the export --path resolve identically in both modes.
DAGGER_CMD = docker run --rm \
	-v /var/run/docker.sock:/var/run/docker.sock \
	-v $(abspath ..):$(abspath ..) \
	-w $(abspath ../ci) \
	$(DAGGER_CLI_IMAGE)
else
DAGGER_CMD = dagger
endif

help:
	@echo "Little Trotter X-Invoice - Architecture Documentation"
	@echo ""
	@echo "  make build   - Build the arc42 documentation into html/ (via Dagger; runs all three PO checks)"
	@echo "  make serve   - Build and serve html/ via nginx on http://localhost:$(PORT)/ (bind-mount)"
	@echo "  make screenshots - Capture fresh user guide screenshots from running app on https://localhost"
	@echo "  make stop    - Stop the documentation web server"
	@echo "  make clean   - Remove html/ and the compose containers/images"
	@echo "  make image   - Build the docs container image (\$$IMAGE:\$$TAG, default $(IMAGE):$(TAG))"
	@echo "  make serve-image - Build the image and run it standalone on http://localhost:$(PORT)/ (no bind-mount)"
	@echo "  make help    - Show this help message"

# DocsGate (ci/main.go) builds the site and runs the three PO checks (dev-key/watermark/
# wasserzeichen ban, CDN ban, Factor-X misspelling ban) before returning it; a violating
# build fails here with that check's message and html/ is left untouched.
#
# --wipe empties html/ before exporting into it — but the directory itself is written into
# in place, not deleted and recreated, so a running nginx (bind-mounting html/) keeps
# serving the new content after a rebuild without a restart (verified: same inode before
# and after export).
build:
ifeq ($(filter $(DAGGER),local container),)
	@echo "make build: DAGGER must be 'local' or 'container' (got '$(DAGGER)')"; exit 1
endif
ifeq ($(DAGGER),local)
	@command -v dagger >/dev/null 2>&1 || { echo "make build: 'dagger' not found on PATH - install the Dagger CLI first, or run with DAGGER=container"; exit 1; }
	@installed=$$(dagger version | awk '{print $$2}'); \
	if [ "$$installed" != "v$(DAGGER_VERSION)" ]; then \
		echo "make build: local Dagger CLI is $$installed, expected v$(DAGGER_VERSION) - run 'brew upgrade dagger', or check for a stale dagger binary earlier in PATH"; \
	fi
else
	@docker build -q --build-arg DAGGER_VERSION=$(DAGGER_VERSION) -f containers/Dockerfile.dagger-cli -t $(DAGGER_CLI_IMAGE) . >/dev/null
endif
	@mkdir -p html
	@cd ../ci && $(DAGGER_CMD) call docs-gate --source=.. export --path=../arc42-docs/html --wipe
	@echo "Output: $(CURDIR)/html"

serve: build
	@PORT=$(PORT) $(COMPOSE) up --detach nginx
	@echo "Documentation is served at http://localhost:$(PORT)/"

screenshots:
	@python3 scripts/capture-user-guide-screenshots.py

stop:
	@$(COMPOSE) rm --stop --force --volumes nginx

# Bakes html/ into a standalone nginx image (containers/Dockerfile.docs) instead of the
# bind-mount `serve` uses — this is what publish-images.yml's docs job pushes to GHCR, and
# what the admin tooling's opt-in `docs` compose profile pulls and runs, unbuilt.
image: build
	docker build -f containers/Dockerfile.docs -t $(IMAGE):$(TAG) .
	@echo "Built $(IMAGE):$(TAG)"

serve-image: image
	@echo "Documentation is served (from the image, no bind-mount) at http://localhost:$(PORT)/"
	docker run --rm -p $(PORT):80 $(IMAGE):$(TAG)

clean:
	@$(COMPOSE) down --rmi local --volumes || true
	@rm -rf html
