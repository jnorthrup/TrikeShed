package borg.trikeshed.chronicle

import borg.trikeshed.parse.reify

import borg.trikeshed.context.SplatFanoutDispatcherElement.DeliveryOutcome
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.splat.Splat

class ChronicleEventDecoder {

    private fun parseDeliveryOutcomeSplat(splatStr: String?): Splat<DeliveryOutcome> {
        if (splatStr == null) {
             val empty: Splat<DeliveryOutcome> = 0.j { _ : Int -> DeliveryOutcome.ERROR.j(1.0) }
             return empty
        }
        
        val entries = parseSplatString(splatStr)
        if (entries.isEmpty()) {
            val empty: Splat<DeliveryOutcome> = 0.j { _ : Int -> DeliveryOutcome.ERROR.j(1.0) }
            return empty
        }
        
        val parsedEntries = entries.mapNotNull { (k, v) ->
            val outcome = DeliveryOutcome.entries.find { it.name == k }
            if (outcome != null) outcome.j(v) else null
        }
        
        return parsedEntries.size.j { i: Int -> parsedEntries[i] }
    }

    private fun parseSplatString(str: String): List<Pair<String, Double>> {
        // e.g. {"outcome": 0.5, ...} or stringified dictionary.
        val clean = str.removePrefix("{").removeSuffix("}").trim()
        if (clean.isEmpty()) return emptyList()
        
        return clean.split(",").mapNotNull { part ->
            val kv = part.split(":")
            if (kv.size == 2) {
                val key = kv[0].trim().removeSurrounding("\"").removeSurrounding("'")
                val value = kv[1].trim().toDoubleOrNull() ?: return@mapNotNull null
                key to value
            } else {
                null
            }
        }
    }

    fun decode(bytes: ByteArray): Series<ChronicleEvent> {
        val jsonString = bytes.decodeToString()
        val parsed = reify(jsonString)
        
        val eventsList = if (parsed is List<*>) parsed else listOf(parsed)
        
        val decodedEvents = eventsList.mapNotNull { obj ->
            if (obj !is Map<*, *>) return@mapNotNull null
            
            val type = obj["type"] as? String ?: return@mapNotNull null
            
            when (type) {
                "FanoutSplat" -> {
                    val dispatcherId = (obj["dispatcher_id"] as? Number)?.toInt() ?: 0
                    val eventType = obj["event_type"] as? String ?: ""
                    val subscriberCount = (obj["subscriber_count"] as? Number)?.toInt() ?: 0
                    
                    val splatsList = obj["splats"] as? List<*> ?: emptyList<Any>()
                    val actualsList = obj["actuals"] as? List<*> ?: emptyList<Any>()
                    
                    val parsedSplatsList = mutableListOf<Join<String, Splat<DeliveryOutcome>>>()
                    for (itemObj in splatsList) {
                        val itemMap = itemObj as? Map<*, *> ?: continue
                        for ((k, v) in itemMap) {
                            val keyStr = k as? String ?: continue
                            val valStr = v as? String
                            parsedSplatsList.add(keyStr.j(parseDeliveryOutcomeSplat(valStr)))
                        }
                    }
                    val parsedSplats: Series<Join<String, Splat<DeliveryOutcome>>> = parsedSplatsList.size.j { i: Int -> parsedSplatsList[i] }
                    
                    val parsedActualsList = mutableListOf<Join<String, DeliveryOutcome>>()
                    for (itemObj in actualsList) {
                        val itemMap = itemObj as? Map<*, *> ?: continue
                        for ((k, v) in itemMap) {
                            val keyStr = k as? String ?: continue
                            val valueStr = v as? String ?: ""
                            val outcome = DeliveryOutcome.entries.find { it.name == valueStr } ?: DeliveryOutcome.ERROR
                            parsedActualsList.add(keyStr.j(outcome))
                        }
                    }
                    val parsedActuals: Series<Join<String, DeliveryOutcome>> = parsedActualsList.size.j { i: Int -> parsedActualsList[i] }
                    
                    FanoutSplat(
                        dispatcherId = dispatcherId,
                        eventType = eventType,
                        splats = parsedSplats,
                        actuals = parsedActuals,
                        subscriberCount = subscriberCount
                    )
                }
                else -> null
            }
        }
        
        return decodedEvents.size.j { i: Int -> decodedEvents[i] }
    }
}
