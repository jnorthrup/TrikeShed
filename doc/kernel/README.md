# doc/kernel — the kernel's shape documents, carried from CCEKCMMKPlatform

Source: https://github.com/jnorthrup/CCEKCMMKPlatform at commit
`f3f276eeb2148c649e1d6ac5fa2768cdfbdc2821` (2026-05-30). Carried across
2026-09-03, byte-for-byte, because the platform repository froze on that date
while this repository kept moving the same kernel files, and these documents
existed only there.

| File | Was | What it is |
|---|---|---|
| `meta-micro-kernel-whitepaper.md` | `README.md` | The dissertation-review whitepaper: Join, MetaSeries, Series, Cursor, CharStr, the CCEK reactor discipline, Occam refinements |
| `curiously-recursive-metaseries-shapes.md` | `references/…` | The shape families: CCEK (IO/reactor/protocol/parser), CharStr combinators, the algebra subsumption pattern |
| `trikeshed-algebra-deep-dive.md` | `references/…` | Why Join, dense twins, MetaSeries indexing, JIT behaviour, the staircase problem |
| `CharStr-Dag.md` | `references/…` | The CharStr text-facet DAG dissertation |
| `confix-architecture.html` | root | Confix axis summary, ConfixIndex facets, lifecycle states |

Not carried: `references/PRELOAD.md` — this repository's `PRELOAD.md` is the
same document plus the lineage table (K/kdb+, Arrow, optics, Rx), so it is ahead.

## Which side is the source

The Kotlin under `src/commonMain/kotlin/borg/trikeshed/{lib,cursor,charstr,confix,parse/confix,context,manifold}`
exists in both repositories under the same paths. This repository is ahead on
every diverged file (Join 5 commits since the freeze, TypeDefOracle 21,
ConfixKit 10, Confix 7); the platform is the source only for the documents
above. `parity.tsv` records the decision per file, and `KernelParityTest`
(`src/jvmTest/kotlin/borg/trikeshed/kernel/`) holds the working tree to it —
the same discipline as `RouteManifestParityTest` holds routes to `RouteManifest`.

## The gate

`parity.tsv` columns: `path`, `status`, `platformSha256`, `trikeshedSha256`, `resolvedPath`.

- `identical` — the shared kernel file is byte-identical in both repositories.
  Editing it here is a decision: the test fails until the row is re-declared
  (`ahead`) or the platform is brought along and the manifest regenerated.
- `ahead` — this repository has moved past the platform copy. The test fails if
  the file has become identical again (a stale declaration).
- `moved:<path>` — the file lives elsewhere here; the test checks the new path.

Regenerate after either side changes:

```
scripts/kernel-parity.sh [path-to-platform-clone]
```

With no argument the script clones the pinned commit into a temp dir.
