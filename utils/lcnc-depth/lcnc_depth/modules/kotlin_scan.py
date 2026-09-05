"""Lexical scanners for the four Kotlin patterns this agent reasons about.

Mounted into the RLM sandbox as an importable module. Pure stdlib `re` on
purpose: there is no Kotlin parser available in Pyodide, and none is needed —
every pattern below is lexically regular. These functions LOCATE and CLASSIFY;
naming the actual Kotlin type of a value is left to the model reading the
surrounding lines, because that genuinely needs comprehension.

Each function takes source text plus an optional `path` label and returns a
list of dicts carrying at least ``line`` and ``snippet``, so every downstream
claim can cite ``file:line``.
"""

from __future__ import annotations

import re

__all__ = [
    "context_demands",
    "supervision",
    "casts",
    "contracts",
    "runner_registry",
    "ccek_surface",
    "ccek_coverage",
    "scan_all",
]


def _lineno(text: str, pos: int) -> int:
    return text.count("\n", 0, pos) + 1


def _mask(text: str, keep_strings: bool = False) -> str:
    """Blank the CONTENT of comments and string literals, preserving offsets.

    Length and newlines are preserved so every offset — and therefore every
    reported line number — stays valid against the ORIGINAL text.

    This exists because a scanner that reads inside strings reports the prose in
    an error message as if it were code. Concretely, the validator's own advice
    string, "Use SupervisorJob() as a parent in your scope.", was being reported
    as a detached SupervisorJob root. Kotlin raw strings (\"\"\"…\"\"\") are
    handled explicitly; they are common in this codebase and a naive quote
    counter mis-nests on them.
    """
    out = list(text)
    i, n = 0, len(text)
    in_line = in_block = in_str = in_raw = False
    esc = False
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if in_line:
            if c == "\n":
                in_line = False
            else:
                out[i] = " "
        elif in_block:
            if c == "*" and nxt == "/":
                out[i] = out[i + 1] = " "
                i += 2
                in_block = False
                continue
            if c != "\n":
                out[i] = " "
        elif in_raw:
            if text.startswith('"""', i):
                i += 3
                in_raw = False
                continue
            if c != "\n" and not keep_strings:
                out[i] = " "
        elif in_str:
            if esc:
                esc = False
                if not keep_strings:
                    out[i] = " "
            elif c == "\\":
                esc = True
                if not keep_strings:
                    out[i] = " "
            elif c == '"':
                in_str = False
            elif c == "\n":
                in_str = False  # unterminated: do not swallow the rest of the file
            elif not keep_strings:
                out[i] = " "
        else:
            if text.startswith('"""', i):
                in_raw = True
                i += 3
                continue
            if c == '"':
                in_str = True
            elif c == "/" and nxt == "/":
                in_line = True
                out[i] = out[i + 1] = " "
                i += 2
                continue
            elif c == "/" and nxt == "*":
                in_block = True
                out[i] = out[i + 1] = " "
                i += 2
                continue
        i += 1
    return "".join(out)


def _line_at(text: str, pos: int) -> str:
    start = text.rfind("\n", 0, pos) + 1
    end = text.find("\n", pos)
    return text[start : end if end != -1 else len(text)].strip()


# ── 1. context demands ──────────────────────────────────────────────────

_CTX = re.compile(r"currentCoroutineContext\(\)\s*\[\s*([A-Za-z_][\w.]*)\s*\]")
_COROUTINE_CTX = re.compile(r"coroutineContext\s*\[\s*([A-Za-z_][\w.]*)\s*\]")
_ERR_STR = re.compile(r'(?:error|IllegalStateException)\s*\(\s*"((?:[^"\\]|\\.)*)"')


