package borg.trikeshed.loom

import borg.trikeshed.ipns.JvmIpnsCrypto

// The signed-object suites over BouncyCastle Ed25519 until commonMain carries its own.
class JvmLoomAuthTest : LoomAuthTest(JvmIpnsCrypto())

class JvmLoomCustodyTest : LoomCustodyTest(JvmIpnsCrypto())

class JvmLoomSettlementTest : LoomSettlementTest(JvmIpnsCrypto())

class JvmLoomLedgerTest : LoomLedgerTest(JvmIpnsCrypto())
