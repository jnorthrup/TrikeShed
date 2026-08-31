"""Fast, deterministic smoke tests.

No network, no Deno, no Pyodide, no API key, no LLM call. These prove the
package imports, the signatures expose their fields, the services construct, and
— the one test with real substance — that the Kotlin scanners classify the four
patterns correctly.
"""

import pytest

# The scanner is importable on its own (it is stdlib-only and gets mounted into
# the sandbox), so it is tested even where predict_rlm is not installed.
from lcnc_depth.modules import kotlin_scan


# ── the scanners: the only logic this package owns ──────────────────────

FIXTURE = '''
package borg.trikeshed.demo

class Demo(parentJob: Job?) {
    // A parented supervisor: structural sibling isolation.
    val supervisor: CompletableJob = SupervisorJob(parentJob)

    // A DETACHED root: reads the same, behaves oppositely.
    val orphan = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun hard(): HtxElement {
        return currentCoroutineContext()[HtxKey]
            ?: error("No HtxKey found in coroutine context")
    }

    suspend fun soft(): Int {
        val reactor = currentCoroutineContext()[MuxReactorElement.Key] ?: return 0
        return 1
    }

    suspend fun optional() {
        val frame = currentCoroutineContext()[LcncScopeFrame]
    }

    fun consume(inputs: Map<String, Any?>): String {
        val text = (inputs["text"] as? String) ?: ""
        val rows = inputs["rows"] as? List<*> ?: emptyList<Any?>()
        val cell = (inputs["cell"] as Map<String, String>)["sheet"]
        return text
    }

    fun fanout(scope: CoroutineScope) {
        scope.launch {
            try {
                agent()
            } catch (e: Throwable) {
                report(e)
            }
        }
    }
}
'''


def test_supervision_distinguishes_detached_from_parented():
    rows = kotlin_scan.supervision(FIXTURE, "Demo.kt")
    mechanisms = {(r["mechanism"], r.get("parent_arg")) for r in rows}
    assert ("supervisor-job", "parentJob") in mechanisms, rows
    detached = [r for r in rows if r["mechanism"] == "detached-root"]
    assert len(detached) == 1, rows
    assert detached[0]["parent_arg"] is None
    assert "DETACHED ROOT" in (detached[0]["caveat"] or "")


def test_supervision_flags_catch_based_isolation_as_conventional():
    rows = kotlin_scan.supervision(FIXTURE, "Demo.kt")
    catches = [r for r in rows if r["mechanism"] == "try-catch"]
    assert len(catches) == 1, rows
    # It must NOT be reported as structural, and must say why.
    assert catches[0]["discount"] < 0.8
    assert "not structural" in (catches[0]["caveat"] or "")


def test_context_demands_classify_severity_and_capture_error_text():
    rows = kotlin_scan.context_demands(FIXTURE, "Demo.kt")
    by_key = {r["element_key"]: r for r in rows}

    assert by_key["HtxKey"]["severity"] == "throws"
    assert by_key["HtxKey"]["error_message"] == "No HtxKey found in coroutine context"

    assert by_key["MuxReactorElement.Key"]["severity"] == "silent-degrade"
    assert by_key["MuxReactorElement.Key"]["error_message"] is None

    assert by_key["LcncScopeFrame"]["severity"] == "optional"


def test_casts_record_the_silent_fallback():
    rows = kotlin_scan.casts(FIXTURE, "Demo.kt")
    by_cast = {r["cast"]: r for r in rows}

    # The failure mode that makes a kind-legal wire produce an empty answer.
    assert by_cast["as? String"]["on_cast_failure"] == "silent-empty"
    assert by_cast["as? String"]["fallback"] == '""'

    assert by_cast["as? List<*>"]["on_cast_failure"] == "silent-empty"

    # An unchecked cast defers its ClassCastException away from the wire.
    hard = [r for r in rows if not r["checked"]]
    assert hard, rows
    assert hard[0]["on_cast_failure"] == "deferred-CCE"


def test_contracts_parse_and_desuffix_kind_keys():
    src = '''
        LcncPortContract(
            type = "prompt.chat",
            inputs = listOf("prompt", "model?"),
            outputs = listOf("content", "ok"),
            inputKinds = mapOf("prompt" to "text", "model?" to "id"),
            outputKinds = mapOf("content" to "text", "ok" to "json"),
            cardinality = mapOf("prompt" to LcncCardinality.MANY),
        ),
    '''
    rows = kotlin_scan.contracts(src, "LcncContracts.kt")
    assert len(rows) == 1
    c = rows[0]
    assert c["type"] == "prompt.chat"
    assert c["inputs"] == ["prompt", "model?"]
    assert c["outputs"] == ["content", "ok"]
    # Kind maps are keyed de-suffixed, matching how the checker looks them up.
    assert c["inputKinds"] == {"prompt": "text", "model": "id"}
    assert c["outputKinds"]["ok"] == "json"
    assert c["cardinality"]["prompt"].endswith("MANY")


def test_contracts_parse_positional_args_and_identifier_types():
    """The form the real table actually uses: positional args, constant type.

    `cardinality` is the FIFTH positional slot. A parser that only knows the
    first four silently drops every MANY declaration in the table — which is
    exactly the shape hazard the model is supposed to reason about.
    """
    src = '''
        private const val SCOPE = "scope"

        LcncPortContract(SCOPE, "scope (a ring)",
            listOf("args?", "when?"), listOf("returns"),
            mapOf("args" to LcncCardinality.MANY),
            inputKinds = mapOf("args" to "json"),
            outputKinds = mapOf("returns" to "json")),
        LcncPortContract(SubVm.LEGO_PREFIX + "tika", "tika",
            emptyList(), listOf("text")),
    '''
    rows = kotlin_scan.contracts(src, "LcncContracts.kt")
    assert len(rows) == 2

    scope = rows[0]
    assert scope["type"] == "scope", "a file-local val constant must resolve"
    assert scope["type_resolved"] is True
    assert scope["inputs"] == ["args?", "when?"]
    assert scope["cardinality"] == {"args": "LcncCardinality.MANY"}, "5th positional slot"
    assert scope["outputKinds"] == {"returns": "json"}

    # A computed type is kept verbatim and flagged, never guessed at.
    lego = rows[1]
    assert lego["type_resolved"] is False
    assert "LEGO_PREFIX" in lego["type"]


