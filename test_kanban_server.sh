#!/bin/bash
./gradlew compileKotlinJvm --no-daemon -Dorg.gradle.jvmargs="-XX:ReservedCodeCacheSize=1g"
