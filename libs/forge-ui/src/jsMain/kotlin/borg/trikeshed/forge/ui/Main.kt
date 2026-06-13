package borg.trikeshed.forge.ui

import androidx.compose.runtime.Composable
import androidx.compose.web.dom.div
import kotlinx.html.js.HTMLElement
import kotlinx.html.js.document
import org.jetbrains.compose.web.css.ExperimentalComposeWebCss
import org.jetbrains.compose.web.css.StyleSheet
import org.jetbrains.compose.web.css.attrs.backgroundColor
import org.jetbrains.compose.web.css.attrs.color
import org.jetbrains.compose.web.css.attrs.display
import org.jetbrains.compose.web.css.attrs.flexDirection
import org.jetbrains.compose.web.css.attrs.fontSize
import org.jetbrains.compose.web.css.attrs.height
import org.jetbrains.compose.web.css.attrs.justifyContent
import org.jetbrains.compose.web.css.attrs.width
import org.jetbrains.compose.web.core.ComposableSingletons$MainKt
import org.jetbrains.compose.web.core.renderComposable

@OptIn(ExperimentalComposeWebCss::class)
@Composable
fun WebApp() {
    div {
        attrs.style {
            width = "100vw"
            height = "100vh"
            display = "flex"
            flexDirection = "column"
            justifyContent = "center"
            backgroundColor = "#1E1E1E"
            color = "white"
        }
        div {
            attrs.style {
                fontSize = "32px"
                fontWeight = "bold"
                marginBottom = "16px"
            }
            +"Forge UI (Web)"
        }
        div {
            attrs.style {
                fontSize = "16px"
                color = "#AAAAAA"
            }
            +"Kanban workspace powered by TrikeShed"
        }
    }
}

fun main() {
    val root = document.getElementById("root") ?: document.body
    renderComposable(root) { WebApp() }
}