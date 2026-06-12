package borg.trikeshed.forge.cli

import borg.trikeshed.forge.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/**
 * Forge CLI - Command line interface for Forge workspace
 */
class ForgeCli(private val workspace: ForgeWorkspace) {

    private val json = Json { prettyPrint = true }

    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            printHelp()
            return
        }

        runBlocking {
            when (args[0]) {
                "init" -> cmdInit()
                "file" -> cmdFile(args.drop(1))
                "snapshot" -> cmdSnapshot(args.drop(1))
                "prompt" -> cmdPrompt(args.drop(1))
                "workflow" -> cmdWorkflow(args.drop(1))
                "run" -> cmdRun(args.drop(1))
                "artifact" -> cmdArtifact(args.drop(1))
                "collab" -> cmdCollab(args.drop(1))
                "export" -> cmdExport(args.drop(1))
                else -> {
                    println("Unknown command: ${args[0]}")
                    printHelp()
                }
            }
        }
    }

    private fun printHelp() {
        println("""
            Forge CLI - Autonomous Financial Workflow Fabric
            
            Commands:
              init                    Initialize workspace
              file <subcommand>       File operations (put, get, list, search, delete)
              snapshot <subcommand>   Snapshot operations (create, list, restore, diff, branch, merge)
              prompt <subcommand>     Prompt operations (save, get, list, search, delete)
              workflow <subcommand>   Workflow operations (save, get, list, search, delete)
              run <workflow-id>       Execute workflow with inputs
              artifact <subcommand>   Artifact operations (create, get, list, export, import)
              collab <subcommand>     Collaboration (join, leave, users, events)
              export <artifact-id>    Export artifact
              help                    Show this help
            
            Examples:
              forge init
              forge file put --path notes/ach.md --content "# ACH Rules" --mime text/markdown
              forge file list
              forge snapshot create "initial commit"
              forge prompt save --name summarize --template "Summarize: {{text}}" --param text:string:"Text to summarize"
              forge workflow save --name ach-exception --steps @workflow.json
              forge run ach-exception '{"input": "transaction-123"}'
              forge artifact create --name "ACH Report" --files @files.json
              forge collab join --name "Alice" --color "#ff0000"
            """.trimIndent())
    }

    // =========================================================================
    // Init
    // =========================================================================
    private suspend fun cmdInit() {
        println("Initializing Forge workspace...")
        val snap = workspace.snapshot("initial commit")
        println("Created initial snapshot: ${snap.id.value}")
        println("Workspace ready!")
    }

    // =========================================================================
    // File commands
    // =========================================================================
    private suspend fun cmdFile(args: Array<String>) {
        if (args.isEmpty()) {
            println("Usage: forge file <put|get|list|search|delete> [options]")
            return
        }
        when (args[0]) {
            "put" -> cmdFilePut(args.drop(1))
            "get" -> cmdFileGet(args.drop(1))
            "list" -> cmdFileList()
            "search" -> cmdFileSearch(args.drop(1))
            "delete" -> cmdFileDelete(args.drop(1))
            else -> println("Unknown file command: ${args[0]}")
        }
    }

    private suspend fun cmdFilePut(args: Array<String>) {
        var path = ""
        var content = ""
        var mime = "text/markdown"
        var id: String? = null

        args.forEachIndexed { i, arg ->
            when (arg) {
                "--path", "-p" -> path = args[i + 1]
                "--content", "-c" -> content = args[i + 1]
                "--mime", "-m" -> mime = args[i + 1]
                "--id" -> id = args[i + 1]
            }
        }

        if (path.isEmpty() || content.isEmpty()) {
            println("Usage: forge file put --path <path> --content <content> [--mime <mime>] [--id <id>]")
            return
        }

        val file = ForgeFile(
            id = id?.let { ForgeFileId(it) } ?: ForgeFileId.generate(),
            path = path,
            content = content,
            mimeType = mime
        )
        val stored = workspace.put(file)
        println("File stored: ${stored.id.value} (${stored.path})")
    }

    private suspend fun cmdFileGet(args: Array<String>) {
        if (args.isEmpty()) {
            println("Usage: forge file get <id>")
            return
        }
        val file = workspace.get(ForgeFileId(args[0]))
        if (file != null) {
            println(json.encodeToString(file))
        } else {
            println("File not found: ${args[0]}")
        }
    }

    private suspend fun cmdFileList() {
        val files = workspace.list()
        if (files.isEmpty()) {
            println("No files in workspace")
            return
        }
        println("Files (${files.size}):")
        files.forEach { (id, file) ->
            println("  ${id.value}  ${file.path}  (${file.mimeType}, ${file.content.length} chars)")
        }
    }

    private suspend fun cmdFileSearch(args: Array<String>) {
        if (args.isEmpty()) {
            println("Usage: forge file search <query>")
            return
        }
        val results = workspace.search(args.joinToString(" "))
        println("Found ${results.size} matches:")
        results.forEach { file ->
            println("  ${file.id.value}  ${file.path}")
        }
    }

    private suspend fun cmdFileDelete(args: Array<String>) {
        if (args.isEmpty()) {
            println("Usage: forge file delete <id>")
            return
        }
        val deleted = workspace.delete(ForgeFileId(args[0]))
        println(if (deleted) "File deleted" else "File not found")
    }

    // =========================================================================
    // Snapshot commands
    // =========================================================================
    private suspend fun cmdSnapshot(args: Array<String>) {
        if (args.isEmpty()) {
            println("Usage: forge snapshot <create|list|restore|diff|branch|merge> [options]")
            return
        }
        when (args[0]) {
            "create" -> cmdSnapshotCreate(args.drop(1))
            "list" -> cmdSnapshotList()
            "restore" -> cmdSnapshotRestore(args.drop(1))
            "diff" -> cmdSnapshotDiff(args.drop(1))
            "branch" -> cmdSnapshotBranch(args.drop(1))
            "merge" -> cmdSnapshotMerge(args.drop(1))
            else -> println("Unknown snapshot command: ${args[0]}")
        }
    }

    private suspend fun cmdSnapshotCreate(args: Array<String>) {
        var message = "checkpoint"
        var tags = emptySet<String>()

        args.forEachIndexed { i, arg ->
            when (arg) {
                "--message", "-m" -> message = args[i + 1]
                "--tags", "-t" -> tags = args[i + 1].split(",").toSet()
            }
        }

        val snap = workspace.snapshot(message, tags)
        println("Snapshot created: ${snap.id.value}")
        println("  Message: ${snap.message}")
        println("  Files: ${snap.files.size}")
    }

    private suspend fun cmdSnapshotList() {
        val history = workspace.history()
        if (history.isEmpty()) {
            println("No snapshots")
            return
        }
        println("Snapshots (${history.size}):")
        history.forEach { snap ->
            println("  ${snap.id.value}  ${snap.timestamp}  ${snap.message}  [${snap.tags.joinToString(",")}]")
        }
    }

    private suspend fun cmdSnapshotRestore(args: Array<String>) {
        if (args.isEmpty()) {
            println("Usage: forge snapshot restore <id>")
            return
        }
        val snap = workspace.restore(ForgeSnapshotId(args[0]))
        println("Restored to snapshot: ${snap.id.value}")
    }

    private suspend fun cmdSnapshotDiff(args: Array<String>) {
        if (args.size < 2) {
            println("Usage: forge snapshot diff <from-id> <to-id>")
            return
        }
        val diff = workspace.diff(ForgeSnapshotId(args[0]), ForgeSnapshotId(args[1]))
        println("Diff ${args[0]} -> ${args[1]}:")
        println("  Added: ${diff.addedFiles.size}")
        println("  Removed: ${diff.removedFiles.size}")
        println("  Modified: ${diff.modifiedFiles.size}")
        println("  Unchanged: ${diff.unchangedFiles.size}")
    }

    private suspend fun cmdSnapshotBranch(args: Array<String>) {
        if (args.isEmpty()) {
            println("Usage: forge snapshot branch <base-id> <name>")
            return
        }
        val branch = workspace.branch(ForgeSnapshotId(args[0]), args[1])
        println("Branch created: ${branch.id.value} (from ${branch.parentId?.value})")
    }

    private suspend fun cmdSnapshotMerge(args: Array<String>) {
        if (args.size < 3) {
            println("Usage: forge snapshot merge <source-id> <target-id> <message>")
            return
        }
        val merged = workspace.merge(ForgeSnapshotId(args[0]), ForgeSnapshotId(args[1]), args[2])
        println("Merged: ${merged.id.value}")
    }

    // =========================================================================
    // Prompt commands
    // =========================================================================
    private suspend fun cmdPrompt(args: Array<String>) {
        if (args.isEmpty()) {
            println("Usage: forge prompt <save|get|list|search|delete> [options]")
            return
        }
        when (args[0]) {
            "save" -> cmdPromptSave(args.drop(1))
            "get" -> cmdPromptGet(args.drop(1))
            "list" -> cmdPromptList()
            "search" -> cmdPromptSearch(args.drop(1))
            "delete" -> cmdPromptDelete(args.drop(1))
            else -> println("Unknown prompt command: ${args[0]}")
        }
    }

    private suspend fun cmdPromptSave(args: Array<String>) {
        var name = ""
        var template = ""
        var params = mutableMapOf<String, PromptParameter>()

        args.forEachIndexed { i, arg ->
            when (arg) {
                "--name", "-n" -> name = args[i + 1]
                "--template", "-t" -> template = args[i + 1]
                "--param", "-p" -> {
                    val parts = args[i + 1].split(":")
                    if (parts.size >= 3) {
                        params[parts[0]] = PromptParameter(parts[0], parts[1], parts[2])
                    }
                }
            }
        }

        if (name.isEmpty() || template.isEmpty()) {
            println("Usage: forge prompt save --name <name> --template <template> [--param name:type:desc ...]")
            return
        }

        val prompt = ForgePrompt(
            id = ForgePromptId.generate(),
            name = name,
            template = template,
            parameters = params
        )
        workspace.putPrompt(prompt)
        println("Prompt saved: ${prompt.id.value} (${prompt.name})")
    }

    private suspend fun cmdPromptGet(args: Array<String>) {
        if (args.isEmpty()) {
            println("Usage: forge prompt get <id>")
            return
        }
        val prompt = workspace.getPrompt(ForgePromptId(args[0]))
        if (prompt != null) println(json.encodeToString(prompt))
        else println("Prompt not found")
    }

    private suspend fun cmdPromptList() {
        val prompts = workspace.listPrompts()
        println("Prompts (${prompts.size}):")
        prompts.forEach { p -> println("  ${p.id.value}  ${p.name}") }
    }

    private suspend fun cmdPromptSearch(args: Array<String>) {
        if (args.isEmpty()) {
            println("Usage: forge prompt search <query>")
            return
        }
        val results = workspace.searchPrompts(args.joinToString(" "))
        println("Found ${results.size} prompts:")
        results.forEach { p -> println("  ${p.id.value}  ${p.name}") }
    }

    private suspend fun cmdPromptDelete(args: Array<String>) {
        if (args.isEmpty()) {
            println("Usage: forge prompt delete <id>")
            return
        }
        val deleted = workspace.deletePrompt(ForgePromptId(args[0]))
        println(if (deleted) "Prompt deleted" else "Prompt not found")
    }

    // =========================================================================
    // Workflow commands
    // =========================================================================
    private suspend fun cmdWorkflow(args: Array<String>) {
        if (args.isEmpty()) {
            println("Usage: forge workflow <save|get|list|search|delete> [options]")
            return
        }
        when (args[0]) {
            "save" -> cmdWorkflowSave(args.drop(1))
            "get" -> cmdWorkflowGet(args.drop(1))
            "list" -> cmdWorkflowList()
            "search" -> cmdWorkflowSearch(args.drop(1))
            "delete" -> cmdWorkflowDelete(args.drop(1))
            else -> println("Unknown workflow command: ${args[0]}")
        }
    }

    private suspend fun cmdWorkflowSave(args: Array<String>) {
        var name = ""
        var stepsJson = ""

        args.forEachIndexed { i, arg ->
            when (arg) {
                "--name", "-n" -> name = args[i + 1]
                "--steps", "-s" -> stepsJson = args[i + 1]
            }
        }

        if (name.isEmpty() || stepsJson.isEmpty()) {
            println("Usage: forge workflow save --name <name> --steps <json-file-or-inline>")
            return
        }

        val steps = json.decodeFromString<List<WorkflowStep>>(stepsJson)
        val workflow = ForgeWorkflow(
            id = ForgeWorkflowId.generate(),
            name = name,
            steps = steps,
            inputSchema = mapOf("input" to "string"),
            outputSchema = mapOf("output" to "string")
        )
        workspace.putWorkflow(workflow)
        println("Workflow saved: ${workflow.id.value} (${workflow.name})")
    }

    private suspend fun cmdWorkflowGet(args: Array<String>) {
        if (args.isEmpty()) {
            println("Usage: forge workflow get <id>")
            return
        }
        val wf = workspace.getWorkflow(ForgeWorkflowId(args[0]))
        if (wf != null) println(json.encodeToString(wf))
        else println("Workflow not found")
    }

    private suspend fun cmdWorkflowList() {
        val workflows = workspace.listWorkflows()
        println("Workflows (${workflows.size}):")
        workflows.forEach { w -> println("  ${w.id.value}  ${w.name}") }
    }

    private suspend fun cmdWorkflowSearch(args: Array<String>) {
        if (args.isEmpty()) {
            println("Usage: forge workflow search <query>")
            return
        }
        val results = workspace.searchWorkflows(args.joinToString(" "))
        println("Found ${results.size} workflows:")
        results.forEach { w -> println("  ${w.id.value}  ${w.name}") }
    }

    private suspend fun cmdWorkflowDelete(args: Array<String>) {
        if (args.isEmpty()) {
            println("Usage: forge workflow delete <id>")
            return
        }
        val deleted = workspace.deleteWorkflow(ForgeWorkflowId(args[0]))
        println(if (deleted) "Workflow deleted" else "Workflow not found")
    }

    // =========================================================================
    // Run command
    // =========================================================================
    private suspend fun cmdRun(args: Array<String>) {
        if (args.isEmpty()) {
            println("Usage: forge run <workflow-id> [inputs-json]")
            return
        }
        val workflowId = ForgeWorkflowId(args[0])
        val inputs = if (args.size > 1) json.decodeFromString<Map<String, String>>(args[1]) else emptyMap()

        println("Executing workflow: ${workflowId.value}...")
        val result = workspace.executeSync(workflowId, inputs)
        println("Status: ${result.status}")
        println("Execution: ${result.executionId.value}")
        println("Outputs: ${json.encodeToString(result.finalOutputs)}")
    }

    // =========================================================================
    // Artifact commands
    // =========================================================================
    private suspend fun cmdArtifact(args: Array<String>) {
        if (args.isEmpty()) {
            println("Usage: forge artifact <create|get|list|export|import> [options]")
            return
        }
        when (args[0]) {
            "create" -> cmdArtifactCreate(args.drop(1))
            "get" -> cmdArtifactGet(args.drop(1))
            "list" -> cmdArtifactList()
            else -> println("Unknown artifact command: ${args[0]}")
        }
    }

    private suspend fun cmdArtifactCreate(args: Array<String>) {
        var name = ""
        var description = ""
        var fileIds = mutableListOf<ForgeFileId>()

        args.forEachIndexed { i, arg ->
            when (arg) {
                "--name", "-n" -> name = args[i + 1]
                "--description", "-d" -> description = args[i + 1]
                "--files", "-f" -> fileIds = args[i + 1].split(",").map { ForgeFileId(it) }
            }
        }

        if (name.isEmpty()) {
            println("Usage: forge artifact create --name <name> [--description <desc>] [--files <id1,id2,...>]")
            return
        }

        val files = fileIds.mapNotNull { workspace.get(it) }
        val artifact = workspace.artifact(name, description, files, null, null)
        println("Artifact created: ${artifact.id.value} (${artifact.name})")
    }

    private suspend fun cmdArtifactGet(args: Array<String>) {
        if (args.isEmpty()) {
            println("Usage: forge artifact get <id>")
            return
        }
        val artifact = workspace.getArtifact(ForgeArtifactId(args[0]))
        if (artifact != null) println(json.encodeToString(artifact))
        else println("Artifact not found")
    }

    private suspend fun cmdArtifactList() {
        val artifacts = workspace.listArtifacts(false)
        println("Artifacts (${artifacts.size}):")
        artifacts.forEach { a -> println("  ${a.id.value}  ${a.name}") }
    }

    // =========================================================================
    // Collaboration commands
    // =========================================================================
    private suspend fun cmdCollab(args: Array<String>) {
        if (args.isEmpty()) {
            println("Usage: forge collab <join|leave|users|events> [options]")
            return
        }
        when (args[0]) {
            "join" -> cmdCollabJoin(args.drop(1))
            "leave" -> cmdCollabLeave(args.drop(1))
            "users" -> cmdCollabUsers()
            "events" -> cmdCollabEvents()
            else -> println("Unknown collab command: ${args[0]}")
        }
    }

    private suspend fun cmdCollabJoin(args: Array<String>) {
        var name = "User"
        var color = "#${(0..5).map { "0123456789abcdef".random() }.joinToString("")}"

        args.forEachIndexed { i, arg ->
            when (arg) {
                "--name", "-n" -> name = args[i + 1]
                "--color", "-c" -> color = args[i + 1]
            }
        }

        val user = ForgeUser(ForgeUserId.generate(), name, color)
        workspace.join(user)
        println("Joined as: ${user.name} (${user.id.value})")
    }

    private suspend fun cmdCollabLeave(args: Array<String>) {
        if (args.isEmpty()) {
            println("Usage: forge collab leave <user-id>")
            return
        }
        workspace.leave(ForgeUserId(args[0]))
        println("Left")
    }

    private suspend fun cmdCollabUsers() {
        val users = workspace.users()
        println("Active users (${users.size}):")
        users.forEach { u -> println("  ${u.id.value}  ${u.name}  ${u.color}") }
    }

    private suspend fun cmdCollabEvents() {
        println("Listening for events (Ctrl+C to stop)...")
        workspace.events().collect { event ->
            println("[${java.time.Instant.now()}] ${event}")
        }
    }

    // =========================================================================
    // Export command
    // =========================================================================
    private suspend fun cmdExport(args: Array<String>) {
        if (args.isEmpty()) {
            println("Usage: forge export <artifact-id> [format=JSON]")
            return
        }
        val format = if (args.size > 1) ExportFormat.valueOf(args[1].uppercase()) else ExportFormat.JSON
        val bundle = workspace.export(ForgeArtifactId(args[0]), format)
        println("Exported artifact: ${bundle.manifest.artifactName}")
        println("Format: ${bundle.format}")
        println("Size: ${bundle.data.size} bytes")
    }
}