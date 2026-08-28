package muxcontract

import borg.trikeshed.htx.HtxMethod
import borg.trikeshed.htx.openHtxElement
import borg.trikeshed.lib.get
import borg.trikeshed.lib.left
import borg.trikeshed.lib.right
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toList
import keymux.ApiSource
import keymux.CachedKeySource
import keymux.EnvSource
import keymux.EnvVarSource
import keymux.FixedKeySource
import keymux.HarnessSource
import keymux.KeyMux
import keymux.LeaseMetadata
import keymux.PersistSource
import keymux.ReactorSource
import keymux.TestKeySource
import keymux.pathMatch
import keymux.toKeyPath
import borg.trikeshed.userspace.reactor.MuxCredentialRecord
import borg.trikeshed.userspace.reactor.MuxReactorElement
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * KeyMux behavior contract — invariants K1–K10 in docs/mux-repair-contract.md,
 * pinned with mocks. Reconstruction spec: rebuild sources until this passes.
 */
class KeyMuxContractTest {

    // K1: first-wins resolution; getWithSource names the source.
    @Test
    fun k1_firstWinsAndSourceNaming() = runTest {
        val mux = KeyMux {
            bind("llm.a.key", TestKeySource(name = "first", value = "v1"))
            bind("llm.a.key", TestKeySource(name = "second", value = "v2"))
        }
        assertEquals("v1", mux.get("llm.a.key"))
        val withSource = mux.getWithSource("llm.a.key")
        assertEquals("v1", withSource.a)
        assertEquals("first", withSource.b)
        // Unbound path resolves to null j "none".
        val missing = mux.getWithSource("no.such.key")
        assertNull(missing.a)
        assertEquals("none", missing.b)
    }

    // K2: wildcard resolution matches one segment, exact count.
    @Test
    fun k2_wildcardResolution() = runTest {
        val mux = KeyMux {
            bind("llm.*.key", TestKeySource(name = "wild", value = "w"))
            bind("llm.special.key", TestKeySource(name = "exact", value = "e"))
        }
        // Exact binding listed second still loses to nothing — first-wins across the
        // whole binding list, so order matters: exact must be bound BEFORE wildcard
        // to win. This pins the actual precedence rule.
        val ordered = KeyMux {
            bind("llm.special.key", TestKeySource(name = "exact", value = "e"))
            bind("llm.*.key", TestKeySource(name = "wild", value = "w"))
        }
        assertEquals("e", ordered.get("llm.special.key"), "exact binding first wins")
        assertEquals("w", ordered.get("llm.anything.key"), "wildcard fills the rest")
        // Wildcard does not span segments.
        assertNull(ordered.get("llm.a.b.key"), "* matches exactly one segment")
    }

    // K2 (top-level pathMatch): ':'-variables, segment equality, case sensitivity.
    @Test
    fun k2_topLevelPathMatch() {
        assertTrue(pathMatch("users/:id/profile", "users/123/profile"))
        assertTrue(!pathMatch("users/:id/profile", "users/123/settings"))
        assertTrue(!pathMatch("api/users", "api/users/"))
        assertTrue(!pathMatch("api/users", "API/users"))
        assertTrue(!pathMatch("api//users", "api/users"))
    }

    // K3: set() writes to the first WRITABLE matching source.
    @Test
    fun k3_setSkipsReadOnlySources() = runTest {
        val files = FakeFiles()
        val writable = TestKeySource(value = "old")
        val mux = KeyMux {
            bind("cfg.x", EnvVarSource("DEFINITELY_MISSING_ENV_VAR")) // read-only
            bind("cfg.x", writable)                                    // writable
        }
        mux.set("cfg.x", "new")
        assertEquals("new", writable.value, "write lands on the writable source")
        assertFailsWith<IllegalStateException> {
            KeyMux { bind("cfg.y", EnvSource()) }.set("cfg.y", "v")
        }
        files // unused here; persist covered below
    }

    // K4: PersistSource round-trip + reload through a fresh instance.
    @Test
    fun k4_persistRoundTripAndReload() = runTest {
        val files = FakeFiles()
        val p1 = PersistSource(root = "/cfg", explicitFileOps = files)
        val key = "db.pass".toKeyPath()
        assertNull(p1.read(key))
        p1.write(key, "s3cret")
        assertEquals("s3cret", p1.read(key), "cached read after write")
        p1.invalidate()
        assertEquals("s3cret", p1.read(key), "reload from disk after invalidate")
        // A second source instance over the same root sees the persisted value.
        val p2 = PersistSource(root = "/cfg", explicitFileOps = files)
        assertEquals("s3cret", p2.read(key), "persisted value survives source instances")
        assertTrue(files.exists("/cfg/keymux.conf"))
    }

