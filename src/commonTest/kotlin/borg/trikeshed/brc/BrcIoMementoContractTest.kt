package borg.trikeshed.brc

import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.CharSeries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Contract tests for IOMemento codec round-trips relevant to BRC (1 Billion Row Challenge).
 *
 * Covers:
 * 1. `fromChars` parsing for string-like and numeric types
 * 2. `networkSize` correctness for fixed-width types
 * 3. createEncoder/createDecoder round-trip (CommonPlatformCodec is pure Kotlin — safe in commonTest)
 * 4. BRC-specific: parsing temperature strings "12.0" and "-42.7" via IoFloat/IoDouble
 */
class BrcIoMementoContractTest {

    // -------------------------------------------------------------------------
    // networkSize — fixed-width contracts
    // -------------------------------------------------------------------------

    @Test
    fun ioByte_networkSize_is1() {
        assertEquals(1, IOMemento.IoByte.networkSize)
    }

    @Test
    fun ioShort_networkSize_is2() {
        assertEquals(2, IOMemento.IoShort.networkSize)
    }

    @Test
    fun ioInt_networkSize_is4() {
        assertEquals(4, IOMemento.IoInt.networkSize)
    }

    @Test
    fun ioLong_networkSize_is8() {
        assertEquals(8, IOMemento.IoLong.networkSize)
    }

    @Test
    fun ioFloat_networkSize_is4() {
        assertEquals(4, IOMemento.IoFloat.networkSize)
    }

    @Test
    fun ioDouble_networkSize_is8() {
        assertEquals(8, IOMemento.IoDouble.networkSize)
    }

    @Test
    fun ioBoolean_networkSize_is1() {
        assertEquals(1, IOMemento.IoBoolean.networkSize)
    }

    @Test
    fun ioString_networkSize_isNull() {
        assertEquals(null, IOMemento.IoString.networkSize)
    }

    // -------------------------------------------------------------------------
    // fromChars — parsing contracts
    // -------------------------------------------------------------------------

    @Test
    fun ioLong_fromChars_parsesPositiveInteger() {
        val cs = CharSeries("42")
        val result = IOMemento.IoLong.fromChars(cs)
        assertEquals(42L, result)
    }

    @Test
    fun ioLong_fromChars_parsesNegativeInteger() {
        val cs = CharSeries("-100")
        val result = IOMemento.IoLong.fromChars(cs)
        assertEquals(-100L, result)
    }

    @Test
    fun ioInt_fromChars_parsesInteger() {
        val cs = CharSeries("256")
        val result = IOMemento.IoInt.fromChars(cs)
        assertEquals(256, result)
    }

    @Test
    fun ioByte_fromChars_parsesByte() {
        val cs = CharSeries("127")
        val result = IOMemento.IoByte.fromChars(cs)
        assertEquals(127.toByte(), result)
    }

    @Test
    fun ioShort_fromChars_parsesShort() {
        val cs = CharSeries("1000")
        val result = IOMemento.IoShort.fromChars(cs)
        assertEquals(1000.toShort(), result)
    }

    @Test
    fun ioBoolean_fromChars_parsesTrue() {
        val cs = CharSeries("t")
        val result = IOMemento.IoBoolean.fromChars(cs)
        assertEquals(true, result)
    }

    @Test
    fun ioBoolean_fromChars_parsesFalse() {
        val cs = CharSeries("f")
        val result = IOMemento.IoBoolean.fromChars(cs)
        assertEquals(false, result)
    }

    @Test
    fun ioString_fromChars_returnsString() {
        val cs = CharSeries("hello")
        val result = IOMemento.IoString.fromChars(cs)
        assertEquals("hello", result)
    }

    // -------------------------------------------------------------------------
    // BRC-specific: temperature parsing via IoFloat and IoDouble
    // -------------------------------------------------------------------------

    @Test
    fun ioDouble_fromChars_parsesPositiveTemperature() {
        val cs = CharSeries("12.0")
        val result = IOMemento.IoDouble.fromChars(cs) as Double
        assertEquals(12.0, result, 0.0001)
    }

    @Test
    fun ioDouble_fromChars_parsesNegativeTemperature() {
        val cs = CharSeries("-42.7")
        val result = IOMemento.IoDouble.fromChars(cs) as Double
        assertEquals(-42.7, result, 0.0001)
    }

    @Test
    fun ioFloat_fromChars_parsesPositiveTemperature() {
        val cs = CharSeries("12.0")
        val result = IOMemento.IoFloat.fromChars(cs) as Float
        assertEquals(12.0f, result, 0.001f)
    }

    @Test
    fun ioFloat_fromChars_parsesNegativeTemperature() {
        val cs = CharSeries("-42.7")
        val result = IOMemento.IoFloat.fromChars(cs) as Float
        assertEquals(-42.7f, result, 0.001f)
    }

