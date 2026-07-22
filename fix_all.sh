#!/bin/bash
set -e

# Run tests to make sure there are no issues.
./gradlew jvmTest --tests "borg.trikeshed.ConflictMarkerAuditTest"
