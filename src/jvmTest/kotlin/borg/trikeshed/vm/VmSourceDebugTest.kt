package borg.trikeshed.vm

import borg.trikeshed.graal.subvm.GuestException
import kotlinx.coroutines.*
import kotlin.test.*

class VmSourceDebugTest {
    @Test fun authoredCoordinatesFollowTheEmittedBodyWithoutChangingGeneratedBreakpoints() = runBlocking {
        for ((language, source) in listOf(
            VmLanguage.JAVASCRIPT to "let count = 0;\nfor (let i = 0; i < 3; i++) {\n  count += 1;\n}\nreturn count;",
            VmLanguage.PYTHON to "count = 0\nfor i in range(3):\n    count += 1\nreturn count",
            VmLanguage.RUBY to "count = 0\n3.times do\n  count += 1\nend\nreturn count",
        )) {
            HypervisorVmHost().use { host ->
                val guest = host.spawn(VmSpec("authored-${language.id}", checkNotNull(language.facet)))
                val program = VmLanguageProgram(language, source)
                val body = source.lines()
                val first = program.declaration().lineSequence().indexOfFirst { it.trim() == body.first().trim() } + 1
                val target = first + 2
                var stops = 0
                try {
                    val result = program.evaluate(guest, Teleported.Null, "authored-source",
                        debug = VmDebugRequest(currentCoroutineContext()) { pause ->
                            stops++
                            val authored = assertIs<Map<*, *>>(pause["authored"], "${language.id}: missing authored-body coordinates")
                            assertEquals(first, authored["generatedStartLine"])
                            assertEquals(body.size, authored["lineCount"])
                            assertEquals("generated-guest-source", pause["coordinates"])
                            val at = pause["line"] as Int
                            assertEquals((at - first + 1).takeIf { it in 1..body.size }, authored["line"])
                            val lines = authored["sourceLines"] as List<*>
                            assertTrue(lines.size <= 17)
                            if (lines.isNotEmpty()) {
                                val start = authored["lineStart"] as Int
                                lines.forEachIndexed { index, text -> assertEquals(body[start + index - 1], text) }
                            }
                            if (stops == 1) VmDebugAction.Line(target) else {
                                assertEquals(2, stops)
                                val resolved = if (language == VmLanguage.RUBY) 2 else 3
                                assertEquals(resolved, authored["line"])
                                assertEquals(mapOf("hit" to true, "requestedLine" to 3, "resolvedLine" to resolved), authored["breakpoint"])
                                assertEquals(mapOf("hit" to true, "requestedLine" to target, "resolvedLine" to first + resolved - 1), pause["breakpoint"])
                                VmDebugAction.CONTINUE
                            }
                        })
                    assertEquals(Teleported.Num(3), result)
                    assertEquals(2, stops)
                } finally { host.revoke(guest.id, "test complete") }
                assertFalse(guest.isAlive)
            }
        }
    }

    @Test fun rubyTailExpressionExposesItsBlockEntryBoundary() = runBlocking {
        HypervisorVmHost().use { host ->
            val guest = host.spawn(VmSpec("ruby-loop-statements", borg.trikeshed.pointcut.VmFacet.GRAAL_RUBY))
            val lines = mutableListOf<Int>()
            try {
                val program = VmLanguageProgram(VmLanguage.RUBY, "count = 0\n3.times do\n  count += 1\nend\nreturn count")
                val blockLine = program.declaration().lineSequence().indexOfFirst { it.trim() == "3.times do" } + 1
                val result = program.evaluate(guest, Teleported.Null, "ruby-loop", debug = VmDebugRequest(currentCoroutineContext()) { pause ->
                    check(lines.size < 256)
                    if (pause["element"] == "function" && pause["anchor"] == "before") lines += pause["line"] as Int
                    VmDebugAction.INTO
                })
                assertEquals(Teleported.Num(3), result)
                assertEquals(3, lines.count { it == blockLine })
            } finally { host.revoke(guest.id, "test complete") }
        }
    }

