package org.xvm.runtime;

import borg.trikeshed.lib.COWSeriesBody;
import borg.trikeshed.lib.CowSeriesHandle;
import borg.trikeshed.lib.SeriesKt;

import java.util.ArrayList;
import java.util.AbstractList;

/**
 * Bridge: wraps ArrayList/List ctor results in a CowSeriesHandle
 * (VersioningMutableSeries delegate with COW body).
 *
 * Pointcut flow:
 *   1. bytecode sees: new ArrayList<>() / new ArrayList<>(n)
 *   2. ClassfilePointcutRewriter injects: publish(0x38, ...) after new+invokespecial
 *   3. This bridge wraps the ArrayList in a COW delegate
 *   4. Caller uses .toSeries().toList() as the unary getter to read back
 *
 * Architecture:
 *   CowSeriesHandle (envelope, stable facade, MutableSeries)
 *     +-- COWSeriesBody (letter, immutable, versioned, swapped on write)
 *           +-- backing: Join<Int, (Int)->T> (from ArrayList.toSeries())
 *
 *   handle = stable facade (no GC)
 *   body = counters + epoch (swapped defensively)
 *
 *   .toSeries().toList() = unary getter (T) -> T
 */
@SuppressWarnings("unchecked")
public final class ListCtorCowBridge {

    /**
     * Wrap an ArrayList in a CowSeriesHandle (COW delegate).
     * The ArrayList's current contents become the initial backing.
     */
    public static <T> CowHandle<T> wrap(ArrayList<T> list) {
        // SeriesKt.toSeries(List<? extends T>) returns Join<Integer, Function1<Integer, T>>
        // but Kotlin compiler emits wildcards that Java can't reconcile
        var backing = SeriesKt.toSeries((java.util.List) list);
        COWSeriesBody<T> body = new COWSeriesBody<>(backing, null);
        CowSeriesHandle<T> handle = new CowSeriesHandle<>(body, null, null);
        return new CowHandle<>(handle);
    }

    /**
     * Wrap and publish to VmPointcutPublisher — called from injected bytecode.
     */
    public static <T> CowHandle<T> wrapAndPublish(ArrayList<T> list, String method, int addr) {
        VmPointcutPublisher.publish(0x38, method, addr);
        return wrap(list);
    }

    /**
     * Typed handle holding a CowSeriesHandle.
     * Provides .toSeries().toList() as the unary getter.
     */
    public static final class CowHandle<T> {
        private final CowSeriesHandle<T> handle;

        CowHandle(CowSeriesHandle<T> handle) {
            this.handle = handle;
        }

        /** The underlying COW delegate — stable facade, zero GC on read. */
        public CowSeriesHandle<T> handle() { return handle; }

        /** Version from the COW body — changes on every mutation. */
        public Object version() { return handle.getVersion(); }

        /** Size from the COW body. */
        public int size() { return handle.getA(); }

        /** Add an element — COW letter swap, version bumps. */
        public void add(T item) { handle.add(item); }

        /**
         * Unary getter: (CowHandle<T>) -> List<T>
         * Goes through Series lazy fold, not direct ArrayList access.
         * This IS the output of the pointcut — the caller gets a List back,
         * but internally it's backed by a versioned COW Series.
         *
         * toSeries().toList() — the (T)->T unary function from the pointcut spec.
         */
        public java.util.List<T> toSeriesToList() {
            // CowSeriesHandle implements MutableSeries<T> extends Series<T> (= Join<Integer, Function1<Integer, T>>)
            // Kotlin variance makes the Java type system reject the direct pass — raw cast needed
            return SeriesKt.toList((borg.trikeshed.lib.Join) handle);
        }
    }
}
