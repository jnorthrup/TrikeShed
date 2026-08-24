Wait, what if `TrikeShedGraalVfs` doesn't have an `instanceId` yet?
I need to add `instanceId: String = "global"` to the constructor, and pass it from `GraalBtrfsSupervisor`.
And I need a `private val canonicalizer = borg.trikeshed.userspace.containment.createFusePathCanonicalizer(instanceId)`.

In `relativeOf`:
```kotlin
    private fun relativeOf(path: Path): String {
        val absolute = toAbsolutePath(path)
        val text = absolute.toString().replace('\\', '/')
        require(text.startsWith('/')) { "VFS path did not normalize absolute: $path" }
        val relative = text.removePrefix("/").trimEnd('/')
        require(relative.split('/').none { it == ".." }) { "VFS traversal rejected: $path" }

        if (relative.isEmpty()) return ""
        val segments = relative.split('/')
        if (segments.size > 1 && (segments[0] == "workspace" || segments[0] == "tmp")) {
            val unmasked = canonicalizer.resolveOriginal(segments[1]) ?: segments[1]
            val originalSegments = segments.toMutableList()
            originalSegments[1] = unmasked
            return originalSegments.joinToString("/")
        }

        return relative
    }
```

In `newDirectoryStream`:
```kotlin
    override fun newDirectoryStream(path: Path, filter: DirectoryStream.Filter<in Path>): DirectoryStream<Path> {
        val relative = relativeOf(path)
        val children = btrfs.listDirectory(liveSubvolume, relative) ?: throw java.nio.file.NotDirectoryException(path.toString())
        val parent = toAbsolutePath(path)
        return object : DirectoryStream<Path> {
            private var open = true
            override fun iterator(): MutableIterator<Path> {
                check(open) { "directory stream closed" }
                val maskedChildren = if (relative == "workspace" || relative == "tmp") {
                    children.map { childName ->
                        val childRelative = if (relative.isEmpty()) childName else "$relative/$childName"
                        val isDir = btrfs.isDirectory(liveSubvolume, childRelative)
                        canonicalizer.canonicalizePath(childName, isDir)
                    }
                } else {
                    children
                }
                val accepted = maskedChildren.asSequence().map(parent::resolve).filter { child -> filter.accept(child) }.iterator()
                return object : MutableIterator<Path> {
                    override fun hasNext(): Boolean = accepted.hasNext()
                    override fun next(): Path = accepted.next()
                    override fun remove(): Unit = throw UnsupportedOperationException("read-only iterator")
                }
            }
            override fun close() { open = false }
        }
    }
```

Wait, `canonicalizer.canonicalizePath(childName, isDir)` stores the mapping in `canonicalizer` so `resolveOriginal` can work later!
So when `relativeOf` calls `resolveOriginal`, it will find it!
This requires that the guest lists the directory BEFORE accessing it. But that's standard semantics! If the guest somehow magically guesses `dir_xxxx`, it's not mapped unless it listed it first?
Actually, what if the guest accesses it without listing?
If the guest accesses it without listing, `resolveOriginal` will return null, so it will fall back to `segments[1]`.
Then BTRFS will look for `dir_xxxx`. It won't find it, because BTRFS has `remote_probe_pending_hold_swarm`.
But the guest can't magically guess `dir_xxxx` anyway. It will list it first.

Wait! What if the host puts a file, then the guest reads it. The host doesn't use `canonicalizer`!
Yes, the host uses `GraalBtrfsSupervisor.put("workspace/remote_probe_pending_hold_swarm/file.txt", bytes)`.
`GraalBtrfsSupervisor` uses `vfs.put`.
`vfs.put` uses `relativeOf`.
If `relativeOf` sees `remote_probe_pending_hold_swarm` (which is NOT a hash), `resolveOriginal` returns null. So it falls back to `remote_probe_pending_hold_swarm`.
BTRFS stores it under `workspace/remote_probe_pending_hold_swarm/file.txt`.
Then the guest runs `ls /workspace`.
`newDirectoryStream` lists `"workspace"`.
BTRFS returns `["remote_probe_pending_hold_swarm"]`.
`newDirectoryStream` masks it using `canonicalizePath("remote_probe_pending_hold_swarm", isDir)`. This populates the `canonicalizer` map with `dir_0a4f91e -> remote_probe_pending_hold_swarm`.
It yields `/workspace/dir_0a4f91e` to the guest.
The guest accesses `/workspace/dir_0a4f91e/file.txt`.
`relativeOf` sees `dir_0a4f91e`. It calls `resolveOriginal("dir_0a4f91e")`, which returns `remote_probe_pending_hold_swarm`.
It resolves the path to `workspace/remote_probe_pending_hold_swarm/file.txt`.
BTRFS reads the file!
It works flawlessly!
