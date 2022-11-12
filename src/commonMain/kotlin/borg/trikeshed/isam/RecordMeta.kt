package borg.trikeshed.isam

import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.isam.meta.PlatformCodec.createDecoder
import borg.trikeshed.isam.meta.PlatformCodec.createEncoder

data class RecordMeta(
    /** column name*/
    override val name: String,
    /** enum-resident Type describing byte marshalling strategies - a specialization of TypeMemento */
    override val type: IOMemento,
    /** context-specific byte offset beginning*/
    val begin: Int = -1,
    /** context-specific byte offset ending*/
    val end: Int = -1,
    /** a lambda that converts a byte[]  to downstream, often but not necessarily the IoMemento utility */
    val decoder: (ByteArray) -> Any? = createDecoder(type, end - begin),
    /** a lambda that produces a byte[] for marshalling to disk or elsewhere */
    val encoder: (Any?) -> ByteArray = createEncoder(type, end - begin),
    /** open to interpretation, for instance, CSV conversion to ISAM might define two RecordMetas for two steps*/
    var child: RecordMeta? = null,
) : ColMeta
