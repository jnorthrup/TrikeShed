"""Source evidence for palette construction and CoroutineContext keys.

This is a conservative Kotlin lexical index, not a compiler or a call graph.
Only explicit symbols and supported expressions resolve. Source-wide sites are
inventory, never proof that a palette runner reaches a service or inherits it.
"""

from __future__ import annotations

import ast
import re
from collections import Counter, defaultdict
from dataclasses import dataclass

KEY = "kotlin.coroutines.CoroutineContext.Key"
ELEMENT = "kotlin.coroutines.CoroutineContext.Element"
ABSTRACT_ELEMENT = "kotlin.coroutines.AbstractCoroutineContextElement"
_ID = re.compile(r"[A-Za-z_]\w*\Z")
_TOKEN = re.compile(
    r'\s+|//[^\n]*|/\*|"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|'
    r"'(?:\\.|[^'\\])*'|`[^`]*`|[A-Za-z_]\w*|\d+|::|\?:|!!|\?\.|->|.",
    re.DOTALL,
)


@dataclass(frozen=True)
class Token:
    value: str
    start: int
    end: int


def _tokens(text):
    pos = 0
    while pos < len(text):
        m = _TOKEN.match(text, pos)
        value = m.group()
        end = m.end()
        if value == "/*":
            depth = 1
            while depth and end < len(text):
                if text.startswith("/*", end):
                    depth += 1
                    end += 2
                elif text.startswith("*/", end):
                    depth -= 1
                    end += 2
                else:
                    end += 1
        elif not value.isspace() and not value.startswith("//"):
            yield Token(value, pos, end)
        pos = end


class Source:
    def __init__(self, path, text):
        self.path, self.text = path, text
        self.tokens = list(_tokens(text))
        self.v = [t.value for t in self.tokens]
        self.pairs = {}
        stack = []
        for i, v in enumerate(self.v):
            if v in ("(", "[", "{"):
                stack.append(i)
            elif v in (")", "]", "}") and stack:
                left = stack.pop()
                if (self.v[left], v) in (("(", ")"), ("[", "]"), ("{", "}")):
                    self.pairs[left] = i
        self.package = ""
        self.imports = {}
        self.stars = []
        self.decls = []
        for i, v in enumerate(self.v):
            if v not in ("package", "import"):
                continue
            ref, end = self.ref(i + 1)
            if v == "package":
                self.package = ref
            elif self.v[end:end + 2] == [".", "*"]:
                self.stars.append(ref)
            else:
                alias = self.v[end + 1] if self.v[end:end + 1] == ["as"] else ref.rsplit(".", 1)[-1]
                self.imports[alias] = ref
        self._declarations()

    def ref(self, i):
        start = i
        if i >= len(self.v) or not _ID.fullmatch(self.v[i]):
            return "", i
        i += 1
        while i + 1 < len(self.v) and self.v[i] == "." and _ID.fullmatch(self.v[i + 1]):
            i += 2
        return "".join(self.v[start:i]), i

    def raw(self, start, end):
        if start >= end:
            return ""
        return self.text[self.tokens[start].start:self.tokens[end - 1].end]

    def site(self, i):
        pos = self.tokens[i].start
        line = self.text.count("\n", 0, pos) + 1
        start = self.text.rfind("\n", 0, pos) + 1
        end = self.text.find("\n", pos)
        return {"path": self.path, "line": line,
                "snippet": self.text[start:end if end >= 0 else len(self.text)].strip()}

    def split(self, start, end, delimiter=","):
        out, part, i = [], start, start
        while i < end:
            if self.v[i] == delimiter:
                if part < i:
                    out.append((part, i))
                part = i + 1
            i = self.pairs.get(i, i) + 1
        if part < end:
            out.append((part, end))
        return out

    def args(self, opening):
        out = {}
        for number, (a, b) in enumerate(self.split(opening + 1, self.pairs.get(opening, opening))):
            if self.v[a + 1:a + 2] == ["="]:
                out[self.v[a]] = (a + 2, b)
            else:
                out[number] = (a, b)
        return out

    def owner(self, i):
        owners = [d for d in self.decls if d["body"] is not None and d["body"] < i < d["end"]]
        return owners[-1] if owners else None

    def _declarations(self):
        for i, v in enumerate(self.v):
            if v not in ("class", "object", "interface") or self.v[max(0, i - 1):i] == ["::"]:
                continue
            companion = v == "object" and i > 0 and self.v[i - 1] == "companion"
            j = i + 1
            name = self.v[j] if j < len(self.v) and _ID.fullmatch(self.v[j]) else None
            if name is None and not companion:
                continue
            name = name or "Companion"
            j += int(self.v[j:j + 1] == [name])
            start = j
            angle = 0
            while j < len(self.v):
                token = self.v[j]
                if token == "<":
                    angle += 1
                elif token == ">" and angle:
                    angle -= 1
                if not angle:
                    if token in ("internal", "private", "public", "protected") and self.v[j + 1:j + 2] == ["constructor"]:
                        j += 2
                        continue
                    if token in ("{", "}", ";", "=", "class", "object", "interface", "fun", "val", "var", "companion", "override", "private", "public", "internal", "enum", "data"):
                        break
                    if j > start and "\n" in self.text[self.tokens[j - 1].end:self.tokens[j].start]:
                        if token not in (":", ",", "where", "(") and self.v[j - 1] not in (":", ","):
                            break
                j = self.pairs.get(j, j) + 1
            body = j if self.v[j:j + 1] == ["{"] and j in self.pairs else None
            owner = self.owner(i)
            qualified = (owner["qualified"] if owner else self.package) + "." + name
            self.decls.append({"name": name, "qualified": qualified.lstrip("."),
                               "kind": "enum" if i > 0 and self.v[i - 1] == "enum" else v,
                               "companion": companion, "start": i, "header": start,
                               "header_end": j, "body": body,
                               "end": self.pairs[body] if body is not None else j,
                               "owner": owner["qualified"] if owner else None})


