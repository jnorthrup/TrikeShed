package borg.trikeshed.common.collections.associative.trie

fun generateBraceExpansion(tokenList: List<List<String>>, currentIndex: Int, result: StringBuilder) {
    if (currentIndex < tokenList.size) {
        val currentToken = tokenList[currentIndex]
        val hasNextToken = currentIndex + 1 < tokenList.size
        val nextToken = if (hasNextToken) tokenList[currentIndex + 1] else null

        result.append(currentToken.joinToString("/"))

        if (hasNextToken && areTokensAdjacent(tokenList, currentIndex, currentIndex + 1)) {
            result.append(",")
        } else {
            result.append("}")
        }

        generateBraceExpansion(tokenList, currentIndex + 1, result)
    }
}

fun areTokensAdjacent(tokenList: List<List<String>>, index1: Int, index2: Int): Boolean {
    return (index1 + 1 == index2) || (index1 - 1 == index2)
}

fun minimizeBraceExpansion(tokens: List<List<String>>): String {
    val sharedPrefix = mutableListOf<String>()
    var currentIndex = 0
    val maxDepth = tokens.map { it.size }.minOrNull() ?: 0

    while (currentIndex < maxDepth) {
        val currentLevelTokens = tokens.map { it[currentIndex] }.distinct()
        if (currentLevelTokens.size == 1) {
            sharedPrefix.add(currentLevelTokens.first())
        } else {
            break
        }
        currentIndex++
    }

    val remainingTokens = tokens.map { it.subList(currentIndex, it.size) }
    val result = StringBuilder(sharedPrefix.joinToString("/"))

    if (remainingTokens.isNotEmpty()) {
        result.append('{')
        remainingTokens.forEachIndexed { index, tokenList ->
            if (index != 0) {
                result.append(',')
            }
            result.append(tokenList.joinToString("/"))
        }
        result.append('}')
    }

    return result.toString()
}
//
//
//class BraceExpansionTest {
//    @Test
//    fun testBraceCreation() {
//        val tokens = listOf(
//            listOf("token1", "token2", "token3"),
//            listOf("token1", "token2", "token4"),
//            listOf("token1", "token2", "token5")
//        )
//
//        val braceExpansion = minimizeBraceExpansion(tokens)
//        val expectedExpansion = "token1/token2/{token3,token4,token5}"
//
//        assertEquals(expectedExpansion, braceExpansion)
//    }
//}