def context_demands(text: str, path: str = "") -> list[dict]:
    """Every read of a CoroutineContext element, classified by what absence costs.

    Severity is decided by what follows the read, within a small window:

      ``?: error(`` / ``?: throw``   → ``throws``        (the node cannot run)
      ``?: return`` / ``?: <expr>``  → ``silent-degrade`` (it runs, differently)
      bare read / ``?.``             → ``optional``

    The distinction matters more than it looks: a `throws` demand unsatisfied by
    the host scope is a program that cannot work, while a `silent-degrade` one
    is a program that works and quietly stops metering.
    """
    # Comments masked, strings KEPT — the verbatim error string is the evidence.
    scan = _mask(text, keep_strings=True)
    out: list[dict] = []
    for pattern in (_CTX, _COROUTINE_CTX):
        for m in pattern.finditer(scan):
            key = m.group(1)
            tail = scan[m.end() : m.end() + 220]
            if re.match(r"\s*\?:\s*(?:error|throw)\b", tail):
                severity = "throws"
            elif re.match(r"\s*\?:\s*return\b", tail):
                severity = "silent-degrade"
            elif re.match(r"\s*\?:", tail):
                severity = "silent-degrade"
            else:
                severity = "optional"
            err = _ERR_STR.search(tail)
            out.append(
                {
                    "path": path,
                    "line": _lineno(scan, m.start()),
                    "element_key": key,
                    "severity": severity,
                    "error_message": err.group(1) if err and severity == "throws" else None,
                    "snippet": _line_at(text, m.start()),
                }
            )
    out.sort(key=lambda d: d["line"])
    return out


# ── 2. supervision ──────────────────────────────────────────────────────

_SUPERVISOR = re.compile(r"SupervisorJob\s*\(([^)]*)\)")
_SUPERVISOR_SCOPE = re.compile(r"\bsupervisorScope\s*\{")
_CATCH_THROWABLE = re.compile(r"catch\s*\(\s*\w+\s*:\s*Throwable\s*\)")


def supervision(text: str, path: str = "") -> list[dict]:
    """Failure-isolation sites, classified by MECHANISM.

    The classification is the finding. ``detached-root`` — ``SupervisorJob()``
    with no parent argument — reads identically to the parented form and behaves
    oppositely: a parent cancel never reaches it. ``try-catch`` is isolation by
    convention, holding only while nothing escapes the catch.
    """
    # Fully masked: a SupervisorJob() named inside an error message or a comment
    # is prose about code, not code.
    scan = _mask(text)
    out: list[dict] = []
    for m in _SUPERVISOR.finditer(scan):
        arg = m.group(1).strip()
        detached = arg == ""
        line_text = _line_at(scan, m.start())
        # Not every parentless SupervisorJob is a defect. Classifying WHERE it
        # sits is the difference between a real orphan and a correct root:
        #   default-parameter    — root only when the caller supplies no scope
        #   conditional-fallback — the else of `if (parent == null)`, correct
        #   top-level-val        — a process-lifetime global; nothing can cancel it
        #   entry-point          — main()/server bootstrap, where a root is right
        if detached:
            if re.search(r"^\s*\w+\s*:\s*CoroutineScope\s*=", line_text) or re.search(
                r"\w+\s*:\s*CoroutineScope\s*=\s*CoroutineScope\(", line_text
            ):
                position = "default-parameter"
            elif "if (" in line_text and "else" in line_text:
                position = "conditional-fallback"
            elif re.match(r"^(?:private\s+|internal\s+)?val\s", line_text):
                position = "top-level-val"
            else:
                position = "inline"
        else:
            position = "parented"
        suspicious = detached and position in ("top-level-val", "inline")
        out.append(
            {
                "path": path,
                "line": _lineno(scan, m.start()),
                "mechanism": "detached-root" if detached else "supervisor-job",
                "position": position,
                "suspicious": suspicious,
                "parent_arg": arg or None,
                "isolates_siblings": True,
                # A detached root over-isolates: nothing above can cancel it.
                "discount": 1.0 if detached else 0.8,
                "caveat": (
                    "SupervisorJob() with no parent is a DETACHED ROOT — a parent "
                    "cancel will never reach this scope"
                    if suspicious
                    else f"parentless, but positioned as a {position} — root only "
                    "when no parent is supplied"
                )
                if detached
                else None,
                "snippet": _line_at(text, m.start()),
            }
        )
    for m in _SUPERVISOR_SCOPE.finditer(scan):
        out.append(
            {
                "path": path,
                "line": _lineno(scan, m.start()),
                "mechanism": "supervisor-job",
                "position": "supervisor-scope",
                "suspicious": False,
                "parent_arg": None,
                "isolates_siblings": True,
                "discount": 0.8,
                "caveat": None,
                "snippet": _line_at(text, m.start()),
            }
        )
    for m in _CATCH_THROWABLE.finditer(scan):
        out.append(
            {
                "path": path,
                "line": _lineno(scan, m.start()),
                "mechanism": "try-catch",
                "position": "catch-block",
                "suspicious": False,
                "parent_arg": None,
                "isolates_siblings": True,
                "discount": 0.5,
                "caveat": (
                    "isolation is catch-based, not structural: it holds only while "
                    "nothing escapes the catch, and it also swallows CancellationException"
                ),
                "snippet": _line_at(text, m.start()),
            }
        )
    out.sort(key=lambda d: d["line"])
    return out


