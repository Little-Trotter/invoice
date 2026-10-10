# Little Trotter X-Invoice — simple entry point.
#
# This repo is the PRODUCT: Kotlin sources, container images, a one-command
# local stack. The full workshop (dev tooling, QA suite, E2E, admin/deploy —
# including building and publishing images to the Forgejo registry, `make
# -C admin-tools publish`) lives elsewhere — see README "Zwei Repositories".

# Host port of the arc42 documentation web server (see 'docs-serve')
DOCS_PORT ?= 9325

# Target platform of every image build: the local daemon's architecture, so the remote amd64
# builder still produces images the Mac runs natively.
LT_PLATFORM ?= linux/$(shell docker version -f '{{.Server.Arch}}' 2>/dev/null || echo arm64)

.DEFAULT_GOAL := help

help: ## Show available targets
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[1m%-12s\033[0m %s\n", $$1, $$2}'

secrets: ## Create .env with strong random credentials (required once before 'make dev')
	@./ci/scripts/dev/setup-secrets.sh

dev: ## Start the local stack (docker compose up -d)
	@# The workshop repo's dev stack publishes the same ports (5432/9000/4222/…) — starting
	@# both at once fails mid-way with "port is already allocated" and looks like a crash.
	@if docker ps --format '{{.Names}}' 2>/dev/null | grep -q '^little-trotter-admin-tools-'; then \
		echo "The workshop stack (little-trotter-admin-tools) is already running — both stacks share the same ports."; \
		echo "  Keep working there:  app at https://localhost via Caddy (incl. demo data, debug port 5005)"; \
		echo "  Or start this stack instead — stop the other one first (data is kept):"; \
		echo "    cd ../little-trotter-admin-tools && docker compose --profile core stop"; \
		exit 1; \
	fi
	DOCKER_DEFAULT_PLATFORM=$(LT_PLATFORM) BUILDX_BUILDER=$$(ci/scripts/dev/docker-builder.sh) docker compose up -d
	@echo "App: http://localhost:8080 (first start builds images and runs migrations — allow a few minutes)"

build: ## Build all images (remote builder when reachable, else local; LT_PLATFORM overrides the target platform)
	DOCKER_DEFAULT_PLATFORM=$(LT_PLATFORM) BUILDX_BUILDER=$$(ci/scripts/dev/docker-builder.sh) docker compose build

dev-down: ## Stop the local stack (data volumes are kept)
	docker compose down

logs: ## Follow the app container logs
	docker compose logs -f little-trotter-invoice

e2e-mail-image: ## Build the email-app image the E2E suite runs against (kotlin-e2e-tests EmailAppTestResource)
	@docker buildx build --builder $$(ci/scripts/dev/docker-builder.sh) --platform $(LT_PLATFORM) --load -f kotlin-email/email-app/Dockerfile -t little-trotter-email-app:e2e .

e2e-quote-image: ## Build the quote-app image the E2E suite runs against (kotlin-e2e-tests QuoteAppTestResource)
	@docker buildx build --builder $$(ci/scripts/dev/docker-builder.sh) --platform $(LT_PLATFORM) --load -f kotlin-quote/quote-app/Dockerfile -t little-trotter-quote-app:e2e .

test: e2e-mail-image e2e-quote-image ## Build and run all tests incl. the aggregate coverage gate (TERSE=true for compact output)
ifeq ($(TERSE),true)
	@./ci/scripts/dev/mvn-exclusive.sh --terse clean verify
else
	@./ci/scripts/dev/mvn-exclusive.sh clean verify
endif
	# mvn-exclusive.sh takes a MACHINE-WIDE lock (fixed path outside every tree, default
	# ~/GitHub/lt-worktrees/.maven-lock, override via LT_MAVEN_LOCK) before running Maven, not a
	# per-tree one. Several sessions/agents regularly build the same repo at once from separate
	# git worktrees, and Maven's first act is to write into target/ — two runs at once produce a
	# silent 0-class compile that looks like a source error, not like a collision. Worse, the
	# background Kotlin compile daemon is itself shared machine-wide (not per tree), so two
	# concurrent runs from *different* worktrees can poison it for both, even though each writes
	# to its own target/ — a plain per-tree `.build-lock` cannot prevent that at all.
	#
	# `test` used to take its own per-tree `.build-lock` here directly, which is exactly the gap
	# mvn-exclusive.sh already closed for agent build briefs (docs/coverage-gate/, fix/plan.md)
	# but `test` itself never adopted — so a human `make test` and an agent's own
	# mvn-exclusive.sh-based build could still collide. Routing `test` through the same wrapper
	# means every Maven invocation on the machine, however it is started, serializes against the
	# same lock.
	#
	# Only `test` takes it, matching the pre-split behaviour: `dev`/`dev-down` drive docker
	# compose, which never touches target/ — locking them would block a colleague's test run for
	# the lifetime of a stack.

test-terse: ## Run full test suite with token-thrifty output (failures & essentials only)
	@$(MAKE) test TERSE=true


hooks: ## Install the Git pre-commit hook (Gitleaks secret scan on staged changes) for this clone
	@chmod +x .githooks/*
	@git config core.hooksPath .githooks
	@command -v gitleaks >/dev/null 2>&1 || echo "[make] gitleaks not installed - hook will warn and skip until: brew install gitleaks"
	@echo "[make] core.hooksPath=.githooks - pre-commit secret scan active"

docs: ## Build arc42 docs into arc42-docs/html/ (via Dagger, runs the dev-key/CDN/Factor-X PO checks; DAGGER=container runs the Dagger CLI in Docker)
	$(MAKE) -C arc42-docs build

docs-serve: ## Build and serve the arc42 docs locally (http://localhost:9325/, override with DOCS_PORT=)
	$(MAKE) -C arc42-docs serve PORT=$(DOCS_PORT)

docs-stop: ## Stop the arc42 documentation web server
	$(MAKE) -C arc42-docs stop

docs-clean: ## Remove arc42-docs build output (html/) and its compose containers/images
	$(MAKE) -C arc42-docs clean

regen-b2b-term-drift-fixture: ## Regenerate kotlin-shared's B2bTermDbDriftTest fixture from the admin-tools generator (dev-time only)
	@./scripts/regen-b2b-term-drift-fixture.sh

.PHONY: help secrets dev build dev-down logs e2e-mail-image e2e-quote-image test test-terse docs docs-serve docs-stop docs-clean regen-b2b-term-drift-fixture
