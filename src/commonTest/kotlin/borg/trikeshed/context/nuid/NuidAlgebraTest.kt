package borg.trikeshed.context.nuid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NuidAlgebraTest {

    @Test
    fun testSubnetContainment() {
        // Local < Lan < Mesh < Global
        assertTrue(Subnet.Global.contains(Subnet.Global))
        assertTrue(Subnet.Global.contains(Subnet.Mesh))
        assertTrue(Subnet.Global.contains(Subnet.Lan))
        assertTrue(Subnet.Global.contains(Subnet.Local))

        assertFalse(Subnet.Mesh.contains(Subnet.Global))
        assertTrue(Subnet.Mesh.contains(Subnet.Mesh))
        assertTrue(Subnet.Mesh.contains(Subnet.Lan))
        assertTrue(Subnet.Mesh.contains(Subnet.Local))

        assertFalse(Subnet.Lan.contains(Subnet.Global))
        assertFalse(Subnet.Lan.contains(Subnet.Mesh))
        assertTrue(Subnet.Lan.contains(Subnet.Lan))
        assertTrue(Subnet.Lan.contains(Subnet.Local))

        assertFalse(Subnet.Local.contains(Subnet.Global))
        assertFalse(Subnet.Local.contains(Subnet.Mesh))
        assertFalse(Subnet.Local.contains(Subnet.Lan))
        assertTrue(Subnet.Local.contains(Subnet.Local))
    }

    @Test
    fun testTraitSpace() {
        val allAllowed = object : TraitSpace {
            override fun can(capability: Capability) = true
        }
        assertTrue(allAllowed.can(Capability.Process))
        assertTrue(allAllowed.can(Capability.Cas))

        val casOnly = object : TraitSpace {
            override fun can(capability: Capability) = capability is Capability.Cas
        }
        assertTrue(casOnly.can(Capability.Cas))
        assertFalse(casOnly.can(Capability.Process))
        assertFalse(casOnly.can(Capability.Wireproto))
    }

    @Test
    fun testNuidStructure() {
        val cap: Capability = Capability.Mesh
        val nonce: Nonce = Nonce.RandomNonce("abc")
        val subnet: Subnet = Subnet.Lan
        val nuid = Nuid(cap, nonce, subnet)

        assertEquals(cap, nuid.a)
        assertEquals(nonce, nuid.b.a)
        assertEquals(subnet, nuid.b.b)
    }

    @Test
    fun testWorkgroup() {
        val subnet = Subnet.Global
        val traits = object : TraitSpace {
            override fun can(capability: Capability) = true
        }
        val workgroup = Workgroup("TestGroup", subnet, traits)

        assertEquals("TestGroup", workgroup.name)
        assertEquals(subnet, workgroup.scope)
        assertEquals(traits, workgroup.traits)
    }
}
