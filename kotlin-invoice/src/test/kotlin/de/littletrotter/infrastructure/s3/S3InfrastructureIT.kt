package de.littletrotter.infrastructure.s3

import de.littletrotter.test.CombinedTestContainersExtension
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.net.URI

/**
 * Real integration test for RustFS' S3-compatible API using Testcontainers, via the AWS SDK v2
 * client (the same client the application uses in production, unlike a vendor-specific SDK).
 */
@ExtendWith(CombinedTestContainersExtension::class)
class S3InfrastructureIT {

    companion object {
        private lateinit var s3Client: S3Client
        private const val BUCKET = "test-bucket"

        @BeforeAll
        @JvmStatic
        fun setup() {
            s3Client = S3Client.builder()
                .endpointOverride(URI.create(System.getProperty("s3.endpoint")))
                .credentialsProvider(
                    StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(
                            System.getProperty("s3.accessKey"),
                            System.getProperty("s3.secretKey"),
                        )
                    )
                )
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .build()

            try {
                s3Client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build())
            } catch (_: BucketAlreadyOwnedByYouException) {
                // already exists — fine
            }
        }
    }

    @Test
    fun `can upload and download data from s3-compatible store`() {
        val data = "Hello Little Trotter S3".toByteArray()
        val key = "test-file.txt"

        s3Client.putObject(
            PutObjectRequest.builder().bucket(BUCKET).key(key).contentType("text/plain").build(),
            RequestBody.fromBytes(data),
        )

        val downloaded = s3Client.getObjectAsBytes(
            GetObjectRequest.builder().bucket(BUCKET).key(key).build()
        ).asByteArray()

        assertArrayEquals(data, downloaded, "Downloaded data must match uploaded data")
    }
}
