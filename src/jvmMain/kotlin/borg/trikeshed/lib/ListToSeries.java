package borg.trikeshed.lib;

import java.util.Arrays;
import java.util.List;

/**
 * Java-friendly factory to create a Series from varargs or List.
 * Used by Java tests that can't call Kotlin extension functions directly.
 */
public final class ListToSeries {
    @SafeVarargs
    public static <T> Series<T> of(T... elements) {
        return SeriesKt.toSeries(Arrays.asList(elements));
    }

    public static <T> Series<T> from(List<T> list) {
        return SeriesKt.toSeries(list);
    }
}