    // -------------------------------------------------------------------------
    // createEncoder / createDecoder round-trip
    // CommonPlatformCodec is pure Kotlin — safe in commonTest
    // -------------------------------------------------------------------------

    @Test
    fun ioInt_encoderDecoder_roundTrip() {
        val encoder = IOMemento.IoInt.createEncoder(4)
        val decoder = IOMemento.IoInt.createDecoder(4)
        val original = 123456
        val bytes = encoder(original)
        assertEquals(4, bytes.size)
        val decoded = decoder(bytes)
        assertEquals(original, decoded)
    }

    @Test
    fun ioInt_encoderDecoder_roundTrip_negativeValue() {
        val encoder = IOMemento.IoInt.createEncoder(4)
        val decoder = IOMemento.IoInt.createDecoder(4)
        val original = -99999
        val bytes = encoder(original)
        assertEquals(4, bytes.size)
        val decoded = decoder(bytes)
        assertEquals(original, decoded)
    }

    @Test
    fun ioLong_encoderDecoder_roundTrip() {
        val encoder = IOMemento.IoLong.createEncoder(8)
        val decoder = IOMemento.IoLong.createDecoder(8)
        val original = 9876543210L
        val bytes = encoder(original)
        assertEquals(8, bytes.size)
        val decoded = decoder(bytes)
        assertEquals(original, decoded)
    }

    @Test
    fun ioShort_encoderDecoder_roundTrip() {
        val encoder = IOMemento.IoShort.createEncoder(2)
        val decoder = IOMemento.IoShort.createDecoder(2)
        val original: Short = 32000
        val bytes = encoder(original)
        assertEquals(2, bytes.size)
        val decoded = decoder(bytes)
        assertEquals(original, decoded)
    }

    @Test
    fun ioDouble_encoderDecoder_roundTrip_temperature12() {
        val encoder = IOMemento.IoDouble.createEncoder(8)
        val decoder = IOMemento.IoDouble.createDecoder(8)
        val original = 12.0
        val bytes = encoder(original)
        assertNotNull(bytes)
        assertEquals(8, bytes.size)
        val decoded = decoder(bytes) as Double
        assertEquals(original, decoded, 0.0000001)
    }

    @Test
    fun ioDouble_encoderDecoder_roundTrip_temperatureNeg42_7() {
        val encoder = IOMemento.IoDouble.createEncoder(8)
        val decoder = IOMemento.IoDouble.createDecoder(8)
        val original = -42.7
        val bytes = encoder(original)
        assertNotNull(bytes)
        assertEquals(8, bytes.size)
        val decoded = decoder(bytes) as Double
        assertEquals(original, decoded, 0.0000001)
    }

    @Test
    fun ioFloat_encoderDecoder_roundTrip() {
        val encoder = IOMemento.IoFloat.createEncoder(4)
        val decoder = IOMemento.IoFloat.createDecoder(4)
        val original = -42.7f
        val bytes = encoder(original)
        assertNotNull(bytes)
        assertEquals(4, bytes.size)
        val decoded = decoder(bytes) as Float
        assertEquals(original, decoded, 0.0001f)
    }

    @Test
    fun ioByte_encoderDecoder_roundTrip() {
        val encoder = IOMemento.IoByte.createEncoder(1)
        val decoder = IOMemento.IoByte.createDecoder(1)
        val original: Byte = -128
        val bytes = encoder(original)
        assertEquals(1, bytes.size)
        val decoded = decoder(bytes)
        assertEquals(original, decoded)
    }

    @Test
    fun ioBoolean_encoderDecoder_roundTrip_true() {
        val encoder = IOMemento.IoBoolean.createEncoder(1)
        val decoder = IOMemento.IoBoolean.createDecoder(1)
        val bytes = encoder(true)
        assertEquals(1, bytes.size)
        val decoded = decoder(bytes)
        assertEquals(true, decoded)
    }

    @Test
    fun ioBoolean_encoderDecoder_roundTrip_false() {
        val encoder = IOMemento.IoBoolean.createEncoder(1)
        val decoder = IOMemento.IoBoolean.createDecoder(1)
        val bytes = encoder(false)
        assertEquals(1, bytes.size)
        val decoded = decoder(bytes)
        assertEquals(false, decoded)
    }

    @Test
    fun ioString_encoderDecoder_roundTrip() {
        val encoder = IOMemento.IoString.createEncoder(0)
        val decoder = IOMemento.IoString.createDecoder(0)
        val original = "Hamburg"
        val bytes = encoder(original)
        val decoded = decoder(bytes)
        assertEquals(original, decoded)
    }
}
