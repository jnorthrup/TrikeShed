package borg.trikeshed.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostDescriptorJvmTest {
    @Test
    fun runtimeProperties() {
        val host = loadPlatformHost()
        val descriptor = host.descriptor
        assertEquals(HostRuntime.JVM, descriptor.runtime)
        assertEquals(System.getProperty("os.name"), descriptor.rawOs)
        assertEquals(System.getProperty("os.arch"), descriptor.rawArchitecture)
        assertEquals(System.getProperty("os.version"), descriptor.osVersion)
        assertEquals(System.getProperty("java.version"), descriptor.runtimeVersion)
        assertEquals(System.getProperty("java.vm.name"), descriptor.runtimeName)
        assertEquals(System.getProperty("java.vendor"), descriptor.runtimeVendor)
        assertEquals(System.getProperty("java.vm.version"), descriptor.vmVersion)
        assertEquals(System.getProperty("java.specification.version"), descriptor.jvmSpecificationVersion)
        assertNull(descriptor.cpuModel)
        assertNull(descriptor.cpuVendor)
        assertNull(descriptor.nativeAbi)
        assertTrue(host.processors > 0)
        println("JVM host: $descriptor; os=${descriptor.os}; architecture=${descriptor.architecture}; processors=${host.processors}")
    }

    @Test
    fun linuxPropertyFixture() {
        val descriptor = jvmHostDescriptor { name ->
            when (name) {
                "os.name" -> "Linux"
                "os.arch" -> "amd64"
                "java.vendor" -> "Runtime distributor"
                "java.specification.version" -> "25"
                else -> null
            }
        }
        assertEquals("Linux", descriptor.rawOs)
        assertEquals("linux", descriptor.os)
        assertEquals("amd64", descriptor.rawArchitecture)
        assertEquals("x86_64", descriptor.architecture)
        assertEquals("Runtime distributor", descriptor.runtimeVendor)
        assertEquals("25", descriptor.jvmSpecificationVersion)
        assertNull(descriptor.nativeAbi)
        assertNull(descriptor.cpuVendor)
        assertNull(descriptor.cpuModel)
    }

    @Test
    fun restrictedPropertyFixture() {
        val descriptor = jvmHostDescriptor { name ->
            when (name) {
                "os.name" -> "Mac OS X"
                "os.arch", "java.vendor" -> throw SecurityException("property access denied")
                else -> null
            }
        }
        assertEquals(HostRuntime.JVM, descriptor.runtime)
        assertEquals("macos", descriptor.os)
        assertNull(descriptor.rawArchitecture)
        assertNull(descriptor.architecture)
        assertNull(descriptor.runtimeVendor)
        assertNull(descriptor.cpuVendor)
        assertNull(descriptor.cpuModel)
    }

    @Test
    fun absentPropertyFixture() {
        val descriptor = jvmHostDescriptor { null }
        assertEquals(HostRuntime.JVM, descriptor.runtime)
        assertNull(descriptor.os)
        assertNull(descriptor.architecture)
        assertNull(descriptor.runtimeVersion)
        assertNull(descriptor.jvmSpecificationVersion)
        assertNull(descriptor.cpuModel)
        assertNull(descriptor.cpuVendor)
    }
}