# ── 3. casts ────────────────────────────────────────────────────────────

# Type arguments carry `<`, `>`, `,`, `*` and spaces (`Map<String, String>`), so
# the type is matched lazily up to the first real terminator rather than by a
# character class that would stop inside a generic.
_SAFE_CAST = re.compile(r"\bas\?\s*([A-Za-z_][\w.<>,?*\s]*?)(?=\s*(?:\?:|\)|,|;|$|\n))")
_HARD_CAST = re.compile(r"\bas\s+([A-Za-z_][\w.<>,?*\s]*?)\s*\)")


def casts(text: str, path: str = "") -> list[dict]:
    """Cast sites and, crucially, what silently happens when the cast fails.

    A kind-legal wire that lands the wrong Kotlin type does not usually throw —
    it takes the ``?:`` branch. Recording that fallback is what turns "these two
    ports both say json" into "this tribunal rules on an empty record".
    """
    scan = _mask(text)
    out: list[dict] = []
    for m in _SAFE_CAST.finditer(scan):
        target = re.sub(r"\s+", " ", m.group(1).strip())
        tail = scan[m.end() : m.end() + 120]
        # The idiom is `(x as? T) ?: default`, so the elvis sits AFTER the closing
        # paren of the cast expression. Skipping closing parens is what makes the
        # fallback — the thing that silently happens on mismatch — visible at all.
        # The empty-collection builders take optional type arguments
        # (`emptyList<Any?>()`), which is the form actually used in this codebase.
        fb = re.match(
            r'[\s)]*\?:\s*(""|empty(?:List|Map|Set|Sequence)(?:<[^>]*>)?\(\)|null|\S+)', tail
        )
        fallback = fb.group(1) if fb else None
        if fallback is None or fallback == "null":
            on_fail = "silent-null"
        elif fallback == '""' or fallback.startswith("empty"):
            on_fail = "silent-empty"
        elif fallback.startswith(("error", "throw", "require", "check")):
            on_fail = "throw"
        else:
            on_fail = "silent-default"
        out.append(
            {
                "path": path,
                "line": _lineno(scan, m.start()),
                "cast": f"as? {target}",
                "checked": True,
                "fallback": fallback,
                "on_cast_failure": on_fail,
                "snippet": _line_at(text, m.start()),
            }
        )
    for m in _HARD_CAST.finditer(scan):
        out.append(
            {
                "path": path,
                "line": _lineno(scan, m.start()),
                "cast": f"as {re.sub(r'\\s+', ' ', m.group(1).strip())}",
                "checked": False,
                "fallback": None,
                # Erasure defers the ClassCastException to first element use,
                # so the throw lands far from the wire that caused it.
                "on_cast_failure": "deferred-CCE",
                "snippet": _line_at(text, m.start()),
            }
        )
    out.sort(key=lambda d: d["line"])
    return out


# ── 4. the contract table ───────────────────────────────────────────────

