package org.xvm.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.xvm.asm.constants.TypedefResolutionPublisher.TypedefCallsite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TypedefStaircaseTranscriptOracleTest {

    @Test
    public void zeroParamAllow_recordsTypePointcutAndInlineLayoutForBranchB() {
        var oracle = new TypedefStaircaseTranscriptOracle();

        var row = oracle.record(
                TypedefStaircaseTranscriptOracle.Branch.B,
                0x66,
                TypedefCallsite.PTC_Param,
                "DslStep3",
                0,
                XvmPrimitiveTranslationTable.XvmPrimitive.Dec64);

        assertEquals(TypedefStaircaseTranscriptOracle.Vote.ALLOW, row.vote());
        assertEquals(VmPointcutDispatch.Kind.TYPE, row.pointcutKind());
        assertEquals(XvmPrimitiveTranslationTable.VtableLayout.INLINE, row.layout());
        assertEquals(1, oracle.state().allowB());
        assertEquals(0, oracle.state().blockB());
        assertTrue(oracle.branchAllowed(TypedefStaircaseTranscriptOracle.Branch.B));
        assertEquals(1, oracle.snapshot().length);
    }

    @Test
    public void nonZeroParams_recordParameterizedProductionAndKeepBranchAllowed() {
        var oracle = new TypedefStaircaseTranscriptOracle();

        var row = oracle.record(
                TypedefStaircaseTranscriptOracle.Branch.A,
                0x66,
                TypedefCallsite.PTC_Param,
                "DslStep2",
                2,
                XvmPrimitiveTranslationTable.XvmPrimitive.String);

        assertEquals(TypedefStaircaseTranscriptOracle.Vote.ALLOW, row.vote());
        assertEquals(VmPointcutDispatch.Kind.TYPE, row.pointcutKind());
        assertEquals(TypedefProductionTable.Mode.PARAMETERIZED, row.mode());
        assertEquals(XvmPrimitiveTranslationTable.VtableLayout.INTERFACE, row.layout());
        assertEquals(1, oracle.state().allowA());
        assertEquals(0, oracle.state().blockA());
        assertTrue(oracle.branchAllowed(TypedefStaircaseTranscriptOracle.Branch.A));
        assertTrue(row.identityHash() != 0L);
        assertTrue(row.reason().contains("parameterized"), row.reason());
    }

    @Test
    public void concurrentRecord_preservesTranscriptAndCountsAcrossBranches() throws Exception {
        var oracle = new TypedefStaircaseTranscriptOracle();
        int workers = 4;
        int perWorker = 25;
        int total = workers * perWorker;
        var pool = Executors.newFixedThreadPool(workers);
        var ready = new CountDownLatch(workers);
        var start = new CountDownLatch(1);
        try {
            for (int w = 0; w < workers; w++) {
                final int worker = w;
                pool.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    for (int i = 0; i < perWorker; i++) {
                        int paramCount = ((worker + i) & 1) == 0 ? 0 : 1;
                        oracle.record(
                                (i & 1) == 0 ? TypedefStaircaseTranscriptOracle.Branch.A : TypedefStaircaseTranscriptOracle.Branch.B,
                                0x66,
                                TypedefCallsite.PTC_Param,
                                "DslStep" + i,
                                paramCount,
                                XvmPrimitiveTranslationTable.XvmPrimitive.Int32);
                    }
                    return null;
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        var state = oracle.state();
        assertEquals(total, oracle.snapshot().length);
        assertEquals(total, state.total());
        assertEquals(total, state.allowA() + state.blockA() + state.allowB() + state.blockB());
        assertEquals(0, state.blockA() + state.blockB());
        assertTrue(oracle.verifierReport().parameterized() > 0, "parameterized rows should be verified");
        assertEquals(0, oracle.verifierReport().failures());
    }
}
