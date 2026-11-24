package narchy.spacegraph.util

// Port of upstream src/utils/collection.ts at 125b369.
// Appends a value to a map's bucket, creating the bucket on first use.

fun <Key, Value> appendTo(buckets: MutableMap<Key, MutableList<Value>>, key: Key, value: Value) {
    val bucket = buckets[key]
    if (bucket != null) bucket.add(value)
    else buckets[key] = mutableListOf(value)
}
