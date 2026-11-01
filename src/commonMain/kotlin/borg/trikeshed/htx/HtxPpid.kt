package borg.trikeshed.htx

/**
 * SCTP payload protocol identifiers (RFC 9260 §3.3.1) of HTX framings: one HTX message per SCTP message, so the
 * message boundary is the transport's and no reader scans for a length to find it. The low byte names the version:
 * "HTX1" is HTTP/1.1 text.
 */
const val PPID_HTX11: Int = 0x48545831
