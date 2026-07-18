package borg.trikeshed.dag

import borg.trikeshed.job.ContentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReteAgendaTest {

    private fun activation(
        id: String,
        salience: Int,
        sequence: Long,
        support: String = id,
    ): Activation = Activation(
        activationId = id,
        ruleId = "rule-$id",
        ruleVersionCid = ContentId.of("rule-$id".encodeToByteArray()),
        salience = salience,
        sequence = sequence,
        supportCids = listOf(ContentId.of(support.encodeToByteArray())),
        bindings = mapOf("id" to id),
    )

    @Test
    fun popsBySalienceThenCommittedSequenceThenActivationId() {
        val agenda = ReteAgenda()
        agenda.add(activation("low", salience = 10, sequence = 1))
        agenda.add(activation("newer", salience = 100, sequence = 2))
        agenda.add(activation("older-z", salience = 100, sequence = 1))
        agenda.add(activation("older-a", salience = 100, sequence = 1))

        assertEquals(
            listOf("older-a", "older-z", "newer", "low"),
            generateSequence { agenda.popNext() }.map { it.activationId }.toList(),
        )
    }

    @Test
    fun duplicateActivationIdIsIdempotent() {
        val agenda = ReteAgenda()
        val activation = activation("a-1", salience = 50, sequence = 4)

        assertEquals(true, agenda.add(activation))
        assertEquals(false, agenda.add(activation))
        assertEquals(1, agenda.size)
        assertEquals("a-1", agenda.popNext()?.activationId)
        assertNull(agenda.popNext())
    }

    @Test
    fun removingSupportInvalidatesEveryDependentActivation() {
        val agenda = ReteAgenda()
        val support = ContentId.of("shared-support".encodeToByteArray())
        agenda.add(activation("a-1", 100, 1, "shared-support"))
        agenda.add(activation("a-2", 90, 2, "shared-support"))
        agenda.add(activation("unrelated", 80, 3, "other-support"))

        assertEquals(2, agenda.removeBySupport(support))
        assertEquals(listOf("unrelated"),
            generateSequence { agenda.popNext() }.map { it.activationId }.toList())
    }
}
