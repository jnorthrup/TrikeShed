# trikeshed-libs

Submodule tree for [TrikeShed](https://github.com/jnorthrup/TrikeShed). This repo holds the
`libs/` subprojects that were split out of the main TrikeShed monorepo on 2026-07-05.

## Contents

Each top-level directory is an independent Gradle subproject. The set of *working* modules at
split time was:

```
acpmcp, asclepius, ccek-core, cmc, cmc-generated, combined-client, common, concurrency,
couch, cpu-cache, cutedsl, doubledispatch, dreamer-kmm, forge, htx-client,
integration-scratch, ipfs, jvm-agent, krak, kursive, lcnc, lib, lsm, motion-estimation,
nars3, narsive, og1, openapi, patl, polyglot, polyglot-bench, quic, rhood, tcpd, tls,
torrent, tspy, user-signals, userspace-ebpf
```

Broken / experimental modules that were excluded from the parent build and are kept here only
for archival: `ngsctp, miniduck, uring, ccek-dsl, server, window-toolkit`.

## Relationship to TrikeShed

TrikeShed's root `src/` is self-contained and has zero `libs/` imports. The two repos are
intended to be composed via a Gradle composite build (`--include-build`) or a Git submodule
mount at `libs/` if you want the parent project to see these subprojects again. TrikeShed no
longer ships a `libs/` directory in its tree.

## Provenance

Imported verbatim from `jnorthrup/TrikeShed` at commit `19894c4f` (2026-07-05). File paths
inside each subproject are unchanged relative to that commit; only the `libs/` prefix was
stripped.
