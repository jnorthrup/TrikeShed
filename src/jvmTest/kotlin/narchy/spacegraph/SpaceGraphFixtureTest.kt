package narchy.spacegraph

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.size
import borg.trikeshed.parse.json.JsonSupport
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class SpaceGraphFixtureTest {
    @Test
    fun exportGeneratedShake() {
        val program = LcncShakeDemo.build().program
        assertEquals(0, program.wires.size)
        val publisher = LcncPublisher(ConfixBlackboard(), { emptyMap() }, null)
        val vocabulary = publisher.vocabularyPayload()
        val entry = LcncBlackboard.programEntry(program.name, program, LcncContracts.all().associateBy { it.type })
        val fixture = mapOf("entry" to entry, "vocabulary" to vocabulary, "concentric" to ConcentricSurface.render(),
            "shake" to LcncMating.treeshake(program).toMap())
        val output = File("build/reports/spacegraph/generated-shake.json")
        output.parentFile.mkdirs()
        output.writeText(JsonSupport.stringify(fixture))
    }
}
