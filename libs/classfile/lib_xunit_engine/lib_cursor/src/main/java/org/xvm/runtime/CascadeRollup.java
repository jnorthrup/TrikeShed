package org.xvm.runtime;

import java.util.Arrays;

/**
 * 4-tier cascading stat rollup engine.
 *
 * Chains: T1 (LeafScan) → T2 (KindMerge) → T3 (ScopeRollup) → T4 (JointHistogram)
 *
 * T4 computes kind×scope joint histogram for downstream lattice consumption.
 * No threshold gate — lattice decides when to consume.
 */
public final class CascadeRollup {

    // ── AVX2 constants ────────────────────────────────────────────────────

    /** 8 x i32 lanes per 256-bit AVX2 vector register. */
    public static final int AVX2_LANE_COUNT = 8;

    private CascadeRollup() { /* static utility */ }

    // ════════════════════════════════════════════════════════════════════════
    // TierSnapshot — flat-array DTO, no per-row object allocation
    // ════════════════════════════════════════════════════════════════════════

    public static final class TierSnapshot {
        /** Tier that produced this snapshot (1-4). */
        public final int tier;
        /** Per-bucket counters. Interpretation depends on tier. */
        public final long[] buckets;
        /** Kind×scope joint histogram (T4 only, null for others). Layout: [kind * SCOPE_COUNT + scope]. */
        public final long[] jointHistogram;
        /** Total events counted. */
        public final long totalEvents;

        TierSnapshot(int tier, long[] buckets) {
            this(tier, buckets, null, 0);
        }

        TierSnapshot(int tier, long[] buckets, long[] joint, long total) {
            this.tier = tier;
            this.buckets = buckets;
            this.jointHistogram = joint;
            this.totalEvents = total;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Full cascade: T1 → T2 → T3 → T4
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Execute the full 4-tier cascade rollup.
     *
     * @param table  source cascade table
     * @return 4-element TierSnapshot array, one per tier
     */
    public static TierSnapshot[] cascadeRollup(TypedefCascadeTable table) {
        var snapshots = new TierSnapshot[4];
        snapshots[0] = leafScan(table);
        snapshots[1] = kindMerge(table);
        snapshots[2] = scopeRollup(table);
        snapshots[3] = jointHistogram(table);
        return snapshots;
    }

    // ── T1: LeafScan ── per-site-ordinal bucket counters ──────────────────

    /** Scan rows, bucket by site ordinal into 256 bins. */
    public static TierSnapshot leafScan(TypedefCascadeTable table) {
        var buckets = new long[256];
        int n = table.rowCount();
        var siteOrd = table.siteOrdColumn();
        long total = 0;
        for (int i = 0; i < n; i++) {
            int op = siteOrd[i] & 0xFF;
            buckets[op]++;
            total++;
        }
        return new TierSnapshot(1, buckets, null, total);
    }

    // ── T2: KindMerge ── per-kind histogram ───────────────────────────────

    /** Merge per-site into per-kind histogram (KIND_COUNT=9 bins). */
    public static TierSnapshot kindMerge(TypedefCascadeTable table) {
        var buckets = new long[TypedefCascadeTable.KIND_COUNT];
        int n = table.rowCount();
        var kind = table.kindColumn();
        long total = 0;
        for (int i = 0; i < n; i++) {
            byte k = kind[i];
            if (k >= 0 && k < buckets.length) {
                buckets[k]++;
                total++;
            }
        }
        return new TierSnapshot(2, buckets, null, total);
    }

    // ── T3: ScopeRollup ── per-scope aggregation ──────────────────────────

    /** Aggregate by scope level (MODULE=0, PACKAGE=1, CLASS=2, METHOD=3). */
    public static TierSnapshot scopeRollup(TypedefCascadeTable table) {
        var buckets = new long[TypedefCascadeTable.SCOPE_COUNT];
        int n = table.rowCount();
        var scope = table.scopeColumn();
        long total = 0;
        for (int i = 0; i < n; i++) {
            byte s = scope[i];
            if (s >= 0 && s < buckets.length) {
                buckets[s]++;
                total++;
            }
        }
        return new TierSnapshot(3, buckets, null, total);
    }

    // ── T4: JointHistogram ── kind×scope co-occurrence ────────────────────

    /**
     * Build kind×scope joint histogram (KIND_COUNT × SCOPE_COUNT = 9×4).
     *
     * Layout: jointHistogram[kind * SCOPE_COUNT + scope]
     *
     * @return TierSnapshot with jointHistogram populated
     */
    public static TierSnapshot jointHistogram(TypedefCascadeTable table) {
        int K = TypedefCascadeTable.KIND_COUNT;
        int S = TypedefCascadeTable.SCOPE_COUNT;
        int n = table.rowCount();
        var kind = table.kindColumn();
        var scope = table.scopeColumn();

        var joint = new long[K * S];
        long total = 0;
        for (int i = 0; i < n; i++) {
            byte k = kind[i];
            byte s = scope[i];
            if (k >= 0 && k < K && s >= 0 && s < S) {
                joint[k * S + s]++;
                total++;
            }
        }

        // also produce per-kind marginal for buckets
        var buckets = new long[K];
        for (int k = 0; k < K; k++) {
            for (int s = 0; s < S; s++) {
                buckets[k] += joint[k * S + s];
            }
        }

        return new TierSnapshot(4, buckets, joint, total);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Static utilities for LazyTypedefCascadeTable
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Compute AVX2 lane assignment for autovectorized reduction.
     *
     * Groups rows by kind and assigns each row a lane index (0..7) within
     * its kind group. Rows of the same kind that share a lane index can be
     * packed into the same 256-bit vector register for SIMD histogram
     * accumulation (vpaddd).
     */
    public static byte[] avx2LaneAssignment(byte[] kind, int n) {
        var lanes = new byte[n];
        var cursor = new int[TypedefCascadeTable.KIND_COUNT];
        for (int i = 0; i < n; i++) {
            byte k = kind[i];
            if (k >= 0 && k < TypedefCascadeTable.KIND_COUNT) {
                lanes[i] = (byte) (cursor[k] % AVX2_LANE_COUNT);
                cursor[k]++;
            }
        }
        return lanes;
    }

    /**
     * Identify field-access candidate row indices.
     *
     * @param kind  kind column from the cascade table
     * @param n     number of valid rows
     * @return int[] of row indices where kind == KIND_FIELD
     */
    public static int[] fieldCandidateIndices(byte[] kind, int n) {
        var buf = new int[n];
        int count = 0;
        for (int i = 0; i < n; i++) {
            if (kind[i] == TypedefCascadeTable.KIND_FIELD) {
                buf[count++] = i;
            }
        }
        return Arrays.copyOf(buf, count);
    }
}
