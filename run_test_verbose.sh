#!/bin/bash
./gradlew jvmTest --tests "borg.trikeshed.daemon.OroborosDaemonCycleTraceTest" --no-daemon -i > test_verbose.log 2>&1
