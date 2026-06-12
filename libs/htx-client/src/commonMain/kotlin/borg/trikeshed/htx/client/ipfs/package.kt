/**
 * HTX Client IPFS Integration Module
 * 
 * Self-contained IPFS stack integrated with CCEK reactor:
 * - CidAndStore: CID (Content Identifier) and BlockStore
 * - DhtService: Kademlia DHT with iterative routing
 * - HtxDhtTransport: DHT transport via io_uring/FunctionalUringFacade
 * - BitswapEngine: IPFS Block Exchange Protocol
 * - HtxBitswapTransport: Bitswap via HTX Channels
 * - CarIntegration: CAR (Content Addressable Archive) v1/v2
 * - CakManager: Unified Content-Addressable Keys Manager
 * 
 * All components follow PRELOAD.md contracts:
 * - CCEK lifecycle (CREATED → OPEN → ACTIVE → DRAINING → CLOSED)
 * - Zero-copy fanout via FanoutDispatcherElement
 * - Cold Series α-projection for streaming operations
 * - Content-addressable keys (CID) as primary index
 */

package borg.trikeshed.htx.client.ipfs