_CONTRACT = re.compile(r"LcncPortContract\s*\(")
_PAIR = re.compile(r'"([^"]*)"\s+to\s+("([^"]*)"|[A-Za-z_][\w.]*)')
_STR = re.compile(r'"([^"]*)"')
_CONST = re.compile(r'(?:private\s+)?(?:const\s+)?val\s+([A-Z][A-Z0-9_]*)\s*=\s*"([^"]*)"')
_NAMED = re.compile(r"^([A-Za-z_]\w*)\s*=\s*(.*)$", re.DOTALL)

# Positional parameter order of the data class, used when a literal does not
# name its arguments — which most of the table does not. The full order matters:
# `cardinality` is routinely passed as the FIFTH positional argument, so a
# shorter tuple silently drops every MANY declaration in the table.
_POSITIONAL = (
    "type",
    "title",
    "inputs",
    "outputs",
    "cardinality",
    "functions",
    "inputKinds",
    "outputKinds",
    "params",
    "isSource",
    "isSink",
    "wide",
)


def _strip_comments(text: str) -> str:
    """Remove // and /* */ comments without touching string literals.

    The contract table is heavily commented INSIDE the literals, and a comment
    containing a paren or quote derails any naive split.
    """
    out, i, n = [], 0, len(text)
    in_str = in_line = in_block = False
    esc = False
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if in_line:
            if c == "\n":
                in_line = False
                out.append(c)
        elif in_block:
            if c == "*" and nxt == "/":
                in_block = False
                i += 1
        elif in_str:
            out.append(c)
            if esc:
                esc = False
            elif c == "\\":
                esc = True
            elif c == '"':
                in_str = False
        elif c == '"':
            in_str = True
            out.append(c)
        elif c == "/" and nxt == "/":
            in_line = True
            i += 1
        elif c == "/" and nxt == "*":
            in_block = True
            i += 1
        else:
            out.append(c)
        i += 1
    return "".join(out)


def _split_args(body: str) -> list[str]:
    """Split a call's argument list on TOP-LEVEL commas only."""
    parts: list[str] = []
    cur: list[str] = []
    depth = 0
    in_str = False
    esc = False
    for c in body:
        if in_str:
            cur.append(c)
            if esc:
                esc = False
            elif c == "\\":
                esc = True
            elif c == '"':
                in_str = False
            continue
        if c == '"':
            in_str = True
            cur.append(c)
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        if c == "," and depth == 0:
            parts.append("".join(cur).strip())
            cur = []
            continue
        cur.append(c)
    if cur:
        parts.append("".join(cur).strip())
    return [p for p in parts if p]


def _list_value(expr: str) -> list[str]:
    if expr.startswith("emptyList"):
        return []
    return _STR.findall(expr)


def _map_value(expr: str) -> dict[str, str]:
    if expr.startswith("emptyMap"):
        return {}
    out: dict[str, str] = {}
    for pm in _PAIR.finditer(expr):
        key = pm.group(1).removesuffix("?")
        out[key] = pm.group(3) if pm.group(3) is not None else pm.group(2)
    return out


def _balanced(text: str, open_pos: int) -> str:
    """The text between the paren at `open_pos` and its match."""
    depth, i, n = 0, open_pos, len(text)
    while i < n:
        c = text[i]
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return text[open_pos + 1 : i]
        i += 1
    return text[open_pos + 1 :]


