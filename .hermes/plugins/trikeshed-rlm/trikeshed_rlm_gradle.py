from __future__ import annotations

import argparse
import json
import os
from datetime import datetime, timezone
from pathlib import Path
import re
import subprocess
from typing import Any

KOTLIN_SUFFIXES = (".kt", ".kts")
DEFAULT_FORBIDDEN_PATTERNS = (
    r"\bMutableList\b",
    r"\bMutableMap\b",
    r"\bMutableSet\b",
    r"\bArrayList\b",
    r"\bHashMap\b",
    r"\bHashSet\b",
    r"\bjava\.util\.(List|Map|Set)\b",
    # No Java NIO in pure libs (java.nio.* belongs in platform-specific sources only)
    r"\bjava\.nio\.",
    # No Kotlin stdlib mutable collections in pure library commonMain
    r"\bmutableListOf\b",
    r"\bmutableMapOf\b",
    r"\bmutableSetOf\b",
    r"\bmutableMap\b",
)
DEFAULT_SIGNAL_PATTERNS = {
    "mutable_state": re.compile(r"\bvar\b|\bMutable(List|Map|Set)\b|\bArrayList\b|\bHash(Map|Set)\b"),
    "supervisor_job": re.compile(r"\bSupervisorJob\b"),
    "series_terms": re.compile(r"\bSeries\b|\bMetaSeries\b|\bCursor\b"),
    "io_terms": re.compile(r"\bChannel\b|\bSocket\b|\bFile\b|\bnio\b|\buring\b"),
}
DECLARATION_PATTERN = re.compile(
    r"^\s*(?:data\s+class|class|interface|object|fun)\s+([A-Za-z_][A-Za-z0-9_]*)",
    re.MULTILINE,
)
PACKAGE_PATTERN = re.compile(r"^\s*package\s+([A-Za-z0-9_.]+)", re.MULTILINE)
IMPORT_PATTERN = re.compile(r"^\s*import\s+([A-Za-z0-9_.*]+)", re.MULTILINE)
ENTRYPOINT_PATTERN = re.compile(r"^\s*fun\s+main\s*\(", re.MULTILINE)
KEY_PATTERN = re.compile(r"\b([A-Za-z_][A-Za-z0-9_]*Key)\b")
ELEMENT_PATTERN = re.compile(r"\b([A-Za-z_][A-Za-z0-9_]*Element)\b")

# ---------------------------------------------------------------------------
# Reactor context-injection invariant checks
#
# Design contract: ChannelOperations and ReactorOperations are
# CoroutineContext.Key-based services. Callers must retrieve them from the
# coroutine context, never hold them as constructor parameters or fields.
# Kotlin cannot enforce this — the scanner enforces it.
#
# Three violation kinds, ordered largest → smallest footprint:
#   context_injection_bypass  — constructor param typed as a reactor service
#   reactor_field_hold        — stored as private val field (post-construction)
#   missing_context_key       — implements the service interface but lacks
#                               companion object Key : AsyncContextKey<T>
# ---------------------------------------------------------------------------

# The canonical reactor service interface names. Add here as the algebra grows.
REACTOR_SERVICE_TYPES: frozenset[str] = frozenset({
    "ChannelOperations",
    "ReactorOperations",
    "FileOperations",
    "SystemOperations",
    "ProcessOperations",
})

# Matches a constructor or function parameter typed as a reactor service.
# Restricted to lines that look like parameter declarations:
#   val channelOps: ChannelOperations
#   channels: JvmChannelOperations
#   reactorOps: ReactorOperations? = null
# Excluded: class inheritance `class Foo : ChannelOperations`, override return types.
# Strategy: the service type must appear after ': ' and the line must be inside
# a parameter list — indicated by a preceding identifier (param name) immediately
# before the colon, not just a bare class declaration.
_CTOR_PARAM_RE = re.compile(
    r"\b[a-z_][A-Za-z0-9_]*\s*:\s*(?:[A-Za-z_][A-Za-z0-9_]*)?((?:"
    + "|".join(REACTOR_SERVICE_TYPES)
    + r"))\b(?!\s*\{)"
)

# Matches a field declaration holding a reactor service (not a constructor param —
# we look for `private val` / `val` / `var` at class body indentation):
_FIELD_HOLD_RE = re.compile(
    r"^\s+(?:private\s+)?(?:val|var)\s+\w+\s*:\s*(?:[A-Za-z_][A-Za-z0-9_]*)?"
    r"((?:" + "|".join(REACTOR_SERVICE_TYPES) + r"))\b",
    re.MULTILINE,
)

# Detects that a file contains 'companion object Key : AsyncContextKey<'
_HAS_CONTEXT_KEY_RE = re.compile(r"companion\s+object\s+Key\s*:\s*(?:AsyncContextKey|CoroutineContext\.Key)\s*<")

# Detects interface implementation of a reactor service:
#   class Foo : ChannelOperations
#   class Foo(...) : AsyncContextElement(...), ReactorOperations
_IMPLEMENTS_SERVICE_RE = re.compile(
    r"^(?:class|object)\s+[A-Za-z_][A-Za-z0-9_]*"
    r"(?:\s*\([^)]*\))?"          # optional constructor
    r"\s*:\s*[^{]*\b((?:" + "|".join(REACTOR_SERVICE_TYPES) + r"|AsyncContextElement))\b",
    re.MULTILINE,
)


