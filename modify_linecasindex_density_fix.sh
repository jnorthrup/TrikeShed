#!/bin/bash
cat << 'INNER_EOF' > modify.diff
<<<<<<< SEARCH
        return chunks j { i: Int ->
            val start = i * chunkSize
            val end = minOf(start + chunkSize, probe.size)
            val chunk = end - start j { j: Int -> probe[start + j] }
            val res = residualsOf(chunk)
            val density = try { linkDensity(chunk) } catch (e: Exception) { 0 j { _: Int -> error("empty") } }
            res.size j density
        }
=======
        val result: Series<Join<Int, Series<Join<ContentId, OverlapCounts>>>> = chunks j { i: Int ->
            val start = i * chunkSize
            val end = minOf(start + chunkSize, probe.size)
            val chunk = end - start j { j: Int -> probe[start + j] }
            val res = residualsOf(chunk)
            val density = try { linkDensity(chunk) } catch (e: Exception) { 0 j { _: Int -> error("empty") } }
            res.size j density
        }
        return result
>>>>>>> REPLACE
INNER_EOF
