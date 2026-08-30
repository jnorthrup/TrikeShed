package borg.trikeshed.wiki

import borg.trikeshed.platform.CommonResources
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The 1A trainer is a CommonResources asset, not a JVM-only test fixture. */
class HermesWikiTrainerCorpusTest {
    @Test
    fun every1AAssetIsBakedForEveryTarget() {
        for (path in HermesWikiTrainerCorpus.required1AAssets) {
            assertTrue(path in CommonResources.baked, "$path is absent from the generated common bundle")
            assertTrue(assertNotNull(CommonResources.bytes(path), path).isNotEmpty(), "$path is empty")
        }
    }
}
