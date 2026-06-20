package borg.trikeshed.htx.demo

import borg.trikeshed.htx.*
import borg.trikeshed.lib.j

/**
 * HTX Request Demo - demonstrates root HTX types
 */
fun main() {
    println("🌐 HTX Request Demo Starting...\n")
    
    // 1. Parse URL into HtxTarget
    println("1. Parse URL into HtxTarget")
    val request = parseHtxRequest("https://api.example.com/data/items")
    println("   Scheme: ${request.target.scheme}")
    println("   Host: ${request.target.host}")
    println("   Port: ${request.target.port}")
    println("   Path: ${request.target.requestPath}")
    
    // 2. Build HtxTarget manually
    println("\n2. Build HtxTarget manually")
    val target = HtxTarget(
        scheme = HtxScheme.HTTPS,
        transportProtocol = HtxTransportProtocol.HTTPS,
        authority = "api.github.com" j 443,
        requestPath = "/repos/owner/repo/contents"
    )
    println("   Target: ${target.scheme}://${target.host}${target.requestPath}")
    
    // 3. Build HtxRequest
    println("\n3. Build HtxRequest")
    val htxRequest = HtxRequest(
        target = target,
        method = HtxMethod.GET,
        fetchStyle = HtxFetchStyle.CURL,
        headers = htxHeaders(
            "Authorization" j "Bearer token123",
            "Accept" j "application/json"
        )
    )
    println("   Method: ${htxRequest.method}")
    println("   Headers: present")
    
    // 4. HTTP methods
    println("\n4. Available HTTP methods")
    for (method in HtxMethod.entries) {
        println("   - $method")
    }
    
    // 5. Fetch styles
    println("\n5. Available fetch styles")
    for (style in HtxFetchStyle.entries) {
        println("   - $style")
    }
    
    // 6. HtxRange for partial content
    println("\n6. HtxRange for partial content")
    val range = HtxRange(0, 1023)
    println("   Range: bytes ${range.startInclusive}-${range.endInclusive}")
    
    // 7. Build response (simulated)
    println("\n7. Simulated HtxResponse")
    val response = HtxResponse(
        status = 200,
        body = emptyHtxBody(),
        headers = htxHeaders(
            "Content-Type" j "application/json",
            "Content-Length" j "19"
        )
    )
    println("   Status: ${response.status}")
    println("   Body: empty")
    
    println("\n✅ HTX Request Demo Complete!")
}
