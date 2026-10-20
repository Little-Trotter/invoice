workspace "Little Trotter X-Invoice" "Architecture for the Little Trotter X-Invoice application" {

    !impliedRelationships false

    model {
        // --- People ---
        officeManager = person "Office Manager" "Manages data, invoices, receipts" "Person"

        recipient = person "Tax Advisor / Auditor / Invoice Recipient" "Receives invoices and documents" "Person"

        devops = person "DevOps" "Provisions, deploys, and maintains the server" "Person"

        // --- External systems (declared before ltSystem so its container-level
        // relationships below can reference them) ---
        monitoring = softwareSystem "Monitoring (Prometheus/Grafana)" "Scrapes metrics, shows health" "External System"

        backupTarget = softwareSystem "Backup Storage" "Off-site encrypted backup target" "External System"

        authentik = softwareSystem "Authentik" "OIDC identity provider and admin forward-auth" "External System"

        mailDispatch = softwareSystem "little-trotter-email" "Optional mail dispatch self-contained system" "External System"

        quoteSystem = softwareSystem "little-trotter-quote" "Optional quote self-contained system" "External System"

        vies = softwareSystem "EU VIES REST API" "VAT ID validation service (on demand)" "External System"

        // --- Core system ---
        ltSystem = softwareSystem "Little Trotter X-Invoice" "UStG e-invoicing: master data, receipts, jobs" {
            caddy = container "Caddy" "TLS-terminating reverse proxy" "Caddy"

            app = container "little-trotter-invoice" "Web app: master data, invoices, receipts" "Kotlin, Quarkus, Vaadin Flow" {
                uiComponent = component "ui" "Views, navigation, i18n, notifications" "Kotlin package de.littletrotter.ui"

                applicationComponent = component "application" "Use-case orchestration, input ports, DTOs" "Kotlin package de.littletrotter.application"

                domainComponent = component "domain" "Domain model and business rules" "Kotlin package de.littletrotter.domain"

                infrastructureComponent = component "infrastructure" "Adapters for persistence, messaging, storage" "Kotlin package de.littletrotter.infrastructure"

                spiComponent = component "spi" "Extension points for pluggable modules" "Kotlin package de.littletrotter.spi"

                uiComponent -> applicationComponent "Invokes use cases via input ports" "In-process Kotlin calls"
                uiComponent -> spiComponent "Renders module fields" "In-process Kotlin calls"
                applicationComponent -> domainComponent "Orchestrates" "In-process Kotlin calls"
                applicationComponent -> infrastructureComponent "Calls adapters" "In-process Kotlin calls"
                applicationComponent -> spiComponent "Discovers extensions" "CDI / SPI"
                infrastructureComponent -> domainComponent "Implements ports" "In-process Kotlin calls"
            }

            jobWorker = container "little-trotter-job-worker" "Re-publishes stuck generation jobs" "Kotlin, Quarkus"

            xpdf = container "kotlin-xpdf" "Renders and validates PDF/A-3b, XML" "Kotlin, Quarkus, Typst, Mustang, KoSIT"

            verapdf = container "verapdf" "Asynchronous PDF/A-3b conformance check" "Java, veraPDF REST"

            postgres = container "PostgreSQL" "Master data, invoices, receipts, jobs" "PostgreSQL 18" "Database"

            rustfs = container "RustFS" "Object store for receipts, documents" "RustFS (S3 API)" "Database"

            nats = container "NATS JetStream" "Job envelopes: app, worker, renderer" "NATS JetStream" "Queue"

            valkey = container "Valkey" "Cache and text-payload store" "Valkey (Redis-protocol)" "Database"

            officeManager -> caddy "Manages invoices" "HTTPS, browser"
            caddy -> app "Proxies HTTPS" "HTTP loopback"
            monitoring -> app "Scrapes metrics" "HTTP (/q/metrics)"

            app -> postgres "Reads/writes" "JDBC"
            app -> rustfs "Reads/writes documents" "S3 API"
            app -> nats "Publishes jobs, consumes results" "NATS JetStream"
            app -> valkey "Caches data, text payloads" "Redis protocol"
            app -> authentik "Authenticates Office Manager" "OIDC"
            app -> mailDispatch "Creates mail draft" "HTTPS REST, optional"
            app -> quoteSystem "Manages quotes, replicas" "HTTPS REST / NATS JetStream, optional"

            jobWorker -> postgres "Re-publishes stuck jobs" "JDBC"
            jobWorker -> nats "Re-publishes jobs" "NATS JetStream"

            xpdf -> nats "Consumes jobs, publishes results" "NATS JetStream"
            xpdf -> rustfs "Stores/reads documents" "S3 API"
            xpdf -> valkey "Resolves text payloads" "Redis protocol"
            xpdf -> verapdf "Validates PDF/A-3b conformance" "HTTP REST"
            verapdf -> rustfs "Streams PDF documents" "S3 API"
            mailDispatch -> app "Reports dispatch result" "NATS JetStream"
            quoteSystem -> app "Requests invoice draft from quote" "NATS JetStream"
        }

        // --- System Context relationships ---
        officeManager -> ltSystem "Manages invoices, receipts" "HTTPS, browser"
        officeManager -> recipient "Hands over e-invoices and PDFs" "out-of-band"
        ltSystem -> authentik "Authenticates Office Manager" "OIDC"
        ltSystem -> vies "Validates recipient VAT ID" "HTTPS REST, on demand"
        ltSystem -> mailDispatch "Hands over mail draft" "HTTPS REST, optional"
        mailDispatch -> ltSystem "Reports dispatch outcome" "NATS JetStream"
        ltSystem -> quoteSystem "Delegates quote management and master-data events" "HTTPS REST / NATS JetStream, optional"
        quoteSystem -> ltSystem "Hands over explicit invoice order" "NATS JetStream"
        devops -> ltSystem "Provisions, deploys and maintains" "SSH, CLI"
        devops -> backupTarget "Copies backup archives" "age-encrypted"
        devops -> monitoring "Reviews dashboards and alerts" "HTTPS"
        monitoring -> ltSystem "Scrapes metrics" "HTTP"

        // --- Deployment: target environment, "server" (never "pi"/"host", see docs/deployment.md) ---
        // PO 2026-09-12 — reflects the real Ubuntu-server topology (little-trotter-admin-tools,
        // verified 2026-09-12): PostgreSQL, RustFS and Caddy run NATIVELY (systemd), shared by
        // every instance on the box; only the app layer is Docker Compose, one project per
        // instance. This view now backs only the "ServerDeployment" notation-key legend in the
        // appendix (the chapter's own diagram is the hand-laid doc/plantuml/c4-server-deployment.puml)
        // — kept here so the exported legend still matches the real element kinds.
        deploymentEnvironment "Server" {
            server = deploymentNode "Server" "Ubuntu (primary) / Linux (dedicated single-instance host, e.g. a mini PC)" "Linux" {
                native = deploymentNode "Linux host (native)" "systemd, shared by every instance" "systemd" {
                    caddyInstance = containerInstance caddy
                    postgresInstance = containerInstance postgres
                    rustfsInstance = containerInstance rustfs
                }
                dockerEngine = deploymentNode "Docker Engine" "One compose project per instance" "Docker" {
                    invoiceNet = deploymentNode "invoice-net" "Network for this instance's app-layer services" "Docker network" {
                        appInstance = containerInstance app
                        jobWorkerInstance = containerInstance jobWorker
                        xpdfInstance = containerInstance xpdf
                        verapdfInstance = containerInstance verapdf
                        natsInstance = containerInstance nats
                        valkeyInstance = containerInstance valkey
                    }
                }
            }
        }
    }

    views {
        systemContext ltSystem "SystemContext" "Office Manager, recipient, DevOps, external neighbors" {
            include *
            // devops has no direct relationship to ltSystem (only to monitoring), so the default
            // "neighbors of ltSystem" scope of `include *` on a systemContext view drops it —
            // include it explicitly; its existing devops -> monitoring relationship still draws
            // since monitoring is in scope too.
            include devops
            include backupTarget
            autoLayout lr
        }

        container ltSystem "Containers" "App, job worker, renderer, infrastructure" {
            include *
            autoLayout lr
        }

        component app "Components" "Top-level packages of little-trotter-invoice" {
            include *
            autoLayout lr
        }

        deployment ltSystem "Server" "ServerDeployment" {
            include *
            title "Deployment: Server"
            description "Compose stack on the target server"
            autoLayout tb
        }

        // A42-11 — element colors follow the app's own palette (verified
        // against kotlin-invoice/src/main/frontend/themes/littletrotter/
        // styles.css): accent-blue family for core system/container/
        // component/database (the "inside the boundary" elements), and
        // secondary-grey for people and external systems (the "outside the
        // boundary" elements) — per docs/build-arc42/a42-11-design-app-theme.md
        // task 3. Hexes are static (Structurizr/PlantUML render to a flat SVG
        // at build time, no CSS variables available), derived by sRGB-mixing
        // --lt-accent (#1A73E8) / --lt-text-secondary (#5F6368) with white or
        // black; every fill/text pair below is >=4.5:1 (WCAG AA), see
        // doc/css/arc42-docs.css's token-mapping comment for the source
        // tokens and docs/build-arc42/a42-11-design-app-theme.md for the
        // contrast figures.
        styles {
            element "Person" {
                // secondary-grey family: --lt-text-secondary, flat. White text 6.05:1.
                shape Person
                background #5F6368
                stroke #4C4F53
                color #ffffff
                fontSize 12
            }
            element "Software System" {
                // accent-blue family: --lt-accent, full strength. White text 4.51:1.
                background #1A73E8
                stroke #1456AE
                color #ffffff
                fontSize 14
                shape Box
            }
            element "External System" {
                // secondary-grey family: --lt-text-disabled (lighter than Person).
                // Dark text 6.10:1.
                background #9AA0A6
                stroke #74787C
                color #202124
                fontSize 12
                shape Box
            }
            element "Container" {
                // accent-blue family: --lt-accent mixed 85% with white. Dark text 4.53:1.
                background #3C88EB
                stroke #2D66B0
                color #202124
                fontSize 13
                shape Box
            }
            element "Component" {
                // accent-blue family: --lt-accent mixed 55% with white. Dark text 7.35:1.
                background #81B2F2
                stroke #5A7DA9
                color #202124
                fontSize 12
                shape Box
            }
            element "Database" {
                // accent-blue family: --lt-accent mixed 85% with black (deep tint,
                // distinct from Container/Component/System). White text 5.85:1.
                background #1662C5
                stroke #1456AE
                color #ffffff
                fontSize 12
                shape Cylinder
            }
            element "Queue" {
                // accent-blue family: --lt-accent mixed 70% with black (deepest tint,
                // distinguishes the messaging layer). White text 7.79:1.
                background #1250A2
                stroke #0E3C7A
                color #ffffff
                fontSize 12
                shape Pipe
            }
            relationship "Relationship" {
                thickness 2
                color #5F6368
                dashed false
                routing Orthogonal
                fontSize 11
                width 250
                position 50
                opacity 100
            }
        }
    }
}