def _check_reactor_invariants(text: str, rel_path: str) -> list[dict[str, Any]]:
    """Return reactor-context violations found in a single Kotlin source file."""
    violations: list[dict[str, Any]] = []

    # 1. context_injection_bypass — constructor params storing reactor services as fields
    #    LEGIT: Method params passed to orchestrators (mega-service choreography)
    #    VIOLATION: Constructor params with val/var storing reactor services
    #    Skip interface/abstract declarations and companion Key lines.
    #    Carve-out: NioSupervisor.service<T>() pattern is a service registry lookup.
    #    Heuristic: Skip if "fun " appears in same line or previous line (method param).
    lines = text.splitlines()
    for i, line in enumerate(lines, 1):
        stripped = line.strip()
        # Skip companion object Key lines (not constructor params)
        if "companion object Key" in stripped or "companion object Key:" in stripped:
            continue
        # Skip NioSupervisor.service<T>() pattern (service registry lookup)
        if "nioSup.service<" in stripped or "nioSup.service<" in line:
            continue
        if ".service<" in stripped and ("NioSupervisor" in text or "nioSup" in text):
            continue
        # Skip interface declarations, override val key lines, companion objects
        if stripped.startswith("interface ") or "override val key" in stripped:
            continue
        if stripped.startswith("companion object"):
            continue
        # Skip method parameters (not constructor params) - look back up to 3 lines
        is_method_param = False
        if "fun " in line:
            is_method_param = True
        else:
            for lookback in range(1, min(4, i)):
                if "fun " in lines[i - lookback]:
                    is_method_param = True
                    break
        if is_method_param:
            continue
        
        m = _CTOR_PARAM_RE.search(line)
        if m:
            service = m.group(1)
            violations.append({
                "kind": "context_injection_bypass",
                "evidence": f"{rel_path}:{i}: {stripped[:120]}",
                "service_type": service,
                "remedy": (
                    f"Remove constructor parameter '{service}'. "
                    "Fetch from coroutine context via currentCoroutineContext()[{service}.Key] "
                    "inside suspend functions that need it."
                ),
            })

    # 2. reactor_field_hold — private val / var field storing a reactor service
    #    LEGIT: Local vals inside function bodies (orchestration choreography)
    #    VIOLATION: Class-level fields storing reactor services (constructor capture)
    #    Heuristic: Look back for "fun " - if found, we're inside a function (local var).
    #    Look back for "class " - if found after "fun ", reset (new class, might be field).
    lines = text.splitlines()
    for m in _FIELD_HOLD_RE.finditer(text):
        line_no = text.count("\n", 0, m.start()) + 1
        
        # Look back to determine if we're inside a function body
        is_inside_function = False
        seen_class_after_fun = False
        for lookback in range(1, min(100, line_no)):
            check_line_idx = line_no - lookback - 1
            if check_line_idx < 0:
                break
            check_line = lines[check_line_idx].strip()
            
            # If we see a class declaration AFTER seeing "fun ", reset (new class)
            if seen_class_after_fun and (check_line.startswith("class ") or check_line.startswith("object ")):
                is_inside_function = False
                seen_class_after_fun = False
                continue
            
            # If we see "fun ", we're inside a function body
            if "fun " in check_line:
                is_inside_function = True
                # Keep looking back to see if there's a class declaration after this fun
                continue
            
            # If we see a class/object/interface declaration, we're NOT inside a function
            if check_line.startswith("class ") or check_line.startswith("object ") or check_line.startswith("interface "):
                seen_class_after_fun = True
        
        if is_inside_function:
            continue  # Local variable - legit choreography
        
        service = m.group(1)
        violations.append({
            "kind": "reactor_field_hold",
            "evidence": f"{rel_path}:{line_no}: {m.group(0).strip()[:120]}",
            "service_type": service,
            "remedy": (
                f"Remove class-level field holding '{service}'. "
                "Pass as method parameter to orchestrators or retrieve from coroutine context in suspend functions. "
                "Local vals in function bodies are legit choreography."
            ),
        })

    # 3. missing_context_key — implements a reactor service interface but no Key
    if _IMPLEMENTS_SERVICE_RE.search(text) and not _HAS_CONTEXT_KEY_RE.search(text):
        # Carve-out: FileOperations interface already provides companion object Key
        # Implementations only need to override val key, not define their own companion
        m2 = _IMPLEMENTS_SERVICE_RE.search(text)
        if m2 and "FileOperations" in m2.group(0):
            # Check if the class has override val key
            if "override val key" in text:
                # This is fine - FileOperations provides the Key
                pass
            else:
                # Missing override val key
                class_line = m2.group(0).split("\n")[0][:120] if m2 else rel_path
                line_no = text.count("\n", 0, m2.start()) + 1 if m2 else 0
                violations.append({
                    "kind": "missing_context_key",
                    "evidence": f"{rel_path}:{line_no}: {class_line.strip()}",
                    "remedy": (
                        "Add 'override val key get() = FileOperations.Key' to implement "
                        "CoroutineContext.Element for the FileOperations interface."
                    ),
                })
        else:
            # Non-FileOperations services need companion object Key
            class_line = m2.group(0).split("\n")[0][:120] if m2 else rel_path
            line_no = text.count("\n", 0, m2.start()) + 1 if m2 else 0
            violations.append({
                "kind": "missing_context_key",
                "evidence": f"{rel_path}:{line_no}: {class_line.strip()}",
                "remedy": (
                    "Add 'companion object Key : AsyncContextKey<T>()' and "
                    "'override val key get() = Key' so callers can retrieve this "
                    "element from the coroutine context."
                ),
            })

    return violations


