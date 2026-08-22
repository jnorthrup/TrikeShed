package borg.trikeshed.pointcut

import borg.trikeshed.cursor.TypedefProductionSystem

object KataSandboxRunner {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) return
        val language = args[0]
        val sourceCode = args.drop(1).joinToString(" ")

        TypedefProductionSystem.active = true
        val runner = SubgraalPointcutRunner(statementLimit = 10000)
        try {
            runner.eval(language, sourceCode)
        } catch (e: Exception) {
            // Ignore exception, output coordinates captured before kill
        } finally {
            TypedefProductionSystem.flush("exit")
            val wire = TypedefProductionSystem.synapseRing.drainToWireproto()

            val bytes = ByteArray(wire.remaining())
            wire.get(bytes)
            System.out.write("KATA".encodeToByteArray())
            System.out.write(bytes)
            System.out.flush()
            try {
                runner.close()
            } catch (e: Exception) {}
        }
    }
}
