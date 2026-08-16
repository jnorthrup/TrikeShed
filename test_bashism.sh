#!/bin/bash
exec > >(tee -a test.log) 2>&1
echo "hello from bash"
exec sleep 1
