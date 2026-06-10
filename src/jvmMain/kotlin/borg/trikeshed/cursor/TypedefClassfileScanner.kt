package borg.trikeshed.cursor

import java.io.IOException
import java.lang.classfile.ClassFile
import java.lang.classfile.ClassModel
import java.lang.classfile.CodeElement
import java.lang.classfile.MethodModel
import java.lang.classfile.attribute.CodeAttribute
import java.lang.classfile.instruction.Getfield
import java.lang.classfile.instruction.Getstatic
import java.lang.classfile.instruction.Invokedynamic
import java.lang.classfile.instruction.InvokeInstruction
import java.lang.classfile.instruction.Invokespecial
import java.lang.classfile.instruction.Invokestatic
import java.lang.classfile.instruction.Invokevirtual
import java.lang.classfile.instruction.New
import java.lang.classfile.instruction.Putfield
import java.lang.classfile.instruction.Putstatic
import java.lang.classfile.instruction.ReturnInstruction
import java.lang.classfile.instruction.Return
import java.lang.constant.ClassDesc
import java.lang.constant.MethodTypeDesc
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.extension
import kotlin.math.abs

/**
 * JEP 484 ClassFile API scanner for xvm typedef taxonomy.
 *
 * Scans class files (from filesystem or JAR) for bytecode patterns
 * indicating typedef resolution sites, and emits events to
 * TypedefProductionSystem for CRMS fold analysis.
 */
class TypedefClassfileScanner {

    private var classCount = 0
    private var siteCount = 0

    /** Scan a directory tree of class files. */
    fun scanDirectory(root: Path): TypedefScanResult {
        val files = mutableListOf<Path>()
        Files.walk(root)
            .filter { it.extension == "class" }
            .forEach { files.add(it) }

        for (file in files) {
            scanClassFile(file)
        }
        return TypedefScanResult(classCount, siteCount)
    }

