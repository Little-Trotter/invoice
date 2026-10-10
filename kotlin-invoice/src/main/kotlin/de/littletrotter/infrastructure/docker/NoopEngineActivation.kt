package de.littletrotter.infrastructure.docker

import de.littletrotter.application.invoice.EngineActivation
import de.littletrotter.domain.invoice.DocumentEngine

/**
 * facturx-arc-improve.md §3.2 Stufe 2 — the default [EngineActivation] adapter, chosen
 * by [EngineActivationProducer] whenever `invoice.facturx.docker-proxy.url` is blank/unset. Does
 * nothing at all: the system degrades exactly to Stufe 1's behavior, [DockerStatusService]'s
 * conditional `facturx` consistency hint. No exception, no special-casing at either call site
 * ([de.littletrotter.application.invoice.DocumentJobPublisher.publish],
 * [de.littletrotter.ui.view.referencedata.ValidationSettingsTab]) — those callers are written to
 * work identically whether Stufe 2 is deployed here or not.
 */
class NoopEngineActivation : EngineActivation {
    override fun ensureRunning(engine: DocumentEngine) = Unit
    override fun releaseIdle(engine: DocumentEngine) = Unit
}
