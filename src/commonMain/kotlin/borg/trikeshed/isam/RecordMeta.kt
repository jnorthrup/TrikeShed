package borg.trikeshed.isam

import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.isam.meta.IOMemento


/** RecordMeta is a data class that describes a column of an Isam record
 *
 * @param name the name of the column
 * @param type the type of the column
 * @param begin the byte offset of the beginning of the column
 * @param end the byte offset of the end of the column
 * @param decoder a lambda that converts a byte[]  to downstream, often but not necessarily the IoMemento utility
 * @param encoder a lambda that produces a byte[] for marshalling to disk or elsewhere
 * @param child the basis for blackboard cursor-dags where a child type is a cursor or in a speciasl (evidence) loop treating rows before and after conversion to isam meta.
 */

   class RecordMeta(
    override val name: String,
    override val type: IOMemento,
    val begin: Int = -1,
    val end: Int = -1,
    val decoder: (ByteArray) -> Any? = type.createDecoder(end - begin),
    val encoder: (Any?) -> ByteArray = type.createEncoder(end - begin),
    override var child: RecordMeta? = null,
    val groupId: Int = 0,
    /** Human-readable group label. Defaults to groupId.toString() — "0" stays visible as "0". Implicit (max) group → <stem>.bin; named groups → <stem>.<groupName>.bin. */
    val groupName: String = groupId.toString(),
    ) : ColumnMeta by ColumnMeta(name, type, child as ColumnMeta?){
       override fun toString(): String = "RecordMeta(name='$name', type=$type, begin=$begin, end=$end, groupId=$groupId, groupName='$groupName')"
    }
