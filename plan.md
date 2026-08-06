1. **Optimize Hash Function in FunnelHashMap**
   - Replace the expensive `Sha256Pure` cryptographic hash in `FunnelHashMap.hash64` with a fast, deterministic bitwise mix (e.g., `mix64(key.hashCode(), seed)`). This adheres to "Collections DRY: use honest names and cheap hashing for probes" and avoids expensive allocations.
   - Use `ushr` for bitwise operations as per memory guidelines.

2. **Verify changes**
   - Ensure the modified code is correct and the hash function works properly.

3. **Run Pre-Commit Checks**
   - Run tests specifically for `FunnelHashMap` using `./gradlew jvmTest --tests "*FunnelHashMapTest*" --no-daemon`.

4. **Submit PR**
   - Create PR with required Bolt format.
