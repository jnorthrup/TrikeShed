#!/bin/bash
find src -type f -exec grep -l "CacheStoreJvm" {} \; | while read file; do
  echo "--- $file ---"
  grep -n -C 2 "CacheStoreJvm" "$file"
done