    // K5: CachedKeySource caches per path; write passes through and drops the entry.
    // Counting rides an ApiSource over the scripted transport (KeySource is sealed —
    // external packages cannot subclass it; the transport counter is the probe).
    @Test
    fun k5_cachedSourceTtlWriteThroughAndInvalidate() = runTest {
        var gets = 0
        var stored = "v1"
        val htx = openHtxElement(routeService = object : borg.trikeshed.htx.HtxRouteService {
            override suspend fun exchange(state: borg.trikeshed.htx.HtxExchangeState, request: borg.trikeshed.htx.HtxRequest): borg.trikeshed.htx.HtxExchangeResult {
                if (request.method == HtxMethod.PUT) stored = request.body.toArray().decodeToString()
                else gets++
                val resp = borg.trikeshed.htx.HtxResponse(200, borg.trikeshed.lib.ByteSeries(stored.encodeToByteArray()))
                return borg.trikeshed.htx.HtxExchangeResult(state.copy(lifecycle = borg.trikeshed.htx.HtxExchangeLifecycle.RESPONDED, request = request, response = resp))
            }
        })
        val cached = CachedKeySource(ApiSource(baseUrl = "https://keys.internal", explicitHtx = htx), ttlMs = 60_000)
        val key = "k".toKeyPath()
        assertEquals("v1", cached.read(key))
        assertEquals("v1", cached.read(key))
        assertEquals(1, gets, "second read is served from cache")
        stored = "v2"
        assertEquals("v1", cached.read(key), "TTL not expired → stale value served")
        cached.invalidate()
        assertEquals("v2", cached.read(key), "invalidate forces a re-read")
        assertEquals(2, gets)
        cached.write(key, "v3")
        assertEquals("v3", stored, "write passes through")
        assertEquals("v3", cached.read(key), "write drops the cache entry")
        assertEquals(3, gets)
        htx.close()
    }

    // K6: ReactorSource answers only llm.*.key paths, ACTIVE only.
    @Test
    fun k6_reactorSourceContract() = runTest {
        val reactor = MuxReactorElement()
        reactor.open()
        reactor.loadCredentialPool(mapOf(
            "openai" to listOf(MuxCredentialRecord(id = "«redacted:sk-…»", lastStatus = "active", lastSuccessModel = "gpt-4")),
        ))
        val source = ReactorSource(explicitReactor = reactor)
        assertEquals("«redacted:sk-…»", source.read("llm.gpt-4.key".toKeyPath()), "matches lastSuccessModel")
        assertEquals("«redacted:sk-…»", source.read("llm.default.key".toKeyPath()), "default = first ACTIVE")
        assertNull(source.read("db.pass".toKeyPath()), "non-llm path refused")
        assertNull(source.read("llm.gpt-4.secret".toKeyPath()), "non-.key suffix refused")
        reactor.close()
    }

    // K7: rotate() promotes the next persist candidate; no candidates → null.
    @Test
    fun k7_rotatePromotesNextCandidate() = runTest {
        val filesA = FakeFiles()
        val filesB = FakeFiles()
        val pA = PersistSource(root = "/a", explicitFileOps = filesA)
        val pB = PersistSource(root = "/b", explicitFileOps = filesB)
        pA.write("jules.default.key".toKeyPath(), "key-1")
        pB.write("jules.default.key".toKeyPath(), "key-2")
        val mux = KeyMux {
            bind("jules.default.key", pA)
            bind("jules.default.key", pB)
        }
        val rotated = mux.rotate("jules.default.key")
        assertEquals("key-2", rotated, "second candidate becomes the new value")
        assertEquals("key-2", mux.get("jules.default.key"), "first-wins now reads the rotated value")
        // Rotation with no candidates is a no-op returning null.
        val untouched = KeyMux { bind("x.y", TestKeySource(value = "keep")) }
        assertNull(untouched.rotate("x.y"))
        assertEquals("keep", untouched.get("x.y"), "no-candidate rotation retains the current value")
    }

    // K8: activeLeases is a lazy Series2 (Join, not Pair); indexing drives leaseVisits.
    @Test
    fun k8_activeLeasesIsLazySeries2() = runTest {
        val mux = KeyMux { bind("a.b", TestKeySource()) }
        val before = mux.leaseVisits
        val leases = mux.activeLeases
        assertEquals(0, leases.size)
        assertEquals(before, mux.leaseVisits, "empty view visits nothing")
        mux.setLeaseForTest("k1", LeaseMetadata(leasedTo = "task-1", leaseExpiresAt = 123L))
        val view = mux.activeLeases
        assertEquals(1, view.size)
        val entry = view[0]
        assertEquals("k1", entry.a)          // Join.a, not Pair.first
        assertEquals("task-1", entry.b.leasedTo)
        // left/right projections exist (Series2 algebra).
        assertEquals("k1", mux.activeLeases.left[0])
        assertEquals("task-1", mux.activeLeases.right[0].leasedTo)
    }

