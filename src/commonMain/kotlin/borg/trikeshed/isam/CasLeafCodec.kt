package borg.trikeshed.isam

import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId

/** The owner supplies its CAS and may override the portable codec. No global registry or separate I/O path.
 * [dictionary] selects new writes; a reader resolves each file's recorded CID, including older dictionaries.
 * The owner must retain referenced CAS objects for the lifetime of those files.
 */
class CasLeafCodec(
    val cas: CasStore,
    val codec: (ByteArray) -> IsamLeafCodec = ::DictionaryLeafCodec,
    override val dictionary: ContentId? = null,
) : IsamLeafCodec {
    override fun resolve(dictionary: ContentId): IsamLeafCodec {
        val bytes = requireNotNull(cas.get(dictionary)) { "ISAM compression dictionary missing: ${dictionary.value}" }
        require(ContentId.of(bytes) == dictionary) { "ISAM compression dictionary corrupt: ${dictionary.value}" }
        return codec(bytes).also {
            require(it.dictionary == dictionary) { "ISAM codec dictionary differs from ${dictionary.value}" }
        }
    }

    override fun prepareWrite() {
        dictionary?.let {
            resolve(it)
            cas.sync()
        }
    }

    override fun encoder(): LeafEncoder = (dictionary?.let(::resolve) ?: ZstdLeafCodec).encoder()
    override fun decoder(): LeafDecoder = (dictionary?.let(::resolve) ?: ZstdLeafCodec).decoder()
}
