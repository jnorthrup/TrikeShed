package borg.trikeshed.userspace.reactor
import kotlinx.serialization.Serializable
@Serializable
data class ModelRegistryConfig(
    val models: List<MuxModelEntryConfig> = emptyList()
)
@Serializable
data class MuxModelEntryConfig(
    val id: String,
    val provider: String,
    val caps: List<String>,
    val base: String,
    val envKey: String
)