"""Pydantic models for the lcnc-depth RLM.

The vocabulary here mirrors the three layers the LCNC kind strings sit on top of:

  K  Kotlin imperative  — what value actually travels down a patch cable
  C  CCEK context       — which CoroutineContext.Element a node needs to run
  S  supervision        — which failure boundary a wire crosses, and what it discounts

Every claim-bearing model carries a `witness` (a ``file:line``), because the
whole point of this agent is to say things the existing checker cannot, and an
unwitnessed claim about someone else's codebase is a guess wearing a schema.
"""

from pydantic import BaseModel, Field

# ── Layer K: the Kotlin imperative level ────────────────────────────────


class KindBinding(BaseModel):
    """One LCNC kind string and every Kotlin type observed behind it.

    The kind universe is five symbols. `json` alone was observed carrying
    String, Boolean, Int, Map and List — so a kind is a colour, not a type,
    and this model is what records that.
    """

    kind: str = Field(description='"json" | "text" | "id" | "trigger" | "num" | "" (generic)')
    observed_kotlin_types: list[str] = Field(
        default_factory=list,
        description='Distinct Kotlin runtime types produced under this kind, e.g. ["String","Boolean","Int","Map","List"]',
    )
    is_overloaded: bool = Field(
        default=False,
        description="True when two observed types cannot be safely interchanged by a consumer",
    )
    witnesses: list[str] = Field(
        default_factory=list, description="file:line for each observed type"
    )


class PortType(BaseModel):
    """A port's type at the Kotlin level, beneath its declared kind."""

    node_type: str
    port: str
    direction: str = Field(description='"in" or "out"')
    kind: str | None = Field(default=None, description="declared kind, or null when undeclared")
    cardinality: str = Field(default="ONE", description="ONE | OPTIONAL | MANY")
    kotlin_type: str = Field(
        description='Inferred Kotlin type, e.g. "Map<String,Any?>", "Boolean", "List<Map<String,Any?>>"'
    )
    confidence: float = Field(
        default=0.5, description="0..1; how directly the runner evidences this type"
    )
    many_shape_hazard: bool = Field(
        default=False,
        description="True for a MANY port: the runner yields T with one wire and List<T> with two",
    )
    consumer_cast: str | None = Field(
        default=None, description='The cast a consuming runner applies, e.g. "as? String"'
    )
    on_cast_failure: str | None = Field(
        default=None,
        description='"silent-empty" | "silent-null" | "deferred-CCE" | "throw" | null',
    )
    witness: str = Field(default="", description="file:line")


# ── Layer C: the CCEK context level ─────────────────────────────────────


class ContextDemand(BaseModel):
    """A coroutine-context Element a node needs in order to run at all."""

    node_type: str
    element_key: str = Field(
        description='"HtxKey" | "FileOperations.Key" | "MuxReactorElement.Key" | …'
    )
    severity: str = Field(description='"throws" | "silent-degrade" | "optional"')
    error_message: str | None = Field(
        default=None, description="verbatim error string thrown when the element is absent"
    )
    call_path: list[str] = Field(
        default_factory=list, description="runner -> callee -> throw site, as file:line hops"
    )
    witness: str = Field(default="", description="file:line of the demand site")


class ScopeProvision(BaseModel):
    """What a given execution scope actually puts in the coroutine context."""

    scope_name: str = Field(
        description='"CCEK.reactorScope" | "LcncCcekAssembly.child" | "direct-walk" | "webhookScope"'
    )
    provides: list[str] = Field(default_factory=list)
    absent: list[str] = Field(
        default_factory=list,
        description="Elements a reader might reasonably expect here and will NOT find",
    )
    witness: str = Field(default="", description="file:line of the scope construction")


# ── Layer S: supervision and coordination discount ──────────────────────


class SupervisionBoundary(BaseModel):
    """A failure-isolation boundary, and how much coordination it actually discounts.

    `mechanism` is the load-bearing field. A `catch (Throwable)` around a plain
    `launch` reads like isolation and is not structural: it holds only while
    nothing escapes the catch. A `SupervisorJob()` with no parent argument is a
    detached root, which isolates far MORE than intended — a parent cancel never
    reaches it.
    """

    site: str = Field(description='"CCEK.childScope" | "ArticulatedNode.fanout" | …')
    mechanism: str = Field(
        description='"supervisor-job" | "try-catch" | "detached-root" | "sequential"'
    )
    isolates_siblings: bool = Field(default=False)
    discount: float = Field(
        default=0.0,
        description="0..1 reduction in coordination requirement across this boundary; "
        "1.0 = failure fully contained, 0.0 = failure cancels peers",
    )
    caveat: str | None = Field(
        default=None,
        description="Why the discount is not what it appears, e.g. 'isolation is catch-based, "
        "not structural: an uncaught throw cancels siblings'",
    )
    witness: str = Field(default="", description="file:line")


# ── Stage 1 output ──────────────────────────────────────────────────────


class DepthModel(BaseModel):
    """The three-layer model derived from the Kotlin sources."""

    kind_bindings: list[KindBinding] = Field(default_factory=list)
    port_types: list[PortType] = Field(default_factory=list)
    context_demands: list[ContextDemand] = Field(default_factory=list)
    scope_provisions: list[ScopeProvision] = Field(default_factory=list)
    supervision: list[SupervisionBoundary] = Field(default_factory=list)
    undeclared_outputs: list[str] = Field(
        default_factory=list,
        description="node_type.port a runner writes that no contract declares, e.g. 'display.x'",
    )
    summary: str = Field(default="", description="Markdown: what the model found")


# ── Stage 2 output ──────────────────────────────────────────────────────


class Violation(BaseModel):
    """One connection defect, stated so an operator can act on it."""

    rule: str = Field(
        description="kotlin-mismatch | kind-overload | context-missing | many-shape | "
        "undeclared-output | detached-scope | catch-based-isolation"
    )
    severity: str = Field(default="error", description='"error" | "warning"')
    from_node: str = ""
    from_port: str = ""
    to_node: str = ""
    to_port: str = ""
    detail: str = Field(description="One sentence naming the concrete failure")
    invisible_to_current_checker: bool = Field(
        default=True,
        description="True when LcncTypeCheck.check() passes this wire — the whole point",
    )
    failure_at_runtime: str = Field(
        default="", description="What the operator would actually observe"
    )
    witnesses: list[str] = Field(default_factory=list)
    suggested_fix: str = ""


class MateProposal(BaseModel):
    """A candidate patch-cable mate, judged below the kind layer."""

    target_node_type: str
    target_port: str
    kind_compatible: bool = False
    kotlin_compatible: bool = False
    context_satisfied: list[str] = Field(default_factory=list)
    context_missing: list[str] = Field(default_factory=list)
    coupling_discount: float = 0.0
    score: float = Field(
        default=0.0,
        description="0..1 overall fitness; kotlin and context GATE, kind alone does not",
    )
    rationale: str = ""


class DepthReport(BaseModel):
    """Stage 2's verdict."""

    violations: list[Violation] = Field(default_factory=list)
    mates: list[MateProposal] = Field(
        default_factory=list,
        description="Populated only when `focus` names a source port",
    )
    calls_for_depth: list[str] = Field(
        default_factory=list,
        description="Places the CURRENT code is structurally unable to decide, each with a citation",
    )
    report: str = Field(default="", description="Markdown narrative for a human reviewer")
