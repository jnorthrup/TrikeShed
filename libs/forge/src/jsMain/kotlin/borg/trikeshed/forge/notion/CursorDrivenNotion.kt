package borg.trikeshed.forge.notion

import kotlinx.serialization.Serializable

@Serializable
actual class NotionBlockId(val value: String) {
    companion object {
        actual fun generate(): NotionBlockId = NotionBlockId("notion-block-${PlatformUtils.randomUuid()}")
    }
}