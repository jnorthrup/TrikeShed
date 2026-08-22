with open("src/jvmMain/kotlin/borg/trikeshed/forge/server/KanbanHttpServerJvm.kt", "r") as f:
    code = f.read()

patch = """    suspend fun run(port: Int, donorPath: String?) {
        // Out-of-scope finding: JvmKanbanServer binds the port internally and its HTTP routing 
        // logic is closed for extension. It is impossible to intercept or inject BlackboardWire
        // routes without modifying JvmKanbanServer.kt (which violates the strict Owns constraint).
        // Instantiating BlackboardWire here as requested, but it cannot be wired to the port.
        val wire = BlackboardWire(
            borg.trikeshed.graal.ConfixBlackboard.empty(), 
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        )
        
        // Delegates to canonical Litebike Kanban server boundary
        JvmKanbanServer().run(port = port, donorPath = donorPath)
    }"""
    
code = code.replace("""    suspend fun run(port: Int, donorPath: String?) {
        // Delegates to canonical Litebike Kanban server boundary
        JvmKanbanServer().run(port = port, donorPath = donorPath)
    }""", patch)

with open("src/jvmMain/kotlin/borg/trikeshed/forge/server/KanbanHttpServerJvm.kt", "w") as f:
    f.write(code)

