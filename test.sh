#!/bin/bash
cat src/jvmMain/kotlin/borg/trikeshed/userspace/nio/channels/spi/JvmChannelOperations.kt | grep -C 10 "ThreadPoolExecutor("
