package cursors.calendar

import vec.macros.Tw1n
import vec.macros.t2
import vec.ml.featureRange as mlFeatureRange

fun featureRange(seq: Iterable<Int>, maxMinTwin: Tw1n<Int> = Int.MAX_VALUE t2 Int.MIN_VALUE): Tw1n<Int> =
    mlFeatureRange(seq, maxMinTwin)

fun featureRange(seq: Iterable<Long>, maxMinTwin: Tw1n<Long> = Long.MAX_VALUE t2 Long.MIN_VALUE): Tw1n<Long> =
    mlFeatureRange(seq, maxMinTwin)

fun featureRange(seq: Iterable<Double>, maxMinTwin: Tw1n<Double> = Double.MAX_VALUE t2 Double.MIN_VALUE): Tw1n<Double> =
    mlFeatureRange(seq, maxMinTwin)

fun featureRange(seq: Iterable<Float>, maxMinTwin: Tw1n<Float> = Float.MAX_VALUE t2 Float.MIN_VALUE): Tw1n<Float> =
    mlFeatureRange(seq, maxMinTwin)
