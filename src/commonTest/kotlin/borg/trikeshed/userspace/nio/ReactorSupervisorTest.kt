package borg.trikeshed.userspace.nio

import borg.trikeshed.userspace.network.HtxBlock
import borg.trikeshed.userspace.network.HtxBlockType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// ================================================================================
// SELF-CONTAINED STUBS for ReactorSupervisor algebra
// ================================================================================

interface BranchDispatch { val branchId: Int; val block: HtxBlock }
interface ParseSupervisor { val id: Int; fun process(dispatch: BranchDispatch): Unit }
data class BranchDispatchStub(override val branchId: Int, override val block: HtxBlock) : BranchDispatch

class ReactorSupervisorStub(override val id: Int = 0) : ReactorSupervisor {
    private val _branches = mutableMapOf<Int, BranchDispatch>()
    var isOpen = false
        private set

    override fun open() { isOpen = true }
    override fun close() { isOpen = false; _branches.clear() }
    override fun dispatch(dispatch: BranchDispatch) { _branches[dispatch.branchId] = dispatch }
    fun branchById(id: Int) = _branches[id]
    fun allBranchIds() = _branches.keys.toSet()
}

interface ReactorSupervisor {
    val id: Int
    fun open()
    fun close()
    fun dispatch(dispatch: BranchDispatch)
}

// ================================================================================
// TESTS
// ================================================================================

class ReactorSupervisorTest {

    @Test fun reactorSupervisor_stub_startsClosed() {
        val rs = ReactorSupervisorStub(1)
        assertFalse(rs.isOpen)
    }

    @Test fun reactorSupervisor_stub_openSetsIsOpen() {
        val rs = ReactorSupervisorStub(1)
        rs.open()
        assertTrue(rs.isOpen)
    }

    @Test fun reactorSupervisor_stub_closeClearsIsOpen() {
        val rs = ReactorSupervisorStub(1)
        rs.open()
        rs.close()
        assertFalse(rs.isOpen)
    }

    @Test fun reactorSupervisor_stub_id() {
        val rs = ReactorSupervisorStub(42)
        assertEquals(42, rs.id)
    }

    @Test fun branchDispatch_blockType() {
        val block = HtxBlock(HtxBlockType.DATA, byteArrayOf(1, 2, 3))
        val bd = BranchDispatchStub(1, block)
        assertEquals(1, bd.branchId)
        assertEquals(HtxBlockType.DATA, bd.block.blockType)
    }

    @Test fun branchDispatch_blockPayload() {
        val block = HtxBlock(HtxBlockType.MESSAGE, byteArrayOf(0xFF.toByte()))
        val bd = BranchDispatchStub(0, block)
        assertEquals(0xFF.toByte(), bd.block.payloadBytes[0])
    }

    @Test fun reactorSupervisor_stub_dispatch_stores() {
        val rs = ReactorSupervisorStub(1)
        rs.open()
        val block = HtxBlock(HtxBlockType.DATA, byteArrayOf())
        val bd = BranchDispatchStub(5, block)
        rs.dispatch(bd)
        assertSame(bd, rs.branchById(5))
    }

    @Test fun reactorSupervisor_stub_dispatch_multiple() {
        val rs = ReactorSupervisorStub(1)
        rs.open()
        val b1 = BranchDispatchStub(1, HtxBlock(HtxBlockType.DATA, byteArrayOf(1)))
        val b2 = BranchDispatchStub(2, HtxBlock(HtxBlockType.HEADERS, byteArrayOf()))
        rs.dispatch(b1)
        rs.dispatch(b2)
        assertEquals(setOf(1, 2), rs.allBranchIds())
    }

    @Test fun reactorSupervisor_stub_dispatch_clearsOnClose() {
        val rs = ReactorSupervisorStub(1)
        rs.open()
        rs.dispatch(BranchDispatchStub(1, HtxBlock(HtxBlockType.DATA, byteArrayOf())))
        rs.close()
        assertTrue(rs.allBranchIds().isEmpty())
    }

    @Test fun branchDispatchStub_equality() {
        val block = HtxBlock(HtxBlockType.DATA, byteArrayOf(1))
        val a = BranchDispatchStub(1, block)
        val b = BranchDispatchStub(1, block)
        assertEquals(a, b)
    }

    @Test fun branchDispatchStub_streamId() {
        val b0 = BranchDispatchStub(0, HtxBlock(HtxBlockType.DATA, byteArrayOf(), streamId = 0))
        val b1 = BranchDispatchStub(0, HtxBlock(HtxBlockType.DATA, byteArrayOf(), streamId = 1))
        assertTrue(b0.block != b1.block)
    }

    @Test fun htxBlockType_fromDispatch() {
        val block = HtxBlock(HtxBlockType.TRAILERS, byteArrayOf())
        val bd = BranchDispatchStub(0, block)
        assertEquals(HtxBlockType.TRAILERS, bd.block.blockType)
    }

    @Test fun parseSupervisor_interfaceExists() {
        val ps = object : ParseSupervisor {
            override val id: Int = 0
            override fun process(dispatch: BranchDispatch) {}
        }
        assertEquals(0, ps.id)
    }

    @Test fun reactorSupervisor_open_idempotent() {
        val rs = ReactorSupervisorStub(1)
        rs.open()
        rs.open()
        assertTrue(rs.isOpen)
    }

    @Test fun branchDispatchStub_branchIdDistinct() {
        val block = HtxBlock(HtxBlockType.DATA, byteArrayOf())
        val b1 = BranchDispatchStub(1, block)
        val b2 = BranchDispatchStub(2, block)
        assertTrue(b1 != b2)
        assertEquals(1, b1.branchId)
        assertEquals(2, b2.branchId)
    }
}