def test_contracts_skip_the_data_class_declaration():
    src = 'data class LcncPortContract(\n    val type: String,\n    val inputs: List<String>,\n)'
    assert kotlin_scan.contracts(src, "LcncContracts.kt") == []


def test_contracts_survive_inline_comments():
    src = '''
        LcncPortContract("note", "note", // a trailing comment with ( and "
            emptyList(), /* block ) comment */ emptyList()),
    '''
    rows = kotlin_scan.contracts(src, "x.kt")
    assert len(rows) == 1 and rows[0]["type"] == "note"


def test_runner_registry_finds_node_types():
    src = '"keys.status" to LcncNodeRunner { _, _ ->\n  emptyMap()\n}'
    rows = kotlin_scan.runner_registry(src, "Nodes.kt")
    assert [r["node_type"] for r in rows] == ["keys.status"]


def test_scan_all_returns_every_section():
    out = kotlin_scan.scan_all(FIXTURE, "Demo.kt")
    assert set(out) == {"path", "context_demands", "supervision", "casts", "contracts", "runners"}
    assert out["path"] == "Demo.kt"


def test_scanners_are_safe_on_empty_and_garbage_input():
    for text in ("", "not kotlin at all", "((((", 'val x = "unterminated'):
        kotlin_scan.scan_all(text, "x.kt")  # must not raise


# ── package surface (needs predict_rlm installed) ───────────────────────

def test_service_constructs():
    pytest.importorskip("predict_rlm")
    from lcnc_depth import LcncDepth

    service = LcncDepth(max_iterations=1, adjudicate_iterations=1)
    assert service.max_iterations == 1
    assert service.adjudicate_iterations == 1
    assert service.last_model is None


def test_signatures_have_fields():
    pytest.importorskip("predict_rlm")
    from lcnc_depth.agent.signature import AdjudicateDepth, ModelDepth

    assert set(ModelDepth.input_fields) == {"sources", "contracts_json"}
    assert set(ModelDepth.output_fields) == {"model"}
    assert set(AdjudicateDepth.input_fields) == {"model", "programs", "host_scope", "focus"}
    assert set(AdjudicateDepth.output_fields) == {"report"}


def test_skill_mounts_an_existing_module_file():
    pytest.importorskip("predict_rlm")
    import os

    from lcnc_depth.agent.skills import kotlin_source

    assert kotlin_source.name == "kotlin-source"
    assert "networkx" in kotlin_source.packages
    path = kotlin_source.modules["kotlin_scan"]
    assert os.path.isfile(path), f"skill mounts a module that does not exist: {path}"


def test_schema_roundtrip():
    from lcnc_depth.agent.schema import DepthModel, DepthReport, Violation

    model = DepthModel(summary="empty")
    assert DepthModel(**model.model_dump()) == model

    report = DepthReport(
        violations=[
            Violation(
                rule="kotlin-mismatch",
                detail="both ports say json; the consumer casts as? String and gets ''",
                failure_at_runtime="the seat votes on an empty record",
            )
        ]
    )
    assert DepthReport(**report.model_dump()).violations[0].rule == "kotlin-mismatch"
    # The field that carries this agent's whole value defaults to True.
    assert report.violations[0].invisible_to_current_checker is True


# ── the static scanner CLI (no LLM, no network) ─────────────────────────

def test_scan_repo_analyses_a_tree(tmp_path):
    """The static half must work standalone — it is the CI-usable part."""
    from lcnc_depth.scan_repo import analyse

    (tmp_path / "A.kt").write_text(
        'val globalScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)\n'
        'suspend fun f() = currentCoroutineContext()[HtxKey] ?: error("No HtxKey here")\n'
        'fun g(x: Map<String, Any?>) = (x["t"] as? String) ?: ""\n'
    )
    (tmp_path / "ATest.kt").write_text("val ignored = SupervisorJob()\n")

    a = analyse(tmp_path)
    assert len(a["hard_demands"]) == 1
    assert a["hard_demands"][0]["element_key"] == "HtxKey"
    # HtxKey is not in the CCEK assembly scope, so this demand is unsatisfiable.
    assert len(a["unsatisfiable_under_ccek_assembly"]) == 1
    # A top-level val scope nothing can cancel is the suspicious shape.
    assert len(a["suspicious_supervision"]) == 1
    assert a["suspicious_supervision"][0]["position"] == "top-level-val"
    assert len(a["silent_cast_failures"]) == 1
    # Test sources are excluded by default.
    assert all("Test" not in s["path"] for s in a["supervision"])


def test_scan_repo_gate_exit_codes(tmp_path, capsys):
    from lcnc_depth.scan_repo import main

    (tmp_path / "Clean.kt").write_text("val s = SupervisorJob(parent)\n")
    assert main([str(tmp_path), "--fail-on-suspicious"]) == 0

    (tmp_path / "Dirty.kt").write_text("val orphan = CoroutineScope(SupervisorJob())\n")
    assert main([str(tmp_path), "--fail-on-suspicious"]) == 1

    assert main([str(tmp_path / "nope")]) == 2
