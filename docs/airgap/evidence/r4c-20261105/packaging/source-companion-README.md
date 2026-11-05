# R4c source companion

This archive accompanies the bounded Linux x86-64 document runtime. It contains 65 Debian source packages (DSCs and their hash-checked archives), all 23 coordinate-matched Maven source jars, pinned Graal and Labs OpenJDK source archives, notices and inventories, and the full TrikeShed source tree at d80e698139c69e70c594940b007a22673b193937 as a Git archive.

The Debian archive hashes and lengths match their downloaded DSC records. Maven and upstream archives have retained SHA-256 identities and acquisition URLs. These records do not claim uploader-signature authentication.

The vendor JDK SOURCE metadata includes f471c3eca821+; upstream source alone does not establish every vendor modification. Maven source jars do not establish all embedded native resource build inputs. This is a retained source companion, not a declaration that the entire runtime can already be reconstructed bit-for-bit from it. The remaining vendor patch/build and native-resource audits are the next R4 gate.

The runtime is separately archived. It uses real Sulong execution with explicit native buffer ownership and copied host geometry. It is not a Native Image isolate, unified JVM/native garbage collector or general Nix evaluator.
