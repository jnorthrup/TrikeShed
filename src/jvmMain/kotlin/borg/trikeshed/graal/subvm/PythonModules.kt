package borg.trikeshed.graal.subvm

import borg.trikeshed.parse.jsonOf

/** Explicit source ports take precedence over GraalPy's frozen stdlib modules. */
object PythonModules {
    fun install(guest: GraalBtrfsSupervisor, paths: Map<String, String>) {
        require(paths.keys.all { it.matches(Regex("[A-Za-z_][A-Za-z0-9_.]*")) })
        require(paths.values.all { guest.vfs.fetch(it) != null }) { "Python module source is not mounted" }
        guest.eval("""
            import importlib.abc, importlib.util, sys
            class _PythonSleeveImporter(importlib.abc.MetaPathFinder):
                def __init__(self, paths):
                    self.paths = dict(paths)
                def find_spec(self, fullname, path=None, target=None):
                    source = self.paths.get(fullname)
                    if source is None:
                        return None
                    return importlib.util.spec_from_file_location(fullname, source)
            _python_sleeve_paths = ${jsonOf(paths)}
            if any(name in sys.modules for name in _python_sleeve_paths):
                raise RuntimeError('Python source port was loaded before installation')
            sys.meta_path.insert(0, _PythonSleeveImporter(_python_sleeve_paths))
            del _python_sleeve_paths
            True
        """.trimIndent(), "python-sleeve-importer.py")
    }
}
