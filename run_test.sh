#!/bin/bash
./gradlew jvmTest --tests "borg.trikeshed.context.nuid.NuidFanoutElementTest.testFanout16Candidates" || true
