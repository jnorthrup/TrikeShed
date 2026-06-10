package org.xvm.runtime;

import java.util.Arrays;

/**
 * Mapreduce lattice over the 4-tier cascade rollup.
 *
 * Consumes CascadeRollup.TierSnapshot[] from CascadeRollup.cascadeRollup().
 * Provides partition-based map() and reduce() for kind×scope lattice traversal.
 *
 * Partition scheme:
 *   kind-partition  — 9 bins (CALL=0, ALLOC=1, RETURN=2, FIELD=3, TYPE=4, ASSERT=5, LOOP=6, SYNC=7, GAP=8)
 *   scope-partition — 4 bins (MODULE=0, PACKAGE=1, CLASS=2, METHOD=3)
 *
 * Mapreduce lattice layout:
 *   kind-dimension: [9 partitions] × [4 sub-bins] = 36 cells
 *   scope-dimension: [4 partitions] × [9 sub-bins] = 36 cells
 *   Both share the same 9×4 joint histogram from T4.
 */
public final class CascadeLattice {

    private CascadeLattice() { /* static utility */ }

    // ════════════════════════════════════════════════════════════════════════
    // Partition keys
    // ════════════════════════════════════════════════════════════════════════

    public static final int KIND_PARTITIONS = 9;  // KIND_COUNT
    public static final int SCOPE_PARTITIONS = 4; // SCOPE_COUNT
    public static final int CELL_COUNT = KIND_PARTITIONS * SCOPE_PARTITIONS; // 36

    // ════════════════════════════════════════════════════════════════════════
    // Map — partition the cascade snapshots by kind or scope
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Map: partition a TierSnapshot[] by kind, producing per-kind partitions.
     *
     * @param snapshots  4-tier cascade rollup result
     * @param kind       kind to filter (0..KIND_COUNT-1)
     * @return TierSnapshot[] keyed to the requested kind, with buckets
     *         holding per-scope counts within that kind
     */
    public static CascadeRollup.TierSnapshot[] mapByKind(CascadeRollup.TierSnapshot[] snapshots, int kind) {
        if (kind < 0 || kind >= KIND_PARTITIONS) {
            return emptySnapshots();
        }
        var result = new CascadeRollup.TierSnapshot[snapshots.length];
        for (int t = 0; t < snapshots.length; t++) {
            CascadeRollup.TierSnapshot src = snapshots[t];
            if (src == null || src.buckets == null) {
                result[t] = new CascadeRollup.TierSnapshot(t + 1, EMPTY_LONG);
                continue;
            }
            if (t == 3) {
                // T4: extract column from joint histogram
                int S = SCOPE_PARTITIONS;
                var col = new long[S];
                long[] joint = src.jointHistogram;
                if (joint != null) {
                    for (int s = 0; s < S; s++) {
                        col[s] = joint[kind * S + s];
                    }
                }
                result[t] = new CascadeRollup.TierSnapshot(4, col, null, src.totalEvents);
            } else {
                // T1/T2/T3: single bucket at kind index
                var col = new long[1];
                col[0] = src.buckets.length > kind ? src.buckets[kind] : 0;
                result[t] = new CascadeRollup.TierSnapshot(t + 1, col, null, col[0]);
            }
        }
        return result;
    }

    /**
     * Map: partition a TierSnapshot[] by scope, producing per-scope partitions.
     *
     * @param snapshots  4-tier cascade rollup result
     * @param scope      scope to filter (0..SCOPE_COUNT-1)
     * @return TierSnapshot[] keyed to the requested scope, with buckets
     *         holding per-kind counts within that scope
     */
    public static CascadeRollup.TierSnapshot[] mapByScope(CascadeRollup.TierSnapshot[] snapshots, int scope) {
        if (scope < 0 || scope >= SCOPE_PARTITIONS) {
            return emptySnapshots();
        }
        var result = new CascadeRollup.TierSnapshot[snapshots.length];
        for (int t = 0; t < snapshots.length; t++) {
            CascadeRollup.TierSnapshot src = snapshots[t];
            if (src == null || src.buckets == null) {
                result[t] = new CascadeRollup.TierSnapshot(t + 1, EMPTY_LONG);
                continue;
            }
            if (t == 3) {
                // T4: extract row from joint histogram
                int S = SCOPE_PARTITIONS;
                var row = new long[KIND_PARTITIONS];
                long[] joint = src.jointHistogram;
                if (joint != null) {
                    for (int k = 0; k < KIND_PARTITIONS; k++) {
                        row[k] = joint[k * S + scope];
                    }
                }
                result[t] = new CascadeRollup.TierSnapshot(4, row, null, src.totalEvents);
            } else {
                // T1/T2/T3: single bucket
                var row = new long[1];
                row[0] = src.buckets.length > scope ? src.buckets[scope] : 0;
                result[t] = new CascadeRollup.TierSnapshot(t + 1, row, null, row[0]);
            }
        }
        return result;
    }