    /** Scan a JAR file for class files. */
    fun scanJar(jarPath: Path): TypedefScanResult {
        val jarFile = JarFile(jarPath.toFile())
        val entries = jarFile.entries()

        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.name.endsWith(".class")) {
                val bytes = jarFile.getInputStream(entry).readAllBytes()
                scanClassBytes(entry.name, bytes)
            }
        }
        jarFile.close()
        return TypedefScanResult(classCount, siteCount)
    }

    /** Scan raw class bytes. */
    fun scanClassBytes(className: String, bytes: ByteArray) {
        val classModel = ClassFile.of().parse(bytes)
        scanClassModel(classModel)
    }

    /** Scan a class file from filesystem. */
    private fun scanClassFile(file: Path) {
        val bytes = Files.readAllBytes(file)
        val className = file.toString()
        scanClassBytes(className, bytes)
    }

    /** Analyze a parsed ClassModel for typedef-related bytecode. */
    private fun scanClassModel(classModel: ClassModel) {
        classCount++

        for (method in classModel.methods()) {
            scanMethod(classModel.thisClass(), method)
        }
    }

    /** Scan a method for typedef-related call/field patterns. */
    private fun scanMethod(classDesc: ClassDesc, method: MethodModel) {
        val codeAttribute = method.code()
        if (codeAttribute.isEmpty()) return

        val codeElements = codeAttribute.get().elementList()
        var siteOrdinal = 0

        for (i in codeElements.indices) {
            val element = codeElements[i]

            // CALL sites: invokestatic, invokevirtual, invokeinterface, invokespecial
            if (element is InvokeInstruction) {
                val opcode = when (element) {
                    is Invokestatic -> 0x10  // CALL
                    is Invokevirtual -> 0x20 // NVOK
                    is Invokedynamic -> 0x24 // NVOK_N0 variant
                    is Invokespecial -> 0x34 // CONSTR
                    else -> continue
                }

                val owner = element.owner().asInternalName()
                val name = element.name().stringValue()
                val desc = element.methodType().descriptorString()

                // Check if this is a typedef-related call
                if (isTypedefRelated(owner, name)) {
                    emitTypedefEvent(opcode.toByte(), owner, name, desc, siteOrdinal, true)
                    siteCount++
                    siteOrdinal++
                }
            }

            // NEW allocation sites
            if (element is New) {
                val owner = element.classDesc().asInternalName()
                if (isTypedefRelated(owner, "<init>")) {
                    emitTypedefEvent(0x38.toByte(), owner, "<init>", "()V", siteOrdinal, true)
                    siteCount++
                    siteOrdinal++
                }
            }

            // FIELD access sites: getfield, putfield, getstatic, putstatic
            if (element is Getfield) {
                val owner = element.owner().asInternalName()
                val name = element.name().stringValue()
                if (isTypedefRelated(owner, name)) {
                    emitTypedefEvent(0xA5.toByte(), owner, name, element.fieldType().descriptorString(), siteOrdinal, true)
                    siteCount++
                    siteOrdinal++
                }
            } else if (element is Putfield) {
                val owner = element.owner().asInternalName()
                val name = element.name().stringValue()
                if (isTypedefRelated(owner, name)) {
                    emitTypedefEvent(0xA6.toByte(), owner, name, element.fieldType().descriptorString(), siteOrdinal, true)
                    siteCount++
                    siteOrdinal++
                }
            } else if (element is Getstatic) {
                val owner = element.owner().asInternalName()
                val name = element.name().stringValue()
                if (isTypedefRelated(owner, name)) {
                    emitTypedefEvent(0xA7.toByte(), owner, name, element.fieldType().descriptorString(), siteOrdinal, true)
                    siteCount++
                    siteOrdinal++
                }
            } else if (element is Putstatic) {
                val owner = element.owner().asInternalName()
                val name = element.name().stringValue()
                if (isTypedefRelated(owner, name)) {
                    emitTypedefEvent(0xA8.toByte(), owner, name, element.fieldType().descriptorString(), siteOrdinal, true)
                    siteCount++
                    siteOrdinal++
                }
            }

            // RETURN sites
            if (element is ReturnInstruction || element is Return) {
                // Could emit return site if method is typedef-related
            }
        }
    }

    /** Check if a class/method is typedef-related. */
    private fun isTypedefRelated(owner: String, name: String): Boolean {
        // xvm typedef patterns:
        // - org.xvm.asm.constants.* (TypeConstant, FormalConstant, etc.)
        // - org.xvm.compiler.* typedef resolution
        // - org.xvm.runtime.* typedef dispatch
        // - Methods containing "typedef", "resolve", "format", "staircase"
        val ownerPatterns = listOf(
            "org/xvm/asm/constants",
            "org/xvm/compiler",
            "org/xvm/runtime",
            "org/xvm/util"
        )

        val namePatterns = listOf(
            "typedef",
            "resolve",
            "format",
            "staircase",
            "chain",
            "step",
            "lattice"
        )

        val ownerMatch = ownerPatterns.any { owner.startsWith(it) }
        val nameMatch = namePatterns.any { name.lowercase().contains(it) }

        return ownerMatch || nameMatch
    }

    /** Emit a typedef event to the TypedefProductionSystem. */
    private fun emitTypedefEvent(
        opcode: Byte,
        owner: String,
        name: String,
        desc: String,
        siteOrdinal: Int,
        success: Boolean
    ) {
        // poolId derived from class name hash for grouping
        val poolId = owner.hashCode().abs()

        // format string: "Owner.method:Desc"
        val formatName = "$owner.$name:$desc"

        // Class name for grouping
        val className = owner.replace('/', '.')

        // Record to TypedefProductionSystem
        TypedefProductionSystem.publish(
            opcode,
            className,
            formatName,
            siteOrdinal,
            0.toByte(), // depth placeholder
            false // isAfter = false for CRMS fold
        )
    }

    companion object {
        /** Quick scan of a directory and print results. */
        fun scanAndPrint(root: Path) {
            val scanner = TypedefClassfileScanner()
            val result = scanner.scanDirectory(root)
            println("=== Typedef Classfile Scan ===")
            println("Classes scanned: ${result.classCount}")
            println("Typedef sites found: ${result.siteCount}")

            // Print TypedefProductionSystem snapshot
            val wireproto = TypedefProductionSystem.drainToWireproto()
            val slab = wireproto.array()
            val events = TypedefProductionSystem.fold(slab)
            println("\nConflict cells: ${events.size}")
            events.take(20).forEach { cell ->
                println("  hash=${cell.callsiteHash} resolved=${cell.resolved} depth=${cell.depth} count=${cell.count}")
            }
        }

        /** Quick scan of a JAR and print results. */
        fun scanJarAndPrint(jarPath: Path) {
            val scanner = TypedefClassfileScanner()
            val result = scanner.scanJar(jarPath)
            println("=== Typedef JAR Scan ===")
            println("Classes scanned: ${result.classCount}")
            println("Typedef sites found: ${result.siteCount}")

            val wireproto = TypedefProductionSystem.drainToWireproto()
            val slab = wireproto.array()
            val events = TypedefProductionSystem.fold(slab)
            println("\nConflict cells: ${events.size}")
            events.take(20).forEach { cell ->
                println("  hash=${cell.callsiteHash} resolved=${cell.resolved} depth=${cell.depth} count=${cell.count}")
            }
        }
    }
}

data class TypedefScanResult(
    val classCount: Int,
    val siteCount: Int
)

/** CLI entry point. Usage: TypedefClassfileScanner <path> [--jar <jar-path>] */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Usage: TypedefClassfileScanner <directory> [--jar <jar-path>]")
        println("  Scans class files for typedef-related bytecode patterns using JEP 484 ClassFile API")
        println("  Emits events to TypedefProductionSystem for CRMS fold analysis")
        return
    }

    val scanner = TypedefClassfileScanner()
    var result: TypedefScanResult

    if (args[0] == "--jar") {
        if (args.size < 2) {
            println("--jar requires a jar path")
            return
        }
        result = scanner.scanJar(Path.of(args[1]))
    } else {
        result = scanner.scanDirectory(Path.of(args[0]))
    }

    println("=== Typedef Classfile Scan ===")
    println("Classes scanned: ${result.classCount}")
    println("Typedef sites found: ${result.siteCount}")

    val wireproto = TypedefProductionSystem.drainToWireproto()
    val slab = wireproto.array()
    val events = TypedefProductionSystem.fold(slab)
    println("\nConflict cells: ${events.size}")
    events.take(20).forEach { cell ->
        println("  hash=${cell.callsiteHash} resolved=${cell.resolved} depth=${cell.depth} count=${cell.count}")
    }
}