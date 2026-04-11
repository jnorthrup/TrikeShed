package borg.literbike.ccek.api_translation

/**
 * WebSearch API Format (Brave, Tavily, Serper)
 * Ported from literbike/src/ccek/api_translation/src/websearch.rs
 */

object BraveSearchClient {
    fun baseUrl(): String = Provider.BraveSearch.baseUrl()
}

object TavilySearchClient {
    fun baseUrl(): String = Provider.TavilySearch.baseUrl()
}

object SerperSearchClient {
    fun baseUrl(): String = Provider.SerperSearch.baseUrl()
}
