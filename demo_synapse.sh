#!/usr/bin/env bash
# Demo: Show FieldSynapse events emitted by polyglot code

cd /Users/jim/work/TrikeShed
./gradlew :libs:polyglot:jvmTest --tests "GraalPointcutTddTest.L_GET*" -q 2>&1 | grep -v "WARNING:"