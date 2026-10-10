package de.littletrotter.xpdf

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.net.URI

/**
 * Builds AWS SDK v2 clients pointed at a self-hosted, S3-compatible server (RustFS).
 *
 * Two settings are what make this work against a non-AWS endpoint:
 *  - `S3Client.Builder.endpointOverride` so requests go to our server instead of amazonaws.com
 *  - path-style access, because `bucket.host` virtual-host addressing needs DNS entries
 *    that a self-hosted server does not have.
 *
 * A region must still be supplied because it is part of the SigV4 signature; the value itself
 * is irrelevant to an S3-compatible server as long as it is stable.
 */
object S3Clients {

    /** Region is signature filler for S3-compatible servers, not a real AWS placement. */
    val DEFAULT_REGION: Region = Region.US_EAST_1

    fun credentials(accessKey: String, secretKey: String): StaticCredentialsProvider =
        StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey))

    fun build(endpoint: String, accessKey: String, secretKey: String): S3Client =
        S3Client.builder()
            .endpointOverride(URI.create(endpoint))
            .credentialsProvider(credentials(accessKey, secretKey))
            .region(DEFAULT_REGION)
            .forcePathStyle(true)
            .build()

    fun presigner(endpoint: String, accessKey: String, secretKey: String): S3Presigner =
        S3Presigner.builder()
            .endpointOverride(URI.create(endpoint))
            .credentialsProvider(credentials(accessKey, secretKey))
            .region(DEFAULT_REGION)
            .serviceConfiguration(
                software.amazon.awssdk.services.s3.S3Configuration.builder()
                    .pathStyleAccessEnabled(true)
                    .build()
            )
            .build()
}
