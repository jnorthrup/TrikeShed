package borg.trikeshed.userspace.nio.platform.spi

import borg.trikeshed.lib.*
import borg.trikeshed.narsese.EncoderOperations
import borg.trikeshed.narsese.EncoderSession

/** MLX backbones for `JevaInference.loadDevice`: one GPU session per checkpoint, shared with the head's matrices. */
class MlxEncoderOperations : EncoderOperations {
    override fun open(directory: String, check: () -> Unit): EncoderSession {
        check()
        val session = MlxMatrixOperations().open()
        try {
            val backbone = MlxBackbone(session, directory, check)
            check()
            return object : EncoderSession {
                override val hiddenSize: Int = backbone.config.hiddenSize
                override val matrix: MatrixSession = session
                override val identity: String = session.identity + ":weights:" + backbone.identity
                override fun encode(tokens: Series<Int>, check: () -> Unit, progress: (Int, Int) -> Unit) = backbone.encode(tokens, check, progress)
                override fun close() = session.close()
            }
        } catch (failure: Throwable) {
            try { session.close() } catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
            throw failure
        }
    }
}
