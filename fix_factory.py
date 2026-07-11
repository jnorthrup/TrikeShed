with open('src/commonMain/kotlin/borg/trikeshed/couch/isam/ConfixIsamFactory.kt', 'r') as f:
    content = f.read()

new_content = content.replace(
    'import borg.trikeshed.userspace.nio.file.spi.FileOperations',
    'import borg.trikeshed.userspace.nio.file.spi.FileOperations\nimport borg.trikeshed.couch.isam.ConfixIsamIsomorphism\nimport borg.trikeshed.isam.RecordMeta'
)

with open('src/commonMain/kotlin/borg/trikeshed/couch/isam/ConfixIsamFactory.kt', 'w') as f:
    f.write(new_content)
