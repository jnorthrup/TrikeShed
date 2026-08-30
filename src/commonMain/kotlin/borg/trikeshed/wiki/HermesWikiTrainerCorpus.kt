package borg.trikeshed.wiki

/**
 * Stable names for the bundled WikiSkill trainer assets.
 *
 * The bytes live in commonMain resources so every target sees the same corpus.
 * Runtime results belong in CAS/the mutable wiki; they must never rewrite these
 * design and acceptance fixtures.
 */
object HermesWikiTrainerCorpus {
    const val SET_1A: String = "1A"
    const val PAPER_ARXIV: String = "2608.27454v1"
    const val PAPER_URL: String = "https://arxiv.org/abs/2608.27454"
    const val ROOT_1A: String = "hermes/wiki-trainer/1A"
    const val MANIFEST_1A: String = "$ROOT_1A/manifest.json"

    val required1AAssets: List<String> = listOf(
        MANIFEST_1A,
        "$ROOT_1A/raw/train-explicit-cause-pass.md",
        "$ROOT_1A/raw/train-cooccurrence-fail.md",
        "$ROOT_1A/raw/validation-if-then-pass.md",
        "$ROOT_1A/raw/validation-reversed-cause-fail.md",
        "$ROOT_1A/nlp/dependencies.jsonl",
        "$ROOT_1A/translation/round-trips.jsonl",
        "$ROOT_1A/nars/causal-decisions.jsonl",
        "$ROOT_1A/wiki/index.md",
        "$ROOT_1A/wiki/patterns/grounded-causal-link.md",
        "$ROOT_1A/wiki/logs.md",
        "$ROOT_1A/wiki/skill-impact.md",
        "$ROOT_1A/candidate/grounded-causal-link/SKILL.md",
        "$ROOT_1A/candidate/grounded-causal-link/PURPOSE.md",
        "$ROOT_1A/validation/expected-results.json",
    )
}
