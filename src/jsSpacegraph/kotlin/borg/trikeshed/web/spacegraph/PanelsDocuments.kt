package borg.trikeshed.web.spacegraph

import borg.trikeshed.web.*
import kotlinx.browser.document
import kotlinx.coroutines.CancellationException

/** Presentation of the existing document runners. Buttons load authored constructions, never execute them. */
fun Panels.renderDocumentReview(n: dynamic, out: dynamic) {
    setResult(n, out)
    val target = n.el.querySelector(".result") ?: return
    fun action(label: String, preset: String, configure: (dynamic) -> Unit) {
        val button = document.createElement("button").asDynamic()
        button.textContent = label; button.style.cssText = "display:block;margin:8px 0"
        target.appendChild(button)
        on(button, "click", { e ->
            e.stopPropagation()
            val before = JSON.stringify(toConfix())
            button.disabled = true
            launchJs {
                try {
                    val response = fetchJson("/api/panels/presets")
                    val recipe = arr(response.presets).singleOrNull { str(it.name) == preset }
                        ?: error("Document construction is unavailable: $preset")
                    val doc = JSON.parse<dynamic>(JSON.stringify(recipe.document))
                    require(arr(doc.nodes).all { contract(it.type) != null }) { "Refresh contracts before loading this construction" }
                    configure(doc)
                    require(before == JSON.stringify(toConfix()) && n._lastOut === out && truthy(n.el.isConnected)) {
                        "Canvas or result changed; use the current result to load the construction"
                    }
                    load(fromConfix(doc))
                    panelName().value = preset.removePrefix("preset-")
                    status(str(recipe.title) + " loaded without execution — inspect parameters, then Run. Undo restores the previous canvas.")
                } catch (e: CancellationException) { throw e } catch (e: dynamic) {
                    status("Document construction not loaded: " + failureText(e))
                } finally { button.disabled = false }
            }
        })
    }
    if (n.type == "document.curate" && typeOf(out.receiptCid) == "string") {
        action("Load review tasks for this receipt (no execution)", "preset-document-review") { doc ->
            val receipt = arr(doc.nodes).single { it.id == "receipt" && it.type == "scope.in" }
            receipt.params.default = out.receiptCid
        }
    }
    if (n.type == "document.tasks") {
        val tasks = (arr(out.tasks) + arr(out.unchanged)).distinctBy { str(it.jobId) }
        for (task in tasks.take(32)) {
            if (typeOf(task.jobId) != "string" || typeOf(task.receiptCid) != "string" ||
                typeOf(task.revision) != "number") continue
            action("Load correction · " + str(task.issue?.kind ?: "document") + " · revision " + str(task.revision) +
                " · " + str(task.column), "preset-document-correction") { doc ->
                val correction = arr(doc.nodes).single { it.id == "correction" && it.type == "document.correct" }
                correction.params.taskId = task.jobId
                correction.params.expectedRevision = str(task.revision)
                correction.params.priorReceiptCid = task.receiptCid
            }
        }
        if (tasks.size > 32) {
            val notice = document.createElement("p")
            notice.textContent = "First 32 task actions shown; all task references remain in the result above."
            target.appendChild(notice)
        }
    }
}
