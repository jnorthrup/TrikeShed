import kotlinx.coroutines.*
import java.io.File
import sun.misc.Signal
import sun.misc.SignalHandler
import kotlin.system.exitProcess

fun main() {
    val process = ProcessBuilder("sleep", "10").start()
    
    val sigHandler = SignalHandler {
        process.destroy()
    }
    Signal.handle(Signal("TERM"), sigHandler)
    
    try {
        runBlocking {
            val job = launch(Dispatchers.IO) {
                println("Waiting for process...")
                val code = process.waitFor()
                println("Process exited with code $code")
            }
            
            val mainJob = coroutineContext[Job]
            Signal.handle(Signal("TERM")) {
                process.destroy()
                mainJob?.cancel()
            }
            
            job.join()
            delay(100000)
        }
    } catch (e: Exception) {
        println("Caught ${e.javaClass.simpleName}")
        exitProcess(0)
    }
}