    // K9: watch() is an explicit empty flow — never throws, never emits.
    @Test
    fun k9_watchIsExplicitEmptyFlow() = runTest {
        val mux = KeyMux { bind("a.b", TestKeySource()) }
        var emitted = 0
        mux.watch("a").collect { emitted++ }
        assertEquals(0, emitted)
    }

    // K3/K4 glue through the public mux: set() routes into persist, list() filters prefixes.
    @Test
    fun k3k4_setThroughMuxAndListPrefix() = runTest {
        val files = FakeFiles()
        val mux = KeyMux { persist("/cfg") }
        withContext(coroutineContext + files) {
            mux.set("llm.a.key", "va")
            mux.set("llm.b.key", "vb")
            mux.set("db.pass", "vp")
            assertEquals("va", mux.get("llm.a.key"))
            val listed = mux.list("llm.")
            assertEquals(2, listed.size)
            val keys = (0 until listed.size).map { listed[it].a }.toSet()
            assertEquals(setOf("llm.a.key", "llm.b.key"), keys)
        }
    }

    // ApiSource: GET reads, PUT writes, through the scripted transport.
    @Test
    fun apiSourceReadsAndWritesThroughHtx() = runTest {
        val puts = mutableListOf<String>()
        val htx = openHtxElement(routeService = object : borg.trikeshed.htx.HtxRouteService {
            override suspend fun exchange(state: borg.trikeshed.htx.HtxExchangeState, request: borg.trikeshed.htx.HtxRequest): borg.trikeshed.htx.HtxExchangeResult {
                if (request.method == HtxMethod.PUT) puts.add(request.body.toArray().decodeToString())
                val resp = borg.trikeshed.htx.HtxResponse(200, borg.trikeshed.lib.ByteSeries("api-value".encodeToByteArray()))
                return borg.trikeshed.htx.HtxExchangeResult(state.copy(lifecycle = borg.trikeshed.htx.HtxExchangeLifecycle.RESPONDED, request = request, response = resp))
            }
        })
        val api = ApiSource(baseUrl = "https://keys.internal", explicitHtx = htx)
        assertEquals("api-value", api.read("some.path".toKeyPath()))
        api.write("some.path".toKeyPath(), "written")
        assertEquals(listOf("written"), puts)
        htx.close()
    }

    // ── Harness source (K11–K16): env + hermes dotenv + codex/opencode auth.json ──

    /** Fake env + fake home dir wiring for HarnessSource tests. */
    private fun harness(
        files: FakeFiles,
        env: Map<String, String> = emptyMap(),
        hermesHome: String = "/home/t/.hermes",
        homeDir: String = "/home/t",
    ) = HarnessSource(
        explicitFileOps = files,
        getenv = { env[it] },
        hermesHomeOverride = hermesHome,
        homeDirOverride = homeDir,
    )

    // K11: bare "*" binding is the global fallback — multi-segment paths resolve.
    @Test
    fun k11_globalWildcardMatchesMultiSegmentPaths() = runTest {
        val files = FakeFiles()
        val src = harness(files, env = mapOf("OPENAI_API_KEY" to "sk-openai-env"))
        val mux = KeyMux { bind("*", src) }
        assertEquals("sk-openai-env", mux.get("llm.openai.key"), "global wildcard must answer llm.<provider>.key")
        assertEquals("sk-openai-env", mux.get("openai.default.key"), "provider.default.key convention")
        assertEquals("sk-openai-env", mux.get("OPENAI_API_KEY"), "raw env name")
    }

