package borg.trikeshed.lcnc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A prefab nobody can adopt is not an example, it is a demo.
 *
 * Every offered preset must arrive with the four things a person needs before
 * they can take it over: what it does, what it needs first, what they will
 * see, and the one knob to turn. And it must say those in plain language —
 * the note nodes inside the presets are written in author shorthand (arrows,
 * edge-mode capitals, route paths, phase numbers), which is exactly what a
 * newcomer cannot read.
 */
class LcncPresetCatalogTest {

    @Test
    fun everyOfferedPrefabIsDescribed() {
        val offered = LcncPresets.all().keys
        val described = LcncPresets.catalog().map { it.name }.toSet()
        assertEquals(offered, described,
            "every offered prefab needs a description, and every description a prefab")
        assertEquals(LcncPresets.catalog().size, described.size, "no duplicate descriptions")
    }

    @Test
    fun everyDescriptionAnswersAllFourQuestions() {
        for (info in LcncPresets.catalog()) {
            assertTrue(info.title.isNotBlank(), "${info.name}: no plain-language title")
            assertTrue(info.does.length > 20, "${info.name}: 'does' must be a sentence, not a label")
            assertTrue(info.needs.isNotBlank(), "${info.name}: must say what it needs (or that it needs nothing)")
            assertTrue(info.see.isNotBlank(), "${info.name}: must say what will appear")
            assertTrue(info.tweakFirst.isNotBlank(), "${info.name}: must name the first thing worth changing")
            assertTrue(info.title != info.name, "${info.name}: the title is for a person, not the file name")
        }
    }

    @Test
    fun descriptionsAvoidTheShorthandOnlyTheAuthorReads() {
        // The exact vocabulary found in the shipped note nodes: pipeline arrows,
        // route paths, phase numbers like W5.3, and shouted edge modes.
        val jargon = listOf("->", "→", "⇄", "/api/", "LOOP", "JOIN", "ABORT", "CAS", "cid", "KIF")
        val phase = Regex("""\bW\d+(\.\d+)?\b""")
        for (info in LcncPresets.catalog()) {
            val prose = listOf(info.title, info.does, info.needs, info.see, info.tweakFirst).joinToString(" ")
            for (bad in jargon) {
                assertTrue(!prose.contains(bad),
                    "${info.name}: '$bad' is author shorthand — say it in words a newcomer reads")
            }
            assertTrue(!phase.containsMatchIn(prose), "${info.name}: phase numbers mean nothing to a newcomer")
        }
    }
}
