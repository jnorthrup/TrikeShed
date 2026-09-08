"""Regression cases for evidence boundaries; no Kotlin runtime or model calls."""

from copy import deepcopy

import pytest

from lcnc_depth.modules.palette_audit import audit
from lcnc_depth.scan_repo import main


SOURCES = {
    "lcnc/Contracts.kt": '''package borg.trikeshed.lcnc
data class LcncPortContract(val type: String) {
    val context: LcncContextContract get() = LcncContextContract.of(type)
}
object LcncContracts {
    const val SCOPE = "scope"
    const val SCOPE_IN = "scope.in"
    const val SCOPE_OUT = "scope.out"
    fun all(): List<LcncPortContract> = listOf(
        LcncPortContract(SCOPE), LcncPortContract(SCOPE_IN), LcncPortContract(SCOPE_OUT),
        LcncPortContract("note"), LcncPortContract("program.ref"),
        LcncPortContract("http.get"), LcncPortContract(ProjectNodes.READ),
        LcncPortContract(PromptNodes.GET), LcncPortContract(SubVm.LEGO_PREFIX + "tika"),
    )
}
object ProjectNodes { const val READ = "project.read" }
object PromptNodes { const val GET = "prompt.get" }
object SubVm { const val LEGO_PREFIX = "vm." }
fun outside() = LcncPortContract("not.palette")
''',
    "lcnc/Keys.kt": '''package borg.trikeshed.lcnc
import kotlin.coroutines.CoroutineContext
enum class LcncNodeKey(val type: String) : CoroutineContext.Key<LcncNodeElement> {
    HTTP("http.get"), READ(ProjectNodes.READ), PROMPT(PromptNodes.GET), TIKA("vm.tika");
    fun construct(node: LcncNode, inputs: Inputs, runner: LcncNodeRunner, parent: Job): LcncNodeElement {
        return LcncNodeElement(this, node, inputs, runner, parent)
    }
    companion object {
        private val byType = entries.associateBy { it.type }
        fun of(type: String): LcncNodeKey? = byType[type]
    }
}
''',
    "lcnc/Element.kt": '''package borg.trikeshed.lcnc
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext
class LcncNodeElement internal constructor(
    override val key: LcncNodeKey, val node: LcncNode, val inputs: Inputs,
    private val runner: LcncNodeRunner, parent: Job,
) : CoroutineContext.Element {
    val supervisor = SupervisorJob(parent)
    suspend fun execute() {
        val value = withContext(supervisor + this) {
            runner.execute(node, inputs)
        }
    }
}
''',
    "lcnc/Runner.kt": '''package borg.trikeshed.lcnc
fun interface LcncNodeRunner {
    suspend fun execute(node: LcncNode, inputs: Inputs)
    suspend fun run(node: LcncNode, inputs: Inputs) {
        val key = LcncNodeKey.of(node.type)
        return if (key == null) execute(node, inputs)
            else key.construct(node, inputs, this, currentCoroutineContext().job).execute()
    }
}
''',
    "lcnc/Frame.kt": '''package borg.trikeshed.lcnc
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
class LcncScopeFrame : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<LcncScopeFrame>
}
''',
    "lcnc/Metadata.kt": '''package borg.trikeshed.lcnc
data class LcncContextContract(val role: Any, val key: Any?, val keyName: String?, val element: String?, val exception: String? = null) {
    companion object {
        fun of(type: String, composite: Boolean = false): LcncContextContract {
            if (composite) return LcncContextContract(LcncContextRole.COMPOSITE,
                LcncScopeFrame.Key, "LcncScopeFrame.Key", "LcncScopeFrame", "Stored program frame.")
            return when (type) {
                LcncContracts.SCOPE -> LcncContextContract(LcncContextRole.SCOPE,
                    LcncScopeFrame.Key, "LcncScopeFrame.Key", "LcncScopeFrame")
                LcncContracts.SCOPE_IN, LcncContracts.SCOPE_OUT -> LcncContextContract(LcncContextRole.BINDING,
                    LcncScopeFrame.Key, "LcncScopeFrame.Key", "LcncScopeFrame", "Enclosing frame; no new key.")
                "note", "program.ref" -> LcncContextContract(LcncContextRole.PRESENTATION,
                    null, null, null, "Presentation; no execution.")
                else -> LcncNodeKey.of(type)?.let { key ->
                    LcncContextContract(LcncContextRole.INVOCATION, key, "LcncNodeKey.${key.name}", "LcncNodeElement")
                } ?: LcncContextContract(LcncContextRole.UNDECLARED, null, null, null, "Missing mapping.")
            }
        }
    }
}
''',
}


