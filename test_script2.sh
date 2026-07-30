#!/bin/bash

# Find files related to dashboard, mesh, io_uring, and CCEK
grep -r "Dashboard" src
grep -r "mesh" src
grep -r "io_uring" src
grep -r "CCEK" src
