#!/bin/bash

while true; do
    ./gradlew build
    if [ $? -eq 0 ]; then
        echo "Build succeeded!"
        break
    else
        echo "Build failed. Retrying..."
    fi
done
