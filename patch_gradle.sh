#!/bin/bash
sed -i 's/if (cpuKotlin.targets.findByName("linuxArm64") == null) cpuKotlin.linuxArm64()/if (cpuKotlin.targets.findByName("linuxArm64") == null) cpuKotlin.linuxArm64()\n        if (cpuKotlin.targets.findByName("mingwX64") == null) cpuKotlin.mingwX64()/g' gradle/macros/trikeshed-lib.gradle
