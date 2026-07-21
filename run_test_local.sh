#!/bin/bash
./gradlew compileTestKotlinJvm jvmTest --tests '*ProjectionRegistryTest*' -x compileKotlinJvm
