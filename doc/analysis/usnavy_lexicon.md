# US Navy Simplified Technical English - TrikeShed Lexicon

Each technical name keeps its name in the text.
The lexicon defines it one time, in short words.

## Curated terms

| Name | Definition |
|---|---|
| AsyncContextElement | the base element of the reactor lifecycle |
| BlackboardSurface | the projection of a JSON blackboard into rows |
| CAS | content addressable storage. The key is the hash of the bytes |
| CBOR | a binary format for data. It maps one to one onto JSON |
| CCEK | Coroutine, Context, Element, Key. The reactor object model |
| CID | content id. The SHA-256 hash of the bytes |
| CSV | a text format. Commas split the fields |
| Capability | a permission kind, such as Process or Cas |
| CasStore | the store keyed by content hash |
| ChannelRunner | the loop that turns IO events into coroutine wake-ups |
| Confix | the config reader. It parses JSON, YAML, and CBOR |
| ConfixDoc | a parsed config doc: index plus raw bytes |
| ConfixFacetPlan | the validation plan compiled from the schema |
| ConfixIndex | the flat token array of a parsed config doc |
| CoroutineContext | the coroutine scope data. Keys address elements |
| CouchStore | the document store with revisions |
| CowBPlusTree | a copy-on-write B+ tree. Pages live in the CAS |
| Cursor | a table of rows and columns |
| DTO | a data copy object. It moves bytes between layers |
| DuckDB | an embeddable SQL database for analytics |
| EvidenceCoord | raw evidence counts packed in a Long |
| FFI | the foreign function interface. It calls C code |
| Flywheel | the merge, build, and push loop |
| Forge | the user workspace app |
| ForgeAssets | the baked-in Kotlin object that holds the web shell files |
| ForgeDoc | the block tree of a Forge document |
| FunnelHashMap | a map with tiered lookup geometry |
| GraalJS | the GraalVM JavaScript engine |
| GraalVM | the Java runtime used for builds |
| HTML | the text format of a web page |
| Htx | HTTP message blocks |
| ISAM | an index file layout. Keys stay sorted on disk |
| IndexSpecId | the stable id of one index definition |
| JMH | the Java benchmark harness |
| JSON | a text format for data. Curly braces hold the fields |
| JobCommand | one job order: submit, start, complete, fail, or cancel |
| JobLog | the write-ahead log of job frames |
| JobReducer | the pure function that folds commands into snapshots |
| JobSupervisor | the element that owns the job command channel |
| Join | a pair of two values, a and b |
| K8s | Kubernetes. The container orchestrator |
| Kanban | the work board |
| LCNC | low code, no code. Users build apps without writing code |
| Liburing | the Linux async disk and socket interface |
| LinearHashMap | an open-addressing map. It uses no boxed entries |
| Litebike | the listener that opens sockets and hands bytes to CCEK |
| MVCC | many versions kept. Readers never block writers |
| MutableMap | the Kotlin read write map from the standard library |
| MuxReactorElement | the reactor that owns model cache and kanban events |
| NUID | a name token that grants permission. It has a capability, a nonce, and a subnet |
| NarseseBag | the signal store keyed by semantic hash |
| NioSupervisor | the root registry of IO services |
| Pijul | a version control system based on patches |
| ReteNetwork | the rule engine. It matches facts and emits commands |
| RowVec | one row of a Cursor. It holds a value and a meta supplier |
| SemanticSignal | one extracted fact with evidence |
| Series | a list of items. You get an item by its number |
| Series2 | a Series that stores each item as a pair |
| SharedFlow | a broadcast stream. Many collectors see each value |
| Subnet | a trust ring, from core out to global relay |
| TODO | a marker for work not yet done |
| TruthCoord | a belief score packed in a Long |
| Twin | a Join of two values of the same type |
| VFS | the virtual file system. One tree over many stores |
| WASM | the web binary target |
| Workgroup | a worker set with a scope and traits |
| YAML | a text format for config. Indent gives the structure |
| alpha | a lazy view over a Series. It changes each item when you read it |
| coroutine | a suspended unit of work |
| expectedRevision | the version check. A stale request is rejected |
| idempotencyKey | the dedupe key. First request wins |
| io_uring | the Linux async disk and socket interface |
| j | the infix operator that makes a Join |
| projection | a read view built from committed facts |
| reactor | the one event loop that runs all work |

