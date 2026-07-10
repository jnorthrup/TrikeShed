package borg.trikeshed.forge.movie

import borg.trikeshed.dag.ReteAgent
import borg.trikeshed.graph.CausalGraphNode
import borg.trikeshed.graph.CausalGraphNodeIndex
import borg.trikeshed.panama.PanamaInduction
import borg.trikeshed.userspace.reactor.KanbanFSM
import borg.trikeshed.userspace.reactor.KanbanEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.awt.*
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Single-pass causal movie generation.
 * 
 * Pushes real Panama DAG entries → ReteAgent fires → KanbanFSM.reduce() → captures frame → next.
 * No replay, no pantomime — each frame reflects real state transition.
 */
object PanamaKanbanMovie {
    
    private const val WIDTH = 1280
    private const val HEIGHT = 720
    private const val FRAME_DELAY_MS = 500
    
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        println("=== Panama Causal Movie Generator ===")
        println("Single-pass: DAG entries → ReteAgent → KanbanFSM → MP4")
        
        // Single-pass pipeline
        val frames = generateCausalMovieFrames()
        
        // Encode to MP4 via ffmpeg
        encodeToMp4(frames)
        
        println("=== Movie complete: causal-movie.mp4 ===")
    }
    
    /**
     * Single-pass: push each Panama DAG entry, await ReteAgent fire, reduce FSM, capture frame.
     */
    private suspend fun generateCausalMovieFrames(): List<BufferedImage> = coroutineScope {
        val frames = mutableListOf<BufferedImage>()
        
        // Create causal graph index
        val graphIndex = CausalGraphNodeIndex()
        
        // Set up ReteAgent to fire on every causal node
        val fireEvents = mutableListOf<ReteAgent.Fire>()
        val reteAgent = ReteAgent.run(
            rules = listOf(
                ReteAgent.ReteRule(
                    name = "log-all-nodes",
                    predicate = { true },  // Fire on every node
                    transform = { node ->
                        ReteAgent.Fire(
                            ruleName = "log-all-nodes",
                            nodeId = node.nodeId,
                            causalKey = node.causalKey,
                            payload = node.opId,
                            agentId = "panama-movie"
                        )
                    }
                )
            ),
            onFire = { fire -> 
                fireEvents.add(fire)
                println("ReteAgent fired: ${fire.nodeId} -> ${fire.payload}")
            }
        )
        
        // Bind agent to graph
        graphIndex.bindAgent(reteAgent)
        
        // Reset KanbanFSM state
        // Note: KanbanFSM is singleton, we work with its current state
        
        // Single-pass: feed each Panama DAG entry, capture frame after each
        val dagEntries = PanamaInduction.resolveDagEntries().toList()
        println("Resolved ${dagEntries.size} DAG entries from PanamaInduction")
        
        for ((index, node) in dagEntries.withIndex()) {
            println("Processing node $index/${dagEntries.size}: ${node.nodeId}")
            
            // Add to graph index (triggers ReteAgent fire via bound agent)
            graphIndex.addOrGet(node)
            
            // Convert ReteAgent fire to KanbanEvent and reduce
            val fireForNode = fireEvents.filter { it.nodeId == node.nodeId }
            for (fire in fireForNode) {
                val event = KanbanEvent.TaxonomyNodeCreated(
                    nodeId = fire.nodeId,
                    kind = fire.payload,
                    label = fire.nodeId,
                    parentId = node.parentNodeIds.firstOrNull(),
                    timestampMs = System.currentTimeMillis()
                )
                KanbanFSM.reduce(event)
            }
            
            // Capture frame after this node's processing
            val frame = captureKanbanFrame(index, node, KanbanFSM.current())
            frames.add(frame)
            
            // Small delay for visual effect
            delay(FRAME_DELAY_MS.toLong())
        }
        
        // Cleanup
        ReteAgent.stop(reteAgent)
        
        frames
    }
    
    /**
     * Render a frame showing the Kanban board state with causal chain visualization.
     */
    private fun captureKanbanFrame(
        stepIndex: Int,
        currentNode: CausalGraphNode,
        state: borg.trikeshed.userspace.reactor.KanbanState
    ): BufferedImage {
        val image = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        
        // Background
        g.color = Color(30, 30, 40)
        g.fillRect(0, 0, WIDTH, HEIGHT)
        
        // Title bar
        g.color = Color(50, 50, 70)
        g.fillRect(0, 0, WIDTH, 60)
        g.color = Color.WHITE
        g.font = Font("SansSerif", Font.BOLD, 24)
        g.drawString("Panama Causal Movie - Single Pass", 20, 38)
        
        // Step indicator
        g.font = Font("SansSerif", Font.PLAIN, 16)
        g.drawString("Step $stepIndex", WIDTH - 120, 38)
        
        // Causal chain visualization (left panel)
        g.color = Color(60, 60, 90)
        g.fillRect(20, 80, 400, HEIGHT - 100)
        
        g.color = Color(180, 180, 220)
        g.font = Font("SansSerif", Font.BOLD, 18)
        g.drawString("Causal Chain", 40, 110)
        
        g.font = Font("Monospaced", Font.PLAIN, 14)
        val nodeId = currentNode.nodeId.take(30)
        g.drawString("→ ${currentNode.opId}", 40, 140)
        g.color = Color(100, 200, 100)
        g.drawString("  $nodeId", 40, 165)
        
        // Show parent relationships
        if (currentNode.parentNodeIds.isNotEmpty()) {
            g.color = Color(150, 150, 180)
            g.drawString("Parents:", 40, 200)
            currentNode.parentNodeIds.take(3).forEachIndexed { i, parent ->
                g.drawString("  ← $parent", 40, 225 + i * 25)
            }
        }
        
        // Kanban board state (right panel)
        val boardX = 450
        g.color = Color(60, 60, 90)
        g.fillRect(boardX, 80, WIDTH - 470, HEIGHT - 100)
        
        g.color = Color(180, 180, 220)
        g.font = Font("SansSerif", Font.BOLD, 18)
        g.drawString("Kanban State (via KanbanFSM.reduce)", boardX + 20, 110)
        
        // Column labels
        g.font = Font("SansSerif", Font.BOLD, 14)
        val columns = listOf("Backlog" to 130, "In Progress" to 330, "Done" to 530)
        for ((label, xOffset) in columns) {
            g.color = Color(100, 150, 200)
            g.drawString(label, boardX + xOffset, 150)
        }
        
        // Show taxonomy node count from state
        g.font = Font("SansSerif", Font.PLAIN, 14)
        g.color = Color(200, 200, 200)
        g.drawString("Taxonomy Nodes: ${state.taxonomyNodeCount}", boardX + 20, HEIGHT - 60)
        g.drawString("Last Event: ${state.lastEventKind}", boardX + 20, HEIGHT - 35)
        
        // Current node info box (bottom)
        g.color = Color(40, 80, 60)
        g.fillRect(20, HEIGHT - 80, WIDTH - 40, 60)
        
        g.color = Color(150, 220, 150)
        g.font = Font("Monospaced", Font.PLAIN, 12)
        val info = "Node: ${currentNode.nodeId} | Op: ${currentNode.opId} | Topo: ${currentNode.topoOrdinal}"
        g.drawString(info, 40, HEIGHT - 50)
        
        g.dispose()
        return image
    }
    
    /**
     * Encode frames to MP4 using ffmpeg.
     */
    private fun encodeToMp4(frames: List<BufferedImage>) {
        if (frames.isEmpty()) {
            println("No frames to encode!")
            return
        }
        
        val outputFile = File("causal-movie.mp4")
        val tempDir = File("temp-frames")
        tempDir.mkdirs()
        
        // Write frames as PNG
        println("Writing ${frames.size} frames...")
        frames.forEachIndexed { index, frame ->
            ImageIO.write(frame, "PNG", File(tempDir, "frame%04d.png".format(index)))
        }
        
        // Encode to MP4 via ffmpeg
        val process = ProcessBuilder(
            "ffmpeg", "-y", "-framerate", "2",
            "-i", "temp-frames/frame%04d.png",
            "-c:v", "libx264", "-pix_fmt", "yuv420p",
            "-crf", "23", outputFile.absolutePath
        ).directory(File(".")).redirectErrorStream(true).start()
        
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        
        // Cleanup temp frames
        tempDir.listFiles()?.forEach { it.delete() }
        tempDir.delete()
        
        println("Encoded to: ${outputFile.absolutePath}")
        println("File size: ${outputFile.length()} bytes")
    }
}