def contracts(text: str, path: str = "") -> list[dict]:
    """Parse `LcncPortContract(...)` literals out of the vocabulary table.

    Most of the table uses POSITIONAL arguments with an identifier constant for
    the type (`LcncPortContract(SCOPE, "scope …", listOf(…), listOf(…), …)`), so
    both forms are handled and file-local `val NAME = "…"` constants are resolved.
    A type that is a computed expression (string concatenation) is kept verbatim
    and flagged `type_resolved=False` rather than guessed at.

    Kind maps are keyed by the DE-SUFFIXED port name while inputs/outputs carry
    the trailing "?", so callers must strip before lookup — normalised here.
    """
    clean = _mask(text, keep_strings=True)
    constants = {m.group(1): m.group(2) for m in _CONST.finditer(clean)}

    out: list[dict] = []
    for m in _CONTRACT.finditer(clean):
        # Skip the data class DECLARATION itself — its parameter list looks like
        # a call and would otherwise arrive as a contract named "val type: String".
        line_start = clean.rfind("\n", 0, m.start()) + 1
        if re.search(r"\b(?:data\s+)?class\s*$", clean[line_start : m.start()]):
            continue
        body = _balanced(clean, m.end() - 1)
        if not body.strip():
            continue
        entry: dict = {
            "path": path,
            "line": _lineno(clean, m.start()),
            "type": None,
            "type_resolved": False,
            "title": None,
            "inputs": [],
            "outputs": [],
            "inputKinds": {},
            "outputKinds": {},
            "cardinality": {},
        }
        positional = 0
        for arg in _split_args(body):
            nm = _NAMED.match(arg)
            if nm and nm.group(1) in (
                "type", "title", "inputs", "outputs",
                "inputKinds", "outputKinds", "cardinality",
                "functions", "params", "isSource", "isSink", "wide",
            ):
                name, value = nm.group(1), nm.group(2).strip()
            else:
                if positional >= len(_POSITIONAL):
                    continue
                name, value = _POSITIONAL[positional], arg
                positional += 1

            if name == "type":
                if value.startswith('"'):
                    entry["type"] = _STR.findall(value)[0] if _STR.findall(value) else None
                    entry["type_resolved"] = entry["type"] is not None
                elif value in constants:
                    entry["type"] = constants[value]
                    entry["type_resolved"] = True
                else:
                    # e.g. SubVm.LEGO_PREFIX + "tika" — record it, do not invent it.
                    entry["type"] = value
                    entry["type_resolved"] = False
            elif name == "title":
                found = _STR.findall(value)
                entry["title"] = found[0] if found else value
            elif name in ("inputs", "outputs"):
                entry[name] = _list_value(value)
            elif name in ("inputKinds", "outputKinds", "cardinality"):
                entry[name] = _map_value(value)

        if entry["type"]:
            out.append(entry)
    return out


# ── 5. runner registry ──────────────────────────────────────────────────

_RUNNER = re.compile(r'"([a-zA-Z][\w.]*)"\s+to\s+LcncNodeRunner\s*\{')


def runner_registry(text: str, path: str = "") -> list[dict]:
    """`"node.type" to LcncNodeRunner { … }` entries — the type→behaviour map."""
    return [
        {
            "path": path,
            "line": _lineno(text, m.start()),
            "node_type": m.group(1),
            "snippet": _line_at(text, m.start()),
        }
        for m in _RUNNER.finditer(text)
    ]


# ── 6. the CCEK surface ─────────────────────────────────────────────────

_MODIFIERS = (
    "public", "private", "internal", "protected", "open", "abstract", "sealed",
    "data", "enum", "inline", "suspend", "override", "operator", "infix",
    "lateinit", "const", "companion", "inner", "value", "annotation", "external",
    "tailrec", "expect", "actual",
)
_DECL = re.compile(
    r"^[ \t]*(?P<mods>(?:(?:" + "|".join(_MODIFIERS) + r")\s+)*)"
    r"(?P<kw>class|object|interface|fun|val|var|typealias)\b\s*"
    r"(?P<generics><[^>\n]*>\s*)?"
    r"(?P<receiver>(?:[A-Za-z_][\w.]*(?:<[^>\n]*>)?\.)*)"
    r"(?P<name>[A-Za-z_]\w*)?",
)
_HEADER_PROP = re.compile(
    r"(?<![\w.])(?:(?P<vis>private|protected|internal)\s+)?(?:override\s+)?(?:open\s+)?"
    r"(?:val|var)\s+(?P<name>[A-Za-z_]\w*)\s*:"
)
_HIDDEN = ("private", "protected", "internal")
_TYPE_KW = ("class", "object", "interface")


