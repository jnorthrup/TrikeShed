@file:Suppress("UNUSED_IMPORT", "REDUNDANT_PUBLIC")

package borg.trikeshed.activejs

/**
 * ActiveJS — GraalVM ECMA launcher with pointcut integration.
 *
 * Public API exports:
 *   - GraalEcmaLauncher: Main entry point to launch GraalVM Polyglot context with pointcuts
 *   - GraalEcmaContext: Wrapper around GraalVM context with pointcut hooks
 *   - PointcutEvent: Pointcut event emitted from GraalVM ECMA context
 *   - PointcutOpcode: Opcode constants for pointcut kinds
 *   - PointcutEventProducer/Consumer: CCEK SPI for pointcut event transport
 */

public typealias GraalEcmaLauncher = borg.trikeshed.activejs.GraalEcmaLauncher
public typealias GraalEcmaContext = borg.trikeshed.activejs.GraalEcmaContext
public typealias PointcutEvent = borg.trikeshed.activejs.PointcutEvent
public typealias PointcutOpcode = borg.trikeshed.activejs.PointcutOpcode
public typealias PointcutEventProducer = borg.trikeshed.activejs.PointcutEventProducer
public typealias PointcutEventConsumer = borg.trikeshed.activejs.PointcutEventConsumer
public typealias PointcutEventProducerImpl = borg.trikeshed.activejs.PointcutEventProducerImpl
public typealias PointcutEventConsumerImpl = borg.trikeshed.activejs.PointcutEventConsumerImpl