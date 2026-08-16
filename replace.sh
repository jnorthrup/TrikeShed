#!/bin/bash
awk '
/archive session \$sessionId:/ {
    print
    getline
    print
    getline
    print
    print "        return archiveCount"
    next
}
{ print }
' ./src/jvmMain/kotlin/borg/trikeshed/jules/FlywheelDriver.kt > tmp.kt && mv tmp.kt ./src/jvmMain/kotlin/borg/trikeshed/jules/FlywheelDriver.kt
