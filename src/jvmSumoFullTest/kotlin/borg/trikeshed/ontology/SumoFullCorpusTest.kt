package borg.trikeshed.ontology

import borg.trikeshed.kif.KifExpr
import borg.trikeshed.lib.*
import java.io.InputStream
import java.security.MessageDigest
import kotlin.test.*

/** Explicit sumoFullTest suite; its resources never enter the default JVM classpath. */
class SumoFullCorpusTest {
    @Test
    fun middleIdentityAndIds() {
        val middle = SumoCorpus.middle
        assertSame(SumoCorpus.pinned, middle)
        val before = fingerprint(middle)
        println("[SUMO middle] fingerprint=$before stats=${middle.stats}")
        // Recorded from the unchanged classifier before the full loader/parser changes.
        assertEquals("d034178a1f00af820394d8129dc89193aaff4d4669b2e485163d1b01578d0799", before)
        assertNotSame(middle, SumoCorpus.full)
        assertEquals(before, fingerprint(middle))
        assertEquals(15_551, middle.stats["forms"])
        assertEquals(11, middle.stats["unprojectedTaxonomyForms"])
    }

    @Test
    fun completeFilesAndForms() {
        assertEquals(78, SumoCorpus.fullFiles.size)
        assertEquals(78, SumoCorpus.fullFiles.view.toSet().size)
        assertTrue("sumo/full/FOAFmap.kif" in SumoCorpus.fullFiles)
        assertTrue("sumo/full/mappings/FOAFmap.kif" in SumoCorpus.fullFiles)
        assertNotEquals(SumoCorpus.text("sumo/full/FOAFmap.kif"), SumoCorpus.text("sumo/full/mappings/FOAFmap.kif"))
        var count = 0
        for (file in SumoCorpus.fullFiles.view) {
            val text = SumoCorpus.text(file)
            val forms = KifExpr.parseAll(text, strict = true)
            assertTrue(forms.isNotEmpty(), file)
            assertTrue(forms.all { it is KifExpr.ListExpr }, file)
            assertEquals(formCount(text), forms.size, file)
            count += forms.size
        }
        assertEquals(201_007, count)
        val full = SumoCorpus.full
        println("[SUMO full] stats=${full.stats} shapes=${full.shapeHistogram()}")
        assertEquals(count, full.stats["forms"])
        assertEquals(30_735, full.termCount)
        assertEquals(9_059, full.classCount)
        assertEquals(10_356, full.stats["subclassEdges"])
        assertEquals(22_835, full.stats["instanceEdges"])
        assertEquals(69, full.stats["unprojectedTaxonomyForms"])
        assertEquals(count, full.stats.getValue("taxonomyForms") + full.stats.getValue("unprojectedTaxonomyForms") +
            full.stats.getValue("rules") + full.stats.getValue("otherForms"))
    }

    @Test
    fun domainOntologyCoverage() {
        for ((term, ancestor) in s_[
            "ToyotaPrius" j "Vehicle",
            "Violin" j "Artifact",
            "CoffeeMaking" j "Process",
            "HeartDisease" j "DiseaseOrSyndrome",
            "AachenAirport" j "Region",
            "BrailleElevator" j "Device",
        ].view) {
            assertEquals(-1, SumoCorpus.middle.termId(term), term)
            assertTrue(SumoCorpus.full.isA(term, ancestor), "$term is a $ancestor")
        }
        assertFalse(SumoCorpus.full.subclassOf("ToyotaPrius", "Process"))
        assertTrue(SumoCorpus.full.disjoint("ToyotaPrius", "Abstract"))
    }

