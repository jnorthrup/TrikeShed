package testingstuff.borg.trikeshed.isam.meta

import borg.trikeshed.isam.meta.IOMemento.*
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.asString
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals


class PlatformCodecTest {
    @Test
    fun testCurrentCodec() {
        borg.trikeshed.isam.meta.IOMemento.values().forEach {
            when (it) {
                IoBoolean -> {
                    val v = true
                    val dec = IoBoolean.createDecoder(IoBoolean.networkSize!!/*?:v.size */)
                    val enc = IoBoolean.createEncoder(IoBoolean.networkSize!! /*?:v.size */)
                    val bytes = enc(v)
                    val v2 = dec(bytes)
                    assertEquals(v, v2)
                }

                IoByte -> {
                    val v = 0x7f.toByte()
                    val dec = IoByte.createDecoder(IoByte.networkSize!!  /*?:v.size */)
                    val enc = IoByte.createEncoder(IoByte.networkSize!!  /*?:v.size */)
                    val bytes = enc(v)
                    val v2 = dec(bytes)
                    assertEquals(v, v2)
                }

                IoLocalDate -> {
                    val v = Clock.System.now().toLocalDateTime(TimeZone.UTC).date
                    val dec =
                        IoLocalDate.createDecoder(IoLocalDate.networkSize  !!)
                    val enc =
                        IoLocalDate.createEncoder(IoLocalDate.networkSize !! )
                    val bytes = enc(v)
                    val v2 = dec(bytes)
                    assertEquals(v, v2)
                }

                IoInstant -> {
                    val v = Clock.System.now()
                    val dec = IoInstant.createDecoder(IoInstant.networkSize ?: 1)
                    val enc = IoInstant.createEncoder(IoInstant.networkSize ?: 1)
                    val bytes = enc(v)
                    val v2 = dec(bytes)
                    assertEquals(v, v2)
                }

                IoString -> {
                    val v = "Hello World"
                    val dec =
                        IoString.createDecoder(IoString.networkSize ?: v.length)
                    val enc =
                        IoString.createEncoder(IoString.networkSize ?: v.length)
                    val bytes = enc(v)
                    val v2 = dec(bytes)
                    assertEquals(v, v2)
                }

                IoCharSeries -> {
                    val v = "Hello World".toSeries()
                    val dec =
                        IoCharSeries.createDecoder(
                            IoCharSeries.networkSize ?: v.size
                        )
                    val enc =
                        IoCharSeries.createEncoder(
                            IoCharSeries.networkSize ?: v.size
                        )
                    val bytes = enc(v)
                    @Suppress("UNCHECKED_CAST") val v2 = dec(bytes) as Series<Char>
                    assertEquals(v.asString(), v2.asString())
                }

                IoByteArray -> {
                    val v = "Hello World".encodeToByteArray()
                    val dec =
                        IoByteArray.createDecoder(IoByteArray.networkSize ?: v.size)
                    val enc =
                        IoByteArray.createEncoder(IoByteArray.networkSize ?: v.size)
                    val bytes = enc(v)
                    val v2 = dec(bytes) as ByteArray
                    assertEquals( v.toList() , v2.toList())
                }

                IoShort -> {
                    val v = 0x7fff.toShort()
                    val dec = IoShort.createDecoder(IoShort.networkSize!!  /*?:v.size */)
                    val enc = IoShort.createEncoder(IoShort.networkSize!!  /*?:v.size */)
                    val bytes = enc(v)
                    val v2 = dec(bytes)
                    assertEquals(v, v2)
                }

                IoInt -> {
                    val v = 0x7fffffff
                    val dec = IoInt.createDecoder(IoInt.networkSize!!  /*?:v.size */)
                    val enc = IoInt.createEncoder(IoInt.networkSize!!  /*?:v.size */)
                    val bytes = enc(v)
                    val v2 = dec(bytes)
                    assertEquals(v, v2)
                }

                IoLong -> {
                    val v = 0x7fffffffffffffffL
                    val dec = IoLong.createDecoder(IoLong.networkSize!!  /*?:v.size */)
                    val enc = IoLong.createEncoder(IoLong.networkSize!!  /*?:v.size */)
                    val bytes = enc(v)
                    val v2 = dec(bytes)
                    assertEquals(v, v2)
                }

                IoFloat -> {
                    also {
                        val v = 0x7fffffff.toFloat()
                        val dec = IoFloat.createDecoder(IoFloat.networkSize!!  /*?:v.size */)
                        val enc = IoFloat.createEncoder(IoFloat.networkSize!!  /*?:v.size */)
                        val bytes = enc(v)
                        val v2 = dec(bytes)
                        assertEquals(v, v2)
                    }
                    //test 10 more random values
                    repeat(10) {
                        val v = Random.nextFloat()
                        val dec = IoFloat.createDecoder(IoFloat.networkSize!!  /*?:v.size */)
                        val enc = IoFloat.createEncoder(IoFloat.networkSize!!  /*?:v.size */)
                        val bytes = enc(v)
                        val v2 = dec(bytes)
                        assertEquals(v, v2)
                    }
                }

                IoDouble -> {
                    also {
                        val v = 0x7fffffffffffffff.toDouble()
                        val dec = IoDouble.createDecoder(IoDouble.networkSize!!  /*?:v.size */)
                        val enc = IoDouble.createEncoder(IoDouble.networkSize!!  /*?:v.size */)
                        val bytes = enc(v)
                        val v2 = dec(bytes)
                        assertEquals(v, v2)
                    }
                    //test 10 more random values
                    repeat(10) {
                        val v = Random.nextDouble()
                        val dec = IoDouble.createDecoder(IoDouble.networkSize!!  /*?:v.size */)
                        val enc = IoDouble.createEncoder(IoDouble.networkSize!!  /*?:v.size */)
                        val bytes = enc(v)
                        val v2 = dec(bytes)
                        assertEquals(v, v2)
                    }
                }
                else -> {}
//                IoUByte -> TODO()
//                IoUShort -> TODO()
//                IoUInt -> TODO()
//                IoULong -> TODO()
            }
        }
        println("testCurrentCodec: OK")
    }
}