class Index:
    def __init__(self, texts):
        self.sources = [Source(p, t) for p, t in sorted(texts.items())]
        self.symbols = defaultdict(list)
        self.constants = defaultdict(list)
        for source in self.sources:
            for d in source.decls:
                self.symbols[d["qualified"]].append((source, d))
            for i, token in enumerate(source.v):
                if token != "val" or i + 3 >= len(source.v):
                    continue
                name = source.v[i + 1]
                if not _ID.fullmatch(name) or name.upper() != name:
                    continue
                j = i + 2
                while j < len(source.v) and source.v[j] not in ("=", "{", "}", ";"):
                    if "\n" in source.text[source.tokens[j - 1].end:source.tokens[j].start]:
                        break
                    j += 1
                if source.v[j:j + 1] != ["="]:
                    continue
                end = j + 1
                while end < len(source.v) and source.v[end] not in (",", ";", "}"):
                    if end > j + 1 and "\n" in source.text[source.tokens[end - 1].end:source.tokens[end].start] and source.v[end - 1] != "+":
                        break
                    end += 1
                owner = source.owner(i)
                prefix = owner["qualified"] if owner else source.package
                self.constants[prefix + "." + name].append((source, j + 1, end))

    def resolve(self, source, ref, pos, symbols=None):
        symbols = self.symbols if symbols is None else symbols
        if ref in symbols or ref in (KEY, ELEMENT, ABSTRACT_ELEMENT):
            return ref
        owner = source.owner(pos)
        prefix = owner["qualified"] if owner else source.package
        while prefix and prefix != source.package:
            candidate = prefix + "." + ref
            if candidate in symbols:
                return candidate
            prefix = prefix.rsplit(".", 1)[0] if "." in prefix else ""
        head, _, tail = ref.partition(".")
        if head in source.imports:
            return source.imports[head] + ("." + tail if tail else "")
        local = source.package + "." + ref
        if local in symbols:
            return local
        candidates = [p + "." + ref for p in source.stars if p + "." + ref in symbols or p + "." + ref in (KEY, ELEMENT, ABSTRACT_ELEMENT)]
        return candidates[0] if len(candidates) == 1 else None

    def string(self, source, start, end, seen=frozenset()):
        if start >= end:
            return None
        values = []
        for a, b in source.split(start, end, "+"):
            token = source.v[a]
            if b == a + 1 and token.startswith('"') and "$" not in token:
                try:
                    values.append(ast.literal_eval(token))
                except (SyntaxError, ValueError):
                    return None
            else:
                ref, stop = source.ref(a)
                if stop != b:
                    return None
                resolved = self.resolve(source, ref, a, self.constants)
                declarations = self.constants.get(resolved, [])
                if len(declarations) != 1 or resolved in seen:
                    return None
                target, left, right = declarations[0]
                value = self.string(target, left, right, seen | {resolved})
                if value is None:
                    return None
                values.append(value)
        return "".join(values)

    def bases(self, source, declaration):
        i, end = declaration["header"], declaration["header_end"]
        angle = 0
        while i < end:
            if source.v[i] == "<":
                angle += 1
            elif source.v[i] == ">":
                angle -= 1
            elif source.v[i] == ":" and angle == 0:
                i += 1
                break
            i = source.pairs.get(i, i) + 1
        while i < end:
            ref, stop = source.ref(i)
            if not ref:
                break
            target = self.resolve(source, ref, i)
            argument = None
            if source.v[stop:stop + 1] == ["<"]:
                argument, _ = source.ref(stop + 1)
            yield target, argument, i
            i = stop
            angle = 0
            while i < end:
                if source.v[i] == "<":
                    angle += 1
                elif source.v[i] == ">":
                    angle -= 1
                elif source.v[i] == "," and angle == 0:
                    i += 1
                    break
                i = source.pairs.get(i, i) + 1

    def inherits(self, name, target, seen=frozenset()):
        if name == target:
            return True
        if not name or name in seen:
            return False
        return any(self.inherits(base, target, seen | {name})
                   for s, d in self.symbols.get(name, []) for base, _, _ in self.bases(s, d))

    def method(self, owner, name):
        declarations = self.symbols.get(owner, [])
        if len(declarations) != 1:
            return None
        source, declaration = declarations[0]
        for i in range(declaration["body"] or declaration["end"], declaration["end"]):
            if source.v[i:i + 3] != ["fun", name, "("] or source.owner(i) != declaration:
                continue
            j = source.pairs.get(i + 2, i + 2) + 1
            while j < declaration["end"] and source.v[j] not in ("=", "{", "fun", "val", "}"):
                j += 1
            if source.v[j:j + 1] == ["{"] and j in source.pairs:
                return source, i, j + 1, source.pairs[j]
            if source.v[j:j + 1] == ["="]:
                end = j + 1
                while end < declaration["end"]:
                    if end > j + 1 and "\n" in source.text[source.tokens[end - 1].end:source.tokens[end].start]:
                        break
                    end = source.pairs.get(end, end) + 1
                return source, i, j + 1, end
        return None


