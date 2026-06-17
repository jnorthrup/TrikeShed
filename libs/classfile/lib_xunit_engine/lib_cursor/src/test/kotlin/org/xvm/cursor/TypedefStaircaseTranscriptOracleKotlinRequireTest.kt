package org.xvm.cursor

import org.junit.jupiter.api.Test
import org.xvm.asm.constants.TypedefResolutionPublisher.TypedefCallsite
import org.xvm.runtime.TypedefStaircaseTranscriptOracle
import org.xvm.runtime.XvmPrimitiveTranslationTable

class TypedefStaircaseTranscriptOracleKotlinRequireTest {
    @Test
    fun `redux transcript verification accepts non zero typedef params`() {
        val oracle = TypedefStaircaseTranscriptOracle()

        oracle.record(
            TypedefStaircaseTranscriptOracle.Branch.B,
            0x66,
            TypedefCallsite.PTC_Param,
            "dsl.step1().step2().step3()",
            3,
            XvmPrimitiveTranslationTable.XvmPrimitive.Dec64,
        )

        val state = oracle.state()
        require(state.allowB() == 1) { "branch B must accept parameterized typedef" }
        require(state.blockB() == 0) { "branch B must not block parameterized typedef" }
        require(oracle.branchAllowed(TypedefStaircaseTranscriptOracle.Branch.B)) { "branch B should remain allowed" }
        require(oracle.snapshot().single().reason().contains("parameterized")) { "reason must explain the verified path" }
        require(oracle.verifierReport().failures() == 0) { "pointcut verifier must pass" }
    }

    @Test
    fun `targeted debugging demonstration of pointcuts side by side with typedef production table`() {
        // Demonstrate that the runtime ClassFileTaxonomy coordinates line up with the Redux journal
        val tax = ClassFileTaxonomy()
        val poolId = StringPool.intern("DemonstrationPool")
        tax.register(ClassFileTaxonomy.CoordinateRow(
            symbolName = "pkg.Demo.run",
            ownerType = "pkg.Demo",
            methodOrField = "run",
            classfileCoord = "pkg.Demo#run",
            cpIndex = 1,
            descriptor = "()V",
            xvmTypeInfo = "",
            pointcutKind = 0x10,
            poolId = poolId
        ))
        
        // Write to the Redux WAL mimicking the VM
        TypedefResolutionSeries.record(
            poolId,
            TypedefCallsite.PTC_Param.ordinal,
            "pkg.Demo",
            "format",
            true
        )
        
        println("=== Targeted Debugging Demonstration ===")
        println("Taxonomy Size: \${tax.size}")
        println("TypedefResolutionSeries Size: \${TypedefResolutionSeries.size()}")
        println("StringPool Size: \${StringPool.size()}")
        println("RowVec projection: \${TypedefResolutionSeries.toRowVec()}")
        println("========================================")
        
        require(TypedefResolutionSeries.size() > 0) { "Redux series should have events" }
        require(tax.lookupByPoolId(poolId) != null) { "Taxonomy must map the poolId" }
        
        // Clean up
        StringPool.clear()
        TypedefResolutionSeries.drain() // flush anything out
        // Note: the TypedefResolutionSeries Redux state is static, but tests can clear the pool
    }
}