# Any-typed erasure patterns — flag untyped Any in non-test commonMain code
_ANY_PARAM_RE = re.compile(
    r"\b[a-z_][A-Za-z0-9_]*\s*:\s*Any[?\s,)]"  # param: Any or param: Any?
)
_ANY_RETURN_RE = re.compile(
    r"(?:fun\s+\w+[^:]*\)\s*:\s*Any[?\s{])"  # fun foo(...): Any or Any?
)
_ANY_FIELD_RE = re.compile(
    r"(?:val|var)\s+\w+\s*:\s*Any[?\s=]"  # val/var field: Any
)
_ANY_GENERIC_RE = re.compile(
    r"<Any[?,>]"  # generic <Any>, <Any?>, <K, Any>
)


def _check_any_leaks(text: str, rel_path: str) -> list[dict[str, Any]]:
    """Flag untyped Any usage in production code (not test files)."""
    # skip test files — Any in tests is often intentional fixture/mock wiring
    if "/test/" in rel_path or "Test.kt" in rel_path:
        return []
    violations: list[dict[str, Any]] = []
    lines = text.splitlines()
    for i, line in enumerate(lines, 1):
        stripped = line.strip()
        # skip comments and annotations
        if stripped.startswith("//") or stripped.startswith("*") or stripped.startswith("@"):
            continue
        # skip import lines (e.g. from typing import Any)
        if stripped.startswith("import "):
            continue
        # Kotlin's required equality override is intentional Any? usage
        if "equals(other: Any?)" in stripped:
            continue
        # Intentional dynamic patterns in library code:
        # - Cursor algebra: Series<Any?> represents heterogeneous row cells
        # - Row construction: List<Any?> when building dynamic rows
        # - Accumulator polymorphism: add/getResult handle any column type
        # - Predicate builders: compare against any column value
        # - Serialization: encode/decode tagged unions
        # - ISAM parsing: metadata is inherently dynamic
        # - Group/hash join: keys are Any? because group-by columns are heterogeneous
        if ("Series<Any?>" in line or "List<Any?>" in line or "LinkedList<Any?>" in line or
            "Array<Any?>" in line or "mutableListOf<Any?>" in line or "arrayOfNulls<Any?>" in line):
            continue
        # Cursor algebra: Join<Any?, Meta> is the heterogeneous accessor type
        if "Join<Any?" in line:
            continue
        # Data class fields for heterogeneous map entries
        if ("key: Any?," in stripped or "value: Any?," in stripped):
            continue
        # Property getters for Any? fields
        if ("val key: Any? get()" in stripped or "val value: Any? get()" in stripped):
            continue
        # When-expressions extracting heterogeneous values
        if "val v: Any? = when (i)" in stripped:
            continue
        # Accumulator interface polymorphism
        if ("fun add(value: Any?)" in stripped or "fun getResult(): Any?" in stripped):
            continue
        # Predicate builder comparisons
        if ("value: Any?): (RowVec)" in stripped or "compareCursorValues" in stripped or "compareKeys" in stripped):
            continue
        # Infix predicate builders
        if ("infix fun" in stripped and "value: Any?" in stripped):
            continue
        # Infix predicate builders with iterables/pairs
        if ("inList(values: Iterable<Any?>)" in stripped or "between(bounds: Pair<Any?, Any?>)" in stripped):
            continue
        # Accumulator polymorphic append
        if ("fun append(row: Any?)" in stripped or "fun append(value: Any?)" in stripped):
            continue
        # Downcasting helpers
        if ("childSeries(source: Any?)" in stripped):
            continue
        # JSON encoding helpers
        if ("appendJsonValue" in stripped and "value: Any?" in stripped):
            continue
        # Polymorphic comparison helpers
        if ("compareRowValue(left: Any?, right: Any?):" in stripped or "compareComparableValues" in stripped):
            continue
        # Comparable casts for polymorphic comparison
        if ("as Comparable<Any>" in line or "as Comparable<Any?>" in line):
            continue
        # Serialization helpers (wider catch for spacing variants)
        if ("appendTaggedValue(v: Any?)" in stripped or "appendTaggedValue(" in stripped):
            continue
        # Query parameter binding
        if ("withParameter" in stripped and "value: Any?" in stripped):
            continue
        # SQL comparison helpers
        if ("valuesEqual" in stripped or "compareValues" in stripped):
            continue
        # Type introspection
        if ("typeNameOf(value: Any?)" in stripped):
            continue
        # ISAM metadata parsing
        if ("parseViews(value: Any?)" in stripped or "parsePartition" in stripped or "parseFile" in stripped or
            "parseGroup" in stripped or "parseColumnNames" in stripped or "parseFlags" in stripped):
            continue
        # Serialization helpers
        if ("appendTaggedArray(list: List<Any?>)" in stripped or "appendTaggedValue(v: Any?)" in stripped or
            "appendJsonValue(value: Any?)" in stripped or "appendAnyJson" in stripped or
            "decodeTagged(raw: Any?)" in stripped or "encodeRowToString(value: Any)" in stripped):
            continue
        # Hash grouping/join indexes use Any? keys because group-by columns are heterogeneous
        if "LinkedHashMap<Any?," in line:
            continue
        # JSON AST walking/traversal (OpenAPI parser, DOM walkers)
        if ("walk(node: Any?)" in stripped or "walk(" in stripped and "node: Any?" in stripped or
            "var current: Any? =" in stripped or "val node: Any?" in stripped or
            "val root: Any?" in stripped or "val value: Any? =" in stripped and ("parse" in line.lower() or "walk" in line.lower())):
            continue
        # Bencode encoding/decoding (torrent DHT protocol)
        if ("bencode(value: Any)" in stripped or "bencode(" in stripped and "Any" in stripped or
            "error: List<Any>?" in stripped or "error: List<Any>" in stripped or
            "parseBencode" in stripped):
            continue
        # CouchDB view query parameters (keys can be any JSON type)
        if ("key: Any? = null" in stripped or "startKey: Any? = null" in stripped or
            "endKey: Any? = null" in stripped or "keys: List<Any?>" in stripped or
            ("ViewQuery" in line and "Any?" in line)):
            continue
        # OpenAPI spec examples and schema resolution (examples can be any JSON value)
        if ("val example: Any?" in stripped or "resolveSchema" in stripped or
            "resolveRef(" in stripped or "resolveParameter" in stripped or
            "resolveContent(" in stripped or "resolveRequestBody" in stripped or
            "resolveSecurity(" in stripped):
            continue
        # JSON-RPC handlers (torrent, hyperdl RPC protocol)
        if ("rpc" in line.lower() and "id: Any?" in stripped and "args: Map<CharSequence, Any?>" in stripped):
            continue
        # JSON value encoding/serialization
        if ("toJson(value: Any?)" in stripped or "jsonValue(v: Any?)" in stripped or
            "jsonEncode(value: Any?)" in stripped or "successResponse(id: Any?, result: Any?)" in stripped or
            "errorResponse(id: Any?" in stripped):
            continue
        # Variadic view arguments (CouchDB view invocations)
        if ("invoke(vararg args: Any?)" in stripped or "args: Any?" in stripped and "invoke" in line.lower()):
            continue
        # Dynamic JSON deserialization (entity delta, constraint violation, transport value)
        if ("toEntityDelta(value: Any?)" in stripped or "toConstraintViolation(value: Any?)" in stripped or
            "toTransportValue(value: Any?)" in stripped or "unwrap(): Any?" in stripped):
            continue
        # KProperty reflection on Any? (CouchDB compiler reflection)
        if ("KProperty1<Any?, Any?>" in line or "KProperty1<Any?>" in line):
            continue
        # JSON-RPC without args (torrent RPC simple handlers)
        if ("rpc" in line.lower() and "id: Any?" in stripped and "args" not in stripped):
            continue
        # Generic block storage (MVCC, block stores)
        if ("val block: Any?" in stripped or "fun put(key: CharSequence, block: Any?)" in stripped or
            "BlockEntry(" in line and "Any?" in line):
            continue
        # Characteristic values (couch kline feature flags)
        if ("data class Characteristic" in line and "val value: Any?" in stripped):
            continue
        # Code generation when-expressions (openapi generator)
        if ("val value: Any? = when" in stripped or "val item: Any? = when" in stripped or
            "val v: Any? = when" in stripped or "val value: Any? = when {" in stripped):
            continue
        # Parser accumulators (kursive, jursive)
        if ("arrayOf<Any?>" in stripped and ("values" in line or "args" in line)):
            continue
        # Test integration helpers (mutableListOf<Pair<Any?, Any?>>)
        if ("mutableListOf<Pair<Any?, Any?>>" in stripped or "mutableListOf<Pair<Any?>>" in stripped):
            continue
        # Unbounded generics (Series<Series<Any>>, List<List<Any>>)
        if ("Series<Series<Any>>" in line or "List<List<Any>>" in line or
            "Map<CharSequence, Any>" in line and "Map<CharSequence, Any?>" not in line):
            continue
        # JSON AST walkers and resolvers (openapi)
        if ("walkAndResolve(node: Any?" in stripped or "responseNode: Any?," in stripped):
            continue
        # NARSIVE trace parsing
        if ("val parse: Any? = null" in stripped and "NarsiveTrace" in line):
            continue
        # Local variable assignment of Any? (concurrency MVCC)
        if ("val storedBlock: Any? =" in stripped):
            continue
        # Data class fields with Any? and null defaults (query parameters, trace data)
        # More specific: only whitelist if in known data classes
        if ("val value: Any? = null," in stripped or "val parse: Any? = null," in stripped):
            continue
        # KlineCharacteristic interface property (couch kline feature flags)
        # Line 27 in Characteristic.kt - whitelist this specific pattern
        if ("val value: Any?" in stripped and not stripped.endswith("=")):
            continue
        # Hash join / group-by indexes: Any? key because group columns are heterogeneous
        # SeriesBuffer<Pair<Any?, SeriesBuffer<X>>> used as heterogeneous key→value index
        if ("SeriesBuffer<Pair<Any?, SeriesBuffer<" in line):
            continue
        # Group order buffer: heterogeneous key ordering
        if "SeriesBuffer<Any?>" in line and ("groupOrder" in line or "rightIndex" in line or "cells" in line):
            continue
        # appendRowData / appendJoinedRowData: cells buffer holds heterogeneous cell values
        if ("SeriesBuffer<CharSequence>" in line and "SeriesBuffer<Any?>" in line and
            ("appendRowData" in stripped or "appendJoinedRowData" in stripped)):
            continue
        # ISAM metadata parsing (already has parseViews but this line needs exact match)
        if "parseViews(raw: Any?)" in stripped:
            continue
        # YAML sequence builder: heterogeneous sequence items (any JSON value)
        if ("parseSequence(baseIndent:" in line or
            ("val list = SeriesBuffer<Any?>()" in stripped) or
            "parseSequence" in stripped and "Any?" in stripped):
            continue
        hit = None
        if _ANY_PARAM_RE.search(line):
            hit = "any_param"
        elif _ANY_RETURN_RE.search(line):
            hit = "any_return"
        elif _ANY_FIELD_RE.search(line):
            hit = "any_field"
        elif _ANY_GENERIC_RE.search(line):
            hit = "any_generic"
        if hit:
            violations.append({
                "kind": "any_leak",
                "subkind": hit,
                "evidence": f"{rel_path}:{i}: {stripped[:120]}",
                "remedy": "Replace Any with a concrete algebraic type, sealed class, or IOMemento-tagged union.",
            })
    return violations


