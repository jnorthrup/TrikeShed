package borg.trikeshed.cli.htx

import borg.trikeshed.htx.*
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.spi.NioSupervisor

suspend fun runAria2Cli(args: Array<String>) {
    try {
        val options = HtxAria2CliArgs.parse(args)

        val supervisor = NioSupervisor()
        supervisor.open()

        val reactor = openHtxReactorElement(nioSupervisor = supervisor)
        val clientReactor = openHtxClientReactorElement(routeService = reactor, nioSupervisor = supervisor)

        val fileOps = supervisor.service<FileOperations>() ?: error("FileOperations missing")

        val engine = HtxAria2Engine(options, clientReactor, fileOps)
        engine.execute()

        clientReactor.close()
        reactor.close()
        supervisor.close()
    } catch (e: UnsupportedOperationException) {
        println(e.message)
    } catch (e: IllegalArgumentException) {
        println("Error: ${e.message}")
        println("Use --help for usage.")
    }
}
