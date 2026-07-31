package borg.trikeshed.cli.htx

import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) = runBlocking {
    // Arguments explicitly captured
    borg.trikeshed.platform.NativeMainArguments.args = args.toList()
    runAria2Cli(args)
}
