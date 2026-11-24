package narchy.spacegraph.navigation

import borg.trikeshed.lib.*

// Port of upstream packages/navigation/src/deep-link.ts at 125b369.
// Deep link codec for graph permalinks. The host supplies the prefix; the codec handles the rest.

data class DeepLinkParts(
    /** The layout name (e.g. "lanes", "tree"). */
    val layout: String,
    /** The zoom level (e.g. "overview", "execution", "detail"). */
    val level: String,
    /** The node id to focus, if any. */
    val node: String? = null,
)

data class DeepLinkOptions(
    /** The URL prefix the host uses for graph deep links (e.g. "#spacegraph:"). */
    val prefix: String,
)

/** Format: `${prefix}${layout}:${level}${node ? `:${node}` : ''}` */
fun encodeDeepLink(parts: DeepLinkParts, options: DeepLinkOptions): String {
    val segments = mutableListOf(parts.layout, parts.level)
    if (!parts.node.isNullOrEmpty()) segments.add(parts.node)
    return options.prefix + segments.joinToString(":")
}

/** Returns null if the fragment does not match the expected prefix. */
fun decodeDeepLink(fragment: String, options: DeepLinkOptions): DeepLinkParts? {
    if (!fragment.startsWith(options.prefix)) return null
    val content = fragment.substring(options.prefix.length)
    if (content.isEmpty()) return null
    val segments = content.split(":")
    if (segments.size < 2) return null
    return DeepLinkParts(layout = segments[0], level = segments[1], node = segments.getOrNull(2))
}

fun isDeepLink(fragment: String, options: DeepLinkOptions): Boolean = fragment.startsWith(options.prefix)

class DeepLinkCodec(prefix: String) {
    private val options = DeepLinkOptions(prefix)
    fun encode(parts: DeepLinkParts): String = encodeDeepLink(parts, options)
    fun decode(fragment: String): DeepLinkParts? = decodeDeepLink(fragment, options)
    fun isDeepLink(fragment: String): Boolean = narchy.spacegraph.navigation.isDeepLink(fragment, options)
}

/** Convenience for hosts that use a single prefix. */
fun createDeepLinkCodec(prefix: String): DeepLinkCodec = DeepLinkCodec(prefix)
