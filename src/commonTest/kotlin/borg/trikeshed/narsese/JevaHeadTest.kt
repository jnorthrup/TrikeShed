package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import kotlin.io.encoding.Base64
import kotlin.test.*

class JevaHeadTest {
    /** torch.save of {decision_arch, context_proj: OrderedDict(weight 2x3), core: OrderedDict(coverage_bias scalar, blocks.0.ffn_norm.weight [3])}, torch 2.9.1. */
    private val archive = Base64.decode("UEsDBAAACAgAAAAAAAAAAAAAAAAAAAAAAAAQABIAYXJjaGl2ZS9kYXRhLnBrbEZCDgBaWlpaWlpaWlpaWlpaWoACfXEAKFgNAAAAZGVjaXNpb25fYXJjaHEBWAwAAABtYXRyaXhfc3RhY2txAlgMAAAAY29udGV4dF9wcm9qcQNjY29sbGVjdGlvbnMKT3JkZXJlZERpY3QKcQQpUnEFWAYAAAB3ZWlnaHRxBmN0b3JjaC5fdXRpbHMKX3JlYnVpbGRfdGVuc29yX3YyCnEHKChYBwAAAHN0b3JhZ2VxCGN0b3JjaApGbG9hdFN0b3JhZ2UKcQlYAQAAADBxClgDAAAAY3B1cQtLBnRxDFFLAEsCSwOGcQ1LA0sBhnEOiWgEKVJxD3RxEFJxEXNYBAAAAGNvcmVxEmgEKVJxEyhYDQAAAGNvdmVyYWdlX2JpYXNxFGgHKChoCGgJWAEAAAAxcRVoC0sBdHEWUUsAKSmJaAQpUnEXdHEYUnEZWBgAAABibG9ja3MuMC5mZm5fbm9ybS53ZWlnaHRxGmgHKChoCGgJWAEAAAAycRtoC0sDdHEcUUsASwOFcR1LAYVxHoloBClScR90cSBScSF1dS5QSwcIZ8xE840BAACNAQAAUEsDBAAACAgAAAAAAAAAAAAAAAAAAAAAAAAXAC4AYXJjaGl2ZS8uZm9ybWF0X3ZlcnNpb25GQioAWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaMVBLBwi379yDAQAAAAEAAABQSwMEAAAICAAAAAAAAAAAAAAAAAAAAAAAABoANwBhcmNoaXZlLy5zdG9yYWdlX2FsaWdubWVudEZCMwBaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlo2NFBLBwg/d3HpAgAAAAIAAABQSwMEAAAICAAAAAAAAAAAAAAAAAAAAAAAABEAPwBhcmNoaXZlL2J5dGVvcmRlckZCOwBaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWmxpdHRsZVBLBwiFPeMZBgAAAAYAAABQSwMEAAAICAAAAAAAAAAAAAAAAAAAAAAAAA4APgBhcmNoaXZlL2RhdGEvMEZCOgBaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaAACAPwAAAEAAAEBAAACAQAAAoEAAAMBAUEsHCCTlORkYAAAAGAAAAFBLAwQAAAgIAAAAAAAAAAAAAAAAAAAAAAAADgAsAGFyY2hpdmUvZGF0YS8xRkIoAFpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWloAAAA/UEsHCCHyIpcEAAAABAAAAFBLAwQAAAgIAAAAAAAAAAAAAAAAAAAAAAAADgBAAGFyY2hpdmUvZGF0YS8yRkI8AFpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWgAAgD4AAMC/AAAAQVBLBwhZJIf5DAAAAAwAAABQSwMEAAAICAAAAAAAAAAAAAAAAAAAAAAAAA8ANwBhcmNoaXZlL3ZlcnNpb25GQjMAWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaMwpQSwcI0Z5nVQIAAAACAAAAUEsDBAAACAgAAAAAAAAAAAAAAAAAAAAAAAAeADIAYXJjaGl2ZS8uZGF0YS9zZXJpYWxpemF0aW9uX2lkRkIuAFpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlowODA2NDAxMzIwOTM0MjAxMTE3NzA4NTQzNTE4MzcyMDcwOTc5ODQ4UEsHCFZ3EhIoAAAAKAAAAFBLAQIAAAAACAgAAAAAAABnzETzjQEAAI0BAAAQAAAAAAAAAAAAAAAAAAAAAABhcmNoaXZlL2RhdGEucGtsUEsBAgAAAAAICAAAAAAAALfv3IMBAAAAAQAAABcAAAAAAAAAAAAAAAAA3QEAAGFyY2hpdmUvLmZvcm1hdF92ZXJzaW9uUEsBAgAAAAAICAAAAAAAAD93cekCAAAAAgAAABoAAAAAAAAAAAAAAAAAUQIAAGFyY2hpdmUvLnN0b3JhZ2VfYWxpZ25tZW50UEsBAgAAAAAICAAAAAAAAIU94xkGAAAABgAAABEAAAAAAAAAAAAAAAAA0gIAAGFyY2hpdmUvYnl0ZW9yZGVyUEsBAgAAAAAICAAAAAAAACTlORkYAAAAGAAAAA4AAAAAAAAAAAAAAAAAVgMAAGFyY2hpdmUvZGF0YS8wUEsBAgAAAAAICAAAAAAAACHyIpcEAAAABAAAAA4AAAAAAAAAAAAAAAAA6AMAAGFyY2hpdmUvZGF0YS8xUEsBAgAAAAAICAAAAAAAAFkkh/kMAAAADAAAAA4AAAAAAAAAAAAAAAAAVAQAAGFyY2hpdmUvZGF0YS8yUEsBAgAAAAAICAAAAAAAANGeZ1UCAAAAAgAAAA8AAAAAAAAAAAAAAAAA3AQAAGFyY2hpdmUvdmVyc2lvblBLAQIAAAAACAgAAAAAAABWdxISKAAAACgAAAAeAAAAAAAAAAAAAAAAAFIFAABhcmNoaXZlLy5kYXRhL3NlcmlhbGl6YXRpb25faWRQSwYGLAAAAAAAAAAeAy0AAAAAAAAAAAAJAAAAAAAAAAkAAAAAAAAARwIAAAAAAAD4BQAAAAAAAFBLBgcAAAAAPwgAAAAAAAABAAAAUEsFBgAAAAAJAAkARwIAAPgFAAAAAA==")