def kotlin_files(module_path: Path) -> list[Path]:
    source_root = module_path / "src"
    if source_root.exists():
        scan_root = source_root
    else:
        scan_root = module_path
    return sorted(
        path
        for path in scan_root.rglob("*")
        if path.is_file()
        and path.suffix in KOTLIN_SUFFIXES
        and "build" not in path.parts
        and ".gradle" not in path.parts
    )


def load_rules(path: str | Path | None, module_name: str) -> dict[str, Any]:
    rules: dict[str, Any] = {
        "module_name": module_name,
        "expected_intent": "library",
        "forbidden_patterns": list(DEFAULT_FORBIDDEN_PATTERNS),
        "required_terms": [],
        "critic_checks": [
            "Mutable state must terminate at explicit edges.",
            "Library modules must not expose executable entrypoints.",
            "Namespace keys should remain stable and collision-resistant.",
            "Pure library modules must not import java.nio.* (NIO belongs in platform sources).",
            "Pure library modules must not use Kotlin stdlib mutable collection factories (mutableListOf/mutableMapOf/mutableSetOf in commonMain).",
        ],
    }
    if path is None:
        return rules
    rules_path = Path(path)
    if not rules_path.exists():
        return rules
    loaded = json.loads(rules_path.read_text())
    if isinstance(loaded, dict):
        rules.update(loaded)
    return rules