    // K12: hermes dotenv secrets resolve ($HERMES_HOME first, then ~/.hermes, then profiles).
    @Test
    fun k12_hermesDotenvResolutionOrder() = runTest {
        val files = FakeFiles()
        files.files["/home/t/.hermes/.env"] = "DEEPSEEK_API_KEY=sk-deep-root\n".encodeToByteArray()
        files.files["/home/t/.hermes/profiles/work/.env"] = "DEEPSEEK_API_KEY=sk-deep-profile\n".encodeToByteArray()
        files.files["/work-profile/.env"] = "XAI_API_KEY=sk-xai-active\n".encodeToByteArray()

        // Active hermes home ($HERMES_HOME) answers its own .env first.
        val active = harness(files, hermesHome = "/work-profile")
        assertEquals("sk-xai-active", active.read("llm.xai.key".toKeyPath()))
        // Root .env wins over sibling profiles.
        val rootFirst = harness(files, hermesHome = "/home/t/.hermes")
        assertEquals("sk-deep-root", rootFirst.read("llm.deepseek.key".toKeyPath()))
        // A key present only in a profile still resolves through the profile lane.
        files.files["/home/t/.hermes/profiles/work/.env"] = "GROQ_API_KEY=sk-groq-profile\n".encodeToByteArray()
        val profileLane = HarnessSource(
            explicitFileOps = files, getenv = { null },
            hermesHomeOverride = "/home/t/.hermes", homeDirOverride = "/home/t",
        )
        assertEquals("sk-groq-profile", profileLane.read("llm.groq.key".toKeyPath()))
    }

    // K13: codex auth.json + opencode auth.json extraction; hermes auth.json generic shapes.
    @Test
    fun k13_harnessCredentialFiles() = runTest {
        val files = FakeFiles()
        files.files["/home/t/.codex/auth.json"] = """{"OPENAI_API_KEY": "sk-codex"}""".encodeToByteArray()
        files.files["/home/t/.local/share/opencode/auth.json"] =
            """{"anthropic": {"type": "api", "key": "sk-opencode"}}""".encodeToByteArray()
        files.files["/home/t/.hermes/auth.json"] =
            """{"xai": {"apiKey": "sk-hermes-pool"}, "claudeAiOauth": {"accessToken": "oauth-not-an-api-key"}}""".encodeToByteArray()
        val src = harness(files)
        assertEquals("sk-codex", src.read("llm.openai.key".toKeyPath()), "codex top-level env-var field")
        assertEquals("sk-opencode", src.read("llm.anthropic.key".toKeyPath()), "opencode per-provider key")
        assertEquals("sk-hermes-pool", src.read("llm.xai.key".toKeyPath()), "hermes auth.json provider apiKey")
        // OAuth access tokens are never extracted as api keys.
        assertNull(src.read("llm.claudeAiOauth.key".toKeyPath()), "OAuth accessToken is not an api key")
    }

    // K14: base_url — env override beats registry default.
    @Test
    fun k14_baseUrlOverrideAndDefaults() = runTest {
        val files = FakeFiles()
        assertEquals("https://api.openai.com/v1", harness(files).read("llm.openai.base_url".toKeyPath()))
        assertEquals("https://api.moonshot.ai/v1", harness(files).read("llm.moonshot.base_url".toKeyPath()))
        val overridden = harness(files, env = mapOf("OPENAI_BASE_URL" to "http://localhost:8080/v1"))
        assertEquals("http://localhost:8080/v1", overridden.read("llm.openai.base_url".toKeyPath()))
        // Unknown provider, no override anywhere → null (never a fabricated URL).
        assertNull(harness(files).read("llm.bogus.base_url".toKeyPath()))
    }

    // K15: custom host-style integration via HERMES_CUSTOM_<ID>_API_KEY (dash-form id).
    @Test
    fun k15_customHostIntegration() = runTest {
        val files = FakeFiles()
        files.files["/home/t/.hermes/.env"] = (
            "HERMES_CUSTOM_API_SYNTHETIC_NEW_API_KEY=sk-synthetic-custom\n" +
                "HERMES_CUSTOM_API_SYNTHETIC_NEW_BASE_URL=https://api.synthetic.new/v1\n"
            ).encodeToByteArray()
        val src = harness(files)
        assertEquals("sk-synthetic-custom", src.read("llm.api-synthetic-new.key".toKeyPath()),
            "dash-form custom id resolves the HERMES_CUSTOM_ var from hermes .env")
        assertEquals("https://api.synthetic.new/v1", src.read("llm.api-synthetic-new.base_url".toKeyPath()),
            "paired HERMES_CUSTOM_ base URL")
        // Registry providers are unaffected by the custom lane.
        assertNull(src.read("llm.synthetic.key".toKeyPath()), "registry id without env/dotenv stays null")
    }

    // K16: harness is read-only and degrades gracefully without FileOperations.
    @Test
    fun k16_readOnlyAndDegradesWithoutFileOps() = runTest {
        val src = HarnessSource(getenv = { null }, hermesHomeOverride = "/nope", homeDirOverride = "/nope")
        // No env, no files, no context FileOperations → null, never throws.
        assertNull(src.read("llm.openai.key".toKeyPath()))
        assertFailsWith<UnsupportedOperationException> {
            src.write("llm.openai.key".toKeyPath(), "x")
        }
    }
}