    @Test fun lineBreakpointsUseEachNativeGuestsGeneratedSource() = runBlocking {
        for ((language, source) in listOf(
            VmLanguage.JAVASCRIPT to "let count = 0;\nfor (let i = 0; i < 3; i++) {\n  count += 1;\n}\nreturn count;",
            VmLanguage.PYTHON to "count = 0\nfor i in range(3):\n    count += 1\nreturn count",
            VmLanguage.RUBY to "count = 0\n3.times do\n  count += 1\nend\nreturn count",
        )) {
            HypervisorVmHost().use { host ->
                val guest = host.spawn(VmSpec("line-${language.id}", checkNotNull(language.facet)))
                val program = VmLanguageProgram(language, source)
                val target = program.declaration().lineSequence().indexOfFirst { it.trim().startsWith("count += 1") } + 1
                var stops = 0
                try {
                    val result = program.evaluate(guest, Teleported.Null, "line-source",
                        debug = VmDebugRequest(currentCoroutineContext()) { pause ->
                            stops++
                            if (stops == 1) {
                                VmDebugAction.Line(target)
                            } else {
                                check(stops == 2) { "One-shot breakpoint suspended more than once" }
                                // TruffleRuby exposes this tail expression at the enclosing block's entry.
                                val resolved = if (language == VmLanguage.RUBY) target - 1 else target
                                assertEquals(resolved, pause["line"])
                                assertEquals(mapOf("hit" to true, "requestedLine" to target, "resolvedLine" to resolved), pause["breakpoint"])
                                assertEquals("before", pause["anchor"])
                                val lines = pause["sourceLines"] as List<*>
                                assertTrue(lines.size <= 17)
                                assertTrue(lines.all { (it as String).length <= 512 })
                                assertTrue((lines[target - (pause["lineStart"] as Int)] as String).contains("count += 1"))
                                val locals = ((pause["frames"] as List<*>).first() as Map<*, *>)["locals"] as List<*>
                                assertTrue(locals.any { val local = it as Map<*, *>; local["name"] == "count" && local["value"] == 0L })
                                VmDebugAction.CONTINUE
                            }
                        })
                    assertEquals(Teleported.Num(3), result)
                    assertEquals(2, stops, "${language.id}: target $target was not hit")
                } finally { host.revoke(guest.id, "test complete") }
                assertFalse(guest.isAlive)
            }
        }
    }

    @Test fun nonExecutableLineReportsTheResolvedStatement() = runBlocking {
        HypervisorVmHost().use { host ->
            val guest = host.spawn(VmSpec("line-resolution", borg.trikeshed.pointcut.VmFacet.GRAAL_JS))
            var stops = 0
            try {
                val result = VmLanguageProgram(VmLanguage.JAVASCRIPT, "let count = 0;\n\ncount += 1;\nreturn count;")
                    .evaluate(guest, Teleported.Null, "line-resolution", debug = VmDebugRequest(currentCoroutineContext()) { pause ->
                        stops++
                        if (stops == 1) VmDebugAction.Line(3) else {
                            assertEquals(mapOf("hit" to true, "requestedLine" to 3, "resolvedLine" to 4), pause["breakpoint"])
                            VmDebugAction.CONTINUE
                        }
                    })
                assertEquals(Teleported.Num(1), result)
                assertEquals(2, stops)
            } finally { host.revoke(guest.id, "test complete") }
        }
    }

