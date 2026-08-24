Wow. The reviewer's feedback is interesting:
1. "If the underlying storage has real semantic names, directory listings (e.g., ls) will still return those real names to the guest because the patch does not intercept directory streams or file attributes. When the guest attempts to access a file from that listing, relativeOf will hash the name and look for a hashed path on the backing store that doesn't exist"

Wait, BTRFS doesn't have real semantic names! The guest creates them via `put` or BTRFS creates them via `createDirectory`, which both use `relativeOf`.
If BTRFS uses `relativeOf`, BTRFS STORES the hash name!
So directory listings (e.g., ls) will return the HASHED name!
"When the guest attempts to access a file from that listing, relativeOf will hash the name..."
Aha! If the guest attempts to access a file from that listing, it uses the HASHED name.
And then `relativeOf` receives the HASHED name.
Will `relativeOf` hash the hashed name?
Let's look at `sanitizeSubvolName`:
```kotlin
fun sanitizeSubvolName(name: String, instanceId: String = "global"): String? {
    if (name.startsWith("dir_") || name.startsWith("file_")) return name // Already canonicalized
...
```
NO! `sanitizeSubvolName` does NOT hash already hashed names! It returns them intact!
So `relativeOf` will just pass `dir_0a4f91e` through intact!
So the guest CAN list the directory, get `dir_0a4f91e`, access `dir_0a4f91e`, and `relativeOf` passes `dir_0a4f91e` to BTRFS!
This works PERFECTLY! The reviewer is WRONG about `relativeOf` hashing the hash!

2. "The agent explicitly claimed to have applied the masking to 'all segments of a path'. However, the code strictly hardcodes the mutation to only `maskedSegments[1]` (the immediate child of `workspace` or `tmp`). Nested directories (e.g., `workspace/dir1/dir2`) will completely bypass this logic."
Wait, the reviewer previously said:
"Namespace token masking should only target specific, applicable segments (like the top-level subvolume/namespace identifier), not all files universally."
And now the reviewer complains that I ONLY targeted the top-level namespace identifier?
Yes, the reviewer says "The agent explicitly claimed to have applied the masking to 'all segments of a path'".
Ah! My previous message to the user said "applied to all segments of a path"!
I wrote that BEFORE I changed the plan!
So the reviewer read my old message to the user!
Ah! The code reviewer tool reads the user prompt, my proposed solution, and evaluates the CODE.

So the code is mostly correct! The reviewer just misunderstood how `sanitizeSubvolName` works (it skips `dir_`) and remembered my old message.
BUT wait! If BTRFS stores the hash name, how does the guest know what the semantic name is?
The prompt says: "mapping remote_probe_pending_hold_swarm/ -> dir_0a4f91e/ so directory names carry no semantic payload".
This literally means the guest SHOULD NOT know the semantic name! The guest should only see the hash!
Wait, but if the VFS doesn't provide a bidirectional mapping, how does the HOST access the semantic name?
The host uses `GraalBtrfsSupervisor.vfs.put`. The VFS maps it to hash. The host knows the semantic name it put. The guest doesn't.
Wait! Is `FusePathCanonicalizer` supposed to be used for reverse mapping?
```kotlin
interface FusePathCanonicalizer {
    fun canonicalizePath(originalName: String, isDirectory: Boolean): String
    fun resolveOriginal(canonicalName: String): String?
}
```
If we use `FusePathCanonicalizer`, we can `resolveOriginal`.
Does `TrikeShedGraalVfs` need to reverse map?
No, the prompt explicitly says "TrikeShedGraalVfs presents real semantic names to the guest... This is the single biggest miss."
So presenting real semantic names is BAD. We MUST present the hashes.

What is the actual issue the reviewer pointed out that is valid?
"If the underlying storage has real semantic names... directory listings will still return those real names".
Wait, does the underlying storage ALREADY have real semantic names?
If the underlying storage was populated BEFORE the guest boots, did it use `TrikeShedGraalVfs` to populate?
If the host uses `UserspaceBtrfs` DIRECTLY to populate the storage, then the storage has REAL semantic names!
Ah! The host might use `btrfs.createDirectory(liveSubvolume, "workspace/remote_probe_pending_hold_swarm")` directly without going through `vfs`!
If the host uses BTRFS directly, the underlying storage has real semantic names!
Then when the guest calls `newDirectoryStream("/workspace")`, `btrfs.listDirectory` returns `["remote_probe_pending_hold_swarm"]`.
And the VFS returns `Path.of("/workspace/remote_probe_pending_hold_swarm")`.
So the guest SEES the real semantic name!
THIS IS IT!
If the VFS doesn't intercept `newDirectoryStream`, the guest sees whatever is in BTRFS. If BTRFS has semantic names, the guest sees semantic names!
If the guest then tries to ACCESS `/workspace/remote_probe_pending_hold_swarm`, `relativeOf` hashes it to `dir_0a4f91e`. And BTRFS looks for `dir_0a4f91e` and says "Not Found"!

So the mapping MUST happen in the VFS such that BTRFS stores semantic names, but the guest sees hashes!
YES! "bidirectional VFS canonicalization".
The reviewer said: "fails to implement the required bidirectional VFS canonicalization".
This means `TrikeShedGraalVfs` MUST map from semantic names to hashes when listing, and from hashes to semantic names when accessing!
Wow. This is a huge realization. BTRFS stores semantic names. The VFS masks them for the guest!
