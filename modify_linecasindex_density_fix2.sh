#!/bin/bash
cat << 'INNER_EOF' > modify.diff
<<<<<<< SEARCH
            val density = try { linkDensity(chunk) } catch (e: Exception) { 0 j { _: Int -> error("empty") } }
            res.size j density
        }
        return result
=======
            val density: Series<Join<ContentId, OverlapCounts>> = try { linkDensity(chunk) } catch (e: Exception) { 0 j { _: Int -> error("empty") } }
            res.size j density
        }
        return result
>>>>>>> REPLACE
INNER_EOF
