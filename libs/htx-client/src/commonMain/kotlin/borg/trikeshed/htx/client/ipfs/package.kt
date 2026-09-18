/**
 * HTX Client IPFS Integration Module — Common Interfaces
 * 
 * Core types and interfaces for IPFS integration.
 * Implementations are in jvmMain (depend on userspace).
 * 
 * PRELOAD.md contract:
 * - Content-addressable keys (CID) as primary index
 * - CCEK lifecycle for elements
 * - Zero-copy fanout via FanoutDispatcherElement
 *
 * TODO(htx-reactor): move IPFS resolution off the temporary HTTP gateway path and
 * onto a direct reactor protocol element so HTX can preserve IPFS identity end to end.
 */

package borg.trikeshed.htx.client.ipfs
