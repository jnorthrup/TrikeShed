#!/bin/bash
find src -name "*.kt" -type f -print0 | xargs -0 sed -i 's/result.view.map { it.groupId }.toSet()/result.view.map { it.groupId }.toSet()/g'