def changed(path, before, after):
    sources = deepcopy(SOURCES)
    assert before in sources[path]
    sources[path] = sources[path].replace(before, after)
    return sources


def test_full_vocabulary_resolves_constants_and_excludes_other_constructors():
    result = audit(SOURCES)
    assert result["palette"]["entry_count"] == 9
    assert result["palette"]["unique_type_count"] == 9
    assert not result["palette"]["unresolved_types"]
    assert "not.palette" not in {r["type"] for r in result["rows"]}
    assert result["summary"]["mapping_status"] == {"structural": 5, "declared": 4}
    assert result["summary"]["structural_exceptions"] == 4
    assert result["context_metadata"]["composite"]["role"] == "COMPOSITE"
    assert result["summary"]["unresolved_service_requirements"] == 4
    assert result["executor"]["status"] == "observed-source-chain"
    assert result["scope"]["runtime_verified"] is False
    assert result["summary"]["gaps"] == 0


@pytest.mark.parametrize("path,before,after", [
    ("lcnc/Runner.kt", "else key.construct(node, inputs, this, currentCoroutineContext().job).execute()", "else execute(node, inputs)"),
    ("lcnc/Element.kt", "withContext(supervisor + this)", "withContext(supervisor)"),
    ("lcnc/Element.kt", "runner.execute(node, inputs)", "unrelated(node, inputs)"),
    ("lcnc/Keys.kt", "return LcncNodeElement(this, node, inputs, runner, parent)", "return otherElement()"),
    ("lcnc/Keys.kt", "entries.associateBy { it.type }", "entries.associateBy { it.name }"),
    ("lcnc/Element.kt", "kotlinx.coroutines.withContext", "unrelated.withContext"),
])
def test_enum_alone_and_broken_executor_chains_do_not_clear_gaps(path, before, after):
    result = audit(changed(path, before, after))
    assert result["summary"]["mapping_status"]["declared"] == 4
    assert result["summary"]["gaps"] == 4
    assert result["executor"]["status"] == "unresolved"


def test_missing_key_cannot_be_excused_by_prefix_or_fallback_metadata():
    sources = changed("lcnc/Contracts.kt", 'LcncPortContract("http.get")', 'LcncPortContract("ccek.unmapped")')
    sources["lcnc/Metadata.kt"] = sources["lcnc/Metadata.kt"].replace("LcncContextRole.UNDECLARED", "LcncContextRole.PRESENTATION")
    result = audit(sources)
    row = next(r for r in result["rows"] if r["type"] == "ccek.unmapped")
    assert row["invocation_mapping"] == "missing" and row["gap"]
    assert row["structural_exception"] is None
    assert result["context_metadata"]["unresolved"]
    assert result["extra_invocation_keys"][0]["palette_type"] == "http.get"


def test_duplicate_and_unresolved_palette_types_cannot_disappear():
    sources = changed("lcnc/Contracts.kt", 'LcncPortContract("http.get")',
                      'LcncPortContract("http.get"), LcncPortContract("http.get"), LcncPortContract(dynamicType())')
    result = audit(sources)
    assert result["palette"]["duplicate_types"] == ["http.get"]
    assert result["palette"]["unresolved_types"][0]["type_expression"] == "dynamicType()"
    assert result["summary"]["gaps"] >= 2


def test_computed_all_and_copy_fallback_remain_unresolved():
    sources = changed("lcnc/Contracts.kt", "= listOf(", "= buildList(")
    result = audit(sources)
    assert result["palette"]["issues"][0]["reason"] == "unsupported-all-expression"
    assert result["summary"]["gaps"] > 0


