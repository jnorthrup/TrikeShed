with open('src/commonMain/kotlin/borg/trikeshed/userspace/context/AsyncContextKey.kt', 'r') as f:
    content = f.read()

content = content.replace(
    'object BtrfsCodecKey : AsyncContextKey(), CoroutineContext.Key<borg.trikeshed.userspace.btrfs.BtrfsCodecElement>',
    'object BtrfsCodecKey : AsyncContextKey(), CoroutineContext.Key<borg.trikeshed.userspace.btrfs.BtrfsCodecElement>\n\n    object LiburingKey : AsyncContextKey(), CoroutineContext.Key<borg.trikeshed.userspace.LiburingElement>'
)

with open('src/commonMain/kotlin/borg/trikeshed/userspace/context/AsyncContextKey.kt', 'w') as f:
    f.write(content)
