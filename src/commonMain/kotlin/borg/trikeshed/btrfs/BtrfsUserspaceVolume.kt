package borg.trikeshed.btrfs

import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.j
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.toSeries

class BtrfsUserspaceVolume(
    private val rootDir: String,
    private val fileOps: FileOperations
) {
    init {
        if (!fileOps.exists(rootDir)) {
            fileOps.mkdirs(rootDir)
        }
        val subvolumesDir = fileOps.resolvePath(rootDir, "subvolumes")
        if (!fileOps.exists(subvolumesDir)) {
            fileOps.mkdirs(subvolumesDir)
        }
    }

    private fun sanitizeSubvolName(name: String): String? {
        if (name.isEmpty() || name == "." || name == "..") return null
        if (name.contains("/") || name.contains("\\")) return null
        return name
    }

    private fun sanitizePath(path: String): String? {
        if (path.isEmpty() || path.startsWith("/") || path.startsWith("\\")) return null
        val segments = path.split("/", "\\")
        for (seg in segments) {
            if (seg.isEmpty() || seg == "." || seg == "..") return null
        }
        return path
    }

    private fun subvolumePath(name: String): String? {
        val sanitized = sanitizeSubvolName(name) ?: return null
        return fileOps.resolvePath(rootDir, "subvolumes", sanitized)
    }

    private fun filePath(subvol: String, path: String): String? {
        val s = subvolumePath(subvol) ?: return null
        val sanitizedPath = sanitizePath(path) ?: return null
        return fileOps.resolvePath(s, sanitizedPath)
    }

    private fun getMetadataPath(subvol: String): String? {
        val sanitized = sanitizeSubvolName(subvol) ?: return null
        return fileOps.resolvePath(rootDir, "subvolumes", sanitized + ".meta")
    }
    
    private fun isImmutable(subvol: String): Boolean {
        val meta = getMetadataPath(subvol) ?: return false
        return fileOps.exists(meta)
    }

    fun createSubvolume(name: String): Boolean {
        val path = subvolumePath(name) ?: return false
        if (fileOps.exists(path)) {
            return false
        }
        fileOps.mkdirs(path)
        return true
    }

    fun hasSubvolume(name: String): Boolean {
        val path = subvolumePath(name) ?: return false
        return fileOps.exists(path)
    }

    fun listSubvolumes(): List<String> {
        val subvolumesDir = fileOps.resolvePath(rootDir, "subvolumes")
        return fileOps.listDir(subvolumesDir).filter { !it.endsWith(".meta") }
    }

    fun write(path: String, content: ByteArray): Boolean {
        val parts = path.split("/", limit = 2)
        if (parts.size != 2) return false
        val subvol = parts[0]
        val file = parts[1]
        
        if (!hasSubvolume(subvol)) return false
        if (isImmutable(subvol)) return false
        
        val fPath = filePath(subvol, file) ?: return false
        fileOps.write(fPath, content)
        return true
    }

    fun hasFile(path: String): Boolean {
        val parts = path.split("/", limit = 2)
        if (parts.size != 2) return false
        val subvol = parts[0]
        val file = parts[1]
        
        if (!hasSubvolume(subvol)) return false
        val fPath = filePath(subvol, file) ?: return false
        return fileOps.exists(fPath)
    }

    fun fetch(path: String): ByteArray? {
        val parts = path.split("/", limit = 2)
        if (parts.size != 2) return null
        val subvol = parts[0]
        val file = parts[1]
        
        if (!hasSubvolume(subvol)) return null
        val fPath = filePath(subvol, file) ?: return null
        if (!fileOps.exists(fPath)) return null
        return fileOps.readAllBytes(fPath)
    }

    fun deleteFile(path: String): Boolean {
        val parts = path.split("/", limit = 2)
        if (parts.size != 2) return false
        val subvol = parts[0]
        val file = parts[1]
        
        if (!hasSubvolume(subvol)) return false
        if (isImmutable(subvol)) return false
        
        val fPath = filePath(subvol, file) ?: return false
        if (!fileOps.exists(fPath)) return false
        
        fileOps.deleteRecursively(fPath)
        return true
    }

    fun snapshot(source: String, dest: String): Boolean {
        if (!hasSubvolume(source)) return false
        if (hasSubvolume(dest)) return false
        
        if (!createSubvolume(dest)) return false
        
        val srcPath = subvolumePath(source) ?: return false
        val dstPath = subvolumePath(dest) ?: return false
        
        fun copyRecursively(srcDir: String, dstDir: String) {
            val files = fileOps.listDir(srcDir)
            for (f in files) {
                val srcF = fileOps.resolvePath(srcDir, f)
                val dstF = fileOps.resolvePath(dstDir, f)
                if (fileOps.isFile(srcF)) {
                    fileOps.write(dstF, fileOps.readAllBytes(srcF))
                } else if (fileOps.isDir(srcF)) {
                    fileOps.mkdirs(dstF)
                    copyRecursively(srcF, dstF)
                }
            }
        }
        
        copyRecursively(srcPath, dstPath)
        
        // Mark as immutable
        val meta = getMetadataPath(dest) ?: return false
        fileOps.write(meta, ByteArray(0))
        
        return true
    }

    private fun adler32(bytes: ByteArray, length: Int): Int {
        var a = 1
        var b = 0
        for (i in 0 until length) {
            val unsigned = bytes[i].toInt() and 0xFF
            a = (a + unsigned) % 65521
            b = (b + a) % 65521
        }
        return (b shl 16) or a
    }

    fun send(subvol: String): Series<Byte>? {
        if (!hasSubvolume(subvol)) return null
        
        val path = subvolumePath(subvol) ?: return null
        
        // Wire format:
        // [Magic "TRKB" : 4B][Version 1 : Int32][Num files : Int32]
        // [Adler32 Checksum : Int32] -- over the rest of the stream
        // for each file: [Name len : Int32][Name bytes][Content len : Int32][Content bytes]
        
        val actualFiles = mutableListOf<String>()
        fun gatherFiles(dirPath: String, relPath: String) {
            val items = fileOps.listDir(dirPath)
            for (item in items) {
                val fullPath = fileOps.resolvePath(dirPath, item)
                val newRelPath = if (relPath.isEmpty()) item else "$relPath/$item"
                if (fileOps.isFile(fullPath)) {
                    actualFiles.add(newRelPath)
                } else if (fileOps.isDir(fullPath)) {
                    gatherFiles(fullPath, newRelPath)
                }
            }
        }
        gatherFiles(path, "")
        actualFiles.sort()
        
        val fileData = mutableListOf<Join<ByteArray, ByteArray>>()
        var payloadSize = 0
        for (f in actualFiles) {
            val nameBytes = f.encodeToByteArray()
            val content = fileOps.readAllBytes(fileOps.resolvePath(path, f))
            fileData.add(nameBytes j content)
            payloadSize += 4 + nameBytes.size + 4 + content.size
        }
        
        val totalSize = 16 + payloadSize
        val bytes = ByteArray(totalSize)
        var pos = 0
        
        fun putInt(i: Int) {
            bytes[pos++] = (i shr 24).toByte()
            bytes[pos++] = (i shr 16).toByte()
            bytes[pos++] = (i shr 8).toByte()
            bytes[pos++] = i.toByte()
        }
        
        // Magic
        bytes[pos++] = 'T'.code.toByte()
        bytes[pos++] = 'R'.code.toByte()
        bytes[pos++] = 'K'.code.toByte()
        bytes[pos++] = 'B'.code.toByte()
        
        // Version
        putInt(1)
        
        // Num files
        putInt(actualFiles.size)
        
        val checksumPos = pos
        putInt(0) // Placeholder for checksum
        
        val payloadStart = pos
        for (f in fileData) {
            putInt(f.a.size)
            f.a.copyInto(bytes, pos)
            pos += f.a.size
            
            putInt(f.b.size)
            f.b.copyInto(bytes, pos)
            pos += f.b.size
        }
        
        // Calculate and write checksum over payload
        val payloadBytes = bytes.copyOfRange(payloadStart, pos)
        val checksum = adler32(payloadBytes, payloadBytes.size)
        
        pos = checksumPos
        putInt(checksum)
        
        return bytes.toSeries()
    }

    fun receive(dest: String, stream: Series<Byte>): Boolean {
        if (hasSubvolume(dest)) return false
        
        val bytes = ByteArray(stream.a)
        for (i in 0 until stream.a) {
            bytes[i] = stream.b(i)
        }
        
        if (bytes.size < 16) return false
        
        var pos = 0
        fun readInt(): Int {
            if (pos + 4 > bytes.size) throw IllegalArgumentException("Stream truncated")
            val b1 = bytes[pos].toInt() and 0xFF
            val b2 = bytes[pos+1].toInt() and 0xFF
            val b3 = bytes[pos+2].toInt() and 0xFF
            val b4 = bytes[pos+3].toInt() and 0xFF
            pos += 4
            return (b1 shl 24) or (b2 shl 16) or (b3 shl 8) or b4
        }
        
        try {
            val magic1 = bytes[pos++].toInt().toChar()
            val magic2 = bytes[pos++].toInt().toChar()
            val magic3 = bytes[pos++].toInt().toChar()
            val magic4 = bytes[pos++].toInt().toChar()
            
            if (magic1 != 'T' || magic2 != 'R' || magic3 != 'K' || magic4 != 'B') {
                return false
            }
            
            val version = readInt()
            if (version != 1) return false
            
            val numFiles = readInt()
            if (numFiles < 0) return false
            
            val expectedChecksum = readInt()
            
            val payloadBytes = bytes.copyOfRange(pos, bytes.size)
            val actualChecksum = adler32(payloadBytes, payloadBytes.size)
            if (expectedChecksum != actualChecksum) return false
            
            val extracted = mutableListOf<Join<String, ByteArray>>()
            val seenPaths = mutableSetOf<String>()
            
            for (i in 0 until numFiles) {
                val nameLen = readInt()
                if (nameLen < 0 || pos + nameLen > bytes.size) throw IllegalArgumentException("Malformed name len")
                val name = bytes.copyOfRange(pos, pos + nameLen).decodeToString()
                pos += nameLen
                
                if (sanitizePath(name) == null) throw IllegalArgumentException("Invalid path in stream")
                if (seenPaths.contains(name)) throw IllegalArgumentException("Duplicate path in stream")
                seenPaths.add(name)
                
                val contentLen = readInt()
                if (contentLen < 0 || pos + contentLen > bytes.size) throw IllegalArgumentException("Malformed content len")
                val content = bytes.copyOfRange(pos, pos + contentLen)
                pos += contentLen
                
                extracted.add(name j content)
            }
            
            if (pos != bytes.size) throw IllegalArgumentException("Trailing bytes in stream")
            
            // Atomic commit after successful validation
            if (!createSubvolume(dest)) return false
            
            try {
                for (f in extracted) {
                    val fPath = filePath(dest, f.a) ?: throw IllegalArgumentException("Invalid path")
                    fileOps.write(fPath, f.b)
                }
                // Mark as immutable snapshot
                val meta = getMetadataPath(dest) ?: throw IllegalArgumentException("Invalid subvol name")
                fileOps.write(meta, ByteArray(0))
            } catch (e: Exception) {
                deleteSubvolume(dest)
                return false
            }
            
            return true
        } catch (e: Exception) {
            return false
        }
    }

    fun deleteSubvolume(name: String): Boolean {
        if (!hasSubvolume(name)) return false
        val path = subvolumePath(name) ?: return false
        fileOps.deleteRecursively(path)
        val meta = getMetadataPath(name)
        if (meta != null && fileOps.exists(meta)) {
            fileOps.deleteRecursively(meta)
        }
        return true
    }
}
