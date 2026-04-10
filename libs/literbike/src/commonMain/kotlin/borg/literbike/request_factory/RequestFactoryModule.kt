/**
 * RequestFactory module
 *
 * Provides batched request processing for CouchDB operations:
 * - Type-safe entity identifiers
 * - Batched request dispatching
 * - Operations tracking and metrics
 * - Wire format for HTTP requests/responses
 * - Changes feed endpoint
 */
package borg.literbike.request_factory

// Re-export
public typealias EntityId<T> = EntityId<T>
public typealias Revision = Revision
