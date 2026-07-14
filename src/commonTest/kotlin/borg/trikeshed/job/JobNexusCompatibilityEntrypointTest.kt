package borg.trikeshed.job

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * E1 — Compatibility entrypoint convergence RED tests.
 *
 * Existing store/reactor/job-kernel factory functions must delegate to
 * JobNexusFactory, not create independent mutable scopes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JobNexusCompatibilityEntrypointTest {

    @Test
    fun existingStoreFactoryDelegatesToCanonicalFactory() = runTest {
        // The existing CouchStore.create() must internally produce the same
        // component graph as JobNexusFactory.open with equivalent settings.
        val store = borg.trikeshed.couch.CouchStore.create(
            parentScope = this,
            capacity = 64,
        )

        assertTrue(store.delegatedToFactory, "CouchStore.create must delegate to JobNexusFactory")
    }
}

private fun fail(msg: String): Nothing {
    throw AssertionError(msg)
}
