#!/bin/bash
cat << 'INNER_EOF' > modify.diff
<<<<<<< SEARCH
        val chunks = minOf(regions, probe.size)
        val chunkSize = (probe.size + chunks - 1) / chunks

        val result: Series<Join<Int, Series<Join<ContentId, OverlapCounts>>>> = chunks j { i: Int ->
            val start = i * chunkSize
            val end = minOf(start + chunkSize, probe.size)
            val chunk: Series<LineNode> = end - start j { j: Int -> probe[start + j] }
            val res = residualsOf(chunk)
            val density: Series<Join<ContentId, OverlapCounts>> = try { linkDensity(chunk) } catch (e: Exception) { 0 j { _: Int -> error("empty") } }
            res.size j density
        }
        return result as Series<Join<Int, Series<Join<ContentId, OverlapCounts>>>>
=======
        val chunks = minOf(regions, probe.size)

        val result: Series<Join<Int, Series<Join<ContentId, OverlapCounts>>>> = chunks j { i: Int ->
            val start = i * probe.size / chunks
            val end = (i + 1) * probe.size / chunks
            val chunk: Series<LineNode> = end - start j { j: Int -> probe[start + j] }
            val res = residualsOf(chunk)
            val density: Series<Join<ContentId, OverlapCounts>> = if (chunk.size > 0 && res.size > 0) {
                try { linkDensity(chunk) } catch (e: Exception) { 0 j { _: Int -> error("empty") } }
            } else {
                0 j { _: Int -> error("empty") }
            }
            res.size j density
        }
        return result as Series<Join<Int, Series<Join<ContentId, OverlapCounts>>>>
>>>>>>> REPLACE
INNER_EOF
