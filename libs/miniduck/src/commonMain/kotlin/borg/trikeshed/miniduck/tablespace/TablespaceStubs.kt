package borg.trikeshed.miniduck.tablespace

// Lightweight stubs for Tablespace helpers used in tests and compilation.
class Tablespace
class Region(val name: String)

interface BlockStore

class InMemoryBlockStore : BlockStore
