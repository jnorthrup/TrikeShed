//     dispatchCoroutine ←──SlotFreed── settleCoroutine
    //
    // The daemon calls startReactiveCycle(scope) instead of looping cycle().
    // ─────────────────────────────────────────────────────────────────────

    /** Signal channel: a slot freed, dispatch should try to fill it. */
    private val slotFreed = kotlinx.coroutines.channels.Channel<Int>(kotlinx.coroutines.channels.Channel.CONFLATED)

    /** Serializes HTX exchanges per endpoint ordinal. */
    private val drainMutex = Mutex()
    private val answerMutex = Mutex()
    private val dispatchMutex = Mutex()

    /**
     * Launch the reactive choreography. Returns immediately; the coroutines
     * run in [scope] and die when it's cancelled. The daemon's periodicity
     * loop becomes a simple poll trigger — everything else is reactive.
     */
    suspend fun startReactiveCycle(scope: kotlinx.coroutines.CoroutineScope) {
        // Capture the HTX element from the calling scope so the reactive
        // coroutines (launched on Dispatchers.Default) inherit it for all
        // Jules/ModelMux API calls. Without this, launch(Dispatchers.Default)
        // drops the HtxKey and every API call fails.
        val htxElement = kotlin.coroutines.coroutineContext[HtxKey]
        require(htxElement != null) {
            "startReactiveCycle must be called inside a withContext(htxElement) block"
        }
        // Prime the dispatch pump: free capacity may exist at startup before
        // the first poll discovers any state. Without this, dispatch parks on
        // slotFreed.receive() and the wheel deadlocks — nothing drains because
        // nothing was dispatched, and nothing dispatches because nothing drained.
        slotFreed.trySend(maxSlots)

        // Cross-coroutine dispatch counter. The dispatch coroutine bumps this
        // on every successful createSession; the poll coroutine snapshots it
        // at tick boundary into lastReactiveReport.
        val tickDispatched = java.util.concurrent.atomic.AtomicInteger(0)

        // FAN-OUT: drain pipeline. Polls Jules, kicks off drain/answer/approve
        // as fire-and-forget coroutines, and signals dispatch. Nothing in this
        // loop blocks on brain/drain latency — those run concurrently.
        scope.launch(htxElement + Dispatchers.Default) {
            var tickAnswered = 0
            var tickHarvested = 0
            while (true) {
                val tickStart = System.currentTimeMillis()
                cycleHttp429 = 0
                cycleHttp5xx = 0
                tickAnswered = 0
                tickHarvested = 0
                tickDispatched.set(0)

                try {
                    withTimeoutOrNull(intervalMs) { conductor.pollOnce() }
                } catch (t: Throwable) {
                    classifyHttpError(t)
                    _events.tryEmit(FlywheelEvent.PollError("reactive poll: ${t.message?.take(200)}"))
                }

                // Fan-out: drain completed sessions in a child coroutine so
                // brain/build latency never blocks the poll loop. The drain
                // guard prevents concurrent drains.
                val completed = conductor.cards.values.filter {
                    it.snapshot.state in DRAINABLE_STATES && !it.drained
                }
                if (completed.isNotEmpty()) {
                    val sessions = completed.map {
                        JulesRestClient.SessionInfo(it.snapshot.sessionId, it.snapshot.state, it.card.title, 0L)
                    }
                    scope.launch(htxElement + Dispatchers.IO) {
                        drainMutex.withLock {
                            val drain = drainFanout(sessions)
                            val freed = completed.count { it.drained }
                            if (freed > 0) {
                                slotFreed.trySend(freed)
                                println("[CHOREOGRAPHY] drain → dispatch signal: $freed slots freed")
                            }
                        }
                    }
                }

                // Fan-out: answer/approve waiting sessions in child coroutines
                // so brain latency never blocks the poll loop.
                val awaiting = conductor.cards.values.filter {
                    it.snapshot.state.toJulesState() == JulesSessionState.AwaitingUserFeedback &&
                        it.causes.lastOrNull() !is JulesCause.HumanAnswered
                }
                for (card in awaiting) {
                    scope.launch(htxElement + Dispatchers.IO) {
                        answerMutex.withLock {
                            val answer = withTimeoutOrNull(45_000L) { buildAnswer(card) } ?: ""
                            if (answer.isNotEmpty()) {
                                conductor.answer(card.snapshot.sessionId, answer)
                                println("[CHOREOGRAPHY] answer ${card.snapshot.sessionId.takeLast(6)}")
                            }
                        }
                    }
                    tickAnswered++
                }

                // Fan-out: approve plans concurrently
                for (card in conductor.cards.values.filter {
                    it.snapshot.state == "AWAITING_PLAN_APPROVAL" &&
                        it.causes.lastOrNull() !is JulesCause.HumanAnswered
                }) {
                    scope.launch(htxElement + Dispatchers.IO) {
                        answerMutex.withLock {
                            withTimeoutOrNull(45_000L) { conductor.approvePlan(card.snapshot.sessionId) }
                        }
                    }
                    tickAnswered++
                }

                // Sweep terminal failures — instant, no I/O
                for (card in conductor.cards.values.filter {
                    it.snapshot.state in setOf("FAILED", "CANCELLED") && !it.drained
                }) {
                    conductor.retireTerminal(card.snapshot.sessionId, "terminal ${card.snapshot.state}", Clock.System.now().toEpochMilliseconds())
                    slotFreed.trySend(1)
                    println("[CHOREOGRAPHY] sweep ${card.snapshot.sessionId.takeLast(6)} → slot freed")
                }

                // Emit poll event for observers