    @Test fun unreachedLineCompletesAndDoesNotLeakIntoTheNextCall() = runBlocking {
        HypervisorVmHost().use { host ->
            val guest = host.spawn(VmSpec("line-unreached", borg.trikeshed.pointcut.VmFacet.GRAAL_JS))
            try {
                var firstStops = 0
                val first = VmLanguageProgram(VmLanguage.JAVASCRIPT, "if (input) {\n  return 99;\n}\nreturn 7;")
                    .evaluate(guest, Teleported.Bool(false), "same-source", debug = VmDebugRequest(currentCoroutineContext()) {
                        firstStops++
                        check(firstStops == 1) { "Unreachable statement should not suspend" }
                        VmDebugAction.Line(3)
                    })
                assertEquals(Teleported.Num(7), first)
                assertEquals(1, firstStops)
                var nextStops = 0
                val next = VmLanguageProgram(VmLanguage.JAVASCRIPT, "let count = 0;\ncount += 1;\nreturn count;")
                    .evaluate(guest, Teleported.Null, "same-source", debug = VmDebugRequest(currentCoroutineContext()) { pause ->
                        nextStops++
                        assertNull(pause["breakpoint"])
                        VmDebugAction.CONTINUE
                    })
                assertEquals(Teleported.Num(1), next)
                assertEquals(1, nextStops)
            } finally { host.revoke(guest.id, "test complete") }
            assertFalse(guest.isAlive)
        }
    }

    @Test fun stepOverSkipsTheCalleeAndStepOutReturnsToItsCaller() = runBlocking {
        for (mode in listOf(VmDebugAction.OVER, VmDebugAction.OUT)) {
            HypervisorVmHost().use { host ->
                val guest = host.spawn(VmSpec("source-$mode", borg.trikeshed.pointcut.VmFacet.GRAAL_JS))
                val frames = mutableListOf<String>()
                var entered = false
                var returned = false
                try {
                    val result = VmLanguageProgram(VmLanguage.JAVASCRIPT,
                        "function add(x) {\n const y = x + 1;\n return y;\n}\nconst result = add(input);\nreturn result;")
                        .evaluate(guest, Teleported.Num(41), "step-source", debug = VmDebugRequest(currentCoroutineContext()) { pause ->
                            val frame = ((pause["frames"] as List<*>).first() as Map<*, *>)["name"] as String
                            frames += frame
                            check(frames.size < 20) { "Source stepping did not terminate" }
                            if (mode == VmDebugAction.OVER) VmDebugAction.OVER
                            else if (frame == "add") { entered = true; VmDebugAction.OUT }
                            else if (entered) { returned = true; VmDebugAction.CONTINUE }
                            else VmDebugAction.INTO
                        })
                    assertEquals(Teleported.Num(42), result)
                    if (mode == VmDebugAction.OVER) assertFalse("add" in frames)
                    else assertTrue(entered && returned)
                } finally { host.revoke(guest.id, "test complete") }
                assertFalse(guest.isAlive)
            }
        }
    }

    @Test fun nativeLanguagesExposeCopiedScalarLocalsWithoutReadingObjects() = runBlocking {
        for ((language, source) in listOf(
            VmLanguage.JAVASCRIPT to "let count = 1;\nconst record = { get value() { count++; return 99; } };\nreturn count;",
            VmLanguage.PYTHON to "count = 1\nrecord = {'value': 99}\nreturn count",
            VmLanguage.RUBY to "count = 1\nrecord = {'value' => 99}\nreturn count",
        )) {
            HypervisorVmHost().use { host ->
                val guest = host.spawn(VmSpec("source-${language.id}", requireNotNull(language.facet),
                    budget = VmBudget(statements = 5000000, wallMillis = 15000)))
                val snapshots = mutableListOf<Map<String, Any?>>()
                try {
                    val request = VmDebugRequest(currentCoroutineContext()) { pause ->
                        snapshots += pause
                        check(snapshots.size < 256) { "Source stepping failed to terminate for ${language.id}" }
                        VmDebugAction.INTO
                    }
                    val result = VmLanguageProgram(language, source).evaluate(guest, Teleported.Null, "native-source", debug = request)
                    println("Source debug ${language.id}: ${snapshots.size} suspensions")
                    assertEquals(Teleported.Num(1), result, "Observation must not evaluate an object getter")
                    assertTrue(snapshots.isNotEmpty(), language.id)
                    val locals = snapshots.flatMap { pause ->
                        assertEquals("native-source", pause["sourceName"])
                        assertEquals("generated-guest-source", pause["coordinates"])
                        (pause["frames"] as List<*>).flatMap { (it as Map<*, *>)["locals"] as List<*> }
                    }.map { it as Map<*, *> }
                    assertTrue(locals.any { it["name"] == "count" && (it["value"] as? Number)?.toInt() == 1 }, language.id)
                    assertTrue(locals.any { it["name"] == "record" && it["omitted"] == "non_scalar" }, "${language.id}: $locals")
                } finally { host.revoke(guest.id, "test complete") }
                assertFalse(guest.isAlive)
            }
        }
    }

