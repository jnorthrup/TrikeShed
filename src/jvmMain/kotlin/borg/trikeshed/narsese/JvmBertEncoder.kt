package borg.trikeshed.narsese

import borg.trikeshed.graal.subvm.GuestModules
import borg.trikeshed.userspace.nio.file.spi.*
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.lang.System

/** JVM source/cache discovery; tokenizer, tensors and inference are owned by common Kotlin. */
class JvmBertEncoder private constructor(private val encoder: BertModel) : BertEncoder by encoder {
    val dimensions: Int get() = encoder.dimensions
    val parameterBytes: Long get() = encoder.parameterBytes

    companion object {
        const val MODULE = "bert"
        const val ROOT_PROPERTY = "trikeshed.bert.root"
        const val SOURCE_PATH = "src/main/python/bert"

        /** [ROOT_PROPERTY] names the source module directory, matching `utils/subvm/bert`. */
        fun moduleRoot(): File = System.getProperty(ROOT_PROPERTY)?.takeIf { it.isNotBlank() }?.let(::File)
            ?: requireNotNull(GuestModules.moduleDir(MODULE)) {
                "BERT source module is unavailable; configure $ROOT_PROPERTY"
            }

        suspend fun local(scope: CoroutineScope): JvmBertEncoder {
            val home = File(System.getProperty("user.home"))
            val cache = File(home, ".cache/huggingface/hub/models--google-bert--bert-base-uncased")
            val weights = System.getProperty("trikeshed.bert.weights")?.let(::File) ?: run {
                val files = scope.coroutineContext[FileOperations] ?: JvmFileOperations()
                val ref = files.resolvePath(cache.path, "refs/main")
                val revision = if (files.isFile(ref)) files.readString(ref).trim() else null
                require(revision != null && revision.matches(Regex("[a-f0-9]{40}"))) {
                    "No cached BERT revision; configure trikeshed.bert.weights"
                }
                File(cache, "snapshots/$revision")
            }
            return open(scope, weights, weights.canonicalPath)
        }

        suspend fun open(scope: CoroutineScope, weights: File, model: String = weights.canonicalPath): JvmBertEncoder {
            val files = scope.coroutineContext[FileOperations] ?: JvmFileOperations()
            val owner = CoroutineScope(scope.coroutineContext + files)
            return JvmBertEncoder(BertModel.load(owner, weights.canonicalPath, model))
        }
    }
}
