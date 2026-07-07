package borg.trikeshed.jules.client.demo

import borg.trikeshed.ccek.*
import borg.trikeshed.htx.*
import borg.trikeshed.jules.client.*
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlin.coroutines.coroutineContext

object LiveJulesPrApp {

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        println("=== FIRING LIVE JULES API REQUEST & PR EXECUTION VIA USERSPACE NIO ===")
        
        val envKey = System.getenv("JULES_API_KEY") ?: ""
        val keySource = keymux.TestKeySource(name = "env", value = envKey)
        val keyMux = keymux.KeyMux {
            bind("llm.jules.key", keySource)
        }

        val apiKey = keyMux.get("llm.jules.key")
        if (apiKey.isNullOrEmpty()) {
            error("Could not resolve llm.jules.key from KeyMux!")
        }

        println("Using live Jules API key resolved via KeyMux ending with: ...${apiKey.takeLast(8)}")

        // ── Initialize the official userspace NIO supervisor ──
        val supervisor = borg.trikeshed.userspace.nio.spi.NioSupervisor()
        supervisor.open()

        val reactor = supervisor.service<HtxReactorElement>()
            ?: error("No HtxReactorElement registered by platformNioProviders")

        val htxElement = openHtxElement(routeService = reactor)
        val context = coroutineContext + htxElement
        val client = JulesClient(context, apiKey)
        
        val agent = JulesAgent(
            agentId = "live-reactor-generator",
            keyMux = keyMux,
            client = client
        )
        
        val stateObs = agent.state
        val historyObs = agent.history

        println("Initial Agent State: ${stateObs.value}")

        val prompt = "PR a userspace nio reactor driven commonMain official non-expect-actual async reactor fanout code"
        println("\n--- 1. Creating Session ---")
        println("Prompt: $prompt")

        try {
            val session = agent.startSession(prompt = prompt, title = "Live Async Reactor Fanout PR")
            val sessionName = session.name
            println("Successfully created live session: $sessionName")
            println("Session URL: https://jules.google.com/session/${session.id}")
            
            // Wait for Jules to initialize and register the plan
            println("\n--- 2. Waiting 10 seconds for plan generation... ---")
            delay(10000)

            // Let's retrieve activities
            try {
                println("Fetching current activities...")
                val activities = client.listActivities(sessionName)
                println("Activities: $activities")
            } catch (e: Exception) {
                println("Note: could not list activities yet: ${e.message}")
            }

            // 3. Approve Plan
            println("\n--- 3. Approving Plan on session $sessionName ---")
            try {
                client.approvePlan(sessionName)
                println("Plan approval request sent successfully!")
                agent.transitionTo(JulesAgentState.DRAINING, "Plan approved, waiting for code generation...")
            } catch (e: Exception) {
                println("Failed to approve plan: ${e.message}. Proceeding anyway...")
            }

            // 4. Send Message to trigger generation
            println("\n--- 4. Sending prompt message to Jules ---")
            try {
                client.sendMessage(sessionName, "Please draft the AsyncReactor and ReactorEvent interfaces in commonMain package borg.trikeshed.userspace.reactor without using platform expect/actual.")
                println("Message sent successfully!")
            } catch (e: Exception) {
                println("Failed to send message: ${e.message}")
            }

            // 5. Polling for updates
            println("\n--- 5. Polling activities for 30 seconds... ---")
            for (i in 1..6) {
                delay(5000)
                println("Poll #${i} (elapsed: ${i * 5}s)...")
                try {
                    val status = client.getSession(sessionName)
                    println("  Session status: name=${status.name} requirePlanApproval=${status.requirePlanApproval}")
                    val activities = client.listActivities(sessionName)
                    println("  Active activities: $activities")
                } catch (e: Exception) {
                    println("  Poll error: ${e.message}")
                }
            }

            // 6. Try to list sources
            println("\n--- 6. Listing generated sources ---")
            try {
                val sources = client.listSources()
                println("Sources found: ${sources.size}")
                sources.forEach { s ->
                    println("  Source: ${s.name}")
                    println("  Content Preview:\n${s.content?.take(300)}")
                }
            } catch (e: Exception) {
                println("Failed to list sources: ${e.message}")
            }

            agent.transitionTo(JulesAgentState.TERMINATED, "Live PR request completed and polled.")
            println("Final Agent State: ${stateObs.value}")

        } catch (e: Exception) {
            println("ERROR while executing live Jules request: ${e.message}")
            e.printStackTrace()
            agent.transitionTo(JulesAgentState.TERMINATED, "Live PR request failed: ${e.message}")
        }

        println("\n--- Observable Lifecycle History Log: ---")
        historyObs.value.forEachIndexed { index, entry ->
            println(" [$index] Time: ${entry.timestamp} | State: ${entry.state} | Details: ${entry.details}")
        }
        
        reactor.close()
        supervisor.close()
        htxElement.close()
        
        println("\n=== LIVE PR REQUEST PROCESS COMPLETE ===")
    }
}
