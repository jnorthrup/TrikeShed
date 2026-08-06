sed -i 's/val token = generateSecureToken()/val token: Long = generateSecureToken()/g' src/linuxMain/kotlin/borg/trikeshed/userspace/volume/LiburingVolume.linux.kt
