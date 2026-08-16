#!/bin/bash
find src -name "*.kt" -type f -print0 | xargs -0 sed -i 's/distinctGroups.max()/distinctGroups.maxOrNull() ?: 0/g'
