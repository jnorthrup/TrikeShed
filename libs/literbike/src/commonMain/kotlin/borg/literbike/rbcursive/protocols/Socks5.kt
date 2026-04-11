package borg.literbike.rbcursive.protocols

import borg.literbike.rbcursive.*

/**
 * SOCKS5 protocol parser using RBCursive combinators.
 * Binary protocol parsing with precise byte handling.
 * Ported from literbike/src/rbcursive/protocols/socks5.rs.
 */

/**
 * SOCKS5 request variants.
 */
sealed class Socks5Request {
    data class Handshake(val handshake: Socks5HandshakeFull) : Socks5Request()
    data class Connect(val connect: Socks5Connect) : Socks5Request()
}

/**
 * SOCKS5 protocol parser.
 */
class Socks5Parser(
    private val scanner: SimdScanner = ScalarScanner()
) {
    companion object {
        fun new(): Socks5Parser = Socks5Parser()
    }

    /** Parse SOCKS5 handshake request */
    fun parseHandshake(input: ByteArray): ParseResult<Socks5HandshakeFull> {
        if (input.size < 3) return ParseResult.Incomplete(input.size)

        // SOCKS5 version must be 0x05
        if (input[0] != 0x05.toByte()) {
            return ParseResult.Error(ParseError.InvalidProtocol, 0)
        }

        val version = input[0].toInt() and 0xFF
        val methodCount = input[1].toInt() and 0xFF

        if (input.size < 2 + methodCount) {
            return ParseResult.Incomplete(input.size)
        }

        val methods = mutableListOf<Socks5AuthMethod>()
        for (i in 0 until methodCount) {
            val methodByte = input[2 + i].toInt() and 0xFF
            val method = when (methodByte) {
                0x00 -> Socks5AuthMethod.NoAuth
                0x01 -> Socks5AuthMethod.GssApi
                0x02 -> Socks5AuthMethod.UserPass
                0xFF -> Socks5AuthMethod.NoAcceptable
                else -> Socks5AuthMethod.NoAcceptable // Unknown method
            }
            methods.add(method)
        }

        return ParseResult.Complete(Socks5HandshakeFull(version, methods), 2 + methodCount)
    }

    /** Parse SOCKS5 connect request */
    fun parseConnect(input: ByteArray): ParseResult<Socks5Connect> {
        if (input.size < 4) return ParseResult.Incomplete(input.size)

        // Check SOCKS5 version
        if (input[0] != 0x05.toByte()) {
            return ParseResult.Error(ParseError.InvalidProtocol, 0)
        }

        val version = input[0].toInt() and 0xFF
        val command = input[1].toInt() and 0xFF
        val addressType = input[3].toInt() and 0xFF

        val (address, addressLen) = when (addressType) {
            0x01 -> {
                // IPv4 address (4 bytes)
                if (input.size < 4 + 4 + 2) return ParseResult.Incomplete(input.size)
                input.copyOfRange(4, 8) to 4
            }
            0x03 -> {
                // Domain name
                if (input.size < 5) return ParseResult.Incomplete(input.size)
                val domainLen = input[4].toInt() and 0xFF
                if (input.size < 5 + domainLen + 2) return ParseResult.Incomplete(input.size)
                input.copyOfRange(5, 5 + domainLen) to (domainLen + 1) // +1 for length byte
            }
            0x04 -> {
                // IPv6 address (16 bytes)
                if (input.size < 4 + 16 + 2) return ParseResult.Incomplete(input.size)
                input.copyOfRange(4, 20) to 16
            }
            else -> {
                return ParseResult.Error(ParseError.InvalidInput, 3)
            }
        }

        val portOffset = 4 + addressLen
        if (input.size < portOffset + 2) {
            return ParseResult.Incomplete(input.size)
        }

        val port = ((input[portOffset].toInt() and 0xFF) shl 8) or
                (input[portOffset + 1].toInt() and 0xFF)

        return ParseResult.Complete(
            Socks5Connect(version, command, addressType, address, port),
            portOffset + 2
        )
    }

    /** Detect if data looks like SOCKS5 protocol */
    fun isSocks5(input: ByteArray): Boolean {
        if (input.size < 3) return false

        // Check version byte
        if (input[0] != 0x05.toByte()) return false

        // Check method count is reasonable
        val methodCount = input[1].toInt() and 0xFF
        if (methodCount == 0 || methodCount > 255) return false

        // Check we have enough bytes for the methods
        if (input.size < 2 + methodCount) return false

        return true
    }

    /** Parse either handshake or connect request */
    fun parseRequest(input: ByteArray): ParseResult<Socks5Request> {
        if (input.size < 3) return ParseResult.Incomplete(input.size)

        if (input[0] != 0x05.toByte()) {
            return ParseResult.Error(ParseError.InvalidProtocol, 0)
        }

        // Heuristic: if second byte is a small number (< 10), it's likely method count (handshake)
        // If it's 0x01 (CONNECT), it's likely a connect request
        val secondByte = input[1].toInt() and 0xFF

        if (secondByte == 0x01 && input.size >= 4 && input[2] == 0x00.toByte()) {
            // Looks like CONNECT request (cmd=0x01, reserved=0x00)
            return when (val result = parseConnect(input)) {
                is ParseResult.Complete -> ParseResult.Complete(Socks5Request.Connect(result.value), result.consumed)
                is ParseResult.Incomplete -> result
                is ParseResult.Error -> result
            }
        } else if (secondByte > 0 && secondByte < 10) {
            // Looks like handshake with method count
            return when (val result = parseHandshake(input)) {
                is ParseResult.Complete -> ParseResult.Complete(Socks5Request.Handshake(result.value), result.consumed)
                is ParseResult.Incomplete -> result
                is ParseResult.Error -> result
            }
        } else {
            return ParseResult.Error(ParseError.InvalidInput, 1)
        }
    }
}
