#!/bin/bash
# Run pointcut benchmarks directly from compiled classes
set -e

cd /Users/jim/work/TrikeShed

# Build if needed
./gradlew -q jvmMainClasses 2>/dev/null

# Get classpath from gradle
CP=$(./gradlew -q printRuntimeClasspath 2>/dev/null | grep -v "^$" | grep "\.jar" | tr '\n' ':')
CP="$CP:build/classes/kotlin/jvm/main:build/classes/java/jvmMain:build/libs/TrikeShed-jvm-1.0.jar"

java -cp "$CP" borg.trikeshed.lib.PointcutBenchmarksKt "$@"