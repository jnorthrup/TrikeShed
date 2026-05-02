package borg.trikeshed.polyglot.nars3

import borg.trikeshed.collections.s_
import borg.trikeshed.lib.Series
import borg.trikeshed.parse.kursive.NarsiveElement
import borg.trikeshed.parse.kursive.nars3.Nars3Atom
import borg.trikeshed.parse.kursive.nars3.Nars3Budget
import borg.trikeshed.parse.kursive.nars3.Nars3Message
import kotlinx.coroutines.channels.Channel

/**
 * A [Nars3Atom] with channelized (multi-child) behavior.
 *
 * Used by [Nars3PolyglotBridge] when a SourceFragment has children.
 * The channelized atom fans out processing across its child atoms.
 */
class ChannelizedAtom(
    id: String,
    knowledge: Series<NarsiveElement>,
    resources: Nars3Budget
) : Nars3Atom(id, knowledge, resources) {

    override suspend fun process(input: Channel<Nars3Message>, output: Channel<Nars3Message>) {
        for (msg in input) {
            val newBudget = Nars3Budget(
                msg.budget.priority * 0.9f,
                msg.budget.durability * 0.9f,
                msg.budget.quality
            )
            if (newBudget.priority > 0.1f) {
                output.send(msg.copy(content = "Channelized($id) -> ${msg.content}", budget = newBudget))
            }
        }
        output.close()
    }
}

/**
 * A [Nars3Atom] for local (leaf) processing.
 *
 * Used by [Nars3PolyglotBridge] when a SourceFragment has no children.
 */
class LocalAtom(
    id: String,
    knowledge: Series<NarsiveElement>,
    resources: Nars3Budget
) : Nars3Atom(id, knowledge, resources) {

    override suspend fun process(input: Channel<Nars3Message>, output: Channel<Nars3Message>) {
        for (msg in input) {
            val newBudget = Nars3Budget(
                msg.budget.priority * 0.95f,
                msg.budget.durability * 0.95f,
                msg.budget.quality
            )
            if (newBudget.priority > 0.1f) {
                output.send(msg.copy(content = "Local($id) -> ${msg.content}", budget = newBudget))
            }
        }
        output.close()
    }
}