    @Test
    fun domainAndRangeProjection() {
        for ((sumo, files) in s_[SumoCorpus.middle j SumoCorpus.FILES.toSeries(), SumoCorpus.full j SumoCorpus.fullFiles].view) {
            val domains = mutableMapOf<String, Twin<String>>()
            val ranges = mutableMapOf<String, Twin<String>>()
            for (file in files.view) for (form in KifExpr.parseAll(SumoCorpus.text(file))) {
                val args = (form as KifExpr.ListExpr).elements
                if (args.any { it !is KifExpr.Atom }) continue
                val head = (args[0] as KifExpr.Atom).token
                when (head) {
                    "domain", "domainSubclass" -> domains["${(args[1] as KifExpr.Atom).token}/${(args[2] as KifExpr.Atom).token}"] = (args[3] as KifExpr.Atom).token j head
                    "range", "rangeSubclass" -> ranges[(args[1] as KifExpr.Atom).token] = (args[2] as KifExpr.Atom).token j head
                }
            }
            assertEquals(domains.mapValues { it.value.a }, sumo.domainSlots.view.associate { it.a to it.b })
            assertEquals(ranges.mapValues { it.value.a }, sumo.rangeSlots.view.associate { it.a to it.b })
            for ((slot, declaration) in domains) {
                val predicate = slot.substringBeforeLast('/')
                val arg = slot.substringAfterLast('/').toInt()
                assertEquals(declaration.a, sumo.domainOf(predicate, arg))
                assertEquals(declaration.b == "domainSubclass", sumo.domainIsSubclass(predicate, arg))
            }
            for ((slot, declaration) in ranges) {
                assertEquals(declaration.a, sumo.rangeOf(slot))
                assertEquals(declaration.b == "rangeSubclass", sumo.rangeIsSubclass(slot))
            }
        }
    }

    @Test
    fun missingAndCorruptResourcesAreFatal() {
        val manifest = "sumo/full/corpus.pins"
        val last = SumoCorpus.fullFiles[SumoCorpus.fullFiles.size - 1]
        for (resource in s_[manifest, last].view) {
            val failure = assertFailsWith<IllegalStateException> { SumoCorpus.full(resources(mapOf(resource to null))) }
            assertTrue(failure.message.orEmpty().contains(if (resource == manifest) "unavailable" else resource))
        }
        val corrupt = SumoCorpus.text(last).encodeToByteArray() + byteArrayOf(10)
        val failure = assertFailsWith<IllegalStateException> { SumoCorpus.full(resources(mapOf(last to corrupt))) }
        assertTrue(failure.message.orEmpty().contains("checksum mismatch: $last"))
    }

    @Test
    fun incompleteOrInvalidManifestIsFatal() {
        val resource = "sumo/full/corpus.pins"
        val manifest = SumoCorpus.text(resource)
        val lastRow = manifest.lineSequence().last { it.isNotBlank() && !it.startsWith('#') }
        for (invalid in s_[
            "",
            manifest.replace(lastRow, ""),
            manifest + "\n$lastRow\n",
            manifest.replace("Merge.kif", "../Merge.kif"),
            manifest.replace(Regex("(?m)^# ref = .*"), "# unpinned"),
        ].view) assertFailsWith<IllegalStateException> {
            SumoCorpus.full(resources(mapOf(resource to invalid.encodeToByteArray())))
        }
    }

    @Test
    fun malformedKifIsFatalEvenWithMatchingChecksum() {
        val file = SumoCorpus.fullFiles[0]
        val malformed = "(subclass Truncated Entity".encodeToByteArray()
        val sha = MessageDigest.getInstance("SHA-256").digest(malformed).joinToString("") { "%02x".format(it) }
        val manifest = SumoCorpus.text("sumo/full/corpus.pins").replace(Regex("(?m)^[0-9a-f]{64}(\\s+Merge\\.kif)$"), "$sha$1")
        val failure = assertFailsWith<IllegalStateException> {
            SumoCorpus.full(resources(mapOf("sumo/full/corpus.pins" to manifest.encodeToByteArray(), file to malformed)))
        }
        assertTrue(failure.message.orEmpty().contains("Invalid full SUMO KIF in $file"), failure.message)
    }

