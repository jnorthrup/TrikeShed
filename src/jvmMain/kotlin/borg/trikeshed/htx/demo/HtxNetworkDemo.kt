package borg.trikeshed.htx.demo

import borg.trikeshed.htx.*
import borg.trikeshed.lib.*

/**
 * HTX Network Demo - demonstrates HTX request building.
 * 
 * This shows:
 * - Building real HTTP requests with HTX types
 * - Target construction with proper authority
 * - Header management
 * - Range requests
 */
object HtxNetworkDemo {

    @JvmStatic
    fun main(args: Array<String>) {
        println("🌐 HTX Network Demo Starting...")
        println()

        // Demo 1: Build GitHub API request
        println("1. Build GitHub API request")
        val githubTarget = HtxTarget(
            scheme = HtxScheme.HTTPS,
            transportProtocol = HtxTransportProtocol.HTTPS,
            authority = "api.github.com" j 443,
            requestPath = "/users/torvalds"
        )
        
        val githubRequest = HtxRequest(
            target = githubTarget,
            method = HtxMethod.GET,
            headers = htxHeaders(
                "Accept" j "application/json",
                "User-Agent" j "TrikeShed-HTX/1.0"
            )
        )
        
        println("   URL: ${githubTarget.scheme}://${githubTarget.host}${githubTarget.requestPath}")
        println("   Method: ${githubRequest.method}")
        println("   Headers: ${githubRequest.headers.size}")
        println("   ✓ Request built")
        println()

        // Demo 2: Build httpbin GET request
        println("2. Build httpbin GET request")
        val httpbinTarget = HtxTarget(
            scheme = HtxScheme.HTTPS,
            transportProtocol = HtxTransportProtocol.HTTPS,
            authority = "httpbin.org" j 443,
            requestPath = "/get"
        )
        
        val httpbinRequest = HtxRequest(
            target = httpbinTarget,
            method = HtxMethod.GET,
            headers = htxHeaders(
                "Accept" j "application/json"
            )
        )
        
        println("   URL: ${httpbinTarget.scheme}://${httpbinTarget.host}${httpbinTarget.requestPath}")
        println("   Rendered: ${httpbinRequest.renderWireRequest().take(100)}...")
        println("   ✓ Request built and rendered")
        println()

        // Demo 3: Build POST request with body
        println("3. Build POST request with body")
        val postTarget = HtxTarget(
            scheme = HtxScheme.HTTPS,
            transportProtocol = HtxTransportProtocol.HTTPS,
            authority = "httpbin.org" j 443,
            requestPath = "/post"
        )
        
        // Note: HtxRequest doesn't have a body field - body would be sent separately
        // This demonstrates building a POST request
        val postRequest = HtxRequest(
            target = postTarget,
            method = HtxMethod.POST,
            headers = htxHeaders(
                "Content-Type" j "application/json",
                "Accept" j "application/json",
                "Content-Length" j "39"
            )
        )
        
        println("   Method: ${postRequest.method}")
        println("   Headers: ${postRequest.headers.size}")
        println("   ✓ POST request built")
        println()

        // Demo 4: Build range request
        println("4. Build range request")
        val rangeTarget = HtxTarget(
            scheme = HtxScheme.HTTPS,
            transportProtocol = HtxTransportProtocol.HTTPS,
            authority = "httpbin.org" j 443,
            requestPath = "/bytes/1024"
        )
        
        val rangeRequest = HtxRequest(
            target = rangeTarget,
            method = HtxMethod.GET,
            headers = htxHeaders(),
            range = HtxRange(0, 1023)
        )
        
        println("   Range: bytes=${rangeRequest.range?.startInclusive}-${rangeRequest.range?.endInclusive}")
        println("   ✓ Range request built")
        println()

        // Demo 5: Show HTTP methods
        println("5. All HTTP methods available")
        println("   ${HtxMethod.entries.joinToString(", ")}")
        println()

        // Demo 6: Show fetch styles
        println("6. Fetch styles")
        println("   ${HtxFetchStyle.entries.joinToString(", ")}")
        println()

        println("✅ HTX Network Demo Complete!")
    }
}