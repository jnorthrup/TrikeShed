rootProject.name = "TrikeShed"

// Attempt to reconcile with a local libs/duckdb composite build. If a local
// libs/duckdb folder exists, include it so the project can reference a
// local DuckDB build instead of an external JDBC artifact.
if (file("libs/duckdb").exists()) {
    includeBuild("libs/duckdb")
    println("Including local libs/duckdb composite build")
}