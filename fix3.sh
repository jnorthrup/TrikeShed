#!/bin/bash
# The issue mentioned opening multiple HtxElements (one per concurrent operation class) and removing the htxMutex.
# But HtxKey is a singleton key. I can't put multiple HtxElements into the context with the same key.
# Instead, the FlywheelDriver should just open new HtxElements or OroborosDaemon should pass them to startReactiveCycle.
