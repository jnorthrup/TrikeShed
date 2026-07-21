package nexus

import kotlin.system.exitProcess

object App {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            println("Nexus Agent")
            println("Usage: nexus.jar <command> [options]")
            println("Available commands:")
            println("  hello      Prints a hello message")
            // Future commands will be listed here
            exitProcess(0)
        }

        val command = args[0]
        val commandArgs = args.drop(1)

        when (command) {
            "hello" -> {
                val name = if (commandArgs.isNotEmpty()) commandArgs[0] else "World"
                println("Hello, \$name! From Nexus Agent.")
            }
            // Handle other commands here in the future
            else -> {
                System.err.println("Unknown command: \$command")
                System.err.println("Run without arguments to see available commands.")
                exitProcess(1)
            }
        }
    }
}

// Allow calling from command line directly if needed, e.g. java -jar nexus.jar
fun main(args: Array<String>) = App.main(args)

```