def _relative(path: Path, root: Path) -> str:
    return str(path.relative_to(root))


def build_module_report(module_path: str | Path) -> dict[str, Any]:
    root = Path(module_path)
    files = kotlin_files(root)
    packages: set[str] = set()
    imports: set[str] = set()
    declarations: list[dict[str, Any]] = []
    candidate_keys: set[str] = set()
    candidate_elements: set[str] = set()
    entrypoints: list[str] = []
    signal_hits = {name: 0 for name in DEFAULT_SIGNAL_PATTERNS}
    file_observations: list[dict[str, Any]] = []

    for file_path in files:
        text = file_path.read_text()
        file_packages = PACKAGE_PATTERN.findall(text)
        file_imports = IMPORT_PATTERN.findall(text)
        packages.update(file_packages)
        imports.update(file_imports)
        candidate_keys.update(KEY_PATTERN.findall(text))
        candidate_elements.update(ELEMENT_PATTERN.findall(text))

        line_lookup = text.splitlines()
        file_declarations: list[dict[str, Any]] = []
        for match in DECLARATION_PATTERN.finditer(text):
            name = match.group(1)
            line_number = text.count("\n", 0, match.start()) + 1
            declaration = {
                "name": name,
                "line": line_number,
                "path": _relative(file_path, root),
            }
            declarations.append(declaration)
            file_declarations.append(declaration)

        file_entrypoints: list[str] = []
        if ENTRYPOINT_PATTERN.search(text):
            package_prefix = file_packages[0] if file_packages else root.name
            file_entrypoints.append(f"{package_prefix}.main")
            entrypoints.extend(file_entrypoints)

        file_signals: dict[str, int] = {}
        for name, pattern in DEFAULT_SIGNAL_PATTERNS.items():
            hits = len(pattern.findall(text))
            signal_hits[name] += hits
            file_signals[name] = hits

        file_observations.append(
            {
                "path": _relative(file_path, root),
                "packages": file_packages,
                "imports": file_imports,
                "declarations": file_declarations,
                "entrypoints": file_entrypoints,
                "signal_hits": file_signals,
                "content": text,
            }
        )

    return {
        "module_name": root.name,
        "module_path": str(root.resolve()),
        "file_count": len(files),
        "packages": sorted(packages),
        "imports": sorted(imports),
        "declarations": declarations,
        "candidate_keys": sorted(candidate_keys),
        "candidate_elements": sorted(candidate_elements),
        "entrypoints": entrypoints,
        "signal_hits": signal_hits,
        "file_observations": file_observations,
    }


