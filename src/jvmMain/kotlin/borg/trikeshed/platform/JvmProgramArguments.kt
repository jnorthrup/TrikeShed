package borg.trikeshed.platform

/**
 * JVM-side program arguments store. Set by [borg.trikeshed.util.oroboros.OroborosMain]
 * and native entrypoints that capture argv.
 */
var jvmProgramArguments: Array<String> = emptyArray()
