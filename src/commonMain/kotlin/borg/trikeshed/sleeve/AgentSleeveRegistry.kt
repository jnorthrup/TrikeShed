package borg.trikeshed.sleeve

import borg.trikeshed.pointcut.VmFacet

/**
 * The agent sleeves this repo maintains.
 *
 * Every agent we want to host runs as a guest under the same bounds the Hermes sleeve already documents:
 * `allowCreateThread(false)`, no host IO, no process creation, no native access, and a supervisor-owned
 * `UserspaceBtrfs` as the only filesystem. Those four bounds are what generate traps, so a new sleeve
 * starts from the same four and earns its way to GREEN by porting or withholding each one.
 */
object AgentSleeveRegistry {

    /**
     * Hermes — the sleeve with measured history. Traps and evidence here are transcribed from
     * `doc/triage/graalpy-sleeve-2026-09-07.md`, which recorded 1075 → 1255 clean imports and
     * 1 → 0 isolate kills after the prelude landed.
     */
    val HERMES = AgentSleeveManifest(
        id = "hermes",
        facet = VmFacet.GRAAL_PYTHON,
        entryModule = "hermes_cli.main",
        sleeveRoot = "graalpy-sleeve/hermes",
        donorRoot = "~/.hermes/hermes-agent",
        traps = listOf(
            PolyglotTrap(
                id = "hermes/thread-spawn",
                donorModule = "_thread",
                symbol = "start_new_thread / start_new / start_joinable_thread",
                shape = TrapShape.WITHHELD,
                catchability = TrapCatchability.HOST_UNCATCHABLE,
                forkPath = "trikeshed_guest_prelude.py",
                redTest = "HermesGuestPreludeTest.threadSpawnRaisesGuestRuntimeError",
                evidence = "ThreadModuleBuiltins.startThread raised host IllegalStateException; " +
                    "InProcessIsolate.classify mapped it to GuestFailure.DEAD and closed the context",
            ),
            PolyglotTrap(
                id = "hermes/queue-listener",
                donorModule = "logging.handlers",
                symbol = "QueueListener + QueueHandler.emit",
                shape = TrapShape.PORTED,
                catchability = TrapCatchability.GUEST_CATCHABLE,
                forkPath = "trikeshed_guest_prelude.py",
                redTest = "HermesGuestPreludeTest.queueListenerDrainsOnTheOnlyThread",
                evidence = "withholding alone let boot succeed while records piled up in a SimpleQueue " +
                    "nobody drained — measured depth grew, file handler never fired",
            ),
            PolyglotTrap(
                id = "hermes/socket-default-timeout",
                donorModule = "socket",
                symbol = "_GLOBAL_DEFAULT_TIMEOUT",
                shape = TrapShape.PORTED,
                catchability = TrapCatchability.GUEST_CATCHABLE,
                forkPath = "socket.py",
                redTest = "HermesGuestPreludeTest.urllibImportsWithDefaultTimeoutSentinel",
                evidence = "CPython sentinel bound in http.client.HTTPConnection.__init__; its absence " +
                    "raised at import and Hermes' plugin loader swallowed it per-plugin",
            ),
            PolyglotTrap(
                id = "hermes/socket-wire-verbs",
                donorModule = "socket",
                symbol = "socketpair / create_server / inet_* / getnameinfo",
                shape = TrapShape.WITHHELD,
                catchability = TrapCatchability.GUEST_CATCHABLE,
                forkPath = "socket.py",
                redTest = "HermesGuestPreludeTest.wireVerbsFailClosed",
                evidence = "every verb that reaches the wire names the userspace.nio seam",
            ),
            PolyglotTrap(
                id = "hermes/contextvar-name",
                donorModule = "contextvars",
                symbol = "ContextVar.name",
                shape = TrapShape.PORTED,
                catchability = TrapCatchability.GUEST_CATCHABLE,
                forkPath = "contextvars.py",
                redTest = "HermesGuestPreludeTest.contextVarCarriesItsName",
                evidence = "GraalPy 25.3 _contextvars.ContextVar is immutable, unsubclassable and has no " +
                    "name; gateway/session_context.py:62 builds {var.name: var} — took down hermes_cli.oneshot and 13 tools",
            ),
            // Still open in the triage doc, ranked #1 and #2 there. These are what keep hermes RED.
            PolyglotTrap(
                id = "hermes/http-client",
                donorModule = "requests + httpx",
                symbol = "HTTP client waist",
                shape = TrapShape.UNRESOLVED,
                catchability = TrapCatchability.GUEST_CATCHABLE,
                forkPath = "requests/__init__.py",
                redTest = "HermesHttpWaistTest.hermesTurnReachesAModelProvider",
                evidence = "138 module misses (httpx 73, requests 32); not staged into the guest VFS and " +
                    "both reach banned socket regardless. Wants a ruling: which host seam carries credentials",
            ),
            PolyglotTrap(
                id = "hermes/sqlite3",
                donorModule = "sqlite3",
                symbol = "DB-API 2.0 surface",
                shape = TrapShape.UNRESOLVED,
                catchability = TrapCatchability.GUEST_CATCHABLE,
                forkPath = "sqlite3/__init__.py",
                redTest = "HermesSqliteWaistTest.kanbanCommandsPersistThroughTheBlackboard",
                evidence = "23 modules (hermes_cli.kanban*, cron.*, projects_cmd, tui_gateway.ws); the " +
                    "banlist already names the replacement — persistence through the TrikeShed blackboard/CAS",
            ),
        ),
    )

