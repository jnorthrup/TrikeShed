#!/bin/bash
find src/jvmMain -name "*.kt" -exec grep -H "^class" {} \; > classes_jvm.txt
find src/commonMain -name "*.kt" -exec grep -H "^class" {} \; > classes_common.txt