def _match(source, start, end, pattern):
    """Match code tokens only, retaining the source position of the first token."""
    values = [v if not v.startswith(('"', "'")) else "<literal>" for v in source.v[start:end]]
    code = " ".join(values)
    match = re.search(pattern, code)
    if match is None:
        return None
    offset = 0
    for i, value in enumerate(values):
        if offset >= match.start():
            return start + i
        offset += len(value) + 1
    return None


def palette(index):
    entries, issues, authorities = [], [], []
    for source in index.sources:
        for declaration in source.decls:
            if declaration["qualified"] != "borg.trikeshed.lcnc.LcncContracts":
                continue
            for i in range(declaration["body"] or 0, declaration["end"]):
                if source.v[i:i + 4] != ["fun", "all", "(", ")"] or source.owner(i) != declaration:
                    continue
                authorities.append(source.site(i))
                j = i + 4
                while j < declaration["end"] and source.v[j] not in ("=", "{"):
                    j += 1
                if source.v[j:j + 3] != ["=", "listOf", "("]:
                    issues.append({**source.site(i), "reason": "unsupported-all-expression"})
                    continue
                opening = j + 2
                end = source.pairs.get(opening)
                if end is None:
                    issues.append({**source.site(i), "reason": "unterminated-all-list"})
                    continue
                for a, b in source.split(opening + 1, end):
                    ref, stop = source.ref(a)
                    if ref not in ("LcncPortContract", "borg.trikeshed.lcnc.LcncPortContract") or source.v[stop:stop + 1] != ["("] or source.pairs.get(stop) != b - 1:
                        issues.append({**source.site(a), "reason": "unsupported-palette-entry", "expression": source.raw(a, b)})
                        continue
                    args = source.args(stop)
                    span = args.get("type", args.get(0))
                    value = index.string(source, *span) if span else None
                    metadata = {key: source.raw(*value) for key, value in args.items()
                                if isinstance(key, str) and re.search(r"construction|binding|context|key|exception", key, re.I)}
                    entries.append({**source.site(a), "type": value,
                                    "type_expression": source.raw(*span) if span else None,
                                    "type_resolved": value is not None, "metadata": metadata})
                # A mapping/copy after the literal list is evidence, not per-type coverage.
                if source.v[end + 1:end + 2] == ["."]:
                    issues.append({**source.site(end + 1), "reason": "postprocessed-palette-metadata",
                                   "expression": source.raw(end + 1, min(end + 14, len(source.v)))})
    if len(authorities) != 1:
        issues.append({"reason": "missing-palette-authority" if not authorities else "multiple-palette-authorities"})
    counts = Counter(e["type"] for e in entries if e["type_resolved"])
    return {"authority": "borg.trikeshed.lcnc.LcncContracts.all", "sources": authorities,
            "entries": entries, "entry_count": len(entries), "unique_type_count": len(counts),
            "unresolved_types": [e for e in entries if not e["type_resolved"]],
            "duplicate_types": sorted(t for t, count in counts.items() if count > 1), "issues": issues}


