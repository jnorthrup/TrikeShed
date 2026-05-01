libs/torrent
=============

Purpose: Host BitTorrent protocol support and a small client API for downloads.

See @PRELOAD.md for the design notes and candidate source files to port from old/v2superbikeshed.

Goals:
- small, multiplatform core under src/commonMain
- protocol implementation under protocol/
- host and client APIs under host/ and client/
- tests and fixtures under src/commonTest and test-fixtures/
