package de.littletrotter.xpdf.impl

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

/**
 * Runs the pinned veraPDF REST service (`verapdf/rest:v1.30.2`, the version the compose file and
 * [de.littletrotter.shared.ValidationToolchain.VERAPDF_DEFAULT_VERSION] name) in a Testcontainer
 * and hands out a [VeraPdfRestClient] aimed at it — the very client production uses, so the
 * conformance proofs cover the path documents take through the stack. The ITs using it are plain
 * JUnit classes rather than `@QuarkusTest`s, so a `QuarkusTestResourceLifecycleManager` does not
 * apply.
 */
class VeraPdfTestResource : GenericContainer<VeraPdfTestResource>(DockerImageName.parse(IMAGE)) {

    init {
        withExposedPorts(SERVICE_PORT)
        waitingFor(Wait.forListeningPort())
    }

    /** A client for this container; findings name the same engine version the service runs. */
    fun client(): VeraPdfRestClient = VeraPdfRestClient(baseUrl = "http://$host:${getMappedPort(SERVICE_PORT)}")

    companion object {
        const val IMAGE = "verapdf/rest:v1.30.2"
        private const val SERVICE_PORT = 8080
    }
}