def key_declarations(index):
    keys = []
    for source in index.sources:
        for declaration in source.decls:
            if not index.inherits(declaration["qualified"], KEY):
                continue
            base = next(((base, arg, pos) for base, arg, pos in index.bases(source, declaration)
                         if index.inherits(base, KEY)), None)
            element = index.resolve(source, base[1], base[2]) if base and base[1] else None
            row = {**source.site(declaration["start"]), "qualified": declaration["qualified"],
                   "element": element, "element_expression": base[1] if base else None,
                   "key_base": base[0] if base else None, "kind": declaration["kind"],
                   "singleton": declaration["kind"] == "object", "palette_type": None,
                   "aliases": [declaration["owner"]] if declaration["companion"] else []}
            keys.append(row)
            if declaration["kind"] != "enum" or declaration["body"] is None:
                continue
            opening = declaration["body"]
            end = declaration["end"]
            for a, b in source.split(opening + 1, end, ";")[:1]:
                for left, right in source.split(a, b):
                    if not _ID.fullmatch(source.v[left]) or source.v[left + 1:left + 2] != ["("]:
                        continue
                    args = source.args(left + 1)
                    span = args.get("type", args.get(0))
                    keys.append({**row, **source.site(left),
                                 "qualified": declaration["qualified"] + "." + source.v[left],
                                 "kind": "enum-entry", "singleton": True, "aliases": [],
                                 "palette_type": index.string(source, *span) if span else None,
                                 "type_expression": source.raw(*span) if span else None})
    return keys


