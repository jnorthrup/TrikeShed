package borg.trikeshed.forge.donor

import borg.trikeshed.kanban.ForgeBoardPersistence
import borg.trikeshed.kanban.ForgeKanbanIngest
import borg.trikeshed.kanban.ForgeKanbanReduction
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.sqlite.SQLiteDataSource
import java.sql.Connection
import java.sql.ResultSet

object HermesDonorTrace {
    fun ingestDonor(userId: String, format: String, donorPath: String? = null): ForgeKanbanReduction {
        return when (format.lowercase()) {
            "sqlite" -> {
                val path = if (donorPath == null) {
                    val userHome = System.getProperty("user.home")
                    Paths.get(userHome, ".hermes", "hermes-agent", "hermes_core", "kanban.db")
                } else {
                    Paths.get(donorPath)
                }
                
                // Fallback to older `.hermes/kanban.db` if the new deep path doesn't exist but the old one does
                val finalPath = if (!Files.exists(path) && donorPath == null) {
                    val fallbackPath = Paths.get(System.getProperty("user.home"), ".hermes", "kanban.db")
                    if (Files.exists(fallbackPath)) fallbackPath else path
                } else path

                ingestSqlite(userId, finalPath)
            }
            "md", "markdown" -> {
                requireNotNull(donorPath) { "donorPath is required for markdown format" }
                ingestMarkdown(userId, Paths.get(donorPath))
            }
            else -> throw IllegalArgumentException("Unknown donor format: $format. Use 'md' or 'sqlite'.")
        }
    }

    private fun ingestMarkdown(userId: String, donorPath: Path): ForgeKanbanReduction {
        val ingestPath = if (borg.trikeshed.kanban.JvmTikaIngestAdapter.isTikaCandidate(donorPath)) {
            val md = borg.trikeshed.kanban.JvmTikaIngestAdapter.extractToMarkdown(donorPath)
            val tmp = Files.createTempFile("tika-donor", ".md")
            Files.writeString(tmp, md)
            tmp.toString()
        } else {
            donorPath.toString()
        }
        return ForgeKanbanIngest.persistMarkdown(userId, ingestPath)
    }

    private fun ingestSqlite(userId: String, dbPath: Path): ForgeKanbanReduction {
        require(Files.exists(dbPath)) { "SQLite donor db not found at: $dbPath" }

        val dataSource = SQLiteDataSource().apply {
            url = "jdbc:sqlite:${dbPath.toAbsolutePath()}"
        }

        var sourceDescription = ""
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT id, title, body, status, parent_ids FROM tasks ORDER BY id ASC")
                val sb = java.lang.StringBuilder()
                sb.append("TARGET: SQLite Donor Replay\n\n6. Work packages\n\n")

                var hasWorkPackages = false
                while (rs.next()) {
                    hasWorkPackages = true
                    val id = rs.getString("id")
                    val title = rs.getString("title")
                    val body = rs.getString("body")
                    val parentIds = rs.getString("parent_ids")

                    // id needs to match "^([A-Z][0-9]+)$"
                    val formattedId = if (id.matches(Regex("^[A-Z][0-9]+$"))) id else {
                        // try to extract an ID from something like TASK-1 -> T1
                        val match = Regex(".*?([A-Z]+)[^0-9]*([0-9]+).*?").find(id)
                        if (match != null) {
                           match.groupValues[1].take(1) + match.groupValues[2]
                        } else {
                           "T${id.hashCode().toUInt() % 1000u}"
                        }
                    }

                    sb.append("$formattedId — $title\n\n")
                    if (!body.isNullOrBlank()) {
                        sb.append("$body\n\n")
                    }
                    if (!parentIds.isNullOrBlank()) {
                        val deps = parentIds.split(",").mapNotNull { parentIdStr -> 
                             val parentId = parentIdStr.trim()
                             if (parentId.matches(Regex("^[A-Z][0-9]+$"))) parentId else {
                                val match = Regex(".*?([A-Z]+)[^0-9]*([0-9]+).*?").find(parentId)
                                if (match != null) {
                                   match.groupValues[1].take(1) + match.groupValues[2]
                                } else null
                             }
                        }.joinToString(", ")
                        if (deps.isNotBlank()) {
                            sb.append("Depends on: $deps\n\n")
                        }
                    }
                }
                sb.append("7. \n")
                
                // Even if no tasks exist, provide a dummy task so parser doesn't fail
                if (!hasWorkPackages) {
                     sb.append("D0 — Dummy Task\n\nDummy task to prevent empty work package crash\n\n")
                }
                sourceDescription = sb.toString()
            }
        }

        val tmp = Files.createTempFile("sqlite-donor", ".md")
        Files.writeString(tmp, sourceDescription)
        return ForgeKanbanIngest.persistMarkdown(userId, tmp.toString())
    }
}
