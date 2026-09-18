package borg.trikeshed.ebpf

import kotlin.test.Test
import kotlin.test.assertEquals

class EbpfMapHelperTest {

    @Test
    fun testMapUpdateAndLookup() {
        val registry = EbpfHelperRegistry()
        val map = EbpfHashMap()

        // Mocking pointers: Map pointer = 1, Key pointer = 0 in context, Value pointer = 4 in context

        registry.registerHelper(registry.BPF_FUNC_map_update_elem) { regs, context ->
            val mapId = regs[1]
            if (mapId == 1L) {
                val keyOffset = regs[2].toInt()
                val valOffset = regs[3].toInt()
                val key = context.copyOfRange(keyOffset, keyOffset + 4)
                val value = context.copyOfRange(valOffset, valOffset + 4)
                map.update(key, value)
                0L
            } else -1L
        }

        registry.registerHelper(registry.BPF_FUNC_map_lookup_elem) { regs, context ->
            val mapId = regs[1]
            if (mapId == 1L) {
                val keyOffset = regs[2].toInt()
                val key = context.copyOfRange(keyOffset, keyOffset + 4)
                val value = map.lookup(key)
                if (value != null) {
                    // For mock simplicity, we place value back at value ptr in context and return ptr
                    value.copyInto(context, 8)
                    8L
                } else 0L
            } else 0L
        }

        // Context layout:
        // 0-3: key (0x12345678)
        // 4-7: value (0xAABBCCDD)
        // 8-11: lookup output
        val context = ByteArray(12)
        context[0] = 0x78; context[1] = 0x56; context[2] = 0x34; context[3] = 0x12
        context[4] = 0xDD.toByte(); context[5] = 0xCC.toByte(); context[6] = 0xBB.toByte(); context[7] = 0xAA.toByte()

        // --- UPDATE ---
        // R1 = 1 (map ptr)
        val i1 = EbpfInstruction.pack(EbpfOpcode.BPF_ALU64 or EbpfOpcode.BPF_MOV or EbpfOpcode.BPF_K, 1, 0, 0, 1)
        // R2 = 0 (key offset)
        val i2 = EbpfInstruction.pack(EbpfOpcode.BPF_ALU64 or EbpfOpcode.BPF_MOV or EbpfOpcode.BPF_K, 2, 0, 0, 0)
        // R3 = 4 (value offset)
        val i3 = EbpfInstruction.pack(EbpfOpcode.BPF_ALU64 or EbpfOpcode.BPF_MOV or EbpfOpcode.BPF_K, 3, 0, 0, 4)
        // CALL BPF_FUNC_map_update_elem
        val i4 = EbpfInstruction.pack(EbpfOpcode.BPF_JMP or EbpfOpcode.BPF_CALL, 0, 0, 0, registry.BPF_FUNC_map_update_elem)

        // --- LOOKUP ---
        // R1 = 1 (map ptr)
        val i5 = EbpfInstruction.pack(EbpfOpcode.BPF_ALU64 or EbpfOpcode.BPF_MOV or EbpfOpcode.BPF_K, 1, 0, 0, 1)
        // R2 = 0 (key offset)
        val i6 = EbpfInstruction.pack(EbpfOpcode.BPF_ALU64 or EbpfOpcode.BPF_MOV or EbpfOpcode.BPF_K, 2, 0, 0, 0)
        // CALL BPF_FUNC_map_lookup_elem
        val i7 = EbpfInstruction.pack(EbpfOpcode.BPF_JMP or EbpfOpcode.BPF_CALL, 0, 0, 0, registry.BPF_FUNC_map_lookup_elem)
        // EXIT
        val i8 = EbpfInstruction.pack(EbpfOpcode.BPF_JMP or EbpfOpcode.BPF_EXIT, 0, 0, 0, 0)

        val program = EbpfProgram(longArrayOf(i1.raw, i2.raw, i3.raw, i4.raw, i5.raw, i6.raw, i7.raw, i8.raw))
        val interpreter = EbpfInterpreter(program, registry)

        val result = interpreter.execute(context)
        assertEquals(8L, result, "Lookup should return pointer offset 8")
        assertEquals(0xDD.toByte(), context[8])
        assertEquals(0xCC.toByte(), context[9])
        assertEquals(0xBB.toByte(), context[10])
        assertEquals(0xAA.toByte(), context[11])
    }
}
