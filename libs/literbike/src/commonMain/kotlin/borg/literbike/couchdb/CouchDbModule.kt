/**
 * CouchDB module
 *
 * Provides CouchDB-compatible API emulation with:
 * - Document CRUD operations
 * - View server for map/reduce
 * - Attachment management
 * - Cursor-based pagination
 * - IPFS-backed storage
 * - M2M communication
 * - Tensor operations
 * - Git synchronization
 */
package borg.literbike.couchdb

// Re-export commonly used types
public typealias CouchDocument = Document
public typealias CouchError = CouchError
public typealias CouchResult<T> = Result<T>
