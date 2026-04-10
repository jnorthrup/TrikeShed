/**
 * HTTP/2 Client using curl and h2 for QUIC server testing
 *
 * This module provides HTTP/2 client functionality using curl-sys with HTTP/2 support
 * and the h2 library for low-level HTTP/2 protocol handling.
 *
 * Example:
 * ```kotlin
 * val client = H2Client()
 * val response = client.get("https://localhost:8888/").getOrThrow()
 * println("Status: ${response.status}")
 * ```
 */
package borg.literbike.curl_h2

// Re-export
public typealias H2Client = H2Client
public typealias H2Error = H2Error
public typealias H2Request = H2Request
public typealias H2Response = H2Response
