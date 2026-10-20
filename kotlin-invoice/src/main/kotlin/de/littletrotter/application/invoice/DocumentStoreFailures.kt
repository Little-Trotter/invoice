package de.littletrotter.application.invoice

import software.amazon.awssdk.core.exception.SdkException
import java.io.IOException

/**
 * [de.littletrotter.shared.DocumentStore] is a port with two real backends and no shared
 * checked-exception contract between them: the S3-compatible backend
 * (`de.littletrotter.infrastructure.storage.S3DocumentStore`, via
 * `de.littletrotter.shared.AbstractS3DocumentStore`) throws the AWS SDK's own [SdkException]
 * hierarchy for every failure, missing key included ([software.amazon.awssdk.services.s3.model
 * .NoSuchKeyException] is a subtype); the local-filesystem backend
 * (`de.littletrotter.infrastructure.storage.LocalFileSystemDocumentStore`) throws [IOException]
 * for an I/O failure and [NoSuchElementException] for a missing key specifically. Naming all
 * three here, once, replaces the `catch (e: Exception)` every direct caller used to need (and
 * detekt's `TooGenericExceptionCaught` rightly flagged) with the concrete types that can actually
 * reach a caller — every failure a real backend can raise is caught, without also catching
 * unrelated bugs the way a bare `Exception` would.
 *
 * A regular (non-`inline`) function on purpose: an `inline fun`'s body is inlined
 * into every call site, so its own lines never execute as part of *this* file at coverage time —
 * only the caller's file gets credit, and this file's own aggregate coverage stays structurally
 * unmeasurable no matter how thoroughly [DocumentStoreFailuresTest] exercises it. Behaviour is
 * identical either way (the lambda parameters need no non-local `return` at any call site).
 */
internal fun <T> runDocumentStoreOp(onFailure: (Exception) -> T, block: () -> T): T =
    try {
        block()
    } catch (failure: IOException) {
        onFailure(failure)
    } catch (failure: NoSuchElementException) {
        onFailure(failure)
    } catch (failure: SdkException) {
        onFailure(failure)
    }
