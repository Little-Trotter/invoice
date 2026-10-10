package de.littletrotter.application.invoice

import de.littletrotter.domain.invoice.DocumentEngine

/**
 * facturx-arc-improve.md §2.1 / §7 — starts/stops the container behind the fachlich
 * selected [DocumentEngine]. The application only knows the *intent*; HOW that is carried out
 * (Compose, a docker-socket-proxy sidecar, or nothing at all) is an infrastructure decision made
 * by whichever adapter is wired in ([de.littletrotter.infrastructure.docker
 * .EngineActivationProducer]). A no-op implementation is a valid adapter — see
 * [de.littletrotter.infrastructure.docker.NoopEngineActivation].
 *
 * Only ever called with [DocumentEngine.FACTURX] today — both call sites
 * ([de.littletrotter.application.invoice.DocumentJobPublisher.publish],
 * [de.littletrotter.ui.view.referencedata.ValidationSettingsTab.applyEngineActivation]) filter
 * out [DocumentEngine.MUSTANG] before reaching this port, since Mustang runs in-process in
 * `kotlin-xpdf` and has no container to manage. The signature still takes the full
 * [DocumentEngine] enum rather than being FACTURX-only, so an adapter never has to assume which
 * engine it will be asked about.
 *
 * Neither method may throw: a stuck or unreachable engine must never break invoice creation or
 * the reference-data switch itself — [de.littletrotter.infrastructure.docker.DockerStatusService]
 * carries the resulting inconsistency to the anwender as a status hint (Stufe 1), not this port.
 */
interface EngineActivation {
    /** Best-effort: make sure [engine]'s container is running (and, where the adapter can tell,
     *  reachable) before it is asked to do work. */
    fun ensureRunning(engine: DocumentEngine)

    /** Best-effort: release [engine]'s container once nothing needs it — either because an
     *  anwender switched it off, or because a scheduler decided it has been idle too long. */
    fun releaseIdle(engine: DocumentEngine)
}
