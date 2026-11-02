package borg.trikeshed.narsese

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.graal.subvm.CoreNlpRuntime
import borg.trikeshed.job.*
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.ontology.CorpusOntology
import borg.trikeshed.parse.jsonOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.*

class ConstellationCorpusProfileTest {
    @TempDir lateinit var directory: Path

    @Test fun fullSnapshotRejectsMarkerlessReducedRawAndSanitizedAliases() = runBlocking {
        val cas = CasStore.inMemory(); val text = "Staff may provide care."; val cid = cas.put(text.encodeToByteArray())
        val source = DocumentSource(cid, cid, text, "report", "text/plain", "alias-test")
        val ontology = CorpusOntology.retain(cas, "test", "v1", "(subclass Staff Participant)", emptyMap())
        val reducedName = ConstellationNodes.curationBookName("report", ontology.cid, CoreNlpRuntime.Profile.NO_KBP)
        val stem = reducedName.replace(Regex("[^A-Za-z0-9._-]+"), "-")
        val nodes = ConstellationNodes(directory.toFile(), ConfixBlackboard(), scope = this, cas = cas)
        val books = File(nodes.root, "books")
        File(books, "$stem.source").writeText(cid.value)
        File(books, "$stem.sections.jsonl").writeText(jsonOf(text))
        File(books, "$stem.readings/0.json").apply { parentFile.mkdirs(); writeText("[]") }
        val runners = linkedMapOf<String, LcncNodeRunner>(); nodes.register(runners)
        val inputs = _m["source" j DocumentCuratorCodec.source(source), "ontologyCid" j ontology.cid.value]
        try {
            for (alias in s_[reducedName, stem].view) {
                assertFailsWith<IllegalArgumentException> {
                    runners.getValue(ConstellationNodes.SNAPSHOT).run(LcncNode("snapshot", ConstellationNodes.SNAPSHOT, _m["book" j alias]), inputs)
                }
            }
            assertFalse(File(books, "$stem.parser.json").exists())
        } finally { nodes.drain() }
    }

    @Test fun interruptedProjectionPublicationLeavesDurableInvalidMarkerAndLegacyIdentityUnknown() = runBlocking {
        val cas = CasStore.inMemory(); val text = "Staff may provide care."; val cid = cas.put(text.encodeToByteArray())
        val source = DocumentSource(cid, cid, text, "report", "text/plain", "interruption-test")
        val ontology = CorpusOntology.retain(cas, "test", "v1", "(subclass Staff Participant)", emptyMap())
        val books = File(directory.toFile(), "constellations/books").apply { mkdirs() }
        File(books, "report.source").writeText(cid.value)
        File(books, "report.sections.jsonl").writeText(jsonOf(text))
        File(books, "report.readings/0.json").apply { parentFile.mkdirs(); writeText("[]") }
        val outputName = ConstellationNodes.curationBookName("report", ontology.cid)
        val stem = outputName.replace(Regex("[^A-Za-z0-9._-]+"), "-")
        File(books, "$stem.source").writeText(cid.value)
        File(books, "$stem.sections.jsonl").writeText(jsonOf(text))
        File(books, "$stem.parser.json").writeText(jsonOf(_m["state" j "complete", "profile" j "full-v1",
            "configuration" j CoreNlpRuntime.Profile.FULL.configuration, "extractedTextCid" j cid.value]))
        val actual = borg.trikeshed.userspace.nio.file.spi.JvmFileOperations()
        var interrupt = true
        val adapter = object : borg.trikeshed.userspace.nio.file.spi.FileOperations by actual {
            override fun writeAtomically(filename: String, bytes: ByteArray) {
                actual.writeAtomically(filename, bytes)
                if (interrupt && filename == File(books, "$stem.readings/0.json").absolutePath) error("interrupted after first projection")
            }
        }
        val nodes = ConstellationNodes(directory.toFile(), ConfixBlackboard(), files = adapter, scope = this, cas = cas)
        val runners = linkedMapOf<String, LcncNodeRunner>(); nodes.register(runners)
        val inputs = _m["source" j DocumentCuratorCodec.source(source), "ontologyCid" j ontology.cid.value]
        try {
            assertFailsWith<IllegalStateException> {
                runners.getValue(ConstellationNodes.CURATE).run(LcncNode("curate", ConstellationNodes.CURATE, _m["book" j "report"]), inputs)
            }
            val marker = borg.trikeshed.parse.reifyMap(File(books, "$stem.parser.json").readText())
            assertEquals("in-progress", marker["state"])
            assertEquals(cid.value, File(books, "$stem.source").readText())
            assertFailsWith<IllegalArgumentException> {
                runners.getValue(ConstellationNodes.SNAPSHOT).run(LcncNode("snapshot", ConstellationNodes.SNAPSHOT, _m["book" j outputName]), inputs)
            }
            interrupt = false
            val output = runners.getValue(ConstellationNodes.CURATE).run(LcncNode("curate", ConstellationNodes.CURATE, _m["book" j "report"]), inputs)
            val provenance = output["parserProvenance"] as Map<*, *>
            assertEquals("unknown-retained", provenance["profile"])
            assertEquals(emptyMap<String, String>(), provenance["configuration"])
            assertEquals("full-v1", provenance["selectedProfile"])
            assertEquals(true, provenance["retainedProjectionIdentityUnverified"])
            val receipt = BookCurationReceipt.read(cas, ContentId(output["curationReceiptCid"] as String))
            assertEquals(provenance, receipt["parserProvenance"])
            val completed = borg.trikeshed.parse.reifyMap(File(books, "$stem.parser.json").readText())
            assertEquals("complete", completed["state"])
            assertEquals("unknown-retained", completed["profile"])
        } finally { nodes.drain() }
    }

