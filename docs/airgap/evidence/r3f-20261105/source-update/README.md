# R3f source update

Apply the exact source archives named and hashed in source-update.json: R4e source base, R4f update, R3c final update, R3d final update, R3e final update. This yields 5fdd0472c115922287ba46a4977cb1134aa9017a. Do not separately apply the R3e strict-row validation supplement; its added assertions are included in this full patch.

Apply trikeshed.patch with git apply --binary. The temporary index verifies the exact target tree, including the R3e publication evidence, later strict-row assertions, and R3f page-owned wire editing and verified aggregate output projection. The runtime overlay changes only the web jar and manifests; JVM, dependency and native bytes remain identical to the R3e installation. Later R3f publication evidence is supplied separately. Full vendor/native source rebuild closure remains open.