def source_sites(index, keys):
    """Inventory direct constructions, context installation calls, and key reads."""
    key_symbols = {k["qualified"]: k for k in keys if k["singleton"]}
    aliases = {a: k["qualified"] for k in keys for a in k["aliases"]}
    key_symbols.update({a: key_symbols[q] for a, q in aliases.items() if q in key_symbols})
    elements = {k["element"] for k in keys if k["element"] in index.symbols}
    constructions, installations, demands, bindings = [], [], [], []
    for source in index.sources:
        declarations = {d["start"] + 1 for d in source.decls}
        for i, token in enumerate(source.v):
            if not _ID.fullmatch(token) or (i and source.v[i - 1] in (".", "?.", "import", "package", "fun", "class", "interface", "object")):
                continue
            ref, end = source.ref(i)
            resolved = index.resolve(source, ref, i)
            if i not in declarations and source.v[end:end + 1] == ["("] and end in source.pairs:
                close = source.pairs[end]
                if resolved in elements:
                    constructions.append({**source.site(i), "element": resolved, "expression": source.raw(i, close + 1), "evidence": "constructor-call"})
                if ref.rsplit(".", 1)[-1] in ("withContext", "CoroutineScope", "launch", "async"):
                    installations.append({**source.site(i), "call": ref, "arguments": source.raw(end + 1, close),
                                          "evidence": "context-call-candidate", "provision_proven": False})
                if ref.endswith(".construct"):
                    constructions.append({**source.site(i), "key_expression": ref[:-10], "expression": source.raw(i, close + 1),
                                          "evidence": "construct-call-candidate", "dispatch_proven": False})
            if source.v[end:end + 2] == ["(", ")"]:
                end += 2
            if source.v[end:end + 1] != ["["] or end not in source.pairs:
                continue
            close = source.pairs[end]
            key_ref, stop = source.ref(end + 1)
            key = index.resolve(source, key_ref, end + 1, key_symbols)
            context_syntax = ref.endswith("coroutineContext") or ref.endswith("currentCoroutineContext")
            if stop != close or (key not in key_symbols and not context_syntax):
                continue
            tail = source.v[close + 1:close + 4]
            severity = "throws" if tail[:1] == ["!!"] or (tail[:1] == ["?:"] and tail[1:2] in (["error"], ["throw"])) else "optional"
            if severity != "throws" and tail[:1] == ["?:"]:
                severity = "fallback"
            demands.append({**source.site(i), "receiver": ref, "key_expression": source.raw(end + 1, close),
                            "key": aliases.get(key, key) if key in key_symbols else None,
                            "severity": severity, "evidence": "context-read" if context_syntax else "key-index-candidate",
                            "palette_reachability": "unresolved"})
        for d in source.decls:
            if d["qualified"] not in elements:
                continue
            for base, _, pos in index.bases(source, d):
                if base == ABSTRACT_ELEMENT:
                    _, stop = source.ref(pos)
                    if source.v[stop:stop + 1] == ["("] and stop in source.pairs:
                        a, b = stop + 1, source.pairs[stop]
                        ref, end = source.ref(a)
                        local = d["qualified"] + "." + ref
                        key = (local if local in key_symbols else index.resolve(source, ref, a, key_symbols)) if end == b else None
                        bindings.append({**source.site(pos), "element": d["qualified"], "key_expression": source.raw(a, b),
                                         "key": aliases.get(key, key) if key in key_symbols else None, "evidence": "abstract-element-key"})
            for i in range(d["body"] or d["end"], d["end"]):
                if source.v[i:i + 2] != ["val", "key"] or source.owner(i) != d:
                    continue
                end = i + 2
                while end < d["end"] and source.v[end] not in ("=", "{", "}", ";", "val", "fun", "companion"):
                    end += 1
                if source.v[end:end + 1] == ["="]:
                    ref, stop = source.ref(end + 1)
                    key = index.resolve(source, ref, end + 1, key_symbols)
                    bindings.append({**source.site(i), "element": d["qualified"], "key_expression": source.raw(end + 1, stop),
                                     "key": aliases.get(key, key) if key in key_symbols else None, "evidence": "element-key-property"})
    return {"construction_sites": constructions, "installation_sites": installations,
            "demand_sites": demands, "element_bindings": bindings}


_LCNC = "borg.trikeshed.lcnc."


def context_metadata(index, keys):
    """Read explicit LcncContextContract.of branches; never apply an else exemption."""
    method = index.method(_LCNC + "LcncContextContract.Companion", "of")
    out = {"status": "missing", "branches": [], "composite": None,
           "invocation_lookup": None, "contract_property": None, "unresolved": []}
    if method is None:
        return out
    source, declaration, start, end = method
    out.update(status="present", declaration=source.site(declaration))
    key_symbols = {k["qualified"]: k for k in keys if k["singleton"]}

    def contract(i):
        if source.v[i:i + 2] != ["LcncContextContract", "("]:
            return None
        args = source.args(i + 1)
        def span(name, position):
            return args.get(name, args.get(position))
        role_span = span("role", 0)
        key_span = span("key", 1)
        exception_span = span("exception", 4)
        role_ref = source.ref(role_span[0])[0] if role_span else ""
        if not role_ref.startswith("LcncContextRole."):
            return None
        key_ref = source.ref(key_span[0])[0] if key_span else ""
        key = index.resolve(source, key_ref, i, key_symbols)
        return {**source.site(i), "role": role_ref.rsplit(".", 1)[-1],
                "key_expression": source.raw(*key_span) if key_span else None,
                "key": key if key in key_symbols else None,
                "exception": index.string(source, *exception_span) if exception_span else None}

    for i in range(start, end):
        if source.v[i:i + 5] == ["if", "(", "composite", ")", "return"]:
            out["composite"] = contract(i + 5)
        if source.v[i:i + 5] != ["when", "(", "type", ")", "{"]:
            continue
        opening = i + 4
        close = source.pairs.get(opening, opening)
        left = opening + 1
        while left < close:
            arrow = left
            while arrow < close and source.v[arrow] != "->":
                arrow = source.pairs.get(arrow, arrow) + 1
            if arrow == close:
                break
            if source.v[left] == "else":
                pattern = (r"else -> LcncNodeKey \. of \( type \) \?\. let \{ key -> "
                           r"LcncContextContract \( LcncContextRole \. INVOCATION , key ,")
                match = _match(source, left, close, pattern)
                if match is not None and _match(source, left, close, r"LcncContextRole \. UNDECLARED , null") is not None:
                    out["invocation_lookup"] = source.site(match)
                else:
                    out["unresolved"].append({**source.site(left), "reason": "unsupported-fallback-metadata"})
                break
            row = contract(arrow + 1)
            if row is None:
                out["unresolved"].append({**source.site(left), "reason": "unsupported-context-branch"})
                break
            for a, b in source.split(left, arrow):
                value = index.string(source, a, b)
                out["branches"].append({**row, "type": value, "type_expression": source.raw(a, b)})
            left = source.pairs.get(arrow + 2, close) + 1
    for contract_source, d in index.symbols.get(_LCNC + "LcncPortContract", []):
        match = _match(contract_source, d["body"] or d["end"], d["end"],
                       r"val context : LcncContextContract get \( \) = LcncContextContract \. of \( type \)")
        if match is not None:
            out["contract_property"] = contract_source.site(match)
    return out


