from pathlib import Path
import importlib.util
import json


def _load_module():
    script_path = Path(__file__).resolve().parents[1] / "trikeshed_rlm_gradle.py"
    spec = importlib.util.spec_from_file_location("trikeshed_rlm_gradle", script_path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def test_lint_module_flags_library_drift(tmp_path: Path):
    module = _load_module()
    module_dir = tmp_path / "sample-lib"
    src_dir = module_dir / "src/commonMain/kotlin/sample"
    src_dir.mkdir(parents=True)
    (module_dir / "build.gradle.kts").write_text('fun main() = println("ignore gradle")')
    (src_dir / "Sample.kt").write_text(
        """
        package sample

        import kotlin.collections.MutableList

        class SharedKey

        fun main() = println("drift")

        class MutableState(private val items: MutableList<String>)
        """.strip()
    )

    report = module.build_module_report(module_dir)
    rules = module.load_rules(None, module_name="sample-lib")
    lint = module.lint_module(report, rules)

    kinds = {violation["kind"] for violation in lint["violations"]}
    declaration_paths = {declaration["path"] for declaration in report["declarations"]}
    assert "library_entrypoint" in kinds
    assert "mutable_stdlib_leak" in kinds
    assert lint["module_name"] == "sample-lib"
    assert lint["passed"] is False
    assert "build.gradle.kts" not in declaration_paths


def test_refine_rules_without_hermes_stays_manual(tmp_path: Path):
    module = _load_module()
    report = {
        "module_name": "sample-lib",
        "signal_hits": {"mutable_state": 2},
        "candidate_keys": ["SharedKey"],
        "entrypoints": ["sample.main"],
    }
    lint = {
        "module_name": "sample-lib",
        "violations": [
            {"kind": "mutable_stdlib_leak", "evidence": "MutableList in Sample.kt"},
            {"kind": "library_entrypoint", "evidence": "fun main in Sample.kt"},
        ],
    }

    proposal = module.refine_rules(
        report=report,
        lint=lint,
        module_rules={"expected_intent": "library"},
        hermes_command=None,
    )

    assert proposal["mode"] == "manual"
    assert proposal["proposed_rules"]["forbidden_patterns"]
    assert "hermes_prompt" in proposal


def test_gradle_macro_exposes_manual_tasks_only():
    macro_path = Path(__file__).resolve().parents[2] / "gradle/macros/trikeshed-lib.gradle"
    text = macro_path.read_text()

    for task_name in (
        "trikeshedModuleBrief",
        "trikeshedLint",
        "trikeshedDelegatePacket",
        "trikeshedRefineRules",
    ):
        assert task_name in text

    forbidden_wiring = (
        'dependsOn("trikeshedModuleBrief")',
        'dependsOn("trikeshedLint")',
        'dependsOn("trikeshedDelegatePacket")',
        'dependsOn("trikeshedRefineRules")',
    )
    for token in forbidden_wiring:
        lifecycle_lines = [line for line in text.splitlines() if "build" in line or "check" in line or "assemble" in line or "test" in line]
        assert token not in "\n".join(lifecycle_lines)


def test_root_aggregators_only_depend_on_projects_with_manual_tasks():
    build_path = Path(__file__).resolve().parents[2] / "build.gradle.kts"
    text = build_path.read_text()

    assert 'fun Project.manualTrikeshedLibTask(taskName: String): String?' in text
    assert 'tasks.findByName(taskName)' in text
    assert 'manualTrikeshedLibTask("trikeshedModuleBrief")' in text
    assert 'manualTrikeshedLibTask("trikeshedLint")' in text
    assert 'manualTrikeshedLibTask("trikeshedDelegatePacket")' in text
    assert 'manualTrikeshedLibTask("trikeshedRefineRules")' in text


def test_trace_accumulation(tmp_path: Path):
    module = _load_module()
    module_dir = tmp_path / "sample-lib"
    src_dir = module_dir / "src/commonMain/kotlin/sample"
    src_dir.mkdir(parents=True)
    (src_dir / "Sample.kt").write_text(
        "package sample\n\nimport kotlin.collections.MutableList\n\nclass SampleKey\nvar x = 1\n"
    )

    report = module.build_module_report(module_dir)
    rules = module.load_rules(None, module_name="sample-lib")
    lint = module.lint_module(report, rules)

    trace_file = module.append_trace(str(tmp_path), "sample-lib", rules, lint)
    assert trace_file.exists()

    traces = module.load_traces(str(tmp_path), "sample-lib")
    assert len(traces) == 1
    assert traces[0]["module_name"] == "sample-lib"
    assert traces[0]["passed"] is False
    assert "mutable_stdlib_leak" in traces[0]["violation_kinds"]

    module.append_trace(str(tmp_path), "sample-lib", rules, lint)
    traces2 = module.load_traces(str(tmp_path), "sample-lib")
    assert len(traces2) == 2

    modules = module.all_trace_modules(str(tmp_path))
    assert modules == ["sample-lib"]


def test_gepa_build_from_traces(tmp_path: Path):
    module = _load_module()
    module_dir = tmp_path / "sample-lib"
    src_dir = module_dir / "src/commonMain/kotlin/sample"
    src_dir.mkdir(parents=True)
    (src_dir / "Sample.kt").write_text("package sample\n\nclass Foo\n")

    report = module.build_module_report(module_dir)
    rules = module.load_rules(None, module_name="sample-lib")
    lint = module.lint_module(report, rules)
    module.append_trace(str(tmp_path), "sample-lib", rules, lint)

    result = module.build_gepa_project(str(tmp_path))
    assert result["module_count"] == 1
    assert result["example_count"] == 1

    project_dir = Path(result["project_dir"])
    assert (project_dir / "gepa_project.json").exists()
    assert (project_dir / "__init__.py").exists()


def test_apply_refined_rules(tmp_path: Path):
    module = _load_module()
    proposal_path = tmp_path / "proposal.json"
    proposal_path.write_text(json.dumps({
        "proposed_rules": {
            "expected_intent": "library",
            "forbidden_patterns": [r"\bvar\b"],
            "required_terms": ["FooKey"],
            "critic_checks": ["test check"],
        }
    }))

    rules_dir = tmp_path / "rules"
    target = module.apply_refined_rules(str(proposal_path), str(rules_dir), ":libs:common")
    assert target.exists()
    applied = json.loads(target.read_text())
    assert applied["required_terms"] == ["FooKey"]
    assert target.name == "libs__common.json"
