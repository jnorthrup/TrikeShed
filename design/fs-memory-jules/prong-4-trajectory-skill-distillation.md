# Prong 4: Trajectory-to-Skill Distillation Pipeline

## CONTEXT

Paper 2607.26637v1, Section 2.3 (procedural memory / skills): the execution
agent attempts tasks, its trajectories are rendered into chunks, and the
management agent distills them into skill files. Equation (3):

    Gamma_i = search(iota_r, tau_i, M_{i-1})          # retrieve relevant skills
    (xi_i, z_i) = execute(tau_i, Gamma_i)               # attempt the task
    M_i = manage(iota_c, render(xi_i), M_{i-1})         # distill into store

The protocol is leak-free: tau is attempted with a store built only from
earlier tasks. Outcome gate: "only a successful attempt may create or extend a
positive procedure."

RQ3 skill finding: capability acts as a threshold. Every management agent meets
the format; only the strongest masters distillation. Store size runs inversely
with capability (nano: 114 files, mini: 45, gpt-5.4: 16 dense entries).

RQ4 skill finding: "what was curated mattered more than which model executed."
The gpt-5.4 management agent's chain, run at the mini execution agent,
out-scored every cell at the gpt-4.1 execution agent.

TrikeShed already has the trajectory reduction surface:

- `src/jvmMain/kotlin/borg/trikeshed/lcnc/reduction/TrajectoryReductionCli.kt`
  — reads JSON-encoded `JulesCause` list from stdin, prints verdict
  (fingerprint, attempts, category, frozen, depsSatisfied).
- `src/commonMain/kotlin/borg/trikeshed/reduction/ReducerRegistry.kt` —
  reduction registry (search hit confirms existence).
- `TrajectoryReduction` and `TrajectoryOutcome` and `verdictFor` are imported
  by the CLI.
- `src/commonMain/kotlin/borg/trikeshed/util/oroboros/CouchAttachmentGateway.kt`
  — the blob bridge with revision tracking, agentId, sequence.

## TASK

1. Read `TrajectoryReductionCli.kt` to confirm the input format (causes list
   with `JulesCause` types: DrainApplied with commitSha/rejects, DrainFailed
   with reason) and output format (category like NoPatch, frozen flag,
   depsSatisfied).

2. Define `SkillFile` in
  `src/commonMain/kotlin/borg/trikeshed/reduction/SkillFile.kt`:
   - `data class SkillFile(val goalFamily: String, val procedure:
     Series<String>, val warnings: Series<String>, val outcomeGate:
     OutcomeGate)`
   - `OutcomeGate` is a sealed class: `SuccessfulAttempt`, `FailedAttempt`,
     `Ambiguous`. The paper's gate: only `SuccessfulAttempt` may create or
     extend a positive procedure.
   - The skill file serializes to markdown with frontmatter (name,
     description, metadata.type=skill) matching the paper's Figure 2 anatomy.

3. Define `renderTrajectory(xi: Series<JulesCause>): String` that serializes
   a trajectory into the chunk format the management agent consumes. This is
   the paper's `render(xi_i)` from Equation (3). Include: task fingerprint,
   attempt count, cause sequence (ordered), verdict category, and the actual
   commit SHA if DrainApplied.

4. Wire the distillation pipeline:
   ```
   fun distillAttempt(
     taskFingerprint: String,
     causes: Series<JulesCause>,
     existingStore: MemoryStore,
     managementAgent: ModelMux,
   ): ContentId
   ```
   - Compute the verdict via `verdictFor(causes)`.
   - Apply the outcome gate: if verdict is successful, allow procedure
     creation; if failed, only create a warning note.
   - Render the trajectory via `renderTrajectory`.
   - Call `managementAgent.chat(role=Management, tools=MEMORY_WRITE_TOOLS,
     messages=[systemPrompt + renderedTrajectory])`.
   - The management agent's tool calls resolve against the MemoryStore
     (Prong 1), producing the updated store M_i.
   - Return the ContentId of the new/updated skill file.

5. Implement the leak-free protocol guard: before retrieval for task tau_i,
   assert that the store contains NO entries from task i or later. The guard
   checks the `sequence` field in `OroborosAttachmentRef` and rejects any
   retrieval that would leak future information.

6. Wire the distilled skill files as blackboard cards with causalKey linkage
   to their source trajectories. Each skill file's `BlackboardSurfaceRow`
   carries lane="skill", facet="distilled", provenance=<task fingerprint>,
   causalKey=<goal family>:<skill ContentId hex prefix>.

7. Verify: write `SkillDistillationTest` under
   `src/commonTest/kotlin/borg/trikeshed/reduction/` that:
   - Feeds a successful trajectory, asserts a positive procedure is created.
   - Feeds a failed trajectory, asserts only a warning note is created (no
     positive procedure).
   - Feeds the same task twice, asserts the outcome gate prevents duplicate
     positive procedures.

## BUILD GATE

```
./gradlew jvmMainClasses --console=plain
```

## TERMINAL SURFACE

Distilled skill files visible as blackboard cards with causalKey linkage to
their source trajectories. The forge gallery can show the skill store as a
force-layout graph where nodes are skill files and edges link to the
trajectories that produced them.

## FAN-IN NOTES

This prong owns the distillation layer. It consumes the management agent
interface from Prong 3 (ModelMux with Management role + MEMORY_WRITE_TOOLS).
It writes skill files through Prong 1 (MemoryStore). It reads the ISAM
retrieval route from Prong 2 for the leak-free retrieval guard. Patches
commute because the new files (SkillFile.kt, distillAttempt) are disjoint
from Prongs 1-3.
