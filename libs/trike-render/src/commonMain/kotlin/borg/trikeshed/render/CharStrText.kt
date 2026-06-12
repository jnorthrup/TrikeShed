package borg.trikeshed.render

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontStyle
import borg.trikeshed.charstr.*
import borg.trikeshed.lib.*

// ── CharStr → AnnotatedString ─────────────────────────────────
//
// CharStr = MetaSeries<TextK<*>, Any?> — a faceted text row.
// Each TextK facet maps to a Compose text annotation:
//   TextK.Raw       → text content
//   TextK.NormK.NFC → normalised display string
//   TextK.CaseFold  → case-folded display
//
// Only materialised facets contribute; CharStr is lazy by contract.

/**
 * Renders a [CharStr] as Compose [Text] with typed facet selection.
 *
 * By default, renders [TextK.Raw] (the witness CharSequence).
 * Pass specific facets to render normalised, case-folded, or other projections.
 *
 * @param charStr   the faceted text to render
 * @param modifier  layout modifier
 * @param facets    which TextK facets to resolve (default: Raw only)
 * @param style     text style override
 */
@Composable
fun CharStrText(
    charStr: CharStr,
    modifier: Modifier = Modifier,
    facets: Set<TextK<*>> = setOf(TextK.Raw),
    style: TextStyle? = null,
) {
    val text = remember(charStr, facets) {
        resolveCharStr(charStr, facets)
    }
    Text(
        text = text,
        modifier = modifier,
        style = style ?: LocalTextStyle.current,
    )
}

/**
 * Renders a [Corpus] (Series<CharStr>) as a column of text lines.
 */
@Composable
fun CorpusView(
    corpus: Corpus,
    modifier: Modifier = Modifier,
    lineContent: @Composable (index: Int, CharStr) -> Unit = { _, cs ->
        CharStrText(cs)
    },
) {
    androidx.compose.foundation.layout.Column(modifier = modifier) {
        for (i in 0 until corpus.size) {
            lineContent(i, corpus[i])
        }
    }
}

// ── Internal: CharStr → AnnotatedString ────────────────────────

/** Resolve a CharStr to a display string using the requested facets. */
private fun resolveCharStr(charStr: CharStr, facets: Set<TextK<*>>): String {
    // Primary: use the first resolved facet that returns CharSequence
    for (facet in facets) {
        val result = charStr[facet]
        if (result is CharSequence) return result.toString()
    }
    // Fallback: Raw
    return charStr.raw.toString()
}

/**
 * Build an [AnnotatedString] from a [CharStr], mapping TextK facets
 * to SpanStyle annotations. This is the rich-text pathway.
 *
 * @param charStr    faceted text
 * @param spans      facet → SpanStyle mapping
 * @param baseFacet  which facet provides the base text (default: Raw)
 */
fun CharStr.toAnnotatedString(
    spans: Map<TextK<*>, SpanStyle> = emptyMap(),
    baseFacet: TextK<*> = TextK.Raw,
): AnnotatedString {
    val baseText = (this[baseFacet] as? CharSequence)?.toString() ?: ""
    if (spans.isEmpty()) return AnnotatedString(baseText)

    val builder = AnnotatedString.Builder(baseText)
    // Each facet with a SpanStyle becomes a full-range annotation
    spans.forEach { (facet, style) ->
        val value = this[facet]
        // Only apply annotation if the facet is computed (non-null evidence)
        if (value != null) {
            builder.addStyle(style, 0, baseText.length)
        }
    }
    return builder.toAnnotatedString()
}
