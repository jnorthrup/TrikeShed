package borg.trikeshed.graal.subvm

import borg.trikeshed.btrfs.BtrfsWorldStore
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.reify
import borg.trikeshed.parse.reifyMap
import borg.trikeshed.vm.Teleported

/** Application-independent Python effects, owned and closed beside one Graal VM. */
class PythonHostServices(val guest: GraalBtrfsSupervisor, world: BtrfsWorldStore) : AutoCloseable {
    val leases = PythonFileLeases(world.fileOpsFor(guest.id), "${world.root}/leases/${guest.id}", guest::put, { guest.isAlive })
    val sqlite = PythonSqlite(
        readImage = guest.vfs::fetch,
        writeImage = guest::put,
        callFunction = { function, arguments ->
            val result = guest.call("__trikeshed_sqlite_function", Teleported.Str(function),
                Teleported.Str(jsonOf(arguments.map(PythonSqlite::encode))))
            val encoded = (result as? Teleported.Str)?.v ?: error("SQLite callback must return JSON")
            PythonSqlite.decode(reify(encoded))
        },
    )

    fun install() {
        guest.delegate("vfs_lease", leases::call)
        guest.delegate("sqlite3") { arguments ->
            val request = (arguments.singleOrNull() as? Teleported.Str)?.v
                ?: throw IllegalArgumentException("sqlite3 requires a JSON request")
            Teleported.Str(jsonOf(sqlite.call(reifyMap(request))))
        }
        guest.delegate("host_facts") { Teleported.Str(jsonOf(PythonHostFacts.snapshot())) }
        guest.delegate("process_alive") { arguments ->
            val pid = (arguments.singleOrNull() as? Teleported.Num)?.v
                ?: throw IllegalArgumentException("process_alive requires a PID")
            Teleported.Bool(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        }
        guest.delegate("process_start") { arguments ->
            val pid = (arguments.singleOrNull() as? Teleported.Num)?.v
                ?: throw IllegalArgumentException("process_start requires a PID")
            ProcessHandle.of(pid).flatMap { it.info().startInstant() }.orElse(null)?.let {
                Teleported.Real(it.toEpochMilli() / 1000.0)
            } ?: Teleported.Null
        }
        guest.eval("""
            import sys as _sys
            from types import ModuleType as _ModuleType
            if 'trikeshed_host' in _sys.modules:
                raise RuntimeError('Python host services are already installed')
            _host_api = _ModuleType('trikeshed_host', 'Owned Graal Python host capabilities')
            _host_api.call = host.call
            _sys.modules['trikeshed_host'] = _host_api
            del _sys, _ModuleType, _host_api
            def __trikeshed_sqlite_function(function, arguments):
                from sqlite3 import __trikeshed_sqlite_callback
                return __trikeshed_sqlite_callback(function, arguments)
            True
        """.trimIndent(), "python-sqlite-callback.py")
    }

    override fun close() {
        try { sqlite.close() } finally { leases.close() }
    }
}
