#!/bin/bash
find src -name "*.kt" -type f -print0 | xargs -0 sed -i 's/\.map { it.groupId }.maxOrNull() ?: 0/.maxOfOrNull { it.groupId } ?: 0/g'
find src -name "*.kt" -type f -print0 | xargs -0 sed -i 's/\.map { (it as RecordMeta).groupId }.maxOrNull() ?: 0/.maxOfOrNull { (it as RecordMeta).groupId } ?: 0/g'
find src -name "*.kt" -type f -print0 | xargs -0 sed -i 's/distinctGroups.max()/distinctGroups.maxOrNull() ?: 0/g'
