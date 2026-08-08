#!/bin/bash
find src/jvmMain -name "*.kt" -exec grep -H "import" {} \; | awk '{print $2}' | sort -u > imports_jvm.txt
find src/commonMain -name "*.kt" -exec grep -H "import" {} \; | awk '{print $2}' | sort -u > imports_common.txt
