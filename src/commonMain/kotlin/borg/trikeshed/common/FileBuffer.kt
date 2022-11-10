import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import kotlinx.coroutines.CloseableCoroutineDispatcher


/**
 * an openable and closeable mmap file.
 *
 *
 *
 */
expect class FileBuffer(filename: String, initialOffset: Long, blkSize: Long, readOnly: Boolean) :Join <Long,(Long)->Byte>{

    val  filename: String
    val  initialOffset: Long
    val  blkSize: Long
    val  readOnly: Boolean


  fun close()
  fun open() //post-init open
  fun isOpen():Boolean
  fun size():Long
  fun  get(index:Long):Byte
  fun  put(index:Long,value:Byte)

}
