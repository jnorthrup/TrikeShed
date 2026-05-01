#!/bin/bash
sed -i 's/if (cpuKotlin.targets.findByName("mingwX64") == null) cpuKotlin.mingwX64()//g' gradle/macros/trikeshed-lib.gradle
