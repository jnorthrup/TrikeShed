"""Retain Hermes' declared names when GraalPy omits ContextVar.name metadata."""

import ast
import hashlib
import importlib.abc
import importlib.machinery
import importlib.util
import sys

_MODULE = "gateway.session_context"
_provenance = None


def _adapt(source, filename):
    tree = ast.parse(source, filename)
    assignments = {target.id: node for node in tree.body if isinstance(node, ast.Assign)
                   for target in node.targets if isinstance(target, ast.Name)}
    try:
        constructor = assignments["_SESSION_VARS"].value.args[0]
        if not (isinstance(constructor, ast.GeneratorExp)
                and isinstance(constructor.elt, ast.Call)
                and isinstance(constructor.elt.func, ast.Name)
                and constructor.elt.func.id == "ContextVar"
                and len(constructor.generators) == 1
                and isinstance(constructor.generators[0].target, ast.Name)
                and isinstance(constructor.elt.args[0], ast.Name)
                and constructor.elt.args[0].id == constructor.generators[0].target.id
                and not constructor.generators[0].ifs and not constructor.generators[0].is_async):
            raise ValueError("session variable constructors changed")
        names = list(ast.literal_eval(constructor.generators[0].iter))
        mapping = assignments["_VAR_MAP"]
        original = mapping.value
        if not (isinstance(original, ast.DictComp)
                and isinstance(original.key, ast.Attribute) and original.key.attr == "name"
                and isinstance(original.key.value, ast.Name) and original.key.value.id == "var"
                and isinstance(original.value, ast.Name) and original.value.id == "var"
                and len(original.generators) == 1
                and isinstance(original.generators[0].target, ast.Name)
                and original.generators[0].target.id == "var"
                and not original.generators[0].ifs and not original.generators[0].is_async):
            raise ValueError("session name mapping changed")
        values = original.generators[0].iter
        if not (isinstance(values, ast.Tuple) and isinstance(values.elts[0], ast.Starred)
                and isinstance(values.elts[0].value, ast.Name)
                and values.elts[0].value.id == "_SESSION_VARS"):
            raise ValueError("session variable ordering changed")
        for variable in values.elts[1:]:
            call = assignments[variable.id].value
            if not (isinstance(call, ast.Call) and isinstance(call.func, ast.Name)
                    and call.func.id == "ContextVar"):
                raise ValueError("additional session variable constructor changed")
            names.append(ast.literal_eval(call.args[0]))
        if not all(isinstance(name, str) for name in names) or len(set(names)) != len(names):
            raise ValueError("session variable names must be distinct strings")
        mapping.value = ast.copy_location(ast.Call(ast.Name("dict", ast.Load()), [
            ast.Call(ast.Name("zip", ast.Load()), [
                ast.Tuple([ast.Constant(name) for name in names], ast.Load()), values,
            ], []),
        ], []), original)
    except (KeyError, IndexError, AttributeError, TypeError, ValueError) as error:
        raise ImportError("Unsupported Hermes session-name source: " + str(error)) from error
    return compile(ast.fix_missing_locations(tree), filename, "exec"), names


class _Loader(importlib.machinery.SourceFileLoader):
    def get_code(self, fullname):
        global _provenance
        raw = self.get_data(self.path)
        code, names = _adapt(importlib.util.decode_source(raw), self.path)
        _provenance = {"module": fullname, "source": self.path,
                       "sha256": hashlib.sha256(raw).hexdigest(),
                       "change": "_VAR_MAP uses declared constructor names", "names": names}
        return code


class _Finder(importlib.abc.MetaPathFinder):
    def find_spec(self, fullname, path=None, target=None):
        if fullname != _MODULE:
            return None
        spec = importlib.machinery.PathFinder.find_spec(fullname, path, target)
        if spec is None or not isinstance(spec.loader, importlib.machinery.SourceFileLoader):
            raise ImportError("Hermes session context requires its actual Python source")
        spec.loader = _Loader(fullname, spec.origin)
        return spec


def install():
    from contextvars import ContextVar
    if hasattr(ContextVar("trikeshed-name-metadata"), "name"):
        return False
    if _MODULE in sys.modules and _provenance is None:
        raise RuntimeError("Session context was imported before its name metadata bridge")
    if not any(isinstance(finder, _Finder) for finder in sys.meta_path):
        sys.meta_path.insert(0, _Finder())
    return True


def provenance():
    return None if _provenance is None else dict(_provenance)