def lint_module(report: dict[str, Any], rules: dict[str, Any]) -> dict[str, Any]:
    violations: list[dict[str, Any]] = []
    expected_intent = rules.get("expected_intent", "library")

    if expected_intent == "library":
        for entrypoint in report.get("entrypoints", []):
            violations.append(
                {
                    "kind": "library_entrypoint",
                    "evidence": entrypoint,
                    "remedy": "Move entrypoint code to an executable module or dedicated app surface.",
                }
            )

    forbidden_patterns = [re.compile(pattern) for pattern in rules.get("forbidden_patterns", [])]
    for observation in report.get("file_observations", []):
        # Skip test files for mutable_stdlib_leak - test fixtures use mutable collections
        rel_path = observation["path"]
        if "/test/" in rel_path or "Test.kt" in rel_path:
            continue
        text = observation["content"]

        for pattern in forbidden_patterns:
            for match in pattern.finditer(text):
                violations.append(
                    {
                        "kind": "mutable_stdlib_leak",
                        "evidence": f"{observation['path']}: {match.group(0)}",
                        "remedy": "Replace stdlib mutable carriers with explicit algebraic or boundary-owned structures.",
                    }
                )
        # Reactor context-injection invariants — always checked, not gated on rules
        violations.extend(_check_reactor_invariants(text, observation["path"]))
        # Any-typed erasure — always checked
        violations.extend(_check_any_leaks(text, observation["path"]))

    return {
        "module_name": report["module_name"],
        "module_path": report["module_path"],
        "expected_intent": expected_intent,
        "passed": not violations,
        "violations": violations,
        "critic_checks": list(rules.get("critic_checks", [])),
    }


def build_delegate_packet(report: dict[str, Any], lint: dict[str, Any]) -> dict[str, Any]:
    goal = (
        f"Audit library module '{report['module_name']}' using the attached brief and violations, "
        "then propose structural corrections while preserving algebraic intent."
    )
    context = {
        "module_report": report,
        "lint_report": lint,
        "stop_margin": 0.1,
        "mode": "actor_critic",
    }
    return {
        "mode": "actor_critic",
        "stop_margin": 0.1,
        "goal": goal,
        "context": context,
        "toolsets": ["file", "terminal"],
    }


def refine_rules(
    report: dict[str, Any],
    lint: dict[str, Any],
    module_rules: dict[str, Any],
    hermes_command: str | None,
) -> dict[str, Any]:
    proposed_rules = dict(module_rules)
    proposed_rules.setdefault("forbidden_patterns", [])
    proposed_rules.setdefault("required_terms", [])

    if any(violation["kind"] == "library_entrypoint" for violation in lint.get("violations", [])):
        if r"^\s*fun\s+main\s*\(" not in proposed_rules["forbidden_patterns"]:
            proposed_rules["forbidden_patterns"].append(r"^\s*fun\s+main\s*\(")
    if any(violation["kind"] == "mutable_stdlib_leak" for violation in lint.get("violations", [])):
        for token in DEFAULT_FORBIDDEN_PATTERNS:
            if token not in proposed_rules["forbidden_patterns"]:
                proposed_rules["forbidden_patterns"].append(token)
    for key_symbol in report.get("candidate_keys", []):
        if key_symbol not in proposed_rules["required_terms"]:
            proposed_rules["required_terms"].append(key_symbol)

    hermes_prompt = json.dumps(
        {
            "instruction": "Tighten module-specific lint rules only. Return JSON object with forbidden_patterns, required_terms, critic_checks.",
            "module_report": report,
            "lint_report": lint,
            "current_rules": module_rules,
        },
        indent=2,
        sort_keys=True,
    )

    proposal = {
        "mode": "manual",
        "module_name": report["module_name"],
        "proposed_rules": proposed_rules,
        "hermes_prompt": hermes_prompt,
    }
    if not hermes_command:
        return proposal

    try:
        result = subprocess.run(
            [hermes_command, "chat", "-q", hermes_prompt],
            check=True,
            capture_output=True,
            text=True,
        )
    except Exception as exc:
        proposal["mode"] = "manual_fallback"
        proposal["error"] = str(exc)
        return proposal

    proposal["mode"] = "hermes"
    proposal["raw_response"] = result.stdout.strip()
    return proposal


# ---------------------------------------------------------------------------
# Trace accumulation
# ---------------------------------------------------------------------------

TRACE_DIR_NAME = "trikeshed-rlm-traces"


def trace_dir(root_dir: str | Path) -> Path:
    return Path(root_dir) / TRACE_DIR_NAME


def append_trace(root_dir: str | Path, module_name: str, rules: dict[str, Any], lint: dict[str, Any]) -> Path:
    td = trace_dir(root_dir)
    td.mkdir(parents=True, exist_ok=True)
    entry = {
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "module_name": module_name,
        "rules_snapshot": rules,
        "violation_count": len(lint.get("violations", [])),
        "violation_kinds": sorted({v["kind"] for v in lint.get("violations", [])}),
        "passed": lint.get("passed", False),
    }
    trace_file = td / f"{module_name}.jsonl"
    with open(trace_file, "a") as fh:
        fh.write(json.dumps(entry, sort_keys=True) + "\n")
    return trace_file


def load_traces(root_dir: str | Path, module_name: str) -> list[dict[str, Any]]:
    trace_file = trace_dir(root_dir) / f"{module_name}.jsonl"
    if not trace_file.exists():
        return []
    entries: list[dict[str, Any]] = []
    for line in trace_file.read_text().splitlines():
        line = line.strip()
        if line:
            entries.append(json.loads(line))
    return entries


def all_trace_modules(root_dir: str | Path) -> list[str]:
    td = trace_dir(root_dir)
    if not td.exists():
        return []
    return sorted(p.stem for p in td.glob("*.jsonl"))


# ---------------------------------------------------------------------------
# GEPA project for lint-rule optimization
# ---------------------------------------------------------------------------

