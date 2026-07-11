with open('src/commonMain/kotlin/borg/trikeshed/userspace/context/AsyncContextKey.kt', 'r') as f:
    content = f.read()

content = content.replace(
    'sealed class AsyncContextKey {',
    'sealed class AsyncContextKey {\n\n    object BtrfsCodecKey : AsyncContextKey(), CoroutineContext.Key<borg.trikeshed.userspace.btrfs.BtrfsCodecElement>'
)

with open('src/commonMain/kotlin/borg/trikeshed/userspace/context/AsyncContextKey.kt', 'w') as f:
    f.write(content)
