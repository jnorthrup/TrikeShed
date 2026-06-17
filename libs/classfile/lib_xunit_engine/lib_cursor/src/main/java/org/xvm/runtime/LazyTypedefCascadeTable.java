package org.xvm.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Lazy column inference wrapper for {@link TypedefCascadeTable}.
 *
 * The append hot path ({@link #fold}, {@link #routeOpcode}) delegates directly
 * to the backing {@code TypedefCascadeTable} — zero allocation on the write
 * side. Columns are not materialized until queried via {@link LazyColumn#get()},
 * at which point the deferred {@link Supplier} fires once and the result is
 * cached permanently.
 *
 * Named columns and their indices:
 * <pre>
 *   0 "depth"   -> byte[]   backing depth
 *   1 "kind"    -> byte[]   backing kind
 *   2 "scope"   -> byte[]   backing scope
 *   3 "success" -> byte[]   backing success
 *   4 "opcode"  -> int[]    backing siteOrd (opcode-derived ordinal)
 *   5 "addr"    -> int[]    backing poolId  (address-like identity)
 * </pre>
 *
 * @see TypedefCascadeTable
 * @see CascadeRollup
 */
public final class LazyTypedefCascadeTable {

    // ── Column indices ────────────────────────────────────────────────────

    public static final int COL_DEPTH    = 0;
    public static final int COL_KIND     = 1;
    public static final int COL_SCOPE    = 2;
    public static final int COL_SUCCESS  = 3;
    public static final int COL_OPCODE   = 4;
    public static final int COL_ADDR     = 5;
    public static final int COLUMN_COUNT = 6;

    private static final String[] COLUMN_NAMES = {
        "depth", "kind", "scope", "success", "opcode", "addr"
    };

    // ── Instance state ────────────────────────────────────────────────────

    private final TypedefCascadeTable backing;

    /** Fixed-size array indexed by column index. */
    private final LazyColumn<?>[] columns;

    /** Name -> LazyColumn lookup. */
    private final Map<String, LazyColumn<?>> columnRegistry;

    // ── Inference results (populated by infer()) ──────────────────────────

    /** Row indices where kind == KIND_FIELD. */
    private int[] fieldCandidateIndices;
    private int fieldCandidateCount;

    /** AVX2 lane assignment per row (0..7), computed by infer(). */
    private byte[] laneAssignment;

    /** True after infer() has run. */
    private boolean inferred;

    // ── Constructor ───────────────────────────────────────────────────────

    /**
     * Wrap an existing cascade table with lazy column providers.
     *
     * @param backing the eager SoA table that owns the columnar data
     */
    public LazyTypedefCascadeTable(TypedefCascadeTable backing) {
        this.backing = backing;
        this.columns = new LazyColumn[COLUMN_COUNT];
        this.columnRegistry = new LinkedHashMap<>(COLUMN_COUNT * 2);
        this.fieldCandidateIndices = new int[64];
        this.fieldCandidateCount = 0;
        this.laneAssignment = null;
        this.inferred = false;

        // Deferred suppliers — the backing accessors are called only on first get()
        columns[COL_DEPTH]   = registerColumn("depth",   () -> backing.depthColumn());
        columns[COL_KIND]    = registerColumn("kind",    () -> backing.kindColumn());
        columns[COL_SCOPE]   = registerColumn("scope",   () -> backing.scopeColumn());
        columns[COL_SUCCESS] = registerColumn("success", () -> backing.successColumn());
        columns[COL_OPCODE]  = registerColumn("opcode",  () -> backing.siteOrdColumn());
        columns[COL_ADDR]    = registerColumn("addr",    () -> backing.poolIdColumn());
    }

    private LazyColumn<?> registerColumn(String name, Supplier<?> supplier) {
        var col = new LazyColumn<>(supplier);
        columnRegistry.put(name, col);
        return col;
    }

    // ── Hot-path delegation (allocation-free) ─────────────────────────────

    /**
     * Fold a single event into the backing cascade table.
     * Delegates directly — no lazy overhead, no allocation.
     */
    public void fold(int opcode, String method, int addr) {
        backing.fold(opcode, method, addr);
    }

    /**
     * Route an opcode through the dispatch tables and materialize into
     * the backing cascade table. Delegates directly — zero lazy overhead.
     */
    public void routeOpcode(int opcode, String method, int addr) {
        backing.routeOpcode(opcode, method, addr);
    }

    /** Current row count in the backing table. */
    public int rowCount() {
        return backing.rowCount();
    }

    // ── Lazy column access ────────────────────────────────────────────────

    /**
     * Route a column name to its lazy provider.
     *
     * @param columnName one of: depth, kind, scope, success, opcode, addr
     * @param <T>        the column array type (byte[], int[], or double[])
     * @return lazy column provider — materialized on first {@link LazyColumn#get()}
     * @throws IllegalArgumentException if the column name is unknown
     */
    @SuppressWarnings("unchecked")
    public <T> LazyColumn<T> columnRouter(String columnName) {
        var col = columnRegistry.get(columnName);
        if (col == null) {
            throw new IllegalArgumentException("unknown column: " + columnName);
        }
        return (LazyColumn<T>) col;
    }

    /**
     * Return a lazy view over selected columns. Does NOT copy data.
     *
     * Column indices are the {@code COL_*} constants on this class.
     * The returned list holds the same {@link LazyColumn} instances from the
     * registry — no data is materialized or copied by this call.
     *
     * @param columnIndices column indices to project (0..5)
     * @return unmodifiable list of lazy column references
     */
    public List<LazyColumn<?>> project(int... columnIndices) {
        var result = new ArrayList<LazyColumn<?>>(columnIndices.length);
        for (int idx : columnIndices) {
            if (idx < 0 || idx >= COLUMN_COUNT) {
                throw new IndexOutOfBoundsException("column index out of range: " + idx);
            }
            result.add(columns[idx]);
        }
        return Collections.unmodifiableList(result);
    }

    // ── Inference ─────────────────────────────────────────────────────────

    /**
     * Trigger deferred column inference. Scans the kind column and computes:
     * <ol>
     *   <li>Field-access candidate indices (rows where kind == KIND_FIELD)</li>
     *   <li>AVX2 lane assignment — group-by-kind packing, 8 int lanes per
     *       256-bit vector register</li>
     * </ol>
     */
    public void infer() {
        int n = backing.rowCount();
        byte[] kinds = backing.kindColumn();

        // 1. Field-access candidates
        fieldCandidateCount = 0;
        for (int i = 0; i < n; i++) {
            if (kinds[i] == TypedefCascadeTable.KIND_FIELD) {
                ensureFieldCapacity(fieldCandidateCount + 1);
                fieldCandidateIndices[fieldCandidateCount++] = i;
            }
        }

        // 2. AVX2 lane assignment (group by kind, 8 lanes per vector)
        laneAssignment = CascadeRollup.avx2LaneAssignment(kinds, n);

        inferred = true;
    }

    // ── Inference result accessors ────────────────────────────────────────

    /**
     * Row indices where kind == KIND_FIELD (field-access candidates).
     * Triggers {@link #infer()} if not yet called.
     *
     * @return copy of the field candidate index array
     */
    public int[] fieldCandidates() {
        if (!inferred) infer();
        var result = new int[fieldCandidateCount];
        System.arraycopy(fieldCandidateIndices, 0, result, 0, fieldCandidateCount);
        return result;
    }

    /** Number of field-access candidate rows. */
    public int fieldCandidateCount() {
        return fieldCandidateCount;
    }

    /**
     * AVX2 lane assignment per row (0..7).
     * Triggers {@link #infer()} if not yet called.
     *
     * @return lane assignment byte array, one entry per row
     */
    public byte[] laneAssignment() {
        if (!inferred) infer();
        return laneAssignment;
    }

    /** Whether {@link #infer()} has been called. */
    public boolean isInferred() {
        return inferred;
    }

    /** The backing eager cascade table. */
    public TypedefCascadeTable backing() {
        return backing;
    }

    /**
     * Accumulates the histogram of a column, designed to be autovectorization-friendly
     * by the JVM C2/Graal compiler on any CPU (including macOS Apple Silicon and server architectures).
     *
     * @param columnName one of: depth, kind, scope, success, opcode, addr
     * @param binCount   number of histogram bins
     * @return flat histogram of length binCount
     */
    public long[] accumulateHistogram(String columnName, int binCount) {
        var lazyCol = columnRouter(columnName);
        var array = lazyCol.get();
        int n = rowCount();
        long[] histogram = new long[binCount];

        if (array instanceof byte[] bytes) {
            for (int i = 0; i < n; i++) {
                int val = bytes[i] & 0xFF;
                if (val >= 0 && val < binCount) {
                    histogram[val]++;
                }
            }
        } else if (array instanceof int[] ints) {
            for (int i = 0; i < n; i++) {
                int val = ints[i];
                if (val >= 0 && val < binCount) {
                    histogram[val]++;
                }
            }
        } else {
            throw new UnsupportedOperationException("Column type not supported for histogram accumulation");
        }
        return histogram;
    }

    // ── LazyColumn ────────────────────────────────────────────────────────

    /**
     * Deferred column provider. Takes a {@link Supplier}, computes on first
     * {@link #get()}, and caches the result permanently. The supplier is
     * released for GC after materialization.
     *
     * <p>Zero-allocation on the hot path: suppliers are only invoked when a
     * column is actually queried, never during {@code fold}/{@code routeOpcode}.
     *
     * @param <T> the column array type (e.g. {@code byte[]}, {@code int[]},
     *            {@code double[]})
     */
    public static final class LazyColumn<T> {

        private Supplier<T> supplier;
        private T cached;
        private boolean materialized;

        LazyColumn(Supplier<T> supplier) {
            this.supplier = supplier;
        }

        /**
         * Materialize and return the column data. On first call the supplier
         * fires; subsequent calls return the cached result.
         *
         * @return the column array
         */
        public T get() {
            if (!materialized) {
                cached = supplier.get();
                materialized = true;
                supplier = null; // release for GC
            }
            return cached;
        }

        /** Whether {@link #get()} has been called at least once. */
        public boolean isMaterialized() {
            return materialized;
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private void ensureFieldCapacity(int needed) {
        if (needed <= fieldCandidateIndices.length) return;
        int newCap = fieldCandidateIndices.length * 2;
        while (newCap < needed) newCap *= 2;
        var newBuf = new int[newCap];
        System.arraycopy(fieldCandidateIndices, 0, newBuf, 0, fieldCandidateCount);
        fieldCandidateIndices = newBuf;
    }
}