    @Test fun readsTheTensorsOfATorchStateDictByFlattenedName() {
        val tensors = JevaHead.parse(archive)
        assertEquals(setOf("context_proj.weight", "core.coverage_bias", "core.blocks.0.ffn_norm.weight"), tensors.keys)
        val weight = tensors.getValue("context_proj.weight")
        assertEquals(2, weight.a[0]); assertEquals(3, weight.a[1])
        assertEquals(1f, weight.b(shapeOf(0, 0))); assertEquals(6f, weight.b(shapeOf(1, 2))); assertEquals(4f, weight.b(shapeOf(1, 0)))
        val scalar = tensors.getValue("core.coverage_bias")
        assertEquals(0, scalar.a.size); assertEquals(0.5f, scalar.b(shapeOf()))
        val vector = tensors.getValue("core.blocks.0.ffn_norm.weight")
        assertEquals(listOf(0.25f, -1.5f, 8f), (0 until 3).map { vector.b(shapeOf(it)) })
    }

    @Test fun theHeadTensorsAreAJevaTensorsOwnerWithNothingMapped() {
        val head = JevaHeadTensors(JevaHead.parse(archive))
        assertEquals(0L, head.mappedBytes)
        assertEquals(8f, head["core.blocks.0.ffn_norm.weight"].b(shapeOf(2)))
        assertFailsWith<IllegalArgumentException> { head["core.absent"] }
        head.close()
    }

    @Test fun anArchiveThatIsNotAWholeStoredZipIsRefused() {
        assertFailsWith<IllegalArgumentException> { JevaHead.parse(archive.copyOf(100)) }
        assertFailsWith<IllegalArgumentException> { JevaHead.parse(ByteArray(64)) }
    }
}
