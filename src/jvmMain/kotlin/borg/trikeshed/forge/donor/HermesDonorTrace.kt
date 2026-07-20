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
    fun ingestDonor(userId: String, format: String, donorPath: String): ForgeKanbanReduction {
        return when (format.lowercase()) {
            "sqlite" -> ingestSqlite(userId, Paths.get(donorPath))
            "md", "markdown" -> ingestMarkdown(userId, Paths.get(donorPath))
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

                while (rs.next()) {
                    val id = rs.getString("id")
                    val title = rs.getString("title")
                    val body = rs.getString("body")
                    val parentIds = rs.getString("parent_ids")

                    sb.append("### $id — $title\n\n")
                    if (!body.isNullOrBlank()) {
                        sb.append("$body\n\n")
                    }
                    if (!parentIds.isNullOrBlank()) {
                        val deps = parentIds.split(",").map { it.trim() }.joinToString(", ")
                        sb.append("Depends on: $deps\n\n")
                    }
                }
                sb.append("7.\n")
                sourceDescription = sb.toString()
            }
        }

        val tmp = Files.createTempFile("sqlite-donor", ".md")
        Files.writeString(tmp, sourceDescription)
        return ForgeKanbanIngest.persistMarkdown(userId, tmp.toString())
    }
}
