# R3d source update

Apply the source archives named and hashed in source-update.json in their recorded order: R4e source base, R4f update, R3c final update. This yields 792a14c3b982956df3a139661797e171cba9b963 with its retained dependency sources. Apply this archive's trikeshed.patch with `git apply --binary`. A temporary Git index verified the exact target tree.

The patch retains every tracked change through targetSourceCommit, including the two browser rendering changes and the D3M local English corpus search contract/preset. JVM and browser jars are rebuilt together; dependencies and native module inputs are unchanged. Later publication evidence is supplied separately. Full vendor/native source rebuild closure remains open.
