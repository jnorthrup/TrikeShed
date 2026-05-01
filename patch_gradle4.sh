#!/bin/bash
sed -i 's/cpuKotlin.linuxX64("linux")/cpuKotlin.linuxX64("linux") {\n            binaries.executable("interrogateCpuNative") {\n                entryPoint = "borg.trikeshed.cpucache.main"\n            }\n        }/g' gradle/macros/trikeshed-lib.gradle
sed -i 's/cpuKotlin.linuxArm64()/cpuKotlin.linuxArm64() {\n            binaries.executable("interrogateCpuNative") {\n                entryPoint = "borg.trikeshed.cpucache.main"\n            }\n        }/g' gradle/macros/trikeshed-lib.gradle
sed -i 's/cpuKotlin.macosX64()/cpuKotlin.macosX64() {\n            binaries.executable("interrogateCpuNative") {\n                entryPoint = "borg.trikeshed.cpucache.main"\n            }\n        }/g' gradle/macros/trikeshed-lib.gradle
sed -i 's/dependsOn("linkInterrogateNativeReleaseExecutable${targetName.capitalize()}")/dependsOn("linkInterrogateCpuNativeReleaseExecutable${targetName.capitalize()}")/g' gradle/macros/trikeshed-lib.gradle
sed -i 's/interrogateNativeReleaseExecutable\/interrogateNative.kexe/interrogateCpuNativeReleaseExecutable\/interrogateCpuNative.kexe/g' gradle/macros/trikeshed-lib.gradle
