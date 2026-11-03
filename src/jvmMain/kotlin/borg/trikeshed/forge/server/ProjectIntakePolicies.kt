package borg.trikeshed.forge.server

import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.ontology.CorpusOntology
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.file.spi.StorageDurability
import java.io.File

/** Intake policy only: direct joins, queries and existing members are outside this setting. */
internal class ProjectIntakePolicies(
    private val ledger: File?,
    private val files: FileOperations,
    private val cas: CasStore,
    private val lock: Any,
) {
    private val policies = HashMap<String, CurationIntake.Mode>()
    private var loadFailure: String? = null
    private val guardRoot = ledger?.parentFile?.let { File(it, "project-intake-policy") }

    private fun guard(project: String): File = File(requireNotNull(guardRoot) { "Policy guard requires a ledger" }, "$project.guard")
    private fun rowDigest(row: String): String = ContentId.of(row.encodeToByteArray()).value

    init {
        synchronized(lock) {
            try {
                val rows = HashMap<String, String>()
                ledger?.takeIf { it.exists() }?.forEachLine { line ->
                    val parts = line.split('\t')
                    val name = parts.firstOrNull().orEmpty()
                    if (name.isBlank()) {
                        loadFailure = "Project ledger has a row without a name"
                    } else if (name in policies) {
                        policies[name] = CurationIntake.Mode.Blocked("Duplicate project ledger rows")
                    } else {
                        rows[name] = line
                        policies[name] = decode(parts)
                    }
                }
                val root = guardRoot
                if (root != null && root.exists()) {
                    val guards = requireNotNull(root.listFiles()) { "Cannot list policy guards" }
                    for (file in guards.filter { it.name.endsWith(".guard") }) {
                        val name = file.name.removeSuffix(".guard")
                        val row = rows[name]
                        val expected = row?.let { "stable\t${rowDigest(it)}" }
                        if (name.isBlank() || row == null || row.split('\t').size < 5 ||
                            !file.isFile || file.readText() != expected) {
                            if (name.isBlank()) loadFailure = "Invalid project policy guard"
                            else policies[name] = CurationIntake.Mode.Blocked("Project policy guard is pending or does not match its ledger row")
                        }
                    }
                }
                for ((name, row) in rows) {
                    if (row.split('\t').size >= 5 && !guard(name).isFile)
                        policies[name] = CurationIntake.Mode.Blocked("Project policy guard is missing")
                }
            } catch (e: Exception) {
                loadFailure = "Project ledger cannot be read: ${e.message ?: e}"
            }
        }
    }

    private fun decode(parts: List<String>): CurationIntake.Mode {
        if (parts.size < 3 || parts.size > 6) return CurationIntake.Mode.Blocked("Malformed project ledger row")
        if (parts.size <= 4) return CurationIntake.Mode.Legacy
        return try {
            when (parts[4]) {
                "legacy", "manual" -> {
                    require(parts.size == 5 || parts[5].isBlank()) { "Unexpected ontology for this intake policy" }
                    if (parts[4] == "manual") CurationIntake.Mode.Manual else CurationIntake.Mode.Legacy
                }
                "review-required" -> {
                    require(parts.size == 6 && parts[5].isNotBlank()) { "Review ontology CID is missing" }
                    require(files.durability == StorageDurability.DURABLE) { "Project policy storage is not durable" }
                    require(cas.durability == StorageDurability.DURABLE) { "Review ontology storage is not durable" }
                    val cid = ContentId(parts[5])
                    CorpusOntology.read(cas, cid)
                    CurationIntake.Mode.ReviewRequired(cid)
                }
                else -> error("Unknown intake policy")
            }
        } catch (e: Exception) {
            CurationIntake.Mode.Blocked(e.message ?: "Invalid intake policy")
        }
    }

    fun mode(project: String): CurationIntake.Mode = synchronized(lock) {
        loadFailure?.let { CurationIntake.Mode.Blocked(it) } ?: policies[project] ?: CurationIntake.Mode.Legacy
    }

    /** Preserve a tombstone so name reuse or a crash cannot turn a guarded row into legacy. */
    fun retire(project: String) = synchronized(lock) {
        val marker = guardRoot?.let { File(it, "$project.guard") }
        if (marker?.exists() == true || policies[project] is CurationIntake.Mode.ReviewRequired ||
            policies[project] is CurationIntake.Mode.Blocked)
            policies[project] = CurationIntake.Mode.Blocked("Project policy must be configured after remount")
        else policies.remove(project)
        Unit
    }

    /** A failed registry retirement requires explicit recovery even after a restart. */
    fun markRecovery(project: String) = synchronized(lock) {
        policies[project] = CurationIntake.Mode.Blocked("Project registry recovery is required")
        require(project.matches(Regex("[a-z0-9._-]+")) && project != "." && project != "..") { "Invalid project policy name" }
        require(files.durability == StorageDurability.DURABLE) { "Project recovery marker storage is not durable" }
        files.writeAtomically(guard(project).absolutePath, "recovery".encodeToByteArray())
    }

    /** Publish configuration before acknowledging it. A failed/uncertain write blocks this project. */
    fun set(project: String, selected: CurationIntake.Mode) = synchronized(lock) {
        require(selected !is CurationIntake.Mode.Blocked) { "Blocked is a state, not a selectable policy" }
        require(project.matches(Regex("[a-z0-9._-]+")) && project != "." && project != "..") { "Invalid project policy name" }
        check(loadFailure == null) { "Project ledger must be repaired: $loadFailure" }
        val target = requireNotNull(ledger) { "Project policy requires a persistent project ledger" }
        require(files.durability == StorageDurability.DURABLE) { "Project policy storage is not durable" }
        if (selected is CurationIntake.Mode.ReviewRequired) {
            require(cas.durability == StorageDurability.DURABLE) { "Review ontology storage is not durable" }
            CorpusOntology.read(cas, selected.ontologyCid)
            cas.sync()
        }
        policies[project] = CurationIntake.Mode.Blocked("Project policy update is not yet durable")
        try {
            val marker = guard(project)
            // Pending must reach durable storage before the ledger is replaced.
            files.writeAtomically(marker.absolutePath, "pending".encodeToByteArray())
            val lines = target.readLines()
            val matching = lines.filter { it.substringBefore('\t') == project }
            require(matching.size == 1) { "Project must have exactly one persisted mount record" }
            val parts = matching.single().split('\t')
            require(parts.size in 3..6) { "Malformed project ledger row" }
            val fields = parts.take(4).toMutableList()
            while (fields.size < 4) fields.add("")
            when (selected) {
                CurationIntake.Mode.Legacy -> fields.add("legacy")
                CurationIntake.Mode.Manual -> fields.add("manual")
                is CurationIntake.Mode.ReviewRequired -> { fields.add("review-required"); fields.add(selected.ontologyCid.value) }
                is CurationIntake.Mode.Blocked -> error("Blocked policy cannot be selected")
            }
            val newRow = fields.joinToString("\t")
            val updated = lines.map { if (it.substringBefore('\t') == project) newRow else it }
                .joinToString(separator = "\n", postfix = "\n")
            files.writeAtomically(target.absolutePath, updated.encodeToByteArray())
            files.writeAtomically(marker.absolutePath, "stable\t${rowDigest(newRow)}".encodeToByteArray())
            policies[project] = selected
        } catch (e: Throwable) {
            policies[project] = CurationIntake.Mode.Blocked("Project policy publication failed: ${e.message ?: e}")
            throw e
        }
    }
}
