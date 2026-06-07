package org.xvm.runtime;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class PointcutObservation {
    public enum Source {
        VM,
        FIELD
    }

    @FunctionalInterface
    public interface Observable {
        void onBatch(Source source, int count, long epoch);
    }

    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);
    private static final ConcurrentHashMap<Integer, Observable> OBSERVERS = new ConcurrentHashMap<>();

    private PointcutObservation() {
    }

    public static int subscribe(Observable observer) {
        var id = NEXT_ID.getAndIncrement();
        OBSERVERS.put(id, observer);
        return id;
    }

    public static void unsubscribe(int observerId) {
        OBSERVERS.remove(observerId);
    }

    public static void reset() {
        OBSERVERS.clear();
        NEXT_ID.set(1);
    }

    static void publish(Source source, int count, long epoch) {
        if (OBSERVERS.isEmpty()) {
            return;
        }
        OBSERVERS.values().forEach(observer ->
                observer.onBatch(source, count, epoch));
    }
}
