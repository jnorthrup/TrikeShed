package org.xvm.runtime;

import java.util.Arrays;

import org.xvm.asm.constants.TypedefResolutionPublisher.TypedefCallsite;

/**
 * Typedef production rows are not cascade rows.
 *
 * Cascade tables are for tabular pointcut stats. This table is the separate
 * typedef production surface: stealth typedefs erase; parameterized typedefs
 * keep identity for the later vtable/type calculus.
 */
public final class TypedefProductionTable {
    public enum Mode {
        STEALTH,
        PARAMETERIZED
    }

    public record Row(
            long seq,
            int opcode,
            VmPointcutDispatch.Kind pointcutKind,
            int siteOrd,
            String siteName,
            String alias,
            int paramCount,
            Mode mode,
            XvmPrimitiveTranslationTable.XvmPrimitive primitive,
            XvmPrimitiveTranslationTable.VtableLayout layout,
            byte mixinCompat,
            boolean erased,
            long identityHash) {
    }

    public record Report(int total, int stealth, int parameterized, int failures) {
    }

    private int n;
    private long nextSeq;
    private Row[] rows;

    public TypedefProductionTable(int capacity) {
        rows = new Row[Math.max(1, capacity)];
    }

    public synchronized Row appendProduction(int opcode, TypedefCallsite site, String alias, int paramCount,
                                             XvmPrimitiveTranslationTable.XvmPrimitive primitive) {
        if (paramCount < 0) {
            throw new IllegalArgumentException("paramCount must be >= 0");
        }
        ensureCapacity(n + 1);
        var options = XvmPrimitiveTranslationTable.vtableOptions(primitive);
        var mode = paramCount == 0 ? Mode.STEALTH : Mode.PARAMETERIZED;
        var identityHash = mode == Mode.STEALTH ? 0L : stableIdentity(site, alias, paramCount, primitive);
        var row = new Row(
                ++nextSeq,
                opcode,
                VmPointcutDispatch.kindOf(opcode),
                site.siteIndex(),
                site.name(),
                alias,
                paramCount,
                mode,
                primitive,
                options.layout(),
                options.mixinCompat(),
                mode == Mode.STEALTH,
                identityHash);
        rows[n++] = row;
        return row;
    }

    public synchronized Report verify() {
        var stealth = 0;
        var parameterized = 0;
        var failures = 0;
        for (var i = 0; i < n; i++) {
            var row = rows[i];
            if (row.mode() == Mode.STEALTH) {
                stealth++;
                if (!row.erased() || row.identityHash() != 0L) {
                    failures++;
                }
            } else {
                parameterized++;
                if (row.erased() || row.identityHash() == 0L) {
                    failures++;
                }
            }
            if (row.pointcutKind() != VmPointcutDispatch.kindOf(row.opcode())) {
                failures++;
            }
        }
        return new Report(n, stealth, parameterized, failures);
    }

    public synchronized int rowCount() {
        return n;
    }

    public synchronized Row row(int index) {
        if (index < 0 || index >= n) {
            throw new IndexOutOfBoundsException(index);
        }
        return rows[index];
    }

    public synchronized void reset() {
        Arrays.fill(rows, 0, n, null);
        n = 0;
        nextSeq = 0;
    }

    static long stableIdentity(TypedefCallsite site, String alias, int paramCount,
                               XvmPrimitiveTranslationTable.XvmPrimitive primitive) {
        var hash = 0xcbf29ce484222325L;
        hash = fnv(hash, site.siteIndex());
        hash = fnv(hash, paramCount);
        hash = fnv(hash, primitive.ordinal());
        for (var i = 0; i < alias.length(); i++) {
            hash = fnv(hash, alias.charAt(i));
        }
        return hash == 0L ? 1L : hash;
    }

    private void ensureCapacity(int needed) {
        if (needed <= rows.length) {
            return;
        }
        var newCap = rows.length * 2;
        while (newCap < needed) {
            newCap *= 2;
        }
        rows = Arrays.copyOf(rows, newCap);
    }

    private static long fnv(long hash, int value) {
        hash ^= value & 0xFFL;
        hash *= 0x100000001b3L;
        hash ^= (value >>> 8) & 0xFFL;
        hash *= 0x100000001b3L;
        hash ^= (value >>> 16) & 0xFFL;
        hash *= 0x100000001b3L;
        hash ^= (value >>> 24) & 0xFFL;
        hash *= 0x100000001b3L;
        return hash;
    }
}
