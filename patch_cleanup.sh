sed -i '/private val fanoutChannel = Channel<ReactorAction>/,+3d' src/commonMain/kotlin/borg/trikeshed/lcnc/reactor/LcncIngestPipeline.kt
sed -i '/private val parseSemaphore = Semaphore(config.maxConcurrentParses)/d' src/commonMain/kotlin/borg/trikeshed/lcnc/reactor/LcncIngestPipeline.kt
