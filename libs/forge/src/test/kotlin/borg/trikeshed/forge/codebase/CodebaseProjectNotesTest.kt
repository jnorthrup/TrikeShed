package borg.trikeshed.forge.codebase

import borg.trikeshed.forge.notion.NotionBlockKind
import borg.trikeshed.userspace.reactor.KanbanFSM
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CodebaseProjectNotesTest {

    @BeforeEach
    fun resetFsm() {
        KanbanFSM.reset()
    }

    @Test
    fun `render emits real notes for a generic software project`() {
        val induction = sampleInduction()

        val state = CodebaseProjectNotes.render(induction)
        assertEquals("acme-app project notes", state.block(state.rootPageId)?.text)

        val children = state.requireBlock(state.rootPageId).children
            .map(state::requireBlock)

        val quote = children.first { it.kind == NotionBlockKind.QUOTE }
        assertTrue(quote.text.contains("/workspace/acme-app"))
        assertTrue(quote.text.contains("2 services"))

        val serviceHeadings = children.filter { it.kind == NotionBlockKind.HEADING_2 }
        assertEquals(listOf("api", "worker"), serviceHeadings.map { it.text })
        assertEquals("TYPESCRIPT", serviceHeadings.first().properties["service.language"])
        assertEquals("ENGINE", serviceHeadings.first().properties["service.role"])

        val bullets = children.filter { it.kind == NotionBlockKind.BULLET }
        assertTrue(bullets.any { it.text == "manifest: /workspace/acme-app/package.json" })
        assertTrue(bullets.any { it.text.contains("2 entrypoints") })
        assertTrue(bullets.any { it.text.contains("1 classpath roots") })
    }

    @Test
    fun `notes export to a markdown forge file`() {
        val file = CodebaseProjectNotes.asForgeFile(sampleInduction())
        assertEquals("notion/acme-app-project-notes.md", file.path)
        assertTrue(file.content.contains("# acme-app project notes"))
        assertTrue(file.content.contains("## api"))
        assertTrue(file.content.contains("- build: npm run build"))
    }

    @Test
    fun `notes roll through notion bridge into kanban state`() {
        val reduced = CodebaseProjectNotes.toKanbanState(sampleInduction())
        assertEquals(7, reduced.taxonomyNodeCount)
        assertEquals("TaxonomyNodeCreated", reduced.lastEventKind)
        assertTrue(reduced.recentTaxonomyNodes.contains("api"))
        assertTrue(reduced.recentTaxonomyNodes.contains("worker"))
    }

    private fun sampleInduction(): CodebaseInduction {
        val services = listOf(
            CodebaseService(
                name = "api",
                language = CodebaseLanguage.TYPESCRIPT,
                manifestPath = "/workspace/acme-app/package.json",
                buildCommand = "npm run build",
                classpath = listOf("/workspace/acme-app/src/index.ts"),
                entryPoints = listOf("src/index.ts", "src/routes.ts"),
                role = ServiceRole.ENGINE,
            ),
            CodebaseService(
                name = "worker",
                language = CodebaseLanguage.KOTLIN_JVM,
                manifestPath = "/workspace/acme-app/worker/pom.xml",
                buildCommand = "mvn -q -DskipTests package",
                classpath = listOf("/workspace/acme-app/worker/src/main/kotlin/Worker.kt"),
                entryPoints = listOf("com.acme.WorkerMain"),
                role = ServiceRole.CACHE_TIER,
            ),
        )
        return CodebaseInduction(
            forgeProjectState = ForgeProjectState(
                project = CodebaseProject(
                    repoPath = "/workspace/acme-app",
                    name = "acme-app",
                    services = services,
                    gitHead = "deadbeef",
                ),
                classpathRoots = services.flatMap { it.classpath },
            ),
            masterBlackboardClasspath = MasterBlackboardClasspath(
                trikeshedRoot = ".",
                ccekJars = listOf("build/libs/TrikeShed-jvm.jar"),
            ),
        )
    }
}
