#!/bin/bash
cat << 'INNER_EOF' > modify.diff
<<<<<<< SEARCH
    fun spineOf(docCid: ContentId): LineSpine? = docs[docCid.hex]
}
=======
    fun spineOf(docCid: ContentId): LineSpine? = docs[docCid.hex]

    /**
     * Residual extraction for the Funnel N-way merge.
     * Returns nodes from the probe whose contentCid.hex are MISSES in the index.
     */
    fun residualsOf(probe: LineSpine): Series<LineNode> {
        val f = funnel ?: return probe

        var missCount = 0
        var missIndices = IntArray(minOf(probe.size, 16))
        for (i in 0 until probe.size) {
            if (!f.contains(probe[i].contentCid.hex)) {
                if (missCount == missIndices.size) {
                    missIndices = missIndices.copyOf(missIndices.size * 2)
                }
                missIndices[missCount++] = i
            }
        }

        val finalIndices = if (missCount == missIndices.size) missIndices else missIndices.copyOf(missCount)
        return missCount j { i: Int -> probe[finalIndices[i]] }
    }
}
>>>>>>> REPLACE
INNER_EOF
