## 2024-05-24 - Zero-allocation Array mapping to Series
**Learning:** When mapping a primitive Array or vararg to a Series, using `Array.map { ... }.toSeries()` allocates an intermediate ArrayList. This can be completely avoided.
**Action:** Use the direct array mapped projection via the size `j` constructor (e.g., `array.size j { i -> array[i] }`) to yield a zero-allocation lazy mapped view.
