package borg.trikeshed.userspace.reactor

import java.nio.file.Files
import java.nio.file.Path
import borg.trikeshed.parse.confix.ConfixArray
import borg.trikeshed.parse.confix.ConfixObject
import borg.trikeshed.parse.confix.ConfixPrimitive

object HermesAuthReaderJvm {

    fun readAuthDb(path: Path): Map<String, HermesAccount> {
        if (!Files.exists(path)) return emptyMap()
        val text = Files.readString(path)
        if (text.isBlank()) return emptyMap()

        val root = borg.trikeshed.parse.confix.ConfixYaml.decode<borg.trikeshed.parse.confix.ConfixElement>(text)
        val obj = root as? ConfixObject ?: return emptyMap()
        val users = obj["users"] as? ConfixObject ?: return emptyMap()

        val accounts = mutableMapOf<String, HermesAccount>()
        for ((k, v) in users) {
            val userObj = v as? ConfixObject ?: continue
            val scopesArr = userObj["scopes"] as? ConfixArray
            val scopes = scopesArr?.mapNotNull { (it as? ConfixPrimitive)?.content }?.toSet() ?: emptySet()
            
            val capabilitiesObj = userObj["capabilities"] as? ConfixObject
            val capabilityMap = mutableMapOf<String, Set<String>>()
            capabilitiesObj?.forEach { (capK, capV) ->
                val capArr = capV as? ConfixArray
                val items = capArr?.mapNotNull { (it as? ConfixPrimitive)?.content }?.toSet() ?: emptySet()
                capabilityMap[capK] = items
            }
            
            val limitsObj = userObj["limits"] as? ConfixObject
            val maxTokens = limitsObj?.get("maxTokens")?.let { (it as? ConfixPrimitive)?.content?.toLongOrNull() } ?: 0L
            val tokenRefillRate = limitsObj?.get("tokenRefillRate")?.let { (it as? ConfixPrimitive)?.content?.toLongOrNull() } ?: 0L

            accounts[k] = HermesAccount(
                id = k,
                scopes = scopes,
                capabilities = capabilityMap,
                limits = AccountLimits(maxTokens, tokenRefillRate)
            )
        }
        return accounts
    }
}
