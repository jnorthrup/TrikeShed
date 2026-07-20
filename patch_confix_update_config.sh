sed -i 's/fun updateConfig(newConfig: ConfixSinkConfig)/suspend fun updateConfig(newConfig: ConfixSinkConfig)/g' src/commonMain/kotlin/borg/trikeshed/reactor/ConfixSinkElement.kt
