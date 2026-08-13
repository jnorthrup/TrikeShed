package borg.trikeshed.btrfs

import borg.trikeshed.userspace.nio.file.spi.FileOperations

class UserspaceBtrfs(val rootDir: String, val fileOps: FileOperations) {

    init {
        if (!fileOps.exists(rootDir)) {
            fileOps.mkdirs(rootDir)
        }
        val subvolDir = fileOps.resolvePath(rootDir, "subvolumes")
        if (!fileOps.exists(subvolDir)) {
            fileOps.mkdirs(subvolDir)
        }
    }

    private fun getSubvolPath(name: String): String? {
        if (!isValidName(name)) return null
        return fileOps.resolvePath(rootDir, "subvolumes", name)
    }

    private fun isValidName(name: String): Boolean {
        if (name.isEmpty() || name == "." || name == "..") return false
        if (name.contains("/") || name.contains("\\")) return false
        return true
    }

    private fun isValidFilePath(file: String): Boolean {
        if (file.isEmpty() || file == "." || file == "..") return false
        if (file.contains("..")) return false
        if (file.startsWith("/")) return false
        return true
    }

    fun createSubvolume(name: String): Boolean {
        val path = getSubvolPath(name) ?: return false
        if (fileOps.exists(path)) return false
        fileOps.mkdirs(path)

        fileOps.write(fileOps.resolvePath(path, ".subvol_meta"), ByteArray(0))

        return true
    }

    fun deleteSubvolume(name: String): Boolean {
        val path = getSubvolPath(name) ?: return false
        if (!fileOps.exists(path)) return false
        fileOps.deleteRecursively(path)
        return true
    }

    private fun copyRecursively(src: String, dest: String) {
        if (fileOps.isDir(src)) {
            fileOps.mkdirs(dest)
            val list = fileOps.listDir(src)
            val children = list.map { it.split("/").first() }.distinct()
            for (child in children) {
                if (child.isEmpty()) continue
                copyRecursively(fileOps.resolvePath(src, child), fileOps.resolvePath(dest, child))
            }
        } else if (fileOps.isFile(src)) {
            val bytes = fileOps.readAllBytes(src)
            fileOps.write(dest, bytes.copyOf()) // defensive copy
        }
    }

    fun snapshot(sourceName: String, destName: String): Boolean {
        val srcPath = getSubvolPath(sourceName) ?: return false
        val destPath = getSubvolPath(destName) ?: return false

        if (!fileOps.exists(srcPath)) return false
        if (fileOps.exists(destPath)) return false

        copyRecursively(srcPath, destPath)

        // Mark as snapshot by creating a meta file.
        fileOps.write(fileOps.resolvePath(destPath, ".snapshot"), ByteArray(0))
        // And subvol meta
        fileOps.write(fileOps.resolvePath(destPath, ".subvol_meta"), ByteArray(0))

        return true
    }

    private fun isSnapshot(path: String): Boolean {
        return fileOps.exists(fileOps.resolvePath(path, ".snapshot"))
    }

    // A simple deterministic serialization for send/receive
    // Format:
    // [1 byte marker: 0=file, 1=dir] [2 bytes path length] [path bytes] [4 bytes content length] [content bytes] (only for files)
    private fun serializeRecursively(path: String, relPath: String, out: MutableList<Byte>) {
        if (fileOps.isFile(path)) {
            if (relPath == ".snapshot" || relPath == ".subvol_meta") return // skip meta files
            out.add(0)
            val pathBytes = relPath.encodeToByteArray()
            out.add((pathBytes.size shr 8).toByte())
            out.add(pathBytes.size.toByte())
            out.addAll(pathBytes.toList())

            val content = fileOps.readAllBytes(path)
            out.add((content.size shr 24).toByte())
            out.add((content.size shr 16).toByte())
            out.add((content.size shr 8).toByte())
            out.add(content.size.toByte())
            out.addAll(content.toList())
        } else if (fileOps.isDir(path)) {
            if (relPath.isNotEmpty()) {
                out.add(1)
                val pathBytes = relPath.encodeToByteArray()
                out.add((pathBytes.size shr 8).toByte())
                out.add(pathBytes.size.toByte())
                out.addAll(pathBytes.toList())
            }

            // deterministic order
            val children = fileOps.listDir(path).map { it.split("/").first() }.distinct().filter { it.isNotEmpty() }.sorted()
            for (child in children) {
                if (child == ".snapshot" || child == ".subvol_meta") continue
                val nextRel = if (relPath.isEmpty()) child else "$relPath/$child"
                serializeRecursively(fileOps.resolvePath(path, child), nextRel, out)
            }
        }
    }

    fun send(sourceName: String): ByteArray? {
        val srcPath = getSubvolPath(sourceName) ?: return null
        if (!fileOps.exists(srcPath)) return null

        val out = mutableListOf<Byte>()
        // add a magic header to prevent corruption
        out.addAll("BTRFS_SEND_V1".encodeToByteArray().toList())
        serializeRecursively(srcPath, "", out)
        return out.toByteArray()
    }

