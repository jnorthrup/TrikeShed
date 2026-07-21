#!/bin/bash
./gradlew compileKotlinJvm --no-daemon -Dorg.gradle.jvmargs="-XX:ReservedCodeCacheSize=1g" 2>&1 | grep "e: file://" | cut -d: -f2 | cut -c3- | sort -u > bad_files.txt
for file in $(cat bad_files.txt); do
  echo "rm $file"
  rm -f "$file"
done
./gradlew compileKotlinJvm --no-daemon -Dorg.gradle.jvmargs="-XX:ReservedCodeCacheSize=1g"