def ccek_surface(text: str, path: str = "") -> list[dict]:
    """Every PUBLIC declaration in a Kotlin file, with the class it belongs to.

    The CCEK plane is a small set of classes — ``CCEK``, ``ArticulatedNode``,
    ``UserContext``, ``CausalReteTable`` … — and the question this agent has to
    answer is "which of their members can a program reach?". That needs the
    surface enumerated member by member, each with an owner, so a gap can be
    named as ``UserContext.queryPolyglot`` rather than as a bare identifier.

    Lexical, like every other scanner: comments and strings are blanked, brace
    depth tracks nesting, and a declaration counts only when it sits DIRECTLY in
    the enclosing type's body (or at top level) — a ``val`` inside a function is
    a local, not a member. Constructor ``val``/``var`` parameters are public
    properties too and are recorded as ``kind = "property"``. ``private``,
    ``protected`` and ``internal`` declarations are not surface. ``override``
    members are kept and flagged.

    A member whose name ends in ``ForTest`` is flagged ``test_seam`` — the
    codebase's own convention for a hole punched for tests, not a capability.
    """
    scan = _mask(text)
    lines = scan.split("\n")
    raw = text.split("\n")
    pkg_m = re.search(r"^\s*package\s+([\w.]+)", scan, re.M)
    package = pkg_m.group(1) if pkg_m else ""
    out: list[dict] = []
    stack: list[tuple[str, int]] = []  # (owner, depth of the owner's body)
    depth = 0
    i = 0
    n = len(lines)

    def emit(line_no: int, owner: str | None, name: str, kind: str, mods: list[str]) -> None:
        root = stack[0][0] if stack else (name if kind in _TYPE_KW else None)
        if owner and not stack:
            root = owner
        out.append(
            {
                "path": path,
                "package": package,
                "line": line_no,
                "owner": owner,
                "root": root,
                "member": name,
                "qualified": f"{owner}.{name}" if owner else name,
                "kind": kind,
                "override": "override" in mods,
                "suspend": "suspend" in mods,
                "test_seam": name.endswith("ForTest"),
                "snippet": raw[line_no - 1].strip(),
            }
        )

    while i < n:
        line = lines[i]
        owner_depth = stack[-1][1] if stack else 0
        m = _DECL.match(line)
        at_member_depth = depth == owner_depth
        if m and m.group("name") and at_member_depth:
            mods = m.group("mods").split()
            kw = m.group("kw")
            name = m.group("name")
            hidden = any(v in mods for v in _HIDDEN)
            owner = stack[-1][0] if stack else None
            if kw in _TYPE_KW:
                # Consume the header to its balancing paren (constructor
                # properties live there), then decide whether a body opens.
                j, paren, started = i, 0, False
                header_lines: list[str] = []
                while j < n:
                    seg = lines[j]
                    header_lines.append(seg)
                    for ch in seg:
                        if ch == "(":
                            paren += 1
                            started = True
                        elif ch == ")":
                            paren -= 1
                    if started and paren <= 0:
                        break
                    if not started and ("{" in seg or seg.strip().endswith((")", ":")) is False and j > i):
                        break
                    if not started and "{" in seg:
                        break
                    j += 1
                header = "\n".join(header_lines)
                if not hidden:
                    emit(i + 1, owner, name, kw, mods)
                    if "(" in header:
                        body = header[header.find("(") :]
                        for pm in _HEADER_PROP.finditer(body):
                            if pm.group("vis"):
                                continue
                            stack.append((name, depth + 1))  # so root resolves through this type
                            emit(i + 1 + header[: header.find("(") + pm.start()].count("\n"), name, pm.group("name"), "property", [])
                            stack.pop()
                d0 = depth
                for k in range(i, min(j, n - 1) + 1):
                    depth += lines[k].count("{") - lines[k].count("}")
                if depth > d0:
                    stack.append((name if not hidden else f"({name})", depth))
                while stack and depth < stack[-1][1]:
                    stack.pop()
                i = min(j, n - 1) + 1
                continue
            if not hidden:
                emit(i + 1, owner, name, "val" if kw == "var" else kw, mods)
        depth += line.count("{") - line.count("}")
        while stack and depth < stack[-1][1]:
            stack.pop()
        i += 1
    return out


