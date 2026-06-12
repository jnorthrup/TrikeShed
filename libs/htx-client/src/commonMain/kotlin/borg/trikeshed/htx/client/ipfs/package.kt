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
 */

package borg.trikeshed.htx.client.ipfs