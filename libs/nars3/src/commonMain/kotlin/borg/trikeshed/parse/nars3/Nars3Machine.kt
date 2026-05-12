package borg.trikeshed.parse.nars3

import borg.trikeshed.lib.*
import borg.trikeshed.parse.narsive.*
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
    val content: CharSequence,
    val budget: Nars3Budget,
    val elements: Series<NarsiveElement> = Join.emptySeriesOf()
)

/**
 * A NARS3 Atom: The fundamental unit of execution in the NARS3 Machine.
 * Atoms are refeeding, meaning they can process inputs and produce outputs,
 * exploring IKR (Incomplete Knowledge and Resources) via channels.
 */
abstract class Nars3Atom(
    val id: CharSequence,
    val knowledge: Series<NarsiveElement>,
    var resources: Nars3Budget
) {
    abstract suspend fun process(input: Channel<Nars3Message>, output: Channel<Nars3Message>)
}

class Nars3RefeedingAtom(id: CharSequence, knowledge: Series<NarsiveElement>, resources: Nars3Budget) : Nars3Atom(id, knowledge, resources) {
    override suspend fun process(input: Channel<Nars3Message>, output: Channel<Nars3Message>) {
        for (msg in input) {
            // Processing, deduct from budget
            val newBudget = Nars3Budget(msg.budget.priority * 0.9f, msg.budget.durability * 0.9f, msg.budget.quality)
            if (newBudget.priority > 0.1f) {
                output.send(msg.copy(content = "Atom($id) -> ${msg.content}", budget = newBudget))
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

    private var supervisor: CompletableJob? = null
    private var machineScope: CoroutineScope? = null

    fun createArenaChain(atoms: Series<Nars3Atom>, initialKnowledge: Nars3Message) {
        ensureMachineScope().launch {
            if (atoms.isEmpty()) return@launch

            var currentInput = Channel<Nars3Message>(Channel.BUFFERED)
            val initialInput = currentInput

            for (i in 0 until atoms.size) {
                val atom = atoms[i]
                val currentOutput = Channel<Nars3Message>(Channel.BUFFERED)

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
            }
        }
    }

    fun deriveNonConditionalPairs(left: Nars3Message, right: Nars3Message): Series<Nars3Message> {
        val unionMask = left.nonConditionalOperatorMask() or right.nonConditionalOperatorMask()
        return derivePairMessages(left, right, unionMask)
    }

    suspend fun deriveAll(
        messages: Series<Nars3Message>,
        scope: CoroutineScope,
        onDerived: (Nars3Message) -> Unit,
    ) {
        val scan = scanNonConditional(messages)
        if (scan.count < 2) return

        withContext(scope.coroutineContext.minusKey(Job)) {
            supervisorScope {
                val pairCount = scan.count * (scan.count - 1) / 2
                val tasks = ArrayList<Deferred<Series<Nars3Message>>>(pairCount)
                var leftOrdinal = 0
                while (leftOrdinal < scan.count) {
                    val leftIndex = scan.indices[leftOrdinal]
                    val leftMessage = messages[leftIndex]
                    val leftMask = scan.masks[leftOrdinal]
                    var rightOrdinal = leftOrdinal + 1
                    while (rightOrdinal < scan.count) {
                        val rightIndex = scan.indices[rightOrdinal]
                        val rightMessage = messages[rightIndex]
                        val unionMask = leftMask or scan.masks[rightOrdinal]
                        tasks += async {
                            derivePairMessages(leftMessage, rightMessage, unionMask)
                        }
                        rightOrdinal++
                    }
                    leftOrdinal++
                }

                val results = tasks.awaitAll()
                var resultOrdinal = 0
                while (resultOrdinal < results.size) {
                    val derived = results[resultOrdinal]
                    var derivedOrdinal = 0
                    while (derivedOrdinal < derived.size) {
                        onDerived(derived[derivedOrdinal])
                        derivedOrdinal++
                    }
                    resultOrdinal++
                }
            }
        }
    }

    fun shutdown() {
        supervisor?.cancel()
        machineScope = null
        supervisor = null
    }

    private fun ensureMachineScope(): CoroutineScope {
        val current = machineScope
        if (current != null) return current
        val parent = scope.coroutineContext[Job]
        val nextSupervisor = SupervisorJob(parent)
        val nextScope = CoroutineScope(scope.coroutineContext + nextSupervisor)
        supervisor = nextSupervisor
        machineScope = nextScope
        return nextScope
    }

    private fun scanNonConditional(messages: Series<Nars3Message>): NonConditionalScan {
        val indices = IntArray(messages.size)
        val masks = LongArray(messages.size)
        var count = 0
        var index = 0
        while (index < messages.size) {
            val mask = messages[index].nonConditionalOperatorMask()
            if (mask != 0L) {
                indices[count] = index
                masks[count] = mask
                count++
            }
            index++
        }
        return NonConditionalScan(indices, masks, count)
    }

    private fun derivePairMessages(
        left: Nars3Message,
        right: Nars3Message,
        unionMask: Long,
    ): Series<Nars3Message> {
        val emitCount = nonConditionalEmissionCount(unionMask)
        if (emitCount == 0) return Join.emptySeriesOf()

        val derived = arrayOfNulls<Nars3Message>(emitCount)
        var ordinal = 0
        if ((unionMask and NarsiveOperator.INHERITANCE.mask) != 0L) {
            derived[ordinal++] = derivedMessage(left, right, NarsiveOperator.INHERITANCE)
        }
        if ((unionMask and NarsiveOperator.SIMILARITY.mask) != 0L) {
            derived[ordinal++] = derivedMessage(left, right, NarsiveOperator.SIMILARITY)
        }
        return emitCount j { derived[it]!! }
    }

    private fun derivedMessage(
        left: Nars3Message,
        right: Nars3Message,
        operator: NarsiveOperator,
    ): Nars3Message {
        val label = when (operator) {
            NarsiveOperator.INHERITANCE -> "inheritance"
            NarsiveOperator.SIMILARITY -> "similarity"
            else -> operator.name.lowercase()
        }
        val content = "$label: ${left.content} | ${right.content}"
        return Nars3Message(
            content = content,
            budget = Nars3Budget(
                priority = (minOf(left.budget.priority, right.budget.priority) * 0.95f).coerceAtLeast(0f),
                durability = (minOf(left.budget.durability, right.budget.durability) * 0.95f).coerceAtLeast(0f),
                quality = ((left.budget.quality + right.budget.quality) * 0.5f).coerceIn(0f, 1f),
            ),
            elements = 1 j { _: Int ->
                NarsiveElement(
                    kind = NarsiveElementKind.COPULA,
                    span = 0 j content.length,
                    lexeme = operator.asciiForm.toSeries(),
                )
            },
        )
    }

    private fun Nars3Message.nonConditionalOperatorMask(): Long =
        elements.operatorMask() and NON_CONDITIONAL_OPERATOR_MASK

    private fun nonConditionalEmissionCount(mask: Long): Int {
        var count = 0
        if ((mask and NarsiveOperator.INHERITANCE.mask) != 0L) count++
        if ((mask and NarsiveOperator.SIMILARITY.mask) != 0L) count++
        return count
    }

    private data class NonConditionalScan(
        val indices: IntArray,
        val masks: LongArray,
        val count: Int,
    )

    companion object {
        private val NON_CONDITIONAL_OPERATOR_MASK: Long =
            NarsiveOperator.INHERITANCE.mask or NarsiveOperator.SIMILARITY.mask
    }
}
