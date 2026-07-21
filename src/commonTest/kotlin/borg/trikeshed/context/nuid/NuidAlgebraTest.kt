package borg.trikeshed.context.nuid

import borg.trikeshed.lib.j
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NuidAlgebraTest {

    @Test
    fun testSubnetContainment() {
        val global = Subnet.parse("global")
        val mesh = Subnet.parse("global.mesh")
        val lan = Subnet.parse("global.mesh.lan")
        val local = Subnet.parse("global.mesh.lan.local")

        assertTrue(global contains global, "global contains global")
        assertTrue(global contains mesh, "global contains mesh")
        assertTrue(global contains lan, "global contains lan")
        assertTrue(global contains local, "global contains local")

        assertFalse(mesh contains global, "mesh contains global")
        assertTrue(mesh contains mesh, "mesh contains mesh")
        assertTrue(mesh contains lan, "mesh contains lan")
        assertTrue(mesh contains local, "mesh contains local")

        assertFalse(lan contains global, "lan contains global")
        assertFalse(lan contains mesh, "lan contains mesh")
        assertTrue(lan contains lan, "lan contains lan")
        assertTrue(lan contains local, "lan contains local")

        assertFalse(local contains global, "local contains global")
        assertFalse(local contains mesh, "local contains mesh")
        assertFalse(local contains lan, "local contains lan")
        assertTrue(local contains local, "local contains local")
        
        val unrelated = Subnet.parse("other.mesh")
        assertFalse(global contains unrelated, "global contains unrelated")
    }

    @Test
    fun testTraitSpace() {
        val ts = TraitSpace { 2 j { i -> if (i == 0) Capability.Process("spawn") else Capability.Cas("read") } }
        assertTrue(ts.can(Capability.Process("spawn")), "can spawn")
        assertFalse(ts.can(Capability.Process("kill")), "cannot kill")
        assertTrue(ts.can(Capability.Cas("read")), "can read")
        assertFalse(ts.can(Capability.Wireproto("route")), "cannot route")

        val tsAll = TraitSpace { 1 j { Capability.ProcessAll } }
        assertTrue(tsAll.can(Capability.Process("anything")), "all can anything")
        assertTrue(tsAll.can(Capability.Process("spawn")), "all can spawn")
        assertFalse(tsAll.can(Capability.Cas("read")), "all cannot cas")
    }

    @Test
    fun testNuidStructure() {
        val cap = Capability.Process("test")
        val nonce = Nonce.RandomBytes()
        val sub = Subnet.core
        
        val n = nuid(cap, nonce, sub)
        assertEquals(cap, n.capability)
        assertEquals(nonce, n.nonce)
        assertEquals(sub, n.subnet)
    }

    @Test
    fun testWorkgroup() {
        val wg = Workgroup(
            name = "test-wg",
            scope = Subnet.parse("local"),
            traits = TraitSpace { 1 j { Capability.Process("spawn") } }
        )
        
        val validNuid = nuid(Capability.Process("spawn"), Nonce.RandomBytes(), Subnet.parse("local.process"))
        val invalidCap = nuid(Capability.Cas("read"), Nonce.RandomBytes(), Subnet.parse("local.process"))
        val invalidSubnet = nuid(Capability.Process("spawn"), Nonce.RandomBytes(), Subnet.parse("global"))
        
        assertTrue(wg.canHandle(validNuid), "valid nuid")
        assertFalse(wg.canHandle(invalidCap), "invalid cap")
        assertFalse(wg.canHandle(invalidSubnet), "invalid subnet")
    }
}
