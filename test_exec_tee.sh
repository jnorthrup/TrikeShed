#!/bin/bash
exec > >(tee -a test.log) 2>&1
exec echo "hello world"