    /**
     * Pi — the Pi coding agent as a guest.
     *
     * Donor root is unpinned, which is deliberate rather than an omission: nothing can be trapped until
     * the checkout is named, so the sleeve is RED on that alone. The traps listed are the four sandbox
     * bounds every guest meets, carried here so the first boot has somewhere to record what it hits.
     */
    val PI = AgentSleeveManifest(
        id = "pi",
        facet = VmFacet.GRAAL_PYTHON,
        entryModule = "pi.main",
        sleeveRoot = "graalpy-sleeve/pi",
        donorRoot = null,
        traps = boundTraps("pi"),
    )

    /**
     * ohmypi — the polyglot shell layer. Same unpinned posture as [PI]; its facet differs because the
     * shell surface is JS-hosted, and GRAAL_JS has no sleeve at all yet.
     */
    val OHMYPI = AgentSleeveManifest(
        id = "ohmypi",
        facet = VmFacet.GRAAL_JS,
        entryModule = "ohmypi.main",
        sleeveRoot = "graaljs-sleeve/ohmypi",
        donorRoot = null,
        traps = boundTraps("ohmypi"),
    )

    val ALL: List<AgentSleeveManifest> = listOf(HERMES, PI, OHMYPI)

    fun byId(id: String): AgentSleeveManifest? = ALL.firstOrNull { it.id == id }

    /** Board-level roll-up: one RED sleeve makes the polyglot fleet RED. */
    fun summarize(): Map<String, Any?> = mapOf(
        "sleeves" to ALL.map { it.toMap() },
        "allGreen" to ALL.all { it.isGreen },
        "anyRed" to ALL.any { it.isRed },
        "unresolvedTraps" to ALL.sumOf { it.unresolved.size },
        "uncatchable" to ALL.sumOf { m -> m.unresolved.count { it.catchability == TrapCatchability.HOST_UNCATCHABLE } },
    )

    /**
     * The four bounds a guest meets before it meets anything specific to itself. Seeded UNRESOLVED: they
     * are known to exist, not yet reproduced against this donor, and the red test is what reproduces them.
     */
    private fun boundTraps(sleeve: String): List<PolyglotTrap> = listOf(
        Triple("thread-spawn", "_thread" to "spawn under allowCreateThread(false)", TrapCatchability.HOST_UNCATCHABLE),
        Triple("process-spawn", "subprocess" to "spawn under allowCreateProcess(false)", TrapCatchability.HOST_UNCATCHABLE),
        Triple("host-io", "io + os.path" to "host paths under allowIO(false) — only the UserspaceBtrfs subvolume", TrapCatchability.GUEST_CATCHABLE),
        Triple("native-access", "ctypes" to "native calls under allowNativeAccess(false)", TrapCatchability.GUEST_CATCHABLE),
    ).map { (name, spec, catchability) ->
        val (module, bound) = spec
        PolyglotTrap(
            id = "$sleeve/$name",
            donorModule = module,
            symbol = bound,
            shape = TrapShape.UNRESOLVED,
            catchability = catchability,
            forkPath = "trikeshed_guest_prelude.py",
            redTest = "AgentSleeveTddRedTest.${sleeve}IsRedUntilItsDonorRootIsPinned",
            evidence = "not yet reproduced against a pinned $sleeve checkout",
        )
    }
}
