@file:JvmName("RbcurseModule")

package borg.literbike.rbcurse

/**
 * RBcurse - Recursive Byte Cursor
 *
 * Protocol recognition engine with SIMD acceleration and MLIR JIT support.
 */

// Re-exports
typealias Indexed<T> = borg.literbike.rbcurse.rbcursive.Indexed<T>
typealias NetTuple = borg.literbike.rbcurse.rbcursive.NetTuple
typealias AddrPack = borg.literbike.rbcurse.rbcursive.AddrPack
typealias PortProto = borg.literbike.rbcurse.rbcursive.PortProto
typealias Protocol = borg.literbike.rbcurse.rbcursive.Protocol
typealias RbCursor = borg.literbike.rbcurse.rbcursive.RbCursor
typealias PatternMatcher = borg.literbike.rbcurse.rbcursive.PatternMatcher
typealias CachedResult = borg.literbike.rbcurse.rbcursive.CachedResult
typealias MlirJitEngine = borg.literbike.rbcurse.rbcursive.MlirJitEngine
typealias CompiledMatcher = borg.literbike.rbcurse.rbcursive.CompiledMatcher
typealias TargetMachine = borg.literbike.rbcurse.rbcursive.TargetMachine
typealias PatternAnalysis = borg.literbike.rbcurse.rbcursive.PatternAnalysis
typealias PatternType = borg.literbike.rbcurse.rbcursive.PatternType
typealias Signal = borg.literbike.rbcurse.rbcursive.Signal
typealias SignalMapped<T> = borg.literbike.rbcurse.rbcursive.SignalMapped<T>
typealias Combinator<T> = borg.literbike.rbcurse.rbcursive.Combinator<T>
typealias RbCursorConfig = borg.literbike.rbcurse.rbcursive.RbCursorConfig
typealias RbCursorImpl = borg.literbike.rbcurse.rbcursive.RbCursorImpl
