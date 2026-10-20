# Little Trotter X-Invoice — simple entry point.
#
# This repo is the PRODUCT: Kotlin sources, container images, a one-command local stack.
# The workshop (dev tooling, QA suite, admin/deploy, image publishing) lives in
# little-trotter-admin-tools — see README "Zwei Repositories".
# The arc42 documentation has its own entry point: make -C arc42-docs help

# Target platform of every image build: the local daemon's architecture, so the remote amd64
# builder still produces images the Mac runs natively.
LT_PLATFORM ?= linux/$(shell docker version -f '{{.Server.Arch}}' 2>/dev/null || echo arm64)
BUILDX := DOCKER_DEFAULT_PLATFORM=$(LT_PLATFORM) BUILDX_BUILDER=$$(ci/scripts/dev/docker-builder.sh)

.DEFAULT_GOAL := help

help: ## Show available targets
	@grep -E '^[a-zA-Z0-9_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[1m%-12s\033[0m %s\n", $$1, $$2}'

dev: ## Start the local stack; writes .env with random credentials on first run
	@# The workshop repo's dev stack publishes the same ports (5432/9000/4222/…) — starting
	@# both at once fails mid-way with "port is already allocated" and looks like a crash.
	@if docker ps --format '{{.Names}}' 2>/dev/null | grep -q '^little-trotter-admin-tools-'; then \
		echo "The workshop stack (little-trotter-admin-tools) is already running — both stacks share the same ports."; \
		echo "  Keep working there:  app at https://localhost via Caddy (incl. demo data, debug port 5005)"; \
		echo "  Or start this stack instead — stop the other one first (data is kept):"; \
		echo "    cd ../little-trotter-admin-tools && docker compose --profile core stop"; \
		exit 1; \
	fi
	@test -f .env || ./ci/scripts/dev/setup-secrets.sh
	@./ci/scripts/dev/setup-secrets.sh --check >/dev/null || ./ci/scripts/dev/setup-secrets.sh --check
	$(BUILDX) docker compose up -d
	@echo "App: http://localhost:8080 (first start builds images and runs migrations — allow a few minutes)"

dev-down: ## Stop the local stack (data volumes are kept)
	docker compose down

build: ## Rebuild all images after source changes (remote builder when reachable, else local)
	$(BUILDX) docker compose build

e2e-images: ## Build the email-app and quote-app images the E2E suite runs against
ifeq ($(wildcard kotlin-email),)
	@echo "e2e-images: kotlin-email absent (public snapshot) - nothing to build"
else
	@docker buildx build --builder $$(ci/scripts/dev/docker-builder.sh) --platform $(LT_PLATFORM) --load -f kotlin-email/email-app/Dockerfile -t little-trotter-email-app:e2e .
endif
ifeq ($(wildcard kotlin-quote),)
	@echo "e2e-images: kotlin-quote absent (public snapshot) - nothing to build"
else
	@docker buildx build --builder $$(ci/scripts/dev/docker-builder.sh) --platform $(LT_PLATFORM) --load -f kotlin-quote/quote-app/Dockerfile -t little-trotter-quote-app:e2e .
endif

test: e2e-images ## Build and run all tests incl. the aggregate coverage gate (TERSE=true for compact output)
	@# mvn-exclusive.sh takes a MACHINE-WIDE lock before running Maven: several sessions build
	@# the same repo at once from separate worktrees, Maven's first act is to write target/, and
	@# the Kotlin compile daemon is shared machine-wide — two concurrent runs produce a silent
	@# 0-class compile or poison the daemon for both. Only `test` takes the lock; the compose
	@# targets never touch target/.
	@./ci/scripts/dev/mvn-exclusive.sh $(if $(filter true,$(TERSE)),--terse,) clean verify

.PHONY: help dev dev-down build e2e-images test
