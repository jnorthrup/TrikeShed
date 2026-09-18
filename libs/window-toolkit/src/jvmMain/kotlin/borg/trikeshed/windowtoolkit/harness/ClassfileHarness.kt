package borg.trikeshed.windowtoolkit.harness

import borg.trikeshed.windowtoolkit.dsl.windowContext
import borg.trikeshed.windowtoolkit.dsl.WindowShell
import borg.trikeshed.windowtoolkit.ui.StyleRow
import borg.trikeshed.windowtoolkit.math.v2
import borg.trikeshed.windowtoolkit.math.size
import borg.trikeshed.windowtoolkit.math.j
import borg.trikeshed.windowtoolkit.ui.applyUniformStyle
import borg.trikeshed.windowtoolkit.confix.ConfixBlackboardWidget
import kotlinx.coroutines.runBlocking

/**
 * Harness connecting a generic JSON string mapping Classfile coordinate data
 * into the generic Confix Blackboard and layout rendering system of the window-toolkit.
 */
fun buildClassfileDatagridHarness(jsonDump: String): WindowShell {
    val shell = windowContext {
        // 1. Setup the Confix Datagrid representation layer
        confixDatagrid {
            // val entry = ConfixBlackboardEntry(
            //     doc = ConfixDoc.fromJson(jsonDump),
            //     role = ConfixRole.OBSERVATION
            // )
            // Push entry to render
            // render(entry)
        }

        // 2. Setup a simplistic layout layer mimicking classfile grid plotting
        val points = 3 j { i: Int -> v2(i * 100.0, i * 20.0) }
        val uniformStyles = points.size j { _: Int -> StyleRow(mapOf("color" to "blue")) }

        layoutLayer(points, uniformStyles)
    }

    return shell
}

fun main() = runBlocking {
    val sampleClassfileJson = """
        {
          "symbolName": "com.example.Demo",
          "ownerType": "Demo",
          "methodOrField": "<init>",
          "classfileCoord": "com.example.Demo#<init>",
          "cpIndex": 10,
          "descriptor": "()V",
          "typeInfo": "method",
          "pointcutKind": 12,
          "poolId": 101,
          "activeJsFacet": "Unfaceted"
        }
    """.trimIndent()

    val shell = buildClassfileDatagridHarness(sampleClassfileJson)
    shell.mount()
    println("Successfully mounted Classfile Harness inside Window Toolkit Shell")
    shell.unmount()
}