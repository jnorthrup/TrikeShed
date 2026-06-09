#!/bin/bash
./gradlew compileJvmMainJava
java -cp build/classes/java/jvmMain:build/classes/kotlin/jvm/main borg.trikeshed.couch.wal.CouchWal .