    /**
     * Map: all kind partitions as a 2D array [kind][tier].
     * Convenience for iterating all 9 kind partitions.
     */
    public static CascadeRollup.TierSnapshot[][] mapAllKinds(CascadeRollup.TierSnapshot[] snapshots) {
        var partitions = new CascadeRollup.TierSnapshot[KIND_PARTITIONS][];
        for (int k = 0; k < KIND_PARTITIONS; k++) {
            partitions[k] = mapByKind(snapshots, k);
        }
        return partitions;
    }

    /**
     * Map: all scope partitions as a 2D array [scope][tier].
     * Convenience for iterating all 4 scope partitions.
     */
    public static CascadeRollup.TierSnapshot[][] mapAllScopes(CascadeRollup.TierSnapshot[] snapshots) {
        var partitions = new CascadeRollup.TierSnapshot[SCOPE_PARTITIONS][];
        for (int s = 0; s < SCOPE_PARTITIONS; s++) {
            partitions[s] = mapByScope(snapshots, s);
        }
        return partitions;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Reduce — merge partitions into lattice cells
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Reduce: merge two kind partitions via element-wise addition.
     *
     * @param a  first partition (same tier structure)
     * @param b  second partition
     * @return merged partition (same tier structure)
     */
    public static CascadeRollup.TierSnapshot[] reduceKind(CascadeRollup.TierSnapshot[] a, CascadeRollup.TierSnapshot[] b) {
        if (a == null || b == null) return a != null ? a : b;
        var result = new CascadeRollup.TierSnapshot[a.length];
        for (int t = 0; t < a.length; t++) {
            result[t] = reduceSnapshot(a[t], b[t]);
        }
        return result;
    }

    /**
     * Reduce: merge two scope partitions via element-wise addition.
     * Same as reduceKind — both are element-wise long addition on bucket arrays.
     */
    public static CascadeRollup.TierSnapshot[] reduceScope(CascadeRollup.TierSnapshot[] a, CascadeRollup.TierSnapshot[] b) {
        return reduceKind(a, b); // same algorithm
    }

    /**
     * Reduce: build the full 36-cell lattice from T4 joint histogram.
     *
     * @param snapshots  4-tier cascade rollup result
     * @return 2D long[9][4] lattice — lattice[kind][scope] = count
     */
    public static long[][] latticeCells(CascadeRollup.TierSnapshot[] snapshots) {
        var cells = new long[KIND_PARTITIONS][SCOPE_PARTITIONS];
        if (snapshots == null || snapshots.length < 4) return cells;
        CascadeRollup.TierSnapshot t4 = snapshots[3];
        if (t4 == null || t4.jointHistogram == null) return cells;
        int S = SCOPE_PARTITIONS;
        for (int k = 0; k < KIND_PARTITIONS; k++) {
            for (int s = 0; s < S; s++) {
                cells[k][s] = t4.jointHistogram[k * S + s];
            }
        }
        return cells;
    }

    /**
     * Reduce: merge two lattice cells via element-wise addition.
     */
    public static long[][] reduceLatticeCells(long[][] a, long[][] b) {
        if (a == null || b == null) return a != null ? a : b;
        for (int k = 0; k < KIND_PARTITIONS; k++) {
            for (int s = 0; s < SCOPE_PARTITIONS; s++) {
                a[k][s] += b[k][s];
            }
        }
        return a;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Column-router — navigate the lattice by kind/scope name
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Route: resolve a kind name to its ordinal.
     *
     * @param name  kind name (CALL, ALLOC, RETURN, FIELD, TYPE, ASSERT, LOOP, SYNC, GAP)
     * @return kind ordinal, or -1 if not found
     */
    public static int routeKind(String name) {
        return switch (name) {
            case "CALL"   -> TypedefCascadeTable.KIND_CALL;
            case "ALLOC"  -> TypedefCascadeTable.KIND_ALLOC;
            case "RETURN" -> TypedefCascadeTable.KIND_RETURN;
            case "FIELD"  -> TypedefCascadeTable.KIND_FIELD;
            case "TYPE"   -> TypedefCascadeTable.KIND_TYPE;
            case "ASSERT" -> TypedefCascadeTable.KIND_ASSERT;
            case "LOOP"   -> TypedefCascadeTable.KIND_LOOP;
            case "SYNC"   -> TypedefCascadeTable.KIND_SYNC;
            case "GAP"    -> TypedefCascadeTable.KIND_GAP;
            default       -> -1;
        };
    }

    /**
     * Route: resolve a scope name to its ordinal.
     *
     * @param name  scope name (MODULE, PACKAGE, CLASS, METHOD)
     * @return scope ordinal, or -1 if not found
     */
    public static int routeScope(String name) {
        return switch (name) {
            case "MODULE"  -> 0;
            case "PACKAGE" -> 1;
            case "CLASS"   -> 2;
            case "METHOD"  -> 3;
            default        -> -1;
        };
    }

    /**
     * Route: look up a lattice cell by kind and scope name.
     *
     * @param snapshots  4-tier cascade rollup result
     * @param kindName   kind name string
     * @param scopeName  scope name string
     * @return cell count, or 0 if kind or scope not found
     */
    public static long routeCell(CascadeRollup.TierSnapshot[] snapshots, String kindName, String scopeName) {
        int k = routeKind(kindName);
        int s = routeScope(scopeName);
        if (k < 0 || s < 0) return 0;
        long[][] cells = latticeCells(snapshots);
        return cells[k][s];
    }

    /**
     * Route: kind name → TierSnapshot partition via mapByKind.
     */
    public static CascadeRollup.TierSnapshot[] routeKindPartition(CascadeRollup.TierSnapshot[] snapshots, String kindName) {
        int k = routeKind(kindName);
        return k >= 0 ? mapByKind(snapshots, k) : emptySnapshots();
    }

    /**
     * Route: scope name → TierSnapshot partition via mapByScope.
     */
    public static CascadeRollup.TierSnapshot[] routeScopePartition(CascadeRollup.TierSnapshot[] snapshots, String scopeName) {
        int s = routeScope(scopeName);
        return s >= 0 ? mapByScope(snapshots, s) : emptySnapshots();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════════════

    private static final long[] EMPTY_LONG = new long[0];

    private static CascadeRollup.TierSnapshot[] emptySnapshots() {
        return new CascadeRollup.TierSnapshot[] {
            new CascadeRollup.TierSnapshot(1, EMPTY_LONG),
            new CascadeRollup.TierSnapshot(2, EMPTY_LONG),
            new CascadeRollup.TierSnapshot(3, EMPTY_LONG),
            new CascadeRollup.TierSnapshot(4, EMPTY_LONG)
        };
    }

    private static CascadeRollup.TierSnapshot reduceSnapshot(CascadeRollup.TierSnapshot a, CascadeRollup.TierSnapshot b) {
        if (a == null) return b;
        if (b == null) return a;
        long[] ab = a.buckets;
        long[] bb = b.buckets;
        if (ab == null || bb == null) return a;
        long[] merged = new long[Math.max(ab.length, bb.length)];
        for (int i = 0; i < merged.length; i++) {
            merged[i] = (i < ab.length ? ab[i] : 0) + (i < bb.length ? bb[i] : 0);
        }
        return new CascadeRollup.TierSnapshot(a.tier, merged, null, a.totalEvents + b.totalEvents);
    }
}