def test_all_packages_key_inheritance_aliases_and_source_evidence():
    sources = deepcopy(SOURCES)
    sources.update({
        "outside/Base.kt": '''package services
import kotlin.coroutines.CoroutineContext
open class BaseKey<E> : CoroutineContext.Key<E>
object ServiceKey : BaseKey<ServiceElement>()
class ServiceElement : CoroutineContext.Element { override val key get() = ServiceKey }
class PerInstanceKey : CoroutineContext.Key<ServiceElement>
object NotAKey
''',
        "outside/Boot.kt": '''package boot
import services.ServiceElement as Service
import services.ServiceKey as K
fun boot() {
    val service = Service()
    val scope = CoroutineScope(service)
    val x = scope.coroutineContext[K] ?: error("missing")
}
''',
        "another/Nodes.kt": '''package arbitrary
import services.ServiceKey
fun registry() = mapOf(
    "http.get" to LcncNodeRunner { _, _ -> currentCoroutineContext()[ServiceKey]!! },
    "project.read" to LcncNodeRunner { _, _ -> emptyMap() },
)
''',
    })
    result = audit(sources)
    keys = {k["qualified"]: k for k in result["keys"]}
    assert keys["services.ServiceKey"]["singleton"]
    assert keys["services.ServiceKey"]["element"] == "services.ServiceElement"
    assert not keys["services.PerInstanceKey"]["singleton"]
    assert "services.NotAKey" not in keys
    assert any(s.get("element") == "services.ServiceElement" for s in result["construction_sites"])
    demand = next(d for d in result["demand_sites"] if d["key_expression"] == "K")
    assert demand["key"] == "services.ServiceKey"
    assert demand["severity"] == "throws"
    http = next(r for r in result["rows"] if r["type"] == "http.get")
    read = next(r for r in result["rows"] if r["type"] == "project.read")
    assert http["runner_sites"][0]["direct_demands"][0]["key"] == "services.ServiceKey"
    assert not read["runner_sites"][0]["direct_demands"]
    assert http["service_requirements"] == "unresolved-transitive-reachability"
    assert all(not s["provision_proven"] for s in result["installation_sites"])


def test_comments_strings_and_unrelated_methods_are_not_executor_evidence():
    sources = changed("lcnc/Element.kt", "runner.execute(node, inputs)", 'println("runner.execute(node, inputs)")')
    sources["unrelated.kt"] = '''
/* outer /* nested */ class Fake : CoroutineContext.Key<X> */
val help = """enum class FakeKey : CoroutineContext.Key<X> { A("http.get") }
runner.execute(node, inputs)"""
fun unrelated() = withContext(supervisor + this) { runner.execute(node, inputs) }
'''
    result = audit(sources)
    assert not any("Fake" in k["qualified"] for k in result["keys"])
    assert result["executor"]["status"] == "unresolved"


def test_companion_identity_resolves_to_the_singleton_not_class_name():
    sources = deepcopy(SOURCES)
    sources["lookup.kt"] = '''package elsewhere
import borg.trikeshed.lcnc.LcncScopeFrame
fun get() = currentCoroutineContext()[LcncScopeFrame]
'''
    result = audit(sources)
    row = next(d for d in result["demand_sites"] if d["path"] == "lookup.kt")
    assert row["key"] == "borg.trikeshed.lcnc.LcncScopeFrame.Key"
    binding = next(b for b in result["element_bindings"] if b["element"].endswith("LcncScopeFrame"))
    assert binding["key"] == row["key"]


def test_cli_gate_is_independent_of_historical_ccek_reachability(tmp_path, capsys):
    (tmp_path / "X.kt").write_text(SOURCES["lcnc/Contracts.kt"])
    assert main([str(tmp_path), "--fail-on-ccek-gap"]) == 0
    assert main([str(tmp_path), "--fail-on-palette-key-gap"]) == 1
    assert "PALETTE KEY CORRESPONDENCE" in capsys.readouterr().out
    assert main([str(tmp_path / "missing"), "--fail-on-palette-key-gap"]) == 2


def test_test_source_set_does_not_supply_a_production_key(tmp_path):
    from lcnc_depth.scan_repo import analyse
    (tmp_path / "Contracts.kt").write_text(SOURCES["lcnc/Contracts.kt"])
    test = tmp_path / "commonTest" / "kotlin"
    test.mkdir(parents=True)
    (test / "Keys.kt").write_text(SOURCES["lcnc/Keys.kt"])
    assert analyse(tmp_path)["palette_key_audit"]["summary"]["mapping_status"].get("declared", 0) == 0
    assert analyse(tmp_path, include_tests=True)["palette_key_audit"]["summary"]["mapping_status"]["declared"] == 4