    fun receive(destName: String, stream: ByteArray): Boolean {
        val destPath = getSubvolPath(destName) ?: return false
        if (fileOps.exists(destPath)) return false

        val magic = "BTRFS_SEND_V1".encodeToByteArray()
        if (stream.size < magic.size) return false
        for (i in magic.indices) {
            if (stream[i] != magic[i]) return false
        }

        val tempDest = fileOps.resolvePath(rootDir, "subvolumes", "$destName.tmp")
        if (fileOps.exists(tempDest)) {
            fileOps.deleteRecursively(tempDest)
        }
        fileOps.mkdirs(tempDest)

        try {
            var i = magic.size
            while (i < stream.size) {
                val type = stream[i++]
                val pathLen = ((stream[i++].toInt() and 0xFF) shl 8) or (stream[i++].toInt() and 0xFF)
                val pathBytes = stream.copyOfRange(i, i + pathLen)
                i += pathLen
                val relPath = pathBytes.decodeToString()

                if (!isValidFilePath(relPath)) throw IllegalArgumentException("Invalid path in send stream")
                val fullPath = fileOps.resolvePath(tempDest, relPath)

                if (type == 1.toByte()) {
                    fileOps.mkdirs(fullPath)
                } else if (type == 0.toByte()) {
                    val contentLen = ((stream[i++].toInt() and 0xFF) shl 24) or
                                     ((stream[i++].toInt() and 0xFF) shl 16) or
                                     ((stream[i++].toInt() and 0xFF) shl 8) or
                                     (stream[i++].toInt() and 0xFF)
                    val contentBytes = stream.copyOfRange(i, i + contentLen)
                    i += contentLen

                    val parentDir = fullPath.substringBeforeLast('/')
                    if (!fileOps.exists(parentDir)) fileOps.mkdirs(parentDir)

                    fileOps.write(fullPath, contentBytes)
                } else {
                    throw IllegalArgumentException("Unknown type in send stream")
                }
            }
            // Add snapshot meta
            fileOps.write(fileOps.resolvePath(tempDest, ".snapshot"), ByteArray(0))
            // And subvol meta
            fileOps.write(fileOps.resolvePath(tempDest, ".subvol_meta"), ByteArray(0))

            fileOps.mkdirs(destPath)
            copyRecursively(tempDest, destPath)
            fileOps.deleteRecursively(tempDest)

            return true
        } catch (e: Exception) {
            fileOps.deleteRecursively(tempDest)
            return false
        }
    }

    fun hasSubvolume(name: String): Boolean {
        val path = getSubvolPath(name) ?: return false
        return fileOps.exists(path) && fileOps.exists(fileOps.resolvePath(path, ".subvol_meta"))
    }

    fun listSubvolumes(): List<String> {
        val subvolDir = fileOps.resolvePath(rootDir, "subvolumes")
        if (!fileOps.exists(subvolDir)) return emptyList()
        val all = fileOps.listDir(subvolDir)
        // InMemoryFileOperations listDir returns things like "alpha" and "alpha/.dir"
        // we just want top level distinct names
        val topLevel = all.map { it.split("/").first() }.distinct()
        return topLevel.filter { it.isNotEmpty() && !it.endsWith(".tmp") && fileOps.exists(fileOps.resolvePath(subvolDir, it, ".subvol_meta")) }
    }

    fun writeFile(subvol: String, file: String, content: ByteArray): Boolean {
        val subvolPath = getSubvolPath(subvol) ?: return false
        if (!fileOps.exists(subvolPath)) return false
        if (isSnapshot(subvolPath)) return false // immutable
        if (!isValidFilePath(file)) return false

        val fullPath = fileOps.resolvePath(subvolPath, file)

        val parentDir = fullPath.substringBeforeLast('/')
        if (!fileOps.exists(parentDir)) fileOps.mkdirs(parentDir)

        fileOps.write(fullPath, content.copyOf())
        return true
    }

    fun fetchFile(subvol: String, file: String): ByteArray? {
        val subvolPath = getSubvolPath(subvol) ?: return null
        if (!fileOps.exists(subvolPath)) return null
        if (!isValidFilePath(file)) return null

        val fullPath = fileOps.resolvePath(subvolPath, file)
        if (!fileOps.exists(fullPath) || fileOps.isDir(fullPath) || fullPath.endsWith(".snapshot") || fullPath.endsWith(".subvol_meta")) return null

        return fileOps.readAllBytes(fullPath).copyOf()
    }

    fun deleteFile(subvol: String, file: String): Boolean {
        val subvolPath = getSubvolPath(subvol) ?: return false
        if (!fileOps.exists(subvolPath)) return false
        if (isSnapshot(subvolPath)) return false // immutable
        if (!isValidFilePath(file)) return false

        val fullPath = fileOps.resolvePath(subvolPath, file)
        if (!fileOps.exists(fullPath)) return false

        fileOps.deleteRecursively(fullPath)
        return true
    }
}
