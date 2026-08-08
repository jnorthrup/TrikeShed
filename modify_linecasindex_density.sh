#!/bin/bash
cat << 'INNER_EOF' > modify.diff
<<<<<<< SEARCH
    fun spineOf(docCid: ContentId): LineSpine? = docs[docCid.hex]
=======
    fun spineOf(docCid: ContentId): LineSpine? = docs[docCid.hex]

    /**
     * Regional top-k density per aperture band.
     * Computes the residual density (novel content hits and overlap) grouped by
     * chunks matching the provided aperture zoom level, avoiding full probe loads.
     */
    fun residualDensity(probe: LineSpine, aperture: LineAperture): Series<Join<Int, Series<Join<ContentId, OverlapCounts>>>> {
        if (probe.size == 0) return 0 j { _: Int -> error("empty") }

        val regions = when (aperture) {
            LineAperture.L0 -> 1
            LineAperture.L1 -> 4
            LineAperture.L2 -> 16
            LineAperture.L3 -> 64
        }
        val chunks = minOf(regions, probe.size)
        val chunkSize = (probe.size + chunks - 1) / chunks

        return chunks j { i: Int ->
            val start = i * chunkSize
            val end = minOf(start + chunkSize, probe.size)
            val chunk = end - start j { j: Int -> probe[start + j] }
            val res = residualsOf(chunk)
            val density = if (res.size > 0) linkDensity(chunk) else 0 j { _: Int -> error("empty") }
            res.size j density
        }
    }
>>>>>>> REPLACE
INNER_EOF
