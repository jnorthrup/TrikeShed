package borg.trikeshed.parse.kursive.nars3

import borg.trikeshed.lib.*
import borg.trikeshed.collections.s_
import borg.trikeshed.parse.kursive.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

// NARS3 Machine and Context

/**
 * NARS3 IKR Resource Budget.
 */
data class Nars3Budget(
    val priority: Float,
    val durability: Float,
    val quality: Float
)

/**
 * An item of knowledge, potentially incomplete, processed by atoms.
 */
data class Nars3Message(
    val content: String,
    val budget: Nars3Budget,
    val elements: Series<NarsiveElement> = Join.emptySeriesOf()
)

/**
 * A NARS3 Atom: The fundamental unit of execution in the NARS3 Machine.
 * Atoms are refeeding, meaning they can process inputs and produce outputs,
 * exploring IKR (Incomplete Knowledge and Resources) via channels.
 */
abstract class Nars3Atom(
    val id: String,
    val knowledge: Series<NarsiveElement>,
    var resources: Nars3Budget
) {
    abstract suspend fun process(input: Channel<Nars3Message>, output: Channel<Nars3Message>)
}

class LocalAtom(id: String, knowledge: Series<NarsiveElement>, resources: Nars3Budget) : Nars3Atom(id, knowledge, resources) {
    override suspend fun process(input: Channel<Nars3Message>, output: Channel<Nars3Message>) {
        for (msg in input) {
            // Local processing, deduct from budget
            val newBudget = Nars3Budget(msg.budget.priority * 0.9f, msg.budget.durability * 0.9f, msg.budget.quality)
            if (newBudget.priority > 0.1f) {
                output.send(msg.copy(content = "Local($id) -> ${msg.content}", budget = newBudget))
            }
        }
        output.close()
    }
}

class ChannelizedAtom(id: String, knowledge: Series<NarsiveElement>, resources: Nars3Budget) : Nars3Atom(id, knowledge, resources) {
    override suspend fun process(input: Channel<Nars3Message>, output: Channel<Nars3Message>) {
        for (msg in input) {
            // Channelized processing, deduct from budget
            val newBudget = Nars3Budget(msg.budget.priority * 0.9f, msg.budget.durability * 0.9f, msg.budget.quality)
            if (newBudget.priority > 0.1f) {
                output.send(msg.copy(content = "Channelized($id) -> ${msg.content}", budget = newBudget))
            }
        }
        output.close()
    }
}


/**
 * The NARS3 Machine: Constructs arbitrary ephemeral arena chains of bottom-to-top
 * refeeding atoms. It uses a reactor SupervisorJob assembly to manage channelized
 * and local atoms for exploring IKR.
 */
class Nars3Machine(private val scope: CoroutineScope) {

    private val supervisor = SupervisorJob(scope.coroutineContext[Job])
    private val machineScope = CoroutineScope(scope.coroutineContext + supervisor)

    fun createArenaChain(atoms: Series<Nars3Atom>, initialKnowledge: Nars3Message) {
        machineScope.launch {
            if (atoms.isEmpty()) return@launch

            var currentInput = Channel<Nars3Message>()
            val initialInput = currentInput

            for (i in 0 until atoms.size) {
                val atom = atoms[i]
                val currentOutput = Channel<Nars3Message>()

                launch {
                    atom.process(currentInput, currentOutput)
                }

                currentInput = currentOutput
            }

            // Final output handling - bottom to top refeeding
            launch {
                for (msg in currentInput) {
                    if (msg.budget.priority > 0.2f) {
                        // Refeed to the bottom
                        initialInput.send(msg.copy(budget = Nars3Budget(msg.budget.priority * 0.8f, msg.budget.durability, msg.budget.quality)))
                    }
                }
                initialInput.close()
            }

            // Feed initial input
            launch {
                initialInput.send(initialKnowledge)
                initialInput.close()
            }
        }
    }

    fun shutdown() {
        supervisor.cancel()
    }
}