    @Test fun reducedProfileParsesFreshKeepsFullReadingsAndRejectsSnapshots() = runBlocking {
        val cas = CasStore.inMemory()
        val text = "Staff must deliver notice."
        val cid = cas.put(text.encodeToByteArray())
        val source = DocumentSource(cid, cid, text, "report", "text/plain", "profile-test")
        val ontology = CorpusOntology.retain(cas, "test", "v1", "(subclass Staff Participant)", _m["staff" j _l["Staff"]])
        val reduced = ConstellationNodes(directory.toFile(), ConfixBlackboard(), scope = this, cas = cas,
            corpusParserProfile = CoreNlpRuntime.Profile.NO_KBP)
        val full = ConstellationNodes(directory.toFile(), ConfixBlackboard(), scope = this, cas = cas)
        val books = File(reduced.root, "books")
        val legacy = _m[
            "report.source" j cid.value,
            "report.sections.jsonl" j "malformed legacy section projection",
            "report.parser.json" j "malformed legacy parser marker",
            "report.readings/0.json" j jsonOf(_l[_m["text" j text, "statements" j _l[_m[
                "bearer" j "staff", "force" j "must", "action" j "destroy", "object" j "files"]]]])]
        for ((path, value) in legacy) File(books, path).apply { parentFile.mkdirs(); writeText(value) }
        val expectedFull = "report@${ontology.cid.hex.take(16)}"
        val expectedReduced = reduced.curationBookName("report", ontology.cid)
        val sentinel = File(books, "${expectedFull.replace(Regex("[^A-Za-z0-9._-]+"), "-")}.source").apply { writeText("FULL marker preserved") }
        val runners = linkedMapOf<String, LcncNodeRunner>(); reduced.register(runners)
        val fullRunners = linkedMapOf<String, LcncNodeRunner>(); full.register(fullRunners)
        val inputs = _m["source" j DocumentCuratorCodec.source(source), "ontologyCid" j ontology.cid.value]
        fun node(kind: String, book: String = "report") = LcncNode("profile", kind, _m["book" j book])
        try {
            assertEquals(expectedFull, full.curationBookName("report", ontology.cid))
            assertNotEquals(expectedFull, expectedReduced)
            assertEquals(CoreNlpRuntime.Profile.NO_KBP, reduced.forProvider(DecisionProvider.JEVA).corpusParserProfile)
            assertFailsWith<IllegalArgumentException> { runners.getValue(ConstellationNodes.SNAPSHOT).run(node(ConstellationNodes.SNAPSHOT), inputs) }
            val output = runners.getValue(ConstellationNodes.CURATE).run(node(ConstellationNodes.CURATE), inputs)
            assertEquals(expectedReduced, output["book"])
            assertEquals(false, output["retainedParser"])
            val provenance = output["parserProvenance"] as Map<*, *>
            assertEquals("no-kbp-v1", provenance["profile"])
            assertEquals(CoreNlpRuntime.Profile.NO_KBP.configuration, provenance["configuration"])
            val receipt = BookCurationReceipt.read(cas, ContentId(output["curationReceiptCid"] as String))
            assertEquals(provenance, receipt["parserProvenance"])
            val relationships = receipt["relationships"] as List<*>
            assertTrue(relationships.isNotEmpty())
            for (value in relationships) {
                val row = value as Map<*, *>
                val derivation = row["derivation"] as Map<*, *>
                val parser = CanonicalCbor.decodeMap(requireNotNull(cas.get(ContentId(derivation["parserReceiptCid"] as String))))
                val metadata = parser["metadata"] as Map<*, *>
                assertEquals("no-kbp-v1", (metadata["runtime"] as Map<*, *>)["profile"])
            }
            for ((path, value) in legacy) assertEquals(value, File(books, path).readText())
            assertEquals("FULL marker preserved", sentinel.readText())
            assertFailsWith<IllegalArgumentException> {
                runners.getValue(ConstellationNodes.SNAPSHOT).run(node(ConstellationNodes.SNAPSHOT, expectedReduced), inputs)
            }
            assertFailsWith<IllegalArgumentException> {
                fullRunners.getValue(ConstellationNodes.SNAPSHOT).run(node(ConstellationNodes.SNAPSHOT, expectedReduced), inputs)
            }
        } finally { reduced.drain(); full.drain() }
    }

