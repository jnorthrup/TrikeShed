# SUMO corpus variants

Full SUMO is **deferred/experimental**, preserved separately at the user's
request on 2026-09-14. Merge + Mid-level remains the shared/default corpus.
General ontology must be reviewed and separated from product/brand-specific
and historical assertions before full is adopted. No such filtering is
implemented. The adoption TODO is in [the task ledger](../../doc/todo.md).

## Build and access

`SumoCorpus.pinned` and `SumoCorpus.middle` return the same lazy classifier,
using only `sumo/Merge.kif` and `sumo/Mid-level-ontology.kif`. The original pins,
file order, term IDs, and class IDs remain unchanged.

Ordinary `jvmProcessResources`, `jvmJar`, and JVM tests do not depend on the
full download or include its resources. There is no full-corpus selection in
common runtime, blackboard, or neighbors. These tasks are explicitly optional:

```text
./gradlew fetchSumoFullCorpus
./gradlew sumoFullTest
./gradlew sumoFullCorpusJar
```

The last task produces a separate `*-sumo-full.jar` containing only resources;
it is not attached to normal assembly or publication. A caller must explicitly
add those resources to its classpath to use `SumoCorpus.full` and
`SumoCorpus.fullFiles: Series<String>`. `SumoCorpus.full(classLoader)` also
supports an explicitly supplied resource classloader. Full is never a fallback
for middle. Without its manifest or any required resource, loading full fails.

Use JDK 25 for Gradle. On the verification host, `JAVA_HOME` was
`/Users/jim/.sdkman/candidates/java/25.0.4.1-graal`. A warm content-addressed
cache under Gradle user home permits `--offline`; a cold or corrupt full cache
fails. Build and runtime both check SHA-256. Repository-relative paths prevent
`FOAFmap.kif` and `mappings/FOAFmap.kif` from overwriting one another. A failed
fetch invalidates the completion manifest. Each file is parsed independently
in strict mode, so a truncated file cannot absorb forms from its successor.

## Pinned scope

Both manifests pin revision `25bb366fdffd8769e741d2e22f215c2785bddce1` of
[ontologyportal/sumo](https://github.com/ontologyportal/sumo/tree/25bb366fdffd8769e741d2e22f215c2785bddce1).
The [recursive upstream inventory](https://api.github.com/repos/ontologyportal/sumo/git/trees/25bb366fdffd8769e741d2e22f215c2785bddce1?recursive=1)
was fetched and compared with the supplied inventory: 139 KIF files, no
truncation. The full manifest selects 78 files totaling **19,980,249 bytes**:

| Location | Included | Excluded |
| --- | ---: | ---: |
| Repository root | 65 | 3 `tiny*` examples |
| `SimpleFacts/` | 1 | 0 |
| `Translations/` | 8 | 0 |
| `mappings/` | 4 | 0 |
| `development/` | 0 | 31 |
| `tests/` | 0 | 27 |

This is TrikeShed's explicit repository selection, not an upstream-defined
release, a Sigma configuration, or all files in the repository. Root files
with experimental names remain included. Upstream's [README](https://raw.githubusercontent.com/ontologyportal/sumo/25bb366fdffd8769e741d2e22f215c2785bddce1/README.txt)
identifies translations as natural-language format files and development as
new/immature knowledge; the [development README](https://raw.githubusercontent.com/ontologyportal/sumo/25bb366fdffd8769e741d2e22f215c2785bddce1/development/README)
says those files are unsuitable for general use. Excluding development does
not establish the accuracy or freshness of the included files.

## Counts and projection limits

| Measurement | Middle | Full (optional) |
| --- | ---: | ---: |
| Files | 2 | 78 |
| Parsed top-level forms | 15,551 | 201,007 |
| Indexed taxonomy terms | 3,685 | 30,735 |
| Indexed classes | 2,504 | 9,059 |
| Atomic taxonomy forms projected | 6,534 | 37,790 |
| Taxonomy forms outside the projection's operands/arity | 11 | 69 |
| Top-level `=>` / `<=>` rules, counted only | 3,077 | 8,167 |
| Other forms, parsed but outside this projection | 5,929 | 154,981 |
| Subclass declarations projected | 2,953 | 10,356 |
| Instance declarations projected | 1,760 | 22,835 |
| Domain declarations projected | 1,469 | 3,890 |
| Range declarations projected | 179 | 341 |
| Closure container bytes (not total heap) | 43,578 | 193,498 |

`termCount` counts constants indexed from top-level atomic taxonomy
declarations, not all symbols, names, literals, or words in KIF. The old
5,279-term / 2,558-class source comment did not describe this pinned
classifier and has been corrected. Both columns above use the same definition.

The classifier projects subclass, instance, domain/range, disjoint, partition,
and decomposition declarations. It does not infer arbitrary logic or turn
functional class expressions into named classes. For example,
`(subclass Mask (CoveringFn Face))` is parsed and counted as unprojected.
Full has 66 such functional-expression forms and three two-argument
`disjointDecomposition` forms in `VirusProteinAndCellPart.kif`; the latter
do not supply the whole plus at least two parts required by this projection.
Documentation, translations, mappings, non-taxonomic assertions, and other
logical forms are parsed and counted, not used as subclass edges. All four
form categories sum to the parsed total. Domain/range queries retain the
existing last-declaration semantics, including when files redeclare a slot;
this is not a consistency checker.

Examples present only in full's indexed terms include `ToyotaPrius` under
`Vehicle`, `Violin` under `Artifact`, `CoffeeMaking` under `Process`,
`HeartDisease` as a `DiseaseOrSyndrome`, `AachenAirport` as a `Region`, and
`BrailleElevator` under `Device`. These demonstrate added source coverage;
they are not endorsements of relevance or current real-world facts.

## Verification boundary

The optional corpus suite uses the **same pinned source bytes and KifExpr
parser** as the loader. It independently extracts atomic subclass/instance
adjacency into ordinary sets and computes breadth-first reachability, without
the classifier builder, `ClosureIndex`, or Roaring union. It compares ancestors
and descendants for every class and instance closure for every indexed term.
No subclass cycles were found. A separate delimiter scanner compares the
number of complete forms in each of the 78 files with the parser's output.
This checks calculations against the input graph, not against another dataset,
and does not validate facts, product relevance, or freshness.

The middle baseline's pool order, every term/class ID, and all four masks
(ancestors, descendants, instances, disjoint) have SHA-256 fingerprint
`d034178a1f00af820394d8129dc89193aaff4d4669b2e485163d1b01578d0799`,
recorded before the loader/parser changes and checked again after loading full.
The original middle behavior tests are retained. Full verification also covers
missing resources, corrupt checksums, incomplete/invalid manifests, strict KIF
boundaries, and domain/range projection. That last check exposed a JVM
`IllegalAccessError` from an inlined projection accessing private arrays;
capturing the same array references locally fixes access without changing IDs.

Verification on 2026-09-14 passed 11 focused default JVM tests (middle corpus,
strict parser, and closure index) and all eight optional `sumoFullTest` tests,
with no skips. The ordinary JVM jar contained only the two middle KIF files
and their manifest. The separate full resource jar contained all 78 payloads,
each rechecked against its SHA-256 pin, and no class files.
