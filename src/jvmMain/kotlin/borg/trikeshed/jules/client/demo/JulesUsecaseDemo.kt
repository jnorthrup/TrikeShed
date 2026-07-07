package borg.trikeshed.jules.client.demo

import borg.trikeshed.ccek.*
import borg.trikeshed.htx.*
import borg.trikeshed.jules.client.*
import borg.trikeshed.lib.ByteSeries
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.coroutineContext

object JulesUsecaseDemo {

    class MockJulesRouteService : HtxRouteService {
        override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
            val path = request.target.requestPath
            val method = request.method

            val responseBody = when {
                method == HtxMethod.POST && path.contains("/v1alpha/sessions") -> {
                    """{"name": "sessions/jules-nio-reactor-pr", "id": "jules-nio-reactor-pr", "prompt": "PR a userspace nio reactor driven commonMain official non-expect-actual async reactor fanout code"}"""
                }
                method == HtxMethod.GET && path.contains("/activities") -> {
                    """{"activities": [{"name": "act1", "type": "code-generation", "status": "COMPLETED"}]}"""
                }
                method == HtxMethod.POST && path.contains("sendMessage") -> {
                    """{"status": "SUCCESS"}"""
                }
                else -> """{"message": "ok"}"""
            }

            val responded = state.copy(
                lifecycle = HtxExchangeLifecycle.RESPONDED,
                request = request,
                response = HtxResponse(status = 200, body = ByteSeries(responseBody.encodeToByteArray()))
            )
            return HtxExchangeResult(responded)
        }
    }

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        println("=== STARTING JULES AGENT USECASE DEMO ===")
        val fakeService = MockJulesRouteService()
        val htxElement = openHtxElement(routeService = fakeService)
        val context = coroutineContext + htxElement
        val keySource = keymux.TestKeySource(name = "test", value = "demo-api-key")
        val keyMux = keymux.KeyMux {
            bind("llm.jules.key", keySource)
        }
        val client = JulesClient(context, "demo-api-key")

        // 1. Initialize JulesAgent
        val agent = JulesAgent(
            agentId = "jules-nio-generator",
            keyMux = keyMux,
            client = client
        )
        
        // 2. Observe the state and history via slots
        val stateObs = agent.state
        val historyObs = agent.history

        println("Initial State: ${stateObs.value}")

        // 3. Assign task (Create Session)
        val prompt = "PR a userspace nio reactor driven commonMain official non-expect-actual async reactor fanout code"
        println("\n--- Assigning Task to Jules Agent ---")
        println("Prompt: $prompt")
        
        agent.startSession(prompt, "NIO Reactor PR Assignment")
        println("Current State: ${stateObs.value}")

        // 4. Transition to DRAINING to simulate code generation and PR drafting
        println("\n--- Jules Agent generating NIO Async Reactor Fanout Code ---")
        agent.transitionTo(JulesAgentState.DRAINING, "Generating AsyncReactor.kt...")

        // The mock code created by the agent
        val prCode = """
            |package borg.trikeshed.userspace.reactor
            |
            |import kotlinx.coroutines.CoroutineScope
            |import kotlinx.coroutines.channels.Channel
            |import kotlinx.coroutines.flow.MutableSharedFlow
            |import kotlinx.coroutines.flow.SharedFlow
            |import kotlinx.coroutines.flow.asSharedFlow
            |import kotlinx.coroutines.launch
            |
            |/**
            | * Platform-independent userspace NIO Async Reactor.
            | * Does not use expect-actual.
            | * Drives async fanout of network event frames using coroutine channels and flows.
            | */
            |class AsyncReactor(
            |    private val scope: CoroutineScope
            |) {
            |    private val eventChannel = Channel<ReactorEvent>(Channel.BUFFERED)
            |    
            |    private val _eventFlow = MutableSharedFlow<ReactorEvent>(replay = 16)
            |    val eventFlow: SharedFlow<ReactorEvent> = _eventFlow.asSharedFlow()
            |
            |    init {
            |        scope.launch {
            |            for (event in eventChannel) {
            |                _eventFlow.emit(event)
            |            }
            |        }
            |    }
            |
            |    fun post(event: ReactorEvent) {
            |        eventChannel.trySend(event)
            |    }
            |}
            |
            |interface ReactorEvent {
            |    val key: String
            |    val payload: ByteArray
            |}
        """.trimMargin()

        println("\n--- Drafted Pull Request Diff / Generated File contents: ---")
        println(prCode)

        // Simulate PR complete and transition to TERMINATED
        println("\n--- Completing PR and Shutdown ---")
        agent.transitionTo(JulesAgentState.TERMINATED, "Pull Request drafted successfully. Code changes generated.")

        println("Final State: ${stateObs.value}")

        // 5. Inspect and Print full observable lifecycle history
        println("\n--- Observable Lifecycle History Log: ---")
        historyObs.value.forEachIndexed { index, entry ->
            println(" [$index] Time: ${entry.timestamp} | State: ${entry.state} | Details: ${entry.details}")
        }

        htxElement.close()
        println("\n=== DEMO COMPLETED SUCCESSFULLY ===")
    }
}