def executor_evidence(index):
    """Recognize the declared SAM -> factory -> installed invocation source chain.

    Checks are confined to the owning methods and the withContext lambda. This
    records lexical evidence; it does not establish execution of any call site.
    """
    checks = []

    def check(name, owner, method_name, pattern):
        method = index.method(_LCNC + owner, method_name)
        match = _match(method[0], method[2], method[3], pattern) if method else None
        row = {"check": name, "status": "observed" if match is not None else "unresolved"}
        if match is not None:
            row.update(method[0].site(match))
        checks.append(row)
        return method, match

    check("exact-type-lookup", "LcncNodeKey.Companion", "of", r"byType \[ type \]")
    declarations = index.symbols.get(_LCNC + "LcncNodeKey.Companion", [])
    found = None
    if len(declarations) == 1:
        source, d = declarations[0]
        found = _match(source, d["body"], d["end"], r"val byType = entries \. associateBy \{ it \. type \}")
    checks.append({"check": "enum-type-index", "status": "observed" if found is not None else "unresolved",
                   **(source.site(found) if found is not None else {})})
    check("factory-owns-key", "LcncNodeKey", "construct",
          r"return LcncNodeElement \( this , node , inputs , runner , parent \)")
    declarations = index.symbols.get(_LCNC + "LcncNodeElement", [])
    found = None
    if len(declarations) == 1:
        source, d = declarations[0]
        if index.inherits(d["qualified"], ELEMENT):
            found = _match(source, d["header"], d["header_end"], r"override val key : LcncNodeKey")
    checks.append({"check": "element-owns-key", "status": "observed" if found is not None else "unresolved",
                   **(source.site(found) if found is not None else {})})
    check("sam-selects-key", "LcncNodeRunner", "run", r"val key = LcncNodeKey \. of \( node \. type \)")
    check("sam-constructs-and-executes", "LcncNodeRunner", "run",
          r"else key \. construct \( node , inputs , this , currentCoroutineContext \( \) \. job \) \. execute \( \)")
    method, match = check("element-installs-self", "LcncNodeElement", "execute",
                          r"withContext \( supervisor \+ this \) \{")
    behavior = None
    if match is not None:
        source, _, start, end = method
        if index.resolve(source, "withContext", match) != "kotlinx.coroutines.withContext":
            checks[-1]["status"] = "unresolved"
        else:
            opening = source.pairs[match + 1] + 1
            close = source.pairs.get(opening, opening)
            behavior = _match(source, opening + 1, close, r"runner \. execute \( node , inputs \)")
    checks.append({"check": "behavior-inside-installed-context", "status": "observed" if behavior is not None else "unresolved",
                   **(source.site(behavior) if behavior is not None else {})})
    return {"status": "observed-source-chain" if all(c["status"] == "observed" for c in checks) else "unresolved",
            "runtime_verified": False, "checks": checks}


def runner_sites(index, demands):
    """Associate only reads lexically inside a literal runner body with its type."""
    out = []
    for source in index.sources:
        for i in range(1, len(source.v) - 2):
            if source.v[i:i + 3] != ["to", "LcncNodeRunner", "{"] or i + 2 not in source.pairs:
                continue
            a = i - 1
            while a >= 2 and source.v[a - 1] in (".", "+"):
                a -= 2
            value = index.string(source, a, i)
            close = source.pairs[i + 2]
            first, last = source.site(i + 2)["line"], source.site(close)["line"]
            out.append({**source.site(a), "type": value, "type_expression": source.raw(a, i),
                        "direct_demands": [d for d in demands if d["path"] == source.path and first <= d["line"] <= last],
                        "transitive_requirements": "unresolved"})
    return out


