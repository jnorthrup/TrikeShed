#!/bin/bash
sed -i 's/cpuKotlin.linuxX64("linux") {/cpuKotlin.linuxX64("linux") {\n            binaries.executable("interrogateCpuNative") {\n                entryPoint = "borg.trikeshed.cpucache.main"\n            }\n        }/g' gradle/macros/trikeshed-lib.gradle
