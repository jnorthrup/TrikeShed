package borg.trikeshed.docs

import borg.trikeshed.parse.json.JsonSupport
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event

/**
 * The browser half of the document surface: the only hand-written-JS-shaped code
 * the page needs (DOM mounting, fetch, clicks). Every string it paints comes
 * from [DocumentSurface] in commonMain; every JSON it reads is parsed by the
 * one commonMain parser. Mounted by the bundle's entry point when the served
 * shell carries `#document-surface`.
 */
object DocumentSurfacePage {
    fun present(): Boolean = document.getElementById(DocumentSurface.ROOT_ID) != null

    private var projects: List<ProjectRow> = emptyList()
    private var project: String? = null
    private var docs: List<DocRow> = emptyList()
    private var doc: DocView? = null

    suspend fun mount() {
        val root = document.getElementById(DocumentSurface.ROOT_ID) as HTMLElement
        root.innerHTML = DocumentSurface.layout()
        root.addEventListener("click", { e -> onClick(e) })
        projects = DocumentSurface.projects(getJson("/api/projects"))
        render()
        // Deep link: #project or #project/doc, the hrefs the panes render.
        val hash = decode(window.location.hash.removePrefix("#"))
        if (hash.isNotEmpty()) {
            openProject(hash.substringBefore('/'))
            val id = hash.substringAfter('/', "")
            if (id.isNotEmpty()) openDoc(id)
        }
        js("window.forgeKotlin = Object.assign(window.forgeKotlin || {}, { documentSurface: true })")
    }

    private fun onClick(e: Event) {
        val target = (e.target as? Element)?.closest("[data-project],[data-doc]") ?: return
        e.preventDefault()
        val p = target.getAttribute("data-project")
        val d = target.getAttribute("data-doc")
        MainScope().launch {
            if (p != null) openProject(p) else if (d != null) openDoc(d)
        }
    }

    suspend fun openProject(name: String) {
        project = name; doc = null
        docs = DocumentSurface.docs(getJson("/api/projects/" + encode(name) + "/docs?limit=512"))
        render()
    }

    suspend fun openDoc(id: String) {
        val p = project ?: return
        doc = DocumentSurface.document(getJson("/api/projects/" + encode(p) + "/docs/" + encode(id)))
        render()
    }

    private fun render() {
        setHtml("ds-projects", DocumentSurface.projectsHtml(projects, project))
        setHtml("ds-docs", DocumentSurface.docsHtml(project, docs, doc?.id))
        setHtml("ds-doc", DocumentSurface.documentHtml(doc))
        val p = project
        val wanted = if (p == null) "" else "#" + encode(p) + (doc?.let { "/" + encode(it.id) } ?: "")
        if (window.location.hash != wanted) window.location.hash = wanted
        document.title = listOfNotNull(doc?.id, project, "Documents").joinToString(" · ")
    }

    private fun setHtml(id: String, html: String) {
        (document.getElementById(id) as? HTMLElement)?.innerHTML = html
    }

    private suspend fun getJson(url: String): Any? {
        val response = window.fetch(url).await()
        val text = response.text().await()
        return runCatching { JsonSupport.parse(text) }.getOrNull()
    }
}

private fun encode(s: String): String = js("encodeURIComponent(s)") as String
private fun decode(s: String): String = runCatching { js("decodeURIComponent(s)") as String }.getOrDefault(s)