def audit(texts):
    index = Index(texts)
    vocabulary = palette(index)
    keys = key_declarations(index)
    sites = source_sites(index, keys)
    metadata = context_metadata(index, keys)
    executor = executor_evidence(index)
    runners = runner_sites(index, sites["demand_sites"])
    by_type = defaultdict(list)
    for key in keys:
        if key["singleton"] and key["palette_type"] and key["element"] == _LCNC + "LcncNodeElement" and key["qualified"].startswith(_LCNC + "LcncNodeKey."):
            by_type[key["palette_type"]].append(key)
    rows = []
    for entry in vocabulary["entries"]:
        declared = by_type[entry["type"]]
        status = "declared" if len(declared) == 1 else "ambiguous" if declared else "missing"
        branches = [b for b in metadata["branches"] if b["type"] == entry["type"]]
        branch = branches[0] if len(branches) == 1 else None
        expected_role = {"scope": "SCOPE", "scope.in": "BINDING", "scope.out": "BINDING",
                         "note": "PRESENTATION", "program.ref": "PRESENTATION"}.get(entry["type"])
        structural = branch if expected_role and branch and branch["role"] == expected_role else None
        if structural:
            valid_key = structural["key"] == _LCNC + "LcncScopeFrame.Key" if expected_role != "PRESENTATION" else structural["key_expression"] == "null"
            status = "structural" if valid_key and not declared else "ambiguous"
        if not entry["type_resolved"]:
            status = "unresolved-type"
        dispatch = executor["status"] if status == "declared" else "not-applicable" if status == "structural" else "unresolved"
        metadata_status = "declared" if metadata["contract_property"] and (structural or (declared and metadata["invocation_lookup"])) else "unresolved"
        gap = status not in ("declared", "structural") or dispatch == "unresolved" or metadata_status == "unresolved"
        rows.append({**entry, "invocation_mapping": status,
                     "invocation_keys": [k["qualified"] for k in declared],
                     "executor_construction": dispatch, "context_metadata": metadata_status,
                     "structural_exception": structural if expected_role != "SCOPE" else None,
                     "scope_construction": structural if expected_role == "SCOPE" else None,
                     "runner_sites": [r for r in runners if r["type"] == entry["type"]],
                     "service_requirements": "unresolved-transitive-reachability",
                     "gap": gap})
    palette_types = {r["type"] for r in rows if r["type"] is not None}
    extra_keys = [k for t, entries in by_type.items() if t not in palette_types for k in entries]
    unresolved_keys = [k for k in keys if k["kind"] == "enum-entry" and k["qualified"].startswith(_LCNC + "LcncNodeKey.") and not k["palette_type"]]
    return {"schema_version": 1, "basis": "lexical-source-evidence",
            "scope": {"source_files": len(texts), "packages": "all", "runtime_verified": False},
            "limitations": ["No Kotlin type checking, call graph, runtime execution, or transitive service-demand proof.",
                            "Construction and context-call inventories do not prove installation or inheritance on a palette path.",
                            "Only exact palette types and resolved singleton identities can establish declared correspondence."],
            "palette": vocabulary, "keys": keys, **sites, "context_metadata": metadata,
            "executor": executor, "runner_sites": runners, "rows": rows,
            "extra_invocation_keys": extra_keys, "unresolved_invocation_keys": unresolved_keys,
            "gaps": [r for r in rows if r["gap"]],
            "summary": {"palette_entries": len(rows), "mapping_status": dict(Counter(r["invocation_mapping"] for r in rows)),
                        "structural_exceptions": sum(r["structural_exception"] is not None for r in rows),
                        "unresolved_service_requirements": sum(r["invocation_mapping"] != "structural" for r in rows),
                        "unresolved_executor_paths": sum(r["executor_construction"] == "unresolved" for r in rows),
                        "gaps": sum(r["gap"] for r in rows) + len(vocabulary["issues"]) + len(vocabulary["duplicate_types"]) + len(extra_keys) + len(unresolved_keys)}}
