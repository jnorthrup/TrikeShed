#!/bin/bash
./gradlew jsTest --tests "borg.trikeshed.parse.confix.ConfixCborTest"
./gradlew jsTest --tests "borg.trikeshed.parse.confix.ConfixSerializationBoundaryTest"