    @Test fun callerCancellationReleasesASuspendedGuestAndJoinsItsCallback() = runBlocking {
        HypervisorVmHost().use { host ->
            val paused = CompletableDeferred<Unit>()
            val waiting = async(Dispatchers.Default) {
                val guest = host.spawn(VmSpec("source-cancel", borg.trikeshed.pointcut.VmFacet.GRAAL_JS))
                try {
                    VmLanguageProgram(VmLanguage.JAVASCRIPT, "let value = 1;\nreturn value;")
                        .evaluate(guest, Teleported.Null, "cancel-source", debug = VmDebugRequest(currentCoroutineContext()) {
                            paused.complete(Unit)
                            awaitCancellation()
                        })
                } finally { host.revoke(guest.id, "caller closed") }
            }
            try {
                withTimeout(20000) { paused.await() }
                withTimeout(5000) { waiting.cancelAndJoin() }
                assertFalse(waiting.children.any())
                assertTrue(host.ids().none { host.get(it)?.isAlive == true })
            } finally { waiting.cancelAndJoin() }
        }
    }

    @Test fun guestWallDeadlineWakesASourceHoldBeforeWaitingForTheSafepoint() = runBlocking {
            val paused = CompletableDeferred<Unit>()
            withTimeout(20000) {
                withContext(Dispatchers.Default) {
                    borg.trikeshed.graal.subvm.InProcessIsolate("source-wall-direct", borg.trikeshed.pointcut.VmFacet.GRAAL_JS,
                        borg.trikeshed.graal.subvm.Budget(statements = 0, wallMillis = 300)).use { isolate ->
                        isolate.eval("function check() { return 1; }", "wall-source")
                        isolate.debug("wall-source", VmDebugRequest(currentCoroutineContext()) {
                            paused.complete(Unit)
                            awaitCancellation()
                        }).use {
                            assertFailsWith<GuestException> { isolate.call("check") }
                        }
                    }
                }
            }
            assertTrue(paused.isCompleted)
    }

    @Test fun inspectionFailureStopsTheGuestAndStillReleasesIt() = runBlocking {
        HypervisorVmHost().use { host ->
            val guest = host.spawn(VmSpec("source-failure", borg.trikeshed.pointcut.VmFacet.GRAAL_JS))
            val cause = IllegalArgumentException("inspection failed")
            try {
                val failure = assertFailsWith<IllegalStateException> {
                    VmLanguageProgram(VmLanguage.JAVASCRIPT, "return input;")
                        .evaluate(guest, Teleported.Null, "failed-source", debug = VmDebugRequest(currentCoroutineContext()) {
                            throw cause
                        })
                }
                assertSame(cause, failure.cause)
            } finally { host.revoke(guest.id, "test complete") }
            assertFalse(guest.isAlive)
        }
    }

    @Test fun unsupportedSourceDebuggingRefusesBeforeEvaluatingAnyGuestCode() = runBlocking {
        var evals = 0
        val handle = object : VmHandle {
            override val id = "no-debug"
            override val facet = borg.trikeshed.pointcut.VmFacet.GRAAL_JS
            override val tier = "process"
            override fun eval(source: String, name: String): Teleported { evals++; return Teleported.Null }
        }
        assertFailsWith<IllegalArgumentException> {
            VmLanguageProgram(VmLanguage.JAVASCRIPT, "return input;").evaluate(handle, Teleported.Null, "unsupported",
                debug = VmDebugRequest(currentCoroutineContext()) { error("No source callback expected") })
        }
        assertEquals(0, evals)
    }
}