## Discovered candidates (define or reject)

| Name | Hits | Status |
|---|---|---|
| CCEKrole | 2 | NEEDS DEFINITION |
| BikeShed | 1 | NEEDS DEFINITION |
| JDK | 1 | KEEP AS IS (acronym) |
| CE | 1 | KEEP AS IS (acronym) |
| SerializableContextual | 1 | NEEDS DEFINITION |
| AGPLv3 | 1 | NEEDS DEFINITION |
| T22T29 | 1 | NOISE (task-id or path glue) |
| Kanbanlive | 1 | NEEDS DEFINITION |
| TKANBAN | 1 | NOISE (task-id or path glue) |
| Storageunification | 1 | NEEDS DEFINITION |
| TCASPROJ | 1 | NOISE (task-id or path glue) |
| hierarchicalUI | 1 | NEEDS DEFINITION |
| Compiledout | 1 | NEEDS DEFINITION |
| FacetedCursorContract | 1 | NEEDS DEFINITION |
| MiniDuckContract | 1 | NEEDS DEFINITION |
| CircularQueuepollpeekiteratorremove | 1 | NOISE (glued code identifier) |
| srccommonMainresourcesweb | 1 | NOISE (glued code identifier) |
| Kotlininternal | 1 | NEEDS DEFINITION |
| KMPnative | 1 | NEEDS DEFINITION |
| TCASPROJ1 | 1 | NOISE (task-id or path glue) |
| JoinAB | 1 | NEEDS DEFINITION |
| libJoinkt | 1 | NEEDS DEFINITION |
| libSerieskt | 1 | NEEDS DEFINITION |
| srcREADMEmd | 1 | NEEDS DEFINITION |
| KanbanCouchsnapshots | 1 | NEEDS DEFINITION |
| FlatIndex | 1 | NEEDS DEFINITION |
| srccommonMainresourcesconfixjobnexusschemajson | 1 | NOISE (glued code identifier) |
| HeadChanges | 1 | NEEDS DEFINITION |
| CIDderived | 1 | NEEDS DEFINITION |
| MultiIndex | 1 | NEEDS DEFINITION |
| IO | 1 | KEEP AS IS (acronym) |
| Systemjava | 1 | NEEDS DEFINITION |
| withContextDispatchersIO | 1 | NOISE (glued code identifier) |
| waitFortimeout | 1 | NEEDS DEFINITION |
| Todo | 1 | NEEDS DEFINITION |
| GitHub | 1 | NEEDS DEFINITION |
| 200KB | 1 | NEEDS DEFINITION |
| BlackboardasConfixcursor | 1 | NOISE (glued code identifier) |
| confixDocjson | 1 | NEEDS DEFINITION |
| UI | 1 | KEEP AS IS (acronym) |
| LcncEntitySurface | 1 | NEEDS DEFINITION |
| CausalGraphNodeIndex | 1 | NEEDS DEFINITION |
| BlackboardSurfaceRows | 1 | NEEDS DEFINITION |
| ManimWM | 1 | NEEDS DEFINITION |
| 25D3D | 1 | NEEDS DEFINITION |
| ForgeBlackboardCamera | 1 | NEEDS DEFINITION |
| 25D | 1 | NEEDS DEFINITION |
| ForgeBlackboard3D | 1 | NEEDS DEFINITION |
| 3D | 1 | NEEDS DEFINITION |
| ArticulatedNode | 1 | NEEDS DEFINITION |
| Longlived | 1 | NEEDS DEFINITION |
| NUIDCapability | 1 | NEEDS DEFINITION |
| includeBuild | 1 | NEEDS DEFINITION |
| TrikeShedlocal | 1 | NEEDS DEFINITION |
| Precommit | 1 | NEEDS DEFINITION |
| HTMLWASM | 1 | NEEDS DEFINITION |
| PR | 1 | KEEP AS IS (acronym) |
| IsamVolume | 1 | NEEDS DEFINITION |
| MingwIsamOperations | 1 | NEEDS DEFINITION |
