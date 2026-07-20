sed -i 's/signalIn.poll()/signalIn.tryReceive().getOrNull()/g' src/commonMain/kotlin/borg/trikeshed/forge/ccek/ForgeElement.kt
