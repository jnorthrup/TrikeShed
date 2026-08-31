"""DSPy signatures for the two stages.

The docstrings are the RLM's system instructions, so they carry the load-bearing
facts about this codebase rather than generic advice. Everything asserted here
was established by reading the sources, and each claim names where it lives so
the agent can verify rather than trust.
"""

import dspy
from predict_rlm import File

from .schema import DepthModel, DepthReport


class ModelDepth(dspy.Signature):
    """Derive the three-layer type model that sits beneath LCNC's five kind strings.

    The declared kind vocabulary is exactly {json, text, id, trigger, num} plus
    generic, and it is NOMINAL over `Any?`: `LcncNodeRunner` is
    `suspend (LcncNode, Map<String,Any?>) -> Map<String,Any?>`, and the executor
    never reads a kind at all. Your job is to recover what the kinds do not say.

    1. **Read the vocabulary.** Prefer `contracts_json` when a dump is supplied;
       otherwise parse the `LcncPortContract(...)` literals out of
       LcncContracts.kt with `kotlin_scan.contracts()`. Record inputs, outputs,
       inputKinds, outputKinds and cardinality per node type. Kind maps are keyed
       by the DE-SUFFIXED port name ("args", not "args?") while inputs/outputs
       keep the "?" — strip before lookup.

    2. **Layer K — Kotlin imperative.** For each node type, find its runner in
       the registries (`kotlin_scan.runner_registry()`) and determine the actual
       Kotlin type of each output value, plus the cast each consumer applies to
       each input (`kotlin_scan.casts()`). Read the surrounding function body and
       use predict() to name the produced type — do NOT infer a type from a port
       name. Record `on_cast_failure` honestly: most sites degrade silently to
       "" / null / emptyList, some defer a ClassCastException to a later line,
       few throw. Mark a kind `is_overloaded` when two Kotlin types behind it
       cannot be interchanged by a consumer. Treat MANY cardinality as a shape
       hazard: the runner yields `T` for one wire and `List<T>` for two, so the
       same port has two shapes depending on how many cables reach it.

    3. **Layer C — CCEK context.** Use `kotlin_scan.context_demands()` to find
       every CoroutineContext element read, and keep its severity: a `throws`
       demand means the node cannot run without that element, a `silent-degrade`
       one means it runs and quietly stops doing something (metering, caching).
       Attribute each demand to the node types that can reach it, following calls
       out of the runner body. Then determine what each execution scope PROVIDES
       by reading the scope constructions themselves — a scope's context is
       exactly the elements composed into it with `+`, so an element that is not
       named there is not present. A `throws` demand that the host scope does not
       satisfy is the defect class this whole model exists to expose.

    4. **Layer S — supervision.** Use `kotlin_scan.supervision()` and decide the
       MECHANISM for each site, because mechanism determines whether isolation is
       structural or merely conventional:
         - `supervisor-job`  — structural; a child failure does not cancel siblings
         - `try-catch`       — conventional; holds only while nothing escapes the
                               catch, and a `catch (Throwable)` also swallows
                               CancellationException
         - `detached-root`   — `SupervisorJob()` with NO parent argument; this
                               over-isolates, because a parent cancel never
                               reaches it. It reads identically to the parented
                               form and behaves oppositely.
         - `sequential`      — no isolation at all; a throw aborts the whole walk
       Assign a `discount` in 0..1 for how much coordination the boundary
       genuinely removes, and state the `caveat` whenever the discount is not
       what the code appears to promise.

    5. **Emit the model.** Every claim carries a `file:line` witness. Where the
       evidence is thin, LOWER `confidence` rather than inventing a type. A model
       that admits uncertainty is usable; one that guesses confidently is not.
    """

    sources: list[File] = dspy.InputField(
        desc="Kotlin sources: the lcnc/ and ccek/ subtrees plus the files that own "
        "context-element throw sites (keymux, modelmux, htx, nio spi)"
    )
    contracts_json: list[File] = dspy.InputField(
        desc="Zero or one JSON dump of the contracts route; when empty, parse LcncContracts.kt"
    )
    model: DepthModel = dspy.OutputField(
        desc="The three-layer model, every claim carrying a file:line witness"
    )


class AdjudicateDepth(dspy.Signature):
    """Judge LCNC programs against the deep model, and report what the shallow checker cannot see.

    1. **Parse each program.** Wire endpoints are 2-ELEMENT ARRAYS, not objects:
       `{"from":["node","port"], "to":["node","port"]}`. Params are always
       strings, even when they look numeric or boolean. A node is a RING if it
       has children, a `subprogram`, or a `program` param; a NAMED ring (no
       children) is OPAQUE and its wires must be excluded from port-level
       judgement, exactly as the existing checker does.

    2. **Replay the shallow check first.** Reproduce the existing rules —
       unknown type, duplicate id, missing node, undeclared port, kind mismatch
       (exact string equality, with generic as a wildcard on either side), and
       the ring-boundary rule that data flows lateral or inward only. Anything it
       already rejects is NOT your finding: mark
       `invisible_to_current_checker=False` and move on. Your value is entirely
       in the wires it ACCEPTS.

    3. **Judge at depth.** For every wire the shallow check accepts, ask:
       - do the Kotlin types actually compose, or does the consumer's cast fail
         and take a silent fallback? Name what the operator would then observe.
       - does a MANY port receive the shape it expects at THIS fan-in count?
       - are the target node's `throws`-severity context demands satisfied by
         `host_scope`? Look the scope up in the model's scope_provisions.
       - does the wire cross a detached root, or isolation that is only
         catch-based? Report the coupling the author probably assumed versus the
         coupling they actually have.
       Every violation must name a concrete runtime observation, not just a rule
       name. "kind-legal but the cast yields empty, so the seat votes on nothing"
       is a finding; "type mismatch" is not.

    4. **Propose mates** when `focus` names a `nodeType.port`. Rank by Kotlin and
       context compatibility — those GATE. Kind agreement is necessary and
       nowhere near sufficient, which is precisely why the current
       mating-options route proposes cables that fail at run time.

    5. **Name the depth gaps.** Populate `calls_for_depth` with places the
       CURRENT code is structurally unable to decide, each citing the file that
       proves it. Be specific about WHY it cannot decide, not merely that it does
       not.
    """

    model: DepthModel = dspy.InputField(desc="The three-layer model from stage 1")
    programs: list[File] = dspy.InputField(
        desc="LCNC program documents (Confix shape) to adjudicate"
    )
    host_scope: str = dspy.InputField(
        desc="Which scope the program runs under: ccek-assembly | direct-walk | webhook"
    )
    focus: str = dspy.InputField(
        desc="Optional 'nodeType.port' to propose mates for; empty string to skip"
    )
    report: DepthReport = dspy.OutputField(desc="Violations, mate proposals, and depth gaps")
