package borg.trikeshed.forge.server

import borg.trikeshed.agent.keymux.KeyMux
import borg.trikeshed.agent.keymux.LlmAuth
import borg.trikeshed.agent.modelmux.BreadcrumbReelStore
import borg.trikeshed.agent.modelmux.BreadcrumbStore
import borg.trikeshed.agent.modelmux.ContextReadPolicy
import borg.trikeshed.agent.modelmux.ModelMux
import borg.trikeshed.agent.modelmux.SharedContextElement
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.CasStore
import borg.trikeshed.lib.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Which evidence context a reel run reads through: the root's one shared owner when it truly applies, otherwise its own. */
class BreadcrumbReelServiceContextTest {
    private fun archiveOver(cas: CasStore): BreadcrumbReelStore {
        val backing = linkedMapOf<String, String>()
        val persistence = object : BreadcrumbStore {
            override suspend fun load(): Series<Join<String, String>> = backing.entries.map { it.key j it.value }.toSeries()
            override suspend fun write(id: String, state: String?) { if (state == null) backing.remove(id) else backing[id] = state }
        }
        return BreadcrumbReelStore(persistence, cas)
    }

    private fun catalog() = ModelMux(KeyMux {}) {
        model("m", setOf("chat"), baseUrl = "https://fixture.invalid", auth = LlmAuth.NONE)
        defaultModel("m")
    }

    private fun service(scope: CoroutineScope, context: CoroutineContext, archive: BreadcrumbReelStore?) =
        BreadcrumbReelService(scope, context, ConfixBlackboard.empty(), "/nonexistent-repo", { catalog() }, archive = archive)

    @Test
    fun childrenOfOneRootReadThroughOneOwner() = runBlocking {
        val cas = CasStore.inMemory()
        val element = SharedContextElement.open("reel-test", cas, null, ContextReadPolicy(allowedScopes = setOf("reel/*")))
        try {
            val service = service(this, coroutineContext + element, archiveOver(cas))
            assertSame(element.context, service.contextFor("ace-1"))
            assertSame(element.context, service.contextFor("ace-2"), "a second run reads through the same owner, so identical misses coalesce")
        } finally {
            element.drain()
        }
    }

    @Test
    fun anElementThatDoesNotServeTheScopeOrReadsAnotherStoreIsNeverBorrowed() = runBlocking {
        val cas = CasStore.inMemory()
        val wrongScopes = SharedContextElement.open("a", cas, null, ContextReadPolicy(allowedScopes = setOf("team/a")))
        val otherStore = SharedContextElement.open("b", CasStore.inMemory(), null, ContextReadPolicy(allowedScopes = setOf("reel/*")))
        try {
            for (element in listOf(wrongScopes, otherStore)) {
                val own = service(this, coroutineContext + element, archiveOver(cas)).contextFor("ace-1")!!
                assertNotSame(element.context, own)
                assertTrue(own.policy.serves("reel/ace-1"))
                assertFalse(own.policy.serves("reel/ace-2"), "a private context serves only its own run")
                assertTrue(own.usesStore(cas), "and it reads and writes the archive's own store")
            }
        } finally {
            wrongScopes.drain(); otherStore.drain()
        }
    }

    @Test
    fun withoutAnElementOrAnArchiveBehaviourIsUnchanged() = runBlocking {
        val cas = CasStore.inMemory()
        val plain = service(this, coroutineContext, archiveOver(cas))
        val one = plain.contextFor("ace-1")!!
        assertNotSame(one, plain.contextFor("ace-1"), "private contexts are made per run, as before")
        assertTrue(one.usesStore(cas))
        assertNull(service(this, coroutineContext, null).contextFor("ace-1"), "no archive, no evidence store")
    }
}
