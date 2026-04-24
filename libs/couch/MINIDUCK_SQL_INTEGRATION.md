MiniDuck SQL integration — quick reference

Supported SQL subset (MVP)
- SELECT <expr>[, ...] FROM <table> [WHERE <predicate>] [LIMIT <n>]
- Expr: column refs, string/numeric literals, binary arithmetic ops (+,-,*,/), comparisons (=, !=, <, >, <=, >=), LIKE
- Predicate: AND / OR composed comparisons
- No JOINs, GROUP BY, ORDER BY or aggregates yet

How to use
1) Create storage+schema surfaces:
   val db = LsmrDatabase(LsmrConfig(path = "", memtableThreshold = 1024))
   val schema = LsmrSchemaManager(db)
   val source = LsmrTableSource(db)
   // seed data for tests/examples: source.seedRows("users", rows)

2) Execution (convenience):
   val execCtx = ExecutionContext(schema, PlannerConfig(), source)
   val cursor = SqlExecutor.execute(scope, "SELECT id, name FROM users WHERE id = 2", execCtx)
   while (cursor.next()) { val id = cursor.row.get("id") }
   cursor.close()

Notes
- PlannerConfig.autoCreateSchema defaults to true; missing columns/tables may be created via SchemaManager.ensureColumns().
- LsmrSchemaManager stores schema at key: miniduck:schema:{table}
- LsmrTableSource stores rows at keys: miniduck:table:{table}:row:{i} and keeps count at miniduck:table:{table}:count

Next work
- Harden serializers for Row encoding (currently string/primitive-friendly simple encoding)
- Isolate integration tests or fix pre-existing failing commonTest files so the integration test can run under the standard Gradle test lifecycle
- Expand SQL subset and lower more operations to LSMR-backed scans
