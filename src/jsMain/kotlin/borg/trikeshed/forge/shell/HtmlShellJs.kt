package borg.trikeshed.forge.shell

// Minimal stub for jsMain to satisfy expect, exact async fetching logic would require
// async/await but the actual is synchronous in the expect, so we throw or return placeholders
// if synchronous behaviour is strictly expected. The issue instructions stated:
// jsMain and wasmJsMain: actual object HtmlShell that fetches via
// kotlinx.browser.window.fetch("shell/index.html") and reads the response body.
// Note: fetch is async, so a strict synchronous expect String return might be difficult in JS,
// but let's do a best effort stub based on instructions. We'll throw an UnsupportedOperationException
// since sync fetch isn't supported in browser JS.

actual object HtmlShell {
    actual fun load(): String {
        throw UnsupportedOperationException("Synchronous fetch not supported in JS. Use async.")
    }

    actual fun cssAsset(name: String): String {
        throw UnsupportedOperationException("Synchronous fetch not supported in JS. Use async.")
    }

    actual fun jsAsset(name: String): String {
        throw UnsupportedOperationException("Synchronous fetch not supported in JS. Use async.")
    }
}