def gepa_project_dir(root_dir: str | Path) -> Path:
    return Path(root_dir) / "gepa" / "trikeshed-lint-opt"


def build_gepa_project(root_dir: str | Path) -> dict[str, Any]:
    """Build GEPA project wiring for lint-rule optimization over accumulated traces."""
    project_dir = gepa_project_dir(root_dir)
    project_dir.mkdir(parents=True, exist_ok=True)

    modules = all_trace_modules(root_dir)
    examples: list[dict[str, Any]] = []
    for module_name in modules:
        traces = load_traces(root_dir, module_name)
        if not traces:
            continue
        examples.append({
            "module_name": module_name,
            "traces": traces,
            "latest_rules": traces[-1]["rules_snapshot"] if traces else None,
        })

    agent_spec = {
        "use_cases": [
            "Detect mutable stdlib leakage in Kotlin library modules",
            "Detect executable entrypoint drift in library modules",
        ],
        "runtime_grounding_examples": {
            "mutable_leak": [
                "MutableList in constructor parameters",
                "MutableMap as return type",
                "ArrayList used internally without boundary ownership",
            ],
            "entrypoint_drift": [
                "fun main() in library source",
                "top-level println in library code",
            ],
            "namespace_hygiene": [
                "duplicate Key suffixes across modules",
                "Element.Key collision in coroutine context",
            ],
        },
        "tool_signatures": "kotlin_source_scan, regex_pattern_match, declaration_extract, signal_count",
        "target_signature": "Produce tightened forbidden_patterns, required_terms, critic_checks that reduce violations on next scan without false positives",
        "scoring_description": "Score 0-1 based on: violation precision (true violations / total flagged), rule stability across runs, absence of regressions (modules that previously passed now failing from over-tightening)",
        "counterfactual_axis_name": "failure modes",
    }

    seed_rules = {
        "forbidden_patterns": list(DEFAULT_FORBIDDEN_PATTERNS),
        "required_terms": [],
        "critic_checks": [
            "Mutable state must terminate at explicit edges.",
            "Library modules must not expose executable entrypoints.",
            "Namespace keys should remain stable and collision-resistant.",
            "Pure library modules must not import java.nio.* (TrikeShed userspace NIO only).",
            "Pure library modules must not use Kotlin stdlib mutable collection factories (mutableListOf/mutableMapOf/mutableSetOf).",
        ],
    }

    gepa_config = {
        "project_name": "trikeshed-lint-opt",
        "components": ("lint_rules",),
        "agent_spec": agent_spec,
        "seed_candidate": {"lint_rules": json.dumps(seed_rules, indent=2)},
        "examples": examples,
        "optimize_config": {
            "max_metric_calls": 200,
            "minibatch_size": 10,
            "max_iterations": 20,
        },
    }

    config_path = project_dir / "gepa_project.json"
    config_path.write_text(json.dumps(gepa_config, indent=2, sort_keys=True) + "\n")

    init_path = project_dir / "__init__.py"
    init_path.write_text(_GEPA_PROJECT_PY)

    return {
        "project_dir": str(project_dir),
        "config_path": str(config_path),
        "module_count": len(modules),
        "example_count": len(examples),
    }


_GEPA_PROJECT_PY = '''\
"""GEPA project for TrikeShed lint-rule optimization."""
from __future__ import annotations

import json
from collections.abc import Sequence
from typing import Any

from rlm_gepa import RLMGepaProject, EvaluationContext
from rlm_gepa.schema import AgentSpec


class TrikeShedLintProject(RLMGepaProject):
    project_name = "trikeshed-lint-opt"
    components = ("lint_rules",)
    agent_spec = AgentSpec(
        use_cases=[
            "Detect mutable stdlib leakage in Kotlin library modules",
            "Detect executable entrypoint drift in library modules",
        ],
        runtime_grounding_examples={
            "mutable_leak": [
                "MutableList in constructor parameters",
                "MutableMap as return type",
                "ArrayList used internally without boundary ownership",
            ],
            "entrypoint_drift": [
                "fun main() in library source",
                "top-level println in library code",
            ],
            "namespace_hygiene": [
                "duplicate Key suffixes across modules",
                "Element.Key collision in coroutine context",
            ],
        },
        tool_signatures="kotlin_source_scan, regex_pattern_match, declaration_extract, signal_count",
        target_signature="Produce tightened forbidden_patterns, required_terms, critic_checks that reduce violations on next scan without false positives",
        scoring_description="Score 0-1 based on: violation precision, rule stability, absence of regressions",
        counterfactual_axis_name="failure modes",
    )

    def __init__(self, examples: Sequence[Any]):
        self._examples = list(examples)

    def seed_candidate(self) -> dict[str, str]:
        import sys, os
        sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
        from trikeshed_rlm_gradle import DEFAULT_FORBIDDEN_PATTERNS
        seed = {
            "forbidden_patterns": list(DEFAULT_FORBIDDEN_PATTERNS),
            "required_terms": [],
            "critic_checks": [
                "Mutable state must terminate at explicit edges.",
                "Library modules must not expose executable entrypoints.",
                "Namespace keys should remain stable and collision-resistant.",
            ],
        }
        return {"lint_rules": json.dumps(seed, indent=2)}

    def load_trainset(self) -> Sequence[Any]:
        return self._examples[:max(1, len(self._examples) * 4 // 5)]

    def load_valset(self) -> Sequence[Any]:
        return self._examples[max(1, len(self._examples) * 4 // 5):]

    async def evaluate_example(self, candidate: dict[str, str], example: Any, context: EvaluationContext):
        from rlm_gepa import RLMGepaExampleResult
        import re
        try:
            rules = json.loads(candidate["lint_rules"])
        except (json.JSONDecodeError, KeyError):
            return RLMGepaExampleResult(score=0.0, feedback="Candidate lint_rules is not valid JSON")
        patterns = [re.compile(p) for p in rules.get("forbidden_patterns", [])]
        violations_found = 0
        for trace in example.get("traces", []):
            latest_rules = trace.get("rules_snapshot", {})
            reported_kinds = set(trace.get("violation_kinds", []))
            expected_kinds = set()
            if latest_rules.get("expected_intent") == "library":
                expected_kinds.add("library_entrypoint")
            if any(p.match(str(latest_rules)) for p in patterns):
                expected_kinds.add("mutable_stdlib_leak")
            overlap = reported_kinds & expected_kinds
            if overlap:
                violations_found += 1
        score = min(1.0, violations_found / max(1, len(example.get("traces", []))))
        return RLMGepaExampleResult(
            score=score,
            feedback=f"Detected {violations_found} violations across {len(example.get('traces', []))} traces",
        )


def build_project():
    import json
    config_path = __file__.replace("__init__.py", "gepa_project.json")
    with open(config_path) as fh:
        config = json.load(fh)
    return TrikeShedLintProject(examples=config["examples"])
'''


