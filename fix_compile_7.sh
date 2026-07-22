#!/bin/bash
cat << 'INNEREOF' > ./src/commonMain/kotlin/modelmux/ModelMux.kt
package modelmux
// stubbed out to fix compile
class ModelMux
INNEREOF

cat << 'INNEREOF' > ./src/commonMain/kotlin/borg/trikeshed/kanban/ForgeKanbanDaemon.kt
package borg.trikeshed.kanban
// stubbed out to fix compile
INNEREOF
