# R3e source update

Apply the source archives named and hashed in source-update.json in their recorded order: R4e source base, R4f update, R3c final update, R3d final update. This yields 81438b1290c417318b1ad9c2d51c73aaa07564f5 with its retained dependency sources. Apply this archive's trikeshed.patch with `git apply --binary`. A temporary Git index verified the exact target tree.

The patch retains every tracked change through targetSourceCommit, including wire geometry snapshots and fragment attachment, the R3d published evidence and D3M native Horn proof-quad inspection and note readiness fixes, plus common-map entry equality/hash correction. JVM and browser jars are rebuilt together; dependencies and native module inputs are unchanged. Later publication evidence is supplied separately. Full vendor/native source rebuild closure remains open.