    fun resources(overrides: Map<String, ByteArray?>): ClassLoader = object : ClassLoader(SumoCorpus::class.java.classLoader) {
        override fun getResourceAsStream(name: String): InputStream? =
            if (name in overrides) overrides[name]?.inputStream() else super.getResourceAsStream(name)
    }

    @Test
    fun closureEqualsGraphReachability() {
        // This shares the pinned source bytes and KifExpr parser with the loader.
        // Adjacency extraction and breadth-first traversal use ordinary sets;
        // they do not call the classifier builder, ClosureIndex, or Roaring union.
        val full = SumoCorpus.full
        val parents = mutableMapOf<String, MutableSet<String>>()
        val types = mutableMapOf<String, MutableSet<String>>()
        for (file in SumoCorpus.fullFiles.view) for (form in KifExpr.parseAll(SumoCorpus.text(file))) {
            val elements = (form as KifExpr.ListExpr).elements
            if (elements.size != 3 || elements.any { it !is KifExpr.Atom }) continue
            val head = (elements[0] as KifExpr.Atom).token
            val from = (elements[1] as KifExpr.Atom).token
            val to = (elements[2] as KifExpr.Atom).token
            when (head) {
                "subclass" -> parents.getOrPut(from) { mutableSetOf() }.add(to)
                "instance" -> types.getOrPut(from) { mutableSetOf() }.add(to)
            }
        }
        val ancestors = mutableMapOf<String, Set<String>>()
        val descendants = mutableMapOf<String, MutableSet<String>>()
        for (term in full.terms.view) if (full.isClass(term)) {
            val visited = mutableSetOf<String>()
            val pending = ArrayDeque<String>()
            parents[term]?.let { pending.addAll(it) }
            while (pending.isNotEmpty()) {
                val next = pending.removeFirst()
                if (visited.add(next)) parents[next]?.let { pending.addAll(it) }
            }
            assertFalse(term in visited, "unexpected upstream subclass cycle: $term")
            ancestors[term] = visited
            for (parent in visited) descendants.getOrPut(parent) { mutableSetOf() }.add(term)
            assertEquals(visited, full.superclassesOf(term).view.toSet(), "ancestors of $term")
            assertEquals(term, full.className(full.classId(term)!!))
        }
        for (term in full.terms.view) {
            if (full.isClass(term)) {
                assertEquals(descendants[term] ?: emptySet(), full.subclassesOf(term).view.toSet(), "descendants of $term")
                types.getOrPut(term) { mutableSetOf() }.add("Class")
            }
            val expected = mutableSetOf<String>()
            for (type in types[term].orEmpty()) {
                expected.add(type)
                expected.addAll(ancestors[type].orEmpty())
            }
            val actual = full.mask(term, SumoMask.INSTANCES).toIntArray().map { full.className(SumoClassId(it)) }.toSet()
            assertEquals(expected, actual, "instance types of $term")
        }
    }

    /** Independent delimiter scan, without constructing or projecting KIF expressions. */
    fun formCount(text: String): Int {
        var depth = 0
        var count = 0
        var string = false
        var comment = false
        var escape = false
        for (char in text) when {
            comment -> if (char == '\n') comment = false
            string -> when {
                escape -> escape = false
                char == '\\' -> escape = true
                char == '"' -> string = false
            }
            char == ';' -> comment = true
            char == '"' -> string = true
            char == '(' -> depth++
            char == ')' -> { assertTrue(depth > 0); if (--depth == 0) count++ }
            depth == 0 -> assertTrue(char.isWhitespace(), "content outside a form: $char")
        }
        assertFalse(string)
        assertEquals(0, depth)
        return count
    }

    /** Pool order, term/class IDs, and all four masks. */
    fun fingerprint(sumo: SumoClassifier): String {
        val text = buildString {
            for (term in sumo.terms.view) {
                append("$term\t${sumo.termId(term)}\t${sumo.classId(term)?.value}\n")
                for (kind in SumoMask.entries) append("$kind:${sumo.mask(term, kind).toIntArray().joinToString(",")}\n")
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it) }
    }
}
