package org.xvm.runtime;

import java.util.concurrent.atomic.AtomicLong;

import borg.trikeshed.lib.ChunkedMutableSeries;
import borg.trikeshed.lib.Reducer;
import borg.trikeshed.lib.ReduxMutableSeries;
import kotlin.jvm.functions.Function1;

import org.xvm.asm.constants.TypedefResolutionPublisher.TypedefCallsite;

/**
 * Redux-backed transcript verifier for typedef production responses.
 *
 * Typedef facts stay out of the cascade table. Cascade is for tabular stats;
 * this transcript delegates semantic typedef identity to TypedefProductionTable
 * through a pointcut verifier.
 */
public final class TypedefStaircaseTranscriptOracle {
    public enum Branch {
        A,
        B
    }

    public enum Vote {
        ALLOW,
        BLOCK
    }

    public record TranscriptRow(
            long seq,
            Branch branch,
            int opcode,
            VmPointcutDispatch.Kind pointcutKind,
            int siteOrd,
            String siteName,
            String alias,
            int paramCount,
            Vote vote,
            TypedefProductionTable.Mode mode,
            XvmPrimitiveTranslationTable.VtableLayout layout,
            byte mixinCompat,
            long identityHash,
            String reason) {
    }

    public record TranscriptState(
            int total,
            int allowA,
            int blockA,
            int allowB,
            int blockB,
            int maxParamCount) {
        public boolean branchAllowed(Branch branch) {
            return switch (branch) {
                case A -> blockA == 0;
                case B -> blockB == 0;
            };
        }
    }

    private static final TranscriptRow CAPTURE = new TranscriptRow(
            -1,
            Branch.A,
            -1,
            VmPointcutDispatch.Kind.GAP,
            -1,
            "capture",
            "capture",
            -1,
            Vote.ALLOW,
            TypedefProductionTable.Mode.STEALTH,
            XvmPrimitiveTranslationTable.VtableLayout.VIRTUAL,
            (byte) 0,
            0L,
            "capture");

    private final Object lock = new Object();
    private final AtomicLong seq = new AtomicLong();
    private final TypedefProductionTable productionTable = new TypedefProductionTable(64);
    private final TypedefPointcutVerifier verifier = new TypedefPointcutVerifier(productionTable);
    private final ReduxMutableSeries<TranscriptRow, TranscriptState> transcript;

    public TypedefStaircaseTranscriptOracle() {
        var delegate = new ChunkedMutableSeries<TranscriptRow>(64);
        transcript = new ReduxMutableSeries<>(delegate, new TranscriptReducer(), new TranscriptReducer().getZero(), CAPTURE);
    }

    public TranscriptRow record(Branch branch, int opcode, TypedefCallsite site,
                                String alias, int paramCount,
                                XvmPrimitiveTranslationTable.XvmPrimitive primitive) {
        synchronized (lock) {
            var production = verifier.typedefPointcut(opcode, site, alias, paramCount, primitive);
            var reason = production.mode() == TypedefProductionTable.Mode.STEALTH
                    ? "stealth typedef params=0 erases through production table"
                    : "parameterized typedef keeps identity through pointcut verifier";
            var row = new TranscriptRow(
                    seq.incrementAndGet(),
                    branch,
                    opcode,
                    production.pointcutKind(),
                    production.siteOrd(),
                    production.siteName(),
                    alias,
                    paramCount,
                    Vote.ALLOW,
                    production.mode(),
                    production.layout(),
                    production.mixinCompat(),
                    production.identityHash(),
                    reason);
            transcript.dispatch(row);
            return row;
        }
    }

    public TranscriptState state() {
        synchronized (lock) {
            return transcript.reify();
        }
    }

    public boolean branchAllowed(Branch branch) {
        return state().branchAllowed(branch);
    }

    public TypedefProductionTable.Report verifierReport() {
        synchronized (lock) {
            return verifier.verify();
        }
    }

    public TypedefProductionTable productionTable() {
        return productionTable;
    }

    public TranscriptRow[] snapshot() {
        synchronized (lock) {
            var journal = transcript.getEventJournal();
            var size = journal.getA();
            @SuppressWarnings("unchecked")
            var reader = (Function1<Integer, TranscriptRow>) journal.getB();
            var rows = new TranscriptRow[size];
            for (var i = 0; i < size; i++) {
                rows[i] = reader.invoke(i);
            }
            return rows;
        }
    }

    private static final class TranscriptReducer implements Reducer<TranscriptRow, TranscriptState> {
        @Override
        public TranscriptState getZero() {
            return new TranscriptState(0, 0, 0, 0, 0, 0);
        }

        @Override
        public TranscriptState combine(TranscriptState state, TranscriptRow row) {
            var allowA = state.allowA();
            var blockA = state.blockA();
            var allowB = state.allowB();
            var blockB = state.blockB();
            if (row.branch() == Branch.A) {
                if (row.vote() == Vote.ALLOW) {
                    allowA++;
                } else {
                    blockA++;
                }
            } else {
                if (row.vote() == Vote.ALLOW) {
                    allowB++;
                } else {
                    blockB++;
                }
            }
            return new TranscriptState(
                    state.total() + 1,
                    allowA,
                    blockA,
                    allowB,
                    blockB,
                    Math.max(state.maxParamCount(), row.paramCount()));
        }
    }
}
