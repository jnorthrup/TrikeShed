package borg.trikeshed.loom

import borg.trikeshed.ipns.JvmIpnsCrypto

// The signed-object suites over BouncyCastle Ed25519 until commonMain carries its own.
class JvmLoomAuthTest : LoomAuthTest(JvmIpnsCrypto())
