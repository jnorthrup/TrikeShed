package borg.trikeshed.duck

import borg.trikeshed.lib.Series

// Helper to bridge DuckDB to the pure Kotlin FeatureExtractor
// This replaces the old pandas InstrumentPanel data source
class DuckDBInstrumentAdapter(private val connection: DuckConnection) {
    
    fun fetchOHLCV(symbol: String): Map<String, Series<Any?>> {
        // Retrieve data directly via DuckCursor avoiding pandas
        return connection.queryOHLCV(symbol)
    }
    
    fun fetchIndicators(symbol: String): Map<String, Series<Any?>> {
        // Delegate to DuckDB native window functions
        return connection.queryIndicators(symbol)
    }
}
