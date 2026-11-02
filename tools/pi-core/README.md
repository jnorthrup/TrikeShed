# Graal Pi bundle

The runtime embeds the actual pinned upstream Pi Agent, with a local TS transport entry. Rebuild it from a clean checkout at the manifest revision and an explicit local webpack module:

```sh
node tools/pi-core/build.cjs /path/to/pi-mono /path/to/node_modules/webpack
```

The builder uses Node's built-in TypeScript stripping (Node 24.19.0 and webpack 5.108.1 are the recorded toolchain). It rejects a different upstream revision or modified/untracked package inputs. It copies inputs into a temporary tree, bundles with stable natural module IDs, then regenerates the bundle, manifest input hashes, and PiModelPort's admitted bundle digest. It performs no dependency installation or network requests. Existing license and notice files remain unchanged.

To verify reproducibility, run the command twice with identical inputs and compare `manifest.json`'s bundle SHA256 and input list. The upstream agent, schema, and transport sources are recorded individually in that list.

## Source tool grant

The host constructs `PiWorkspace(root, paths)` and passes it explicitly to `PiWire` as `workspace`. Paths must be existing, explicitly named non-hidden TS/JS files; at most 16 paths can be granted. Reads and resulting writes are bounded to 16 KiB and decoded/encoded as strict UTF-8. Secure directory handles and NOFOLLOW reject parent and leaf symlinks; unsupported filesystems fail closed. There is no shell, directory listing, arbitrary write, or guest access to provider credentials.

`read({path})` records the exact source content. `edit({path, oldText, newText})` requires the file still equal that read and exactly one occurrence of oldText. Successful edits return a textual before/after patch in the upstream tool transcript, which the Pi page displays. Editing model-entry.ts changes the actual Pi transport source; a host-owned rebuild and restart are required to execute that changed source.

Operations are serialized within each workspace grant. The content check detects changes made before that check; it is not an atomic filesystem compare-and-swap. An external editor or another independent grant can write concurrently after the check. Use a dedicated owned workspace without concurrent writers for the self-edit demonstration. The tool does not promise transaction isolation or rollback after an I/O failure.
