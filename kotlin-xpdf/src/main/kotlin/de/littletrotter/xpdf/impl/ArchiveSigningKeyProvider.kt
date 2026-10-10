package de.littletrotter.xpdf.impl

import de.littletrotter.shared.archive.ArchiveSigner
import de.littletrotter.shared.archive.Ed25519ArchiveSigner
import de.littletrotter.shared.archive.Ed25519Keys
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.util.Optional

/**
 * Loads the Ed25519 signing key `kotlin-xpdf` uses to sign every archive package
 * ([de.littletrotter.shared.archive.ArchivePackageBuilder], invoked from
 * [PdfExportServiceImpl.completeExport]). The keypair itself is generated once by the `lt-setup`
 * wizard (admin-tools) and delivered as two base64-encoded `.env` secrets — the same
 * env-var-from-`.env` convention every other credential in this stack already uses
 * (`LT_MIGRATOR_DB_PASSWORD`, `S3_ACCESS_KEY`/`S3_SECRET_KEY` above); there is no file-mounted
 * secret/bind-mount convention anywhere in this repo to follow instead.
 *
 * Two independent secrets, not one keypair blob, because the JDK's own `java.security` API has no
 * way to derive an Ed25519 public key from its private half (see [Ed25519Keys]'s own KDoc) — both
 * halves are generated together once (`lt-setup`, via `openssl`) and configured separately here.
 * Only the private key is sensitive; the public key only ever feeds [Ed25519Keys.fingerprint] for
 * `manifest.json`, meant to be handed to auditors.
 *
 * `%prod.xpdf.archive.signing-private-key`/`signing-public-key` in `application.properties` carry
 * no default — the exact fail-fast pattern [de.littletrotter.xpdf.impl.S3DocumentStore]'s
 * own config already uses (a `%prod` deployment missing either env var refuses to start rather
 * than silently signing with a dev/ephemeral key). The base (non-`%prod`) properties default to
 * blank, which [init] treats as "no key configured — generate an ephemeral, JVM-lifetime-only
 * keypair" so `%dev`/`%test` work out of the box without requiring `setup-secrets.sh` to have run;
 * every package this instance ever signs stays internally consistent (its own manifest carries the
 * matching fingerprint), it is simply not the same key across restarts — acceptable for local
 * development, never reached in `%prod` at all.
 *
 * This class itself is now a plain, CDI-free wrapper (Sonar rule S6514) that only `by`-delegates
 * [ArchiveSigner] to an already-built [Ed25519ArchiveSigner]: constructing it needs no Quarkus
 * bootstrap, so a test constructs it directly with no `init()` step (a manually hand-written
 * `algorithm`/`keyFingerprint`/`sign` forwarding trio was exactly the boilerplate `by` delegation
 * exists to avoid, but `by` only binds against a primary-constructor value, evaluated as part of
 * the class header — never against a property a `@PostConstruct` builds later in the body, which
 * is what the former `lateinit var delegate` here was). All the config reading and the
 * ephemeral-keypair fallback below now run eagerly in [ArchiveSignerProducer]'s own `@Produces`
 * method, once, at bean-creation time, before this class is ever constructed.
 */
class ArchiveSigningKeyProvider(private val delegate: Ed25519ArchiveSigner) : ArchiveSigner by delegate {

    companion object {
        private val log = Logger.getLogger(ArchiveSigningKeyProvider::class.java)

        /** Decodes the two configured base64 secrets into a working [Ed25519ArchiveSigner], or
         *  falls back to an ephemeral, JVM-lifetime-only keypair when either is blank — see this
         *  class's own KDoc for the `%prod`/`%dev` split this relies on. */
        fun resolveDelegate(privateKeyBase64: Optional<String>, publicKeyBase64: Optional<String>): Ed25519ArchiveSigner {
            val privateKey = privateKeyBase64.orElse("")
            val publicKey = publicKeyBase64.orElse("")
            return if (privateKey.isBlank() || publicKey.isBlank()) {
                log.warn(
                    "xpdf.archive.signing-private-key/signing-public-key not configured — generating an " +
                        "ephemeral, JVM-lifetime-only Ed25519 keypair for archive package signing. This is " +
                        "expected in dev/test; a %prod deployment without these secrets refuses to start " +
                        "instead of reaching this branch (see this class's own KDoc).",
                )
                val keyPair = Ed25519Keys.generateKeyPair()
                Ed25519ArchiveSigner(keyPair.private, Ed25519Keys.fingerprint(keyPair.public))
            } else {
                Ed25519ArchiveSigner(
                    Ed25519Keys.decodePrivateKeyBase64(privateKey),
                    Ed25519Keys.fingerprint(Ed25519Keys.decodePublicKeyBase64(publicKey)),
                )
            }
        }
    }
}

/**
 * The CDI wiring split out of [ArchiveSigningKeyProvider] itself (Sonar rule S6514): reads the
 * two `xpdf.archive.signing-*` secrets once via constructor injection and hands out the resulting
 * [ArchiveSigner] through a `@Produces` method, so the config read genuinely happens at producer
 * time, not deferred to a lifecycle callback on the signer bean.
 *
 * `Optional<String>`, not `String`: outside `%prod` these two resolve to an EMPTY value
 * (`${ARCHIVE_ED25519_PRIVATE_KEY:}` in application.properties), and SmallRye Config treats an
 * empty value as ABSENT — a non-optional String injection point therefore fails outright with
 * "Failed to load config value of type class java.lang.String", crashing the whole service before
 * [ArchiveSigningKeyProvider.resolveDelegate]'s own ephemeral-keypair fallback can ever run. Found
 * by a live e2e-stack run (2026-09-04): kotlin-xpdf crash-looped on startup in every non-prod
 * deployment, so no generation job could complete at all. `%prod` keeps failing fast on a missing
 * secret exactly as before — that happens during property expansion
 * (`${ARCHIVE_ED25519_PRIVATE_KEY}`, no default there), which this type never reaches.
 */
@ApplicationScoped
class ArchiveSignerProducer @Inject constructor(
    // `@param:` pins the annotation to the constructor parameter only, same reasoning as
    // S3DocumentStore's own constructor (see that class's KDoc).
    @param:ConfigProperty(name = "xpdf.archive.signing-private-key") private val privateKeyBase64: Optional<String>,
    @param:ConfigProperty(name = "xpdf.archive.signing-public-key") private val publicKeyBase64: Optional<String>,
) {
    @Produces
    @ApplicationScoped
    fun produceArchiveSigner(): ArchiveSigner =
        ArchiveSigningKeyProvider(ArchiveSigningKeyProvider.resolveDelegate(privateKeyBase64, publicKeyBase64))
}
