package borg.trikeshed.loom

import kotlin.test.*

/** gcs.rs against the requests and tokens its Rust build produced for the same inputs ([GcsVectors]). */
class LoomGcsJvmTest {
    @Test
    fun serviceAccountTokenIsTheRustBearer() {
        val key = ServiceAccountKey(
            "loom-handoff@operator-a.iam.gserviceaccount.com", "0123456789abcdef0123456789abcdef01234567", GcsVectors.serviceAccountKeyPem,
        )
        assertEquals(GcsVectors.jwtBearer, "Bearer " + key.generate(STORAGE_SCOPE, GcsVectors.jwtNow.toULong()))
    }
}
