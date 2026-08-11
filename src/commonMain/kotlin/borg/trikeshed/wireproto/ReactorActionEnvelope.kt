package borg.trikeshed.wireproto

// Canonically defined in borg.trikeshed.reactor.endpoint (WireVerb/WirePayload).
// The two declarations were structurally identical (Nuid + verb + payload with
// contentEquals-based hashCode/equals). Keep the canonical one alive.

typealias ReactorActionEnvelope = borg.trikeshed.reactor.endpoint.ReactorActionEnvelope
