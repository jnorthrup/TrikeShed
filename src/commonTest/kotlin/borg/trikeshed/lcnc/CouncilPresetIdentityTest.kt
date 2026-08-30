package borg.trikeshed.lcnc

import borg.trikeshed.parse.json.JsonSupport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The can/atoms identity regression gate (design brief: "the can and the
 * atoms are the same substance at different zoom levels"): `preset-council`
 * ships CouncilProgram.build(DEFAULT_3x5) VERBATIM, and the pure
 * `council.convene` node re-emits the same bytes for an empty config. Any
 * drift between the three authorships — preset map, builder, convene runner
 * — fails here first.
 */
class CouncilPresetIdentityTest {

    @Test
    fun presetCouncilIsTheBuilderByteForByte() {
        assertEquals(
            LcncProgramConfix.toJson(CouncilProgram.build(CouncilConfig.DEFAULT_3x5)),
            LcncPresets.all().getValue("preset-council"),
            "preset-council must be build(DEFAULT_3x5) verbatim — one geometry author",
        )
    }

    @Test
    fun conveneWithEmptyConfigReStringifiesToThePresetBytes() = runTest {
        val out = CouncilNodes.registry(CouncilDialog { SeatOutcome.Ok("unused", "unused") })
            .getValue("council.convene")
            .run(LcncNode("cv", "council.convene"), emptyMap())
        assertEquals(
            LcncPresets.all().getValue("preset-council"),
            JsonSupport.stringify(out["program"]),
            "council.convene(<empty>) must emit the preset's exact document",
        )
    }
}
