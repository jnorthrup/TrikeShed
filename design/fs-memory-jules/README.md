# Filesystem-Based Memory: Jules Fan-Out Design

Maps arXiv:2607.26637v1 ("Filesystem-Based Memory for LLM Agents") onto
TrikeShed's BTRFS userspace VFS, Couch blob + CAS store, ISAM routes, and
ModelMux tooling palette.

## The Paper in One Line

One store class (rooted path hierarchy of markdown files), three roles
(management / search / execution), interchangeable tool harness. Declarative
memory and skills unify into one store.

## Key Findings Applied

- RQ1: where hierarchy lives (folders vs headings vs files) is model behavior.
  ConfixIsamIsomorphism indexes structure (headings as levels), not just files.
- RQ2: organization halves retrieval cost on large material. ISAM routes are
  the indexed traversal that delivers this.
- RQ3: search strength pays directly; management buys style. ModelMux.route
  selects different models per role.
- RQ4: stores get more useful as they grow. BTRFS reflinks make
  reorganization storage-free; ConfixWal gives durability.
- RQ5: the harness is a lever. ACP tool sets (write vs read vs BM25 vs shell)
  are the variable; ModelMux routes them.

## Layer Stack

```
Layer 4 (roles)     ModelMux.route("manage"|"search"|"execute") + ACP tools  [Prong 3]
Layer 3 (index)     ConfixIsamIsomorphism + FunnelHashIndex + ConfixWal      [Prong 2]
Layer 2 (docs)      CouchStore + CouchAttachmentGateway                       [shared]
Layer 1 (physical)  BtrfsReflinkStore (CasStore, reflink CoW) + userspace.nio [Prong 1]
Layer 5 (external)  McpServerElement (citation-backed search)                 [Prong 5]
Layer 6 (skills)    TrajectoryReduction -> SkillFile distillation             [Prong 4]
```

## Five Prongs

| Prong | Layer | Files Touched | Terminal Surface |
|-------|-------|---------------|------------------|
| 1 | Physical | BtrfsReflinkStore, TinyBtrfsContract, MemoryFile (new) | Blackboard Cursor: subvolume ops |
| 2 | Index | ConfixIsamIsomorphism, ConfixIsamFactory, MemoryIndexRoute (new) | Blackboard Cursor: route queries |
| 3 | Protocol | AcpProtocol, ModelMux, MemoryTools (new) | Receipt provenance: harness profile |
| 4 | Skills | TrajectoryReduction, SkillFile (new), distillAttempt (new) | Blackboard cards: skill provenance |
| 5 | External | McpServerElement (new), LitebikeListenerElement pattern | MCP route: citation-backed search |

## Fan-In

All five prongs touch disjoint file sets (physical vs index vs protocol vs
distillation vs external API). Patches commute through PijulChannel. Fan-in
converges on the MCP server (Prong 5), which is the unified query interface
over the complete memory system.

## Build Gate

```
./gradlew jvmMainClasses --console=plain
```

No unit tests in the gate. Each prong's commonTest is for local verification.

## Dispatch Order

Prongs 1 and 2 are leaf layers with no cross-dependency; dispatch in parallel.
Prong 3 depends on the MemoryFile type from Prong 1 (tool dispatch targets).
Prong 4 depends on Prong 3 (management agent interface) and Prong 2
(retrieval guard). Prong 5 depends on Prong 2 (ISAM search), Prong 3 (read
tools), and Prong 1 (CAS resolution). It is the convergence point.

Recommended: dispatch Prongs 1+2 in parallel first. When both land, dispatch
Prong 3. When 3 lands, dispatch Prongs 4 and 5 in parallel.

## Reference

Zhou et al., "Filesystem-Based Memory for LLM Agents: Organization, Evolution,
and Sustainability," arXiv:2607.26637v1, 29 Jul 2026.
