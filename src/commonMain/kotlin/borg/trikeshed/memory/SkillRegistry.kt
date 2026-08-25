package borg.trikeshed.memory

import borg.trikeshed.cursor.IsAEdge
import borg.trikeshed.cursor.IsALattice
import borg.trikeshed.cursor.TypeToken
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.narsese.AngularCodec
import borg.trikeshed.narsese.RelationKind
import borg.trikeshed.parse.yaml.YamlParser
import borg.trikeshed.userspace.nio.file.spi.FileOperations

/**
 * SkillRegistry — ingests a Hermes-shaped skill tree
 * (`<root>/<category>/<name>/SKILL.md`, YAML frontmatter + markdown) into the
 * planes: one [SkillCard] per skill with its AngularCodec coordinate, plus the
 * category IS-A lattice (skill --> category). The registry is a boundary
 * reader; minting cards into the belief bag is the caller's move.
 */
class SkillRegistry(private val fileOps: FileOperations) {

    data class SkillCard(
        val name: String,
        val category: String,
        val description: String,
        val tags: List<String>,
        val path: String,
        /** Feature-coded coordinate: taxonomy prefix + name/description/tags surface. */
        val angular: Long,
    )

    /** Interned name pool: skill and category names → TypeTokens for the ISA plane. */
    private val pool = ArrayList<String>()
    private val byName = HashMap<String, TypeToken>()
    private val edges = ArrayList<IsAEdge>()

    fun token(name: String): TypeToken = byName.getOrPut(name) {
        pool.add(name)
        TypeToken(pool.size - 1)
    }

    fun nameOf(t: TypeToken): String? = pool.getOrNull(t.poolIdx)

    fun lattice(): IsALattice = IsALattice(edges.size j { i: Int -> edges[i] })

    fun ingest(skillsRoot: String): Series<SkillCard> {
        val cards = ArrayList<SkillCard>()
        if (!fileOps.isDir(skillsRoot)) return 0 j { _: Int -> error("empty") }
        for (category in fileOps.listDir(skillsRoot).sorted()) {
            if (category.startsWith(".")) continue
            val categoryDir = fileOps.resolvePath(skillsRoot, category)
            if (!fileOps.isDir(categoryDir)) continue
            for (skillName in fileOps.listDir(categoryDir).sorted()) {
                val skillMd = fileOps.resolvePath(fileOps.resolvePath(categoryDir, skillName), "SKILL.md")
                if (!fileOps.isFile(skillMd)) continue
                val text = runCatching { fileOps.readAllBytes(skillMd).decodeToString() }.getOrNull() ?: continue
                val fm = frontmatter(text)
                val name = (fm["name"] as? String)?.ifEmpty { null } ?: skillName
                val description = (fm["description"] as? String).orEmpty()
                val tags = tagsOf(fm)
                val card = SkillCard(
                    name = name,
                    category = category,
                    description = description,
                    tags = tags,
                    path = skillMd,
                    angular = AngularCodec.encode(
                        relation = RelationKind.CAUSALITY,
                        taxonomyKey = "skills/$category",
                        subjectTerm = "$name $description ${tags.joinToString(" ")}",
                        objectTerm = name,
                    ),
                )
                cards.add(card)
                edges.add(token(name) edgeTo token(category))
            }
        }
        return cards.size j { i: Int -> cards[i] }
    }

    private fun frontmatter(text: String): Map<String, Any?> {
        if (!text.startsWith("---")) return emptyMap()
        val end = text.indexOf("\n---", 3)
        if (end < 0) return emptyMap()
        return runCatching { asMap(YamlParser.reify(text.substring(3, end).trim())) }.getOrDefault(emptyMap())
    }

    @Suppress("UNCHECKED_CAST")
    private fun asMap(v: Any?): Map<String, Any?> =
        (v as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } ?: emptyMap()

    @Suppress("UNCHECKED_CAST")
    private fun tagsOf(fm: Map<String, Any?>): List<String> {
        val meta = asMap(fm["metadata"])
        val hermes = asMap(meta["hermes"])
        val tags = hermes["tags"] ?: fm["tags"]
        return (tags as? List<*>)?.map { it.toString() } ?: emptyList()
    }
}
