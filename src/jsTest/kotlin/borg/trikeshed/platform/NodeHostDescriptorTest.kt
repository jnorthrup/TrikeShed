package borg.trikeshed.platform

import borg.trikeshed.lib.os
import borg.trikeshed.lib.processObj
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NodeHostDescriptorTest {
    @Test
    fun nodeRuntimeObservation() {
        val process = processObj
        if (!jsNodeProcess(process)) return // Also compiled for browser tests.
        val host = loadPlatformHost()
        val descriptor = host.descriptor
        assertEquals(HostRuntime.NODE_JS, descriptor.runtime)
        assertEquals(process.platform as String, descriptor.rawOs)
        assertEquals(process.arch as String, descriptor.rawArchitecture)
        assertEquals(process.versions.node as String, descriptor.runtimeVersion)
        assertEquals(process.versions.modules as String, descriptor.nativeAbi)
        assertEquals((process.versions.napi as String).toInt(), descriptor.napiVersion)
        assertNull(descriptor.cpuVendor)
        val nodeOs = os
        if (nodeOs != null) {
            assertEquals(nodeOs.release() as String, descriptor.osVersion)
            val cpus = nodeOs.cpus()
            if ((cpus.length as Int) > 0) assertTrue(descriptor.cpuModel?.contains(cpus[0].model as String) == true)
        }
        assertTrue(host.processors > 0)
        println("Node host: $descriptor; processors=${host.processors}")
    }

    @Test
    fun browserPolyfillDoesNotSupplyHost() {
        val process = js("({platform:'linux',arch:'x64',versions:{}})")
        val descriptor = jsHostDescriptor(process, null, browser = true)
        assertEquals(HostRuntime.BROWSER_JS, descriptor.runtime)
        assertNull(descriptor.os)
        assertNull(descriptor.architecture)
        assertNull(descriptor.nativeAbi)
    }

    @Test
    fun restrictedNodeRetainsRuntimeWithoutCpuMetadata() {
        val process = js("({platform:'linux',arch:'aarch64',versions:{node:'22.0.0',modules:'127',napi:'9'},release:{name:'node'}})")
        val descriptor = jsHostDescriptor(process, null)
        assertEquals(HostRuntime.NODE_JS, descriptor.runtime)
        assertEquals("linux", descriptor.os)
        assertEquals("arm64", descriptor.architecture)
        assertEquals("aarch64", descriptor.rawArchitecture)
        assertEquals("127", descriptor.nativeAbi)
        assertEquals(9, descriptor.napiVersion)
        assertNull(descriptor.cpuModel)
        assertNull(descriptor.cpuVendor)
        assertNull(descriptor.libc)
    }

    @Test
    fun hostedVmDoesNotUseProcessPolyfill() {
        val process = js("({platform:'linux',arch:'amd64'})")
        val descriptor = jsHostDescriptor(process, null, hosted = true)
        assertEquals(HostRuntime.HOSTED_JS, descriptor.runtime)
        assertNull(descriptor.os)
        assertNull(descriptor.architecture)
    }

    @Test
    fun optionalMetadataFailuresRemainUnknown() {
        val process = js("({platform:'linux',arch:'x64',versions:{node:'22.0.0'},report:{getReport:function(){throw new Error('denied')}}})")
        val os = js("({cpus:function(){throw new Error('denied')},release:function(){throw new Error('denied')}})")
        val descriptor = jsHostDescriptor(process, os)
        assertEquals(HostRuntime.NODE_JS, descriptor.runtime)
        assertEquals("x86_64", descriptor.architecture)
        assertNull(descriptor.cpuModel)
        assertNull(descriptor.osVersion)
        assertNull(descriptor.libc)
        assertEquals(1, jsHostProcessors(os, null))
    }

    @Test
    fun glibcRequiresPositiveRuntimeObservation() {
        val process = js("({platform:'linux',arch:'x64',versions:{node:'22.0.0'},report:{getReport:function(){return {header:{glibcVersionRuntime:'2.39'}}}}})")
        assertEquals("glibc", jsHostDescriptor(process, null).libc)
        process.report.getReport = js("function(){return {header:{}}}")
        assertNull(jsHostDescriptor(process, null).libc)
    }

    @Test
    fun conflictingReleaseIsNotNode() {
        val process = js("({platform:'linux',arch:'x64',versions:{node:'22.0.0'},release:{name:'other-vm'}})")
        val descriptor = jsHostDescriptor(process, null)
        assertEquals(HostRuntime.JS, descriptor.runtime)
        assertNull(descriptor.os)
        assertNull(descriptor.nativeAbi)
    }

    @Test
    fun unknownJsHasNoHostMetadata() {
        val descriptor = jsHostDescriptor(null, null)
        assertEquals(HostRuntime.JS, descriptor.runtime)
        assertNull(descriptor.os)
        assertNull(descriptor.architecture)
        assertEquals(6, jsHostProcessors(null, js("({hardwareConcurrency:6})")))
    }

    @Test
    fun cpuModelsDoNotFabricateVendor() {
        val process = js("({platform:'linux',arch:'arm64',versions:{node:'22.0.0'}})")
        val os = js("({cpus:function(){return [{model:'CPU A'},{model:'CPU A'},{model:'CPU B'}]}})")
        val descriptor = jsHostDescriptor(process, os)
        assertEquals("CPU A; CPU B", descriptor.cpuModel)
        assertNull(descriptor.cpuVendor)
    }
}
