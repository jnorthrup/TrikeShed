package cbadvanced.main

import cbadvanced.resolveDotenv
import cbadvanced.runCoinbaseAuthProof
import kotlinx.coroutines.*

fun main(args: Array<String>) {
    val requestedProduct = args.firstOrNull()?.ifBlank { null }?.substringBefore("-USD") ?: "BTC"
    val resolution = resolveDotenv()
    val proof = runBlocking { runCoinbaseAuthProof(resolution.resolvedPath, requestedProduct) }

    println(proof.message)
    println("cbadvanced auth proof")
    println("dotenv: ${proof.dotenvPath}")
    println("key: ${proof.keyName}")
    println("rest: ${proof.restUrl}")
    println("authSucceeded: ${proof.authSucceeded}")
    println("cashBalance: ${proof.balance?.cashBalance ?: "unavailable"}")
    println("buyingPower: ${proof.balance?.buyingPower ?: "unavailable"}")
    println("quote ${proof.quoteProduct}: ${proof.quote ?: "unavailable"}")
}