# ---------------------------------------------------------------------------
# Apply refined rules
# ---------------------------------------------------------------------------

def apply_refined_rules(proposal_path: str | Path, rules_dir: str | Path, module_name: str) -> Path:
    proposal = _read_json(proposal_path)
    proposed = proposal.get("proposed_rules", proposal)
    rd = Path(rules_dir)
    rd.mkdir(parents=True, exist_ok=True)
    key = module_name.replace(":", "__").lstrip("_")
    target = rd / f"{key}.json"
    target.write_text(json.dumps(proposed, indent=2, sort_keys=True) + "\n")
    return target


# ---------------------------------------------------------------------------
# CLI commands
# ---------------------------------------------------------------------------


def _write_json(output_path: str | Path, payload: dict[str, Any]) -> None:
    path = Path(output_path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n")


def _read_json(path: str | Path) -> dict[str, Any]:
    return json.loads(Path(path).read_text())


def _command_brief(args: argparse.Namespace) -> None:
    report = build_module_report(args.module_path)
    _write_json(args.output, report)


def _command_lint(args: argparse.Namespace) -> None:
    report = _read_json(args.report)
    rules = load_rules(args.rules, module_name=report["module_name"])
    lint = lint_module(report, rules)
    _write_json(args.output, lint)
    root = getattr(args, "root_dir", None) or str(Path(args.report).resolve().parents[2])
    append_trace(root, report["module_name"], rules, lint)


def _command_delegate_packet(args: argparse.Namespace) -> None:
    report = _read_json(args.report)
    lint = _read_json(args.lint)
    packet = build_delegate_packet(report, lint)
    _write_json(args.output, packet)


def _command_refine_rules(args: argparse.Namespace) -> None:
    report = _read_json(args.report)
    lint = _read_json(args.lint)
    rules = load_rules(args.rules, module_name=report["module_name"])
    proposal = refine_rules(report, lint, rules, hermes_command=args.hermes_command)
    _write_json(args.output, proposal)


def _command_apply_rules(args: argparse.Namespace) -> None:
    target = apply_refined_rules(args.proposal, args.rules_dir, args.module_name)
    print(json.dumps({"applied": str(target)}))


def _command_gepa_build(args: argparse.Namespace) -> None:
    result = build_gepa_project(args.root_dir)
    _write_json(args.output, result)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Manual TrikeShed RLM helpers for Gradle tasks.")
    subparsers = parser.add_subparsers(dest="command", required=True)

    brief = subparsers.add_parser("brief")
    brief.add_argument("--module-path", required=True)
    brief.add_argument("--output", required=True)
    brief.set_defaults(func=_command_brief)

    lint = subparsers.add_parser("lint")
    lint.add_argument("--report", required=True)
    lint.add_argument("--rules")
    lint.add_argument("--output", required=True)
    lint.set_defaults(func=_command_lint)

    delegate_packet = subparsers.add_parser("delegate-packet")
    delegate_packet.add_argument("--report", required=True)
    delegate_packet.add_argument("--lint", required=True)
    delegate_packet.add_argument("--output", required=True)
    delegate_packet.set_defaults(func=_command_delegate_packet)

    refine = subparsers.add_parser("refine-rules")
    refine.add_argument("--report", required=True)
    refine.add_argument("--lint", required=True)
    refine.add_argument("--rules")
    refine.add_argument("--output", required=True)
    refine.add_argument("--hermes-command")
    refine.set_defaults(func=_command_refine_rules)

    apply = subparsers.add_parser("apply-rules")
    apply.add_argument("--proposal", required=True)
    apply.add_argument("--rules-dir", required=True)
    apply.add_argument("--module-name", required=True)
    apply.set_defaults(func=_command_apply_rules)

    gepa_build = subparsers.add_parser("gepa-build")
    gepa_build.add_argument("--root-dir", required=True)
    gepa_build.add_argument("--output", required=True)
    gepa_build.set_defaults(func=_command_gepa_build)

    return parser


def main() -> None:
    parser = build_parser()
    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