def _mentions(text: str, package: str, root: str, same_package: bool) -> bool:
    """Does this file bring `package.root` into scope?

    An explicit import, a star import of the package, a fully-qualified use, or
    living in the same package. Nothing else counts: `SupervisorJob(` in a file
    that imports kotlinx.coroutines is kotlinx's, not the CCEK interface of the
    same name.
    """
    if same_package:
        return re.search(r"(?<!\w)" + re.escape(root) + r"(?!\w)", text) is not None
    pkg = re.escape(package)
    return (
        re.search(r"^\s*import\s+" + pkg + r"\." + re.escape(root) + r"(?:\.\w+)*\s*$", text, re.M) is not None
        or re.search(r"^\s*import\s+" + pkg + r"\.\*\s*$", text, re.M) is not None
        or re.search(r"(?<!\w)" + pkg + r"\." + re.escape(root) + r"(?!\w)", text) is not None
    )


def _package_of(text: str) -> str:
    m = re.search(r"^\s*package\s+([\w.]+)", text, re.M)
    return m.group(1) if m else ""


def ccek_coverage(surface: list[dict], seams: dict[str, str], everywhere: dict[str, str] | None = None) -> list[dict]:
    """For each surface member, where (if anywhere) the LCNC seams reach it.

    ``seams`` maps a path to the text of a file whose runners are how a program
    touches the plane (every ``lcnc/*.kt`` in this codebase). A member is
    ``reached`` when a seam file brings its ROOT type into scope (an import from
    the surface's package, a star import, a qualified use, or the same package)
    AND calls or reads the member — ``.member(`` / ``.member``. A type is reached
    when such a file names it. Reachability is the fact reported; whether an
    unreached member is a missing lego or substrate is a RULING, and lives with
    the caller.

    ``everywhere`` (optional) is the same map for the whole source tree: a member
    whose root type no file outside its own brings into scope is ``orphan`` —
    vocabulary ahead of implementation, a different finding from a missing lego.

    Plumbing is never a gap: a test seam, a nested ``Key`` object, or an
    ``override`` of a stdlib contract (``toString``, ``key``, ``equals``).
    """
    masked = {p: _mask(t) for p, t in seams.items()}
    tree = {p: _mask(t) for p, t in (everywhere or {}).items()}
    pkgs = {p: _package_of(t) for p, t in {**tree, **masked}.items()}
    out: list[dict] = []
    for row in surface:
        name = row["member"]
        kind = row["kind"]
        root = row.get("root") or name
        package = row.get("package", "")
        is_type = kind in ("class", "object", "interface", "typealias")
        if is_type:
            pattern = re.compile(r"(?<!\w)" + re.escape(name) + r"\s*(?:\(|\.|<|\b(?=\s*[,):]))")
        elif kind == "fun":
            pattern = re.compile(r"(?<![\w])" + re.escape(name) + r"\s*(?:\(|\{)")
        else:
            pattern = re.compile(r"\." + re.escape(name) + r"\b(?!\s*\()")
        hits: list[str] = []
        for p, t in masked.items():
            if not _mentions(t, package, root, pkgs.get(p, "") == package):
                continue
            for m in pattern.finditer(t):
                hits.append(f"{p}:{_lineno(t, m.start())}")
                if len(hits) >= 6:
                    break
        orphan = False
        if tree:
            orphan = not any(
                _mentions(t, package, root, pkgs.get(p, "") == package)
                for p, t in tree.items()
                if not p.endswith(row["path"])
            )
        plumbing = (
            row["test_seam"]
            or (row["override"] and name in ("toString", "key", "equals", "hashCode"))
            or name == "Key"
        )
        status = "plumbing" if plumbing else "orphan" if orphan and not hits else "reached" if hits else "unreached"
        out.append({**row, "reached": bool(hits), "reached_at": hits, "status": status})
    return out


def scan_all(text: str, path: str = "") -> dict:
    """Every scanner at once, for a single file."""
    return {
        "path": path,
        "context_demands": context_demands(text, path),
        "supervision": supervision(text, path),
        "casts": casts(text, path),
        "contracts": contracts(text, path),
        "runners": runner_registry(text, path),
        "surface": ccek_surface(text, path),
    }