    @Test fun fullSnapshotRejectsMalformedOrReducedParserMarkers() = runBlocking {
        val cas = CasStore.inMemory(); val text = "Staff may provide care."; val cid = cas.put(text.encodeToByteArray())
        val source = DocumentSource(cid, cid, text, "report", "text/plain", "profile-test")
        val ontology = CorpusOntology.retain(cas, "test", "v1", "(subclass Staff Participant)", emptyMap())
        val nodes = ConstellationNodes(directory.toFile(), ConfixBlackboard(), scope = this, cas = cas)
        val books = File(nodes.root, "books")
        File(books, "report.source").writeText(cid.value)
        File(books, "report.sections.jsonl").writeText(jsonOf(text))
        File(books, "report.readings/0.json").apply { parentFile.mkdirs(); writeText("[]") }
        val runners = linkedMapOf<String, LcncNodeRunner>(); nodes.register(runners)
        val node = LcncNode("snapshot", ConstellationNodes.SNAPSHOT, _m["book" j "report"])
        val inputs = _m["source" j DocumentCuratorCodec.source(source), "ontologyCid" j ontology.cid.value]
        try {
            for (marker in s_["not-json", jsonOf(_m["profile" j "no-kbp-v1", "configuration" j CoreNlpRuntime.Profile.NO_KBP.configuration,
                "extractedTextCid" j cid.value])].view) {
                File(books, "report.parser.json").writeText(marker)
                assertFailsWith<IllegalArgumentException> { runners.getValue(ConstellationNodes.SNAPSHOT).run(node, inputs) }
            }
            assertFalse(File(books, "report@${ontology.cid.hex.take(16)}.curation").exists())
        } finally { nodes.drain() }
    }
}
